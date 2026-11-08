package io.gate.pkg.check;

import io.gate.common.finding.Decision;
import io.gate.common.finding.Finding;
import io.gate.common.finding.Severity;
import io.gate.pkg.registry.PackageInfo;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PKG-NEW: 갓 게시된 패키지나 버전.
 * <ul>
 *   <li>패키지 첫 게시 후 {@link Thresholds#newPackage()} 미만 → WARN·MEDIUM (선점·철자 흉내 패키지는 대개 새것이다)</li>
 *   <li>요청 버전 게시 후 {@link Thresholds#newVersion()} 미만 → WARN·MEDIUM (계정 탈취 후 악성 버전 게시 패턴)</li>
 * </ul>
 */
public final class NewPackageCheck implements Check {

    @Override
    public List<Finding> evaluate(CheckContext ctx) {
        PackageInfo info = ctx.info();
        List<Finding> findings = new ArrayList<>();

        if (isWithin(info.createdAt(), ctx.now(), ctx.thresholds().newPackage())) {
            findings.add(Rules.finding(ctx.runId(), Rules.NEW,
                    "Package '" + info.name() + "' was first published " + age(info.createdAt(), ctx.now()) + " ago",
                    Severity.MEDIUM, Decision.WARN, ctx.location(),
                    evidence("createdAt", info.createdAt(), ctx.now(), ctx.thresholds().newPackage()), ctx.now()));
        }

        if (!info.versionMissing()
                && isWithin(info.versionPublishedAt(), ctx.now(), ctx.thresholds().newVersion())
                // 패키지 자체가 새것이면 첫 버전도 당연히 새것이므로 같은 경고를 두 번 내지 않는다
                && findings.isEmpty()) {
            findings.add(Rules.finding(ctx.runId(), Rules.NEW,
                    "Version '" + info.resolvedVersion() + "' of '" + info.name() + "' was published "
                            + age(info.versionPublishedAt(), ctx.now()) + " ago",
                    Severity.MEDIUM, Decision.WARN, ctx.location(),
                    evidence("versionPublishedAt", info.versionPublishedAt(), ctx.now(),
                            ctx.thresholds().newVersion()), ctx.now()));
        }
        return findings;
    }

    private static boolean isWithin(Instant publishedAt, Instant now, Duration window) {
        // 미래 시각(시계 오차)도 "새것"으로 본다
        return publishedAt != null && Duration.between(publishedAt, now).compareTo(window) < 0;
    }

    private static Map<String, Object> evidence(String field, Instant at, Instant now, Duration threshold) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put(field, at.toString());
        evidence.put("ageHours", Math.max(0, Duration.between(at, now).toHours()));
        evidence.put("thresholdHours", threshold.toHours());
        return evidence;
    }

    private static String age(Instant at, Instant now) {
        Duration d = Duration.between(at, now);
        if (d.isNegative()) {
            return "0 hours";
        }
        return d.toDays() >= 1 ? d.toDays() + " day(s)" : d.toHours() + " hour(s)";
    }
}
