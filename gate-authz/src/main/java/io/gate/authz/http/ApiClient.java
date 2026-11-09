package io.gate.authz.http;

import java.io.IOException;
import java.net.URI;

/** 검사 대상 API 호출 경계. 테스트에서는 가짜 구현으로 바꾼다. */
public interface ApiClient {

    /** 응답. 네트워크 오류는 {@link IOException}. */
    record Response(int status, String body) {

        public boolean ok() {
            return status >= 200 && status < 300;
        }

        /** 접근이 막혔다는 응답 (401·403·404). */
        public boolean denied() {
            return status == 401 || status == 403 || status == 404;
        }
    }

    /**
     * @param bearerToken 없으면 null (인증 없이 호출)
     * @param jsonBody    없으면 null
     */
    Response send(String method, URI uri, String bearerToken, String jsonBody) throws IOException;
}
