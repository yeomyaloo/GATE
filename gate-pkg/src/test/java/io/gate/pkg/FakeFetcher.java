package io.gate.pkg;

import io.gate.pkg.http.HttpFetcher;
import io.gate.pkg.http.HttpResult;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 녹화본(fixture)을 돌려주는 가짜 HTTP. 등록하지 않은 URL은 404로 답한다. 테스트는 네트워크를 쓰지 않는다. */
public final class FakeFetcher implements HttpFetcher {

    private final Map<String, HttpResult> responses = new HashMap<>();
    private final Set<String> failing = new HashSet<>();
    private final List<String> calls = new ArrayList<>();
    private final List<String> postBodies = new ArrayList<>();
    private final List<Object[]> postRules = new ArrayList<>();

    public FakeFetcher on(String url, int status, String body) {
        responses.put(url, new HttpResult(status, body));
        return this;
    }

    public FakeFetcher onFixture(String url, String fixturePath) {
        return on(url, 200, fixture(fixturePath));
    }

    /** POST 본문에 {@code bodyContains}가 들어 있으면 이 응답을 준다. 먼저 등록한 규칙이 이긴다. */
    public FakeFetcher onPost(String url, String bodyContains, int status, String body) {
        postRules.add(new Object[] {url, bodyContains, new HttpResult(status, body)});
        return this;
    }

    /** 이미 등록된 규칙보다 먼저 확인할 POST 규칙 (기본 응답을 덮어쓸 때). */
    public FakeFetcher onPostFirst(String url, String bodyContains, int status, String body) {
        postRules.add(0, new Object[] {url, bodyContains, new HttpResult(status, body)});
        return this;
    }

    public synchronized List<String> postBodies() {
        return List.copyOf(postBodies);
    }

    /** 이 URL은 네트워크 오류(시간 초과 등)를 낸다. */
    public FakeFetcher failing(String url) {
        failing.add(url);
        return this;
    }

    public synchronized List<String> calls() {
        return List.copyOf(calls);
    }

    @Override
    public HttpResult get(URI uri) throws IOException {
        return respond(uri.toString());
    }

    @Override
    public HttpResult postJson(URI uri, String json) throws IOException {
        synchronized (this) {
            postBodies.add(json);
        }
        for (Object[] rule : postRules) {
            if (rule[0].equals(uri.toString()) && json.contains((String) rule[1])) {
                synchronized (this) {
                    calls.add(uri.toString());
                }
                if (failing.contains(uri.toString())) {
                    throw new IOException("simulated timeout");
                }
                return (HttpResult) rule[2];
            }
        }
        return respond(uri.toString());
    }

    private HttpResult respond(String url) throws IOException {
        synchronized (this) {
            calls.add(url);
        }
        if (failing.contains(url)) {
            throw new IOException("simulated timeout");
        }
        return responses.getOrDefault(url, new HttpResult(404, "{\"error\":\"Not found\"}"));
    }

    public static String fixture(String path) {
        try (InputStream in = FakeFetcher.class.getResourceAsStream("/fixtures/" + path)) {
            if (in == null) {
                throw new IllegalArgumentException("missing fixture " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
