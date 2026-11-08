package io.gate.pkg.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gate.pkg.FakeFetcher;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class NpmRegistryClientTest {

    static final String REG = NpmRegistryClient.REGISTRY;
    static final String DL = NpmRegistryClient.DOWNLOADS;

    final FakeFetcher http = new FakeFetcher()
            .onFixture(REG + "lodash", "npm/lodash.json")
            .onFixture(REG + "esbuild", "npm/esbuild.json")
            .onFixture(REG + "native-addon", "npm/native-addon.json")
            .onFixture(REG + "@types%2Fnode", "npm/types-node.json")
            .on(REG + "broken", 200, "<html>not json")
            .on(REG + "busy", 503, "")
            .failing(REG + "slow")
            .on(DL + "lodash", 200, "{\"downloads\":204729283,\"package\":\"lodash\"}")
            .on(DL + "busy", 500, "")
            .on(DL + "weird", 200, "{\"error\":\"oops\"}");

    final NpmRegistryClient client = new NpmRegistryClient(http);

    PackageInfo found(String name, String spec) {
        LookupResult result = client.lookup(PackageRequest.npm(name, spec));
        return assertInstanceOf(LookupResult.Found.class, result, result.toString()).info();
    }

    @Test
    void noVersionMeansLatest() {
        PackageInfo info = found("lodash", null);
        assertEquals("4.17.21", info.resolvedVersion());
        assertEquals("4.17.21", info.latestVersion());
        assertEquals(Instant.parse("2012-04-23T16:37:11.912Z"), info.createdAt());
        assertEquals(Instant.parse("2021-02-20T15:42:16.891Z"), info.versionPublishedAt());
        assertTrue(info.installScripts().isEmpty(), "'test' is not an install lifecycle script");
        assertNull(info.resolutionNote());
    }

    @Test
    void exactVersionAndVPrefix() {
        assertEquals("4.17.20", found("lodash", "4.17.20").resolvedVersion());
        assertEquals("4.17.20", found("lodash", "v4.17.20").resolvedVersion());
    }

    @Test
    void distTagIsResolved() {
        assertEquals("0.29.0-beta.1", found("esbuild", "next").resolvedVersion());
    }

    @Test
    void missingExactVersionIsReportedAsMissing() {
        PackageInfo info = found("lodash", "9.9.9");
        assertTrue(info.versionMissing());
        assertEquals("9.9.9", info.locationVersion());
        assertNull(info.versionPublishedAt());
    }

    @Test
    void rangeFallsBackToLatestWithNote() {
        PackageInfo info = found("lodash", "^4.17.0");
        assertEquals("4.17.21", info.resolvedVersion());
        assertNotNull(info.resolutionNote());
    }

    @Test
    void installScriptsAreExtracted() {
        assertEquals(Map.of("postinstall", "node install.js"), found("esbuild", null).installScripts());
    }

    @Test
    void bindingGypImpliesInstallScript() {
        assertTrue(found("native-addon", null).installScripts().get("install").contains("node-gyp"));
    }

    @Test
    void scopedNameIsEncoded() {
        assertEquals("@types/node", found("@types/node", null).name());
        assertTrue(http.calls().contains(REG + "@types%2Fnode"));
    }

    @Test
    void notFound() {
        assertInstanceOf(LookupResult.NotFound.class, client.lookup(PackageRequest.npm("no-such-pkg", null)));
    }

    @Test
    void serverErrorNetworkErrorAndBadJsonAreUnavailable() {
        assertInstanceOf(LookupResult.Unavailable.class, client.lookup(PackageRequest.npm("busy", null)));
        assertInstanceOf(LookupResult.Unavailable.class, client.lookup(PackageRequest.npm("slow", null)));
        assertInstanceOf(LookupResult.Unavailable.class, client.lookup(PackageRequest.npm("broken", null)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"../etc/passwd", "a b", "foo?x=1", "@scope", "@/x", "a/b", "%2e%2e"})
    void invalidNamesAreRejectedWithoutHttpCall(String name) {
        assertInstanceOf(LookupResult.Unavailable.class, client.lookup(PackageRequest.npm(name, null)));
        assertTrue(http.calls().isEmpty());
        assertFalse(NpmRegistryClient.isValidName(name));
    }

    @Test
    void downloads() {
        assertEquals(204729283L,
                assertInstanceOf(DownloadsResult.Known.class, client.weeklyDownloads("lodash")).weekly());
        assertEquals(0L,
                assertInstanceOf(DownloadsResult.Known.class, client.weeklyDownloads("brand-new")).weekly(),
                "npm answers 404 for packages without download stats yet");
        assertInstanceOf(DownloadsResult.Unknown.class, client.weeklyDownloads("busy"));
        assertInstanceOf(DownloadsResult.Unknown.class, client.weeklyDownloads("weird"));
    }
}
