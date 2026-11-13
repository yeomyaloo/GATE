package io.gate.prompt.input;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 입력 검사 (보조 수단, 설계안 1장): AI에 들어가는 글(사용자 질문, 리뷰처럼 AI가 읽는 데이터)에서 인젝션 신호를 찾는다.
 *
 * <p><b>막지 않는다.</b> 이런 문장 탐지는 표현을 바꾸면 쉽게 우회되고, 정상 글을 막는 오탐도 생긴다.
 * 신호는 기록·경고에만 쓰고, 막는 힘은 출력 경계(SqlGuard·HtmlGuard·ActionGuard)에서 나온다.
 */
public final class InputInspector {

    public static final String OVERRIDE = "PROMPT-IN-OVERRIDE";
    public static final String ROLE = "PROMPT-IN-ROLE";
    public static final String HIDDEN = "PROMPT-IN-HIDDEN";
    public static final String CODE = "PROMPT-IN-CODE";

    /** 신호 하나. excerpt는 찾은 부분 (최대 80자). */
    public record Signal(String ruleId, String excerpt) {
        public Signal {
            excerpt = excerpt.length() > 80 ? excerpt.substring(0, 80) + "…" : excerpt;
        }
    }

    private record Rule(String id, Pattern pattern) {
    }

    private static final List<Rule> RULES = List.of(
            // 앞의 지시를 무시·덮어쓰라는 요구
            new Rule(OVERRIDE, Pattern.compile("(ignore|disregard|forget|override|bypass)\\s+(all\\s+|any\\s+|the\\s+"
                    + "|your\\s+)*(previous|prior|above|earlier|system|original)?\\s*(instructions?|rules?|prompts?"
                    + "|guidelines?|directions?)")),
            new Rule(OVERRIDE, Pattern.compile("(이전|앞의|위의|기존|모든)\\s*(지시|명령|규칙|지침|프롬프트)[을를은는]?\\s*"
                    + "(모두\\s*)?(무시|잊어|따르지)")),
            new Rule(OVERRIDE, Pattern.compile("new\\s+instructions?\\s*:|from\\s+now\\s+on\\s*,?\\s*you")),
            // 역할 사칭·대화 형식 위조
            new Rule(ROLE, Pattern.compile("(^|\\n)\\s*(system|assistant|developer)\\s*:|<\\|?(im_start|im_end|system)"
                    + "\\|?>|\\[/?inst]|<</?sys>>|you\\s+are\\s+now\\s+|developer\\s+mode|act\\s+as\\s+(an?\\s+)?"
                    + "(admin|administrator|root|system)")),
            // 실행될 수 있는 코드 조각 (SQL·HTML·셸)
            new Rule(CODE, Pattern.compile("\\b(select\\b[^;]{0,200}\\bfrom|union\\s+(all\\s+)?select|drop\\s+table"
                    + "|delete\\s+from|insert\\s+into|update\\s+\\w+\\s+set)\\b")),
            new Rule(CODE, Pattern.compile("<\\s*(script|iframe|img|svg|object|embed|form)\\b|javascript\\s*:"
                    + "|\\bon(error|load|click|mouseover)\\s*=")),
            new Rule(CODE, Pattern.compile("(;|&&|\\|\\|)\\s*(rm|curl|wget|sh|bash|nc|powershell)\\b|\\$\\(|`[^`]+`"
                    + "|\\|\\s*(sh|bash)\\b")));

    private InputInspector() {
    }

    public static List<Signal> inspect(String text) {
        List<Signal> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        if (HiddenText.hasHidden(text)) {
            out.add(new Signal(HIDDEN, "hidden or control characters"));
        }
        // 숨김 문자를 빼고, 전각·호환 문자를 보통 글자로 바꾼 뒤 소문자로 검사한다 (단순 변형 우회 줄이기)
        String visible = text.codePoints().filter(c -> !HiddenText.isHidden(c))
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
        String normalized = Normalizer.normalize(visible, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        for (Rule r : RULES) {
            Matcher m = r.pattern().matcher(normalized);
            if (m.find() && out.stream().noneMatch(s -> s.ruleId().equals(r.id()))) {
                out.add(new Signal(r.id(), m.group().strip()));
            }
        }
        return out;
    }
}
