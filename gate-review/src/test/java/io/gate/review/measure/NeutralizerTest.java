package io.gate.review.measure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gate.review.LabFiles;
import io.gate.review.SourceFile;
import java.util.List;
import org.junit.jupiter.api.Test;

class NeutralizerTest {

    static final List<SourceFile> LAB = LabFiles.all();

    @Test
    void eachVariantIsReviewedAloneUnderTheSameNeutralName() {
        for (String variant : List.of("vulnerable", "fixed")) {
            Neutralizer.Neutralized n = Neutralizer.prepare(LAB, variant);
            assertEquals(List.of(), Neutralizer.leaks(n.files()), variant);
            String other = variant.equals("vulnerable") ? "/fixed/" : "/vulnerable/";
            assertTrue(n.files().stream().noneMatch(f -> n.original(f.path()).contains(other)),
                    "the other variant is not shown");
            assertTrue(n.files().stream().anyMatch(f -> f.path().contains("/io/gate/lab/app/")));
            assertTrue(n.files().stream().anyMatch(f -> f.path().contains("/common/")), "shared code included");
        }
        // 두 버전 모두 같은 중립 패키지(app)를 쓴다. 파일 구성은 버전마다 다를 수 있다
        // (뚫린 버전에만 BearerTokenFilter가 있다) - 이름으로는 어느 쪽인지 알 수 없다
        for (String variant : List.of("vulnerable", "fixed")) {
            assertTrue(Neutralizer.prepare(LAB, variant).files().stream()
                    .filter(f -> !f.path().contains("/common/") && !f.path().endsWith("LabApplication.java"))
                    .allMatch(f -> f.path().contains("/io/gate/lab/app/")), variant);
        }
    }

    @Test
    void lineNumbersArePreservedSoGroundTruthStillApplies() {
        Neutralizer.Neutralized n = Neutralizer.prepare(LAB, "vulnerable");
        for (SourceFile f : n.files()) {
            SourceFile original = LAB.stream().filter(o -> o.path().equals(n.original(f.path()))).findFirst()
                    .orElseThrow();
            assertEquals(original.lines().size(), f.lines().size(), f.path());
        }
        SourceFile search = n.files().stream().filter(f -> f.path().endsWith("app/ProductSearchController.java"))
                .findFirst().orElseThrow();
        assertTrue(search.line(24).contains("name ILIKE '%\" + q + \"%' "), "ground-truth anchor at the same line");
        assertFalse(search.content().contains("vulnerable"));
    }

    @Test
    void unknownVariantIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> Neutralizer.prepare(LAB, "both"));
    }
}
