package io.gate.authz.probe;

import io.gate.common.finding.Decision;
import io.gate.common.finding.Finding;
import java.util.List;
import java.util.UUID;

/**
 * 검사 1회의 결과.
 *
 * @param findings 공통 모델 결과 (발견·판단 불가)
 * @param coverage 작업마다 무엇을 했고 어떻게 판정했는지 (발견이 없어도 "검사해서 통과"인지 "못 했음"인지 구분)
 */
public record AuthzReport(UUID runId, List<Finding> findings, List<Coverage> coverage) {

    public enum Status { FINDING, PASS, INCONCLUSIVE, SKIPPED }

    /** 작업 하나에 대한 시도 한 가지(예: GET /api/orders/{id}를 A의 토큰으로). */
    public record Coverage(String operation, String check, Status status, String detail) {
    }

    public AuthzReport {
        findings = List.copyOf(findings);
        coverage = List.copyOf(coverage);
    }

    public Decision decision() {
        if (findings.stream().anyMatch(f -> f.decision() == Decision.BLOCK)) {
            return Decision.BLOCK;
        }
        return findings.stream().anyMatch(f -> f.decision() == Decision.WARN) ? Decision.WARN : Decision.PASS;
    }

    public List<Finding> byRule(String ruleId) {
        return findings.stream().filter(f -> f.ruleId().equals(ruleId)).toList();
    }
}
