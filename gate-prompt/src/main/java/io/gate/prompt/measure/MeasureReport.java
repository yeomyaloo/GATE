package io.gate.prompt.measure;

import io.gate.prompt.measure.PromptMeasurement.Defense;
import io.gate.prompt.measure.PromptMeasurement.Outcome;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** 측정 결과 요약·JSON·gate-server 전송 본문. */
public final class MeasureReport {

    static final JsonMapper JSON = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    /** 방어 방식 하나의 요약. */
    public record Summary(Defense defense, int attacks, int attacksSucceeded, int benign, int benignFailed,
            List<String> succeededIds, List<String> failedBenignIds) {

        public int attacksStopped() {
            return attacks - attacksSucceeded;
        }
    }

    private MeasureReport() {
    }

    public static Map<Defense, Summary> summarize(List<Outcome> outcomes) {
        return summarize(outcomes, null);
    }

    /** @param feature null이면 전체 */
    public static Map<Defense, Summary> summarize(List<Outcome> outcomes, Corpus.Feature feature) {
        Map<Defense, Summary> out = new LinkedHashMap<>();
        for (Defense d : Defense.values()) {
            List<Outcome> mine = outcomes.stream().filter(o -> o.defense() == d)
                    .filter(o -> feature == null || o.feature() == feature).toList();
            List<String> succeeded = mine.stream().filter(o -> o.kind() == Corpus.Kind.ATTACK && o.harmful())
                    .map(Outcome::caseId).toList();
            List<String> failed = mine.stream().filter(o -> o.kind() == Corpus.Kind.BENIGN && !o.useful())
                    .map(Outcome::caseId).toList();
            int attacks = (int) mine.stream().filter(o -> o.kind() == Corpus.Kind.ATTACK).count();
            int benign = mine.size() - attacks;
            out.put(d, new Summary(d, attacks, succeeded.size(), benign, failed.size(), succeeded, failed));
        }
        return out;
    }

    public static String text(List<Outcome> outcomes) {
        StringBuilder sb = new StringBuilder("GATE gate-prompt measurement (scripted model that obeys injected"
                + " instructions)\n\n");
        sb.append(String.format("%-12s %-8s %-22s %-22s%n", "defense", "feature", "attacks stopped",
                "benign working"));
        for (Corpus.Feature f : new Corpus.Feature[] {null, Corpus.Feature.SQL, Corpus.Feature.HTML,
            Corpus.Feature.ACTION}) {
            for (Summary s : summarize(outcomes, f).values()) {
                sb.append(String.format("%-12s %-8s %-22s %-22s%n", s.defense(), f == null ? "all" : f.name(),
                        s.attacksStopped() + "/" + s.attacks(), (s.benign() - s.benignFailed()) + "/" + s.benign()));
            }
            sb.append('\n');
        }
        for (Summary s : summarize(outcomes).values()) {
            sb.append(s.defense()).append(": attacks that got through ").append(s.succeededIds())
                    .append(", benign cases broken ").append(s.failedBenignIds()).append('\n');
        }
        return sb.toString();
    }

    public static String json(List<Outcome> outcomes, Instant measuredAt) {
        ObjectNode root = JSON.createObjectNode();
        root.put("measuredAt", measuredAt.toString());
        root.put("model", "scripted (obeys any instruction in its input)");
        ObjectNode summary = root.putObject("summary");
        for (Summary s : summarize(outcomes).values()) {
            ObjectNode n = summary.putObject(s.defense().name());
            n.put("attacks", s.attacks());
            n.put("attacksStopped", s.attacksStopped());
            n.put("benign", s.benign());
            n.put("benignWorking", s.benign() - s.benignFailed());
            n.set("attacksThatGotThrough", JSON.valueToTree(s.succeededIds()));
            n.set("benignBroken", JSON.valueToTree(s.failedBenignIds()));
        }
        root.set("outcomes", JSON.valueToTree(outcomes));
        return JSON.writeValueAsString(root);
    }

    /**
     * gate-server {@code POST /api/scan-runs} 본문. 실습장 고친 버전과 같은 방식(출력 관문 + 입력 신호는 기록만,
     * {@link Defense#OUTPUT_ONLY})의 결과를 보낸다: 출력 관문 차단은 BLOCK, HTML 정리와 입력 신호만 있는 경우는 WARN.
     */
    public static String serverBody(List<Outcome> outcomes, Instant startedAt, Instant finishedAt) {
        ObjectNode root = JSON.createObjectNode();
        root.put("module", "PROMPT");
        root.put("trigger", "CLI");
        root.put("target", "gate-prompt measurement corpus");
        root.put("startedAt", startedAt.toString());
        root.put("finishedAt", finishedAt.toString());
        ArrayNode arr = root.putArray("findings");
        for (Outcome o : outcomes) {
            if (o.defense() != Defense.OUTPUT_ONLY || o.ruleId() == null && o.inputSignals().isEmpty()) {
                continue;
            }
            boolean output = o.status() == PromptMeasurement.Status.BLOCKED_OUTPUT;
            boolean html = !output && o.ruleId() != null;
            ObjectNode n = arr.addObject();
            n.put("ruleId", o.ruleId() != null ? o.ruleId() : o.inputSignals().get(0));
            n.put("title", (output ? "AI output blocked: " : html ? "Active HTML removed: "
                    : "Injection signal in input (recorded): ") + o.technique());
            n.put("severity", o.kind() == Corpus.Kind.ATTACK ? "HIGH" : "LOW");
            n.put("decision", output ? "BLOCK" : "WARN");
            ObjectNode loc = n.putObject("location");
            loc.put("kind", "endpoint");
            loc.put("method", "POST");
            loc.put("path", switch (o.feature()) {
                case SQL -> "/api/assistant/order-search";
                case HTML -> "/api/assistant/review-summary";
                case ACTION -> "/api/assistant/order-action";
            });
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("case", o.caseId());
            ev.put("kind", o.kind().name());
            ev.put("reason", o.detail());
            ev.put("inputSignals", new ArrayList<>(o.inputSignals()));
            n.set("evidence", JSON.valueToTree(ev));
            n.put("source", "RULE");
            n.put("detectedAt", finishedAt.toString());
        }
        return JSON.writeValueAsString(root);
    }
}
