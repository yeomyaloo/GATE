package io.gate.pkg.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gate.common.finding.Decision;
import io.gate.common.finding.Finding;
import io.gate.pkg.FakeFetcher;
import io.gate.pkg.MutableClock;
import io.gate.pkg.check.Rules;
import io.gate.pkg.check.Thresholds;
import io.gate.pkg.check.TypoDetector;
import io.gate.pkg.registry.Ecosystem;
import io.gate.pkg.registry.NpmRegistryClient;
import io.gate.pkg.registry.OsvClient;
import io.gate.pkg.registry.PackageRequest;
import io.gate.pkg.registry.PyPiRegistryClient;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** 2주차 전체 구성(npm + PyPI + OSV + 철자 흉내 + 조합 규칙)으로 녹화본을 검사한다. 네트워크 없음. */
class FullScanTest {

    static final String NPM = "https://registry.npmjs.org/";
    static final String NPM_DL = "https://api.npmjs.org/downloads/point/last-week/";
    static final String PYPI = "https://pypi.org/pypi/";
    static final String PYPI_DL = "https://pypistats.org/api/packages/";
    static final String OSV = "https://api.osv.dev/v1/query";

    final FakeFetcher http = new FakeFetcher()
            .onFixture(NPM + "lodash", "npm/lodash.json")
            .on(NPM_DL + "lodash", 200, "{\"downloads\":204729283}")
            .onFixture(NPM + "lodahs", "npm/lodahs.json")
            .on(NPM_DL + "lodahs", 200, "{\"downloads\":41}")
            .onFixture(NPM + "fresh-helper-utils", "npm/fresh-helper-utils.json")
            .on(NPM_DL + "fresh-helper-utils", 200, "{\"downloads\":3}")
            .onFixture(NPM + "hijacked-lib", "npm/hijacked-lib.json")
            .on(NPM_DL + "hijacked-lib", 200, "{\"downloads\":2000000}")
            .onFixture(NPM + "esbuild", "npm/esbuild.json")
            .on(NPM_DL + "esbuild", 200, "{\"downloads\":90000000}")
            .onFixture(PYPI + "requests/json", "pypi/requests.json")
            .on(PYPI_DL + "requests/recent", 200, "{\"data\":{\"last_week\":360913926}}")
            .onFixture(PYPI + "reqeusts/json", "pypi/reqeusts.json")
            .on(PYPI_DL + "reqeusts/recent", 404, "")
            .onPost(OSV, "\"esbuild\"", 200, FakeFetcher.fixture("osv/malicious.json"))
            .onPost(OSV, "\"2.0.0\"", 200, FakeFetcher.fixture("osv/requests-2.19.0.json"))
            .onPost(OSV, "", 200, "{}");

    final PackageScanner scanner = new PackageScanner(
            List.of(new NpmRegistryClient(http), new PyPiRegistryClient(http)),
            new OsvClient(http),
            List.of(TypoDetector.load(Ecosystem.NPM), TypoDetector.load(Ecosystem.PYPI)),
            PackageScanner.allChecks(), new MutableClock(), Thresholds.defaults());

    PackageVerdict scan(PackageRequest r) {
        return scanner.scan(List.of(r)).packages().get(0);
    }

    static Set<String> rules(PackageVerdict v) {
        return v.findings().stream().map(Finding::ruleId).collect(Collectors.toSet());
    }

    @Test
    void wellKnownPackagesPass() {
        ScanReport report = scanner.scan(List.of(PackageRequest.npm("lodash", null),
                new PackageRequest(Ecosystem.PYPI, "requests", null)));
        assertEquals(Decision.PASS, report.decision(), report.findings().toString());
    }

    @Test
    void npmTyposquatIsBlocked() {
        PackageVerdict v = scan(PackageRequest.npm("lodahs", null));
        assertEquals(Decision.BLOCK, v.decision());
        assertTrue(rules(v).contains(Rules.TYPO), rules(v).toString());
        Finding typo = v.findings().stream().filter(f -> f.ruleId().equals(Rules.TYPO)).findFirst().orElseThrow();
        assertEquals("lodash", typo.evidence().get("similarTo"));
        assertEquals(204729283L, typo.evidence().get("targetWeeklyDownloads"));
    }

    @Test
    void pypiTyposquatIsBlocked() {
        PackageVerdict v = scan(new PackageRequest(Ecosystem.PYPI, "reqeusts", null));
        assertEquals(Decision.BLOCK, v.decision());
        // 녹화본은 12일 전에 만들어져 PKG-NEW(7일) 대상은 아니다. 철자 흉내 + 다운로드 없음 + sdist만 있음
        assertEquals(Set.of(Rules.TYPO, Rules.POP, Rules.SCRIPT), rules(v));
    }

    @Test
    void comboTurnsWarningsIntoBlock() {
        PackageVerdict v = scan(PackageRequest.npm("fresh-helper-utils", null));
        assertEquals(Decision.BLOCK, v.decision());
        assertEquals(Set.of(Rules.NEW, Rules.SCRIPT, Rules.POP, Rules.COMBO), rules(v));
    }

    @Test
    void publisherChangeWarns() {
        PackageVerdict v = scan(PackageRequest.npm("hijacked-lib", null));
        assertEquals(Decision.WARN, v.decision());
        assertEquals(Set.of(Rules.MAINT), rules(v));
    }

    @Test
    void maliciousReportBlocksEvenAPopularPackage() {
        PackageVerdict v = scan(PackageRequest.npm("esbuild", null));
        assertEquals(Decision.BLOCK, v.decision());
        assertTrue(rules(v).contains(Rules.VULN));
    }

    @Test
    void knownVulnerabilitiesOfOldVersionWarn() {
        PackageVerdict v = scan(new PackageRequest(Ecosystem.PYPI, "requests", "==2.0.0"));
        assertEquals(Decision.WARN, v.decision());
        assertTrue(rules(v).contains(Rules.VULN));
        assertTrue(http.postBodies().stream().anyMatch(b -> b.contains("\"PyPI\"") && b.contains("\"2.0.0\"")));
    }

    @Test
    void osvIsNotQueriedForMissingPackageOrVersion() {
        scan(PackageRequest.npm("does-not-exist-anywhere", null));
        scan(PackageRequest.npm("lodash", "9.9.9"));
        assertTrue(http.postBodies().isEmpty());
    }
}
