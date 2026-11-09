package io.gate.authz.config;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * gate-authz 설정 (설계안 4장). 비밀번호는 설정에 넣지 않고 환경 변수 이름만 적는다.
 *
 * @param baseUrl              검사 대상 기본 주소 (명세의 servers는 믿지 않는다)
 * @param login                로그인 방법
 * @param userA                공격자 역할 사용자
 * @param userB                객체 주인 역할 사용자
 * @param allowMutation        true일 때만 생성·수정·삭제를 시도한다 (기본 false)
 * @param allowedHosts         루프백 밖에서 허용할 호스트 이름 (정확히 일치)
 * @param idField              응답에서 객체 ID를 담은 필드 이름 (기본 id)
 * @param seeds                자원 목록 경로별로 사람이 적어 준 B의 객체 ID (예: {"/api/orders": ["2"]})
 * @param maxRequestsPerSecond 초당 요청 수 상한 (기본 5)
 */
public record AuthzConfig(String baseUrl, Login login, User userA, User userB, boolean allowMutation,
        Set<String> allowedHosts, String idField, Map<String, List<String>> seeds, double maxRequestsPerSecond) {

    /** 로그인 방법: 경로, 본문 틀({username}·{password} 자리 표시자), 응답에서 토큰을 담은 필드(점으로 구분). */
    public record Login(String path, Map<String, String> body, String tokenField) {
        public Login {
            Objects.requireNonNull(path, "login.path");
            body = body == null ? Map.of("username", "{username}", "password", "{password}") : Map.copyOf(body);
            tokenField = tokenField == null || tokenField.isBlank() ? "accessToken" : tokenField;
        }
    }

    /** 사용자: 이름과, 비밀번호를 담은 환경 변수의 이름. */
    public record User(String username, String passwordEnv) {
        public User {
            Objects.requireNonNull(username, "username");
            Objects.requireNonNull(passwordEnv, "passwordEnv");
        }
    }

    public AuthzConfig {
        Objects.requireNonNull(baseUrl, "baseUrl");
        Objects.requireNonNull(login, "login");
        Objects.requireNonNull(userA, "users.a");
        Objects.requireNonNull(userB, "users.b");
        if (userA.username().equals(userB.username())) {
            throw new IllegalArgumentException("users.a and users.b must be different users");
        }
        allowedHosts = allowedHosts == null ? Set.of() : Set.copyOf(allowedHosts);
        idField = idField == null || idField.isBlank() ? "id" : idField;
        seeds = seeds == null ? Map.of() : Map.copyOf(seeds);
        maxRequestsPerSecond = maxRequestsPerSecond <= 0 ? 5 : maxRequestsPerSecond;
        baseUrl = baseUrl.replaceAll("/+$", "");
    }
}
