package io.gate.lab;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * docs/lab/ground-truth.json(4단계 측정의 정답지)이 실제 코드와 어긋나지 않았는지 확인한다.
 * 취약 코드에는 표시 주석을 달지 않으므로, 위치가 틀어지면 이 테스트만이 알려 준다.
 */
class GroundTruthTest {

    static final Path ROOT = Path.of("..").toAbsolutePath().normalize();

    @Test
    void everyAnchorIsInsideItsLineRange() throws Exception {
        JsonNode truth = JsonMapper.builder().build()
                .readTree(Files.readString(ROOT.resolve("docs/lab/ground-truth.json"), StandardCharsets.UTF_8));
        assertThat(truth.path("vulnerabilities").size()).isEqualTo(4);
        for (JsonNode v : truth.path("vulnerabilities")) {
            Path file = ROOT.resolve(v.path("file").asString());
            assertThat(file).as(v.path("id").asString()).exists();
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            int start = v.path("lines").get(0).asInt();
            int end = v.path("lines").get(1).asInt();
            assertThat(end).isLessThanOrEqualTo(lines.size());
            String range = String.join("\n", lines.subList(start - 1, end));
            assertThat(range).as(v.path("id").asString() + " anchor").contains(v.path("anchor").asString());
            assertThat(ROOT.resolve(v.path("fixedIn").asString())).exists();
        }
        for (JsonNode clean : truth.path("expectedClean")) {
            assertThat(ROOT.resolve(clean.asString())).exists();
        }
    }

    /**
     * 주석·문자열·코드 본문에 답을 흘리지 않는지 확인한다.
     * 패키지 이름({@code io.gate.lab.vulnerable})과 {@code @Profile("vulnerable")}은 사람이 비교하기 쉽도록 남겨 두므로
     * 검사에서 뺀다. 4단계에서 리뷰어에게 코드를 줄 때는 경로·프로필 이름을 중립적으로 바꿔야 한다.
     */
    @Test
    void vulnerableCodeCarriesNoMarkerComments() throws Exception {
        try (var files = Files.walk(ROOT.resolve("gate-lab/src/main/java/io/gate/lab/vulnerable"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String text = Files.readString(f, StandardCharsets.UTF_8).toLowerCase()
                        .replaceAll("(?m)^(package|import) [^;]+;", "")
                        .replace("@profile(\"vulnerable\")", "");
                assertThat(text).as(f.getFileName() + " must not reveal the answer to reviewers")
                        .doesNotContain("vulnerab").doesNotContain("취약").doesNotContain("cwe-")
                        .doesNotContain("insecure").doesNotContain("injection");
            }
        }
    }
}
