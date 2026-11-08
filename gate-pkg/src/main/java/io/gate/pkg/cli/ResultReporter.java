package io.gate.pkg.cli;

import io.gate.common.finding.Finding;
import io.gate.common.finding.Location;
import io.gate.common.finding.Trigger;
import io.gate.pkg.http.HttpFetcher;
import io.gate.pkg.http.HttpResult;
import io.gate.pkg.http.JdkHttpFetcher;
import io.gate.pkg.scan.ScanReport;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 검사 결과를 gate-server({@code POST /api/scan-runs})로 보낸다 (설계안 7장).
 * {@code GATE_SERVER_URL}이 있을 때만 보내고, 제한 시간은 1초다. 전송 실패는 판정에 영향을 주지 않는다.
 */
public final class ResultReporter {

    public static final String ENV = "GATE_SERVER_URL";
    static final int MAX_TARGET = 512;

    private final URI endpoint;
    private final HttpFetcher http;

    ResultReporter(URI endpoint, HttpFetcher http) {
        this.endpoint = endpoint;
        this.http = http;
    }

    /** 환경 변수에서 만든다. 없거나 http(s)가 아니면 빈 값. */
    public static Optional<ResultReporter> fromEnv() {
        return fromUrl(System.getenv(ENV), new JdkHttpFetcher(Duration.ofSeconds(1)));
    }

    static Optional<ResultReporter> fromUrl(String base, HttpFetcher http) {
        if (base == null || base.isBlank()) {
            return Optional.empty();
        }
        String b = base.strip();
        String lower = b.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return Optional.empty();
        }
        return Optional.of(new ResultReporter(URI.create(b.replaceAll("/+$", "") + "/api/scan-runs"), http));
    }

    /** @return 전송 결과 한 줄 (로그용). 실패해도 예외를 던지지 않는다. */
    public String report(ScanReport report, Trigger trigger, String target, Instant startedAt, Instant finishedAt) {
        try {
            HttpResult r = http.postJson(endpoint, body(report, trigger, target, startedAt, finishedAt));
            return r.ok() ? "reported to " + endpoint : "report rejected: HTTP " + r.status();
        } catch (IOException | RuntimeException e) {
            return "report failed: " + e.getMessage();
        }
    }

    static String body(ScanReport report, Trigger trigger, String target, Instant startedAt, Instant finishedAt) {
        ObjectNode root = Reports.JSON.createObjectNode();
        root.put("module", "PKG");
        root.put("trigger", trigger.name());
        String t = target == null || target.isBlank() ? "(none)" : target.strip();
        root.put("target", t.length() <= MAX_TARGET ? t : t.substring(0, MAX_TARGET - 1) + "…");
        root.put("startedAt", startedAt.toString());
        root.put("finishedAt", finishedAt.toString());
        ArrayNode findings = root.putArray("findings");
        for (Finding f : report.findings()) {
            ObjectNode n = findings.addObject();
            n.put("ruleId", f.ruleId());
            n.put("title", f.title());
            n.put("severity", f.severity().name());
            n.put("decision", f.decision().name());
            ObjectNode loc = n.putObject("location");
            loc.put("kind", f.location().kind());
            if (f.location() instanceof Location.Package p) {
                loc.put("ecosystem", p.ecosystem());
                loc.put("name", p.name());
                if (p.version() != null) {
                    loc.put("version", p.version());
                }
            }
            n.set("evidence", Reports.JSON.valueToTree(f.evidence()));
            n.put("source", f.source().name());
            n.put("detectedAt", f.detectedAt().toString());
        }
        return Reports.JSON.writeValueAsString(root);
    }
}
