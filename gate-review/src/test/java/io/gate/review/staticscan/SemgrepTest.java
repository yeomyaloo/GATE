package io.gate.review.staticscan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gate.review.LabFiles;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class SemgrepTest {

    @Test
    void parsesRecordedLabOutput() {
        SemgrepResult r = SemgrepResult.parse(LabFiles.fixture("semgrep-lab.json"));
        assertEquals("1.182.1", r.version());
        assertEquals(6, r.hits().size());
        assertTrue(r.errors().isEmpty());
        StaticHit sql = r.hits().stream().filter(h -> h.ruleId().equals("gate-java-sql-string-concat")).findFirst()
                .orElseThrow();
        assertEquals("gate-lab/src/main/java/io/gate/lab/vulnerable/ProductSearchController.java", sql.path(),
                "Windows backslashes are normalized and the rule-file prefix is removed from the id");
        assertEquals("CWE-89", sql.cwe());
        assertEquals("ERROR", sql.severity());
    }

    /** 정적 규칙만으로: 뚫린 버전에서 V2·V3·V4, 고친 버전 0건, IDOR(V1)는 못 찾는다. 4단계 측정의 출발점. */
    @Test
    void staticRulesCoverSqlJwtAndErrorsButNotIdor() {
        List<StaticHit> hits = SemgrepResult.parse(LabFiles.fixture("semgrep-lab.json")).hits();
        Set<String> files = hits.stream().map(h -> h.path().substring(h.path().lastIndexOf('/') + 1))
                .collect(Collectors.toSet());
        assertEquals(Set.of("ProductSearchController.java", "BearerTokenFilter.java", "ApiErrorHandler.java"), files);
        assertTrue(hits.stream().allMatch(h -> h.path().contains("/vulnerable/")), "nothing in the fixed variant");
    }

    @Test
    void pinnedVersionMatchesRequirementsFile() throws Exception {
        String req = Files.readString(LabFiles.ROOT.resolve("gate-review/semgrep/requirements.txt"));
        assertTrue(req.contains("semgrep==" + SemgrepRunner.PINNED_VERSION), req);
    }
}
