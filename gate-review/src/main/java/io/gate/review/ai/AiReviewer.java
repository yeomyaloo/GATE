package io.gate.review.ai;

import io.gate.review.SourceFile;
import io.gate.review.staticscan.StaticHit;
import java.util.List;

/**
 * AI 검토 경계. 실제 구현은 2주차(공식 Anthropic Java SDK, 구조화 출력). 테스트는 가짜 구현을 쓴다.
 * 구현은 예외를 던지지 않고 실패를 {@link AiResult.Status}로 알린다.
 *
 * @param staticHints 정적 분석 결과. AI가 참고하되, AI가 같은 것을 지적했는지는 합칠 때 따로 판단한다
 */
public interface AiReviewer {

    AiResult review(List<SourceFile> files, List<StaticHit> staticHints);
}
