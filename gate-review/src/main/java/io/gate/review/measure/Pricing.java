package io.gate.review.measure;

import io.gate.review.SourceFile;
import io.gate.review.ai.ReviewPrompt;
import io.gate.review.staticscan.StaticHit;
import java.util.List;

/**
 * 토큰 → 비용 (미국 달러). 단가는 100만 토큰당.
 * 프롬프트 캐시 쓰기·읽기 토큰은 따로 세지 않는다 (지침이 짧아 영향이 작다). 문서에 상한 추정으로 적는다.
 */
public record Pricing(String model, double inputPerMillion, double outputPerMillion) {

    /** Claude Opus 5.5: 입력 $4, 출력 $20 (설계안 결정 2). */
    public static final Pricing OPUS_5_5 = new Pricing("claude-opus-5-5", 4.0, 20.0);

    /** 호출 전 추정에 쓰는 출력 토큰 수 (추론 포함). 실측으로 고친다. */
    public static final long ASSUMED_OUTPUT_TOKENS = 10_000;

    /** 글자 수로 입력 토큰을 어림한다 (코드는 대략 3글자당 1토큰으로 넉넉하게). */
    static final double CHARS_PER_TOKEN = 3.0;

    public double cost(long inputTokens, long outputTokens) {
        return inputTokens / 1_000_000.0 * inputPerMillion + outputTokens / 1_000_000.0 * outputPerMillion;
    }

    public static long estimateInputTokens(List<SourceFile> files, List<StaticHit> hints) {
        int chars = ReviewPrompt.SYSTEM.length() + ReviewPrompt.userMessage(files, hints).length();
        return (long) Math.ceil(chars / CHARS_PER_TOKEN);
    }
}
