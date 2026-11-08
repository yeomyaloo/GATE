package io.gate.pkg.check;

import io.gate.pkg.registry.Ecosystem;
import io.gate.pkg.registry.PyPiRegistryClient;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 인기 패키지 이름과 "비슷하지만 같지 않은" 이름을 찾는다 (설계안 4.1).
 * 검사 대상 자체가 인기 목록에 있으면 찾지 않는다.
 *
 * <p>기법은 다음 순서로 확인하고, 처음 맞은 것을 쓴다.
 * <ol>
 *   <li>separator: 구분자만 다름 ({@code react-dom} / {@code reactdom} / {@code react_dom})</li>
 *   <li>homoglyph: 비슷하게 생긴 글자 ({@code rn}↔{@code m}, {@code l}↔{@code 1}↔{@code i}, {@code 0}↔{@code o})</li>
 *   <li>affix: 흔한 접두·접미어 추가 ({@code python-}, {@code -js} …)</li>
 *   <li>scope: npm 스코프 흉내 ({@code types-node} ↔ {@code @types/node}, {@code @evil/lodash} ↔ {@code lodash})</li>
 *   <li>edit-distance: Damerau-Levenshtein 거리. 5~9자는 1, 10자 이상은 2 이하. 4자 이하는 오탐이 많아 보지 않는다</li>
 * </ol>
 */
public final class TypoDetector {

    /** 비슷한 인기 패키지 하나. rank는 인기 목록 순위(0이 1위). */
    public record TypoMatch(String target, String technique, int rank) {
    }

    private static final List<String> PREFIXES = List.of("python-", "py-", "node-", "js-");
    private static final List<String> SUFFIXES = List.of("-js", ".js", "-python", "-py", "-dev", "-node", "-lib");

    private final Ecosystem ecosystem;
    private final Map<String, Integer> rank = new HashMap<>();
    private final Map<String, String> bySeparatorless = new HashMap<>();
    private final Map<String, String> byGlyph = new HashMap<>();
    private final List<String> popular;

    public TypoDetector(Ecosystem ecosystem, List<String> popularByRank) {
        this.ecosystem = ecosystem;
        List<String> names = new ArrayList<>();
        for (String raw : popularByRank) {
            String name = normalize(raw);
            if (name.isEmpty() || rank.containsKey(name)) {
                continue;
            }
            rank.put(name, names.size());
            names.add(name);
            bySeparatorless.putIfAbsent(stripSeparators(name), name);
            byGlyph.putIfAbsent(glyphs(name), name);
        }
        this.popular = List.copyOf(names);
    }

    /** {@code popular/<ecosystem>.txt} 스냅샷을 읽는다. '#'으로 시작하는 줄은 머리말이다. */
    public static TypoDetector load(Ecosystem ecosystem) {
        String path = "/popular/" + ecosystem.id() + ".txt";
        try (InputStream in = TypoDetector.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("missing popular list " + path);
            }
            List<String> names = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.strip();
                    if (!line.isEmpty() && !line.startsWith("#")) {
                        names.add(line);
                    }
                }
            }
            return new TypoDetector(ecosystem, names);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Ecosystem ecosystem() {
        return ecosystem;
    }

    public int size() {
        return popular.size();
    }

    public boolean isPopular(String name) {
        return rank.containsKey(normalize(name));
    }

    public Optional<TypoMatch> find(String rawName) {
        String name = normalize(rawName);
        if (name.isEmpty() || rank.containsKey(name)) {
            return Optional.empty();
        }

        String target = bySeparatorless.get(stripSeparators(name));
        if (target != null) {
            return match(target, "separator");
        }
        target = byGlyph.get(glyphs(name));
        if (target != null) {
            return match(target, "homoglyph");
        }
        for (String prefix : PREFIXES) {
            if (name.startsWith(prefix) && rank.containsKey(name.substring(prefix.length()))) {
                return match(name.substring(prefix.length()), "affix");
            }
        }
        for (String suffix : SUFFIXES) {
            if (name.endsWith(suffix) && rank.containsKey(name.substring(0, name.length() - suffix.length()))) {
                return match(name.substring(0, name.length() - suffix.length()), "affix");
            }
        }
        if (ecosystem == Ecosystem.NPM) {
            if (name.startsWith("types-") && rank.containsKey("@types/" + name.substring(6))) {
                return match("@types/" + name.substring(6), "scope");
            }
            int slash = name.indexOf('/');
            if (name.startsWith("@") && slash > 0 && rank.containsKey(name.substring(slash + 1))) {
                return match(name.substring(slash + 1), "scope");
            }
        }

        int max = name.length() >= 10 ? 2 : name.length() >= 5 ? 1 : 0;
        if (max == 0) {
            return Optional.empty();
        }
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String candidate : popular) { // 순위 순서이므로 거리가 같으면 더 인기 있는 쪽이 남는다
            if (Math.abs(candidate.length() - name.length()) > max) {
                continue;
            }
            int d = distance(name, candidate, max);
            if (d <= max && d < bestDistance) {
                best = candidate;
                bestDistance = d;
                if (d == 1) {
                    break;
                }
            }
        }
        return best == null ? Optional.empty() : match(best, "edit-distance");
    }

    private Optional<TypoMatch> match(String target, String technique) {
        return Optional.of(new TypoMatch(target, technique, rank.getOrDefault(target, -1)));
    }

    private String normalize(String name) {
        String n = name.strip().toLowerCase(Locale.ROOT);
        return ecosystem == Ecosystem.PYPI ? PyPiRegistryClient.normalize(n) : n;
    }

    static String stripSeparators(String s) {
        return s.replaceAll("[-_.]", "");
    }

    /** 비슷하게 생긴 글자를 하나로 모은 형태. */
    static String glyphs(String s) {
        return s.replace("rn", "m").replace("vv", "w").replace('1', 'l').replace('i', 'l').replace('0', 'o');
    }

    /** 최적 문자열 정렬(OSA) 거리. max를 넘으면 일찍 멈추고 max + 1을 돌려준다. */
    static int distance(String a, String b, int max) {
        int n = a.length();
        int m = b.length();
        int[][] d = new int[n + 1][m + 1];
        for (int i = 0; i <= n; i++) {
            d[i][0] = i;
        }
        for (int j = 0; j <= m; j++) {
            d[0][j] = j;
        }
        for (int i = 1; i <= n; i++) {
            int rowMin = Integer.MAX_VALUE;
            for (int j = 1; j <= m; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                int v = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    v = Math.min(v, d[i - 2][j - 2] + 1);
                }
                d[i][j] = v;
                rowMin = Math.min(rowMin, v);
            }
            if (rowMin > max) {
                return max + 1;
            }
        }
        return d[n][m];
    }
}
