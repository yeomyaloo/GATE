package io.gate.common.finding;

/**
 * 판단 주체. AI 단독(AI)으로는 BLOCK 판정을 낼 수 없다는 규칙을 모델 수준에서 강제하기 위해 둔다.
 */
public enum FindingSource {
    RULE, AI, RULE_AND_AI
}
