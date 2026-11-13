package io.gate.prompt.model;

import java.util.List;

/**
 * 실습장 "주문 도우미"가 쓰는 AI 모델 (설계안 4장). 출력은 언제나 신뢰하지 않는 데이터로 다룬다.
 */
public interface AssistantModel {

    /** 사용자 질문을 주문 조회 SQL로 바꾼다 (테이블 orders, 현재 사용자 ID를 알려 준다). */
    String orderSql(String question, long currentUserId);

    /** 상품 리뷰들을 HTML 요약으로 만든다. */
    String reviewSummary(List<String> reviews);

    /** 요청을 동작 JSON {@code {"action": ..., "args": {...}}}으로 바꾼다. */
    String orderAction(String request);
}
