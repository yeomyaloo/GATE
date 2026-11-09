package io.gate.authz.spec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * OpenAPI 3.0 명세(JSON·YAML)에서 권한 검사에 필요한 것만 읽는다.
 * 작업(메서드·경로·경로 파라미터·인증 필요 여부·요청 본문 스키마)과, 객체 단위 작업을 자원별로 묶은 결과.
 */
public final class ApiSpec {

    /** API 작업 하나. requestSchema는 $ref를 풀어 둔 요청 본문 스키마(없으면 null). */
    public record Operation(String method, String path, List<String> pathParams, boolean requiresAuth,
            JsonNode requestSchema, String operationId) {

        public String label() {
            return method + " " + path;
        }
    }

    /**
     * 객체 단위 자원: {@code /api/orders/{id}} 같은 항목 경로와, 같은 앞부분의 생성·목록 작업.
     */
    public record Resource(String collectionPath, String itemPath, String idParam, Optional<Operation> create,
            Optional<Operation> list, List<Operation> itemOperations) {
    }

    static final Set<String> METHODS = Set.of("get", "put", "post", "delete", "patch");
    private static final Pattern PARAM = Pattern.compile("\\{([^}/]+)}");
    private static final Pattern ITEM_PATH = Pattern.compile("^(.*)/\\{([^}/]+)}$");
    private static final int MAX_REF_DEPTH = 10;

    private final JsonNode root;
    private final List<Operation> operations;

    private ApiSpec(JsonNode root) {
        this.root = root;
        this.operations = readOperations();
    }

    /** YAML은 JSON의 상위 집합이라 같은 파서로 둘 다 읽는다. */
    public static ApiSpec parse(String text) {
        JsonNode root = YAMLMapper.builder().build().readTree(text);
        if (root == null || !root.path("paths").isObject()) {
            throw new IllegalArgumentException("not an OpenAPI document: 'paths' is missing");
        }
        String version = root.path("openapi").asString("");
        if (!version.startsWith("3.")) {
            throw new IllegalArgumentException("only OpenAPI 3.x is supported (found '" + version + "')");
        }
        return new ApiSpec(root);
    }

    public List<Operation> operations() {
        return operations;
    }

    public Optional<Operation> find(String method, String path) {
        return operations.stream().filter(o -> o.method().equals(method) && o.path().equals(path)).findFirst();
    }

    /** 경로 마지막 칸이 파라미터 하나인 작업을 자원별로 묶는다. 앞부분에 다른 파라미터가 있는 경로는 다루지 않는다. */
    public List<Resource> resources() {
        Map<String, List<Operation>> byItem = new LinkedHashMap<>();
        for (Operation op : operations) {
            Matcher m = ITEM_PATH.matcher(op.path());
            if (m.matches() && !PARAM.matcher(m.group(1)).find()) {
                byItem.computeIfAbsent(op.path(), k -> new ArrayList<>()).add(op);
            }
        }
        List<Resource> resources = new ArrayList<>();
        byItem.forEach((itemPath, ops) -> {
            Matcher m = ITEM_PATH.matcher(itemPath);
            m.matches();
            String collection = m.group(1).isEmpty() ? "/" : m.group(1);
            resources.add(new Resource(collection, itemPath, m.group(2), find("POST", collection),
                    find("GET", collection), List.copyOf(ops)));
        });
        return resources;
    }

    private List<Operation> readOperations() {
        boolean globalAuth = hasRequirement(root.path("security"));
        List<Operation> ops = new ArrayList<>();
        for (Map.Entry<String, JsonNode> path : root.path("paths").properties()) {
            List<String> params = new ArrayList<>();
            Matcher m = PARAM.matcher(path.getKey());
            while (m.find()) {
                params.add(m.group(1));
            }
            for (Map.Entry<String, JsonNode> item : path.getValue().properties()) {
                String method = item.getKey().toLowerCase(Locale.ROOT);
                if (!METHODS.contains(method)) {
                    continue;
                }
                JsonNode op = item.getValue();
                boolean auth = op.has("security") ? hasRequirement(op.path("security")) : globalAuth;
                JsonNode schema = resolve(op.path("requestBody").path("content").path("application/json")
                        .path("schema"), 0);
                ops.add(new Operation(method.toUpperCase(Locale.ROOT), path.getKey(), List.copyOf(params), auth,
                        schema == null || schema.isMissingNode() ? null : schema,
                        op.path("operationId").asString(null)));
            }
        }
        return List.copyOf(ops);
    }

    /** {@code security: []}는 "인증 없음", 비어 있지 않은 요구 사항이 하나라도 있으면 인증 필요. */
    private static boolean hasRequirement(JsonNode security) {
        if (!security.isArray()) {
            return false;
        }
        for (JsonNode requirement : security) {
            if (requirement.isObject() && !requirement.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** 같은 문서 안의 {@code #/components/...} 참조를 풀어 준다(중첩 포함). 외부 파일 참조는 다루지 않는다. */
    public JsonNode resolve(JsonNode schema, int depth) {
        if (schema == null || schema.isMissingNode() || depth > MAX_REF_DEPTH) {
            return schema;
        }
        String ref = schema.path("$ref").asString(null);
        if (ref == null) {
            return schema;
        }
        if (!ref.startsWith("#/")) {
            throw new IllegalArgumentException("external $ref is not supported: " + ref);
        }
        JsonNode target = root;
        for (String part : ref.substring(2).split("/")) {
            target = target.path(part.replace("~1", "/").replace("~0", "~"));
        }
        if (target.isMissingNode()) {
            throw new IllegalArgumentException("unresolved $ref: " + ref);
        }
        return resolve(target, depth + 1);
    }
}
