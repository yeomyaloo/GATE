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

        try {
            for (ApiSpec.Resource resource : spec.resources()) {
                Optional<OwnedObject> owned = findOwnedByB(resource, tokenB);
                for (ApiSpec.Operation op : resource.itemOperations()) {
                    if (op.method().equals("GET")) {
                        if (owned.isEmpty()) {
                            inconclusive(op, "no object owned by user B could be found (no list endpoint result, "
                                    + (config.allowMutation() ? "create failed"
                                            : "creating is disabled without allowMutation")
                                    + ", no seed id)", Map.of("resource", resource.collectionPath()));
                        } else {
                            probeRead(op, owned.get(), tokenA, tokenB);
                        }
                    } else if (MUTATING.contains(op.method())) {
                        probeMutation(resource, op, tokenA, tokenB);
                    }
                }
            }
        } finally {
            cleanup(tokenB);
        }
        return new AuthzReport(runId, findings, coverage);
    }

    static final Set<String> MUTATING = Set.of("PUT", "PATCH", "DELETE");
    /** 수정 시도에 넣는 표시 문자열. B가 다시 읽었을 때 이 값이 보이면 A의 수정이 실제로 반영된 것이다. */
    static final String MARKER = "gate-authz-probe";

    /** 이 검사에서 B로 만든 객체: 검사 후 B로 지운다. */
    private final List<CreatedObject> created = new ArrayList<>();

    record CreatedObject(ApiSpec.Resource resource, String id) {
    }

    // ---------- 수정·삭제: AUTHZ-BOLA-WRITE, AUTHZ-BOLA-DELETE (설계안 2.1, 3장) ----------

    /**
     * 기존 데이터는 건드리지 않는다: allowMutation일 때만, 이 검사를 위해 B로 새로 만든 객체에만 시도한다.
     * 판정은 응답 코드가 아니라 B가 다시 읽은 결과로 한다.
     */
    void probeMutation(ApiSpec.Resource resource, ApiSpec.Operation op, String tokenA, String tokenB)
            throws IOException {
        if (!config.allowMutation()) {
            coverage.add(new AuthzReport.Coverage(op.label(), "as user A", AuthzReport.Status.SKIPPED,
                    "read-only by default; set allowMutation: true to test writes on objects created for the test"));
            return;
        }
        Optional<ApiSpec.Operation> read = resource.itemOperations().stream()
                .filter(o -> o.method().equals("GET")).findFirst();
        if (read.isEmpty()) {
            inconclusive(op, "no GET on '" + resource.itemPath() + "' to verify the effect", Map.of());
            return;
        }
        Optional<OwnedObject> fresh = createForB(resource, tokenB);
        if (fresh.isEmpty()) {
            inconclusive(op, "could not create a fresh object as user B to test on", Map.of());
            return;
        }
        OwnedObject owned = fresh.get();
        URI target = itemUri(op, owned.id());
        ApiClient.Response before = client.send("GET", target, tokenB, null);
        JsonNode beforeNode = parse(before.body());
        if (!before.ok() || !idOf(beforeNode).map(owned.id()::equals).orElse(false)) {
            inconclusive(op, "baseline failed: user B could not read the object it just created (HTTP "
                    + before.status() + ")", Map.of("objectId", owned.id()));
            return;
        }

        if (op.method().equals("DELETE")) {
            ApiClient.Response attack = client.send("DELETE", target, tokenA, null);
            ApiClient.Response after = client.send("GET", target, tokenB, null);
            boolean gone = after.status() == 404 || after.status() == 410;
            if (gone) {
                Map<String, Object> ev = evidence(owned, target, before, attack);
                ev.put("verifiedBy", "user B re-read the object: HTTP " + after.status());
                findings.add(finding(Rules.BOLA_DELETE, "User A can delete user B's object via '" + op.label() + "'",
                        Severity.CRITICAL, Decision.BLOCK, op, ev));
                coverage.add(new AuthzReport.Coverage(op.label(), "as user A", AuthzReport.Status.FINDING,
                        "HTTP " + attack.status() + ", object gone for its owner (verified)"));
            } else if (after.ok()) {
                coverage.add(new AuthzReport.Coverage(op.label(), "as user A", AuthzReport.Status.PASS,
                        "HTTP " + attack.status() + ", object still there for its owner (verified)"));
            } else {
                inconclusive(op, "could not verify: user B re-read returned HTTP " + after.status(),
                        Map.of("objectId", owned.id()));
            }
            return;
        }

        String body = mutationBody(op);
        ApiClient.Response attack = client.send(op.method(), target, tokenA, body);
        ApiClient.Response after = client.send("GET", target, tokenB, null);
        if (!after.ok()) {
            inconclusive(op, "could not verify: user B re-read returned HTTP " + after.status(),
                    Map.of("objectId", owned.id(), "attackStatus", attack.status()));
            return;
        }
        List<String> changed = changedBy(parse(body), beforeNode, parse(after.body()));
        if (!changed.isEmpty()) {
            Map<String, Object> ev = evidence(owned, target, before, attack);
            ev.put("changedFields", changed);
            ev.put("verifiedBy", "user B re-read the object and saw the attacker's values");
            findings.add(finding(Rules.BOLA_WRITE, "User A can modify user B's object via '" + op.label() + "'",
                    Severity.CRITICAL, Decision.BLOCK, op, ev));
            coverage.add(new AuthzReport.Coverage(op.label(), "as user A", AuthzReport.Status.FINDING,
                    "HTTP " + attack.status() + ", changed " + String.join(", ", changed) + " (verified)"));
        } else {
            coverage.add(new AuthzReport.Coverage(op.label(), "as user A", AuthzReport.Status.PASS,
                    "HTTP " + attack.status() + (attack.ok() ? " but nothing changed" : "")
                            + " (verified by owner re-read)"));
        }
    }

    /** 요청 본문: 명세 예시로 만들고, 문자열 필드는 표시 문자열로 바꿔 변경 여부를 확실히 알 수 있게 한다. */
    String mutationBody(ApiSpec.Operation op) {
        if (op.requestSchema() == null) {
            return null;
        }
        JsonNode generated = bodies.generate(op.requestSchema());
        if (generated.isObject()) {
            var obj = (tools.jackson.databind.node.ObjectNode) generated;
            for (String name : new ArrayList<>(obj.propertyNames())) {
                if (obj.get(name).isString()) {
                    obj.put(name, MARKER);
                }
            }
        }
        return JSON.writeValueAsString(generated);
    }

    /** 보낸 값이 다시 읽은 객체에 나타났고 원래 값과 다른 필드. */
    static List<String> changedBy(JsonNode sent, JsonNode before, JsonNode after) {
        List<String> changed = new ArrayList<>();
        if (!sent.isObject()) {
            return changed;
        }
        for (String name : sent.propertyNames()) {
            JsonNode value = sent.get(name);
            if (after.has(name) && after.get(name).equals(value) && !before.path(name).equals(value)) {
                changed.add(name);
            }
        }
        return changed;
    }

    Optional<OwnedObject> createForB(ApiSpec.Resource resource, String tokenB) throws IOException {
        if (resource.create().isEmpty()) {
            return Optional.empty();
        }
        ApiSpec.Operation create = resource.create().get();
        String body = create.requestSchema() == null ? null
                : JSON.writeValueAsString(bodies.generate(create.requestSchema()));
        ApiClient.Response r = client.send("POST", uri(resource.collectionPath()), tokenB, body);
        Optional<String> id = r.ok() ? idOf(parse(r.body())) : Optional.empty();
        id.ifPresent(i -> created.add(new CreatedObject(resource, i)));
        return id.map(i -> new OwnedObject(i, "created by user B"));
    }

    /** 검사가 만든 객체를 주인(B)으로 지운다. 실패해도 검사 결과에는 영향이 없고 커버리지에 남긴다. */
    void cleanup(String tokenB) {
        for (CreatedObject c : created) {
            Optional<ApiSpec.Operation> delete = c.resource().itemOperations().stream()
                    .filter(o -> o.method().equals("DELETE")).findFirst();
            if (delete.isEmpty()) {
                coverage.add(new AuthzReport.Coverage(c.resource().itemPath(), "cleanup", AuthzReport.Status.SKIPPED,
                        "no DELETE operation; object " + c.id() + " was left in place"));
                continue;
            }
            try {
                ApiClient.Response r = client.send("DELETE", itemUri(delete.get(), c.id()), tokenB, null);
                if (!r.ok() && r.status() != 404) {
                    coverage.add(new AuthzReport.Coverage(c.resource().itemPath(), "cleanup",
                            AuthzReport.Status.INCONCLUSIVE, "could not delete test object " + c.id() + ": HTTP "
                                    + r.status()));
                }
            } catch (IOException | RuntimeException e) {
                coverage.add(new AuthzReport.Coverage(c.resource().itemPath(), "cleanup",
                        AuthzReport.Status.INCONCLUSIVE, "could not delete test object " + c.id() + ": " + e));
            }
        }
        created.clear();
    }

    private URI itemUri(ApiSpec.Operation op, String id) {
        return uri(op.path().replace("{" + op.pathParams().get(op.pathParams().size() - 1) + "}",
                URLEncoder.encode(id, StandardCharsets.UTF_8)));
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
        if (config.allowMutation()) {
            Optional<OwnedObject> createdObject = createForB(resource, tokenB);
            if (createdObject.isPresent()) {
                return createdObject;
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
