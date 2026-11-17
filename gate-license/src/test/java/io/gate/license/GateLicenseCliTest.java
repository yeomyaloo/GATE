package io.gate.license;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.time.LocalDate;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GateLicenseCliTest {

    @TempDir
    Path home;

    record Run(int code, String out, String err) {
    }

    Run run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = new GateLicenseCli(home, LocalDate.of(2026, 11, 17)).run(args, Map.<String, String>of()::get,
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Run(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void noKeyMeansNoncommercialNotice() {
        Run r = run("status");
        assertEquals(GateLicenseCli.OK, r.code());
        assertTrue(r.out().startsWith("NONE") && r.out().contains("noncommercial"), r.out());
    }

    @Test
    void verifyRejectsGarbage() {
        assertEquals(GateLicenseCli.INVALID, run("verify", "abc.def").code());
    }

    @Test
    void issuingWithAPrivateKeyThatIsNotGatesIsRefused() throws Exception {
        // 실제 발급용 개인 키는 저장소에 없다. 다른 키로는 GATE 공개 키와 맞지 않아 발급을 거부해야 한다
        Path wrong = home.resolve("wrong.key");
        Files.writeString(wrong, Base64.getEncoder().encodeToString(
                KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPrivate().getEncoded()));
        Run r = run("issue", "--private-key", wrong.toString(), "--licensee", "Example Corp", "--seats", "5",
                "--expires", "2027-11-16");
        assertEquals(GateLicenseCli.ERROR, r.code());
        assertEquals("", r.out(), "no key printed");
        assertTrue(r.err().contains("does not match"), r.err());
    }

    @Test
    void issuingNeedsAllFieldsAndAFutureDate() throws Exception {
        assertEquals(GateLicenseCli.ERROR, run("issue", "--licensee", "x").code());
        Path wrong = home.resolve("k");
        Files.writeString(wrong, "AAAA");
        assertEquals(GateLicenseCli.ERROR, run("issue", "--private-key", wrong.toString(), "--licensee", "x",
                "--seats", "1", "--expires", "2026-01-01").code());
    }

    @Test
    void usageIsOffUntilTurnedOnAndCanBeTurnedOff() {
        assertTrue(run("usage", "status").out().contains("OFF"));
        assertTrue(run("usage", "preview").out().contains("Never sent"));
        assertEquals(GateLicenseCli.ERROR, run("usage", "on").code(), "needs an explicit --url");
        Run on = run("usage", "on", "--url", "https://usage.example.invalid/v1");
        assertEquals(GateLicenseCli.OK, on.code());
        assertTrue(run("usage", "status").out().contains("ON to https://usage.example.invalid/v1"));
        run("usage", "off");
        assertTrue(run("usage", "status").out().contains("OFF"));
        assertFalse(run("usage", "status").out().contains("install id"));
    }
}
