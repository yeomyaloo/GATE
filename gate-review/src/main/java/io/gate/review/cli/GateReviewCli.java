package io.gate.review.cli;

import com.anthropic.models.messages.OutputConfig;
import io.gate.common.finding.Decision;
import io.gate.pkg.manifest.GitReader;
import io.gate.review.SourceFile;
import io.gate.review.ai.AiResult;
import io.gate.review.ai.AiReviewer;
import io.gate.review.ai.ClaudeReviewer;
import io.gate.review.diff.ChangedSources;
import io.gate.review.merge.ReviewMerger;
import io.gate.review.merge.ReviewReport;
import io.gate.review.staticscan.SemgrepResult;
import io.gate.review.staticscan.SemgrepRunner;
import io.gate.review.staticscan.ScanWorkspace;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

/**
 * gate-review 명령줄 진입점.
 * <pre>
 * gate-review review --base &lt;ref&gt; [--head &lt;ref&gt;] [--repo DIR] [--rules PATH] [--format text|json]
 *                    [--no-ai] [--effort low|medium|high|xhigh|max]
 * </pre>
 * 종료 코드: 0 문제 없음 · 1 경고 · 2 차단 · 3 실행 오류. AI만의 지적은 경고까지만 낸다.
 * {@code ANTHROPIC_API_KEY}가 없으면 정적 분석만 하고 그 사실을 출력에 표시한다.
 */
public final class GateReviewCli {

    public static final String VERSION = "0.1.0";
    static final int PASS = 0;
    static final int WARN = 1;
    static final int BLOCK = 2;
    static final int ERROR = 3;
    static final String DEFAULT_RULES = "gate-review/semgrep/rules/gate-java.yml";

    private final Function<String, String> env;
    private final Function<Optional<OutputConfig.Effort>, Optional<AiReviewer>> aiFactory;

    GateReviewCli(Function<String, String> env, Function<Optional<OutputConfig.Effort>, Optional<AiReviewer>> ai) {
        this.env = env;
        this.aiFactory = ai;
    }

    public static void main(String[] args) {
        Function<String, String> env = System::getenv;
        GateReviewCli cli = new GateReviewCli(env, effort -> ClaudeReviewer.fromEnv(env, ClaudeReviewer.DEFAULT_MODEL,
                effort.orElse(OutputConfig.Effort.HIGH)).map(r -> (AiReviewer) r));
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(System.err, true, StandardCharsets.UTF_8);
        System.exit(cli.run(args, out, err));
    }

    int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length > 0 && args[0].equals("measure")) {
            return new MeasureCommand(env, aiFactory, this::semgrep).run(args, out, err);
        }
        if (args.length == 0 || !args[0].equals("review")) {
            if (args.length > 0 && args[0].equals("version")) {
                out.println("gate-review " + VERSION);
                return PASS;
            }
            err.print(usage());
            return ERROR;
        }
        String base = null;
        String head = "HEAD";
        String format = "text";
        String rules = DEFAULT_RULES;
        Path repo = Path.of("").toAbsolutePath();
        boolean noAi = false;
        Optional<OutputConfig.Effort> effort = Optional.empty();
        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            if (i + 1 < args.length && a.equals("--base")) {
                base = args[++i];
            } else if (i + 1 < args.length && a.equals("--head")) {
                head = args[++i];
            } else if (i + 1 < args.length && a.equals("--repo")) {
                repo = Path.of(args[++i]);
            } else if (i + 1 < args.length && a.equals("--rules")) {
                rules = args[++i];
            } else if (i + 1 < args.length && a.equals("--format")) {
                format = args[++i];
            } else if (i + 1 < args.length && a.equals("--effort")) {
                effort = Optional.of(OutputConfig.Effort.of(args[++i].toLowerCase(Locale.ROOT)));
            } else if (a.equals("--no-ai")) {
                noAi = true;
            } else {
                err.println("unknown option: " + a);
                return ERROR;
            }
        }
        if (base == null || (!format.equals("text") && !format.equals("json"))) {
            err.print(usage());
            return ERROR;
        }
        try {
            java.time.Instant started = java.time.Instant.now();
            ReviewReport report = review(repo, base, head, rules, noAi, effort);
            out.print(format.equals("json") ? ReviewOutput.json(report) + System.lineSeparator()
                    : ReviewOutput.text(report));
            report(report, base, head, started, err);
            Decision d = report.decision();
            return d == Decision.BLOCK ? BLOCK : d == Decision.WARN ? WARN : PASS;
        } catch (IOException | IllegalArgumentException e) {
            err.println("gate-review: " + e.getMessage());
            return ERROR;
        }
    }

    /**
     * {@code GATE_SERVER_URL}이 있으면 결과를 gate-server에 저장한다 (제한 시간 2초).
     * 전송 실패는 판정·종료 코드에 영향을 주지 않는다. 안내는 표준 오류로 (JSON 출력을 깨지 않게).
     */
    private void report(ReviewReport report, String base, String head, java.time.Instant started, PrintStream err) {
        String server = env.apply("GATE_SERVER_URL");
        if (server == null || server.isBlank() || !server.toLowerCase(Locale.ROOT).matches("^https?://.*")) {
            return;
        }
        String trigger = "true".equals(env.apply("GITHUB_ACTIONS")) ? "GITHUB_ACTION" : "CLI";
        try {
            var req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(server.replaceAll("/+$", "")
                    + "/api/scan-runs")).timeout(java.time.Duration.ofSeconds(2))
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(ReviewOutput.serverBody(report, trigger,
                            "review " + base + ".." + head, started, java.time.Instant.now())))
                    .build();
            int status = java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(2)).build()
                    .send(req, java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode();
            err.println(status / 100 == 2 ? "reported to " + server : "report rejected: HTTP " + status);
        } catch (IOException | IllegalArgumentException e) {
            err.println("could not report to gate-server: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    ReviewReport review(Path repo, String base, String head, String rules, boolean noAi,
            Optional<OutputConfig.Effort> effort) throws IOException {
        List<SourceFile> files = ChangedSources.between(new GitReader(repo), base, head);

        // head 시점 내용을 임시 폴더에 같은 경로로 써서 semgrep을 돌린다 (작업 트리와 head가 달라도 정확하게)
        Path rulesPath = repo.resolve(rules).toAbsolutePath();
        SemgrepResult st = ScanWorkspace.scan(semgrep(), rulesPath.toString(), files);

        AiResult ai;
        if (noAi) {
            ai = AiResult.skipped("disabled with --no-ai");
        } else if (files.isEmpty()) {
            ai = AiResult.skipped("no changed Java sources");
        } else {
            ai = aiFactory.apply(effort).map(r -> r.review(files, st.hits()))
                    .orElse(AiResult.skipped("ANTHROPIC_API_KEY is not set; static analysis only"));
        }
        return new ReviewMerger(Clock.systemUTC()).merge(files, st.hits(), st.errors(), "semgrep " + st.version(), ai);
    }

    SemgrepRunner semgrep() {
        String exe = env.apply("GATE_SEMGREP");
        return new SemgrepRunner(exe == null || exe.isBlank() ? "semgrep" : exe);
    }

    static String usage() {
        return String.join(System.lineSeparator(),
                "gate-review " + VERSION + " - static analysis (Semgrep) + AI security review of changes (GATE)",
                "",
                "Usage:",
                "  gate-review review --base <ref> [--head <ref>] [--repo DIR] [--rules PATH] [--format text|json]",
                "                     [--no-ai] [--effort low|medium|high|xhigh|max]",
                "  gate-review measure [--repo DIR] [--rules PATH|p/java] [--out FILE]",
                "                      [--ai --budget-usd X [--runs N] [--effort LEVEL]] [--estimate]",
                "",
                "Exit codes: 0 clean, 1 warnings, 2 blocking findings, 3 error.",
                "With GATE_SERVER_URL set, review results are also stored in gate-server.",
                "AI-only findings never block. Without ANTHROPIC_API_KEY only static analysis runs.",
                "") + System.lineSeparator();
    }
}
