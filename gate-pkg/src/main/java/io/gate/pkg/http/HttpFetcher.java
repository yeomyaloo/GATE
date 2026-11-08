package io.gate.pkg.http;

import java.io.IOException;
import java.net.URI;

/**
 * 외부 API 호출 경계. 테스트에서는 녹화본(fixture)을 돌려주는 가짜 구현으로 바꾼다.
 * 네트워크 오류·시간 초과는 {@link IOException}으로 알린다.
 */
public interface HttpFetcher {

    HttpResult get(URI uri) throws IOException;

    HttpResult postJson(URI uri, String json) throws IOException;
}
