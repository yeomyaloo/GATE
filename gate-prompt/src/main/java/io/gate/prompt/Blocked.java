package io.gate.prompt;

/**
 * 관문이 AI 출력을 막은 이유. 규칙 ID와 위반 위치(가능하면 원문 조각)를 함께 남긴다 (AI 판단이 아니라 결정적 검사 결과).
 *
 * @param ruleId  예: PROMPT-SQL-TABLE
 * @param reason  사람이 읽는 설명 (영어, 로그·결과 저장용)
 * @param snippet 문제 부분 (원문 일부, 최대 200자)
 */
public record Blocked(String ruleId, String reason, String snippet) {

    static final int MAX_SNIPPET = 200;

    public Blocked {
        if (snippet != null && snippet.length() > MAX_SNIPPET) {
            snippet = snippet.substring(0, MAX_SNIPPET) + "…";
        }
    }
}
