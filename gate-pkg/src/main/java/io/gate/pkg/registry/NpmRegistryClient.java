package io.gate.pkg.registry;

import io.gate.pkg.http.HttpFetcher;
import io.gate.pkg.http.HttpResult;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * npm 레지스트리 조회.
 * <ul>
 *   <li>메타데이터: {@code GET registry.npmjs.org/{name}} 전체 문서. 축약 문서에는 게시 시각과 scripts가 없다</li>
 *   <li>다운로드: {@code GET api.npmjs.org/downloads/point/last-week/{name}}</li>
 * </ul>
 */
public final class NpmRegistryClient implements RegistryClient {

    static final String REGISTRY = "https://registry.npmjs.org/";
    static final String DOWNLOADS = "https://api.npmjs.org/downloads/point/last-week/";

    /** 설치 시 npm이 자동으로 실행하는 생명주기 스크립트. */
    static final List<String> INSTALL_LIFECYCLE = List.of("preinstall", "install", "postinstall");

    // npm 이름 규칙: 선택적 @scope/ + 이름. URL에 넣기 전에 검증해서 경로 조작을 막는다.
    // 대문자는 예전 패키지에 남아 있어 허용한다.
    private static final Pattern NAME = Pattern.compile(
            "^(@[a-z0-9~-][a-z0-9._~-]*/)?[a-z0-9~-][a-z0-9._~-]*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern EXACT_VERSION = Pattern.compile(
            "^v?\\d+\\.\\d+\\.\\d+(-[0-9A-Za-z.-]+)?(\\+[0-9A-Za-z.-]+)?$");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpFetcher http;

    public NpmRegistryClient(HttpFetcher http) {
        this.http = http;
    }

    @Override
    public Ecosystem ecosystem() {
        return Ecosystem.NPM;
    }

    public static boolean isValidName(String name) {
        return name.length() <= 214 && NAME.matcher(name).matches();
    }

    static String encodeName(String name) {
        return name.replace("/", "%2F");
    }

    @Override
    public LookupResult lookup(PackageRequest request) {
        String url = REGISTRY + (isValidName(request.name()) ? encodeName(request.name()) : "");
        if (!isValidName(request.name())) {
            return new LookupResult.Unavailable(url, "invalid npm package name: " + request.name());
        }
        HttpResult result;
        try {
            result = http.get(URI.create(url));
        } catch (IOException e) {
            return new LookupResult.Unavailable(url, "request failed: " + e.getMessage());
        }
        if (result.notFound()) {
            return new LookupResult.NotFound(url);
        }
        if (!result.ok()) {
            return new LookupResult.Unavailable(url, "registry returned HTTP " + result.status());
        }
        try {
            return new LookupResult.Found(parse(request, url, JSON.readTree(result.body())));
        } catch (JacksonException | IllegalArgumentException e) {
            return new LookupResult.Unavailable(url, "unreadable registry response: " + e.getMessage());
        }
    }

    @Override
    public DownloadsResult weeklyDownloads(String name) {
        String url = DOWNLOADS + name;
        if (!isValidName(name)) {
            return new DownloadsResult.Unknown(url, "invalid npm package name");
        }
        try {
            HttpResult result = http.get(URI.create(url));
            if (result.notFound()) {
                return new DownloadsResult.Known(0, url);
            }
            if (!result.ok()) {
                return new DownloadsResult.Unknown(url, "downloads API returned HTTP " + result.status());
            }
            JsonNode downloads = JSON.readTree(result.body()).path("downloads");
            if (!downloads.isNumber()) {
                return new DownloadsResult.Unknown(url, "downloads field missing");
            }
            return new DownloadsResult.Known(downloads.asLong(), url);
        } catch (IOException | JacksonException e) {
            return new DownloadsResult.Unknown(url, "request failed: " + e.getMessage());
        }
    }

    private static PackageInfo parse(PackageRequest request, String url, JsonNode doc) {
        JsonNode versions = doc.path("versions");
        JsonNode distTags = doc.path("dist-tags");
        JsonNode time = doc.path("time");
        String latest = textOrNull(distTags.path("latest"));

        String spec = request.versionSpec();
        String resolved;
        String note = null;
        if (spec == null || spec.equals("latest")) {
            resolved = latest;
        } else if (distTags.has(spec)) {
            resolved = textOrNull(distTags.path(spec));
        } else if (versions.has(stripV(spec))) {
            resolved = stripV(spec);
        } else if (EXACT_VERSION.matcher(spec).matches()) {
            resolved = null; // 요청한 정확한 버전이 없다
        } else {
            resolved = latest;
            note = "version range '" + spec + "' not resolved; checked latest (" + latest + ") instead";
        }

        Map<String, String> scripts = new LinkedHashMap<>();
        if (resolved != null) {
            JsonNode version = versions.path(resolved);
            JsonNode lifecycle = version.path("scripts");
            for (String name : INSTALL_LIFECYCLE) {
                String script = textOrNull(lifecycle.path(name));
                if (script != null) {
                    scripts.put(name, script);
                }
            }
            // binding.gyp가 있으면 install 스크립트가 없어도 npm이 node-gyp rebuild를 실행한다
            if (version.path("gypfile").asBoolean(false) && !scripts.containsKey("install")) {
                scripts.put("install", "node-gyp rebuild (implicit: binding.gyp)");
            }
        }

        return new PackageInfo(
                Ecosystem.NPM,
                textOrNull(doc.path("name")) != null ? textOrNull(doc.path("name")) : request.name(),
                url,
                spec,
                latest,
                resolved,
                note,
                instantOrNull(time.path("created")),
                resolved == null ? null : instantOrNull(time.path(resolved)),
                scripts,
                false,
                resolved == null ? null : publishers(versions, time, resolved));
    }

    /** 기준 버전의 게시자와, 게시 시각상 바로 전 버전의 게시자·관리자를 찾는다. */
    static PublisherHistory publishers(JsonNode versions, JsonNode time, String resolved) {
        Instant resolvedAt = instantOrNull(time.path(resolved));
        String previous = null;
        Instant previousAt = null;
        if (resolvedAt != null) {
            for (String v : versions.propertyNames()) {
                Instant at = instantOrNull(time.path(v));
                if (at != null && at.isBefore(resolvedAt) && (previousAt == null || at.isAfter(previousAt))) {
                    previous = v;
                    previousAt = at;
                }
            }
        }
        Set<String> previousMaintainers = new LinkedHashSet<>();
        if (previous != null) {
            for (JsonNode m : versions.path(previous).path("maintainers")) {
                String name = textOrNull(m.path("name"));
                if (name != null) {
                    previousMaintainers.add(name);
                }
            }
        }
        return new PublisherHistory(
                textOrNull(versions.path(resolved).path("_npmUser").path("name")),
                previous,
                previous == null ? null : textOrNull(versions.path(previous).path("_npmUser").path("name")),
                previousMaintainers);
    }

    private static String stripV(String spec) {
        return spec.startsWith("v") && spec.length() > 1 && Character.isDigit(spec.charAt(1)) ? spec.substring(1) : spec;
    }

    private static String textOrNull(JsonNode node) {
        return node.isString() ? node.asString() : null;
    }

    private static Instant instantOrNull(JsonNode node) {
        if (!node.isString()) {
            return null;
        }
        try {
            return Instant.parse(node.asString());
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
