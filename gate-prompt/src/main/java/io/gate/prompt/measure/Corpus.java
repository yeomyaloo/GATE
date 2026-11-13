package io.gate.prompt.measure;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 측정용 공격·정상 사례 묶음 ({@code corpus.json}, 이 프로젝트에서 직접 작성). */
public record Corpus(List<Case> cases) {

    public enum Feature { SQL, HTML, ACTION }

    public enum Kind { ATTACK, BENIGN }

    /**
     * @param input        SQL·동작 기능의 사용자 입력
     * @param reviews      HTML 기능이 요약하는 리뷰들 (다른 사용자가 쓴 데이터)
     * @param mustContain  정상 HTML 사례: 결과에 남아 있어야 하는 내용 (엔티티를 푼 뒤 비교)
     * @param expectAction 정상 동작 사례: 실행되어야 하는 동작
     */
    public record Case(String id, Feature feature, Kind kind, String technique, String input, List<String> reviews,
            List<String> mustContain, String expectAction) {

        /** 입력 검사가 보는 글 전체. */
        public String inspectedText() {
            return feature == Feature.HTML ? String.join("\n", reviews) : input;
        }
    }

    public Corpus {
        cases = List.copyOf(cases);
    }

    public static Corpus load() {
        try (InputStream in = Corpus.class.getResourceAsStream("corpus.json")) {
            if (in == null) {
                throw new IllegalStateException("corpus.json not found");
            }
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static Corpus parse(String json) {
        JsonNode root = JsonMapper.builder().build().readTree(json);
        List<Case> out = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (JsonNode c : root.path("cases")) {
            String id = c.path("id").asString();
            if (!ids.add(id)) {
                throw new IllegalArgumentException("duplicate case id " + id);
            }
            Feature f = Feature.valueOf(c.path("feature").asString().toUpperCase(java.util.Locale.ROOT));
            Kind k = Kind.valueOf(c.path("kind").asString().toUpperCase(java.util.Locale.ROOT));
            List<String> reviews = strings(c.path("reviews"));
            String input = c.has("input") ? c.path("input").asString() : null;
            if (f == Feature.HTML ? reviews.isEmpty() : input == null) {
                throw new IllegalArgumentException("case " + id + " has no input");
            }
            String expect = c.has("expectAction") ? c.path("expectAction").asString() : null;
            if (k == Kind.BENIGN && f == Feature.ACTION && expect == null) {
                throw new IllegalArgumentException("benign action case " + id + " needs expectAction");
            }
            out.add(new Case(id, f, k, c.path("technique").asString(), input, reviews, strings(c.path("mustContain")),
                    expect));
        }
        return new Corpus(out);
    }

    private static List<String> strings(JsonNode arr) {
        List<String> out = new ArrayList<>();
        arr.forEach(n -> out.add(n.asString()));
        return out;
    }
}
