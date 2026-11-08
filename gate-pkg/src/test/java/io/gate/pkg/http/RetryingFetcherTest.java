package io.gate.pkg.http;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.Test;

class RetryingFetcherTest {

    /** 정해 둔 순서대로 응답하는 가짜. */
    static final class Sequence implements HttpFetcher {
        final Deque<Integer> statuses;
        int calls;

        Sequence(Integer... statuses) {
            this.statuses = new ArrayDeque<>(List.of(statuses));
        }

        @Override
        public HttpResult get(URI uri) {
            calls++;
            return new HttpResult(statuses.isEmpty() ? 200 : statuses.poll(), "{}");
        }

        @Override
        public HttpResult postJson(URI uri, String json) {
            return get(uri);
        }
    }

    final List<Long> waits = new ArrayList<>();
    static final URI U = URI.create("https://api.npmjs.org/downloads/point/last-week/x");

    @Test
    void retriesRateLimitThenSucceeds() throws Exception {
        Sequence s = new Sequence(429, 200);
        assertEquals(200, new RetryingFetcher(s, waits::add).get(U).status());
        assertEquals(2, s.calls);
        assertEquals(List.of(400L), waits);
    }

    @Test
    void givesUpAfterTwoRetries() throws Exception {
        Sequence s = new Sequence(429, 503, 429, 200);
        assertEquals(429, new RetryingFetcher(s, waits::add).get(U).status());
        assertEquals(3, s.calls);
    }

    @Test
    void otherStatusesAreNotRetried() throws Exception {
        for (int status : new int[] {200, 404, 500}) {
            Sequence s = new Sequence(status);
            assertEquals(status, new RetryingFetcher(s, waits::add).postJson(U, "{}").status());
            assertEquals(1, s.calls);
        }
        assertEquals(List.of(), waits);
    }
}
