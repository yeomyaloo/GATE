package io.gate.authz.probe;

import io.gate.authz.config.AuthzConfig;
import io.gate.authz.http.ApiClient;
import io.gate.authz.spec.ApiSpec;
import io.gate.authz.spec.BodyGenerator;
import io.gate.common.finding.Decision;
import io.gate.common.finding.Finding;
import io.gate.common.finding.FindingSource;
import io.gate.common.finding.Location;
import io.gate.common.finding.Severity;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 권한 검사 본체 (설계안 2장).
 * 자원마다 B의 객체를 확보하고, 기준선(B 자신의 접근)이 성공할 때만 A의 토큰·토큰 없음으로 같은 요청을 보내 판정한다.
 */
public final class AuthzScanner {

    /** 소유자를 나타낼 법한 필드. 기준선 응답에 있으면 공격 응답에서도 같아야 "같은 객체"로 본다. */
    static final List<String> OWNER_FIELDS = List.of("owner", "ownerId", "userId", "username", "user", "createdBy");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ApiSpec spec;
    private final AuthzConfig config;
    private final ApiClient client;
    private final Function<String, String> env;
    private final Clock clock;
    private final BodyGenerator bodies;

    private final UUID runId = UUID.randomUUID();
    private final List<Finding> findings = new ArrayList<>();
    private final List<AuthzReport.Coverage> coverage = new ArrayList<>();

    public AuthzScanner(ApiSpec spec, AuthzConfig config, ApiClient client, Function<String, String> env,
            Clock clock) {
        this.spec = spec;
        this.config = config;
        this.client = client;
        this.env = env;
        this.clock = clock;
        this.bodies = new BodyGenerator(spec);
    }

    /** 객체 하나와 그것을 어떻게 얻었는지. */
    record OwnedObject(String id, String source) {
    }

    public AuthzReport scan() throws IOException {
        LoginClient login = new LoginClient(config, client, env);
        String tokenA = login.token(config.userA());
        String tokenB = login.token(config.userB());

        for (ApiSpec.Resource resource : spec.resources()) {
            Optional<OwnedObject> owned = findOwnedByB(resource, tokenB);
            for (ApiSpec.Operation op : resource.itemOperations()) {
                if (owned.isEmpty()) {
                    inconclusive(op, "no object owned by user B could be found (no list endpoint result, "
                            + (config.allowMutation() ? "create failed" : "creating is disabled without allowMutation")
                            + ", no seed id)", Map.of("resource", resource.collectionPath()));
                    continue;
                }
                if (op.method().equals("GET")) {
                    probeRead(op, owned.get(), tokenA, tokenB);
                } else {
                    coverage.add(new AuthzReport.Coverage(op.label(), "as user A", AuthzReport.Status.SKIPPED,
                            "write/delete checks are not implemented yet (stage 3 week 2)"));
                }
            }
        }
        return new AuthzReport(runId, findings, coverage);
    }

    // ---------- B의 객체 확보 (설계안 2.2) ----------

    Optional<OwnedObject> findOwnedByB(ApiSpec.Resource resource, String tokenB) throws IOException {
        if (resource.list().isPresent()) {
            ApiClient.Response r = client.send("GET", uri(resource.collectionPath()), tokenB, null);
            Optional<String> id = r.ok() ? firstId(r.body()) : Optional.empty();
            if (id.isPresent()) {
                return Optional.of(new OwnedObject(id.get(), "listed by user B"));
            }
        }
        if (config.allowMutation() && resource.create().isPresent()) {
            ApiSpec.Operation create = resource.create().get();
            String body = create.requestSchema() == null ? null
                    : JSON.writeValueAsString(bodies.generate(create.requestSchema()));
            ApiClient.Response r = client.send("POST", uri(resource.collectionPath()), tokenB, body);
            Optional<String> id = r.ok() ? idOf(parse(r.body())) : Optional.empty();
            if (id.isPresent()) {
                return Optional.of(new OwnedObject(id.get(), "created by user B"));
            }
        }
        List<String> seeds = config.seeds().getOrDefault(resource.collectionPath(), List.of());
        return seeds.isEmpty() ? Optional.empty() : Optional.of(new OwnedObject(seeds.get(0), "seed from config"));
    }

    // ---------- 조회: AUTHZ-BOLA-READ, AUTHZ-NOAUTH ----------

    void probeRead(ApiSpec.Operation op, OwnedObject owned, String tokenA, String tokenB) throws IOException {
        URI target = uri(op.path().replace("{" + op.pathParams().get(op.pathParams().size() - 1) + "}",
                URLEncoder.encode(owned.id(), StandardCharsets.UTF_8)));
        ApiClient.Response baseline = client.send("GET", target, tokenB, null);
        JsonNode baseNode = parse(baseline.body());
        if (!baseline.ok() || !idOf(baseNode).map(owned.id()::equals).orElse(false)) {
            inconclusive(op, "baseline failed: user B could not read its own object (HTTP " + baseline.status() + ")",
                    Map.of("objectId", owned.id(), "baselineStatus", baseline.status()));
            return;
        }

        ApiClient.Response attack = client.send("GET", target, tokenA, null);
        judgeRead(op, owned, target, baseline, baseNode, attack);

        if (op.requiresAuth()) {
            ApiClient.Response anon = client.send("GET", target, null, null);
            if (anon.ok()) {
                Map<String, Object> ev = evidence(owned, target, baseline, anon);
                ev.put("attacker", "anonymous (no token)");
                findings.add(finding(Rules.NOAUTH, "'" + op.label() + "' returns data without authentication",
                        Severity.HIGH, Decision.BLOCK, op, ev));
                coverage.add(new AuthzReport.Coverage(op.label(), "no token", AuthzReport.Status.FINDING,
                        "HTTP " + anon.status()));
            } else if (anon.status() >= 500) {
                coverage.add(new AuthzReport.Coverage(op.label(), "no token", AuthzReport.Status.INCONCLUSIVE,
                        "HTTP " + anon.status()));
            } else {
                coverage.add(new AuthzReport.Coverage(op.label(), "no token", AuthzReport.Status.PASS,
                        "HTTP " + anon.status()));
            }
        }
    }

    void judgeRead(ApiSpec.Operation op, OwnedObject owned, URI target, ApiClient.Response baseline,
            JsonNode baseNode, ApiClient.Response attack) {
        if (attack.ok()) {
            JsonNode attackNode = parse(attack.body());
            List<String> matched = sameObject(baseNode, attackNode);
            if (!matched.isEmpty()) {
                Map<String, Object> ev = evidence(owned, target, baseline, attack);
                ev.put("matchedFields", matched);
                findings.add(finding(Rules.BOLA_READ, "User A can read user B's object via '" + op.label() + "'",
                        Severity.HIGH, Decision.BLOCK, op, ev));
                coverage.add(new AuthzReport.Coverage(op.label(), "as user A", AuthzReport.Status.FINDING,
                        "HTTP " + attack.status() + ", same object (" + String.join(", ", matched) + ")"));
            } else {
                coverage.add(new AuthzReport.Coverage(op.label(), "as user A", AuthzReport.Status.PASS,
                        "HTTP " + attack.status() + " but a different object was returned"));
            }
        } else if (attack.status() >= 500) {
            inconclusive(op, "user A's request failed with HTTP " + attack.status(),
                    Map.of("objectId", owned.id(), "status", attack.status()));
        } else {
            String note = attack.status() == 403
                    ? "HTTP 403: blocked, but 403 (unlike 404) reveals that the object exists"
                    : "HTTP " + attack.status() + ": blocked";
            coverage.add(new AuthzReport.Coverage(op.label(), "as user A", AuthzReport.Status.PASS, note));
        }
    }

    /** 같은 객체인지: ID가 같고, 기준선에 소유자 필드가 있으면 그것도 같아야 한다. 일치한 필드 이름을 돌려준다. */
    List<String> sameObject(JsonNode baseline, JsonNode attack) {
        List<String> matched = new ArrayList<>();
        Optional<String> baseId = idOf(baseline);
        if (baseId.isEmpty() || !baseId.equals(idOf(attack))) {
            return List.of();
        }
        matched.add(config.idField());
        for (String f : OWNER_FIELDS) {
            if (baseline.has(f)) {
                if (!baseline.get(f).equals(attack.path(f))) {
                    return List.of();
                }
                matched.add(f);
            }
        }
        return matched;
    }

    // ---------- 공통 ----------

    private void inconclusive(ApiSpec.Operation op, String reason, Map<String, Object> extra) {
        Map<String, Object> ev = new LinkedHashMap<>(extra);
        ev.put("reason", reason);
        findings.add(finding(Rules.INCONCLUSIVE, "Could not check '" + op.label() + "'", Severity.INFO,
                Decision.WARN, op, ev));
        coverage.add(new AuthzReport.Coverage(op.label(), "as user A", AuthzReport.Status.INCONCLUSIVE, reason));
    }

    private Finding finding(String rule, String title, Severity severity, Decision decision, ApiSpec.Operation op,
            Map<String, Object> evidence) {
        return new Finding(UUID.randomUUID(), runId, rule, title, severity, decision,
                new Location.Endpoint(op.method(), op.path()), evidence, FindingSource.RULE, clock.instant());
    }

    private Map<String, Object> evidence(OwnedObject owned, URI target, ApiClient.Response baseline,
            ApiClient.Response attack) {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("attacker", config.userA().username());
        ev.put("owner", config.userB().username());
        ev.put("objectId", owned.id());
        ev.put("objectSource", owned.source());
        ev.put("request", target.getPath());
        ev.put("baselineStatus", baseline.status());
        ev.put("attackStatus", attack.status());
        ev.put("responseExcerpt", Redactor.excerpt(attack.body()));
        return ev;
    }

    private URI uri(String path) {
        return URI.create(config.baseUrl() + path);
    }

    private Optional<String> firstId(String body) {
        JsonNode node = parse(body);
        if (node.isArray()) {
            for (JsonNode item : node) {
                Optional<String> id = idOf(item);
                if (id.isPresent()) {
                    return id;
                }
            }
        }
        // {"items":[...]} 같은 감싼 목록
        if (node.isObject()) {
            for (String key : Set.of("items", "content", "data", "results")) {
                if (node.path(key).isArray()) {
                    return firstId(node.path(key).toString());
                }
            }
        }
        return Optional.empty();
    }

    private Optional<String> idOf(JsonNode node) {
        JsonNode id = node == null ? null : node.path(config.idField());
        if (id == null || id.isMissingNode() || id.isNull() || !id.isValueNode()) {
            return Optional.empty();
        }
        return Optional.of(id.asString());
    }

    private static JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return JSON.missingNode();
        }
        try {
            return JSON.readTree(body);
        } catch (JacksonException e) {
            return JSON.missingNode();
        }
    }
}
