package io.gate.pkg.check;

import io.gate.common.finding.Decision;
import io.gate.common.finding.Finding;
import io.gate.common.finding.Severity;
import io.gate.pkg.registry.PublisherHistory;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * PKG-MAINT: 요청 버전을 게시한 계정이 직전 버전의 게시자도 아니고 관리자 목록에도 없음 → WARN·HIGH.
 * 계정 탈취나 관리권 이전 직후 악성 버전을 올리는 공급망 공격 패턴이다 (예: event-stream 사건).
 * 정상적인 관리자 추가도 걸릴 수 있으므로 차단하지 않고 경고한다.
 * PyPI는 이력을 제공하지 않아 검사하지 않는다.
 */
public final class MaintainerCheck implements Check {

    @Override
    public List<Finding> evaluate(CheckContext ctx) {
        PublisherHistory h = ctx.info().publishers();
        if (h == null || h.publisher() == null || h.previousPublisher() == null) {
            return List.of();
        }
        if (h.publisher().equals(h.previousPublisher()) || h.previousMaintainers().contains(h.publisher())) {
            return List.of();
        }
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("version", ctx.info().resolvedVersion());
        evidence.put("publisher", h.publisher());
        evidence.put("previousVersion", h.previousVersion());
        evidence.put("previousPublisher", h.previousPublisher());
        evidence.put("previousMaintainers", List.copyOf(new TreeSet<>(h.previousMaintainers())));
        return List.of(Rules.finding(ctx.runId(), Rules.MAINT,
                "'" + ctx.info().name() + "@" + ctx.info().resolvedVersion() + "' was published by '" + h.publisher()
                        + "', who was not a maintainer of the previous version " + h.previousVersion(),
                Severity.HIGH, Decision.WARN, ctx.location(), evidence, ctx.now()));
    }
}
