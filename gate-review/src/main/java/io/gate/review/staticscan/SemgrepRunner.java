package io.gate.review.staticscan;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Semgrep을 외부 프로그램으로 실행한다 (셸 없이, 인자 배열로).
 * 측정을 재현할 수 있도록 엔진 버전이 {@link #PINNED_VERSION}과 다르면 실행하지 않는다.
 * 사용 통계 전송은 끈다({@code --metrics=off}).
 */
public final class SemgrepRunner {

    /** semgrep/requirements.txt와 같아야 한다 (테스트로 확인). */
    public static final String PINNED_VERSION = "1.182.1";
    static final long TIMEOUT_SECONDS = 300;

    private final String executable;

    public SemgrepRunner(String executable) {
        this.executable = executable;
    }

    public String version() throws IOException {
        return parseVersion(run(List.of(executable, "--version"), null));
    }

    /**
     * 새 버전 안내("A new version of Semgrep is available")가 같은 출력에 섞여도 버전 줄만 고른다
     * (2026-11-13 확인: 안내 문구 때문에 버전 비교가 틀려 실제 Semgrep 테스트가 건너뛰어졌다).
     */
    static String parseVersion(String output) {
        return output.lines().map(String::strip).filter(l -> l.matches("\\d+\\.\\d+\\.\\d+")).reduce((a, b) -> b)
                .orElse(output.strip());
    }

    /**
     * @param workDir 저장소 루트. paths와 결과 경로는 이 폴더 기준 상대 경로다
     * @param config  규칙 파일·폴더 경로 또는 레지스트리 이름(예: p/java)
     */
    public SemgrepResult scan(Path workDir, String config, List<String> paths) throws IOException {
        String version = version();
        if (!version.equals(PINNED_VERSION)) {
            throw new IOException("semgrep " + PINNED_VERSION + " is required (found " + version
                    + "). Install it with: pip install -r gate-review/semgrep/requirements.txt");
        }
        if (paths.isEmpty()) {
            return new SemgrepResult(version, List.of(), List.of());
        }
        for (String p : paths) {
            if (p.startsWith("-")) {
                throw new IllegalArgumentException("invalid path: " + p); // 옵션으로 해석되지 않게
            }
        }
        List<String> cmd = new ArrayList<>(List.of(executable, "scan", "--config", config, "--json",
                "--metrics=off", "--disable-version-check", "--quiet", "--"));
        cmd.addAll(paths);
        return SemgrepResult.parse(run(cmd, workDir));
    }

    private static String run(List<String> cmd, Path dir) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (dir != null) {
            pb.directory(dir.toFile());
        }
        pb.environment().put("PYTHONUTF8", "1"); // Windows에서 출력 인코딩 문제 방지
        pb.environment().put("SEMGREP_ENABLE_VERSION_CHECK", "0"); // 새 버전 확인(네트워크 요청·안내 문구) 끔
        Process p = pb.start();
        p.getOutputStream().close();
        CompletableFuture<byte[]> out = CompletableFuture.supplyAsync(() -> read(p.getInputStream()));
        CompletableFuture<byte[]> err = CompletableFuture.supplyAsync(() -> read(p.getErrorStream()));
        try {
            if (!p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("semgrep timed out after " + TIMEOUT_SECONDS + "s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
        String stdout = new String(out.join(), StandardCharsets.UTF_8);
        // semgrep은 결과가 있어도 0이 아닌 코드로 끝날 수 있다. JSON이 있으면 그것을 믿는다.
        if (p.exitValue() != 0 && !stdout.strip().startsWith("{") && !cmd.contains("--version")) {
            throw new IOException("semgrep failed (exit " + p.exitValue() + "): "
                    + new String(err.join(), StandardCharsets.UTF_8).strip());
        }
        return stdout;
    }

    private static byte[] read(InputStream in) {
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            in.transferTo(buf);
            return buf.toByteArray();
        } catch (IOException e) {
            return new byte[0];
        }
    }
}
