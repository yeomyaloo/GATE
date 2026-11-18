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

/** npm 문서에 적힌 범위 규칙을 이 프로젝트의 사례로 다시 쓴 테스트 (node-semver 테스트를 옮기지 않음). */
class NpmSemverTest {

    static boolean satisfies(String version, String range) {
        return NpmSemver.parse(range).orElseThrow().test(NpmSemver.Version.parse(version).orElseThrow());
    }

    @ParameterizedTest(name = "{1} matches {0}: {2}")
    @CsvSource(delimiter = '|', textBlock = """
            4.21.2      | ^4.21.2         | true
            4.99.0      | ^4.21.2         | true
            5.0.0       | ^4.21.2         | false
            4.21.1      | ^4.21.2         | false
            0.2.9       | ^0.2.3          | true
            0.3.0       | ^0.2.3          | false
            0.0.3       | ^0.0.3          | true
            0.0.4       | ^0.0.3          | false
            0.0.9       | ^0.0            | true
            0.1.0       | ^0.0            | false
            0.9.0       | ^0.x            | true
            1.0.0       | ^0.x            | false
            1.2.9       | ~1.2.3          | true
            1.3.0       | ~1.2.3          | false
            1.9.0       | ~1              | true
            2.0.0       | ~1              | false
            1.2.7       | 1.2.x           | true
            1.3.0       | 1.2.x           | false
            9.9.9       | *               | true
            9.9.9       | ''              | true
            1.2.0       | 1.2             | true
            1.3.0       | 1.2             | false
            1.2.3       | 1.2.3           | true
            1.2.4       | 1.2.3           | false
            1.2.3       | v1.2.3          | true
            2.3.9       | 1.2.3 - 2.3     | true
            2.4.0       | 1.2.3 - 2.3     | false
            2.3.4       | 1.2 - 2.3.4     | true
            2.3.5       | 1.2 - 2.3.4     | false
            2.0.0       | >1              | true
            1.9.9       | >1              | false
            1.1.9       | <1.2            | true
            1.2.0       | <1.2            | false
            1.2.9       | <=1.2           | true
            1.3.0       | <=1.2           | false
            2.5.0       | >=2.0.0 <3.0.0  | true
            3.0.0       | >=2.0.0 <3.0.0  | false
            2.5.0       | >= 2.0.0 < 3    | true
            """)
    void rangesFollowNpmRules(String version, String range, boolean expected) {
        assertEquals(expected, satisfies(version, range));
    }

    @Test
    void orCombinesRanges() {
        assertTrue(satisfies("1.5.0", "^1.0.0 || ^3.0.0"));
        assertEquals(false, satisfies("2.5.0", "^1.0.0 || ^3.0.0"));
        assertTrue(satisfies("3.1.0", "^1.0.0 || ^3.0.0"));
        assertTrue(satisfies("0.1.0", "<1 || >=5"));
    }

    @ParameterizedTest(name = "{1} matches {0}: {2}")
    @CsvSource(delimiter = '|', textBlock = """
            2.0.0-beta.1   | ^1.2.3               | false
            1.3.0-beta.1   | ^1.2.3               | false
            1.2.3-beta.3   | ^1.2.3-beta.2        | true
            1.2.4-beta.1   | ^1.2.3-beta.2        | false
            1.2.3-beta.10  | >1.2.3-beta.2        | true
            1.2.3-beta.2   | >1.2.3-beta.10       | false
            1.2.3-alpha    | >=1.2.3-0            | true
            1.2.3          | ^1.2.3-beta.2        | true
            """)
    void prereleasesOnlyMatchWhenTheRangeNamesTheSameTuple(String version, String range, boolean expected) {
        assertEquals(expected, satisfies(version, range));
    }

    @ParameterizedTest
    @ValueSource(strings = {"git+https://github.com/a/b.git", "github:user/repo", "file:../lib", "npm:other@^1",
        "workspace:*", "https://example.invalid/x.tgz", "user/repo", "not a range!", ">>1"})
    void unsupportedSyntaxIsNotGuessed(String spec) {
        assertTrue(NpmSemver.parse(spec).isEmpty(), spec);
    }

    @Test
    void npmPrefersLatestWhenItSatisfies() {
        List<String> versions = List.of("4.21.2", "4.22.0", "5.0.0", "5.1.0-beta.1");
        assertEquals(Optional.of("4.22.0"), pick("^4.21.2", versions, "5.0.0"));
        assertEquals(Optional.of("5.0.0"), pick(">=4", versions, "5.0.0"), "latest wins even if 4.x also matches");
        assertEquals(Optional.of("4.22.0"), pick("^4", versions, "4.21.2-not-latest-tag"));
        assertEquals(Optional.empty(), pick("^9.0.0", versions, "5.0.0"), "nothing satisfies");
        assertEquals(Optional.of("5.1.0-beta.1"), pick("^5.1.0-beta.0", versions, "5.0.0"));
    }

    @Test
    void nonDeprecatedVersionsArePreferred() {
        assertEquals(Optional.of("1.1.0"), NpmSemver.pick(NpmSemver.parse("^1").orElseThrow(),
                List.of("1.1.0", "1.2.0"), null, Set.of("1.2.0")));
        assertEquals(Optional.of("1.2.0"), NpmSemver.pick(NpmSemver.parse("^1.2").orElseThrow(),
                List.of("1.1.0", "1.2.0"), null, Set.of("1.2.0")), "deprecated is still chosen when it is the only match");
    }

    @Test
    void versionOrdering() {
        List<String> sorted = List.of("1.0.0-0", "1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta.2",
                "1.0.0-beta.11", "1.0.0", "1.0.1", "1.10.0", "2.0.0");
        for (int i = 1; i < sorted.size(); i++) {
            NpmSemver.Version a = NpmSemver.Version.parse(sorted.get(i - 1)).orElseThrow();
            NpmSemver.Version b = NpmSemver.Version.parse(sorted.get(i)).orElseThrow();
            assertTrue(a.compareTo(b) < 0, a + " < " + b);
        }
    }

    static Optional<String> pick(String range, List<String> versions, String latest) {
        return NpmSemver.pick(NpmSemver.parse(range).orElseThrow(), versions, latest, Set.of());
    }
}
