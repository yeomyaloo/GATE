package io.gate.review.staticscan;

import io.gate.review.SourceFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 메모리에 있는 파일 내용(예: PR head 시점, 이름을 중립화한 실습장 코드)을 임시 폴더에 같은 경로로 써서
 * Semgrep을 돌리고 지운다. 작업 트리와 상관없이 넘긴 내용 그대로 검사한다.
 */
public final class ScanWorkspace {

    private ScanWorkspace() {
    }

    public static SemgrepResult scan(SemgrepRunner semgrep, String config, List<SourceFile> files) throws IOException {
        Path tmp = Files.createTempDirectory("gate-review-");
        try {
            // semgrep은 git 저장소가 아닌 폴더에서 멈출 수 있다(Windows에서 확인: 60초 넘게 무응답 → git init 후 5초)
            initGitRepo(tmp);
            for (SourceFile f : files) {
                Path p = tmp.resolve(f.path()).normalize();
                if (!p.startsWith(tmp)) {
                    throw new IllegalArgumentException("path escapes the workspace: " + f.path());
                }
                Files.createDirectories(p.getParent());
                Files.writeString(p, f.content(), StandardCharsets.UTF_8);
            }
            return semgrep.scan(tmp, config, files.stream().map(SourceFile::path).toList());
        } finally {
            deleteTree(tmp);
        }
    }

    private static void initGitRepo(Path dir) throws IOException {
        Process p = new ProcessBuilder("git", "init", "-q").directory(dir.toFile()).redirectErrorStream(true).start();
        try {
            p.getInputStream().readAllBytes();
            if (!p.waitFor(30, TimeUnit.SECONDS) || p.exitValue() != 0) {
                throw new IOException("git init failed in the review workspace");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }

    private static void deleteTree(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException e) {
            // 임시 폴더 정리 실패는 결과에 영향이 없다
        }
    }
}
