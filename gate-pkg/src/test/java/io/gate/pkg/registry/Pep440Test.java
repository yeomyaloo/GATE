package io.gate.pkg.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** PEP 440 문서의 규칙을 이 프로젝트의 사례로 다시 쓴 테스트. */
class Pep440Test {

    static boolean contains(String spec, String version) {
        return Pep440.parse(spec).orElseThrow().contains(version);
    }

    @ParameterizedTest(name = "{0} contains {1}: {2}")
    @CsvSource(delimiter = '|', textBlock = """
            '>=2.0,<3'      | 2.31.0      | true
            '>=2.0,<3'      | 3.0         | false
            '>=2.0,<3'      | 1.9         | false
            ~=2.1           | 2.9         | true
            ~=2.1           | 3.0         | false
            ~=2.1.3         | 2.1.9       | true
            ~=2.1.3         | 2.2.0       | false
            ==2.*           | 2.7.1       | true
            ==2.*           | 3.0         | false
            '!=2.5.*'       | 2.5.4       | false
            '!=2.5.*'       | 2.6.0       | true
            ==1.0           | 1.0.0       | true
            ==1.0           | 1.0+local.7 | true
            ==1.0+local.7   | 1.0         | false
            '!=1.0'         | 1.0.0       | false
            <=2.0           | 2.0         | true
            >1.0            | 1.0.post1   | false
            >1.0.post1      | 1.0.post2   | true
            >1.0            | 1.0.1       | true
            <2.0            | 2.0rc1      | false
            <2.0rc2         | 2.0rc1      | true
            ===1.0-custom   | 1.0-custom  | true
            ''              | 9.9         | true
            (>=2.0)         | 2.1         | true
            """)
    void specifiersFollowPep440(String spec, String version, boolean expected) {
        assertEquals(expected, contains(spec, version));
    }

    @Test
    void versionOrderingIncludesAliasesAndSeparators() {
        List<String> sorted = List.of("1.0.dev0", "1.0a1", "1.0-alpha-2", "1.0b1", "1.0c1", "1.0rc2", "1.0",
                "1.0.post1", "1.0-2", "1.1.dev1", "1.1", "1!0.1");
        for (int i = 1; i < sorted.size(); i++) {
            Pep440.Version a = Pep440.Version.parse(sorted.get(i - 1)).orElseThrow();
            Pep440.Version b = Pep440.Version.parse(sorted.get(i)).orElseThrow();
            assertTrue(a.compareTo(b) < 0, sorted.get(i - 1) + " < " + sorted.get(i));
        }
        assertEquals(0, Pep440.Version.parse("1.0").orElseThrow().compareTo(Pep440.Version.parse("1.0.0").orElseThrow()));
        assertEquals(0, Pep440.Version.parse("1.0RC1").orElseThrow().compareTo(Pep440.Version.parse("1.0rc1")
                .orElseThrow()), "case-insensitive");
    }

    @Test
    void pipPicksTheHighestFinalReleaseUnlessPrereleasesAreAsked() {
        List<String> releases = List.of("2.30.0", "2.31.0", "2.32.0rc1", "3.0.0a1");
        assertEquals(Optional.of("2.31.0"), pick(">=2.0,<3", releases, Set.of()));
        assertEquals(Optional.of("2.32.0rc1"), pick(">=2.32.0rc1,<3", releases, Set.of()), "spec names a prerelease");
        assertEquals(Optional.of("3.0.0a1"), pick(">2.31.0", releases, Set.of()), "only prereleases match");
        assertEquals(Optional.empty(), pick(">=3", releases, Set.of()), "3.0.0a1 is below 3 in PEP 440");
        assertEquals(Optional.empty(), pick(">=9", releases, Set.of()));
    }

    @Test
    void yankedReleasesAreSkippedUnlessPinned() {
        List<String> releases = List.of("1.0", "1.1");
        assertEquals(Optional.of("1.0"), pick(">=1.0", releases, Set.of("1.1")));
        assertEquals(Optional.of("1.1"), pick("==1.1", releases, Set.of("1.1")), "PEP 592: an exact pin still works");
    }

    @ParameterizedTest
    @ValueSource(strings = {">=", "~=2", ">=2.*", "latest", "^1.2", "git+https://x.invalid/a.git", ">=1.0 ; os_name"})
    void unsupportedSpecifiersAreNotGuessed(String spec) {
        assertTrue(Pep440.parse(spec).isEmpty(), spec);
    }

    static Optional<String> pick(String spec, List<String> releases, Set<String> yanked) {
        return Pep440.pick(Pep440.parse(spec).orElseThrow(), releases, yanked);
    }
}
