package io.gate.review.measure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gate.review.LabFiles;
import io.gate.review.SourceFile;
import io.gate.review.ai.AiFinding;
import io.gate.review.ai.AiResult;
import io.gate.review.ai.AiReviewer;
import io.gate.review.staticscan.SemgrepResult;
import io.gate.review.staticscan.StaticHit;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 가짜 정적 분석·가짜 AI로 측정 흐름을 확인한다 (API 키·Semgrep 없이). */
class MeasurementTest {

    static final List<SourceFile> LAB = LabFiles.all();
    static final String APP = "gate-lab/src/main/java/io/gate/lab/app/";

    /** 중립 경로에서만 지적한다: 받은 코드에 SQL 문자열 연결이 있으면 V2 위치를 지적. */
    static final Measurement.StaticScan FAKE_STATIC = files -> {
        List<StaticHit> hits = new ArrayList<>();
        for (SourceFile f : files) {
            if (f.path().endsWith("app/ProductSearchController.java") && f.content().contains("\" + q + \"")) {
                hits.add(new StaticHit("sql", f.path(), 24, 26, "SQL built by concatenation", "CWE-89", "ERROR"));
            }
        }
        return new SemgrepResult("1.182.1", hits, List.of());
    };

    Measurement measurement() {
        return new Measurement(ScorerTest.TRUTH, LAB, FAKE_STATIC, Clock.systemUTC());
    }

    @Test
    void staticOnlyRunsOnceAndScoresBothVariants() throws Exception {
        Measurement.Result r = measurement().run("rules", Optional.empty(), "m", "high", 3, Pricing.OPUS_5_5, 0);
        assertEquals(1, r.runs().size(), "without AI the result is deterministic: one run");
        Measurement.VariantRun vuln = r.runs().get(0).variants().get(0);
        Measurement.VariantRun fixed = r.runs().get(0).variants().get(1);
        assertEquals("vulnerable", vuln.variant());
        assertEquals(List.of("V2"), vuln.scores().staticOnly().detected());
        assertEquals(List.of("V1", "V3", "V4"), vuln.scores().staticOnly().missed());
        assertNull(vuln.scores().aiOnly());
        assertEquals(List.of(), fixed.scores().staticOnly().falsePositives());
        assertEquals(AiResult.Status.SKIPPED, vuln.aiStatus());
        assertEquals(0.0, r.spentUsd());
    }

    @Test
    void aiFindingsAreMappedBackToOriginalPathsAndScored() throws Exception {
        List<List<String>> seen = new ArrayList<>();
        AiReviewer ai = (files, hints) -> {
            seen.add(files.stream().map(SourceFile::path).toList());
            List<AiFinding> out = new ArrayList<>();
            for (SourceFile f : files) {
                if (f.path().endsWith("app/OrderDetailController.java") && !f.content().contains("Authentication")) {
                    out.add(new AiFinding(f.path(), 29, 31, "CWE-639", "IDOR", "HIGH", "no owner check",
                            f.line(30).strip(), "high"));
                }
                if (f.path().endsWith("app/ProductSearchController.java")) {
                    out.add(new AiFinding(f.path(), 26, 26, "CWE-89", "SQLi", "HIGH", "concat",
                            "this text is not in the file", "high")); // 지어낸 인용 → 버려져야 함
                }
            }
            return new AiResult(AiResult.Status.OK, out, "m", 10_000, 5_000, null);
        };
        Measurement.Result r = measurement().run("rules", Optional.of(ai), "m", "high", 2, Pricing.OPUS_5_5, 10);
        assertEquals(2, r.runs().size());
        assertTrue(seen.stream().flatMap(List::stream).allMatch(p -> !p.contains("/vulnerable/")
                && !p.contains("/fixed/")), "the AI only ever sees neutral paths");

        Measurement.VariantRun vuln = r.runs().get(0).variants().get(0);
        assertEquals(1, vuln.aiRejected(), "made-up quote discarded");
        assertEquals(List.of("V1"), vuln.scores().aiOnly().detected());
        assertEquals(List.of("V2"), vuln.scores().combinedBlock().detected(), "AI-only IDOR is WARN, not BLOCK");
        assertTrue(vuln.scores().combinedAll().detected().containsAll(List.of("V1", "V2")));
        assertTrue(vuln.scores().aiOnly().matched().get(0).path().contains("/vulnerable/"), "original path");
        // 비용: 요청 4번(2회 × 2버전) × (1만 입력 × $4 + 5천 출력 × $20) / 100만 = 4 × $0.14
        assertEquals(0.56, r.spentUsd(), 1e-9);
    }

    @Test
    void budgetStopsFurtherAiCalls() throws Exception {
        int[] calls = {0};
        AiReviewer ai = (files, hints) -> {
            calls[0]++;
            return new AiResult(AiResult.Status.OK, List.of(), "m", 100_000, 20_000, null); // $0.80
        };
        Measurement.Result r = measurement().run("rules", Optional.of(ai), "m", "high", 3, Pricing.OPUS_5_5, 1.0);
        assertEquals(2, calls[0], "after $1.60 spent the remaining calls are skipped");
        Measurement.VariantRun skipped = r.runs().get(1).variants().get(0);
        assertEquals(AiResult.Status.SKIPPED, skipped.aiStatus());
        assertTrue(skipped.aiDetail().contains("budget"));
        assertFalse(r.spentUsd() > 1.6 + 1e-9);
    }
}
