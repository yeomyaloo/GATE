package io.gate.prompt;

import java.util.Optional;

/**
 * 출력 경계 검사 결과: 통과(실행해도 되는 형태로 다시 만든 값) 또는 차단.
 * 통과한 값은 원래 AI 출력이 아니라 관문이 허용 목록으로 다시 만든 것이다.
 */
public sealed interface GuardResult<T> permits GuardResult.Allowed, GuardResult.Rejected {

    record Allowed<T>(T value) implements GuardResult<T> {
    }

    record Rejected<T>(Blocked blocked) implements GuardResult<T> {
    }

    static <T> GuardResult<T> allow(T value) {
        return new Allowed<>(value);
    }

    static <T> GuardResult<T> block(String ruleId, String reason, String snippet) {
        return new Rejected<>(new Blocked(ruleId, reason, snippet));
    }

    default boolean allowed() {
        return this instanceof Allowed;
    }

    default Optional<T> allowedValue() {
        return this instanceof Allowed<T> a ? Optional.of(a.value()) : Optional.empty();
    }

    default Optional<Blocked> rejection() {
        return this instanceof Rejected<T> r ? Optional.of(r.blocked()) : Optional.empty();
    }
}
