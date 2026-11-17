package io.gate.common.license;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.LocalDate;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 테스트 전용 키 쌍으로 서명·검증을 확인한다 (실제 발급용 개인 키는 저장소에 없다). */
class LicenseTest {

    static final KeyPair TEST_KEYS = generate();
    static final LocalDate TODAY = LocalDate.of(2026, 11, 17);

    static KeyPair generate() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static Map<String, String> fields(String expires) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("id", "LIC-0001");
        f.put("licensee", "Example Corp \"Korea\"");
        f.put("seats", "25");
        f.put("issuedAt", "2026-11-17T00:00:00Z");
        f.put("expiresAt", expires);
        f.put("modules", "pkg,review");
        return f;
    }

    static String key(String expires) throws Exception {
        return License.sign(fields(expires), TEST_KEYS.getPrivate());
    }

    @Test
    void validKeyIsAccepted() throws Exception {
        License.Info i = License.verify(key("2027-11-16"), TEST_KEYS.getPublic(), TODAY);
        assertEquals(License.Status.VALID, i.status());
        assertEquals("Example Corp \"Korea\"", i.licensee());
        assertEquals(25, i.seats());
        assertEquals(List.of("pkg", "review"), i.modules());
        assertTrue(i.notice().contains("LIC-0001") && i.notice().contains("2027-11-16"));
    }

    @Test
    void expiryDayIsStillValidAndTheNextDayIsExpired() throws Exception {
        assertEquals(License.Status.VALID, License.verify(key("2026-11-17"), TEST_KEYS.getPublic(), TODAY).status());
        License.Info expired = License.verify(key("2026-11-16"), TEST_KEYS.getPublic(), TODAY);
        assertEquals(License.Status.EXPIRED, expired.status());
        assertTrue(expired.notice().contains(License.COMMERCIAL_URL));
    }

    @Test
    void tamperedContentIsRejected() throws Exception {
        String k = key("2027-11-16");
        String payload = new String(Base64.getUrlDecoder().decode(k.split("\\.")[0]));
        String forged = Base64.getUrlEncoder().withoutPadding().encodeToString(
                payload.replace("\"25\"", "\"9999\"").getBytes()) + "." + k.split("\\.")[1];
        assertEquals(License.Status.INVALID, License.verify(forged, TEST_KEYS.getPublic(), TODAY).status());
    }

    @Test
    void keySignedByAnotherKeyIsRejected() throws Exception {
        // 실제 공개 키로는 테스트 키가 서명한 키를 받아들이지 않는다
        assertEquals(License.Status.INVALID, License.verify(key("2027-11-16"), License.publicKey(), TODAY).status());
        assertEquals(License.Status.INVALID, License.verify(key("2027-11-16"), generate().getPublic(), TODAY)
                .status());
    }

    @Test
    void malformedKeysAreInvalidNotErrors() {
        for (String bad : new String[] {"x", "a.b.c", "!!!.???", "e30.AAAA", "x".repeat(5000)}) {
            assertEquals(License.Status.INVALID, License.verify(bad, TEST_KEYS.getPublic(), TODAY).status(), bad);
        }
    }

    @Test
    void currentReadsEnvironmentThenFileAndDefaultsToNone(@TempDir Path home) throws Exception {
        Map<String, String> env = new java.util.HashMap<>();
        assertEquals(License.Status.NONE, License.current(env::get, home, TODAY).status());
        assertTrue(License.current(env::get, home, TODAY).notice().contains("noncommercial"));

        Files.createDirectories(home.resolve(".gate"));
        Files.writeString(home.resolve(".gate/license.key"), "not-a-key\n");
        assertEquals(License.Status.INVALID, License.current(env::get, home, TODAY).status(), "file is read");

        env.put(License.ENV, "also-not-a-key");
        assertEquals(License.Status.INVALID, License.current(env::get, home, TODAY).status(), "env wins");
    }

    @Test
    void productionPublicKeyLoads() {
        assertEquals("EdDSA", License.publicKey().getAlgorithm());
    }
}
