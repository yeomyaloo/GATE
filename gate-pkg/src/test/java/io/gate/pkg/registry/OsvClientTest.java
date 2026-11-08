package io.gate.pkg.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gate.common.finding.Severity;
import io.gate.pkg.FakeFetcher;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class OsvClientTest {

    static final String URL = OsvClient.URL;

    final FakeFetcher http = new FakeFetcher()
            .onPost(URL, "\"requests\"", 200, FakeFetcher.fixture("osv/requests-2.19.0.json"))
            .onPost(URL, "discord-selfbot", 200, FakeFetcher.fixture("osv/malicious.json"))
            .onPost(URL, "rce-lib", 200, FakeFetcher.fixture("osv/critical.json"))
            .onPost(URL, "osv-down", 503, "")
            .onPost(URL, "lodash", 200, "{}");

    final OsvClient client = new OsvClient(http);

    List<OsvClient.Vulnerability> known(Ecosystem e, String name, String version) {
        return assertInstanceOf(OsvClient.Result.Known.class, client.query(e, name, version)).vulnerabilities();
    }

    @Test
    void requestUsesOsvEcosystemNameAndVersion() {
        client.query(Ecosystem.PYPI, "requests", "2.19.0");
        String body = http.postBodies().get(0);
        assertTrue(body.contains("\"ecosystem\":\"PyPI\""), body);
        assertTrue(body.contains("\"version\":\"2.19.0\""), body);
    }

    @Test
    void aliasesAreMergedAndBestSeverityKept() {
        List<OsvClient.Vulnerability> vulns = known(Ecosystem.PYPI, "requests", "2.19.0");
        assertEquals(2, vulns.size(), "GHSA and PYSEC copies of the same CVE are one vulnerability");
        Map<String, Severity> byId = vulns.stream()
                .collect(Collectors.toMap(OsvClient.Vulnerability::id, OsvClient.Vulnerability::severity));
        assertEquals(Map.of("GHSA-x84v-xcm2-53pg", Severity.HIGH, "GHSA-j8r2-6x86-q33q", Severity.MEDIUM), byId);
        assertTrue(vulns.get(0).aliases().contains("PYSEC-2018-28"));
        assertFalse(vulns.get(0).malicious());
    }

    @Test
    void maliciousReportIsFlagged() {
        OsvClient.Vulnerability v = known(Ecosystem.NPM, "discord-selfbot-v14", "1.0.0").get(0);
        assertEquals("MAL-2022-2490", v.id());
        assertTrue(v.malicious());
    }

    @Test
    void criticalSeverity() {
        assertEquals(Severity.CRITICAL, known(Ecosystem.NPM, "rce-lib", "1.0.0").get(0).severity());
    }

    @Test
    void noVulnerabilities() {
        assertTrue(known(Ecosystem.NPM, "lodash", "4.17.21").isEmpty());
    }

    @Test
    void outageIsUnknown() {
        assertInstanceOf(OsvClient.Result.Unknown.class, client.query(Ecosystem.NPM, "osv-down", "1.0.0"));
        http.failing(URL);
        assertInstanceOf(OsvClient.Result.Unknown.class, client.query(Ecosystem.NPM, "lodash", "1.0.0"));
    }
}
