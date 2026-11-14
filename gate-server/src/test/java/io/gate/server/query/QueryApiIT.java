package io.gate.server.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 조회 API를 실제 PostgreSQL로 확인한다 (DISTINCT ON, FILTER, JSONB 같은 PostgreSQL 문법을 쓰기 때문).
 * Docker가 없으면 건너뛰고, CI에서는 건너뛰면 실패로 처리한다.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QueryApiIT {

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

    static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort
    int port;

    record Res(int status, JsonNode body, org.springframework.http.HttpHeaders headers) {
    }

    Res get(String path) {
        return RestClient.create("http://localhost:" + port).get().uri(java.net.URI.create("http://localhost:" + port
                + path)).exchange((req, res) -> {
                    String raw = new String(res.getBody().readAllBytes());
                    return new Res(res.getStatusCode().value(), raw.isBlank() ? null : JSON.readTree(raw),
                            res.getHeaders());
                });
    }

    String post(String json) {
        return RestClient.create("http://localhost:" + port).post().uri("/api/scan-runs")
                .contentType(MediaType.APPLICATION_JSON).body(json).retrieve().body(String.class);
    }

    String pkgRun;
    String promptRun;

    @BeforeAll
    void seed() {
        pkgRun = JSON.readTree(post("""
                {"module":"PKG","trigger":"CLAUDE_HOOK","target":"npm install lodahs",
                 "startedAt":"2026-11-15T00:00:00Z","finishedAt":"2026-11-15T00:00:01Z",
                 "findings":[
                   {"ruleId":"PKG-TYPO","title":"'lodahs' looks like 'lodash'","severity":"HIGH","decision":"BLOCK",
                    "location":{"kind":"package","ecosystem":"npm","name":"lodahs","version":"1.0.0"},
                    "evidence":{"similarTo":"lodash"},"source":"RULE","detectedAt":"2026-11-15T00:00:01Z"},
                   {"ruleId":"PKG-NEW","title":"published 2 days ago","severity":"MEDIUM","decision":"WARN",
                    "location":{"kind":"package","ecosystem":"npm","name":"lodahs","version":"1.0.0"},
                    "evidence":{"ageDays":2},"source":"RULE","detectedAt":"2026-11-15T00:00:02Z"}]}
                """)).path("id").asString();
        promptRun = JSON.readTree(post("""
                {"module":"PROMPT","trigger":"CLI","target":"corpus",
                 "startedAt":"2026-11-15T01:00:00Z","finishedAt":"2026-11-15T01:00:01Z",
                 "findings":[
                   {"ruleId":"PROMPT-HTML-REMOVED","title":"<script>alert(1)</script> removed","severity":"HIGH",
                    "decision":"WARN","location":{"kind":"endpoint","method":"POST","path":"/api/assistant/review-summary"},
                    "evidence":{"payload":"<img src=x onerror=alert(1)>"},"source":"RULE",
                    "detectedAt":"2026-11-15T01:00:01Z"}]}
                """)).path("id").asString();
    }

    @Test
    void summaryCountsEveryModule() {
        Res r = get("/api/summary");
        assertThat(r.status()).isEqualTo(200);
        List<String> modules = new ArrayList<>();
        r.body().path("modules").forEach(m -> modules.add(m.path("module").asString()));
        assertThat(modules).containsExactly("PKG", "REVIEW", "AUTHZ", "PROMPT");
        JsonNode pkg = r.body().path("modules").get(0);
        assertThat(pkg.path("runs").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(pkg.path("findings").path("BLOCK").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(r.body().path("modules").get(1).path("lastRunId").isNull()).as("REVIEW has no run yet").isTrue();
        assertThat(r.body().path("totals").path("bySeverity").path("HIGH").asLong()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void runsAreListedNewestFirstWithCounts() {
        Res r = get("/api/scan-runs?module=pkg&limit=5");
        assertThat(r.status()).isEqualTo(200);
        JsonNode first = r.body().get(0);
        assertThat(first.path("module").asString()).isEqualTo("PKG");
        assertThat(first.path("findings").path("BLOCK").asLong()).isEqualTo(1);
        assertThat(first.path("findings").path("WARN").asLong()).isEqualTo(1);
    }

    @Test
    void runDetailIncludesFindingsWithJsonEvidence() {
        Res r = get("/api/scan-runs/" + pkgRun);
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body().path("target").asString()).isEqualTo("npm install lodahs");
        assertThat(r.body().path("findings")).hasSize(2);
        JsonNode typo = r.body().path("findings").get(1);
        assertThat(typo.path("ruleId").asString()).isEqualTo("PKG-TYPO");
        assertThat(typo.path("location").path("name").asString()).isEqualTo("lodahs");
        assertThat(typo.path("evidence").path("similarTo").asString()).as("evidence is JSON, not a string")
                .isEqualTo("lodash");
        assertThat(get("/api/scan-runs/00000000-0000-0000-0000-000000000000").status()).isEqualTo(404);
    }

    @Test
    void findingsCanBeFilteredAndPaged() {
        Res warn = get("/api/findings?decision=WARN&module=PKG");
        assertThat(warn.body()).allSatisfy(f -> assertThat(f.path("decision").asString()).isEqualTo("WARN"));
        assertThat(warn.body()).isNotEmpty();

        Res page = get("/api/findings?module=PKG&before=2026-11-15T00:00:02Z");
        assertThat(page.body()).allSatisfy(f -> assertThat(f.path("ruleId").asString()).isEqualTo("PKG-TYPO"));

        String fp = warn.body().get(0).path("fingerprint").asString();
        Res same = get("/api/findings?fingerprint=" + fp);
        assertThat(same.body()).allSatisfy(f -> assertThat(f.path("fingerprint").asString()).isEqualTo(fp));
    }

    @Test
    void attackTextInResultsIsReturnedAsDataUnderAStrictPolicy() {
        Res r = get("/api/findings?module=PROMPT");
        assertThat(r.body().get(0).path("evidence").path("payload").asString())
                .isEqualTo("<img src=x onerror=alert(1)>");
        assertThat(r.headers().getContentType().toString()).startsWith("application/json");
        assertThat(r.headers().getFirst("Content-Security-Policy")).contains("script-src 'self'");
        assertThat(r.headers().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(r.headers().getFirst("Cache-Control")).isEqualTo("no-store");
    }

    @Test
    void badFiltersAreRejected() {
        assertThat(get("/api/findings?decision=MAYBE").status()).isEqualTo(400);
        assertThat(get("/api/findings?module=PKG'%20OR%201=1--").status()).isEqualTo(400);
        assertThat(get("/api/findings?limit=100000").status()).isEqualTo(400);
        assertThat(get("/api/findings?before=yesterday").status()).isEqualTo(400);
        assertThat(get("/api/findings?fingerprint=abc").status()).isEqualTo(400);
        assertThat(get("/api/scan-runs/not-a-uuid").status()).isEqualTo(400);
    }
}
