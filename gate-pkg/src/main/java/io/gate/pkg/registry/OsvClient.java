package io.gate.pkg.registry;

import io.gate.common.finding.Severity;
import io.gate.pkg.http.HttpFetcher;
import io.gate.pkg.http.HttpResult;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * OSV 취약점 조회: {@code POST api.osv.dev/v1/query}.
 * 같은 취약점이 여러 ID(GHSA·PYSEC·CVE)로 중복되어 오므로 aliases로 묶어 하나로 합친다.
 * {@code MAL-}로 시작하는 ID는 OpenSSF 악성 패키지 보고다.
 */
public final class OsvClient {

    static final String URL = "https://api.osv.dev/v1/query";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpFetcher http;

    public OsvClient(HttpFetcher http) {
        this.http = http;
    }

    /** 취약점 하나(별칭으로 묶은 묶음). severity를 알 수 없으면 null. */
    public record Vulnerability(String id, Set<String> aliases, String summary, Severity severity,
            boolean malicious) {

        public Vulnerability {
            aliases = Set.copyOf(aliases);
        }
    }

    public sealed interface Result permits Result.Known, Result.Unknown {

        record Known(List<Vulnerability> vulnerabilities, String url) implements Result {
            public Known {
                vulnerabilities = List.copyOf(vulnerabilities);
            }
        }

        record Unknown(String url, String reason) implements Result {
        }
    }

    public Result query(Ecosystem ecosystem, String name, String version) {
        ObjectNode body = JSON.createObjectNode();
        ObjectNode pkg = body.putObject("package");
        pkg.put("name", name);
        pkg.put("ecosystem", ecosystem.osvName());
        if (version != null) {
            body.put("version", version);
        }
        try {
            HttpResult result = http.postJson(URI.create(URL), JSON.writeValueAsString(body));
            if (!result.ok()) {
                return new Result.Unknown(URL, "OSV returned HTTP " + result.status());
            }
            return new Result.Known(merge(JSON.readTree(result.body()).path("vulns")), URL);
        } catch (IOException | JacksonException e) {
            return new Result.Unknown(URL, "request failed: " + e.getMessage());
        }
    }

    /** aliases로 이어진 항목을 하나로 묶는다. 대표 ID는 MAL- > GHSA- > 처음 본 것 순서로 고른다. */
    static List<Vulnerability> merge(JsonNode vulns) {
        List<Group> groups = new ArrayList<>();
        for (JsonNode v : vulns) {
            Set<String> ids = new LinkedHashSet<>();
            String id = v.path("id").asString("");
            if (id.isEmpty()) {
                continue;
            }
            ids.add(id);
            for (JsonNode alias : v.path("aliases")) {
                ids.add(alias.asString(""));
            }
            ids.remove("");
            Group target = null;
            for (Group g : new ArrayList<>(groups)) {
                if (g.ids.stream().anyMatch(ids::contains)) {
                    if (target == null) {
                        target = g;
                    } else {
                        target.absorb(g);
                        groups.remove(g);
                    }
                }
            }
            if (target == null) {
                target = new Group();
                groups.add(target);
            }
            target.add(id, ids, v.path("summary").asString(null), severityOf(v));
        }
        return groups.stream().map(Group::toVulnerability).toList();
    }

    static Severity severityOf(JsonNode v) {
        String s = v.path("database_specific").path("severity").asString("").toUpperCase(Locale.ROOT);
        switch (s) {
            case "CRITICAL":
                return Severity.CRITICAL;
            case "HIGH":
                return Severity.HIGH;
            case "MODERATE":
            case "MEDIUM":
                return Severity.MEDIUM;
            case "LOW":
                return Severity.LOW;
            default:
                return null;
        }
    }

    private static final class Group {
        final Set<String> ids = new LinkedHashSet<>();
        final List<String> primaryIds = new ArrayList<>();
        String summary;
        Severity severity;

        void add(String primary, Set<String> all, String summary, Severity sev) {
            primaryIds.add(primary);
            ids.addAll(all);
            if (this.summary == null) {
                this.summary = summary;
            }
            severity = stronger(severity, sev);
        }

        void absorb(Group other) {
            primaryIds.addAll(other.primaryIds);
            ids.addAll(other.ids);
            if (summary == null) {
                summary = other.summary;
            }
            severity = stronger(severity, other.severity);
        }

        Vulnerability toVulnerability() {
            String rep = primaryIds.stream().filter(i -> i.startsWith("MAL-")).findFirst()
                    .or(() -> primaryIds.stream().filter(i -> i.startsWith("GHSA-")).findFirst())
                    .orElse(primaryIds.get(0));
            Set<String> aliases = new LinkedHashSet<>(ids);
            aliases.remove(rep);
            boolean malicious = ids.stream().anyMatch(i -> i.startsWith("MAL-"));
            return new Vulnerability(rep, aliases, summary, severity, malicious);
        }

        /** Severity enum은 CRITICAL이 맨 앞이므로 ordinal이 작을수록 강하다. */
        static Severity stronger(Severity a, Severity b) {
            if (a == null) {
                return b;
            }
            if (b == null) {
                return a;
            }
            return a.ordinal() <= b.ordinal() ? a : b;
        }
    }
}
