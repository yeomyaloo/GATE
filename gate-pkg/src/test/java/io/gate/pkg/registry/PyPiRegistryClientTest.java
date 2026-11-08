package io.gate.pkg.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gate.pkg.FakeFetcher;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class PyPiRegistryClientTest {

    static final String REG = PyPiRegistryClient.REGISTRY;
    static final String DL = PyPiRegistryClient.DOWNLOADS;

    final FakeFetcher http = new FakeFetcher()
            .onFixture(REG + "requests/json", "pypi/requests.json")
            .onFixture(REG + "setup-only-tool/json", "pypi/setup-only-tool.json")
            .on(REG + "busy/json", 502, "")
            .on(DL + "requests/recent", 200, "{\"data\":{\"last_week\":360913926}}")
            .on(DL + "flask-sqlalchemy/recent", 200, "{\"data\":{\"last_week\":3138252}}")
            .on(DL + "busy/recent", 429, "");

    final PyPiRegistryClient client = new PyPiRegistryClient(http);

    PackageInfo found(String name, String spec) {
        LookupResult r = client.lookup(new PackageRequest(Ecosystem.PYPI, name, spec));
        return assertInstanceOf(LookupResult.Found.class, r, r.toString()).info();
    }

    @Test
    void latestWithTimesAndWheel() {
        PackageInfo info = found("requests", null);
        assertEquals("2.32.3", info.resolvedVersion());
        assertEquals(Instant.parse("2013-09-24T19:10:00Z"), info.createdAt(), "earliest upload across releases");
        assertEquals(Instant.parse("2024-05-29T15:37:47.027275Z"), info.versionPublishedAt());
        assertFalse(info.sdistOnly());
        assertTrue(info.installScripts().isEmpty());
    }

    @ParameterizedTest
    @CsvSource({"==2.31.0, 2.31.0", "2.31.0, 2.31.0", "=== 2.31.0, 2.31.0", "==2.0, 2.0.0", "v2.32.3, 2.32.3"})
    void exactSpecifiers(String spec, String expected) {
        assertEquals(expected, found("requests", spec).resolvedVersion());
    }

    @Test
    void missingExactVersion() {
        assertTrue(found("requests", "==9.9.9").versionMissing());
    }

    @Test
    void rangeFallsBackToLatestWithNote() {
        PackageInfo info = found("requests", ">=2.0,<3");
        assertEquals("2.32.3", info.resolvedVersion());
        assertNotNull(info.resolutionNote());
    }

    @Test
    void sdistOnlyIsDetected() {
        assertTrue(found("setup-only-tool", null).sdistOnly());
        assertTrue(found("requests", "2.0.0").sdistOnly());
    }

    @Test
    void releaseWithoutFilesIsNotSdistOnly() {
        PackageInfo info = found("requests", "0.0.1");
        assertFalse(info.sdistOnly());
        assertEquals(null, info.versionPublishedAt());
    }

    @Test
    void nameIsNormalizedForUrls() {
        found("Requests", null);
        assertTrue(http.calls().contains(REG + "requests/json"));
        assertEquals(3138252L, ((DownloadsResult.Known) client.weeklyDownloads("Flask_SQLAlchemy")).weekly());
    }

    @ParameterizedTest
    @CsvSource({"Flask_SQLAlchemy, flask-sqlalchemy", "zope.interface, zope-interface", "a__b--c..d, a-b-c-d"})
    void pep503(String in, String out) {
        assertEquals(out, PyPiRegistryClient.normalize(in));
    }

    @Test
    void notFoundAndOutages() {
        assertInstanceOf(LookupResult.NotFound.class, client.lookup(new PackageRequest(Ecosystem.PYPI, "nope", null)));
        assertInstanceOf(LookupResult.Unavailable.class,
                client.lookup(new PackageRequest(Ecosystem.PYPI, "busy", null)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"../x", "a b", "-lead", "trail-", "x/y", "x?y"})
    void invalidNamesRejectedWithoutHttp(String name) {
        assertInstanceOf(LookupResult.Unavailable.class, client.lookup(new PackageRequest(Ecosystem.PYPI, name, null)));
        assertTrue(http.calls().isEmpty());
    }

    @Test
    void downloads() {
        assertEquals(360913926L, ((DownloadsResult.Known) client.weeklyDownloads("requests")).weekly());
        assertEquals(0L, ((DownloadsResult.Known) client.weeklyDownloads("brand-new")).weekly());
        assertInstanceOf(DownloadsResult.Unknown.class, client.weeklyDownloads("busy"));
    }
}
