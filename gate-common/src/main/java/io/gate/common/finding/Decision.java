package io.gate.common.finding;

/** 관문이 내린 판정. 훅·Action은 BLOCK이 하나라도 있으면 진행을 멈춘다. */
public enum Decision {
    BLOCK, WARN, PASS
}
