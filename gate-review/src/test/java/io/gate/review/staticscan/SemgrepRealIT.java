package io.gate.review.staticscan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.gate.review.LabFiles;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 실제 semgrep(고정 버전)을 실습장 코드에 실행한다. 실행 파일은 시스템 속성 gate.semgrep(환경 변수 GATE_SEMGREP).
 * 로컬에 없으면 건너뛰지만, CI는 이 테스트가 건너뛰어지면 실패로 처리한다.
 */
class SemgrepRealIT {

    static SemgrepRunner runner;

    @BeforeAll
    static void requireSemgrep() {
        runner = new SemgrepRunner(System.getProperty("gate.semgrep", "semgrep"));
        boolean available;
        try {
            available = runner.version().equals(SemgrepRunner.PINNED_VERSION);
        } catch (Exception e) {
            available = false;
        }
        assumeTrue(available, "semgrep " + SemgrepRunner.PINNED_VERSION + " not available");
    }

    @Test
    void labScanMatchesTheRecordedFixture() throws Exception {
        List<String> paths = LabFiles.all().stream().map(f -> f.path()).toList();
        SemgrepResult live = runner.scan(LabFiles.ROOT, "gate-review/semgrep/rules/gate-java.yml", paths);
        SemgrepResult recorded = SemgrepResult.parse(LabFiles.fixture("semgrep-lab.json"));
        assertEquals(key(recorded.hits()), key(live.hits()), "fixture is stale: re-record semgrep-lab.json");
        assertEquals(List.of(), live.errors());
    }

    static Map<String, List<Integer>> key(List<StaticHit> hits) {
        return hits.stream().collect(Collectors.groupingBy(h -> h.ruleId() + " " + h.path(),
                java.util.TreeMap::new, Collectors.mapping(StaticHit::startLine, Collectors.toList())));
    }
}
