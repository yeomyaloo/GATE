package io.gate.review.merge;

import io.gate.common.finding.Decision;
import io.gate.common.finding.Finding;
import io.gate.review.ai.AiFinding;
import io.gate.review.ai.AiResult;
import java.util.List;
import java.util.UUID;

/**
 * 검토 1회의 결과.
 *
 * @param rejected 인용 확인에 실패해 버린 AI 지적과 이유 (측정에 "지어낸 지적 수"로 기록)
 * @param ai       AI 검토 상태 (OK가 아니면 결과에 표시)
 */
public record ReviewReport(UUID runId, List<Finding> findings, List<Rejected> rejected, AiResult ai,
        String staticEngine, List<String> staticErrors) {

    public record Rejected(AiFinding finding, String reason) {
    }

    public ReviewReport {
        findings = List.copyOf(findings);
        rejected = List.copyOf(rejected);
        staticErrors = List.copyOf(staticErrors);
    }

    public Decision decision() {
        if (findings.stream().anyMatch(f -> f.decision() == Decision.BLOCK)) {
            return Decision.BLOCK;
        }
        return findings.stream().anyMatch(f -> f.decision() == Decision.WARN) ? Decision.WARN : Decision.PASS;
    }
}
