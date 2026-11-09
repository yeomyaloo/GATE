package io.gate.authz.cli;

import io.gate.authz.config.AuthzConfig;
import io.gate.authz.probe.AuthzReport;
import io.gate.common.finding.Finding;
import io.gate.common.finding.Location;
import java.time.Instant;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** 검사 결과를 글·JSON·gate-server 요청 본문으로 바꾼다. */
public final class AuthzReports {

    static final JsonMapper JSON = JsonMapper.builder().build();

    private AuthzReports() {
    }

    public static String text(AuthzReport report, AuthzConfig config, String spec) {
        StringBuilder sb = new StringBuilder();
        sb.append("GATE gate-authz: ").append(config.baseUrl()).append("  (spec: ").append(spec)
                .append(", users: ").append(config.userA().username()).append(" -> ").append(config.userB().username())
                .append(", mutation: ").append(config.allowMutation() ? "on" : "off").append(")\n\n");
        for (AuthzReport.Coverage c : report.coverage()) {
            sb.append(String.format("%-12s %-28s %-10s %s%n", c.status(), c.operation(), c.check(), c.detail()));
        }
        if (!report.findings().isEmpty()) {
            sb.append("\nFindings:\n");
            for (Finding f : report.findings()) {
                sb.append(String.format("  %-20s %-9s %s%n", f.ruleId(), f.severity(), f.title()));
                Object id = f.evidence().get("objectId");
                if (id != null) {
                    sb.append("      object ").append(id).append(" (").append(f.evidence().get("objectSource"))
                            .append(")\n");
                }
            }
        }
        long blocking = report.findings().stream()
                .filter(f -> f.decision() == io.gate.common.finding.Decision.BLOCK).count();
        sb.append("\nResult: ").append(report.decision()).append(" (").append(blocking).append(" finding")
                .append(blocking == 1 ? "" : "s").append(")\n");
        return sb.toString();
    }

    public static String json(AuthzReport report, AuthzConfig config) {
        ObjectNode root = JSON.createObjectNode();
        root.put("runId", report.runId().toString());
        root.put("target", config.baseUrl());
        root.put("decision", report.decision().name());
        root.put("allowMutation", config.allowMutation());
        root.set("findings", findings(report));
        ArrayNode cov = root.putArray("coverage");
        for (AuthzReport.Coverage c : report.coverage()) {
            ObjectNode n = cov.addObject();
            n.put("operation", c.operation());
            n.put("check", c.check());
            n.put("status", c.status().name());
            n.put("detail", c.detail());
        }
        return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root);
    }

    /** gate-server {@code POST /api/scan-runs} 본문 (gate-server ScanRunRequest와 같은 모양). */
    public static String serverBody(AuthzReport report, AuthzConfig config, String trigger, Instant startedAt,
            Instant finishedAt) {
        ObjectNode root = JSON.createObjectNode();
        root.put("module", "AUTHZ");
        root.put("trigger", trigger);
        root.put("target", config.baseUrl());
        root.put("startedAt", startedAt.toString());
        root.put("finishedAt", finishedAt.toString());
        root.set("findings", findings(report));
        return JSON.writeValueAsString(root);
    }

    private static ArrayNode findings(AuthzReport report) {
        ArrayNode arr = JSON.createArrayNode();
        for (Finding f : report.findings()) {
            ObjectNode n = arr.addObject();
            n.put("ruleId", f.ruleId());
            n.put("title", f.title());
            n.put("severity", f.severity().name());
            n.put("decision", f.decision().name());
            ObjectNode loc = n.putObject("location");
            loc.put("kind", f.location().kind());
            if (f.location() instanceof Location.Endpoint e) {
                loc.put("method", e.method());
                loc.put("path", e.path());
            }
            n.set("evidence", JSON.valueToTree(f.evidence()));
            n.put("source", f.source().name());
            n.put("detectedAt", f.detectedAt().toString());
        }
        return arr;
    }
}
