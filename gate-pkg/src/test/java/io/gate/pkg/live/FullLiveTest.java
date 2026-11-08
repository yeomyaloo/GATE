package io.gate.pkg.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gate.common.finding.Decision;
import io.gate.common.finding.Finding;
import io.gate.pkg.check.Rules;
import io.gate.pkg.check.Thresholds;
import io.gate.pkg.check.TypoDetector;
import io.gate.pkg.http.JdkHttpFetcher;
import io.gate.pkg.registry.Ecosystem;
import io.gate.pkg.registry.NpmRegistryClient;
import io.gate.pkg.registry.OsvClient;
import io.gate.pkg.registry.PackageRequest;
import io.gate.pkg.registry.PyPiRegistryClient;
import io.gate.pkg.scan.PackageScanner;
import io.gate.pkg.scan.PackageVerdict;
import io.gate.pkg.scan.ScanReport;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 전체 구성(npm + PyPI + OSV + 철자 흉내)으로 실제 API를 호출한다. {@code ./gradlew :gate-pkg:liveTest}.
 * 가장 중요한 확인은 "널리 쓰이는 정상 패키지를 막지 않는가"(오탐)이다.
 */
@Tag("live")
class FullLiveTest {

    final JdkHttpFetcher http = new JdkHttpFetcher(Duration.ofSeconds(10));
    final PackageScanner scanner = new PackageScanner(
            List.of(new NpmRegistryClient(http), new PyPiRegistryClient(http)),
            new OsvClient(http),
            List.of(TypoDetector.load(Ecosystem.NPM), TypoDetector.load(Ecosystem.PYPI)),
            PackageScanner.allChecks(), Clock.systemUTC(), Thresholds.defaults());

    static PackageRequest pypi(String name, String spec) {
        return new PackageRequest(Ecosystem.PYPI, name, spec);
    }

    @Test
    void popularPackagesAreNeverBlocked() {
        ScanReport report = scanner.scan(List.of(
                PackageRequest.npm("lodash", null), PackageRequest.npm("express", null),
                PackageRequest.npm("react", null), PackageRequest.npm("@types/node", null),
                PackageRequest.npm("axios", null), PackageRequest.npm("typescript", null),
                pypi("requests", null), pypi("numpy", null), pypi("django", null), pypi("Flask_SQLAlchemy", null)));
        for (PackageVerdict v : report.packages()) {
            System.out.println(v.request() + " -> " + v.decision() + " "
                    + v.findings().stream().map(Finding::ruleId).toList());
            assertNotEquals(Decision.BLOCK, v.decision(), v.toString());
            assertTrue(v.findings().stream().noneMatch(f -> f.ruleId().equals(Rules.TYPO)), v.toString());
        }
    }

    @Test
    void hallucinatedNamesAreBlockedOnBothRegistries() {
        String name = "gate-live-" + UUID.randomUUID().toString().substring(0, 8);
        for (PackageVerdict v : scanner.scan(List.of(PackageRequest.npm(name, null), pypi(name, null))).packages()) {
            assertEquals(Decision.BLOCK, v.decision(), v.toString());
            assertEquals(Rules.EXIST, v.findings().get(0).ruleId());
        }
    }

    @Test
    void oldVulnerableVersionGetsOsvFinding() {
        PackageVerdict v = scanner.scan(List.of(pypi("requests", "==2.19.0"))).packages().get(0);
        System.out.println(v);
        assertTrue(v.findings().stream().anyMatch(f -> f.ruleId().equals(Rules.VULN)), v.toString());
    }

    @Test
    void knownTyposquatNamesAreNotPassed() {
        // 실제 레지스트리에 이 이름이 있든(철자 흉내로 차단) 없든(존재하지 않음으로 차단) 통과하면 안 된다
        for (PackageVerdict v : scanner.scan(List.of(PackageRequest.npm("lodahs", null), pypi("reqeusts", null)))
                .packages()) {
            System.out.println(v.request() + " -> " + v.decision() + " "
                    + v.findings().stream().map(Finding::ruleId).toList());
            assertNotEquals(Decision.PASS, v.decision(), v.toString());
        }
    }
}
