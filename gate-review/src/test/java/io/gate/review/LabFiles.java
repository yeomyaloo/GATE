package io.gate.review;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/** 테스트용: 실습장(gate-lab) 실제 소스를 저장소 기준 경로로 읽는다. */
public final class LabFiles {

    public static final Path ROOT = Path.of("..").toAbsolutePath().normalize();

    private LabFiles() {
    }

    public static List<SourceFile> all() {
        try (Stream<Path> s = Files.walk(ROOT.resolve("gate-lab/src/main/java"))) {
            // 4단계 측정 범위만 (5단계 주문 도우미 제외, MeasureCommand.inScope와 같은 기준)
            return s.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.toString().replace('\\', '/').contains("/io/gate/lab/assistant/"))
                    .sorted().map(LabFiles::read).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static SourceFile read(Path p) {
        try {
            return new SourceFile(ROOT.relativize(p).toString(), Files.readString(p, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static String fixture(String name) {
        try {
            return Files.readString(Path.of("src/test/resources/fixtures", name), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
