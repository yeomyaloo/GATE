package io.gate.pkg.cli;

import io.gate.common.finding.Decision;
import io.gate.pkg.command.InstallCommandParser;
import io.gate.pkg.command.ParsedCommand;
import io.gate.pkg.scan.AllowList;
import io.gate.pkg.scan.PackageScanner;
import io.gate.pkg.scan.PackageVerdict;
import io.gate.pkg.scan.ScanReport;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import tools.jackson.databind.JsonNode;

/**
 * Claude Code PreToolUse 훅 처리 (설계안 5.2).
 * <ul>
 *   <li>입력: stdin JSON의 {@code tool_name}(Bash·PowerShell), {@code tool_input.command}, {@code cwd}</li>
 *   <li>설치 명령이 아니면 아무것도 출력하지 않는다 → 평소 권한 흐름 그대로</li>
 *   <li>BLOCK → {@code deny}, WARN·검사 불가 → {@code ask}, PASS → 출력 없음.
 *       {@code allow}는 내지 않는다(사용자의 권한 설정을 건너뛰게 되므로)</li>
 *   <li>어떤 오류가 나도 예외를 밖으로 던지지 않고 {@code ask}를 낸다. 훅이 비정상 종료하면 Claude Code는
 *       명령을 그대로 실행하기 때문이다(fail-open)</li>
 * </ul>
 */
public final class HookHandler {

    static final Set<String> SHELL_TOOLS = Set.of("Bash", "PowerShell");

    private final InstallCommandParser parser;
    private final Supplier<PackageScanner> scanner;
    private final Function<Path, AllowList> allowLoader;
    private final Optional<ResultReporter> reporter;

    public HookHandler(InstallCommandParser parser, Supplier<PackageScanner> scanner,
            Function<Path, AllowList> allowLoader) {
        this(parser, scanner, allowLoader, Optional.empty());
    }

    public HookHandler(InstallCommandParser parser, Supplier<PackageScanner> scanner,
            Function<Path, AllowList> allowLoader, Optional<ResultReporter> reporter) {
        this.parser = parser;
        this.scanner = scanner;
        this.allowLoader = allowLoader;
        this.reporter = reporter;
    }

    /**
     * @param stdin      훅 입력 JSON
     * @param projectDir 오탐 예외 파일을 찾을 폴더. null이면 입력의 cwd를 쓴다
     * @return 출력할 JSON. 개입하지 않으면 빈 값
     */
    public Optional<String> handle(String stdin, Path projectDir) {
        try {
            JsonNode input = Reports.JSON.readTree(stdin);
            String tool = input.path("tool_name").asString("");
            String command = input.path("tool_input").path("command").asString("");
            if (!SHELL_TOOLS.contains(tool) || command.isBlank()) {
                return Optional.empty();
            }
            ParsedCommand parsed = parser.parse(command);
            if (!parsed.isInstall()) {
                return Optional.empty();
            }
            Path dir = projectDir != null ? projectDir
                    : input.path("cwd").isString() ? Path.of(input.path("cwd").asString()) : null;
            AllowList allow = allowLoader.apply(dir);

            java.time.Instant started = java.time.Instant.now();
            // 검사할 패키지가 없으면(예: npm ci) 스캐너를 만들지 않는다 (인기 목록 로딩 비용 절약)
            ScanReport raw = parsed.packages().isEmpty() ? new ScanReport(java.util.UUID.randomUUID(), List.of())
                    : scanner.get().scan(parsed.packages());
            List<PackageVerdict> verdicts = raw.packages().stream().map(allow::apply).toList();
            ScanReport report = new ScanReport(raw.runId(), verdicts);
            if (!report.packages().isEmpty()) {
                reporter.ifPresent(r -> r.report(report, io.gate.common.finding.Trigger.CLAUDE_HOOK, command, started,
                        java.time.Instant.now()));
            }

            Decision d = report.decision();
            if (d == Decision.BLOCK) {
                return Optional.of(output("deny", Reports.hookReason(report, parsed.unchecked(), allow)));
            }
            if (d == Decision.WARN || !parsed.unchecked().isEmpty()) {
                return Optional.of(output("ask", Reports.hookReason(report, parsed.unchecked(), allow)));
            }
            return Optional.empty();
        } catch (RuntimeException | StackOverflowError e) {
            return Optional.of(errorOutput(e));
        }
    }

    static String output(String decision, String reason) {
        return Reports.JSON.writeValueAsString(Reports.hookOutput(decision, reason));
    }

    static String errorOutput(Throwable e) {
        return output("ask", "GATE gate-pkg could not check this command (" + e.getClass().getSimpleName() + ": "
                + e.getMessage() + "). Review it manually before allowing.");
    }
}
