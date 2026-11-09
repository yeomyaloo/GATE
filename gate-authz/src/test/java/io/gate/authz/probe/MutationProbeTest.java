package io.gate.authz.probe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gate.authz.config.AuthzConfig;
import io.gate.authz.http.ApiClient;
import io.gate.authz.spec.ApiSpec;
import java.net.URI;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** 수정·삭제 판정을 상태 있는 가짜 메모 서비스로 확인한다. 주인 확인을 켜고 끌 수 있다. */
class MutationProbeTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String SPEC = """
            openapi: 3.0.3
            security: [ { bearerAuth: [] } ]
            paths:
              /api/login: { post: { security: [] } }
              /api/notes:
                get: {}
                post:
                  requestBody: { content: { application/json: { schema: { $ref: '#/components/schemas/Note' } } } }
              /api/notes/{id}:
                get: {}
                put:
                  requestBody: { content: { application/json: { schema: { $ref: '#/components/schemas/Note' } } } }
                delete: {}
            components:
              securitySchemes: { bearerAuth: { type: http, scheme: bearer } }
              schemas:
                Note:
                  type: object
                  required: [text]
                  properties:
                    text: { type: string, example: "hello" }
                    pinned: { type: boolean, example: false }
            """;

    /** 메모를 실제로 저장하는 가짜 서비스. */
    static final class NotesService implements ApiClient {
        final boolean checksOwner;
        final boolean ignoresWrites;
        final Map<String, ObjectNode> notes = new LinkedHashMap<>();
        final List<String> calls = new ArrayList<>();
        int next = 100;

        NotesService(boolean checksOwner, boolean ignoresWrites) {
            this.checksOwner = checksOwner;
            this.ignoresWrites = ignoresWrites;
        }

        @Override
        public Response send(String method, URI uri, String token, String body) {
            String path = uri.getPath();
            if (path.equals("/api/login")) {
                return new Response(200, "{\"accessToken\":\"" + (body.contains("alice") ? "ta" : "tb") + "\"}");
            }
            String user = token == null ? null : token.equals("ta") ? "alice" : "bob";
            calls.add(method + " " + path + " " + user);
            if (user == null) {
                return new Response(401, "");
            }
            if (path.equals("/api/notes")) {
                if (method.equals("GET")) {
                    var arr = JSON.createArrayNode();
                    notes.values().stream().filter(n -> n.path("owner").asString().equals(user)).forEach(arr::add);
                    return new Response(200, arr.toString());
                }
                ObjectNode n = (ObjectNode) JSON.readTree(body);
                n.put("id", next++);
                n.put("owner", user);
                notes.put(n.path("id").asString(), n);
                return new Response(201, n.toString());
            }
            String id = path.substring(path.lastIndexOf('/') + 1);
            ObjectNode note = notes.get(id);
            if (note == null || (checksOwner && !note.path("owner").asString().equals(user))) {
                return new Response(404, "{}");
            }
            switch (method) {
                case "GET":
                    return new Response(200, note.toString());
                case "PUT":
                    if (!ignoresWrites) {
                        JsonNode update = JSON.readTree(body);
                        update.properties().forEach(e -> note.set(e.getKey(), e.getValue()));
                    }
                    return new Response(200, note.toString());
                case "DELETE":
                    if (!ignoresWrites) {
                        notes.remove(id);
                    }
                    return new Response(204, "");
                default:
                    return new Response(405, "");
            }
        }
    }

    static AuthzReport scan(NotesService api, boolean allowMutation) throws Exception {
        AuthzConfig cfg = new AuthzConfig("http://127.0.0.1:9", new AuthzConfig.Login("/api/login", null, null),
                new AuthzConfig.User("alice", "PA"), new AuthzConfig.User("bob", "PB"), allowMutation, Set.of(), null,
                Map.of(), 100);
        return new AuthzScanner(ApiSpec.parse(SPEC), cfg, api, Map.of("PA", "x", "PB", "y")::get, Clock.systemUTC())
                .scan();
    }

    @Test
    void ownerlessServiceHasReadWriteAndDeleteFindings() throws Exception {
        NotesService api = new NotesService(false, false);
        AuthzReport r = scan(api, true);
        assertEquals(1, r.byRule(Rules.BOLA_READ).size());
        assertEquals(1, r.byRule(Rules.BOLA_WRITE).size());
        assertEquals(1, r.byRule(Rules.BOLA_DELETE).size());
        assertEquals(List.of("text"), r.byRule(Rules.BOLA_WRITE).get(0).evidence().get("changedFields"));
        assertTrue(api.notes.isEmpty(), "objects created for the test are cleaned up: " + api.notes);
    }

    @Test
    void ownerCheckingServiceHasNoFindings() throws Exception {
        NotesService api = new NotesService(true, false);
        AuthzReport r = scan(api, true);
        assertTrue(r.findings().isEmpty(), r.findings().toString());
        assertEquals(3, r.coverage().stream().filter(c -> c.check().equals("as user A")
                && c.status() == AuthzReport.Status.PASS).count(), r.coverage().toString());
        assertTrue(api.notes.isEmpty(), "cleanup also runs when nothing was found");
    }

    @Test
    void okResponseThatChangesNothingIsNotAFinding() throws Exception {
        // 200·204를 주지만 실제로는 아무것도 바꾸지 않는 서비스: 응답 코드만 믿었다면 오탐이다
        AuthzReport r = scan(new NotesService(false, true), true);
        assertTrue(r.byRule(Rules.BOLA_WRITE).isEmpty());
        assertTrue(r.byRule(Rules.BOLA_DELETE).isEmpty());
        assertTrue(r.coverage().stream().anyMatch(c -> c.detail().contains("nothing changed")), r.coverage().toString());
    }

    @Test
    void readOnlyByDefaultMakesNoWrites() throws Exception {
        NotesService api = new NotesService(false, false);
        api.notes.put("1", (ObjectNode) JSON.readTree("{\"id\":1,\"owner\":\"bob\",\"text\":\"existing\"}"));
        AuthzReport r = scan(api, false);
        assertFalse(api.calls.stream().anyMatch(c -> !c.startsWith("GET")), api.calls.toString());
        assertEquals("existing", api.notes.get("1").path("text").asString(), "existing data untouched");
        assertEquals(1, r.byRule(Rules.BOLA_READ).size());
        assertEquals(2, r.coverage().stream().filter(c -> c.status() == AuthzReport.Status.SKIPPED).count());
    }

    @Test
    void existingObjectsAreNeverTheTargetOfWrites() throws Exception {
        NotesService api = new NotesService(false, false);
        api.notes.put("1", (ObjectNode) JSON.readTree("{\"id\":1,\"owner\":\"bob\",\"text\":\"existing\"}"));
        scan(api, true);
        assertTrue(api.calls.stream().noneMatch(c -> c.matches("(PUT|DELETE) /api/notes/1 .*")), api.calls.toString());
        assertEquals("existing", api.notes.get("1").path("text").asString());
    }

    @Test
    void changedFieldsCompareSentBeforeAndAfter() throws Exception {
        JsonNode sent = JSON.readTree("{\"text\":\"m\",\"pinned\":false}");
        JsonNode before = JSON.readTree("{\"text\":\"a\",\"pinned\":false}");
        assertEquals(List.of("text"), AuthzScanner.changedBy(sent, before, JSON.readTree("{\"text\":\"m\",\"pinned\":false}")));
        assertEquals(List.of(), AuthzScanner.changedBy(sent, before, before));
    }
}
