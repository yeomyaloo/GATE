package io.gate.authz.lab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gate.authz.config.AuthzConfig;
import io.gate.authz.http.ApiClient;
import io.gate.authz.http.JdkApiClient;
import io.gate.authz.http.TargetGuard;
import io.gate.authz.probe.AuthzReport;
import io.gate.authz.probe.AuthzScanner;
import io.gate.authz.probe.Rules;
import io.gate.authz.spec.ApiSpec;
import io.gate.common.finding.Location;
import java.net.URI;
import java.time.Clock;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * 3단계 완료 기준: 실습장의 뚫린 버전에서 IDOR를 찾고, 고친 버전에서는 찾지 않는다.
 * 실습장을 테스트 안에서 직접 띄우고(127.0.0.1) 실제 HTTP로 검사한다.
 */
class LabTargetTest {

    static final Map<String, String> ENV = Map.of("LAB_PW", LabServers.DEMO_PASSWORD);

    static AuthzReport scan(String base, boolean allowMutation) throws Exception {
        AuthzConfig config = new AuthzConfig(base, new AuthzConfig.Login("/api/auth/login", null, "accessToken"),
                new AuthzConfig.User("alice", "LAB_PW"), new AuthzConfig.User("bob", "LAB_PW"), allowMutation,
                Set.of(), null, Map.of(), 50);
        JdkApiClient client = new JdkApiClient(new TargetGuard(Set.of()), 50);
        ApiSpec spec = ApiSpec.parse(client.send("GET", URI.create(base + "/openapi.yaml"), null, null).body());
        return new AuthzScanner(spec, config, client, ENV::get, Clock.systemUTC()).scan();
    }

    /** bob 주문 수 (정리가 제대로 됐는지 확인용). */
    static int bobsOrders(String base) throws Exception {
        JdkApiClient client = new JdkApiClient(new TargetGuard(Set.of()), 50);
        ApiClient.Response login = client.send("POST", URI.create(base + "/api/auth/login"), null,
                "{\"username\":\"bob\",\"password\":\"" + LabServers.DEMO_PASSWORD + "\"}");
        String token = JsonMapper.builder().build().readTree(login.body()).path("accessToken").asString();
        return JsonMapper.builder().build().readTree(
                client.send("GET", URI.create(base + "/api/orders"), token, null).body()).size();
    }

    @Test
    void vulnerableVariantReadOnly() throws Exception {
        AuthzReport r = scan(LabServers.vulnerableUrl(), false);
        assertEquals(1, r.byRule(Rules.BOLA_READ).size(), r.coverage().toString());
        assertEquals(new Location.Endpoint("GET", "/api/orders/{id}"), r.byRule(Rules.BOLA_READ).get(0).location());
        assertTrue(r.byRule(Rules.NOAUTH).isEmpty(), "both variants require a token");
        assertTrue(r.byRule(Rules.INCONCLUSIVE).isEmpty(), r.findings().toString());
        assertTrue(r.coverage().stream().filter(c -> !c.operation().startsWith("GET"))
                .allMatch(c -> c.status() == AuthzReport.Status.SKIPPED), "read-only by default");
    }

    @Test
    void vulnerableVariantWithMutationFindsReadWriteAndDelete() throws Exception {
        String base = LabServers.vulnerableUrl();
        int before = bobsOrders(base);
        AuthzReport r = scan(base, true);
        assertEquals(1, r.byRule(Rules.BOLA_READ).size(), r.coverage().toString());
        assertEquals(1, r.byRule(Rules.BOLA_WRITE).size(), r.coverage().toString());
        assertEquals(1, r.byRule(Rules.BOLA_DELETE).size(), r.coverage().toString());
        assertEquals(new Location.Endpoint("PUT", "/api/orders/{id}"), r.byRule(Rules.BOLA_WRITE).get(0).location());
        assertTrue(r.byRule(Rules.INCONCLUSIVE).isEmpty(), r.findings().toString());
        assertEquals(before, bobsOrders(base), "orders created for the test are cleaned up");
    }

    @Test
    void fixedVariantHasNoFindingsEvenWithMutation() throws Exception {
        String base = LabServers.fixedUrl();
        int before = bobsOrders(base);
        for (boolean mutation : new boolean[] {false, true}) {
            AuthzReport r = scan(base, mutation);
            assertTrue(r.findings().isEmpty(), "mutation=" + mutation + ": " + r.findings());
        }
        AuthzReport r = scan(base, true);
        assertEquals(3, r.coverage().stream().filter(c -> c.check().equals("as user A")
                && c.status() == AuthzReport.Status.PASS).count(), r.coverage().toString());
        assertEquals(before, bobsOrders(base));
    }
}
