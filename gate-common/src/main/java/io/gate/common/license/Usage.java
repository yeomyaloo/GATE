package io.gate.common.license;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Function;

/**
 * 사용 정보 보내기 (opt-in, docs/design/licensing.md 4장). <b>기본값은 꺼짐</b>: 설정 파일에서 사용자가 켜야만 보낸다.
 *
 * <ul>
 *   <li>보내는 것: 설치 ID(켤 때 만든 무작위 UUID), 버전, 모듈 이름, 날짜(UTC), 라이선스 상태·키 ID</li>
 *   <li>보내지 않는 것: 코드, 파일 경로, 패키지 이름, 검사 결과, 사용자명, 호스트명, 저장소 주소</li>
 *   <li>꺼져 있으면 네트워크 요청을 하지 않는다. 환경 변수 {@code GATE_USAGE=off}는 설정과 상관없이 끈다(CI 등)</li>
 *   <li>보내기 실패는 무시한다 (제한 시간 1초, 결과·종료 코드에 영향 없음)</li>
 * </ul>
 * 설정 파일: {@code ~/.gate/usage.properties} ({@code enabled}, {@code url}, {@code installId}).
 * 받는 주소는 사용자가 정한다. 코드·문서에 실제 서버 주소를 넣지 않는다.
 */
public final class Usage {

    public static final String OFF_ENV = "GATE_USAGE";

    /** 설정. enabled가 false면 url·installId가 있어도 보내지 않는다. */
    public record Settings(boolean enabled, String url, String installId) {
        public static final Settings OFF = new Settings(false, null, null);
    }

    /** HTTP 전송 (테스트에서 바꿔 끼운다). */
    @FunctionalInterface
    public interface Sender {
        void post(URI url, String json) throws IOException, InterruptedException;
    }

    private Usage() {
    }

    static Path file(Path home) {
        return home.resolve(".gate").resolve("usage.properties");
    }

    public static Settings load(Path home) {
        Path f = file(home);
        if (!Files.isRegularFile(f)) {
            return Settings.OFF;
        }
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(f)) {
            p.load(in);
        } catch (IOException | IllegalArgumentException e) {
            return Settings.OFF;
        }
        return new Settings("true".equals(p.getProperty("enabled")), p.getProperty("url"),
                p.getProperty("installId"));
    }

    /** 켠다. 설치 ID가 없으면 새로 만든다. url은 http(s)만. */
    public static Settings enable(Path home, String url) throws IOException {
        if (url == null || !url.matches("(?i)https?://\\S+")) {
            throw new IllegalArgumentException("url must start with http:// or https://");
        }
        Settings old = load(home);
        Settings s = new Settings(true, url, old.installId() != null ? old.installId() : UUID.randomUUID().toString());
        save(home, s);
        return s;
    }

    /** 끈다. 설치 ID는 지운다 (다시 켜면 새 ID). */
    public static void disable(Path home) throws IOException {
        save(home, Settings.OFF);
    }

    private static void save(Path home, Settings s) throws IOException {
        Properties p = new Properties();
        p.setProperty("enabled", String.valueOf(s.enabled()));
        if (s.url() != null) {
            p.setProperty("url", s.url());
        }
        if (s.installId() != null) {
            p.setProperty("installId", s.installId());
        }
        Files.createDirectories(file(home).getParent());
        try (OutputStream out = Files.newOutputStream(file(home))) {
            p.store(out, "GATE usage reporting (opt-in). See PRIVACY.md. Turn off: gate-license usage off");
        }
    }

    /** 보낼 본문. 이 메서드가 만드는 것 외에는 보내지 않는다 (미리 보기에도 같은 것을 보여 준다). */
    public static String payload(String installId, String module, String version, License.Info license,
            LocalDate date) {
        return "{\"installId\":\"" + safe(installId) + "\",\"module\":\"" + safe(module) + "\",\"version\":\""
                + safe(version) + "\",\"date\":\"" + date + "\",\"licenseStatus\":\"" + license.status()
                + "\",\"licenseKeyId\":" + (license.keyId() == null ? "null" : "\"" + safe(license.keyId()) + "\"")
                + "}";
    }

    /** 켜져 있을 때만 한 번 보낸다. 꺼져 있으면 아무것도 하지 않는다. */
    public static boolean report(String module, String version, Function<String, String> env, Path home,
            License.Info license, Sender sender) {
        if ("off".equalsIgnoreCase(env.apply(OFF_ENV))) {
            return false;
        }
        Settings s = load(home);
        if (!s.enabled() || s.url() == null || s.installId() == null) {
            return false;
        }
        try {
            sender.post(URI.create(s.url()), payload(s.installId(), module, version, license,
                    LocalDate.now(ZoneOffset.UTC)));
            return true;
        } catch (IOException | IllegalArgumentException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** CLI용: 실제 환경에서 보낸다. */
    public static void report(String module, String version, License.Info license) {
        report(module, version, System::getenv, Path.of(System.getProperty("user.home")), license, Usage::httpPost);
    }

    static void httpPost(URI url, String json) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(1))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json)).build();
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).followRedirects(HttpClient.Redirect.NEVER)
                .build().send(req, HttpResponse.BodyHandlers.discarding());
    }

    private static String safe(String s) {
        return s == null ? "" : s.replaceAll("[^A-Za-z0-9._:-]", "");
    }

    /** CLI 공통: stderr 안내(상업 키가 유효하면 조용히) + opt-in 사용 정보. 훅처럼 매번 실행되는 곳에서는 부르지 않는다. */
    public static void noticeAndReport(String module, String version, java.io.PrintStream err) {
        License.Info info = License.current();
        if (info.status() != License.Status.VALID) {
            err.println(info.notice());
        }
        report(module, version, info);
    }
}
