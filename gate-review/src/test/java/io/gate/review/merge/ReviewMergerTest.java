package io.gate.review.merge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gate.common.finding.Decision;
import io.gate.common.finding.Finding;
import io.gate.common.finding.FindingSource;
import io.gate.common.finding.Location;
import io.gate.review.Cwe;
import io.gate.review.LabFiles;
import io.gate.review.SourceFile;
import io.gate.review.ai.AiFinding;
import io.gate.review.ai.AiResult;
import io.gate.review.ai.QuoteVerifier;
import io.gate.review.staticscan.SemgrepResult;
import io.gate.review.staticscan.StaticHit;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 실제 실습장 코드 + 녹화된 Semgrep 결과 + 가짜 AI 응답으로 판정·합치기를 확인한다 (API 키 불필요). */
class ReviewMergerTest {

    static final String VULN = "gate-lab/src/main/java/io/gate/lab/vulnerable/";
    static final List<SourceFile> FILES = LabFiles.all();
    static final List<StaticHit> HITS = SemgrepResult.parse(LabFiles.fixture("semgrep-lab.json")).hits();

    static AiFinding ai(String file, int line, int end, String cwe, String quote) {
        return new AiFinding(VULN + file, line, end, cwe, "AI title", "HIGH", "because ...", quote, "high");
    }

    static AiResult ok(AiFinding... findings) {
        return new AiResult(AiResult.Status.OK, List.of(findings), "fake-model", 100, 50, null);
    }

    ReviewReport merge(AiResult ai) {
        return new ReviewMerger(Clock.systemUTC()).merge(FILES, HITS, List.of(), "semgrep 1.182.1", ai);
    }

    static Finding at(ReviewReport r, String file, int line) {
        return r.findings().stream().filter(f -> f.location().equals(new Location.File(VULN + file, line)))
                .findFirst().orElseThrow(() -> new AssertionError("no finding at " + file + ":" + line));
    }

    @Test
    void staticOnlyWhenAiIsSkipped() {
        ReviewReport r = merge(AiResult.skipped("no API key"));
        assertEquals(6, r.findings().size());
        assertTrue(r.findings().stream().allMatch(f -> f.source() == FindingSource.RULE));
        assertEquals(Decision.BLOCK, at(r, "ProductSearchController.java", 26).decision(), "ERROR rule blocks");
        assertEquals(Decision.WARN, at(r, "ApiErrorHandler.java", 40).decision(), "WARNING rule warns");
        assertEquals("return jdbc.queryForList(sql);",
                at(r, "ProductSearchController.java", 26).evidence().get("code"), "code is read from the file");
    }

    @Test
    void ruleAndAiAgreeingOnTheSameProblemBlock() {
        ReviewReport r = merge(ok(ai("ProductSearchController.java", 24, 26, "CWE-89",
                "\"%' \" +\n                \"ORDER BY id\";")));
        Finding f = at(r, "ProductSearchController.java", 26);
        assertEquals(FindingSource.RULE_AND_AI, f.source());
        assertEquals(Decision.BLOCK, f.decision());
        assertTrue(f.evidence().containsKey("ai"));
        assertEquals(6, r.findings().size(), "merged, not duplicated");
    }

    @Test
    void aiAloneNeverBlocksEvenWhenItIsRight() {
        // V1 IDOR: 정적 규칙이 없는 논리 결함. AI만 지적 → WARN(사람이 보라는 표시)
        AiFinding idor = new AiFinding(VULN + "OrderDetailController.java", 51, 53, "CWE-639",
                "Order lookup has no ownership check", "CRITICAL", "any user can access any order",
                "return repository.findOrder(id).orElseThrow(", "high");
        ReviewReport r = merge(ok(idor));
        Finding f = at(r, "OrderDetailController.java", 51);
        assertEquals(FindingSource.AI, f.source());
        assertEquals(Decision.WARN, f.decision());
        assertEquals("REVIEW-AI-CWE-639", f.ruleId());
    }

    @Test
    void differentCweFamilyIsNotMerged() {
        ReviewReport r = merge(ok(ai("ProductSearchController.java", 26, 26, "CWE-79", "return jdbc.queryForList(sql);")));
        assertEquals(7, r.findings().size());
        assertEquals(FindingSource.RULE, at(r, "ProductSearchController.java", 26).source());
    }

    @Test
    void hallucinatedQuotesAreRejectedAndCounted() {
        ReviewReport r = merge(ok(
                ai("OrderDetailController.java", 30, 30, "CWE-639", "if (user.isAdmin()) return order;"),
                ai("NoSuchFile.java", 1, 1, "CWE-89", "SELECT * FROM x"),
                ai("BearerTokenFilter.java", 999, 999, "CWE-347", "decode(parts[1])"),
                ai("BearerTokenFilter.java", 30, 30, "CWE-347", "x")));
        assertEquals(4, r.rejected().size());
        assertEquals(6, r.findings().size(), "nothing from the rejected findings");
        assertTrue(r.rejected().get(0).reason().contains("does not appear"));
    }

    @Test
    void refusedOrFailedAiLeavesStaticResultsAndIsReported() {
        for (AiResult.Status s : List.of(AiResult.Status.REFUSED, AiResult.Status.ERROR)) {
            AiResult failed = new AiResult(s, List.of(ai("ProductSearchController.java", 26, 26, "CWE-89",
                    "return jdbc.queryForList(sql);")), "fake-model", 0, 0, "x");
            ReviewReport r = merge(failed);
            assertTrue(r.findings().stream().allMatch(f -> f.source() == FindingSource.RULE), s.toString());
            assertEquals(s, r.ai().status());
        }
    }

    @Test
    void fixedVariantWithNoFindingsPasses() {
        List<SourceFile> fixed = FILES.stream().filter(f -> f.path().contains("/fixed/")).toList();
        ReviewReport r = new ReviewMerger(Clock.systemUTC()).merge(fixed, List.of(), List.of(), "semgrep 1.182.1",
                ok());
        assertTrue(r.findings().isEmpty());
        assertEquals(Decision.PASS, r.decision());
    }

    @Test
    void cweFamilies() {
        assertTrue(Cwe.sameFamily("CWE-89: SQL", "cwe-564"));
        assertTrue(Cwe.sameFamily("CWE-639", "CWE-862"));
        assertFalse(Cwe.sameFamily("CWE-89", "CWE-79"));
        assertFalse(Cwe.sameFamily(null, "CWE-79"));
    }

    @Test
    void realQuoteIsAccepted() {
        SourceFile filter = FILES.stream().filter(f -> f.path().endsWith("vulnerable/BearerTokenFilter.java"))
                .findFirst().orElseThrow();
        assertTrue(QuoteVerifier.rejectReason(ai("BearerTokenFilter.java", 30, 30, "CWE-347",
                "Base64.getUrlDecoder().decode(parts[1])"), Map.of(filter.path(), filter)).isEmpty());
    }
}
