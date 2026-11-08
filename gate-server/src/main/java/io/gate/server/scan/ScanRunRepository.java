package io.gate.server.scan;

import io.gate.common.finding.Finding;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** scan_run·finding 테이블 저장 (V1__init.sql). */
@Repository
public class ScanRunRepository {

    // JSONB 문자열을 만드는 용도라 자체 매퍼를 쓴다 (자동 구성 빈에 의존하지 않음)
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcTemplate jdbc;

    public ScanRunRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void save(UUID runId, ScanRunRequest run, List<Finding> findings, Instant now) {
        Instant started = run.startedAt() != null ? run.startedAt() : now;
        Instant finished = run.finishedAt() != null ? run.finishedAt() : now;
        jdbc.update("INSERT INTO scan_run (id, module, trigger, target, status, started_at, finished_at) "
                        + "VALUES (?, ?, ?, ?, 'DONE', ?, ?)",
                runId, run.module().name(), run.trigger().name(), run.target(),
                Timestamp.from(started), Timestamp.from(finished));
        for (Finding f : findings) {
            jdbc.update("INSERT INTO finding (id, run_id, rule_id, title, severity, decision, location, evidence, "
                            + "source, fingerprint, detected_at) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?)",
                    f.id(), runId, f.ruleId(), f.title(), f.severity().name(), f.decision().name(),
                    JSON.writeValueAsString(ScanRunRequest.locationJson(f.location())),
                    JSON.writeValueAsString(f.evidence()),
                    f.source().name(), f.fingerprint(), Timestamp.from(f.detectedAt()));
        }
    }
}
