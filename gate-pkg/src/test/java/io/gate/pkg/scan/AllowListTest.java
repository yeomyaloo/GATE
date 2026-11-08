package io.gate.pkg.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gate.common.finding.Decision;
import io.gate.common.finding.Finding;
import io.gate.common.finding.Location;
import io.gate.common.finding.Severity;
import io.gate.pkg.MutableClock;
import io.gate.pkg.check.Rules;
import io.gate.pkg.check.VulnCheck;
import io.gate.pkg.registry.Ecosystem;
import io.gate.pkg.registry.PackageRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AllowListTest {

    static final UUID RUN = UUID.randomUUID();

    static Finding finding(String rule, Decision d, String name, String version, Map<String, Object> evidence) {
        return Rules.finding(RUN, rule, rule, Severity.HIGH, d, new Location.Package("npm", name, version), evidence,
                MutableClock.NOW);
    }

    static PackageVerdict verdict(String name, Finding... findings) {
        return PackageVerdict.of(PackageRequest.npm(name, null), List.of(findings));
    }

    final AllowList allow = AllowList.parse(List.of(
            "# comment",
            "npm:preact-compat@3.19.0   # reviewed 2026-11-10, react compat layer",
            "npm:@scope/thing@1.0.0 # internal fork",
            "pypi:Requests_Mock@1.12.1 # test only",
            "npm:no-version   # missing version",
            "npm:no-reason@1.0.0",
            "cargo:serde@1.0.0 # unsupported ecosystem"));

    @Test
    void parsesValidEntriesAndReportsBadLines() {
        assertEquals(3, allow.entries().size());
        assertEquals(3, allow.problems().size(), allow.problems().toString());
        assertEquals("@scope/thing", allow.entries().get(1).name());
        assertEquals("requests-mock", allow.entries().get(2).name(), "PyPI names are normalized");
    }

    @Test
    void matchingEntryTurnsWarningsAndBlocksIntoPassWithEvidence() {
        PackageVerdict v = allow.apply(verdict("preact-compat",
                finding(Rules.TYPO, Decision.BLOCK, "preact-compat", "3.19.0", Map.of("similarTo", "preact")),
                finding(Rules.SCRIPT, Decision.WARN, "preact-compat", "3.19.0", Map.of("k", "v"))));
        assertEquals(Decision.PASS, v.decision());
        assertEquals(2, v.findings().size(), "findings are kept, not deleted");
        Finding f = v.findings().get(0);
        assertEquals("BLOCK", f.evidence().get("originalDecision"));
        assertTrue(((String) f.evidence().get("allowedBy")).startsWith(".gate/pkg-allow.txt:2"));
    }

    @Test
    void differentVersionIsNotAllowed() {
        PackageVerdict original = verdict("preact-compat",
                finding(Rules.TYPO, Decision.BLOCK, "preact-compat", "3.20.0", Map.of("similarTo", "preact")));
        assertSame(original, allow.apply(original));
    }

    @Test
    void nonexistentPackageCannotBeAllowed() {
        AllowList a = AllowList.parse(List.of("npm:ghost-pkg@1.0.0 # trust me"));
        PackageVerdict v = a.apply(verdict("ghost-pkg",
                finding(Rules.EXIST, Decision.BLOCK, "ghost-pkg", "1.0.0", Map.of("httpStatus", 404))));
        assertEquals(Decision.BLOCK, v.decision());
    }

    @Test
    void maliciousReportCannotBeAllowedButOtherFindingsAre() {
        AllowList a = AllowList.parse(List.of("npm:evil@1.0.0 # trust me"));
        PackageVerdict v = a.apply(verdict("evil",
                finding(Rules.VULN, Decision.BLOCK, "evil", "1.0.0", Map.of(VulnCheck.MALICIOUS_KEY, true)),
                finding(Rules.POP, Decision.WARN, "evil", "1.0.0", Map.of("k", "v"))));
        assertEquals(Decision.BLOCK, v.decision());
        assertEquals(Decision.PASS, v.findings().get(1).decision());
    }

    @Test
    void pypiNameNormalizationMatches() {
        PackageVerdict original = PackageVerdict.of(new PackageRequest(Ecosystem.PYPI, "requests.mock", null),
                List.of(Rules.finding(RUN, Rules.TYPO, "t", Severity.HIGH, Decision.BLOCK,
                        new Location.Package("pypi", "requests-mock", "1.12.1"), Map.of("k", "v"), MutableClock.NOW)));
        assertEquals(Decision.PASS, allow.apply(original).decision());
    }

    @Test
    void loadsFromProjectDirectoryOrIsEmpty(@TempDir Path dir) throws Exception {
        assertTrue(AllowList.load(dir).entries().isEmpty());
        Files.createDirectories(dir.resolve(".gate"));
        Files.writeString(dir.resolve(AllowList.RELATIVE_PATH), "npm:a-b@1.0.0 # ok\n");
        assertEquals(1, AllowList.load(dir).entries().size());
    }
}
