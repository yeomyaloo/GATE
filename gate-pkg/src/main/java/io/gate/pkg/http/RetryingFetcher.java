package io.gate.pkg.http;

import java.io.IOException;
import java.net.URI;

/**
 * 요청 제한(429)·일시 장애(503)에 짧게 다시 시도한다.
 * 측정(2026-11-09)에서 npm 다운로드 API가 짧은 시간에 몰린 요청 60건 중 17건을 429로 거절했다.
 * 훅이 오래 기다리지 않도록 대기는 짧게, 최대 2번만 다시 시도한다.
 */
public final class RetryingFetcher implements HttpFetcher {

    /** 다시 시도하기 전 대기 시간(밀리초). */
    static final long[] BACKOFF_MS = {400, 1200};

    private final HttpFetcher delegate;
    private final Sleeper sleeper;

    /** 테스트에서 실제로 기다리지 않도록 바꿀 수 있다. */
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    public RetryingFetcher(HttpFetcher delegate) {
        this(delegate, Thread::sleep);
    }

    RetryingFetcher(HttpFetcher delegate, Sleeper sleeper) {
        this.delegate = delegate;
        this.sleeper = sleeper;
    }

    @Override
    public HttpResult get(URI uri) throws IOException {
        return withRetry(() -> delegate.get(uri));
    }

    @Override
    public HttpResult postJson(URI uri, String json) throws IOException {
        return withRetry(() -> delegate.postJson(uri, json));
    }

    private interface Call {
        HttpResult run() throws IOException;
    }

    private HttpResult withRetry(Call call) throws IOException {
        HttpResult result = call.run();
        for (long wait : BACKOFF_MS) {
            if (result.status() != 429 && result.status() != 503) {
                return result;
            }
            try {
                sleeper.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return result;
            }
            result = call.run();
        }
        return result;
    }
}
