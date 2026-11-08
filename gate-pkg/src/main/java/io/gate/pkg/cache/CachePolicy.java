package io.gate.pkg.cache;

import io.gate.pkg.http.HttpResult;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;

/**
 * 응답별 캐시 보관 시간. 설계안 6장 표를 그대로 옮긴다.
 *
 * <p>"존재하지 않음"(404)은 10분만 보관한다. 공격자가 AI가 지어낸 이름을 뒤늦게 선점할 수 있기 때문이다.
 * 서버 오류·요청 제한(429, 5xx)은 캐시하지 않는다.
 */
public final class CachePolicy {

    public static final Duration METADATA_TTL = Duration.ofHours(1);
    public static final Duration DOWNLOADS_TTL = Duration.ofHours(24);
    public static final Duration OSV_TTL = Duration.ofHours(6);
    public static final Duration NOT_FOUND_TTL = Duration.ofMinutes(10);

    private CachePolicy() {
    }

    /** 캐시하지 않아야 하면 빈 값을 돌려준다. */
    public static Optional<Duration> ttlFor(URI uri, HttpResult result) {
        if (result.notFound()) {
            return Optional.of(NOT_FOUND_TTL);
        }
        if (!result.ok()) {
            return Optional.empty();
        }
        String host = uri.getHost() == null ? "" : uri.getHost();
        switch (host) {
            case "api.npmjs.org":
            case "pypistats.org":
                return Optional.of(DOWNLOADS_TTL);
            case "api.osv.dev":
                return Optional.of(OSV_TTL);
            default:
                return Optional.of(METADATA_TTL);
        }
    }
}
