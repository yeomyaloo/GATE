package io.gate.review.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.gate.review.LabFiles;
import io.gate.review.ai.AiFinding;
import io.gate.review.ai.AiResult;
import io.gate.review.ai.AiReviewer;
import io.gate.review.staticscan.SemgrepRunner;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * PR 흉내: 임시 git 저장소에서 고친 코드를 취약하게 바꾸는 커밋을 만들고 CLI로 검토한다.
 * 실제 semgrep(고정 버전)과 가짜 AI를 쓴다. CI에서는 건너뛰면 실패.
 */
class ReviewCliIT {

    static String semgrep;

    @TempDir
    Path repo;

    @BeforeAll
    static void requireSemgrep() {
        semgrep = System.getProperty("gate.semgrep", "semgrep");
        boolean ok;
        try {
            ok = new SemgrepRunner(semgrep).version().equals(SemgrepRunner.PINNED_VERSION);
        } catch (Exception e) {
            ok = false;
        }
        assumeTrue(ok, "semgrep not available");
    }

    void git(String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("git", "-c", "user.name=t", "-c", "user.email=t@example.invalid",
                "-c", "commit.gpgsign=false"));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(repo.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) {
            throw new IllegalStateException(out);
        }
    }

    record Run(int code, String out) {
    }

    Run review(AiReviewer ai, String... extra) {
        return review(Map.of("GATE_SEMGREP", semgrep), ai, extra);
    }

    Run review(Map<String, String> env, AiReviewer ai, String... extra) {
        List<String> args = new ArrayList<>(List.of("review", "--base", "base", "--repo", repo.toString(),
                "--rules", LabFiles.ROOT.resolve(GateReviewCli.DEFAULT_RULES).toString()));
        args.addAll(List.of(extra));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = new GateReviewCli(env::get, effort -> Optional.ofNullable(ai))
                .run(args.toArray(String[]::new), new PrintStream(out, true, StandardCharsets.UTF_8),
                        new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Run(code, out.toString(StandardCharsets.UTF_8) + err.toString(StandardCharsets.UTF_8));
    }

    void prepareRepo() throws Exception {
        git("init", "-q");
        Path fixedSearch = LabFiles.ROOT.resolve("gate-lab/src/main/java/io/gate/lab/fixed/ProductSearchController.java");
        Path vulnSearch = LabFiles.ROOT.resolve(
                "gate-lab/src/main/java/io/gate/lab/vulnerable/ProductSearchController.java");
        Path target = repo.resolve("src/main/java/demo/ProductSearchController.java");
        Files.createDirectories(target.getParent());
        Files.copy(fixedSearch, target);
        git("add", "-A");
        git("commit", "-q", "-m", "base");
        git("tag", "base");
        // PR: 바인딩 변수를 문자열 연결로 되돌리는 변경 (AI 코딩 도구가 만들 법한 실수)
        Files.writeString(target, Files.readString(vulnSearch).replace("package io.gate.lab.vulnerable;",
                "package io.gate.lab.fixed;"));
        git("commit", "-q", "-am", "simplify search");
    }

    @Test
    void sqlInjectionPrIsBlockedWithoutAi() throws Exception {
        prepareRepo();
        Run r = review(null, "--format", "json");
        assertEquals(GateReviewCli.BLOCK, r.code(), r.out());
        JsonNode root = JsonMapper.builder().build().readTree(r.out().substring(0, r.out().lastIndexOf('}') + 1));
        assertEquals("SKIPPED", root.path("ai").path("status").asString(), "no key: static analysis only, and said so");
        assertEquals("src/main/java/demo/ProductSearchController.java", root.path("findings").get(0).path("file")
                .asString());
        assertEquals("REVIEW-gate-java-sql-string-concat", root.path("findings").get(0).path("ruleId").asString());
    }

    @Test
    void agreeingAiMakesItRuleAndAi() throws Exception {
        prepareRepo();
        AiReviewer ai = (files, hints) -> new AiResult(AiResult.Status.OK, List.of(new AiFinding(
                "src/main/java/demo/ProductSearchController.java", 26, 26, "CWE-89", "SQL injection", "HIGH",
                "q is concatenated", "return jdbc.queryForList(sql);", "high")), "fake", 10, 5, null);
        Run r = review(ai);
        assertEquals(GateReviewCli.BLOCK, r.code(), r.out());
        assertTrue(r.out().contains("RULE_AND_AI"), r.out());
    }

    @Test
    void unchangedRepoIsClean() throws Exception {
        git("init", "-q");
        Files.writeString(repo.resolve("README.md"), "x");
        git("add", "-A");
        git("commit", "-q", "-m", "base");
        git("tag", "base");
        Run r = review(null);
        assertEquals(GateReviewCli.PASS, r.code(), r.out());
    }

    @Test
    void resultsAreStoredInGateServerWhenConfigured() throws Exception {
        prepareRepo();
        java.util.List<String> bodies = new java.util.ArrayList<>();
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/scan-runs", ex -> {
            bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            ex.sendResponseHeaders(201, -1);
            ex.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort();
            Run r = review(Map.of("GATE_SEMGREP", semgrep, "GATE_SERVER_URL", url), null, "--format", "json");
            assertEquals(GateReviewCli.BLOCK, r.code(), r.out());
            assertTrue(r.out().contains("reported to " + url), r.out());
            assertEquals(1, bodies.size());
            JsonNode body = JsonMapper.builder().build().readTree(bodies.get(0));
            assertEquals("REVIEW", body.path("module").asString());
            assertEquals("CLI", body.path("trigger").asString());
            assertEquals("review base..HEAD", body.path("target").asString());
            JsonNode f = body.path("findings").get(0);
            assertEquals("file", f.path("location").path("kind").asString());
            assertEquals("src/main/java/demo/ProductSearchController.java", f.path("location").path("path").asString());
            assertEquals("BLOCK", f.path("decision").asString());
            assertEquals("RULE", f.path("source").asString());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void unreachableServerDoesNotChangeTheDecision() throws Exception {
        prepareRepo();
        Run r = review(Map.of("GATE_SEMGREP", semgrep, "GATE_SERVER_URL", "http://127.0.0.1:9"), null);
        assertEquals(GateReviewCli.BLOCK, r.code(), r.out());
        assertTrue(r.out().contains("could not report to gate-server"), r.out());
    }
}
