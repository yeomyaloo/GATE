package io.gate.pkg.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gate.pkg.FakeFetcher;
import io.gate.pkg.MutableClock;
import io.gate.pkg.check.Thresholds;
import io.gate.pkg.check.TypoDetector;
import io.gate.pkg.command.InstallCommandParser;
import io.gate.pkg.registry.Ecosystem;
import io.gate.pkg.registry.NpmRegistryClient;
import io.gate.pkg.registry.OsvClient;
import io.gate.pkg.registry.PyPiRegistryClient;
import io.gate.pkg.scan.AllowList;
import io.gate.pkg.scan.PackageScanner;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** CLI와 훅 계약 테스트. 레지스트리는 녹화본, 네트워크 없음. */
class CliTest {

    static final String NPM = "https://registry.npmjs.org/";
    static final String NPM_DL = "https://api.npmjs.org/downloads/point/last-week/";
    static final String PYPI = "https://pypi.org/pypi/";
    static final String PYPI_DL = "https://pypistats.org/api/packages/";
    static final String OSV = "https://api.osv.dev/v1/query";

    static FakeFetcher fixtures() {
        return new FakeFetcher()
                .onFixture(NPM + "lodash", "npm/lodash.json")
                .on(NPM_DL + "lodash", 200, "{\"downloads\":204729283}")
                .onFixture(NPM + "lodahs", "npm/lodahs.json")
                .on(NPM_DL + "lodahs", 200, "{\"downloads\":41}")
                .onFixture(NPM + "esbuild", "npm/esbuild.json")
                .on(NPM_DL + "esbuild", 200, "{\"downloads\":90000000}")
                .onFixture(NPM + "hijacked-lib", "npm/hijacked-lib.json")
                .on(NPM_DL + "hijacked-lib", 200, "{\"downloads\":2000000}")
                .onFixture(PYPI + "requests/json", "pypi/requests.json")
                .on(PYPI_DL + "requests/recent", 200, "{\"data\":{\"last_week\":360913926}}")
                .failing(NPM + "flaky-pkg")
                .onPost(OSV, "", 200, "{}");
    }

    static PackageScanner scanner(FakeFetcher http) {
        return new PackageScanner(List.of(new NpmRegistryClient(http), new PyPiRegistryClient(http)),
                new OsvClient(http), List.of(TypoDetector.load(Ecosystem.NPM), TypoDetector.load(Ecosystem.PYPI)),
                PackageScanner.allChecks(), new MutableClock(), Thresholds.defaults());
    }

    final FakeFetcher http = fixtures();
    Function<Path, AllowList> allow = dir -> AllowList.empty();

    record Run(int code, String out, String err) {
    }

    Run run(String stdin, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = new GatePkgCli(() -> scanner(http), dir -> allow.apply(dir)).run(args,
                new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Run(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    @Nested
    class Check {

        @Test
        void passExitsZero() {
            Run r = run("", "check", "npm", "lodash");
            assertEquals(GatePkgCli.PASS, r.code(), r.out());
            assertTrue(r.out().contains("PASS  npm lodash@4.17.21"), r.out());
        }

        @Test
        void warnExitsOne() {
            Run r = run("", "check", "npm", "hijacked-lib");
            assertEquals(GatePkgCli.WARN, r.code(), r.out());
            assertTrue(r.out().contains("PKG-MAINT"));
        }

        @Test
        void blockExitsTwo() {
            Run r = run("", "check", "npm", "lodash", "lodahs");
            assertEquals(GatePkgCli.BLOCK, r.code(), r.out());
            assertTrue(r.out().contains("PKG-TYPO"));
        }

        @Test
        void uncheckableExitsThree() {
            assertEquals(GatePkgCli.UNCHECKABLE, run("", "check", "npm", "flaky-pkg").code());
            assertEquals(GatePkgCli.UNCHECKABLE, run("", "check", "npm", "github:a/b").code());
            assertEquals(GatePkgCli.UNCHECKABLE, run("", "check", "maven", "x").code());
        }

        @Test
        void pypiSpecs() {
            Run r = run("", "check", "pypi", "requests==2.32.3");
            assertEquals(GatePkgCli.PASS, r.code(), r.out());
        }

        @Test
        void jsonFormat() {
            Run r = run("", "check", "npm", "lodahs", "--format", "json");
            JsonNode root = Reports.JSON.readTree(r.out());
            assertEquals("BLOCK", root.path("decision").asString());
            JsonNode finding = root.path("packages").get(0).path("findings").get(0);
            assertEquals(64, finding.path("fingerprint").asString().length());
            assertTrue(root.path("packages").get(0).path("findings").toString().contains("similarTo"));
        }

        @Test
        void allowListIsApplied() {
            allow = dir -> AllowList.parse(List.of("npm:hijacked-lib@3.3.6 # reviewed"));
            Run r = run("", "check", "npm", "hijacked-lib");
            assertEquals(GatePkgCli.PASS, r.code(), r.out());
            assertTrue(r.out().contains("allowed by"), r.out());
        }

        @Test
        void usageAndVersion() {
            assertEquals(GatePkgCli.UNCHECKABLE, run("").code());
            assertTrue(run("", "version").out().contains(GatePkgCli.VERSION));
            assertEquals(GatePkgCli.UNCHECKABLE, run("", "nope").code());
        }
    }

    @Nested
    class Hook {

        String input(String tool, String command) {
            return Reports.JSON.writeValueAsString(java.util.Map.of(
                    "session_id", "s", "cwd", "/tmp/project", "hook_event_name", "PreToolUse",
                    "tool_name", tool, "tool_input", java.util.Map.of("command", command)));
        }

        Optional<JsonNode> hook(String tool, String command) {
            Run r = run(input(tool, command), "hook", "claude");
            assertEquals(0, r.code(), "the hook always exits 0 and decides via JSON");
            return r.out().isBlank() ? Optional.empty() : Optional.of(Reports.JSON.readTree(r.out()));
        }

        String decision(JsonNode out) {
            return out.path("hookSpecificOutput").path("permissionDecision").asString();
        }

        String reason(JsonNode out) {
            return out.path("hookSpecificOutput").path("permissionDecisionReason").asString();
        }

        @Test
        void typosquatIsDeniedWithGuidanceForClaude() {
            JsonNode out = hook("Bash", "cd web && npm install lodahs").orElseThrow();
            assertEquals("PreToolUse", out.path("hookSpecificOutput").path("hookEventName").asString());
            assertEquals("deny", decision(out));
            assertTrue(reason(out).contains("PKG-TYPO"), reason(out));
            assertTrue(reason(out).contains("Did you mean 'lodash'?"), reason(out));
            assertTrue(reason(out).contains("do not retry"), reason(out));
        }

        @Test
        void hallucinatedPackageIsDenied() {
            JsonNode out = hook("Bash", "npm i express-jwt-auth-helper-pro").orElseThrow();
            assertEquals("deny", decision(out));
            assertTrue(reason(out).contains("PKG-EXIST"));
            assertFalse(reason(out).contains("pkg-allow.txt"), "nonexistent packages cannot be allowed");
        }

        @Test
        void maliciousPackageReasonDoesNotSuggestAllowList() {
            http.onPostFirst(OSV, "\"esbuild\"", 200, FakeFetcher.fixture("osv/malicious.json"));
            JsonNode out = hook("Bash", "npm i esbuild").orElseThrow();
            assertEquals("deny", decision(out));
            assertFalse(reason(out).contains("pkg-allow.txt"), reason(out));
        }

        @Test
        void reviewableBlockSuggestsAllowListLine() {
            JsonNode out = hook("Bash", "npm i lodahs").orElseThrow();
            assertTrue(reason(out).contains("'npm:lodahs@4.17.21  # reason'"), reason(out));
        }

        @Test
        void legitimateInstallProducesNoOutput() {
            assertTrue(hook("Bash", "npm install lodash && pip install requests").isEmpty());
        }

        @Test
        void nonInstallCommandsProduceNoOutputAndNoNetwork() {
            assertTrue(hook("Bash", "git status && ./gradlew build").isEmpty());
            assertTrue(http.calls().isEmpty());
        }

        @Test
        void otherToolsAreIgnored() {
            assertTrue(hook("Write", "npm install lodahs").isEmpty());
        }

        @Test
        void powershellToolIsChecked() {
            assertEquals("deny", decision(hook("PowerShell", "npm install lodahs").orElseThrow()));
        }

        @Test
        void warningsAsk() {
            assertEquals("ask", decision(hook("Bash", "npm i hijacked-lib").orElseThrow()));
        }

        @Test
        void manifestInstallAsks() {
            JsonNode out = hook("Bash", "npm ci").orElseThrow();
            assertEquals("ask", decision(out));
            assertTrue(reason(out).contains("NOT CHECKED"));
        }

        @Test
        void registryOutageAsksInsteadOfPassing() {
            assertEquals("ask", decision(hook("Bash", "npm install flaky-pkg").orElseThrow()));
        }

        @Test
        void neverAllows() {
            for (String c : List.of("npm install lodash", "npm i lodahs", "npm ci", "npm i hijacked-lib")) {
                hook("Bash", c).ifPresent(o -> assertFalse(decision(o).equals("allow"), c));
            }
        }

        @Test
        void brokenInputAsksInsteadOfCrashing() {
            Run r = run("{not json", "hook", "claude");
            assertEquals(0, r.code());
            assertEquals("ask", decision(Reports.JSON.readTree(r.out())));
        }

        @Test
        void internalErrorAsks() {
            HookHandler h = new HookHandler(new InstallCommandParser(), () -> {
                throw new IllegalStateException("boom");
            }, dir -> AllowList.empty());
            String out = h.handle(input("Bash", "npm i lodash"), null).orElseThrow();
            assertEquals("ask", decision(Reports.JSON.readTree(out)));
        }

        @Test
        void allowListFromProjectDirTurnsAskIntoNothing() {
            allow = dir -> AllowList.parse(List.of("npm:hijacked-lib@3.3.6 # reviewed by a human"));
            assertTrue(hook("Bash", "npm i hijacked-lib").isEmpty());
        }

        @Test
        void allowListCannotUnblockNonexistentPackages() {
            allow = dir -> AllowList.parse(List.of("npm:express-jwt-auth-helper-pro@latest # trust me"));
            assertEquals("deny", decision(hook("Bash", "npm i express-jwt-auth-helper-pro").orElseThrow()));
        }
    }
}
