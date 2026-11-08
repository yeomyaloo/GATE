package io.gate.pkg.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gate.pkg.FakeFetcher;
import io.gate.pkg.MutableClock;
import io.gate.pkg.http.HttpResult;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CachingFetcherTest {

    static final URI META = URI.create("https://registry.npmjs.org/lodash");
    static final URI MISSING = URI.create("https://registry.npmjs.org/no-such-pkg");
    static final URI DOWNLOADS = URI.create("https://api.npmjs.org/downloads/point/last-week/lodash");

    @TempDir
    Path dir;

    final MutableClock clock = new MutableClock();
    final FakeFetcher http = new FakeFetcher()
            .on(META.toString(), 200, "{\"name\":\"lodash\"}")
            .on(DOWNLOADS.toString(), 200, "{\"downloads\":5}")
            .on("https://registry.npmjs.org/flaky", 503, "busy");

    CachingFetcher fetcher() {
        return new CachingFetcher(http, new FileCache(dir, clock));
    }

    @Test
    void secondCallIsServedFromCacheUntilTtlExpires() throws IOException {
        CachingFetcher f = fetcher();
        assertEquals("{\"name\":\"lodash\"}", f.get(META).body());
        assertEquals("{\"name\":\"lodash\"}", f.get(META).body());
        assertEquals(1, http.calls().size());

        clock.advance(CachePolicy.METADATA_TTL);
        f.get(META);
        assertEquals(2, http.calls().size(), "expired entry must be refetched");
    }

    @Test
    void notFoundIsCachedOnlyBriefly() throws IOException {
        CachingFetcher f = fetcher();
        assertEquals(404, f.get(MISSING).status());
        clock.advance(Duration.ofMinutes(9));
        f.get(MISSING);
        assertEquals(1, http.calls().size(), "404 is cached for 10 minutes");

        clock.advance(Duration.ofMinutes(1));
        f.get(MISSING);
        assertEquals(2, http.calls().size(), "after 10 minutes a squatter may have registered the name");
    }

    @Test
    void downloadsUseLongerTtl() throws IOException {
        CachingFetcher f = fetcher();
        f.get(DOWNLOADS);
        clock.advance(Duration.ofHours(23));
        f.get(DOWNLOADS);
        assertEquals(1, http.calls().size());
    }

    @Test
    void transientFailuresAreNotCached() throws IOException {
        CachingFetcher f = fetcher();
        URI flaky = URI.create("https://registry.npmjs.org/flaky");
        assertEquals(503, f.get(flaky).status());
        f.get(flaky);
        assertEquals(2, http.calls().size());
    }

    @Test
    void postCacheKeyIncludesBody() throws IOException {
        URI osv = URI.create("https://api.osv.dev/v1/query");
        http.on(osv.toString(), 200, "{}");
        CachingFetcher f = fetcher();
        f.postJson(osv, "{\"a\":1}");
        f.postJson(osv, "{\"a\":1}");
        f.postJson(osv, "{\"a\":2}");
        assertEquals(2, http.calls().size());
    }

    @Test
    void corruptCacheFileIsIgnored() throws IOException {
        CachingFetcher f = fetcher();
        f.get(META);
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : files.toList()) {
                Files.writeString(p, "{not json");
            }
        }
        HttpResult again = f.get(META);
        assertEquals(200, again.status());
        assertEquals(2, http.calls().size());
    }

    @Test
    void cacheFilesAreNamedByHash() throws IOException {
        fetcher().get(META);
        try (Stream<Path> files = Files.list(dir)) {
            assertTrue(files.allMatch(p -> p.getFileName().toString().matches("[0-9a-f]{64}\\.json")));
        }
    }
}
