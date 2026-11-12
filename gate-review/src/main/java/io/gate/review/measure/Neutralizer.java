package io.gate.review.measure;

import io.gate.review.SourceFile;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 측정용: 실습장 코드에서 정답을 드러내는 이름을 지운다 (2단계에서 남긴 숙제).
 * <ul>
 *   <li>{@code io.gate.lab.vulnerable}·{@code io.gate.lab.fixed} 패키지와 경로 → 둘 다 {@code io.gate.lab.app}</li>
 *   <li>{@code @Profile("vulnerable")}·{@code @Profile("fixed")} → {@code @Profile("app")}</li>
 * </ul>
 * 두 버전을 한 번에 보여 주면 AI가 서로 비교해 어느 쪽이 고친 것인지 알 수 있으므로, 버전마다 따로 검토하고
 * 같은 중립 이름을 쓴다. 줄 수는 바꾸지 않아 정답지의 줄 번호가 그대로 유효하다.
 */
public final class Neutralizer {

    static final String NEUTRAL = "app";
    private static final Pattern VARIANT = Pattern.compile("\\b(vulnerable|fixed)\\b");

    /** 중립화한 파일들과, 중립 경로 → 원래 경로 대응표. */
    public record Neutralized(List<SourceFile> files, Map<String, String> originalPath) {
        public String original(String neutralPath) {
            return originalPath.getOrDefault(neutralPath, neutralPath);
        }
    }

    private Neutralizer() {
    }

    /**
     * @param files   실습장 파일 전체
     * @param variant vulnerable 또는 fixed. 이 버전의 파일과 공통 파일만 남긴다
     */
    public static Neutralized prepare(List<SourceFile> files, String variant) {
        if (!variant.equals("vulnerable") && !variant.equals("fixed")) {
            throw new IllegalArgumentException("variant must be vulnerable or fixed");
        }
        String other = variant.equals("vulnerable") ? "fixed" : "vulnerable";
        List<SourceFile> out = new ArrayList<>();
        Map<String, String> back = new LinkedHashMap<>();
        for (SourceFile f : files) {
            if (f.path().contains("/" + other + "/")) {
                continue;
            }
            String path = f.path().replace("/io/gate/lab/" + variant + "/", "/io/gate/lab/" + NEUTRAL + "/");
            String content = f.content()
                    .replace("io.gate.lab." + variant, "io.gate.lab." + NEUTRAL)
                    .replace("@Profile(\"" + variant + "\")", "@Profile(\"" + NEUTRAL + "\")");
            out.add(new SourceFile(path, content));
            back.put(path, f.path());
        }
        return new Neutralized(out, back);
    }

    /** 중립화한 코드에 버전 이름이 남아 있으면 그 위치 (테스트·측정 전 확인용). */
    public static List<String> leaks(List<SourceFile> files) {
        List<String> found = new ArrayList<>();
        for (SourceFile f : files) {
            if (VARIANT.matcher(f.path()).find()) {
                found.add(f.path() + " (path)");
            }
            List<String> lines = f.lines();
            for (int i = 0; i < lines.size(); i++) {
                String l = lines.get(i);
                if (l.contains("io.gate.lab.vulnerable") || l.contains("io.gate.lab.fixed")
                        || l.contains("@Profile(\"vulnerable\")") || l.contains("@Profile(\"fixed\")")) {
                    found.add(f.path() + ":" + (i + 1));
                }
            }
        }
        return found;
    }
}
