package io.gate.pkg.cli;

import io.gate.common.finding.Decision;
import io.gate.common.finding.Finding;
import io.gate.common.finding.Location;
import io.gate.pkg.check.Rules;
import io.gate.pkg.scan.AllowList;
import io.gate.pkg.scan.PackageVerdict;
import io.gate.pkg.scan.ScanReport;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** 검사 결과를 사람이 읽는 글, JSON, 훅 이유 문장으로 바꾼다. */
public final class Reports {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final int MAX_REASON_CHARS = 3000;

    private Reports() {
    }

    public static String text(ScanReport report, List<String> unchecked) {
        StringBuilder sb = new StringBuilder();
        for (PackageVerdict v : report.packages()) {
            sb.append(String.format("%-5s %s%n", v.decision(), label(v)));
            for (Finding f : v.findings()) {
                sb.append(String.format("      %-13s %-8s %s%s%n", f.ruleId(), f.severity(), f.title(),
                        f.evidence().containsKey("allowedBy") ? "  [allowed by " + f.evidence().get("allowedBy") + "]"
                                : ""));
            }
        }
        for (String u : unchecked) {
            sb.append("NOT CHECKED  ").append(u).append(System.lineSeparator());
        }
        sb.append("Result: ").append(report.decision());
        if (!unchecked.isEmpty() && report.decision() != Decision.BLOCK) {
            sb.append(" (with unchecked parts)");
        }
        return sb.append(System.lineSeparator()).toString();
    }

    public static String json(ScanReport report, List<String> unchecked) {
        ObjectNode root = JSON.createObjectNode();
        root.put("runId", report.runId().toString());
        root.put("decision", report.decision().name());
        ArrayNode packages = root.putArray("packages");
        for (PackageVerdict v : report.packages()) {
            ObjectNode p = packages.addObject();
            p.put("ecosystem", v.request().ecosystem().id());
            p.put("name", v.request().name());
            p.put("requested", v.request().versionSpec());
            p.put("checkedVersion", v.checkedVersion());
            p.put("decision", v.decision().name());
            ArrayNode findings = p.putArray("findings");
            for (Finding f : v.findings()) {
                ObjectNode fn = findings.addObject();
                fn.put("ruleId", f.ruleId());
                fn.put("title", f.title());
                fn.put("severity", f.severity().name());
                fn.put("decision", f.decision().name());
                fn.put("source", f.source().name());
                if (f.location() instanceof Location.Package loc) {
                    fn.put("version", loc.version());
                }
                fn.set("evidence", JSON.valueToTree(f.evidence()));
                fn.put("fingerprint", f.fingerprint());
                fn.put("detectedAt", f.detectedAt().toString());
            }
        }
        ArrayNode u = root.putArray("unchecked");
        unchecked.forEach(u::add);
        return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root);
    }

    /**
     * 훅의 permissionDecisionReason. deny일 때는 Claude가, ask일 때는 사용자가 읽는다.
     * 무엇이 왜 걸렸는지, 그리고 다음에 할 일을 짧게 적는다.
     */
    public static String hookReason(ScanReport report, List<String> unchecked, AllowList allow) {
        StringBuilder sb = new StringBuilder("GATE gate-pkg: ");
        Decision d = report.decision();
        sb.append(d == Decision.BLOCK ? "install BLOCKED" : "install needs confirmation").append('\n');
        String firstSimilar = null;
        String firstBlocked = null;
        for (PackageVerdict v : report.packages()) {
            if (v.decision() == Decision.PASS) {
                continue;
            }
            sb.append('[').append(v.decision()).append("] ").append(label(v)).append('\n');
            for (Finding f : v.findings()) {
                if (f.decision() == Decision.PASS) {
                    continue;
                }
                sb.append("  - ").append(f.ruleId()).append(" (").append(f.severity()).append("): ")
                        .append(f.title()).append('\n');
                if (firstSimilar == null && f.ruleId().equals(Rules.TYPO)) {
                    firstSimilar = String.valueOf(f.evidence().get("similarTo"));
                }
            }
            if (firstBlocked == null && v.decision() == Decision.BLOCK) {
                firstBlocked = allowSpec(v);
            }
        }
        for (String u : unchecked) {
            sb.append("[NOT CHECKED] ").append(u).append('\n');
        }
        for (String p : allow.problems()) {
            sb.append("[NOTE] ").append(p).append('\n');
        }
        if (d == Decision.BLOCK) {
            sb.append("Next: do not retry with another spelling or install method to get around this check. ");
            if (firstSimilar != null) {
                sb.append("Did you mean '").append(firstSimilar).append("'? ");
            }
            sb.append("Confirm the intended package with the user");
            if (firstBlocked != null) {
                sb.append("; if a human reviewed it as a false positive, they can add '").append(firstBlocked)
                        .append("  # reason' to ").append(AllowList.RELATIVE_PATH)
                        .append(" (nonexistent or malicious packages cannot be allowed)");
            }
            sb.append('.');
        }
        String reason = sb.toString().strip();
        return reason.length() <= MAX_REASON_CHARS ? reason : reason.substring(0, MAX_REASON_CHARS) + "…";
    }

    private static String label(PackageVerdict v) {
        String version = v.checkedVersion();
        if (version == null && !v.findings().isEmpty() && v.findings().get(0).location() instanceof Location.Package loc) {
            version = loc.version();
        }
        if (version == null) {
            version = v.request().versionSpec();
        }
        return v.request().ecosystem().id() + " " + v.request().name() + (version == null ? "" : "@" + version);
    }

    private static String allowSpec(PackageVerdict v) {
        boolean unoverridable = v.findings().stream()
                .anyMatch(f -> f.decision() == Decision.BLOCK && !AllowList.overridable(f));
        if (unoverridable || v.checkedVersion() == null || v.findings().isEmpty()
                || !(v.findings().get(0).location() instanceof Location.Package loc)) {
            return null; // 없는 패키지·악성 패키지는 예외로 풀 수 없으므로 예외 파일을 안내하지 않는다
        }
        return v.request().ecosystem().id() + ":" + loc.name() + "@" + v.checkedVersion();
    }

    static Map<String, Object> hookOutput(String decision, String reason) {
        return Map.of("hookSpecificOutput", Map.of(
                "hookEventName", "PreToolUse",
                "permissionDecision", decision,
                "permissionDecisionReason", reason));
    }
}
