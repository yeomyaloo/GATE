package io.gate.server.scan;

import io.gate.common.finding.Decision;
import io.gate.common.finding.Finding;
import io.gate.common.finding.FindingSource;
import io.gate.common.finding.GateModule;
import io.gate.common.finding.Location;
import io.gate.common.finding.Severity;
import io.gate.common.finding.Trigger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code POST /api/scan-runs} 요청 본문: 검사 1회와 그 결과들.
 * 결과는 gate-common {@link Finding}으로 바꾸면서 검증한다(근거 없는 BLOCK·WARN, AI 단독 BLOCK 거부).
 */
public record ScanRunRequest(
        GateModule module,
        Trigger trigger,
        String target,
        Instant startedAt,
        Instant finishedAt,
        List<FindingRequest> findings) {

    public static final int MAX_FINDINGS = 1000;
    public static final int MAX_TARGET = 512;
    public static final int MAX_TEXT = 512;

    /** 결과 하나. location은 {@code {"kind":"package","ecosystem":…,"name":…,"version":…}} 형태. */
    public record FindingRequest(
            String ruleId,
            String title,
            Severity severity,
            Decision decision,
            Map<String, Object> location,
            Map<String, Object> evidence,
            FindingSource source,
            Instant detectedAt) {
    }

    /** 요청 전체를 검증하고 결과 목록으로 바꾼다. 같은 지문의 결과는 처음 것만 남긴다. */
    public List<Finding> toFindings(UUID runId, Instant now) {
        require(module != null, "module is required");
        require(trigger != null, "trigger is required");
        require(target != null && !target.isBlank(), "target is required");
        require(target.length() <= MAX_TARGET, "target is longer than " + MAX_TARGET);
        require(startedAt == null || finishedAt == null || !finishedAt.isBefore(startedAt),
                "finishedAt is before startedAt");
        List<FindingRequest> items = findings == null ? List.of() : findings;
        require(items.size() <= MAX_FINDINGS, "too many findings (max " + MAX_FINDINGS + ")");

        Map<String, Finding> byFingerprint = new LinkedHashMap<>();
        for (int i = 0; i < items.size(); i++) {
            FindingRequest f = items.get(i);
            require(f != null, "findings[" + i + "] is null");
            require(f.ruleId() != null && f.ruleId().length() <= 64, "findings[" + i + "].ruleId is required (max 64)");
            require(f.title() != null && f.title().length() <= MAX_TEXT,
                    "findings[" + i + "].title is required (max " + MAX_TEXT + ")");
            Finding finding;
            try {
                finding = new Finding(UUID.randomUUID(), runId, f.ruleId(), f.title(), f.severity(), f.decision(),
                        location(f.location()), f.evidence(), f.source(), f.detectedAt() == null ? now : f.detectedAt());
            } catch (IllegalArgumentException | NullPointerException e) {
                throw new IllegalArgumentException("findings[" + i + "]: " + e.getMessage(), e);
            }
            byFingerprint.putIfAbsent(finding.fingerprint(), finding);
        }
        return new ArrayList<>(byFingerprint.values());
    }

    static Location location(Map<String, Object> raw) {
        require(raw != null, "location is required");
        Object kind = raw.get("kind");
        if ("package".equals(kind)) {
            return new Location.Package(str(raw, "ecosystem"), str(raw, "name"), str(raw, "version"));
        }
        if ("file".equals(kind)) {
            Object line = raw.get("line");
            require(line instanceof Number, "location.line must be a number");
            return new Location.File(str(raw, "path"), ((Number) line).intValue());
        }
        if ("endpoint".equals(kind)) {
            return new Location.Endpoint(str(raw, "method"), str(raw, "path"));
        }
        throw new IllegalArgumentException("location.kind must be package, file or endpoint");
    }

    /** DB에 JSONB로 저장할 위치 표현. */
    static Map<String, Object> locationJson(Location location) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", location.kind());
        if (location instanceof Location.Package p) {
            m.put("ecosystem", p.ecosystem());
            m.put("name", p.name());
            if (p.version() != null) {
                m.put("version", p.version());
            }
        } else if (location instanceof Location.File f) {
            m.put("path", f.path());
            m.put("line", f.line());
        } else if (location instanceof Location.Endpoint e) {
            m.put("method", e.method());
            m.put("path", e.path());
        }
        return m;
    }

    private static String str(Map<String, Object> raw, String key) {
        Object v = raw.get(key);
        return v == null ? null : v.toString();
    }

    private static void require(boolean ok, String message) {
        if (!ok) {
            throw new IllegalArgumentException(message);
        }
    }
}
