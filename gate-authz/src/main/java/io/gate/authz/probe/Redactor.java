package io.gate.authz.probe;

import java.util.Map;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 결과 근거에 넣을 응답 본문을 줄이고 민감한 값을 가린다.
 * 키 이름은 남기고 값만 "***(N자)"로 바꾼다. "남의 주소가 보였다"는 사실은 남기되 주소 자체는 저장하지 않기 위해서다.
 */
public final class Redactor {

    static final int MAX_EXCERPT = 400;
    private static final Pattern SENSITIVE = Pattern.compile(
            "(?i).*(password|passwd|secret|token|authorization|api[-_]?key|email|phone|address|ssn|card|birth).*");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private Redactor() {
    }

    public static String excerpt(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String text;
        try {
            JsonNode node = JSON.readTree(body);
            text = JSON.writeValueAsString(redact(node));
        } catch (JacksonException e) {
            text = body.replaceAll("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]+", "$1***");
        }
        return text.length() <= MAX_EXCERPT ? text : text.substring(0, MAX_EXCERPT) + "…";
    }

    static JsonNode redact(JsonNode node) {
        if (node.isObject()) {
            ObjectNode out = JSON.createObjectNode();
            for (Map.Entry<String, JsonNode> e : node.properties()) {
                if (SENSITIVE.matcher(e.getKey()).matches() && e.getValue().isValueNode()) {
                    out.put(e.getKey(), "***(" + e.getValue().asString("").length() + " chars)");
                } else {
                    out.set(e.getKey(), redact(e.getValue()));
                }
            }
            return out;
        }
        if (node.isArray()) {
            var arr = JSON.createArrayNode();
            node.forEach(n -> arr.add(redact(n)));
            return arr;
        }
        return node;
    }

    /** 토큰은 앞 6자만 남긴다 (로그·오류 메시지용). */
    public static String maskToken(String token) {
        if (token == null) {
            return "null";
        }
        return token.length() <= 6 ? "***" : token.substring(0, 6) + "***";
    }
}
