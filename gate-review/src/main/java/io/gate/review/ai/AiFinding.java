package io.gate.review.ai;

/**
 * AI가 낸 지적 하나 (구조화 출력 스키마와 같은 모양).
 *
 * @param quotedCode 문제라고 본 코드를 그대로 인용한 것. {@link QuoteVerifier}가 실제 코드와 대조한다
 * @param confidence low·medium·high
 */
public record AiFinding(String file, int line, int endLine, String cwe, String title, String severity,
        String evidence, String quotedCode, String confidence) {

    public AiFinding {
        file = file == null ? "" : file.replace('\\', '/');
        if (endLine < line) {
            endLine = line;
        }
    }
}
