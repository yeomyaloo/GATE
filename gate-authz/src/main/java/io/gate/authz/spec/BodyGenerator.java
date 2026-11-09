package io.gate.authz.spec;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * 요청 본문 스키마에서 최소한의 본문을 만든다.
 * 명세의 {@code example}을 우선 쓰고, 없으면 필수 필드만 형식에 맞는 기본값으로 채운다.
 */
public final class BodyGenerator {

    static final String TEXT = "created by gate-authz";
    private static final int MAX_DEPTH = 5;
    private static final JsonNodeFactory NODES = JsonMapper.builder().build().getNodeFactory();

    private final ApiSpec spec;

    public BodyGenerator(ApiSpec spec) {
        this.spec = spec;
    }

    public JsonNode generate(JsonNode schema) {
        return value(spec.resolve(schema, 0), 0);
    }

    private JsonNode value(JsonNode schema, int depth) {
        if (schema == null || schema.isMissingNode()) {
            return NODES.nullNode();
        }
        schema = spec.resolve(schema, 0);
        if (schema.has("example")) {
            return schema.get("example");
        }
        if (schema.path("enum").isArray() && !schema.path("enum").isEmpty()) {
            return schema.path("enum").get(0);
        }
        String type = schema.path("type").asString(schema.has("properties") ? "object" : "string");
        switch (type) {
            case "object":
                return object(schema, depth);
            case "array":
                ArrayNode arr = NODES.arrayNode();
                if (depth < MAX_DEPTH && schema.path("minItems").asInt(0) > 0) {
                    arr.add(value(schema.path("items"), depth + 1));
                }
                return arr;
            case "integer":
                return NODES.numberNode(Math.max(1, schema.path("minimum").asLong(1)));
            case "number":
                return NODES.numberNode(Math.max(1.0, schema.path("minimum").asDouble(1.0)));
            case "boolean":
                return NODES.booleanNode(false);
            default:
                return NODES.stringNode(TEXT);
        }
    }

    private ObjectNode object(JsonNode schema, int depth) {
        ObjectNode out = NODES.objectNode();
        if (depth >= MAX_DEPTH) {
            return out;
        }
        Set<String> required = new HashSet<>();
        schema.path("required").forEach(r -> required.add(r.asString()));
        for (Map.Entry<String, JsonNode> p : schema.path("properties").properties()) {
            JsonNode prop = spec.resolve(p.getValue(), 0);
            if (required.contains(p.getKey()) || prop.has("example")) {
                out.set(p.getKey(), value(prop, depth + 1));
            }
        }
        return out;
    }
}
