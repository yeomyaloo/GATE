package io.gate.pkg.check;

import io.gate.common.finding.Decision;
import io.gate.common.finding.Finding;
import io.gate.common.finding.Severity;
import io.gate.pkg.registry.OsvClient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * PKG-VULN: OSV에 보고된 악성 코드·취약점 (설계안 확정 결정 2번).
 * <ul>
 *   <li>{@code MAL-} 악성 패키지 보고 → BLOCK·CRITICAL</li>
 *   <li>취약점 CRITICAL → BLOCK·CRITICAL</li>
 *   <li>HIGH 이하 → WARN (심각도 그대로). 심각도를 모르면 WARN·MEDIUM</li>
 * </ul>
 * 같은 패키지의 취약점 여러 개는 결과 하나로 묶고, 가장 강한 판정을 따른다.
 */
public final class VulnCheck implements Check {

    static final int MAX_LISTED = 10;
    public static final String MALICIOUS_KEY = "malicious";

    @Override
    public List<Finding> evaluate(CheckContext ctx) {
        OsvClient.Result result = ctx.vulns();
        if (result == null || ctx.info().versionMissing()) {
            return List.of();
        }
        if (result instanceof OsvClient.Result.Unknown unknown) {
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("check", Rules.VULN);
            evidence.put("url", unknown.url());
            evidence.put("reason", unknown.reason());
            return List.of(Rules.finding(ctx.runId(), Rules.UNCHECKED,
                    "Could not check known vulnerabilities for '" + ctx.info().name() + "'",
                    Severity.INFO, Decision.WARN, ctx.location(), evidence, ctx.now()));
        }
        List<OsvClient.Vulnerability> vulns = ((OsvClient.Result.Known) result).vulnerabilities();
        if (vulns.isEmpty()) {
            return List.of();
        }

        List<Finding> findings = new ArrayList<>();
        List<OsvClient.Vulnerability> malicious = vulns.stream().filter(OsvClient.Vulnerability::malicious).toList();
        if (!malicious.isEmpty()) {
            Map<String, Object> evidence = evidence(malicious);
            evidence.put(MALICIOUS_KEY, true); // 오탐 예외 파일로도 풀 수 없는 결과라는 표시
            findings.add(Rules.finding(ctx.runId(), Rules.VULN,
                    "'" + ctx.info().name() + "' is reported as MALICIOUS (" + malicious.get(0).id() + ")",
                    Severity.CRITICAL, Decision.BLOCK, ctx.location(), evidence, ctx.now()));
        }

        List<OsvClient.Vulnerability> regular = vulns.stream().filter(v -> !v.malicious()).toList();
        if (!regular.isEmpty()) {
            Severity worst = regular.stream()
                    .map(v -> v.severity() == null ? Severity.MEDIUM : v.severity())
                    .min(Enum::compareTo).orElse(Severity.MEDIUM);
            Decision decision = worst == Severity.CRITICAL ? Decision.BLOCK : Decision.WARN;
            findings.add(Rules.finding(ctx.runId(), Rules.VULN,
                    "'" + ctx.info().name() + "@" + ctx.info().resolvedVersion() + "' has " + regular.size()
                            + " known vulnerabilit" + (regular.size() == 1 ? "y" : "ies") + " (worst: " + worst + ")",
                    worst, decision, ctx.location(), evidence(regular), ctx.now()));
        }
        return findings;
    }

    private static Map<String, Object> evidence(List<OsvClient.Vulnerability> vulns) {
        List<Map<String, Object>> listed = new ArrayList<>();
        for (OsvClient.Vulnerability v : vulns.subList(0, Math.min(MAX_LISTED, vulns.size()))) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", v.id());
            item.put("severity", v.severity() == null ? "UNKNOWN" : v.severity().name());
            if (!v.aliases().isEmpty()) {
                item.put("aliases", List.copyOf(new TreeSet<>(v.aliases())));
            }
            if (v.summary() != null) {
                item.put("summary", v.summary());
            }
            item.put("url", "https://osv.dev/vulnerability/" + v.id());
            listed.add(item);
        }
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("count", vulns.size());
        evidence.put("vulnerabilities", listed);
        return evidence;
    }
}
