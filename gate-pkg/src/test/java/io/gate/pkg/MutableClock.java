package io.gate.pkg;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** 테스트에서 시간을 앞으로 돌릴 수 있는 시계. */
public final class MutableClock extends Clock {

    /** 모든 테스트의 기준 시각. 녹화본의 게시 시각은 이 값을 기준으로 만들었다. */
    public static final Instant NOW = Instant.parse("2026-11-09T00:00:00Z");

    private Instant instant;

    public MutableClock() {
        this(NOW);
    }

    public MutableClock(Instant instant) {
        this.instant = instant;
    }

    public void advance(Duration d) {
        instant = instant.plus(d);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return instant;
    }
}
