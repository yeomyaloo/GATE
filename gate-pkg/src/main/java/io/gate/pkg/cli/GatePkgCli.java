package io.gate.pkg.cli;

import io.gate.common.finding.Decision;
import io.gate.common.finding.Finding;
import io.gate.pkg.cache.CachingFetcher;
import io.gate.pkg.cache.FileCache;
import io.gate.pkg.check.Rules;
import io.gate.pkg.check.Thresholds;
import io.gate.pkg.check.TypoDetector;
import io.gate.pkg.command.InstallCommandParser;
import io.gate.pkg.http.HttpFetcher;
import io.gate.pkg.http.JdkHttpFetcher;
import io.gate.pkg.registry.Ecosystem;
import io.gate.pkg.registry.NpmRegistryClient;
import io.gate.pkg.registry.OsvClient;
import io.gate.pkg.registry.PackageRequest;
import io.gate.pkg.registry.PyPiRegistryClient;
import io.gate.pkg.scan.AllowList;
import io.gate.pkg.scan.PackageScanner;
import io.gate.pkg.scan.PackageVerdict;
import io.gate.pkg.scan.ScanReport;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import io.gate.pkg.manifest.DependencyDiff;
import io.gate.pkg.manifest.GitReader;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import io.gate.common.finding.Trigger;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * gate-pkg 명령줄 진입점.
 * <pre>
 * gate-pkg check &lt;npm|pypi&gt; &lt;spec&gt;... [--format text|json] [--project-dir DIR]
 * gate-pkg hook claude            (stdin: 훅 JSON, stdout: 훅 응답 JSON)
 * gate-pkg version
 * </pre>
 * 종료 코드(check): 0 PASS · 1 WARN · 2 BLOCK · 3 검사 불가(네트워크·입력 오류). hook은 항상 0.
 */
public final class GatePkgCli {

    public static final String VERSION = "0.1.0";

    static final int PASS = 0;
    static final int WARN = 1;
    static final int BLOCK = 2;
    static final int UNCHECKABLE = 3;

    private final Supplier<PackageScanner> scanner;
    private final Function<Path, AllowList> allowLoader;
    private final Supplier<Optional<ResultReporter>> reporter;

    GatePkgCli(Supplier<PackageScanner> scanner, Function<Path, AllowList> allowLoader) {
        this(scanner, allowLoader, Optional::empty);
    }

    GatePkgCli(Supplier<PackageScanner> scanner, Function<Path, AllowList> allowLoader,
            Supplier<Optional<ResultReporter>> reporter) {
        this.scanner = scanner;
        this.allowLoader = allowLoader;
        this.reporter = reporter;
    }

    public static void main(String[] args) {
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(System.err, true, StandardCharsets.UTF_8);
        int code = new GatePkgCli(GatePkgCli::defaultScanner, AllowList::load, ResultReporter::fromEnv)
                .run(args, System.in, out, err);
        System.exit(code);
    }

    /** 실제 레지스트리 + 파일 캐시 + 전체 검사 구성. */
    static PackageScanner defaultScanner() {
        HttpFetcher http = new CachingFetcher(new JdkHttpFetcher(), new FileCache(FileCache.defaultDir(),
                Clock.systemUTC()));
        return new PackageScanner(
                List.of(new NpmRegistryClient(http), new PyPiRegistryClient(http)),
                new OsvClient(http),
                List.of(TypoDetector.load(Ecosystem.NPM), TypoDetector.load(Ecosystem.PYPI)),
                PackageScanner.allChecks(), Clock.systemUTC(), Thresholds.defaults());
    }

    int run(String[] args, InputStream in, PrintStream out, PrintStream err) {
        if (args.length == 0 || args[0].equals("--help") || args[0].equals("-h") || args[0].equals("help")) {
            out.print(usage());
            return args.length == 0 ? UNCHECKABLE : PASS;
        }
        switch (args[0]) {
            case "version":
            case "--version":
                out.println("gate-pkg " + VERSION);
                return PASS;
            case "hook":
                return hook(args, in, out, err);
            case "check":
                return check(args, out, err);
            case "diff":
                return diff(args, out, err);
            default:
                err.println("unknown command: " + args[0]);
                err.print(usage());
                return UNCHECKABLE;
        }
    }

    private int hook(String[] args, InputStream in, PrintStream out, PrintStream err) {
        if (args.length < 2 || !args[1].equals("claude")) {
            err.println("usage: gate-pkg hook claude");
            return UNCHECKABLE;
        }
        String stdin;
        try {
            stdin = readAll(in);
        } catch (IOException e) {
            out.println(HookHandler.errorOutput(e));
            return PASS;
        }
        String envDir = System.getenv("CLAUDE_PROJECT_DIR");
        Path projectDir = envDir == null || envDir.isBlank() ? null : Path.of(envDir);
        new HookHandler(new InstallCommandParser(), scanner, allowLoader, reporter.get()).handle(stdin, projectDir)
                .ifPresent(out::println);
        return PASS; // 훅은 항상 0으로 끝내고 판단은 JSON으로 전달한다
    }

    private int check(String[] args, PrintStream out, PrintStream err) {
        if (args.length < 3) {
            err.println("usage: gate-pkg check <npm|pypi> <spec>... [--format text|json] [--project-dir DIR]");
            return UNCHECKABLE;
        }
        Ecosystem eco;
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "npm":
                eco = Ecosystem.NPM;
                break;
            case "pypi":
            case "pip":
                eco = Ecosystem.PYPI;
                break;
            default:
                err.println("unsupported ecosystem: " + args[1] + " (use npm or pypi)");
                return UNCHECKABLE;
        }
        String format = "text";
        Path projectDir = Path.of("").toAbsolutePath();
        Set<PackageRequest> requests = new LinkedHashSet<>();
        List<String> unchecked = new ArrayList<>();
        for (int i = 2; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--format") && i + 1 < args.length) {
                format = args[++i];
            } else if (a.startsWith("--format=")) {
                format = a.substring("--format=".length());
            } else if (a.equals("--project-dir") && i + 1 < args.length) {
                projectDir = Path.of(args[++i]);
            } else if (eco == Ecosystem.NPM) {
                InstallCommandParser.npmSpec(a, requests, unchecked);
            } else {
                InstallCommandParser.pypiSpec(a, requests, unchecked);
            }
        }
        if (!format.equals("text") && !format.equals("json")) {
            err.println("unknown format: " + format);
            return UNCHECKABLE;
        }

        AllowList allow = allowLoader.apply(projectDir);
        allow.problems().forEach(err::println);
        Instant started = Instant.now();
        ScanReport raw = scanner.get().scan(List.copyOf(requests));
        ScanReport report = new ScanReport(raw.runId(), raw.packages().stream().map(allow::apply).toList());
        String target = "check " + String.join(" ", java.util.Arrays.asList(args).subList(1, args.length));
        reporter.get().ifPresent(r -> err.println(r.report(report, triggerFromEnv(), target, started, Instant.now())));
        out.print(format.equals("json") ? Reports.json(report, unchecked) + System.lineSeparator()
                : Reports.text(report, unchecked));
        return exitCode(report, unchecked);
    }

    /**
     * PR 검사: base와 head 사이에 매니페스트에서 새로 추가·변경된 직접 의존성만 검사한다.
     * GitHub Actions 안이면 주석과 Job Summary도 남긴다.
     */
    private int diff(String[] args, PrintStream out, PrintStream err) {
        String base = null;
        String head = "HEAD";
        String format = "text";
        Path repo = Path.of("").toAbsolutePath();
        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--base") && i + 1 < args.length) {
                base = args[++i];
            } else if (a.equals("--head") && i + 1 < args.length) {
                head = args[++i];
            } else if (a.equals("--repo") && i + 1 < args.length) {
                repo = Path.of(args[++i]);
            } else if (a.equals("--format") && i + 1 < args.length) {
                format = args[++i];
            } else {
                err.println("unknown option: " + a);
                return UNCHECKABLE;
            }
        }
        if (base == null || (!format.equals("text") && !format.equals("json"))) {
            err.println("usage: gate-pkg diff --base <ref> [--head <ref>] [--repo DIR] [--format text|json]");
            return UNCHECKABLE;
        }

        DependencyDiff.Result changes;
        try {
            changes = DependencyDiff.between(new GitReader(repo), base, head);
        } catch (IOException | IllegalArgumentException e) {
            err.println("diff failed: " + e.getMessage());
            return UNCHECKABLE;
        }

        Instant started = Instant.now();
        AllowList allow = allowLoader.apply(repo);
        allow.problems().forEach(err::println);
        List<PackageRequest> requests = changes.changes().stream().map(DependencyDiff.Change::request).distinct()
                .toList();
        ScanReport raw = requests.isEmpty() ? new ScanReport(java.util.UUID.randomUUID(), List.of())
                : scanner.get().scan(requests);
        ScanReport report = new ScanReport(raw.runId(), raw.packages().stream().map(allow::apply).toList());

        Map<PackageVerdict, DependencyDiff.Change> where = new java.util.IdentityHashMap<>();
        for (PackageVerdict v : report.packages()) {
            changes.changes().stream().filter(c -> c.request().equals(v.request())).findFirst()
                    .ifPresent(c -> where.put(v, c));
        }

        if (requests.isEmpty() && changes.unchecked().isEmpty()) {
            out.println("No new or changed dependencies"
                    + (changes.manifests().isEmpty() ? " (no manifest changed)." : " in " + changes.manifests()));
        } else {
            out.print(format.equals("json") ? Reports.json(report, changes.unchecked()) + System.lineSeparator()
                    : Reports.text(report, changes.unchecked()));
        }
        if ("true".equals(System.getenv("GITHUB_ACTIONS"))) {
            out.print(GitHubOutput.annotations(report, where));
            String summaryFile = System.getenv("GITHUB_STEP_SUMMARY");
            if (summaryFile != null && !summaryFile.isBlank()) {
                try {
                    Files.writeString(Path.of(summaryFile),
                            GitHubOutput.summary(report, where, changes.unchecked(), changes.manifests()),
                            StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                } catch (IOException e) {
                    err.println("could not write job summary: " + e.getMessage());
                }
            }
        }
        if (!report.packages().isEmpty()) {
            String target = "diff " + base + ".." + head;
            reporter.get().ifPresent(r -> err.println(r.report(report, triggerFromEnv(), target, started,
                    Instant.now())));
        }
        return exitCode(report, changes.unchecked());
    }

    /** GitHub Actions 안에서 실행되면 GITHUB_ACTION, 아니면 CLI. */
    static Trigger triggerFromEnv() {
        return "true".equals(System.getenv("GITHUB_ACTIONS")) ? Trigger.GITHUB_ACTION : Trigger.CLI;
    }

    static int exitCode(ScanReport report, List<String> unchecked) {
        if (report.decision() == Decision.BLOCK) {
            return BLOCK;
        }
        boolean uncheckable = !unchecked.isEmpty() || report.packages().stream()
                .map(PackageVerdict::findings).flatMap(List::stream)
                .map(Finding::ruleId).anyMatch(Rules.UNCHECKED::equals);
        if (uncheckable) {
            return UNCHECKABLE;
        }
        return report.decision() == Decision.WARN ? WARN : PASS;
    }

    static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        in.transferTo(buf);
        return buf.toString(StandardCharsets.UTF_8);
    }

    static String usage() {
        return String.join(System.lineSeparator(),
                "gate-pkg " + VERSION + " - package install checkpoint (GATE)",
                "",
                "Usage:",
                "  gate-pkg check <npm|pypi> <spec>... [--format text|json] [--project-dir DIR]",
                "  gate-pkg diff --base <ref> [--head <ref>] [--repo DIR] [--format text|json]",
                "                            check dependencies added or changed between two commits",
                "  gate-pkg hook claude      Claude Code PreToolUse hook (reads hook JSON on stdin)",
                "  gate-pkg version",
                "",
                "Exit codes (check): 0 pass, 1 warn, 2 block, 3 could not check",
                "False positives: add 'ecosystem:name@version  # reason' to " + AllowList.RELATIVE_PATH,
                "") + System.lineSeparator();
    }
}
