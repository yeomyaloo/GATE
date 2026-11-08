package io.gate.pkg.check;

import io.gate.common.finding.Decision;
import io.gate.common.finding.Finding;
import io.gate.common.finding.Location;
import io.gate.common.finding.Severity;
import io.gate.pkg.registry.PackageInfo;
import io.gate.pkg.registry.PackageRequest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * PKG-EXIST: 레지스트리에 없는 패키지·버전.
 * 패키지가 아예 없으면 AI가 지어낸 이름일 가능성이 크다(BLOCK·CRITICAL).
 * 패키지는 있지만 요청한 정확한 버전이 없으면 BLOCK·HIGH.
 */
public final class ExistCheck implements Check {

    /** 레지스트리가 404로 답한 경우. 다른 검사는 할 수 없으므로 스캐너가 직접 부른다. */
    public static Finding packageMissing(UUID runId, PackageRequest request, String url, Instant now) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("registryUrl", url);
        evidence.put("httpStatus", 404);
        evidence.put("hint", "Package does not exist on the registry. AI assistants sometimes invent plausible "
                + "package names; an attacker can later register such a name.");
        return Rules.finding(runId, Rules.EXIST,
                "Package '" + request.name() + "' does not exist on " + request.ecosystem().id(),
                Severity.CRITICAL, Decision.BLOCK,
                new Location.Package(request.ecosystem().id(), request.name(), request.versionSpec()),
                evidence, now);
    }

    @Override
    public List<Finding> evaluate(CheckContext ctx) {
        PackageInfo info = ctx.info();
        if (!info.versionMissing()) {
            return List.of();
        }
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("registryUrl", info.registryUrl());
        evidence.put("requestedVersion", info.requestedSpec());
        if (info.latestVersion() != null) {
            evidence.put("latestVersion", info.latestVersion());
        }
        return List.of(Rules.finding(ctx.runId(), Rules.EXIST,
                "Version '" + info.requestedSpec() + "' of '" + info.name() + "' does not exist",
                Severity.HIGH, Decision.BLOCK, ctx.location(), evidence, ctx.now()));
    }
}
