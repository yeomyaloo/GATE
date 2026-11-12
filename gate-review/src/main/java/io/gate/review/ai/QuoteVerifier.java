package io.gate.review.ai;

import io.gate.review.SourceFile;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * AI가 지어낸 지적을 걸러 낸다 (설계안 1장 원칙).
 * AI가 인용한 코드가 그 파일의 지적한 줄 근처(앞뒤 {@link #WINDOW}줄)에 실제로 있어야 받아들인다.
 * 공백 차이는 무시하고 비교한다.
 */
public final class QuoteVerifier {

    static final int WINDOW = 2;
    static final int MIN_QUOTE = 8;

    private QuoteVerifier() {
    }

    /** 문제가 없으면 빈 값, 버려야 하면 그 이유. */
    public static Optional<String> rejectReason(AiFinding f, Map<String, SourceFile> files) {
        SourceFile file = files.get(f.file());
        if (file == null) {
            return Optional.of("file is not part of the review: " + f.file());
        }
        List<String> lines = file.lines();
        if (f.line() < 1 || f.line() > lines.size()) {
            return Optional.of("line " + f.line() + " is outside the file (" + lines.size() + " lines)");
        }
        String quote = squash(f.quotedCode());
        if (quote.length() < MIN_QUOTE) {
            return Optional.of("quoted code is missing or too short to verify");
        }
        int from = Math.max(1, f.line() - WINDOW);
        int to = Math.min(lines.size(), f.endLine() + WINDOW);
        String window = squash(String.join("\n", lines.subList(from - 1, to)));
        if (!window.contains(quote)) {
            return Optional.of("quoted code does not appear at lines " + from + "-" + to);
        }
        return Optional.empty();
    }

    static String squash(String s) {
        return s == null ? "" : s.replaceAll("\\s+", "");
    }
}
