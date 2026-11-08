package io.gate.pkg.cache;

import io.gate.pkg.http.HttpFetcher;
import io.gate.pkg.http.HttpResult;
import java.io.IOException;
import java.net.URI;
import java.util.Optional;

/** {@link HttpFetcher} 앞에 파일 캐시를 둔다. 보관 시간은 {@link CachePolicy}가 정한다. */
public final class CachingFetcher implements HttpFetcher {

    private final HttpFetcher delegate;
    private final FileCache cache;

    public CachingFetcher(HttpFetcher delegate, FileCache cache) {
        this.delegate = delegate;
        this.cache = cache;
    }

    @Override
    public HttpResult get(URI uri) throws IOException {
        String key = FileCache.key("GET", uri.toString(), null);
        Optional<HttpResult> cached = cache.get(key);
        if (cached.isPresent()) {
            return cached.get();
        }
        return store(key, uri, delegate.get(uri));
    }

    @Override
    public HttpResult postJson(URI uri, String json) throws IOException {
        String key = FileCache.key("POST", uri.toString(), json);
        Optional<HttpResult> cached = cache.get(key);
        if (cached.isPresent()) {
            return cached.get();
        }
        return store(key, uri, delegate.postJson(uri, json));
    }

    private HttpResult store(String key, URI uri, HttpResult result) {
        CachePolicy.ttlFor(uri, result).ifPresent(ttl -> cache.put(key, result, ttl));
        return result;
    }
}
