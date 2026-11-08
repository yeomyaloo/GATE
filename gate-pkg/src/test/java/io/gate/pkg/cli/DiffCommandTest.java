package io.gate.pkg.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gate.pkg.FakeFetcher;
import io.gate.pkg.manifest.DependencyDiff;
import io.gate.pkg.manifest.GitReader;
import io.gate.pkg.scan.AllowList;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 임시 폴더에 실제 git 저장소를 만들어 PR 의존성 검사 흐름을 확인한다 (레지스트리는 녹화본). */
class DiffCommandTest {

    @TempDir
    Path repo;

    final FakeFetcher http = CliTest.fixtures();

    void git(String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of("git", "-c", "user.name=test", "-c", "user.email=test@example.invalid",
                "-c", "commit.gpgsign=false"));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(repo.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) {
            throw new IllegalStateException(String.join(" ", args) + ": " + out);
        }
    }

    void commit(String file, String content) throws Exception {
        Path f = repo.resolve(file);
        Files.createDirectories(f.getParent());
        Files.writeString(f, content);
        git("add", "-A");
        git("commit", "-q", "-m", "change " + file);
    }

    @BeforeEach
    void init() throws Exception {
        git("init", "-q");
        commit("package.json", "{\"dependencies\":{\"lodash\":\"4.17.21\"}}");
        git("tag", "base");
    }

    record Run(int code, String out) {
    }

    Run diff(String... extra) {
        List<String> args = new ArrayList<>(List.of("diff", "--base", "base", "--repo", repo.toString()));
        args.addAll(List.of(extra));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int code = new GatePkgCli(() -> CliTest.scanner(http), AllowList::load).run(args.toArray(String[]::new),
                new ByteArrayInputStream(new byte[0]), new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        return new Run(code, out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void addedTyposquatFailsThePr() throws Exception {
        commit("package.json", "{\"dependencies\":{\n  \"lodash\":\"4.17.21\",\n  \"lodahs\":\"^4.17.21\"\n}}");
        Run r = diff();
        assertEquals(GatePkgCli.BLOCK, r.code(), r.out());
        assertTrue(r.out().contains("npm lodahs"), r.out());
        assertTrue(!r.out().contains("npm lodash"), "unchanged dependencies are not re-checked: " + r.out());
    }

    @Test
    void unchangedManifestsPass() throws Exception {
        commit("README.md", "docs");
        Run r = diff();
        assertEquals(GatePkgCli.PASS, r.code());
        assertTrue(r.out().contains("No new or changed dependencies"), r.out());
    }

    @Test
    void versionBumpIsChecked() throws Exception {
        commit("package.json", "{\"dependencies\":{\"lodash\":\"4.17.20\"}}");
        DependencyDiff.Result d = DependencyDiff.between(new GitReader(repo), "base", "HEAD");
        assertEquals(1, d.changes().size());
        assertEquals("4.17.21", d.changes().get(0).before());
        assertEquals(GatePkgCli.PASS, diff().code());
    }

    @Test
    void pythonManifestInSubfolderAndLineNumbers() throws Exception {
        commit("svc/requirements.txt", "requests==2.32.3\nreqeusts\n");
        DependencyDiff.Result d = DependencyDiff.between(new GitReader(repo), "base", "HEAD");
        assertEquals(List.of("pypi:requests@==2.32.3", "pypi:reqeusts"),
                d.changes().stream().map(c -> c.request().toString()).toList());
        assertEquals("svc/requirements.txt", d.changes().get(1).file());
        assertEquals(2, d.changes().get(1).line());
        assertEquals(GatePkgCli.BLOCK, diff().code(), "reqeusts does not exist (404 in fixtures)");
    }

    @Test
    void allowListInRepoIsUsed() throws Exception {
        commit("package.json", "{\"dependencies\":{\"lodash\":\"4.17.21\",\"hijacked-lib\":\"3.3.6\"}}");
        assertEquals(GatePkgCli.WARN, diff().code());
        commit(".gate/pkg-allow.txt", "npm:hijacked-lib@3.3.6 # reviewed\n");
        assertEquals(GatePkgCli.PASS, diff().code());
    }

    @Test
    void badRefIsAnInputError() {
        assertEquals(GatePkgCli.UNCHECKABLE, new GatePkgCli(() -> CliTest.scanner(http), AllowList::load)
                .run(new String[] {"diff", "--base", "--output=/tmp/x", "--repo", repo.toString()},
                        new ByteArrayInputStream(new byte[0]), new PrintStream(new ByteArrayOutputStream()),
                        new PrintStream(new ByteArrayOutputStream())));
    }

    @Test
    void githubAnnotationsAndSummaryEscaping() {
        assertEquals("a%3Ab%2Cc%0Ad%25", GitHubOutput.property("a:b,c\nd%"));
        assertEquals("a:b,c%0Ad", GitHubOutput.data("a:b,c\nd"));
        var report = CliTest.scanner(http).scan(List.of(io.gate.pkg.registry.PackageRequest.npm("lodahs", null)));
        var v = report.packages().get(0);
        var change = new DependencyDiff.Change(v.request(), "web/package.json", 3, null, "^4.17.21");
        String ann = GitHubOutput.annotations(report, Map.of(v, change));
        assertTrue(ann.startsWith("::error file=web/package.json,line=3,title=GATE BLOCK npm%3Alodahs::"), ann);
        String md = GitHubOutput.summary(report, Map.of(v, change), List.of(), List.of("web/package.json"));
        assertTrue(md.contains("| ⛔ BLOCK | `npm:lodahs@4.17.21` | added ^4.17.21 | web/package.json:3 |"), md);
    }
}
