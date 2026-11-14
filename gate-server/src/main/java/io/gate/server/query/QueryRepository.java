package io.gate.server.query;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 대시보드용 조회 (읽기 전용). 모든 값은 바인딩 변수로만 넘긴다. 필터 값은 컨트롤러에서 열거형으로 검증된 것만 온다.
 */
@Repository
public class QueryRepository {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    static final List<String> MODULES = List.of("PKG", "REVIEW", "AUTHZ", "PROMPT");

    private final JdbcTemplate jdbc;

    public QueryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 모듈별 실행 수·마지막 실행, 판정별·심각도별 결과 수. */
    public Map<String, Object> summary() {
        Map<String, Map<String, Object>> modules = new LinkedHashMap<>();
        for (String m : MODULES) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("module", m);
            e.put("runs", 0L);
            e.put("lastRunId", null);
            e.put("lastRunAt", null);
            e.put("findings", decisionCounts());
            modules.put(m, e);
        }
        jdbc.query("SELECT module, COUNT(*) AS runs, MAX(started_at) AS last_at FROM scan_run GROUP BY module", rs -> {
            Map<String, Object> e = modules.get(rs.getString("module"));
            e.put("runs", rs.getLong("runs"));
            e.put("lastRunAt", instant(rs, "last_at"));
        });
        jdbc.query("SELECT DISTINCT ON (module) module, id FROM scan_run ORDER BY module, started_at DESC, id", rs -> {
            modules.get(rs.getString("module")).put("lastRunId", rs.getString("id"));
        });
        jdbc.query("SELECT r.module, f.decision, COUNT(*) AS n FROM finding f JOIN scan_run r ON r.id = f.run_id "
                + "GROUP BY r.module, f.decision", rs -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Long> counts = (Map<String, Long>) modules.get(rs.getString("module")).get("findings");
                    counts.put(rs.getString("decision"), rs.getLong("n"));
                });

        Map<String, Long> bySeverity = new LinkedHashMap<>();
        for (String s : List.of("CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO")) {
            bySeverity.put(s, 0L);
        }
        jdbc.query("SELECT severity, COUNT(*) AS n FROM finding GROUP BY severity",
                rs -> {
                    bySeverity.put(rs.getString("severity"), rs.getLong("n"));
                });
        Map<String, Long> byDecision = decisionCounts();
        jdbc.query("SELECT decision, COUNT(*) AS n FROM finding GROUP BY decision",
                rs -> {
                    byDecision.put(rs.getString("decision"), rs.getLong("n"));
                });
        Long runs = jdbc.queryForObject("SELECT COUNT(*) FROM scan_run", Long.class);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("modules", new ArrayList<>(modules.values()));
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("runs", runs == null ? 0 : runs);
        totals.put("findings", byDecision.values().stream().mapToLong(Long::longValue).sum());
        totals.put("byDecision", byDecision);
        totals.put("bySeverity", bySeverity);
        out.put("totals", totals);
        return out;
    }

    public List<Map<String, Object>> runs(String module, int limit) {
        String sql = "SELECT r.id, r.module, r.trigger, r.target, r.status, r.started_at, r.finished_at, "
                + "COUNT(f.id) FILTER (WHERE f.decision = 'BLOCK') AS block, "
                + "COUNT(f.id) FILTER (WHERE f.decision = 'WARN') AS warn, "
                + "COUNT(f.id) FILTER (WHERE f.decision = 'PASS') AS pass "
                + "FROM scan_run r LEFT JOIN finding f ON f.run_id = r.id "
                + (module == null ? "" : "WHERE r.module = ? ")
                + "GROUP BY r.id ORDER BY r.started_at DESC, r.id LIMIT ?";
        Object[] args = module == null ? new Object[] {limit} : new Object[] {module, limit};
        return jdbc.query(sql, (rs, i) -> run(rs), args);
    }

    public Optional<Map<String, Object>> run(UUID id) {
        List<Map<String, Object>> runs = jdbc.query("SELECT r.id, r.module, r.trigger, r.target, r.status, "
                + "r.started_at, r.finished_at, "
                + "(SELECT COUNT(*) FROM finding f WHERE f.run_id = r.id AND f.decision = 'BLOCK') AS block, "
                + "(SELECT COUNT(*) FROM finding f WHERE f.run_id = r.id AND f.decision = 'WARN') AS warn, "
                + "(SELECT COUNT(*) FROM finding f WHERE f.run_id = r.id AND f.decision = 'PASS') AS pass "
                + "FROM scan_run r WHERE r.id = ?", (rs, i) -> run(rs), id);
        if (runs.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> run = runs.get(0);
        run.put("findings", jdbc.query(FINDING_SELECT + "WHERE f.run_id = ? ORDER BY f.detected_at DESC, f.id",
                (rs, i) -> finding(rs), id));
        return Optional.of(run);
    }

    public Optional<Map<String, Object>> finding(UUID id) {
        return jdbc.query(FINDING_SELECT + "WHERE f.id = ?", (rs, i) -> finding(rs), id).stream().findFirst();
    }

    /** 조건은 모두 선택이다. before가 있으면 그 시각보다 앞선 결과만 (시각 기준 페이지 나누기). */
    public List<Map<String, Object>> findings(FindingFilter filter, int limit) {
        StringBuilder where = new StringBuilder();
        List<Object> args = new ArrayList<>();
        add(where, args, "r.module = ?", filter.module());
        add(where, args, "f.decision = ?", filter.decision());
        add(where, args, "f.severity = ?", filter.severity());
        add(where, args, "f.fingerprint = ?", filter.fingerprint());
        add(where, args, "f.detected_at < ?", filter.before() == null ? null : Timestamp.from(filter.before()));
        args.add(limit);
        return jdbc.query(FINDING_SELECT + (where.isEmpty() ? "" : "WHERE " + where + " ")
                + "ORDER BY f.detected_at DESC, f.id LIMIT ?", (rs, i) -> finding(rs), args.toArray());
    }

    /** 결과 목록 필터. null은 "조건 없음". */
    public record FindingFilter(String module, String decision, String severity, String fingerprint,
            Instant before) {
    }

    private static final String FINDING_SELECT = "SELECT f.id, f.run_id, r.module, f.rule_id, f.title, f.severity, "
            + "f.decision, f.location::text AS location, f.evidence::text AS evidence, f.source, f.fingerprint, "
            + "f.detected_at FROM finding f JOIN scan_run r ON r.id = f.run_id ";

    private static void add(StringBuilder where, List<Object> args, String condition, Object value) {
        if (value == null) {
            return;
        }
        if (!where.isEmpty()) {
            where.append(" AND ");
        }
        where.append(condition);
        args.add(value);
    }

    private static Map<String, Long> decisionCounts() {
        Map<String, Long> m = new LinkedHashMap<>();
        m.put("BLOCK", 0L);
        m.put("WARN", 0L);
        m.put("PASS", 0L);
        return m;
    }

    private static Map<String, Object> run(ResultSet rs) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getString("id"));
        m.put("module", rs.getString("module"));
        m.put("trigger", rs.getString("trigger"));
        m.put("target", rs.getString("target"));
        m.put("status", rs.getString("status"));
        m.put("startedAt", instant(rs, "started_at"));
        m.put("finishedAt", instant(rs, "finished_at"));
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("BLOCK", rs.getLong("block"));
        counts.put("WARN", rs.getLong("warn"));
        counts.put("PASS", rs.getLong("pass"));
        m.put("findings", counts);
        return m;
    }

    private static Map<String, Object> finding(ResultSet rs) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getString("id"));
        m.put("runId", rs.getString("run_id"));
        m.put("module", rs.getString("module"));
        m.put("ruleId", rs.getString("rule_id"));
        m.put("title", rs.getString("title"));
        m.put("severity", rs.getString("severity"));
        m.put("decision", rs.getString("decision"));
        m.put("location", json(rs.getString("location")));
        m.put("evidence", json(rs.getString("evidence")));
        m.put("source", rs.getString("source"));
        m.put("fingerprint", rs.getString("fingerprint"));
        m.put("detectedAt", instant(rs, "detected_at"));
        return m;
    }

    private static JsonNode json(String text) {
        return JSON.readTree(text == null ? "{}" : text);
    }

    private static String instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant().toString();
    }
}
