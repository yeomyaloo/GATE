package io.gate.pkg.check;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gate.pkg.registry.Ecosystem;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** 저장소에 들어 있는 실제 인기 목록 스냅샷으로 검사한다. */
class TypoDetectorTest {

    static final TypoDetector NPM = TypoDetector.load(Ecosystem.NPM);
    static final TypoDetector PYPI = TypoDetector.load(Ecosystem.PYPI);

    @Test
    void snapshotsAreLoaded() {
        assertTrue(NPM.size() >= 4000, "npm list size " + NPM.size());
        assertTrue(PYPI.size() >= 4000, "pypi list size " + PYPI.size());
        assertTrue(NPM.isPopular("lodash") && NPM.isPopular("@types/node") && NPM.isPopular("react-dom"));
        assertTrue(PYPI.isPopular("requests") && PYPI.isPopular("Flask_SQLAlchemy"));
    }

    // 설계안 9장의 필수 케이스: lodahs, reqeusts, react_dom, types-node는 걸려야 한다
    @ParameterizedTest
    @CsvSource({
        "lodahs, lodash, edit-distance",
        "react_dom, react-dom, separator",
        "reactdom, react-dom, separator",
        "types-node, @types/node, scope",
        "@evil-corp/lodash, lodash, scope",
        "rnoment, moment, homoglyph",
        "express-js, express, affix",
        "node-axios, axios, affix",
    })
    void npmLookAlikes(String name, String target, String technique) {
        TypoDetector.TypoMatch m = NPM.find(name).orElseThrow(() -> new AssertionError("no match for " + name));
        assertEquals(target, m.target());
        if (!technique.equals("affix")) {
            assertEquals(technique, m.technique());
        }
    }

    @ParameterizedTest
    @CsvSource({"reqeusts, requests", "requets, requests", "python-requests, requests", "numpyy, numpy",
        "djang0, django"})
    void pypiLookAlikes(String name, String target) {
        assertEquals(target, PYPI.find(name).orElseThrow(() -> new AssertionError(name)).target());
    }

    @ParameterizedTest
    @ValueSource(strings = {"lodash", "preact", "react", "express", "@types/node", "my-company-internal-tool-xyz"})
    void popularOrUnrelatedNamesDoNotMatch(String name) {
        assertTrue(NPM.find(name).isEmpty(), () -> name + " -> " + NPM.find(name));
    }

    @Test
    void pypiNormalizationMeansSeparatorVariantsAreTheSameProject() {
        // pip는 Flask_SQLAlchemy와 flask-sqlalchemy를 같은 프로젝트로 본다. 흉내가 아니다.
        assertTrue(PYPI.find("Flask_SQLAlchemy").isEmpty());
    }

    @Test
    void shortNamesSkipEditDistance() {
        TypoDetector d = new TypoDetector(Ecosystem.NPM, List.of("vue", "ms", "abcde"));
        assertTrue(d.find("vua").isEmpty(), "names of 4 chars or fewer are too collision-prone");
        assertEquals("abcde", d.find("abcdf").orElseThrow().target());
    }

    @Test
    void tiesGoToTheMorePopularTarget() {
        TypoDetector d = new TypoDetector(Ecosystem.NPM, List.of("color", "colors"));
        assertEquals("color", d.find("colorx").orElseThrow().target());
        assertEquals(0, d.find("colorx").orElseThrow().rank());
    }

    @ParameterizedTest
    @CsvSource({"lodash, lodahs, 1", "lodash, lodash, 0", "lodash, ldash, 1", "requests, reqeusts, 1",
        "abc, xyz, 3", "kitten, sitting, 3"})
    void osaDistance(String a, String b, int expected) {
        assertEquals(expected, TypoDetector.distance(a, b, 10));
    }

    @Test
    void distanceStopsEarlyAboveMax() {
        assertEquals(2, TypoDetector.distance("aaaaaaaa", "bbbbbbbb", 1));
    }
}
