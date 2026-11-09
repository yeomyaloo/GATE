package io.gate.authz.probe;

import io.gate.authz.config.AuthzConfig;
import io.gate.authz.http.ApiClient;
import java.io.IOException;
import java.net.URI;
import java.util.function.Function;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** 설정의 로그인 방법으로 토큰을 받는다. 비밀번호는 환경 변수에서만 읽고 어디에도 남기지 않는다. */
public final class LoginClient {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final AuthzConfig config;
    private final ApiClient client;
    private final Function<String, String> env;

    public LoginClient(AuthzConfig config, ApiClient client, Function<String, String> env) {
        this.config = config;
        this.client = client;
        this.env = env;
    }

    public String token(AuthzConfig.User user) throws IOException {
        String password = env.apply(user.passwordEnv());
        if (password == null || password.isEmpty()) {
            throw new AuthzException("environment variable " + user.passwordEnv() + " (password of '"
                    + user.username() + "') is not set");
        }
        ObjectNode body = JSON.createObjectNode();
        config.login().body().forEach((k, v) -> body.put(k,
                v.replace("{username}", user.username()).replace("{password}", password)));
        ApiClient.Response r = client.send("POST", URI.create(config.baseUrl() + config.login().path()), null,
                JSON.writeValueAsString(body));
        if (!r.ok()) {
            throw new AuthzException("login failed for '" + user.username() + "': HTTP " + r.status());
        }
        try {
            JsonNode node = JSON.readTree(r.body());
            for (String part : config.login().tokenField().split("\\.")) {
                node = node.path(part);
            }
            if (!node.isString() || node.asString().isBlank()) {
                throw new AuthzException("login response for '" + user.username() + "' has no token field '"
                        + config.login().tokenField() + "'");
            }
            return node.asString();
        } catch (JacksonException e) {
            throw new AuthzException("login response for '" + user.username() + "' is not JSON");
        }
    }

    /** 검사를 시작할 수 없는 설정·로그인 문제. */
    public static final class AuthzException extends RuntimeException {
        public AuthzException(String message) {
            super(message);
        }
    }
}
