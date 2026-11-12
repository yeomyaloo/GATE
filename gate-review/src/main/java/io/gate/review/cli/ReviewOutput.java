package io.gate.review.cli;

import io.gate.common.finding.Finding;
import io.gate.common.finding.Location;
import io.gate.review.ai.AiResult;
import io.gate.review.merge.ReviewReport;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** 검토 결과를 글·JSON으로 바꾼다. */
public final class ReviewOutput {

    static final JsonMapper JSON = JsonMapper.builder().build();

    private ReviewOutput() {
    }

    public static String text(ReviewReport r) {
        StringBuilder sb = new StringBuilder("GATE gate-review  static: ").append(r.staticEngine()).append("  AI: ")
                .append(aiLine(r.ai())).append("\n\n");
        for (Finding f : r.findings()) {
            Location.File loc = (Location.File) f.location();
            sb.append(String.format("%-5s %-11s %-8s %s:%d  %s%n", f.decision(), f.source(), f.severity(),
                    loc.path(), loc.line(), f.title()));
        }
        if (!r.rejected().isEmpty()) {
            sb.append("\nDiscarded AI findings (quoted code not found): ").append(r.rejected().size()).append('\n');
            r.rejected().forEach(x -> sb.append("  ").append(x.finding().file()).append(':')
                    .append(x.finding().line()).append("  ").append(x.reason()).append('\n'));
        }
        for (String e : r.staticErrors()) {
            sb.append("static analysis error: ").append(e).append('\n');
        }
        sb.append("\nResult: ").append(r.decision()).append(" (").append(r.findings().size()).append(" finding")
                .append(r.findings().size() == 1 ? "" : "s").append(")\n");
        return sb.toString();
    }

    static String aiLine(AiResult ai) {
        if (ai.status() == AiResult.Status.OK) {
            return ai.model() + " (" + ai.inputTokens() + " in / " + ai.outputTokens() + " out tokens)";
        }
        return ai.status() + (ai.detail() == null ? "" : " - " + ai.detail());
    }

    public static String json(ReviewReport r) {
        ObjectNode root = JSON.createObjectNode();
        root.put("runId", r.runId().toString());
        root.put("decision", r.decision().name());
        root.put("staticEngine", r.staticEngine());
        ObjectNode ai = root.putObject("ai");
        ai.put("status", r.ai().status().name());
        ai.put("model", r.ai().model());
        ai.put("inputTokens", r.ai().inputTokens());
        ai.put("outputTokens", r.ai().outputTokens());
        ai.put("detail", r.ai().detail());
        ArrayNode findings = root.putArray("findings");
        for (Finding f : r.findings()) {
            Location.File loc = (Location.File) f.location();
            ObjectNode n = findings.addObject();
            n.put("ruleId", f.ruleId());
            n.put("title", f.title());
            n.put("severity", f.severity().name());
            n.put("decision", f.decision().name());
            n.put("source", f.source().name());
            n.put("file", loc.path());
            n.put("line", loc.line());
            n.set("evidence", JSON.valueToTree(f.evidence()));
        }
        ArrayNode rejected = root.putArray("rejectedAiFindings");
        r.rejected().forEach(x -> {
            ObjectNode n = rejected.addObject();
            n.put("file", x.finding().file());
            n.put("line", x.finding().line());
            n.put("cwe", x.finding().cwe());
            n.put("reason", x.reason());
        });
        ArrayNode errors = root.putArray("staticErrors");
        r.staticErrors().forEach(errors::add);
        return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root);
    }
}
