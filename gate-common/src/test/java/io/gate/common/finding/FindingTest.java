package io.gate.common.finding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FindingTest {

    private static final Location PKG = new Location.Package("npm", "lodahs", "1.0.0");

    private static Finding finding(Decision decision, FindingSource source, Map<String, Object> evidence) {
        return new Finding(UUID.randomUUID(), UUID.randomUUID(), "PKG-TYPO", "typosquat of lodash",
                Severity.HIGH, decision, PKG, evidence, source, Instant.now());
    }

    @Test
    void ruleBackedBlockIsAllowed() {
        Finding f = finding(Decision.BLOCK, FindingSource.RULE, Map.of("similarTo", "lodash"));
        assertEquals(Decision.BLOCK, f.decision());
    }

    @Test
    void aiOnlyFindingCannotBlock() {
        assertThrows(IllegalArgumentException.class,
                () -> finding(Decision.BLOCK, FindingSource.AI, Map.of("reason", "looks suspicious")));
    }

    @Test
    void blockOrWarnRequiresEvidence() {
        assertThrows(IllegalArgumentException.class, () -> finding(Decision.WARN, FindingSource.RULE, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> finding(Decision.BLOCK, FindingSource.RULE, null));
    }

    @Test
    void passMayHaveNoEvidence() {
        assertEquals(Map.of(), finding(Decision.PASS, FindingSource.RULE, null).evidence());
    }

    @Test
    void fingerprintIgnoresIdAndTimeButDependsOnRuleAndLocation() {
        Finding a = finding(Decision.WARN, FindingSource.RULE, Map.of("k", "v"));
        Finding b = finding(Decision.WARN, FindingSource.RULE, Map.of("k", "other"));
        assertEquals(a.fingerprint(), b.fingerprint());
        assertNotEquals(a.fingerprint(), Fingerprints.of("PKG-EXIST", PKG));
        assertNotEquals(a.fingerprint(),
                Fingerprints.of("PKG-TYPO", new Location.Package("npm", "lodahs", "1.0.1")));
    }

    @Test
    void locationsValidateInput() {
        assertThrows(IllegalArgumentException.class, () -> new Location.Package("npm", " ", null));
        assertThrows(IllegalArgumentException.class, () -> new Location.File("src/A.java", 0));
        assertEquals("GET", new Location.Endpoint("get", "/api/orders/{id}").method());
    }
}
