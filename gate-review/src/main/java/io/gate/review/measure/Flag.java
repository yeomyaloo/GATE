package io.gate.review.measure;

import io.gate.common.finding.Finding;
import io.gate.common.finding.Location;
import io.gate.review.Cwe;
import io.gate.review.ai.AiFinding;
import io.gate.review.staticscan.StaticHit;
import java.util.Map;

/**
 * 채점용으로 단순화한 지적 하나: 원래 경로(중립화 전), 줄 범위, CWE.
 *
 * @param cwe   "CWE-89" 형식. 모르면 null (종류가 맞는지 판단할 수 없어 정답으로 치지 않는다)
 * @param label 누가 낸 지적인지 (규칙 ID 또는 "AI") - 원자료를 사람이 읽을 때 쓴다
 */
public record Flag(String path, int startLine, int endLine, String cwe, String label) {

    public Flag {
        if (endLine < startLine) {
            endLine = startLine;
        }
    }

    public Flag withPath(String p) {
        return new Flag(p, startLine, endLine, cwe, label);
    }

    public static Flag of(StaticHit h) {
        return new Flag(h.path(), h.startLine(), h.endLine(), h.cwe(), h.ruleId());
    }

    public static Flag of(AiFinding a) {
        return new Flag(a.file(), a.line(), a.endLine(), Cwe.normalize(a.cwe()).orElse(null), "AI");
    }

    /** 합친 결과의 지적. 줄 범위와 CWE는 근거(evidence)에서 읽는다 (규칙 쪽이 먼저, 없으면 AI 쪽). */
    public static Flag of(Finding f) {
        Location.File loc = (Location.File) f.location();
        Map<String, Object> ev = f.evidence();
        int start = loc.line();
        int end = start;
        Object lines = ev.get("lines");
        if (lines instanceof String s && s.contains("-")) {
            String[] parts = s.split("-", 2);
            try {
                start = Integer.parseInt(parts[0].strip());
                end = Integer.parseInt(parts[1].strip());
            } catch (NumberFormatException e) {
                // 근거 형식이 다르면 위치 줄만 쓴다
            }
        }
        String cwe = ev.get("cwe") instanceof String c ? c : null;
        if (cwe == null && ev.get("ai") instanceof Map<?, ?> ai && ai.get("cwe") instanceof String c) {
            cwe = c;
        }
        return new Flag(loc.path(), start, end, cwe, f.ruleId());
    }
}
