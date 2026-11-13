package io.gate.prompt.action;

import io.gate.prompt.GuardResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * AI가 고른 "동작"을 실행하기 전의 관문 (설계안 2·3장). 명령 문자열은 받지 않는다.
 * <ul>
 *   <li>AI 출력은 정확히 {@code {"action": "<이름>", "args": {...}}} 모양의 JSON 하나여야 한다
 *       (키 중복·뒤에 붙은 글자·다른 키는 차단)</li>
 *   <li>동작 이름은 정책에 등록된 것만. 인자 이름과 형식도 정책과 정확히 같아야 한다</li>
 *   <li>주문 ID 같은 대상은 현재 사용자의 것인지 확인한다 (AI가 남의 주문을 고르면 차단)</li>
 * </ul>
 * 통과한 결과는 동작 이름과 검사한 인자뿐이다. 실행은 앱이 정해 둔 자바 메서드가 한다(셸·문자열 실행 없음).
 */
public final class ActionGuard {

    public static final String FORMAT = "PROMPT-ACTION-FORMAT";
    public static final String UNKNOWN = "PROMPT-ACTION-UNKNOWN";
    public static final String ARGS = "PROMPT-ACTION-ARGS";
    public static final String NOT_OWNER = "PROMPT-ACTION-NOT-OWNER";

    static final int MAX_INPUT = 2_000;
    static final int MAX_TEXT = 200;

    public enum ArgType {
        /** 양의 정수 ID이고 현재 사용자의 것이어야 한다. */
        OWNED_ID,
        /** 짧은 글 (최대 {@value ActionGuard#MAX_TEXT}자, 제어 문자·숨김 문자 없음). */
        SHORT_TEXT
    }

    /** 동작 하나의 정의. */
    public record ActionSpec(String name, Map<String, ArgType> args) {
        public ActionSpec {
            args = Map.copyOf(args);
        }
    }

    /** 대상이 현재 사용자의 것인지 앱이 판단한다 (예: 주문의 user_id 비교). */
    @FunctionalInterface
    public interface Ownership {
        boolean owns(Object currentUser, String action, String arg, long id);
    }

    /** 검사를 통과한 동작. args 값은 Long(OWNED_ID) 또는 String(SHORT_TEXT). */
    public record ActionCall(String action, Map<String, Object> args) {
        public ActionCall {
            args = Map.copyOf(args);
        }
    }

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private final Map<String, ActionSpec> specs = new LinkedHashMap<>();
    private final Ownership ownership;

    public ActionGuard(List<ActionSpec> actions, Ownership ownership) {
        actions.forEach(a -> specs.put(a.name(), a));
        this.ownership = ownership;
    }

    public GuardResult<ActionCall> check(String aiOutput, Object currentUser) {
        if (aiOutput == null || aiOutput.isBlank()) {
            return GuardResult.block(FORMAT, "empty action", "");
        }
        if (aiOutput.length() > MAX_INPUT) {
            return GuardResult.block(FORMAT, "action is too long", aiOutput);
        }
        JsonNode root;
        try {
            root = JSON.readTree(aiOutput.strip());
        } catch (JacksonException e) {
            return GuardResult.block(FORMAT, "action must be a single JSON object", aiOutput);
        }
        if (root == null || !root.isObject() || !keys(root).equals(Set.of("action", "args"))
                || !root.get("action").isString() || !root.get("args").isObject()) {
            return GuardResult.block(FORMAT, "expected exactly {\"action\": string, \"args\": object}", aiOutput);
        }
        String name = root.get("action").asString();
        ActionSpec spec = specs.get(name);
        if (spec == null) {
            return GuardResult.block(UNKNOWN, "action not allowed: " + name + " (allowed: " + specs.keySet() + ")",
                    name);
        }
        JsonNode args = root.get("args");
        if (!keys(args).equals(spec.args().keySet())) {
            return GuardResult.block(ARGS, "arguments must be exactly " + new TreeSet<>(spec.args().keySet()),
                    args.toString());
        }
        Map<String, Object> checked = new LinkedHashMap<>();
        for (Map.Entry<String, ArgType> a : spec.args().entrySet()) {
            JsonNode v = args.get(a.getKey());
            if (a.getValue() == ArgType.OWNED_ID) {
                if (!v.isIntegralNumber() || !v.canConvertToLong() || v.asLong() < 1) {
                    return GuardResult.block(ARGS, a.getKey() + " must be a positive integer", v.toString());
                }
                long id = v.asLong();
                if (!ownership.owns(currentUser, name, a.getKey(), id)) {
                    return GuardResult.block(NOT_OWNER, a.getKey() + " " + id + " does not belong to the current user",
                            String.valueOf(id));
                }
                checked.put(a.getKey(), id);
            } else {
                if (!v.isString() || v.asString().length() > MAX_TEXT || hasControl(v.asString())) {
                    return GuardResult.block(ARGS, a.getKey() + " must be short plain text", v.toString());
                }
                checked.put(a.getKey(), v.asString());
            }
        }
        return GuardResult.allow(new ActionCall(name, checked));
    }

    private static Set<String> keys(JsonNode node) {
        List<String> names = new ArrayList<>(node.propertyNames());
        return Set.copyOf(names);
    }

    /** 제어 문자, 제로폭·양방향 제어 문자 (화면에 안 보이게 지시를 숨기는 데 쓰인다). */
    static boolean hasControl(String s) {
        return s.codePoints().anyMatch(c -> Character.isISOControl(c) || c >= 0x200B && c <= 0x200F
                || c >= 0x202A && c <= 0x202E || c >= 0x2060 && c <= 0x2064 || c >= 0x2066 && c <= 0x2069
                || c == 0xFEFF || c >= 0xE0000 && c <= 0xE007F);
    }
}
