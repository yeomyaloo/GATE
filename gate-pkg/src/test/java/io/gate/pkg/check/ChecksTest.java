package io.gate.pkg.check;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gate.common.finding.Decision;
import io.gate.common.finding.Finding;
import io.gate.common.finding.Severity;
import io.gate.pkg.MutableClock;
import io.gate.pkg.registry.DownloadsResult;
import io.gate.pkg.registry.Ecosystem;
import io.gate.pkg.registry.PackageInfo;
import io.gate.pkg.registry.PackageRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** 1주차 검사 규칙마다 "해당" 케이스와 "비해당" 케이스를 둔다. */
class ChecksTest {

    static final Instant NOW = MutableClock.NOW;
    static final Instant OLD = Instant.parse("2015-01-01T00:00:00Z");
    static final UUID RUN = UUID.randomUUID();

    static PackageInfo info(String resolved, Instant created, Instant versionAt, Map<String, String> scripts,
            boolean sdistOnly) {
        return new PackageInfo(Ecosystem.NPM, "demo", "https://registry.npmjs.org/demo", resolved, "1.0.0",
                resolved, null, created, versionAt, scripts, sdistOnly);
    }

    static PackageInfo oldPackage() {
        return info("1.0.0", OLD, OLD, Map.of(), false);
    }

    static CheckContext ctx(PackageInfo info, DownloadsResult downloads) {
        return new CheckContext(RUN, info, downloads, NOW, Thresholds.defaults());
    }

    static CheckContext ctx(PackageInfo info) {
        return ctx(info, new DownloadsResult.Known(1_000_000, "u"));
    }

    static Finding single(List<Finding> findings) {
        assertEquals(1, findings.size(), findings.toString());
        return findings.get(0);
    }

    @Nested
    class Exist {

        @Test
        void missingPackageBlocksAsCritical() {
            Finding f = ExistCheck.packageMissing(RUN, PackageRequest.npm("reqeusts-helper", null),
                    "https://registry.npmjs.org/reqeusts-helper", NOW);
            assertEquals(Rules.EXIST, f.ruleId());
            assertEquals(Decision.BLOCK, f.decision());
            assertEquals(Severity.CRITICAL, f.severity());
            assertEquals(404, f.evidence().get("httpStatus"));
        }

        @Test
        void missingVersionBlocksAsHigh() {
            PackageInfo info = new PackageInfo(Ecosystem.NPM, "demo", "url", "9.9.9", "1.0.0", null, null, OLD,
                    null, Map.of(), false);
            Finding f = single(new ExistCheck().evaluate(ctx(info)));
            assertEquals(Decision.BLOCK, f.decision());
            assertEquals(Severity.HIGH, f.severity());
            assertEquals("9.9.9", f.evidence().get("requestedVersion"));
            assertEquals("1.0.0", f.evidence().get("latestVersion"));
        }

        @Test
        void existingVersionPasses() {
            assertTrue(new ExistCheck().evaluate(ctx(oldPackage())).isEmpty());
        }
    }

    @Nested
    class New {

        @Test
        void packagePublishedRecentlyWarns() {
            Instant created = NOW.minus(Duration.ofDays(2));
            Finding f = single(new NewPackageCheck().evaluate(ctx(info("1.0.0", created, created, Map.of(), false))));
            assertEquals(Rules.NEW, f.ruleId());
            assertEquals(Decision.WARN, f.decision());
            assertEquals(48L, f.evidence().get("ageHours"));
        }

        @Test
        void freshVersionOfOldPackageWarns() {
            Finding f = single(new NewPackageCheck().evaluate(
                    ctx(info("2.1.0", OLD, NOW.minus(Duration.ofHours(6)), Map.of(), false))));
            assertTrue(f.title().contains("Version '2.1.0'"), f.title());
        }

        @Test
        void exactlyAtThresholdIsNotNew() {
            Instant created = NOW.minus(Duration.ofDays(7));
            assertTrue(new NewPackageCheck().evaluate(
                    ctx(info("1.0.0", created, NOW.minus(Duration.ofHours(24)), Map.of(), false))).isEmpty());
        }

        @Test
        void oldPackagePasses() {
            assertTrue(new NewPackageCheck().evaluate(ctx(oldPackage())).isEmpty());
        }

        @Test
        void unknownTimesPass() {
            assertTrue(new NewPackageCheck().evaluate(ctx(info("1.0.0", null, null, Map.of(), false))).isEmpty());
        }
    }

    @Nested
    class Script {

        @Test
        void installScriptWarns() {
            Finding f = single(new InstallScriptCheck().evaluate(
                    ctx(info("1.0.0", OLD, OLD, Map.of("postinstall", "node install.js"), false))));
            assertEquals(Rules.SCRIPT, f.ruleId());
            assertEquals(Severity.MEDIUM, f.severity());
            assertEquals(Map.of("postinstall", "node install.js"), f.evidence().get("scripts"));
        }

        @Test
        void longScriptIsTruncatedInEvidence() {
            String longScript = "x".repeat(500);
            Finding f = single(new InstallScriptCheck().evaluate(
                    ctx(info("1.0.0", OLD, OLD, Map.of("preinstall", longScript), false))));
            String shown = (String) ((Map<?, ?>) f.evidence().get("scripts")).get("preinstall");
            assertEquals(InstallScriptCheck.MAX_SCRIPT_CHARS + 1, shown.length());
        }

        @Test
        void sdistOnlyWarnsLow() {
            Finding f = single(new InstallScriptCheck().evaluate(ctx(info("1.0.0", OLD, OLD, Map.of(), true))));
            assertEquals(Severity.LOW, f.severity());
        }

        @Test
        void noScriptPasses() {
            assertTrue(new InstallScriptCheck().evaluate(ctx(oldPackage())).isEmpty());
        }
    }

    @Nested
    class Popularity {

        @Test
        void fewDownloadsWarns() {
            Finding f = single(new PopularityCheck().evaluate(ctx(oldPackage(), new DownloadsResult.Known(12, "u"))));
            assertEquals(Rules.POP, f.ruleId());
            assertEquals(Severity.LOW, f.severity());
            assertEquals(12L, f.evidence().get("weeklyDownloads"));
        }

        @Test
        void thresholdItselfPasses() {
            assertTrue(new PopularityCheck().evaluate(ctx(oldPackage(), new DownloadsResult.Known(100, "u")))
                    .isEmpty());
        }

        @Test
        void unknownDownloadsAreReportedAsUnchecked() {
            Finding f = single(new PopularityCheck().evaluate(
                    ctx(oldPackage(), new DownloadsResult.Unknown("u", "HTTP 503"))));
            assertEquals(Rules.UNCHECKED, f.ruleId());
            assertEquals(Decision.WARN, f.decision());
            assertEquals(Rules.POP, f.evidence().get("check"));
        }
    }
}
