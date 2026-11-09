package io.gate.authz.http;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * JDK HttpClient 구현.
 * <ul>
 *   <li>모든 요청 전에 {@link TargetGuard}로 대상 주소를 확인한다</li>
 *   <li>리다이렉트를 따라가지 않는다 (허용 목록 밖으로 끌려가지 않게)</li>
 *   <li>초당 요청 수를 제한한다</li>
 * </ul>
 */
public final class JdkApiClient implements ApiClient {

    static final String USER_AGENT = "gate-authz/0.1 (+https://github.com/yeomyaloo/GATE)";

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final TargetGuard guard;
    private final long minIntervalNanos;
    private long lastRequestNanos;

    public JdkApiClient(TargetGuard guard, double maxRequestsPerSecond) {
        if (maxRequestsPerSecond <= 0) {
            throw new IllegalArgumentException("maxRequestsPerSecond must be positive");
        }
        this.guard = guard;
        this.minIntervalNanos = (long) (1_000_000_000L / maxRequestsPerSecond);
    }

    @Override
    public Response send(String method, URI uri, String bearerToken, String jsonBody) throws IOException {
        guard.check(uri);
        throttle();
        HttpRequest.Builder b = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT);
        if (bearerToken != null) {
            b.header("Authorization", "Bearer " + bearerToken);
        }
        if (jsonBody != null) {
            b.header("Content-Type", "application/json");
            b.method(method, HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        try {
            HttpResponse<String> r = client.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Response(r.statusCode(), r.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }

    private synchronized void throttle() {
        long now = System.nanoTime();
        long wait = lastRequestNanos + minIntervalNanos - now;
        if (wait > 0) {
            try {
                Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        lastRequestNanos = System.nanoTime();
    }
}
