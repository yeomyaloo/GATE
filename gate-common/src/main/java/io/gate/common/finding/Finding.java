package io.gate.common.finding;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 모든 모듈이 공통으로 남기는 검사 결과 한 건: 무엇이(ruleId), 어디서(location), 위험도(severity),
 * 근거(evidence), 시각(detectedAt).
 */
public record Finding(
        UUID id,
        UUID runId,
        String ruleId,
        String title,
        Severity severity,
        Decision decision,
        Location location,
        Map<String, Object> evidence,
        FindingSource source,
        Instant detectedAt) {

    public Finding {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(detectedAt, "detectedAt");
        evidence = evidence == null ? Map.of() : Map.copyOf(evidence);
        if (evidence.isEmpty() && decision != Decision.PASS) {
            throw new IllegalArgumentException("BLOCK/WARN findings must carry evidence");
        }
        if (source == FindingSource.AI && decision == Decision.BLOCK) {
            throw new IllegalArgumentException("AI-only findings cannot BLOCK; a rule must back the decision");
        }
    }

    /**
     * 같은 발견의 중복 저장을 막는 식별값. 시각·ID는 빼고 "규칙 + 위치"로만 만든다.
     */
    public String fingerprint() {
        return Fingerprints.of(ruleId, location);
    }
}
