package io.gate.pkg.http;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** JDK 내장 HttpClient 구현. 요청 하나의 제한 시간은 기본 3초다. */
public final class JdkHttpFetcher implements HttpFetcher {

    static final String USER_AGENT = "gate-pkg/0.1 (+https://github.com/yeomyaloo/GATE)";

    private final HttpClient client;
    private final Duration timeout;

    public JdkHttpFetcher() {
        this(Duration.ofSeconds(3));
    }

    public JdkHttpFetcher(Duration timeout) {
        this.timeout = timeout;
        this.client = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public HttpResult get(URI uri) throws IOException {
        return send(base(uri).GET().build());
    }

    @Override
    public HttpResult postJson(URI uri, String json) throws IOException {
        return send(base(uri)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build());
    }

    private HttpRequest.Builder base(URI uri) {
        return HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT);
    }

    private HttpResult send(HttpRequest request) throws IOException {
        try {
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new HttpResult(response.statusCode(), response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while calling " + request.uri(), e);
        }
    }
}
