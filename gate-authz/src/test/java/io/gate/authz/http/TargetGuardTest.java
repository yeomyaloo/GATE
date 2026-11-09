package io.gate.authz.http;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TargetGuardTest {

    final TargetGuard guard = new TargetGuard(Set.of("Staging.Example.Test"));

    @ParameterizedTest
    @ValueSource(strings = {"http://127.0.0.1:8081/api", "http://localhost/x", "http://[::1]:8082/",
        "https://staging.example.test/api"})
    void allowed(String url) {
        assertDoesNotThrow(() -> guard.check(URI.create(url)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://192.168.0.10/api", "https://example.com/", "http://10.0.0.1/",
        "http://staging.example.test.evil.invalid/", "http://evil-staging.example.test/", "ftp://127.0.0.1/",
        "file:///etc/passwd", "http://0.0.0.0/"})
    void refused(String url) {
        assertThrows(TargetGuard.TargetNotAllowedException.class, () -> guard.check(URI.create(url)));
    }
}
