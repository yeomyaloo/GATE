package io.gate.authz.lab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gate.authz.config.AuthzConfig;
import io.gate.authz.http.JdkApiClient;
import io.gate.authz.http.TargetGuard;
import io.gate.authz.probe.AuthzReport;
import io.gate.authz.probe.AuthzScanner;
import io.gate.authz.probe.Rules;
import io.gate.authz.spec.ApiSpec;
import io.gate.common.finding.Location;
import io.gate.lab.LabApplication;
import java.net.URI;
import java.time.Clock;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 완료 기준 확인: 실습장(gate-lab)을 테스트 안에서 vulnerable·fixed로 띄우고(127.0.0.1, 임의 포트) 실제 HTTP로 검사한다.
 * 데모 비밀번호는 이 테스트에서만 쓰는 가짜 값이며, gate-authz에는 환경 변수 조회 함수로만 전달한다.
 */
class LabTargetTest {

    static final String DEMO_PASSWORD = "test-only-authz-demo-password";
    static ConfigurableApplicationContext vulnerable;
    static ConfigurableApplicationContext fixed;

    static ConfigurableApplicationContext start(String variant) {
        return new SpringApplicationBuilder(LabApplication.class).profiles(variant)
                .run("--gate.lab.ack=local-only", "--server.port=0", "--server.address=127.0.0.1",
                        "--gate.lab.demo-password=" + DEMO_PASSWORD, "--spring.main.banner-mode=off");
    }

    @BeforeAll
    static void startLab() {
        vulnerable = start("vulnerable");
        fixed = start("fixed");
    }

    @AfterAll
    static void stopLab() {
        if (vulnerable != null) {
            vulnerable.close();
        }
        if (fixed != null) {
            fixed.close();
        }
    }

    static AuthzReport scan(ConfigurableApplicationContext lab) throws Exception {
        String base = "http://127.0.0.1:" + lab.getEnvironment().getProperty("local.server.port");
        AuthzConfig config = new AuthzConfig(base, new AuthzConfig.Login("/api/auth/login", null, "accessToken"),
                new AuthzConfig.User("alice", "LAB_PW"), new AuthzConfig.User("bob", "LAB_PW"), false, Set.of(),
                null, Map.of(), 50);
        JdkApiClient client = new JdkApiClient(new TargetGuard(Set.of()), 50);
        ApiSpec spec = ApiSpec.parse(client.send("GET", URI.create(base + "/openapi.yaml"), null, null).body());
        return new AuthzScanner(spec, config, client, Map.of("LAB_PW", DEMO_PASSWORD)::get, Clock.systemUTC()).scan();
    }

    @Test
    void vulnerableVariantLeaksOrdersToOtherUsers() throws Exception {
        AuthzReport r = scan(vulnerable);
        assertEquals(1, r.byRule(Rules.BOLA_READ).size(), r.coverage().toString());
        assertEquals(new Location.Endpoint("GET", "/api/orders/{id}"), r.byRule(Rules.BOLA_READ).get(0).location());
        assertTrue(r.byRule(Rules.NOAUTH).isEmpty(), "both variants require a token");
        assertTrue(r.byRule(Rules.INCONCLUSIVE).isEmpty(), r.findings().toString());
    }

    @Test
    void fixedVariantHasNoFindings() throws Exception {
        AuthzReport r = scan(fixed);
        assertTrue(r.findings().isEmpty(), r.findings().toString());
        assertTrue(r.coverage().stream().anyMatch(c -> c.operation().equals("GET /api/orders/{id}")
                && c.check().equals("as user A") && c.status() == AuthzReport.Status.PASS), r.coverage().toString());
    }

    @Test
    void readOnlyByDefault() throws Exception {
        AuthzReport r = scan(vulnerable);
        assertTrue(r.coverage().stream().filter(c -> !c.operation().startsWith("GET"))
                .allMatch(c -> c.status() == AuthzReport.Status.SKIPPED), r.coverage().toString());
    }
}
