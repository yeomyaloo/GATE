package io.gate.review.ai;

import java.util.List;

/**
 * AI 검토 결과.
 *
 * @param status   OK가 아니면 AI 지적은 없고, 보고서에 "AI 검토 불가"로 표시한다 (통과로 처리하지 않음)
 * @param detail   상태 설명 (거절 사유, 오류 메시지 등). 비밀값을 넣지 않는다
 */
public record AiResult(Status status, List<AiFinding> findings, String model, long inputTokens, long outputTokens,
        String detail) {

    public enum Status {
        OK,
        /** API 키가 없음(포크 PR 등). 정적 분석만 한다 */
        SKIPPED,
        /** 모델이 요청을 거절함 (stop_reason: refusal) */
        REFUSED,
        /** 형식이 틀린 응답, 요청 제한, 서버 오류 등 */
        ERROR
    }

    public AiResult {
        findings = List.copyOf(findings);
    }

    public static AiResult skipped(String why) {
        return new AiResult(Status.SKIPPED, List.of(), null, 0, 0, why);
    }
}
