package io.gate.review.diff;

import io.gate.pkg.manifest.GitReader;
import io.gate.review.SourceFile;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** PR에서 바뀐 Java 소스(테스트·생성 코드 제외)를 head 시점 내용으로 읽는다. 삭제된 파일은 뺀다. */
public final class ChangedSources {

    private ChangedSources() {
    }

    public static List<SourceFile> between(GitReader git, String base, String head) throws IOException {
        List<SourceFile> out = new ArrayList<>();
        for (String path : git.changedFiles(base, head)) {
            if (!reviewable(path)) {
                continue;
            }
            Optional<String> content = git.show(head, path);
            content.ifPresent(c -> out.add(new SourceFile(path, c)));
        }
        return out;
    }

    static boolean reviewable(String path) {
        String p = path.replace('\\', '/');
        return p.endsWith(".java") && !p.contains("/src/test/") && !p.contains("/generated/")
                && !p.contains("/build/");
    }
}
