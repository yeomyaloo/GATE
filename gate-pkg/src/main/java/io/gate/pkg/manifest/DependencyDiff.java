package io.gate.pkg.manifest;

import io.gate.pkg.registry.PackageRequest;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 두 커밋 사이에서 새로 추가되거나 지정자가 바뀐 직접 의존성을 찾는다. */
public final class DependencyDiff {

    /** 바뀐 의존성 하나와 그것이 적힌 파일·줄 (GitHub 주석 위치). */
    public record Change(PackageRequest request, String file, int line, String before, String after) {
    }

    public record Result(List<Change> changes, List<String> unchecked, List<String> manifests) {
    }

    private DependencyDiff() {
    }

    public static Result between(GitReader git, String base, String head) throws IOException {
        List<Change> changes = new ArrayList<>();
        List<String> unchecked = new ArrayList<>();
        List<String> manifests = new ArrayList<>();
        Map<String, Boolean> seen = new LinkedHashMap<>();
        for (String path : git.changedFiles(base, head)) {
            if (!ManifestParser.isManifest(path)) {
                continue;
            }
            Optional<String> after = git.show(head, path);
            if (after.isEmpty()) {
                continue; // 삭제된 파일: 새로 설치되는 것이 없다
            }
            manifests.add(path);
            ManifestParser.Parsed before = ManifestParser.parse(path, git.show(base, path).orElse(""));
            ManifestParser.Parsed now = ManifestParser.parse(path, after.get());
            List<String> oldProblems = before.unchecked();
            now.unchecked().stream().filter(p -> !oldProblems.contains(p)).forEach(unchecked::add);
            for (ManifestParser.Dependency dep : now.dependencies().values()) {
                ManifestParser.Dependency old = before.dependencies().get(dep.key());
                if (old != null && old.spec().equals(dep.spec())) {
                    continue;
                }
                if (seen.putIfAbsent(dep.key() + "|" + dep.spec(), true) != null) {
                    continue;
                }
                changes.add(new Change(dep.request(), path, lineOf(after.get(), dep.request().name()),
                        old == null ? null : old.spec(), dep.spec()));
            }
        }
        return new Result(changes, unchecked, manifests);
    }

    /** 파일에서 이름이 처음 나오는 줄 (1부터). 못 찾으면 1. */
    static int lineOf(String content, String name) {
        String[] lines = content.split("\\R", -1);
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].toLowerCase(java.util.Locale.ROOT).contains(lower)) {
                return i + 1;
            }
        }
        return 1;
    }
}
