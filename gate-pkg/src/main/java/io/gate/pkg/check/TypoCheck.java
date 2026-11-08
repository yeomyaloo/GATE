package io.gate.pkg.check;

import io.gate.common.finding.Decision;
import io.gate.common.finding.Finding;
import io.gate.common.finding.Severity;
import io.gate.pkg.registry.DownloadsResult;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PKG-TYPO: 인기 패키지와 철자가 비슷한 패키지 (설계안 4.1).
 * <ul>
 *   <li>비슷함 + (다운로드가 대상의 {@link Thresholds#typoMaxShare()} 미만 <b>또는</b> 첫 게시 후
 *       {@link Thresholds#typoNewWindow()} 미만) → BLOCK·HIGH</li>
 *   <li>비슷하지만 자체 사용자가 충분함 → WARN·LOW (예: 오래되고 널리 쓰이는 이름이 우연히 비슷한 경우)</li>
 *   <li>다운로드 수를 알 수 없어 판단할 수 없음 → WARN·MEDIUM (확인 요청)</li>
 * </ul>
 */
public final class TypoCheck implements Check {

    @Override
    public List<Finding> evaluate(CheckContext ctx) {
        TypoSignal typo = ctx.typo();
        if (typo == null) {
            return List.of();
        }
        Thresholds t = ctx.thresholds();
        Instant created = ctx.info().createdAt();
        boolean young = created != null && Duration.between(created, ctx.now()).compareTo(t.typoNewWindow()) < 0;

        Long own = ctx.downloads() instanceof DownloadsResult.Known k ? k.weekly() : null;
        Long target = typo.targetDownloads() instanceof DownloadsResult.Known k ? k.weekly() : null;
        Double share = own != null && target != null && target > 0 ? (double) own / target : null;
        boolean tiny = share != null && share < t.typoMaxShare();

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("similarTo", typo.match().target());
        evidence.put("technique", typo.match().technique());
        evidence.put("targetPopularityRank", typo.match().rank() + 1);
        if (own != null) {
            evidence.put("weeklyDownloads", own);
        }
        if (target != null) {
            evidence.put("targetWeeklyDownloads", target);
        }
        if (share != null) {
            evidence.put("downloadShare", Math.round(share * 1_000_000) / 1_000_000.0);
        }
        if (created != null) {
            evidence.put("createdAt", created.toString());
        }

        String title = "'" + ctx.info().name() + "' looks like popular package '" + typo.match().target() + "' ("
                + typo.match().technique() + ")";
        if (young || tiny) {
            evidence.put("reason", young ? "recently created look-alike" : "look-alike with a tiny fraction of the "
                    + "target's downloads");
            return List.of(Rules.finding(ctx.runId(), Rules.TYPO, title, Severity.HIGH, Decision.BLOCK,
                    ctx.location(), evidence, ctx.now()));
        }
        if (share == null) {
            evidence.put("reason", "download counts unavailable; cannot rule out typosquatting");
            return List.of(Rules.finding(ctx.runId(), Rules.TYPO, title, Severity.MEDIUM, Decision.WARN,
                    ctx.location(), evidence, ctx.now()));
        }
        evidence.put("reason", "similar name but established package");
        return List.of(Rules.finding(ctx.runId(), Rules.TYPO, title, Severity.LOW, Decision.WARN,
                ctx.location(), evidence, ctx.now()));
    }
}
