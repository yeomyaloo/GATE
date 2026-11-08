package io.gate.pkg.http;

/** HTTP 응답 중 검사에 필요한 부분만 담는다. */
public record HttpResult(int status, String body) {

    public boolean ok() {
        return status >= 200 && status < 300;
    }

    public boolean notFound() {
        return status == 404;
    }

    /** 서버 장애나 요청 제한처럼 "지금은 판단할 수 없는" 응답. 캐시하지 않는다. */
    public boolean transientFailure() {
        return status == 429 || status >= 500;
    }
}
