package io.gate.pkg.manifest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * git을 셸 없이 실행해 두 ref 사이의 파일을 읽는다.
 * ref는 실행 전에 형식을 검증한다. {@code -}로 시작하는 값은 git 옵션으로 해석될 수 있으므로 거부한다(옵션 주입 방지).
 */
public final class GitReader {

    private static final Pattern REF = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._/~^@{}-]{0,199}$");
    private static final long TIMEOUT_SECONDS = 30;

    private final Path repo;

    public GitReader(Path repo) {
        this.repo = repo;
    }

    public static String requireRef(String ref) {
        if (ref == null || !REF.matcher(ref).matches() || ref.contains("..")) {
            throw new IllegalArgumentException("invalid git ref: " + ref);
        }
        return ref;
    }

    /** base와 head 사이에 바뀐 파일 경로 (저장소 루트 기준, 슬래시 구분). */
    public List<String> changedFiles(String base, String head) throws IOException {
        String out = run("diff", "--name-only", "--no-renames", requireRef(base), requireRef(head), "--");
        List<String> files = new ArrayList<>();
        for (String line : out.split("\\R")) {
            if (!line.isBlank()) {
                files.add(line.strip());
            }
        }
        return files;
    }

    /** ref 시점의 파일 내용. 그 시점에 파일이 없으면 빈 값. */
    public Optional<String> show(String ref, String path) throws IOException {
        if (path.startsWith("-") || path.contains("..")) {
            throw new IllegalArgumentException("invalid path: " + path);
        }
        try {
            return Optional.of(run("show", requireRef(ref) + ":" + path));
        } catch (GitException e) {
            return Optional.empty();
        }
    }

    /** git이 0이 아닌 코드로 끝났을 때. */
    public static final class GitException extends IOException {
        GitException(String message) {
            super(message);
        }
    }

    private String run(String... args) throws IOException {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-c");
        command.add("core.quotepath=off");
        command.addAll(Arrays.asList(args));
        Process p = new ProcessBuilder(command).directory(repo.toFile()).redirectErrorStream(false).start();
        p.getOutputStream().close();
        byte[] out = readAll(p.getInputStream());
        byte[] err = readAll(p.getErrorStream());
        try {
            if (!p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("git timed out: " + String.join(" ", args));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
        if (p.exitValue() != 0) {
            throw new GitException("git " + args[0] + " failed: " + new String(err, StandardCharsets.UTF_8).strip());
        }
        return new String(out, StandardCharsets.UTF_8);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        in.transferTo(buf);
        return buf.toByteArray();
    }
}
