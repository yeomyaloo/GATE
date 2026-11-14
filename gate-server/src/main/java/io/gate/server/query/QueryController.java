package io.gate.server.query;

import io.gate.common.finding.Decision;
import io.gate.common.finding.GateModule;
import io.gate.common.finding.Severity;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 대시보드 조회 API (마지막 단계 설계안 3장). 읽기 전용이다.
 * 인증은 두지 않는다: 서버가 127.0.0.1에만 열려 있다(결정 3). 외부에 노출하는 단계에서 토큰을 추가한다.
 * 필터 값은 열거형으로 검증하고, 잘못된 값은 400으로 막는다.
 */
@RestController
public class QueryController {

    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 200;

    private final QueryRepository repository;

    public QueryController(QueryRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/api/summary")
    public Map<String, Object> summary() {
        return repository.summary();
    }

    @GetMapping("/api/scan-runs")
    public List<Map<String, Object>> runs(@RequestParam(required = false) String module,
            @RequestParam(required = false) Integer limit) {
        return repository.runs(enumName(module, GateModule::valueOf, "module"), limit(limit));
    }

    @GetMapping("/api/scan-runs/{id}")
    public ResponseEntity<Map<String, Object>> run(@PathVariable String id) {
        return repository.run(uuid(id)).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/api/findings")
    public List<Map<String, Object>> findings(@RequestParam(required = false) String module,
            @RequestParam(required = false) String decision, @RequestParam(required = false) String severity,
            @RequestParam(required = false) String fingerprint, @RequestParam(required = false) String before,
            @RequestParam(required = false) Integer limit) {
        if (fingerprint != null && !fingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("fingerprint must be 64 lowercase hex characters");
        }
        Instant beforeAt;
        try {
            beforeAt = before == null ? null : Instant.parse(before);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("before must be an ISO-8601 instant, e.g. 2026-11-15T00:00:00Z");
        }
        return repository.findings(new QueryRepository.FindingFilter(
                enumName(module, GateModule::valueOf, "module"),
                enumName(decision, Decision::valueOf, "decision"),
                enumName(severity, Severity::valueOf, "severity"),
                fingerprint, beforeAt), limit(limit));
    }

    @GetMapping("/api/findings/{id}")
    public ResponseEntity<Map<String, Object>> finding(@PathVariable String id) {
        return repository.finding(uuid(id)).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    /** 잘못된 조회 조건은 400과 이유 한 줄. 내부 정보는 내보내지 않는다. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    static int limit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be 1.." + MAX_LIMIT);
        }
        return limit;
    }

    static <E extends Enum<E>> String enumName(String value, Function<String, E> parse, String name) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return parse.apply(value.toUpperCase(Locale.ROOT)).name();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown " + name + ": " + value);
        }
    }

    static UUID uuid(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("id must be a UUID");
        }
    }
}
