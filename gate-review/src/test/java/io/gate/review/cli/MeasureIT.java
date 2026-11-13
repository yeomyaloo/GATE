package io.gate.review.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.gate.review.LabFiles;
import io.gate.review.staticscan.SemgrepRunner;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 실제 Semgrep(고정 버전)과 저장소 규칙으로 실습장을 측정한다. 문서의 정적 분석 수치가 바뀌면 여기서 먼저 깨진다.
 * CI에서는 건너뛰면 실패.
 */
class MeasureIT {

    static String semgrep;

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

    record Run(int code, String out, String err) {
    }

    static Run measure(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = new GateReviewCli(Map.of("GATE_SEMGREP", semgrep)::get, effort -> Optional.empty())
                .run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                        new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Run(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void repositoryRulesFindV2V3V4WithoutFalsePositives(@TempDir Path tmp) throws Exception {
        Path json = tmp.resolve("m.json");
        Run r = measure("measure", "--repo", LabFiles.ROOT.toString(), "--out", json.toString());
        assertEquals(GateReviewCli.PASS, r.code(), r.out() + r.err());
        JsonNode root = JsonMapper.builder().build().readTree(Files.readString(json));
        JsonNode vuln = root.path("runs").get(0).path("variants").get(0).path("scores").path("staticOnly");
        JsonNode fixed = root.path("runs").get(0).path("variants").get(1).path("scores").path("staticOnly");
        assertEquals("[\"V2\",\"V3\",\"V4\"]", sorted(vuln.path("detected")));
        assertEquals("[\"V1\"]", vuln.path("missed").toString(), "IDOR is a logic flaw: no static rule");
        assertEquals(0, vuln.path("falsePositives").size());
        assertEquals(0, fixed.path("total").asInt(), "the fixed variant is clean");
        assertTrue(r.out().contains("static only"), r.out());
    }

    @Test
    void aiNeedsAKeyAndAnExplicitBudget() {
        Run noBudget = measure("measure", "--repo", LabFiles.ROOT.toString(), "--ai");
        assertEquals(GateReviewCli.ERROR, noBudget.code());
        assertTrue(noBudget.err().contains("--budget-usd"), noBudget.err());
        Run noKey = measure("measure", "--repo", LabFiles.ROOT.toString(), "--ai", "--budget-usd", "3");
        assertEquals(GateReviewCli.ERROR, noKey.code());
        assertTrue(noKey.err().contains("ANTHROPIC_API_KEY"), noKey.err());
    }

    @Test
    void estimateMakesNoCall() {
        Run r = measure("measure", "--repo", LabFiles.ROOT.toString(), "--estimate", "--runs", "3");
        assertEquals(GateReviewCli.PASS, r.code(), r.err());
        assertTrue(r.out().contains("no API call made") && r.out().contains("3 runs"), r.out());
    }

    private static String sorted(JsonNode array) {
        java.util.List<String> ids = new java.util.ArrayList<>();
        array.forEach(n -> ids.add("\"" + n.asString() + "\""));
        ids.sort(null);
        return "[" + String.join(",", ids) + "]";
    }
}
