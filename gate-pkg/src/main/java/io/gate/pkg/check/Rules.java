package io.gate.pkg.check;

import io.gate.common.finding.Decision;
import io.gate.common.finding.Finding;
import io.gate.common.finding.FindingSource;
import io.gate.common.finding.Location;
import io.gate.common.finding.Severity;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** gate-pkg 규칙 ID와 결과 생성 도우미. */
public final class Rules {

    public static final String EXIST = "PKG-EXIST";
    public static final String NEW = "PKG-NEW";
    public static final String SCRIPT = "PKG-SCRIPT";
    public static final String POP = "PKG-POP";
    public static final String UNCHECKED = "PKG-UNCHECKED";

    private Rules() {
    }

    /** 규칙 기반 결과 하나를 만든다. gate-pkg의 판정은 모두 규칙(RULE)에서 나온다. */
    public static Finding finding(UUID runId, String ruleId, String title, Severity severity, Decision decision,
            Location location, Map<String, Object> evidence, Instant now) {
        return new Finding(UUID.randomUUID(), runId, ruleId, title, severity, decision, location, evidence,
                FindingSource.RULE, now);
    }
}
