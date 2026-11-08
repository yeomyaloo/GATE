package io.gate.pkg.check;

import io.gate.common.finding.Decision;
import io.gate.common.finding.Finding;
import io.gate.common.finding.Severity;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * PKG-COMBO: 각각은 경고 수준인 신호가 겹치면 차단한다.
 * {@code PKG-NEW} + ({@code PKG-SCRIPT} 또는 {@code PKG-POP}) → BLOCK·HIGH.
 * "새로 만들어졌고, 설치하면 코드가 돌거나, 아무도 안 쓰는" 패키지는 전형적인 악성 패키지 모양이다.
 * 다른 검사 결과를 입력으로 받으므로 스캐너가 마지막에 부른다.
 */
public final class ComboRule {

    private ComboRule() {
    }

    public static Optional<Finding> evaluate(CheckContext ctx, List<Finding> findings) {
        Set<String> rules = findings.stream().map(Finding::ruleId).collect(Collectors.toSet());
        if (!rules.contains(Rules.NEW) || !(rules.contains(Rules.SCRIPT) || rules.contains(Rules.POP))) {
            return Optional.empty();
        }
        Set<String> signals = new TreeSet<>();
        for (String r : List.of(Rules.NEW, Rules.SCRIPT, Rules.POP)) {
            if (rules.contains(r)) {
                signals.add(r);
            }
        }
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("signals", List.copyOf(signals));
        evidence.put("details", findings.stream()
                .filter(f -> signals.contains(f.ruleId()))
                .map(Finding::title)
                .toList());
        return Optional.of(Rules.finding(ctx.runId(), Rules.COMBO,
                "'" + ctx.info().name() + "' combines several risk signals: " + String.join(" + ", signals),
                Severity.HIGH, Decision.BLOCK, ctx.location(), evidence, ctx.now()));
    }
}
