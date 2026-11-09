package io.gate.authz.http;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 검사 대상 허용 목록. gate-authz가 남의 시스템을 공격하는 도구가 되지 않게 한다 (설계안 3장).
 * <ul>
 *   <li>루프백(127.0.0.1, ::1, localhost)은 기본 허용</li>
 *   <li>그 밖의 호스트는 설정의 {@code allowedHosts}에 이름이 그대로 있어야 한다(와일드카드 없음)</li>
 *   <li>http·https만</li>
 * </ul>
 */
public final class TargetGuard {

    private final Set<String> allowedHosts;

    public TargetGuard(Set<String> allowedHosts) {
        this.allowedHosts = allowedHosts == null ? Set.of()
                : allowedHosts.stream().map(h -> h.strip().toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
    }

    public void check(URI uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new TargetNotAllowedException("only http and https targets are allowed: " + uri);
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new TargetNotAllowedException("target has no host: " + uri);
        }
        String h = host.toLowerCase(Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]")) {
            h = h.substring(1, h.length() - 1);
        }
        if (isLoopback(h) || allowedHosts.contains(h)) {
            return;
        }
        throw new TargetNotAllowedException("target host '" + host + "' is not allowed. Only loopback addresses are "
                + "allowed by default; add the exact host name to allowedHosts if you own that system.");
    }

    static boolean isLoopback(String host) {
        if (host.equals("localhost")) {
            return true;
        }
        // 이름 해석으로 우회되지 않도록 IP 리터럴만 판단한다
        if (!host.matches("^[0-9.]+$") && !host.contains(":")) {
            return false;
        }
        try {
            return InetAddress.getByName(host).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    /** 허용되지 않은 대상. */
    public static final class TargetNotAllowedException extends RuntimeException {
        public TargetNotAllowedException(String message) {
            super(message);
        }
    }
}
