package io.gate.pkg.registry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * npm 버전 범위 해석 (node-semver 규칙을 직접 구현, 외부 라이브러리 없음).
 * <ul>
 *   <li>문법: {@code ^ ~ x * X} 범위, {@code 1.2 - 2.3} 하이픈 범위, {@code >= <= > < =} 비교, 공백(그리고), {@code ||}(또는)</li>
 *   <li>사전 배포판({@code 1.2.3-beta})은 같은 {@code major.minor.patch}의 사전 배포판이 범위에 적혀 있을 때만 맞는다</li>
 *   <li>고르기: npm처럼 {@code latest} 태그가 범위에 맞으면 그것, 아니면 범위에 맞는 가장 높은 버전(폐기되지 않은 것 우선)</li>
 * </ul>
 * git·파일·URL·별칭({@code npm:})·{@code workspace:} 같은 범위 밖 문법은 해석하지 않는다(빈 값).
 */
public final class NpmSemver {

    private NpmSemver() {
    }

    // ---------------------------------------------------------------- 버전

    /** 정확한 버전. build 메타데이터는 비교에 쓰지 않는다. */
    public record Version(long major, long minor, long patch, List<String> pre) implements Comparable<Version> {

        private static final Pattern FULL = Pattern.compile(
                "^[v=\\s]*(\\d+)\\.(\\d+)\\.(\\d+)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+[0-9A-Za-z.-]+)?$");

        public static Optional<Version> parse(String s) {
            Matcher m = FULL.matcher(s.trim());
            if (!m.matches()) {
                return Optional.empty();
            }
            try {
                return Optional.of(new Version(Long.parseLong(m.group(1)), Long.parseLong(m.group(2)),
                        Long.parseLong(m.group(3)), m.group(4) == null ? List.of() : List.of(m.group(4).split("\\."))));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        }

        boolean prerelease() {
            return !pre.isEmpty();
        }

        Version withoutPre() {
            return new Version(major, minor, patch, List.of());
        }

        Version withPre0() {
            return new Version(major, minor, patch, List.of("0"));
        }

        boolean sameTuple(Version o) {
            return major == o.major && minor == o.minor && patch == o.patch;
        }

        @Override
        public int compareTo(Version o) {
            int c = Long.compare(major, o.major);
            if (c == 0) {
                c = Long.compare(minor, o.minor);
            }
            if (c == 0) {
                c = Long.compare(patch, o.patch);
            }
            if (c != 0) {
                return c;
            }
            if (pre.isEmpty() || o.pre.isEmpty()) {
                return pre.isEmpty() == o.pre.isEmpty() ? 0 : pre.isEmpty() ? 1 : -1; // 정식판이 사전 배포판보다 높다
            }
            for (int i = 0; i < Math.min(pre.size(), o.pre.size()); i++) {
                c = comparePre(pre.get(i), o.pre.get(i));
                if (c != 0) {
                    return c;
                }
            }
            return Integer.compare(pre.size(), o.pre.size());
        }

        private static int comparePre(String a, String b) {
            boolean an = a.chars().allMatch(Character::isDigit);
            boolean bn = b.chars().allMatch(Character::isDigit);
            if (an && bn) {
                return new java.math.BigInteger(a).compareTo(new java.math.BigInteger(b));
            }
            if (an != bn) {
                return an ? -1 : 1; // 숫자 식별자가 낮다
            }
            return a.compareTo(b);
        }

        @Override
        public String toString() {
            return major + "." + minor + "." + patch + (pre.isEmpty() ? "" : "-" + String.join(".", pre));
        }
    }

    // ---------------------------------------------------------------- 범위

    enum Op { GT, GTE, LT, LTE, EQ }

    record Comparator(Op op, Version v) {
        boolean test(Version x) {
            int c = x.compareTo(v);
            switch (op) {
                case GT: return c > 0;
                case GTE: return c >= 0;
                case LT: return c < 0;
                case LTE: return c <= 0;
                default: return c == 0;
            }
        }
    }

    /** {@code ||}로 나뉜 집합들. 집합 안의 비교는 모두 맞아야 한다. 빈 집합은 "아무 버전". */
    public record Range(List<List<Comparator>> sets) {

        public boolean test(Version v) {
            for (List<Comparator> set : sets) {
                if (set.stream().allMatch(c -> c.test(v)) && prereleaseAllowed(set, v)) {
                    return true;
                }
            }
            return false;
        }

        private static boolean prereleaseAllowed(List<Comparator> set, Version v) {
            if (!v.prerelease()) {
                return true;
            }
            // 사전 배포판은 같은 major.minor.patch의 사전 배포판 비교가 있을 때만 (node-semver와 같은 규칙)
            return set.stream().anyMatch(c -> c.v().prerelease() && c.v().sameTuple(v));
        }
    }

    private static final Set<String> X = Set.of("x", "X", "*");
    private static final Pattern PARTIAL = Pattern.compile(
            "^[v=]*(\\d+|[xX*])(?:\\.(\\d+|[xX*]))?(?:\\.(\\d+|[xX*]))?(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?"
                    + "(?:\\+[0-9A-Za-z.-]+)?$");
    private static final Pattern PRIMITIVE = Pattern.compile("^(<=|>=|<|>|=|\\^|~>?)?\\s*(.*)$");

    /** 해석할 수 없는 문법이면 빈 값. */
    public static Optional<Range> parse(String spec) {
        String s = spec == null ? "" : spec.trim();
        if (s.matches("(?i)^(git|github|gitlab|bitbucket|file|link|workspace|npm|http|https|catalog):.*")
                || s.contains("/") || s.length() > 256) {
            return Optional.empty();
        }
        List<List<Comparator>> sets = new ArrayList<>();
        for (String part : s.split("\\|\\|", -1)) {
            Optional<List<Comparator>> set = parseSet(part.trim());
            if (set.isEmpty()) {
                return Optional.empty();
            }
            sets.add(set.get());
        }
        return Optional.of(new Range(sets));
    }

    private static Optional<List<Comparator>> parseSet(String s) {
        List<Comparator> out = new ArrayList<>();
        if (s.isEmpty()) {
            return Optional.of(out); // 아무 버전
        }
        Matcher hyphen = Pattern.compile("^(\\S+)\\s+-\\s+(\\S+)$").matcher(s);
        if (hyphen.matches()) {
            Optional<Partial> a = Partial.parse(hyphen.group(1));
            Optional<Partial> b = Partial.parse(hyphen.group(2));
            if (a.isEmpty() || b.isEmpty()) {
                return Optional.empty();
            }
            if (!a.get().anyMajor()) {
                out.add(new Comparator(Op.GTE, a.get().floor()));
            }
            if (!b.get().anyMajor()) {
                out.add(b.get().complete() ? new Comparator(Op.LTE, b.get().floor())
                        : new Comparator(Op.LT, b.get().nextUnspecified()));
            }
            return Optional.of(out);
        }
        // "> = 1.2" 같은 띄어쓰기를 붙인 뒤 공백으로 나눈다
        String joined = s.replaceAll("(<=|>=|<|>|=|\\^|~>?)\\s+", "$1");
        for (String token : joined.split("\\s+")) {
            Optional<List<Comparator>> cs = primitive(token);
            if (cs.isEmpty()) {
                return Optional.empty();
            }
            out.addAll(cs.get());
        }
        return Optional.of(out);
    }

    /** 부분 버전 (1, 1.2, 1.2.x, 1.2.3-beta). 빠진 자리는 null. */
    private record Partial(Long major, Long minor, Long patch, List<String> pre) {

        static Optional<Partial> parse(String s) {
            Matcher m = PARTIAL.matcher(s);
            if (!m.matches()) {
                return Optional.empty();
            }
            Long ma = num(m.group(1));
            Long mi = ma == null ? null : num(m.group(2));
            Long pa = mi == null ? null : num(m.group(3));
            List<String> pre = pa != null && m.group(4) != null ? List.of(m.group(4).split("\\.")) : List.of();
            return Optional.of(new Partial(ma, mi, pa, pre));
        }

        private static Long num(String g) {
            return g == null || X.contains(g) ? null : Long.parseLong(g);
        }

        boolean anyMajor() {
            return major == null;
        }

        boolean complete() {
            return patch != null;
        }

        Version floor() {
            return new Version(major == null ? 0 : major, minor == null ? 0 : minor, patch == null ? 0 : patch, pre);
        }

        /** 빠진 자리 바로 위 (1 → 2.0.0-0, 1.2 → 1.3.0-0). 사전 배포판이 끼어들지 않게 -0을 붙인다. */
        Version nextUnspecified() {
            return minor == null ? new Version(major + 1, 0, 0, List.of("0"))
                    : new Version(major, minor + 1, 0, List.of("0"));
        }
    }

    private static Optional<List<Comparator>> primitive(String token) {
        Matcher m = PRIMITIVE.matcher(token);
        if (!m.matches()) {
            return Optional.empty();
        }
        String op = m.group(1) == null ? "" : m.group(1);
        Optional<Partial> parsed = Partial.parse(m.group(2));
        if (parsed.isEmpty()) {
            return Optional.empty();
        }
        Partial p = parsed.get();
        List<Comparator> out = new ArrayList<>();
        switch (op) {
            case "^": {
                if (p.anyMajor()) {
                    return Optional.of(out);
                }
                out.add(new Comparator(Op.GTE, p.floor()));
                Version upper;
                if (p.major() > 0 || p.minor() == null) {
                    upper = new Version(p.major() + 1, 0, 0, List.of("0"));
                } else if (p.minor() > 0 || p.patch() == null) {
                    upper = new Version(0, p.minor() + 1, 0, List.of("0"));
                } else {
                    upper = new Version(0, 0, p.patch() + 1, List.of("0"));
                }
                out.add(new Comparator(Op.LT, upper));
                return Optional.of(out);
            }
            case "~":
            case "~>": {
                if (p.anyMajor()) {
                    return Optional.of(out);
                }
                out.add(new Comparator(Op.GTE, p.floor()));
                out.add(new Comparator(Op.LT, p.minor() == null ? new Version(p.major() + 1, 0, 0, List.of("0"))
                        : new Version(p.major(), p.minor() + 1, 0, List.of("0"))));
                return Optional.of(out);
            }
            case "":
            case "=": {
                if (p.anyMajor()) {
                    return Optional.of(out);
                }
                if (p.complete()) {
                    out.add(new Comparator(Op.EQ, p.floor()));
                } else {
                    out.add(new Comparator(Op.GTE, p.floor()));
                    out.add(new Comparator(Op.LT, p.nextUnspecified()));
                }
                return Optional.of(out);
            }
            case ">": {
                if (p.anyMajor()) {
                    out.add(new Comparator(Op.LT, new Version(0, 0, 0, List.of("0")))); // 맞는 버전 없음
                } else if (p.complete()) {
                    out.add(new Comparator(Op.GT, p.floor()));
                } else {
                    out.add(new Comparator(Op.GTE, p.nextUnspecified().withoutPre()));
                }
                return Optional.of(out);
            }
            case ">=":
                if (!p.anyMajor()) {
                    out.add(new Comparator(Op.GTE, p.floor()));
                }
                return Optional.of(out);
            case "<":
                if (p.anyMajor()) {
                    out.add(new Comparator(Op.LT, new Version(0, 0, 0, List.of("0"))));
                } else {
                    out.add(new Comparator(Op.LT, p.complete() ? p.floor() : p.floor().withPre0()));
                }
                return Optional.of(out);
            case "<=":
                if (!p.anyMajor()) {
                    out.add(p.complete() ? new Comparator(Op.LTE, p.floor())
                            : new Comparator(Op.LT, p.nextUnspecified()));
                }
                return Optional.of(out);
            default:
                return Optional.empty();
        }
    }

    // ---------------------------------------------------------------- 고르기

    /**
     * npm이 설치할 버전을 고른다.
     *
     * @param versions   레지스트리에 있는 모든 버전 문자열
     * @param latest     {@code latest} 태그 (없으면 null)
     * @param deprecated 폐기 표시된 버전
     * @return 맞는 버전이 없으면 빈 값
     */
    public static Optional<String> pick(Range range, Collection<String> versions, String latest,
            Set<String> deprecated) {
        if (latest != null) {
            Optional<Version> l = Version.parse(latest);
            if (l.isPresent() && range.test(l.get())) {
                return Optional.of(latest);
            }
        }
        String best = null;
        Version bestV = null;
        boolean bestDeprecated = true;
        for (String s : versions) {
            Optional<Version> v = Version.parse(s);
            if (v.isEmpty() || !range.test(v.get())) {
                continue;
            }
            boolean dep = deprecated.contains(s);
            // 폐기되지 않은 버전을 먼저, 같은 조건이면 높은 버전
            if (best == null || bestDeprecated && !dep || bestDeprecated == dep && v.get().compareTo(bestV) > 0) {
                best = s;
                bestV = v.get();
                bestDeprecated = dep;
            }
        }
        return Optional.ofNullable(best);
    }
}
