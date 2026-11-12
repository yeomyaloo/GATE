package io.gate.review.ai;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Messages API(POST /v1/messages)를 흉내 내는 로컬 서버. 실제 SDK 클라이언트를 이 주소로 돌려
 * API 키 없이 요청 본문과 응답 처리를 검증한다. 응답은 등록한 순서대로 하나씩 돌려준다.
 */
final class FakeMessagesApi implements AutoCloseable {

    record Reply(int status, String body) {
    }

    final List<String> requestBodies = new ArrayList<>();
    final List<String> apiKeys = new ArrayList<>();
    final java.util.Set<String> headerNames = new java.util.TreeSet<>();
    private final Deque<Reply> replies = new ArrayDeque<>();
    private final HttpServer server;

    FakeMessagesApi() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            synchronized (this) {
                requestBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                String key = exchange.getRequestHeaders().getFirst("x-api-key");
                String auth = exchange.getRequestHeaders().getFirst("Authorization");
                apiKeys.add(key != null ? key : auth == null ? null : auth.replaceFirst("^Bearer ", ""));
                headerNames.addAll(exchange.getRequestHeaders().keySet());
            }
            Reply r = replies.isEmpty() ? new Reply(500, "{\"type\":\"error\",\"error\":{\"type\":\"api_error\","
                    + "\"message\":\"no reply\"}}") : replies.poll();
            byte[] body = r.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(r.status(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    FakeMessagesApi reply(int status, String body) {
        replies.add(new Reply(status, body));
        return this;
    }

    /** 정상 응답: text 블록 하나(구조화 출력 JSON). */
    FakeMessagesApi message(String stopReason, String text) {
        String escaped = text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
        return reply(200, "{\"id\":\"msg_test\",\"type\":\"message\",\"role\":\"assistant\","
                + "\"model\":\"claude-opus-5-5\",\"content\":[{\"type\":\"text\",\"text\":\"" + escaped + "\"}],"
                + "\"stop_reason\":\"" + stopReason + "\",\"stop_sequence\":null,"
                + "\"usage\":{\"input_tokens\":1200,\"output_tokens\":300,\"cache_creation_input_tokens\":0,"
                + "\"cache_read_input_tokens\":0}}");
    }

    FakeMessagesApi refusal(String category) {
        return reply(200, "{\"id\":\"msg_test\",\"type\":\"message\",\"role\":\"assistant\","
                + "\"model\":\"claude-opus-5-5\",\"content\":[],\"stop_reason\":\"refusal\",\"stop_sequence\":null,"
                + "\"stop_details\":{\"type\":\"refusal\",\"category\":\"" + category + "\",\"explanation\":\"x\"},"
                + "\"usage\":{\"input_tokens\":10,\"output_tokens\":0}}");
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
