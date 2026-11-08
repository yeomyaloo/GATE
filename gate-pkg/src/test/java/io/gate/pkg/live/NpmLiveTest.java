package io.gate.pkg.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.gate.common.finding.Decision;
import io.gate.common.finding.Finding;
import io.gate.pkg.check.Rules;
import io.gate.pkg.check.Thresholds;
import io.gate.pkg.http.JdkHttpFetcher;
import io.gate.pkg.registry.NpmRegistryClient;
import io.gate.pkg.registry.PackageRequest;
import io.gate.pkg.scan.PackageScanner;
import io.gate.pkg.scan.PackageVerdict;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 실제 npm 레지스트리를 호출한다. 기본 빌드에서는 돌지 않는다: {@code ./gradlew :gate-pkg:liveTest}.
 * 녹화본과 실제 응답 형식이 어긋나지 않았는지 확인하는 용도다. 캐시는 쓰지 않는다.
 */
@Tag("live")
class NpmLiveTest {

    final PackageScanner scanner = new PackageScanner(
            List.of(new NpmRegistryClient(new JdkHttpFetcher(Duration.ofSeconds(10)))),
            PackageScanner.week1Checks(), Clock.systemUTC(), Thresholds.defaults());

    @Test
    void realWellKnownPackagesAreNotBlocked() {
        for (PackageVerdict v : scanner.scan(List.of(
                PackageRequest.npm("lodash", null),
                PackageRequest.npm("@types/node", null),
                PackageRequest.npm("express", "4.21.2"))).packages()) {
            assertFalse(v.findings().stream().map(Finding::ruleId).anyMatch(Rules.EXIST::equals), v.toString());
            assertFalse(v.findings().stream().map(Finding::ruleId).anyMatch(Rules.UNCHECKED::equals), v.toString());
        }
    }

    @Test
    void randomNameIsBlockedAsNonexistent() {
        String name = "gate-live-test-" + UUID.randomUUID();
        PackageVerdict v = scanner.scan(List.of(PackageRequest.npm(name, null))).packages().get(0);
        assertEquals(Decision.BLOCK, v.decision());
        assertEquals(Rules.EXIST, v.findings().get(0).ruleId());
    }

    @Test
    void realInstallScriptIsDetected() {
        PackageVerdict v = scanner.scan(List.of(PackageRequest.npm("esbuild", null))).packages().get(0);
        assertFalse(v.findings().stream().noneMatch(f -> f.ruleId().equals(Rules.SCRIPT)), v.toString());
    }
}
