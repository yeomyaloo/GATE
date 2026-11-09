package io.gate.authz.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * {@code gate-authz.yml}을 읽는다 (설계안 4장). 비밀번호 값이 설정에 들어 있으면 거부한다.
 *
 * <pre>
 * spec: http://127.0.0.1:8081/openapi.yaml      # URL 또는 이 파일 기준 상대 경로
 * baseUrl: http://127.0.0.1:8081
 * login: { path: /api/auth/login, body: { username: "{username}", password: "{password}" }, tokenField: accessToken }
 * users:
 *   a: { username: alice, passwordEnv: GATE_AUTHZ_PASSWORD_A }
 *   b: { username: bob,   passwordEnv: GATE_AUTHZ_PASSWORD_B }
 * allowMutation: false
 * allowedHosts: []
 * </pre>
 */
public final class ConfigLoader {

    /** 읽은 설정과 명세 위치. specLocation은 URL이거나, 설정 파일 기준으로 풀어 둔 파일 경로다. */
    public record Loaded(AuthzConfig config, String specLocation) {
    }

    private ConfigLoader() {
    }

    public static Loaded load(Path file) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        JsonNode root;
        try {
            root = YAMLMapper.builder().build().readTree(text);
        } catch (JacksonException e) {
            throw new IllegalArgumentException(file + ": not valid YAML: " + e.getOriginalMessage());
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException(file + ": expected a YAML mapping");
        }
        Path dir = file.toAbsolutePath().getParent();
        String spec = required(root, "spec");
        String specLocation = spec.matches("(?i)^https?://.*") ? spec : dir.resolve(spec).normalize().toString();

        JsonNode login = root.path("login");
        Map<String, String> body = null;
        if (login.path("body").isObject()) {
            body = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> e : login.path("body").properties()) {
                body.put(e.getKey(), e.getValue().asString(""));
            }
            // 비밀번호 키의 값은 자리 표시자여야 한다 (설정 파일에 비밀번호를 적지 못하게)
            for (Map.Entry<String, String> e : body.entrySet()) {
                if (e.getKey().toLowerCase(java.util.Locale.ROOT).contains("pass")
                        && !e.getValue().equals("{password}")) {
                    throw new IllegalArgumentException("login.body." + e.getKey() + " must be \"{password}\"; put the "
                            + "password in an environment variable and set the user's passwordEnv");
                }
            }
        }
        AuthzConfig.Login loginCfg = new AuthzConfig.Login(required(login, "path", "login.path"), body,
                login.path("tokenField").asString(null));

        if (root.path("users").path("a").has("password") || root.path("users").path("b").has("password")) {
            throw new IllegalArgumentException("users.*.password is not allowed: put the password in an environment "
                    + "variable and set passwordEnv");
        }
        AuthzConfig.User a = user(root.path("users").path("a"), "users.a");
        AuthzConfig.User b = user(root.path("users").path("b"), "users.b");

        Set<String> hosts = new LinkedHashSet<>();
        root.path("allowedHosts").forEach(h -> hosts.add(h.asString()));
        Map<String, List<String>> seeds = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> e : root.path("seeds").properties()) {
            List<String> ids = new ArrayList<>();
            e.getValue().forEach(id -> ids.add(id.asString()));
            seeds.put(e.getKey(), ids);
        }
        AuthzConfig config = new AuthzConfig(required(root, "baseUrl"), loginCfg, a, b,
                root.path("allowMutation").asBoolean(false), hosts, root.path("idField").asString(null), seeds,
                root.path("maxRequestsPerSecond").asDouble(5));
        return new Loaded(config, specLocation);
    }

    private static AuthzConfig.User user(JsonNode node, String name) {
        return new AuthzConfig.User(required(node, "username", name + ".username"),
                required(node, "passwordEnv", name + ".passwordEnv"));
    }

    private static String required(JsonNode node, String key) {
        return required(node, key, key);
    }

    private static String required(JsonNode node, String key, String label) {
        String v = node.path(key).asString("");
        if (v.isBlank()) {
            throw new IllegalArgumentException("missing required setting: " + label);
        }
        return v;
    }
}
