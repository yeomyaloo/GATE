package io.gate.pkg.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gate.common.finding.Decision;
import io.gate.common.finding.Finding;
import io.gate.pkg.FakeFetcher;
import io.gate.pkg.MutableClock;
import io.gate.pkg.check.Rules;
import io.gate.pkg.check.Thresholds;
import io.gate.pkg.registry.NpmRegistryClient;
import io.gate.pkg.registry.PackageRequest;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** 레지스트리 녹화본으로 스캐너 전체 흐름을 확인한다 (네트워크 없음). */
class PackageScannerTest {

    static final String REG = "https://registry.npmjs.org/";
    static final String DL = "https://api.npmjs.org/downloads/point/last-week/";

    final FakeFetcher http = new FakeFetcher()
            .onFixture(REG + "lodash", "npm/lodash.json")
            .on(DL + "lodash", 200, "{\"downloads\":204729283}")
            .onFixture(REG + "esbuild", "npm/esbuild.json")
            .on(DL + "esbuild", 200, "{\"downloads\":90000000}")
            .onFixture(REG + "fresh-helper-utils", "npm/fresh-helper-utils.json")
            .on(DL + "fresh-helper-utils", 200, "{\"downloads\":3}")
            .onFixture(REG + "hotfix-lib", "npm/hotfix-lib.json")
            .on(DL + "hotfix-lib", 200, "{\"downloads\":250000}")
            .failing(REG + "down-pkg")
            .on(DL + "down-pkg", 200, "{\"downloads\":1000}");

    final PackageScanner scanner = new PackageScanner(List.of(new NpmRegistryClient(http)),
            PackageScanner.week1Checks(), new MutableClock(), Thresholds.defaults());

    static Set<String> rules(PackageVerdict v) {
        return v.findings().stream().map(Finding::ruleId).collect(Collectors.toSet());
    }

    @Test
    void wellKnownPackagePasses() {
        PackageVerdict v = scanner.scan(List.of(PackageRequest.npm("lodash", null))).packages().get(0);
        assertEquals(Decision.PASS, v.decision());
        assertTrue(v.findings().isEmpty(), v.findings().toString());
    }

    @Test
    void hallucinatedPackageIsBlocked() {
        PackageVerdict v = scanner.scan(List.of(PackageRequest.npm("express-jwt-auth-helper-pro", null)))
                .packages().get(0);
        assertEquals(Decision.BLOCK, v.decision());
        assertEquals(Set.of(Rules.EXIST), rules(v));
    }

    @Test
    void missingVersionIsBlocked() {
        PackageVerdict v = scanner.scan(List.of(PackageRequest.npm("lodash", "5.0.0"))).packages().get(0);
        assertEquals(Decision.BLOCK, v.decision());
        assertEquals(Set.of(Rules.EXIST), rules(v));
    }

    @Test
    void freshPackageWithInstallScriptAndNoDownloadsIsBlockedByCombo() {
        PackageVerdict v = scanner.scan(List.of(PackageRequest.npm("fresh-helper-utils", null))).packages().get(0);
        // 각 신호는 WARN이지만, 2주차부터 PKG-COMBO가 겹친 신호를 BLOCK으로 올린다 (1주차 때는 WARN이었다)
        assertEquals(Decision.BLOCK, v.decision());
        assertEquals(Set.of(Rules.NEW, Rules.SCRIPT, Rules.POP, Rules.COMBO), rules(v));
    }

    @Test
    void popularPackageWithInstallScriptOnlyWarnsOnScript() {
        PackageVerdict v = scanner.scan(List.of(PackageRequest.npm("esbuild", null))).packages().get(0);
        assertEquals(Set.of(Rules.SCRIPT), rules(v));
    }

    @Test
    void freshlyPublishedVersionWarns() {
        PackageVerdict v = scanner.scan(List.of(PackageRequest.npm("hotfix-lib", null))).packages().get(0);
        assertEquals(Set.of(Rules.NEW), rules(v));
    }

    @Test
    void registryOutageIsUncheckedNotPass() {
        PackageVerdict v = scanner.scan(List.of(PackageRequest.npm("down-pkg", null))).packages().get(0);
        assertEquals(Decision.WARN, v.decision());
        assertEquals(Set.of(Rules.UNCHECKED), rules(v));
        assertTrue(v.findings().get(0).evidence().containsKey("url"));
    }

    @Test
    void reportKeepsOrderSharesRunIdAndTakesStrongestDecision() {
        ScanReport report = scanner.scan(List.of(
                PackageRequest.npm("lodash", null),
                PackageRequest.npm("express-jwt-auth-helper-pro", null),
                PackageRequest.npm("esbuild", null)));
        assertEquals(List.of("lodash", "express-jwt-auth-helper-pro", "esbuild"),
                report.packages().stream().map(p -> p.request().name()).toList());
        assertEquals(Decision.BLOCK, report.decision());
        assertTrue(report.findings().stream().allMatch(f -> f.runId().equals(report.runId())));
    }

    @Test
    void emptyRequestPasses() {
        assertEquals(Decision.PASS, scanner.scan(List.of()).decision());
    }
}
