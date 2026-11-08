package io.gate.server.scan;

import io.gate.common.finding.Finding;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 검사 결과 저장 API (1단계 설계안 7장).
 * 인증: 1단계에서는 서버가 127.0.0.1에만 열려 있어 넣지 않는다. 외부에 노출하는 단계에서 토큰을 추가한다.
 */
@RestController
@RequestMapping("/api/scan-runs")
public class ScanRunController {

    private final ScanRunRepository repository;
    private final Clock clock;

    public ScanRunController(ScanRunRepository repository) {
        this(repository, Clock.systemUTC());
    }

    ScanRunController(ScanRunRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody ScanRunRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request body is required");
        }
        UUID runId = UUID.randomUUID();
        Instant now = clock.instant();
        List<Finding> findings = request.toFindings(runId, now);
        repository.save(runId, request, findings, now);
        return ResponseEntity.created(URI.create("/api/scan-runs/" + runId))
                .body(Map.of("id", runId.toString(), "findings", findings.size()));
    }

    /** 입력 검증 실패는 400과 이유 한 줄로 돌려준다. 내부 정보(스택 등)는 내보내지 않는다. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
