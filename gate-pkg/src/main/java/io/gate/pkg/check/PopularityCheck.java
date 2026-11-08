package io.gate.pkg.check;

import io.gate.common.finding.Decision;
import io.gate.common.finding.Finding;
import io.gate.common.finding.Severity;
import io.gate.pkg.registry.DownloadsResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PKG-POP: 주간 다운로드가 {@link Thresholds#minWeeklyDownloads()} 미만 → WARN·LOW.
 * 다운로드 수를 얻지 못하면 PKG-UNCHECKED(검사 불가)로 알린다. 설계 결정상 검사 불가는 숨기지 않고 확인을 요청한다.
 */
public final class PopularityCheck implements Check {

    @Override
    public List<Finding> evaluate(CheckContext ctx) {
        DownloadsResult downloads = ctx.downloads();
        if (downloads instanceof DownloadsResult.Unknown unknown) {
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("check", Rules.POP);
            evidence.put("url", unknown.url());
            evidence.put("reason", unknown.reason());
            return List.of(Rules.finding(ctx.runId(), Rules.UNCHECKED,
                    "Could not get download counts for '" + ctx.info().name() + "'",
                    Severity.INFO, Decision.WARN, ctx.location(), evidence, ctx.now()));
        }
        DownloadsResult.Known known = (DownloadsResult.Known) downloads;
        long min = ctx.thresholds().minWeeklyDownloads();
        if (known.weekly() >= min) {
            return List.of();
        }
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("weeklyDownloads", known.weekly());
        evidence.put("threshold", min);
        evidence.put("url", known.url());
        return List.of(Rules.finding(ctx.runId(), Rules.POP,
                "'" + ctx.info().name() + "' has only " + known.weekly() + " downloads in the last week",
                Severity.LOW, Decision.WARN, ctx.location(), evidence, ctx.now()));
    }
}
