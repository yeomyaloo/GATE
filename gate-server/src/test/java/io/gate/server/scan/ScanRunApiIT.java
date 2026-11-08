package io.gate.server.scan;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** POST /api/scan-runs를 실제 PostgreSQL로 확인한다. Docker가 없으면 건너뛴다(CI에서는 항상 실행). */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class ScanRunApiIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse(
            // postgres:18.6-alpine3.24
            "postgres@sha256:123b17998ccaf47e53b502e8c6a1e79afd8f579c6f3eec83c563da026f957395"));

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse(
            // redis:8.8.4-alpine3.23
            "redis@sha256:c027ae0f677dcf73d74038256f5b88c9ba7b11ccbfae97e96a9f091208d36125"))
            .withExposedPorts(6379);

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    record Response(int status, String body) {
    }

    Response post(String json) {
        return RestClient.create("http://localhost:" + port).post().uri("/api/scan-runs")
                .contentType(MediaType.APPLICATION_JSON).body(json)
                .exchange((req, res) -> new Response(res.getStatusCode().value(),
                        new String(res.getBody().readAllBytes())));
    }

    static final String VALID = """
            {"module":"PKG","trigger":"CLAUDE_HOOK","target":"npm install lodahs",
             "findings":[
               {"ruleId":"PKG-TYPO","title":"'lodahs' looks like 'lodash'","severity":"HIGH","decision":"BLOCK",
                "location":{"kind":"package","ecosystem":"npm","name":"lodahs","version":"0.0.1-security"},
                "evidence":{"similarTo":"lodash","technique":"edit-distance"},"source":"RULE"},
               {"ruleId":"PKG-VULN","title":"MALICIOUS","severity":"CRITICAL","decision":"BLOCK",
                "location":{"kind":"package","ecosystem":"npm","name":"lodahs","version":"0.0.1-security"},
                "evidence":{"malicious":true,"count":1},"source":"RULE"}]}
            """;

    @Test
    void storesRunAndFindings() {
        Response r = post(VALID);
        assertThat(r.status()).isEqualTo(201);
        assertThat(r.body()).contains("\"findings\":2");
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT f.rule_id, f.location->>'name' AS name, f.evidence->>'similarTo' AS similar, r.trigger "
                        + "FROM finding f JOIN scan_run r ON r.id = f.run_id WHERE f.rule_id = 'PKG-TYPO' "
                        + "ORDER BY f.detected_at DESC LIMIT 1");
        assertThat(row).containsEntry("name", "lodahs").containsEntry("similar", "lodash")
                .containsEntry("trigger", "CLAUDE_HOOK");
    }

    @Test
    void aiOnlyBlockIsRejectedWith400() {
        Response r = post(VALID.replace("\"source\":\"RULE\"}]}", "\"source\":\"AI\"}]}"));
        assertThat(HttpStatusCode.valueOf(r.status()).is4xxClientError()).isTrue();
        assertThat(r.body()).contains("AI-only");
    }

    @Test
    void unknownEnumIsRejected() {
        assertThat(post(VALID.replace("\"PKG\"", "\"NOPE\"")).status()).isEqualTo(400);
    }
}
