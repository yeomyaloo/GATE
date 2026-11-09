package io.gate.authz.probe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gate.authz.config.AuthzConfig;
import io.gate.authz.http.ApiClient;
import io.gate.authz.spec.ApiSpec;
import io.gate.common.finding.Decision;
import io.gate.common.finding.Location;
import java.net.URI;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 판정 규칙을 가짜 API로 확인한다. 응답은 "메서드 경로 토큰주인"으로 정한다. */
class AuthzScannerTest {

    static final String SPEC = """
            openapi: 3.0.3
            security: [ { bearerAuth: [] } ]
            paths:
              /api/login: { post: { security: [] } }
              /api/notes: { get: {}, post: {} }
              /api/notes/{id}: { get: {}, delete: {} }
            components: { securitySchemes: { bearerAuth: { type: http, scheme: bearer } } }
            """;

    /** 정해 둔 응답을 돌려주는 가짜 API. 토큰 "ta"는 A, "tb"는 B, null은 익명. */
    static final class FakeApi implements ApiClient {
        final Map<String, Response> responses = new HashMap<>();
        final List<String> calls = new ArrayList<>();

        FakeApi on(String method, String path, String who, int status, String body) {
            responses.put(method + " " + path + " " + who, new Response(status, body));
            return this;
        }

        @Override
        public Response send(String method, URI uri, String token, String body) {
            String who = token == null ? "anon" : token.equals("ta") ? "a" : token.equals("tb") ? "b" : "?";
            if (uri.getPath().equals("/api/login")) {
                String user = body.contains("alice") ? "ta" : "tb";
                return new Response(200, "{\"accessToken\":\"" + user + "\"}");
            }
            String key = method + " " + uri.getPath() + " " + who;
            calls.add(key);
            return responses.getOrDefault(key, new Response(404, "{}"));
        }
    }

    static AuthzConfig config(boolean mutation) {
        return new AuthzConfig("http://127.0.0.1:9", new AuthzConfig.Login("/api/login", null, null),
                new AuthzConfig.User("alice", "PW_A"), new AuthzConfig.User("bob", "PW_B"), mutation, Set.of(), null,
                Map.of(), 100);
    }

    static AuthzReport scan(FakeApi api, boolean mutation) throws Exception {
        Map<String, String> env = Map.of("PW_A", "a-pass", "PW_B", "b-pass");
        return new AuthzScanner(ApiSpec.parse(SPEC), config(mutation), api, env::get, Clock.systemUTC()).scan();
    }

    static final String BOBS = "{\"id\":7,\"owner\":\"bob\",\"text\":\"secret note\"}";

    FakeApi base() {
        return new FakeApi().on("GET", "/api/notes", "b", 200, "[" + BOBS + "]").on("GET", "/api/notes/7", "b", 200,
                BOBS);
    }

    @Test
    void readByOtherUserIsAFinding() throws Exception {
        AuthzReport r = scan(base().on("GET", "/api/notes/7", "a", 200, BOBS).on("GET", "/api/notes/7", "anon", 401,
                ""), false);
        assertEquals(1, r.byRule(Rules.BOLA_READ).size());
        var f = r.byRule(Rules.BOLA_READ).get(0);
        assertEquals(new Location.Endpoint("GET", "/api/notes/{id}"), f.location());
        assertEquals(List.of("id", "owner"), f.evidence().get("matchedFields"));
        assertEquals("listed by user B", f.evidence().get("objectSource"));
        assertEquals(Decision.BLOCK, r.decision());
        assertTrue(r.byRule(Rules.NOAUTH).isEmpty());
    }

    @Test
    void notFoundOrForbiddenIsAPass() throws Exception {
        for (int status : new int[] {404, 403, 401}) {
            AuthzReport r = scan(base().on("GET", "/api/notes/7", "a", status, "{}")
                    .on("GET", "/api/notes/7", "anon", 401, ""), false);
            assertTrue(r.byRule(Rules.BOLA_READ).isEmpty(), "status " + status);
            assertTrue(r.coverage().stream().anyMatch(c -> c.status() == AuthzReport.Status.PASS
                    && c.check().equals("as user A")));
        }
    }

    @Test
    void okButDifferentObjectIsNotAFinding() throws Exception {
        AuthzReport r = scan(base().on("GET", "/api/notes/7", "a", 200, "{\"id\":9,\"owner\":\"alice\"}")
                .on("GET", "/api/notes/7", "anon", 401, ""), false);
        assertTrue(r.byRule(Rules.BOLA_READ).isEmpty());
    }

    @Test
    void missingAuthenticationIsAFinding() throws Exception {
        AuthzReport r = scan(base().on("GET", "/api/notes/7", "a", 404, "{}").on("GET", "/api/notes/7", "anon", 200,
                BOBS), false);
        assertEquals(1, r.byRule(Rules.NOAUTH).size());
        assertEquals("anonymous (no token)", r.byRule(Rules.NOAUTH).get(0).evidence().get("attacker"));
    }

    @Test
    void failedBaselineIsInconclusiveNotPass() throws Exception {
        AuthzReport r = scan(new FakeApi().on("GET", "/api/notes", "b", 200, "[{\"id\":7}]")
                .on("GET", "/api/notes/7", "b", 500, "boom"), false);
        assertEquals(1, r.byRule(Rules.INCONCLUSIVE).stream()
                .filter(f -> f.location().equals(new Location.Endpoint("GET", "/api/notes/{id}"))).count());
        assertEquals(Decision.WARN, r.decision());
    }

    @Test
    void withoutMutationNothingIsCreatedAndNoObjectMeansInconclusive() throws Exception {
        FakeApi api = new FakeApi().on("GET", "/api/notes", "b", 200, "[]");
        AuthzReport r = scan(api, false);
        assertFalse(api.calls.stream().anyMatch(c -> c.startsWith("POST") || c.startsWith("DELETE")),
                "no writes without allowMutation: " + api.calls);
        assertFalse(r.byRule(Rules.INCONCLUSIVE).isEmpty());
    }

    @Test
    void withMutationBCreatesItsOwnObject() throws Exception {
        FakeApi api = new FakeApi().on("GET", "/api/notes", "b", 200, "[]")
                .on("POST", "/api/notes", "b", 201, BOBS).on("GET", "/api/notes/7", "b", 200, BOBS)
                .on("GET", "/api/notes/7", "a", 200, BOBS).on("GET", "/api/notes/7", "anon", 401, "");
        AuthzReport r = scan(api, true);
        assertEquals("created by user B", r.byRule(Rules.BOLA_READ).get(0).evidence().get("objectSource"));
    }

    @Test
    void responseEvidenceIsRedacted() throws Exception {
        String withPii = "{\"id\":7,\"owner\":\"bob\",\"shippingAddress\":\"Busan 22\"}";
        AuthzReport r = scan(new FakeApi().on("GET", "/api/notes", "b", 200, "[" + withPii + "]")
                .on("GET", "/api/notes/7", "b", 200, withPii).on("GET", "/api/notes/7", "a", 200, withPii)
                .on("GET", "/api/notes/7", "anon", 401, ""), false);
        String excerpt = (String) r.byRule(Rules.BOLA_READ).get(0).evidence().get("responseExcerpt");
        assertFalse(excerpt.contains("Busan"), excerpt);
    }

    @Test
    void missingPasswordEnvStopsBeforeAnyRequest() {
        FakeApi api = base();
        assertThrows(LoginClient.AuthzException.class, () -> new AuthzScanner(ApiSpec.parse(SPEC), config(false),
                api, name -> null, Clock.systemUTC()).scan());
        assertTrue(api.calls.isEmpty());
    }

    @Test
    void sameUserForAAndBIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new AuthzConfig("http://127.0.0.1",
                new AuthzConfig.Login("/l", null, null), new AuthzConfig.User("bob", "X"),
                new AuthzConfig.User("bob", "Y"), false, null, null, null, 0));
    }
}
