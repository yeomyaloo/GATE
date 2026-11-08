package io.gate.server.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gate.common.finding.Decision;
import io.gate.common.finding.Finding;
import io.gate.common.finding.FindingSource;
import io.gate.common.finding.GateModule;
import io.gate.common.finding.Location;
import io.gate.common.finding.Severity;
import io.gate.common.finding.Trigger;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 요청 검증은 DB 없이 확인한다 (Docker가 없는 PC에서도 실행됨). */
class ScanRunRequestTest {

    static final Instant NOW = Instant.parse("2026-11-09T00:00:00Z");
    static final Map<String, Object> PKG = Map.of("kind", "package", "ecosystem", "npm", "name", "lodahs",
            "version", "4.17.21");

    static ScanRunRequest.FindingRequest finding(Decision d, FindingSource s, Map<String, Object> evidence) {
        return new ScanRunRequest.FindingRequest("PKG-TYPO", "looks like lodash", Severity.HIGH, d, PKG, evidence, s,
                null);
    }

    static ScanRunRequest run(List<ScanRunRequest.FindingRequest> findings) {
        return new ScanRunRequest(GateModule.PKG, Trigger.CLAUDE_HOOK, "npm install lodahs", null, null, findings);
    }

    @Test
    void validRequestBecomesFindings() {
        List<Finding> f = run(List.of(finding(Decision.BLOCK, FindingSource.RULE, Map.of("similarTo", "lodash"))))
                .toFindings(UUID.randomUUID(), NOW);
        assertEquals(1, f.size());
        assertEquals(new Location.Package("npm", "lodahs", "4.17.21"), f.get(0).location());
        assertEquals(NOW, f.get(0).detectedAt());
    }

    @Test
    void aiOnlyBlockIsRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> run(List.of(finding(Decision.BLOCK, FindingSource.AI, Map.of("r", "x"))))
                        .toFindings(UUID.randomUUID(), NOW));
        assertTrue(e.getMessage().startsWith("findings[0]"), e.getMessage());
    }

    @Test
    void blockWithoutEvidenceIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> run(List.of(finding(Decision.BLOCK, FindingSource.RULE, Map.of())))
                        .toFindings(UUID.randomUUID(), NOW));
    }

    @Test
    void duplicateFingerprintsAreMerged() {
        ScanRunRequest.FindingRequest f = finding(Decision.WARN, FindingSource.RULE, Map.of("k", "v"));
        assertEquals(1, run(List.of(f, f)).toFindings(UUID.randomUUID(), NOW).size());
    }

    @Test
    void missingFieldsAndLimits() {
        assertThrows(IllegalArgumentException.class, () -> new ScanRunRequest(null, Trigger.CLI, "t", null, null,
                List.of()).toFindings(UUID.randomUUID(), NOW));
        assertThrows(IllegalArgumentException.class, () -> new ScanRunRequest(GateModule.PKG, Trigger.CLI, " ", null,
                null, List.of()).toFindings(UUID.randomUUID(), NOW));
        assertThrows(IllegalArgumentException.class, () -> run(Collections.nCopies(ScanRunRequest.MAX_FINDINGS + 1,
                finding(Decision.PASS, FindingSource.RULE, null))).toFindings(UUID.randomUUID(), NOW));
        assertThrows(IllegalArgumentException.class, () -> new ScanRunRequest(GateModule.PKG, Trigger.CLI, "t",
                NOW, NOW.minusSeconds(1), List.of()).toFindings(UUID.randomUUID(), NOW));
    }

    @Test
    void locationKinds() {
        assertEquals(new Location.File("src/A.java", 3),
                ScanRunRequest.location(Map.of("kind", "file", "path", "src/A.java", "line", 3)));
        assertEquals(new Location.Endpoint("GET", "/api/x"),
                ScanRunRequest.location(Map.of("kind", "endpoint", "method", "get", "path", "/api/x")));
        assertThrows(IllegalArgumentException.class, () -> ScanRunRequest.location(Map.of("kind", "other")));
        assertThrows(IllegalArgumentException.class, () -> ScanRunRequest.location(null));
    }
}
