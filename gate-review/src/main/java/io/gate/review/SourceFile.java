package io.gate.review;

import java.util.List;
import java.util.Objects;

/** 검토할 파일 하나. path는 저장소 기준 상대 경로(슬래시 구분). */
public record SourceFile(String path, String content) {

    public SourceFile {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(content, "content");
        path = path.replace('\\', '/');
    }

    public List<String> lines() {
        return content.lines().toList();
    }

    /** 1부터 세는 줄. 범위를 벗어나면 빈 문자열. */
    public String line(int number) {
        List<String> lines = lines();
        return number >= 1 && number <= lines.size() ? lines.get(number - 1) : "";
    }
}
