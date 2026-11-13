package io.gate.review.measure;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.gate.review.LabFiles;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScorerTest {

    static final GroundTruth TRUTH;

    static {
        try {
            TRUTH = GroundTruth.parse(Files.readString(LabFiles.ROOT.resolve("docs/lab/ground-truth.json")));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    static final String SEARCH = "gate-lab/src/main/java/io/gate/lab/vulnerable/ProductSearchController.java";
    static final String ORDERS = "gate-lab/src/main/java/io/gate/lab/vulnerable/OrderDetailController.java";
    static final String FIXED_SEARCH = "gate-lab/src/main/java/io/gate/lab/fixed/ProductSearchController.java";

    static Flag flag(String path, int line, String cwe) {
        return new Flag(path, line, line, cwe, "t");
    }

    @Test
    void groundTruthIsReadCompletely() {
        assertEquals(List.of("V1", "V2", "V3", "V4"),
                TRUTH.vulnerabilities().stream().map(GroundTruth.Vulnerability::id).toList());
        assertEquals(5, TRUTH.expectedClean().size());
    }

    @Test
    void rightFileRangeAndKindIsADetectionAndRepeatsAreDuplicates() {
        Scorer.Score s = Scorer.score(TRUTH.vulnerabilities(), TRUTH.expectedClean(),
                List.of(flag(SEARCH, 25, "CWE-89"), flag(SEARCH, 26, "CWE-564"), flag(ORDERS, 30, "CWE-862")));
        assertEquals(List.of("V2", "V1"), s.detected(), "CWE family counts: 564 ~ 89, 862 ~ 639");
        assertEquals(List.of("V3", "V4"), s.missed());
        assertEquals(1, s.duplicates());
        assertEquals(List.of(), s.falsePositives());
    }

    @Test
    void wrongKindOutsideRangeOrUnknownCweIsAFalsePositive() {
        Scorer.Score s = Scorer.score(TRUTH.vulnerabilities(), TRUTH.expectedClean(),
                List.of(flag(SEARCH, 25, "CWE-79"), flag(SEARCH, 60, "CWE-89"), flag(SEARCH, 25, null)));
        assertEquals(List.of(), s.detected());
        assertEquals(3, s.falsePositives().size());
    }

    @Test
    void everythingOnTheFixedVariantIsAFalsePositiveAndCleanFilesAreCounted() {
        Scorer.Score s = Scorer.score(List.of(), TRUTH.expectedClean(), List.of(flag(FIXED_SEARCH, 25, "CWE-89")));
        assertEquals(1, s.falsePositives().size());
        assertEquals(1, s.fpOnCleanFiles());
        assertEquals(List.of(), s.missed());
    }
}
