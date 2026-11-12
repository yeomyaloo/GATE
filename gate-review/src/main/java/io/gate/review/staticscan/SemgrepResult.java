package io.gate.review.staticscan;

import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Semgrep JSON 출력에서 필요한 것만 읽은 결과. */
public record SemgrepResult(String version, List<StaticHit> hits, List<String> errors) {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    public SemgrepResult {
        hits = List.copyOf(hits);
        errors = List.copyOf(errors);
    }

    /** {@code semgrep scan --json} 출력. 오픈소스판의 {@code extra.lines}는 "requires login"이라 쓰지 않는다. */
    public static SemgrepResult parse(String json) {
        JsonNode root = JSON.readTree(json);
        List<StaticHit> hits = new java.util.ArrayList<>();
        for (JsonNode r : root.path("results")) {
            String checkId = r.path("check_id").asString("");
            String ruleId = checkId.substring(checkId.lastIndexOf('.') + 1); // 규칙 파일 경로 접두어 제거
            JsonNode cwe = r.path("extra").path("metadata").path("cwe");
            String cweText = cwe.isArray() && !cwe.isEmpty() ? cwe.get(0).asString("") : cwe.asString("");
            hits.add(new StaticHit(ruleId, r.path("path").asString(""), r.path("start").path("line").asInt(),
                    r.path("end").path("line").asInt(), r.path("extra").path("message").asString("").strip(),
                    io.gate.review.Cwe.normalize(cweText).orElse(null), r.path("extra").path("severity")
                            .asString("INFO")));
        }
        List<String> errors = new java.util.ArrayList<>();
        for (JsonNode e : root.path("errors")) {
            errors.add(e.path("type").asString("error") + ": " + e.path("message").asString(e.toString()));
        }
        return new SemgrepResult(root.path("version").asString(null), hits, errors);
    }
}
