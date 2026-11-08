-- GATE 공통 검사 결과 저장소
-- scan_run: 검사 1회 / finding: 발견 사항 (무엇이·어디서·위험도·근거·시각)

CREATE TABLE scan_run (
    id           UUID PRIMARY KEY,
    module       VARCHAR(16)  NOT NULL CHECK (module IN ('PKG', 'REVIEW', 'AUTHZ', 'PROMPT')),
    trigger      VARCHAR(16)  NOT NULL CHECK (trigger IN ('CLI', 'CLAUDE_HOOK', 'GITHUB_ACTION', 'API')),
    target       VARCHAR(512) NOT NULL,
    status       VARCHAR(16)  NOT NULL CHECK (status IN ('RUNNING', 'DONE', 'FAILED')),
    started_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    finished_at  TIMESTAMPTZ
);

CREATE INDEX idx_scan_run_module_started ON scan_run (module, started_at DESC);

CREATE TABLE finding (
    id           UUID PRIMARY KEY,
    run_id       UUID         NOT NULL REFERENCES scan_run (id) ON DELETE CASCADE,
    rule_id      VARCHAR(64)  NOT NULL,
    title        VARCHAR(512) NOT NULL,
    severity     VARCHAR(16)  NOT NULL CHECK (severity IN ('CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'INFO')),
    decision     VARCHAR(8)   NOT NULL CHECK (decision IN ('BLOCK', 'WARN', 'PASS')),
    location     JSONB        NOT NULL CHECK (jsonb_typeof(location -> 'kind') = 'string'),
    evidence     JSONB        NOT NULL DEFAULT '{}'::jsonb,
    source       VARCHAR(16)  NOT NULL CHECK (source IN ('RULE', 'AI', 'RULE_AND_AI')),
    fingerprint  CHAR(64)     NOT NULL,
    detected_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- AI 단독 판단으로는 차단하지 않는다 (gate-common Finding 규칙과 동일)
    CONSTRAINT chk_ai_cannot_block CHECK (NOT (source = 'AI' AND decision = 'BLOCK')),
    CONSTRAINT uq_finding_run_fingerprint UNIQUE (run_id, fingerprint)
);

CREATE INDEX idx_finding_severity ON finding (severity, detected_at DESC);
CREATE INDEX idx_finding_fingerprint ON finding (fingerprint);
