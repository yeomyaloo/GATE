package io.gate.review.staticscan;

/**
 * 정적 분석 결과 하나.
 *
 * @param ruleId   규칙 ID (경로 접두어를 뗀 이름, 예: gate-java-sql-string-concat)
 * @param path     저장소 기준 상대 경로 (슬래시 구분)
 * @param cwe      "CWE-89" 형식. 규칙에 없으면 null
 * @param severity ERROR·WARNING·INFO
 */
public record StaticHit(String ruleId, String path, int startLine, int endLine, String message, String cwe,
        String severity) {

    public StaticHit {
        path = path.replace('\\', '/');
        if (endLine < startLine) {
            endLine = startLine;
        }
    }
}
