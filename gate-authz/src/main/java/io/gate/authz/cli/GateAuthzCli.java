package io.gate.authz.cli;

import io.gate.authz.config.AuthzConfig;
import io.gate.authz.config.ConfigLoader;
import io.gate.authz.http.ApiClient;
import io.gate.authz.http.JdkApiClient;
import io.gate.authz.http.TargetGuard;
import io.gate.authz.probe.AuthzReport;
import io.gate.authz.probe.AuthzScanner;
import io.gate.authz.probe.LoginClient;
import io.gate.authz.spec.ApiSpec;
import io.gate.common.finding.Decision;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.function.Function;

/**
 * gate-authz 명령줄 진입점.
 * <pre>
 * gate-authz scan --config gate-authz.yml [--format text|json]
 * gate-authz version
 * </pre>
 * 종료 코드: 0 발견 없음 · 2 발견 · 3 판단 불가 또는 설정·로그인·대상 오류.
 * {@code GATE_SERVER_URL}이 있으면 결과를 gate-server로 보낸다(제한 시간 2초, 실패해도 종료 코드에 영향 없음).
 */
public final class GateAuthzCli {

    public static final String VERSION = "0.1.0";
    static final int PASS = 0;
    static final int FOUND = 2;
    static final int UNCHECKABLE = 3;

    private final Function<String, String> env;

    GateAuthzCli(Function<String, String> env) {
        this.env = env;
    }

    public static void main(String[] args) {
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(System.err, true, StandardCharsets.UTF_8);
        System.exit(new GateAuthzCli(System::getenv).run(args, out, err));
    }

    int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 0 || args[0].equals("help") || args[0].equals("--help")) {
            out.print(usage());
            return args.length == 0 ? UNCHECKABLE : PASS;
        }
        if (args[0].equals("version") || args[0].equals("--version")) {
            out.println("gate-authz " + VERSION);
            return PASS;
        }
        if (!args[0].equals("scan")) {
            err.println("unknown command: " + args[0]);
            err.print(usage());
            return UNCHECKABLE;
        }
        String configPath = null;
        String format = "text";
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--config") && i + 1 < args.length) {
                configPath = args[++i];
            } else if (args[i].equals("--format") && i + 1 < args.length) {
                format = args[++i].toLowerCase(Locale.ROOT);
            } else {
                err.println("unknown option: " + args[i]);
                return UNCHECKABLE;
            }
        }
        if (configPath == null || (!format.equals("text") && !format.equals("json"))) {
            err.print(usage());
            return UNCHECKABLE;
        }
        try {
            return scan(Path.of(configPath), format, out, err);
        } catch (TargetGuard.TargetNotAllowedException | LoginClient.AuthzException | IllegalArgumentException e) {
            err.println("gate-authz: " + e.getMessage());
            return UNCHECKABLE;
        } catch (IOException e) {
            err.println("gate-authz: request failed: " + e.getMessage());
            return UNCHECKABLE;
        }
    }

    private int scan(Path configFile, String format, PrintStream out, PrintStream err) throws IOException {
        ConfigLoader.Loaded loaded = ConfigLoader.load(configFile);
        AuthzConfig config = loaded.config();
        TargetGuard guard = new TargetGuard(config.allowedHosts());
        guard.check(URI.create(config.baseUrl())); // 로그인 전에 대상부터 확인
        ApiClient client = new JdkApiClient(guard, config.maxRequestsPerSecond());

        String specText;
        if (loaded.specLocation().matches("(?i)^https?://.*")) {
            ApiClient.Response r = client.send("GET", URI.create(loaded.specLocation()), null, null);
            if (!r.ok()) {
                throw new IllegalArgumentException("could not fetch spec " + loaded.specLocation() + ": HTTP "
                        + r.status());
            }
            specText = r.body();
        } else {
            specText = Files.readString(Path.of(loaded.specLocation()), StandardCharsets.UTF_8);
        }

        Instant started = Instant.now();
        AuthzReport report = new AuthzScanner(ApiSpec.parse(specText), config, client, env, Clock.systemUTC()).scan();
        Instant finished = Instant.now();

        out.print(format.equals("json") ? AuthzReports.json(report, config) + System.lineSeparator()
                : AuthzReports.text(report, config, loaded.specLocation()));
        report(report, config, started, finished, err);

        if (report.decision() == Decision.BLOCK) {
            return FOUND;
        }
        return report.decision() == Decision.WARN ? UNCHECKABLE : PASS;
    }

    /** gate-server로 결과 전송 (선택). 서버 주소는 검사 대상 허용 목록과 무관하게 사용자가 직접 정한 값이다. */
    private void report(AuthzReport report, AuthzConfig config, Instant started, Instant finished, PrintStream err) {
        String server = env.apply("GATE_SERVER_URL");
        if (server == null || server.isBlank() || !server.toLowerCase(Locale.ROOT).matches("^https?://.*")) {
            return;
        }
        String trigger = "true".equals(env.apply("GITHUB_ACTIONS")) ? "GITHUB_ACTION" : "CLI";
        try {
            var http = java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            var req = java.net.http.HttpRequest.newBuilder(URI.create(server.replaceAll("/+$", "") + "/api/scan-runs"))
                    .timeout(Duration.ofSeconds(2)).header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                            AuthzReports.serverBody(report, config, trigger, started, finished)))
                    .build();
            int status = http.send(req, java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode();
            err.println(status / 100 == 2 ? "reported to " + server : "report rejected: HTTP " + status);
        } catch (IOException | RuntimeException e) {
            err.println("report failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static String usage() {
        return String.join(System.lineSeparator(),
                "gate-authz " + VERSION + " - automated authorization tests from OpenAPI (GATE)",
                "",
                "Usage:",
                "  gate-authz scan --config gate-authz.yml [--format text|json]",
                "  gate-authz version",
                "",
                "Exit codes: 0 no findings, 2 findings, 3 inconclusive or configuration/login/target error",
                "Targets: loopback only by default; list other hosts you own in allowedHosts.",
                "Writes/deletes: only with allowMutation: true, and only on objects created for the test.",
                "") + System.lineSeparator();
    }
}
