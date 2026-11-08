package io.gate.pkg.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gate.common.finding.Trigger;
import io.gate.pkg.FakeFetcher;
import io.gate.pkg.MutableClock;
import io.gate.pkg.registry.PackageRequest;
import io.gate.pkg.scan.ScanReport;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class ResultReporterTest {

    static final String URL = "http://127.0.0.1:8080/api/scan-runs";

    final FakeFetcher http = CliTest.fixtures();

    ScanReport lodahs() {
        return CliTest.scanner(http).scan(List.of(PackageRequest.npm("lodahs", null)));
    }

    @Test
    void onlyHttpUrlsEnableReporting() {
        assertTrue(ResultReporter.fromUrl(null, http).isEmpty());
        assertTrue(ResultReporter.fromUrl("  ", http).isEmpty());
        assertTrue(ResultReporter.fromUrl("file:///etc/passwd", http).isEmpty());
        assertTrue(ResultReporter.fromUrl("http://127.0.0.1:8080/", http).isPresent());
    }

    @Test
    void bodyMatchesServerContract() {
        http.onPost(URL, "\"module\"", 201, "{\"id\":\"x\",\"findings\":1}");
        ResultReporter r = ResultReporter.fromUrl("http://127.0.0.1:8080//", http).orElseThrow();
        String result = r.report(lodahs(), Trigger.CLAUDE_HOOK, "npm install lodahs", MutableClock.NOW,
                MutableClock.NOW.plusSeconds(2));
        assertEquals("reported to " + URL, result);

        JsonNode body = Reports.JSON.readTree(http.postBodies().stream()
                .filter(b -> b.contains("\"module\"")).findFirst().orElseThrow());
        assertEquals("PKG", body.path("module").asString());
        assertEquals("CLAUDE_HOOK", body.path("trigger").asString());
        JsonNode f = body.path("findings").get(0);
        assertEquals("package", f.path("location").path("kind").asString());
        assertEquals("lodahs", f.path("location").path("name").asString());
        assertEquals("RULE", f.path("source").asString());
    }

    @Test
    void failuresNeverThrow() {
        ResultReporter r = ResultReporter.fromUrl("http://127.0.0.1:8080", http.failing(URL)).orElseThrow();
        assertTrue(r.report(lodahs(), Trigger.CLI, "x", Instant.EPOCH, Instant.EPOCH).startsWith("report failed"));
    }

    @Test
    void longTargetsAreTruncated() {
        String body = ResultReporter.body(new ScanReport(java.util.UUID.randomUUID(), List.of()), Trigger.CLI,
                "x".repeat(2000), Instant.EPOCH, Instant.EPOCH);
        assertEquals(ResultReporter.MAX_TARGET, Reports.JSON.readTree(body).path("target").asString().length());
    }
}
