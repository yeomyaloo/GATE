package io.gate.review.ai;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 구조화 출력 JSON을 {@link AiFinding} 목록으로 바꾼다. 형식이 틀리면 예외(호출하는 쪽이 1회 재시도). */
public final class AiResponseParser {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private AiResponseParser() {
    }

    public static List<AiFinding> parse(String json) {
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("AI response is not JSON: " + e.getOriginalMessage());
        }
        if (root == null || !root.path("findings").isArray()) {
            throw new IllegalArgumentException("AI response has no 'findings' array");
        }
        List<AiFinding> out = new ArrayList<>();
        for (JsonNode f : root.path("findings")) {
            if (!f.path("line").isNumber() || !f.path("file").isString()) {
                throw new IllegalArgumentException("AI finding without file/line: " + f);
            }
            out.add(new AiFinding(f.path("file").asString(), f.path("line").asInt(),
                    f.path("endLine").asInt(f.path("line").asInt()), f.path("cwe").asString(""),
                    f.path("title").asString(""), f.path("severity").asString("MEDIUM"),
                    f.path("evidence").asString(""), f.path("quotedCode").asString(""),
                    f.path("confidence").asString("medium")));
        }
        return out;
    }
}
