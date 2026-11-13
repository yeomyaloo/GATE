package io.gate.review.measure;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 실습장 정답지({@code docs/lab/ground-truth.json}, 2단계에서 작성).
 *
 * @param vulnerabilities 정답 취약점 (모두 vulnerable 버전에 있다)
 * @param expectedClean   지적이 없어야 하는 파일 (고친 버전·공통 코드)
 */
public record GroundTruth(List<Vulnerability> vulnerabilities, List<String> expectedClean) {

    public record Vulnerability(String id, String title, String cwe, String file, int startLine, int endLine) {
    }

    public GroundTruth {
        vulnerabilities = List.copyOf(vulnerabilities);
        expectedClean = List.copyOf(expectedClean);
    }

    public static GroundTruth parse(String json) {
        JsonNode root = JsonMapper.builder().build().readTree(json);
        List<Vulnerability> vulns = new ArrayList<>();
        for (JsonNode v : root.path("vulnerabilities")) {
            JsonNode lines = v.path("lines");
            if (!lines.isArray() || lines.size() != 2) {
                throw new IllegalArgumentException("ground truth entry needs lines [start, end]: " + v.path("id"));
            }
            vulns.add(new Vulnerability(v.path("id").asString(), v.path("title").asString(), v.path("cwe").asString(),
                    v.path("file").asString(), lines.get(0).asInt(), lines.get(1).asInt()));
        }
        List<String> clean = new ArrayList<>();
        root.path("expectedClean").forEach(n -> clean.add(n.asString()));
        if (vulns.isEmpty()) {
            throw new IllegalArgumentException("ground truth has no vulnerabilities");
        }
        return new GroundTruth(vulns, clean);
    }
}
