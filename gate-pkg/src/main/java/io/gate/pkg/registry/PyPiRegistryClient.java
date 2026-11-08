package io.gate.pkg.registry;

import io.gate.pkg.http.HttpFetcher;
import io.gate.pkg.http.HttpResult;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * PyPI 조회.
 * <ul>
 *   <li>메타데이터: {@code GET pypi.org/pypi/{name}/json}. 릴리스별 파일 목록과 업로드 시각이 있다</li>
 *   <li>다운로드: {@code GET pypistats.org/api/packages/{normalized}/recent} → {@code data.last_week}.
 *       pypistats는 PEP 503으로 정규화한 소문자 이름만 받는다</li>
 * </ul>
 * PyPI는 게시자 변경 이력을 제공하지 않으므로 PKG-MAINT는 검사하지 않는다(설계안 4장).
 */
public final class PyPiRegistryClient implements RegistryClient {

    static final String REGISTRY = "https://pypi.org/pypi/";
    static final String DOWNLOADS = "https://pypistats.org/api/packages/";

    // PEP 508 이름 규칙. URL에 넣기 전에 검증한다.
    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9]([A-Za-z0-9._-]*[A-Za-z0-9])?$");
    private static final Pattern SEPARATORS = Pattern.compile("[-_.]+");
    private static final Pattern EXACT_SPEC = Pattern.compile("^(?:===?\\s*)?([vV]?[0-9][0-9A-Za-z.+!-]*)$");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpFetcher http;

    public PyPiRegistryClient(HttpFetcher http) {
        this.http = http;
    }

    @Override
    public Ecosystem ecosystem() {
        return Ecosystem.PYPI;
    }

    public static boolean isValidName(String name) {
        return name.length() <= 200 && NAME.matcher(name).matches();
    }

    /** PEP 503 정규화: 소문자, 연속된 {@code - _ .}는 {@code -} 하나로. */
    public static String normalize(String name) {
        return SEPARATORS.matcher(name).replaceAll("-").toLowerCase(Locale.ROOT);
    }

    @Override
    public LookupResult lookup(PackageRequest request) {
        if (!isValidName(request.name())) {
            return new LookupResult.Unavailable(REGISTRY, "invalid PyPI project name: " + request.name());
        }
        String url = REGISTRY + normalize(request.name()) + "/json";
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
        String url = DOWNLOADS + normalize(name) + "/recent";
        if (!isValidName(name)) {
            return new DownloadsResult.Unknown(url, "invalid PyPI project name");
        }
        try {
            HttpResult result = http.get(URI.create(url));
            if (result.notFound()) {
                // pypistats는 통계가 없는(대개 새로 생긴) 프로젝트에 404를 준다
                return new DownloadsResult.Known(0, url);
            }
            if (!result.ok()) {
                return new DownloadsResult.Unknown(url, "pypistats returned HTTP " + result.status());
            }
            JsonNode lastWeek = JSON.readTree(result.body()).path("data").path("last_week");
            if (!lastWeek.isNumber()) {
                return new DownloadsResult.Unknown(url, "last_week field missing");
            }
            return new DownloadsResult.Known(lastWeek.asLong(), url);
        } catch (IOException | JacksonException e) {
            return new DownloadsResult.Unknown(url, "request failed: " + e.getMessage());
        }
    }

    private static PackageInfo parse(PackageRequest request, String url, JsonNode doc) {
        JsonNode releases = doc.path("releases");
        String latest = doc.path("info").path("version").isString() ? doc.path("info").path("version").asString() : null;
        String canonicalName = doc.path("info").path("name").isString()
                ? doc.path("info").path("name").asString() : request.name();

        String spec = request.versionSpec();
        String resolved;
        String note = null;
        Matcher exact = spec == null ? null : EXACT_SPEC.matcher(spec.trim());
        if (spec == null) {
            resolved = latest;
        } else if (exact.matches()) {
            resolved = findRelease(releases, exact.group(1)); // 없으면 null → 버전 없음
        } else {
            resolved = latest;
            note = "version specifier '" + spec + "' not resolved; checked latest (" + latest + ") instead";
        }

        Instant created = null;
        for (Map.Entry<String, JsonNode> release : releases.properties()) {
            Instant first = earliestUpload(release.getValue());
            if (first != null && (created == null || first.isBefore(created))) {
                created = first;
            }
        }

        Instant versionAt = null;
        boolean sdistOnly = false;
        if (resolved != null) {
            JsonNode files = releases.path(resolved);
            versionAt = earliestUpload(files);
            boolean hasFiles = false;
            boolean hasWheel = false;
            for (JsonNode file : files) {
                hasFiles = true;
                if ("bdist_wheel".equals(file.path("packagetype").asString(""))) {
                    hasWheel = true;
                }
            }
            sdistOnly = hasFiles && !hasWheel;
        }

        return new PackageInfo(Ecosystem.PYPI, canonicalName, url, spec, latest, resolved, note, created, versionAt,
                Map.of(), sdistOnly);
    }

    /** 릴리스 키를 그대로, 안 되면 PEP 440식으로 느슨하게(소문자, v 제거, 끝의 .0 무시) 찾는다. */
    static String findRelease(JsonNode releases, String wanted) {
        if (releases.has(wanted)) {
            return wanted;
        }
        String key = looseVersion(wanted);
        for (String candidate : releases.propertyNames()) {
            if (looseVersion(candidate).equals(key)) {
                return candidate;
            }
        }
        return null;
    }

    static String looseVersion(String v) {
        String s = v.toLowerCase(Locale.ROOT);
        if (s.startsWith("v")) {
            s = s.substring(1);
        }
        int end = 0;
        while (end < s.length() && (Character.isDigit(s.charAt(end)) || s.charAt(end) == '.')) {
            end++;
        }
        String release = s.substring(0, end);
        String rest = s.substring(end);
        while (release.endsWith(".0")) {
            release = release.substring(0, release.length() - 2);
        }
        return release + rest;
    }

    private static Instant earliestUpload(JsonNode files) {
        Instant earliest = null;
        for (JsonNode file : files) {
            JsonNode t = file.path("upload_time_iso_8601");
            if (!t.isString()) {
                continue;
            }
            try {
                Instant at = Instant.parse(t.asString());
                if (earliest == null || at.isBefore(earliest)) {
                    earliest = at;
                }
            } catch (DateTimeParseException e) {
                // 해석할 수 없는 시각은 건너뛴다
            }
        }
        return earliest;
    }
}
