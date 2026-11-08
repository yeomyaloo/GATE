package io.gate.pkg.check;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gate.common.finding.Decision;
import io.gate.common.finding.Finding;
import io.gate.common.finding.Severity;
import io.gate.pkg.MutableClock;
import io.gate.pkg.registry.DownloadsResult;
import io.gate.pkg.registry.Ecosystem;
import io.gate.pkg.registry.OsvClient;
import io.gate.pkg.registry.PackageInfo;
import io.gate.pkg.registry.PublisherHistory;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** 2주차 규칙(MAINT·VULN·TYPO·COMBO)마다 "해당" 케이스와 "비해당" 케이스를 둔다. */
class Week2ChecksTest {

    static final Instant NOW = MutableClock.NOW;
    static final Instant OLD = Instant.parse("2015-01-01T00:00:00Z");
    static final UUID RUN = UUID.randomUUID();
    static final DownloadsResult MANY = new DownloadsResult.Known(1_000_000, "u");

    static PackageInfo info(String resolved, Instant created, PublisherHistory publishers) {
        return new PackageInfo(Ecosystem.NPM, "demo", "url", resolved, resolved, resolved, null, created, OLD,
                Map.of(), false, publishers);
    }

    static CheckContext ctx(PackageInfo info, DownloadsResult downloads, OsvClient.Result vulns, TypoSignal typo) {
        return new CheckContext(RUN, info, downloads, vulns, typo, NOW, Thresholds.defaults());
    }

    static Finding single(List<Finding> findings) {
        assertEquals(1, findings.size(), findings.toString());
        return findings.get(0);
    }

    @Nested
    class Maint {

        CheckContext with(PublisherHistory h) {
            return ctx(info("3.3.6", OLD, h), MANY, null, null);
        }

        @Test
        void unknownPublisherWarnsHigh() {
            Finding f = single(new MaintainerCheck().evaluate(
                    with(new PublisherHistory("right9ctrl", "3.3.5", "original-author", Set.of("original-author")))));
            assertEquals(Rules.MAINT, f.ruleId());
            assertEquals(Decision.WARN, f.decision());
            assertEquals(Severity.HIGH, f.severity());
            assertEquals("original-author", f.evidence().get("previousPublisher"));
        }

        @Test
        void samePublisherPasses() {
            assertTrue(new MaintainerCheck().evaluate(
                    with(new PublisherHistory("a", "1.0.0", "a", Set.of("a")))).isEmpty());
        }

        @Test
        void existingCoMaintainerPasses() {
            assertTrue(new MaintainerCheck().evaluate(
                    with(new PublisherHistory("b", "1.0.0", "a", Set.of("a", "b")))).isEmpty());
        }

        @Test
        void firstVersionOrNoHistoryPasses() {
            assertTrue(new MaintainerCheck().evaluate(with(new PublisherHistory("a", null, null, Set.of()))).isEmpty());
            assertTrue(new MaintainerCheck().evaluate(with(null)).isEmpty());
        }
    }

    @Nested
    class Vuln {

        static OsvClient.Vulnerability vuln(String id, Severity s, boolean malicious) {
            return new OsvClient.Vulnerability(id, Set.of(), "summary", s, malicious);
        }

        List<Finding> eval(OsvClient.Result r) {
            return new VulnCheck().evaluate(ctx(info("1.0.0", OLD, null), MANY, r, null));
        }

        @Test
        void maliciousBlocks() {
            Finding f = single(eval(new OsvClient.Result.Known(List.of(vuln("MAL-2022-1", null, true)), "u")));
            assertEquals(Decision.BLOCK, f.decision());
            assertEquals(Severity.CRITICAL, f.severity());
        }

        @Test
        void criticalBlocks() {
            Finding f = single(eval(new OsvClient.Result.Known(
                    List.of(vuln("GHSA-1", Severity.HIGH, false), vuln("GHSA-2", Severity.CRITICAL, false)), "u")));
            assertEquals(Decision.BLOCK, f.decision());
            assertEquals(2, f.evidence().get("count"));
        }

        @Test
        void highWarns() {
            Finding f = single(eval(new OsvClient.Result.Known(List.of(vuln("GHSA-1", Severity.HIGH, false)), "u")));
            assertEquals(Decision.WARN, f.decision());
            assertEquals(Severity.HIGH, f.severity());
        }

        @Test
        void unknownSeverityWarnsMedium() {
            Finding f = single(eval(new OsvClient.Result.Known(List.of(vuln("PYSEC-1", null, false)), "u")));
            assertEquals(Severity.MEDIUM, f.severity());
        }

        @Test
        void maliciousAndRegularAreSeparateFindings() {
            assertEquals(2, eval(new OsvClient.Result.Known(
                    List.of(vuln("MAL-1", null, true), vuln("GHSA-1", Severity.LOW, false)), "u")).size());
        }

        @Test
        void outageIsUnchecked() {
            Finding f = single(eval(new OsvClient.Result.Unknown("u", "HTTP 503")));
            assertEquals(Rules.UNCHECKED, f.ruleId());
            assertEquals(Rules.VULN, f.evidence().get("check"));
        }

        @Test
        void cleanOrNotQueriedPasses() {
            assertTrue(eval(new OsvClient.Result.Known(List.of(), "u")).isEmpty());
            assertTrue(eval(null).isEmpty());
        }
    }

    @Nested
    class Typo {

        static final TypoDetector.TypoMatch LODASH = new TypoDetector.TypoMatch("lodash", "edit-distance", 40);

        List<Finding> eval(Instant created, DownloadsResult own, DownloadsResult target, boolean suspected) {
            TypoSignal signal = suspected ? new TypoSignal(LODASH, target) : null;
            return new TypoCheck().evaluate(ctx(info("1.0.0", created, null), own, null, signal));
        }

        @Test
        void youngLookAlikeBlocks() {
            Finding f = single(eval(NOW.minus(Duration.ofDays(30)), new DownloadsResult.Known(500_000, "u"),
                    new DownloadsResult.Known(200_000_000, "u"), true));
            assertEquals(Decision.BLOCK, f.decision());
            assertEquals("lodash", f.evidence().get("similarTo"));
        }

        @Test
        void oldLookAlikeWithTinyShareBlocks() {
            Finding f = single(eval(OLD, new DownloadsResult.Known(300, "u"),
                    new DownloadsResult.Known(200_000_000, "u"), true));
            assertEquals(Decision.BLOCK, f.decision());
        }

        @Test
        void establishedSimilarNameOnlyWarnsLow() {
            Finding f = single(eval(OLD, new DownloadsResult.Known(5_000_000, "u"),
                    new DownloadsResult.Known(200_000_000, "u"), true));
            assertEquals(Decision.WARN, f.decision());
            assertEquals(Severity.LOW, f.severity());
        }

        @Test
        void unknownDownloadsWarnMedium() {
            Finding f = single(eval(OLD, new DownloadsResult.Unknown("u", "x"),
                    new DownloadsResult.Known(200_000_000, "u"), true));
            assertEquals(Decision.WARN, f.decision());
            assertEquals(Severity.MEDIUM, f.severity());
        }

        @Test
        void notSuspectedPasses() {
            assertTrue(eval(OLD, MANY, MANY, false).isEmpty());
        }
    }

    @Nested
    class Combo {

        static Finding f(String rule) {
            return Rules.finding(RUN, rule, rule, Severity.MEDIUM, Decision.WARN,
                    new io.gate.common.finding.Location.Package("npm", "demo", "1.0.0"), Map.of("k", "v"), NOW);
        }

        CheckContext c() {
            return ctx(info("1.0.0", OLD, null), MANY, null, null);
        }

        @Test
        void newPlusScriptBlocks() {
            Finding combo = ComboRule.evaluate(c(), List.of(f(Rules.NEW), f(Rules.SCRIPT))).orElseThrow();
            assertEquals(Decision.BLOCK, combo.decision());
            assertEquals(List.of(Rules.NEW, Rules.SCRIPT), combo.evidence().get("signals"));
        }

        @Test
        void newPlusPopBlocks() {
            assertTrue(ComboRule.evaluate(c(), List.of(f(Rules.POP), f(Rules.NEW))).isPresent());
        }

        @Test
        void singleSignalsOrNoNewDoNotCombine() {
            assertTrue(ComboRule.evaluate(c(), List.of(f(Rules.NEW))).isEmpty());
            assertTrue(ComboRule.evaluate(c(), List.of(f(Rules.SCRIPT), f(Rules.POP))).isEmpty());
        }
    }
}
