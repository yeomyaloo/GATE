package io.gate.pkg.registry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PyPI 버전 지정자 해석 (PEP 440을 직접 구현, 외부 라이브러리 없음).
 * <ul>
 *   <li>버전: {@code [N!]N(.N)*[{a|b|rc}N][.postN][.devN][+local]} (별칭 alpha·beta·c·pre·preview·rev·r, 구분자 생략 허용)</li>
 *   <li>지정자: {@code == != <= >= < > ~= ===}, {@code ==2.*}·{@code !=2.*}, 쉼표는 "그리고"</li>
 *   <li>사전 배포판·개발판은 지정자에 사전 배포판이 적혔거나, 정식판 중 맞는 것이 없을 때만 고른다(pip·packaging과 같은 규칙)</li>
 *   <li>철회(yanked)된 릴리스는 {@code ==}·{@code ===}로 정확히 고정한 경우에만 고른다(PEP 592)</li>
 *   <li>pip처럼 맞는 것 중 가장 높은 버전을 고른다</li>
 * </ul>
 */
public final class Pep440 {

    private Pep440() {
    }

    private static final Pattern VERSION = Pattern.compile(
            "^\\s*v?(?:(\\d+)!)?(\\d+(?:\\.\\d+)*)"
                    + "(?:[-_.]?(a|b|c|rc|alpha|beta|pre|preview)[-_.]?(\\d+)?)?"
                    + "(?:(?:-(\\d+))|(?:[-_.]?(post|rev|r)[-_.]?(\\d+)?))?"
                    + "(?:[-_.]?(dev)[-_.]?(\\d+)?)?"
                    + "(?:\\+([a-z0-9]+(?:[-_.][a-z0-9]+)*))?\\s*$", Pattern.CASE_INSENSITIVE);

    /** 정규화한 버전. pre: "a"·"b"·"rc" 중 하나와 번호. 없으면 null/-1. */
    public record Version(long epoch, List<Long> release, String preKind, long preNum, long post, long dev,
            String local) implements Comparable<Version> {

        public static Optional<Version> parse(String s) {
            Matcher m = VERSION.matcher(s == null ? "" : s);
            if (!m.matches()) {
                return Optional.empty();
            }
            try {
                List<Long> release = new ArrayList<>();
                for (String part : m.group(2).split("\\.")) {
                    release.add(Long.parseLong(part));
                }
                String preKind = null;
                long preNum = -1;
                if (m.group(3) != null) {
                    String k = m.group(3).toLowerCase(Locale.ROOT);
                    preKind = k.equals("alpha") ? "a" : k.equals("beta") ? "b"
                            : k.equals("c") || k.equals("pre") || k.equals("preview") ? "rc" : k;
                    preNum = m.group(4) == null ? 0 : Long.parseLong(m.group(4));
                }
                long post = -1;
                if (m.group(5) != null) {
                    post = Long.parseLong(m.group(5));
                } else if (m.group(6) != null) {
                    post = m.group(7) == null ? 0 : Long.parseLong(m.group(7));
                }
                long dev = m.group(8) == null ? -1 : m.group(9) == null ? 0 : Long.parseLong(m.group(9));
                return Optional.of(new Version(m.group(1) == null ? 0 : Long.parseLong(m.group(1)), release, preKind,
                        preNum, post, dev, m.group(10) == null ? null : m.group(10).toLowerCase(Locale.ROOT)));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        }

        /** 사전 배포판 또는 개발판. */
        public boolean prerelease() {
            return preKind != null || dev >= 0;
        }

        boolean postRelease() {
            return post >= 0;
        }

        long releaseAt(int i) {
            return i < release.size() ? release.get(i) : 0;
        }

        Version publicPart() {
            return new Version(epoch, release, preKind, preNum, post, dev, null);
        }

        @Override
        public int compareTo(Version o) {
            int c = Long.compare(epoch, o.epoch);
            for (int i = 0; c == 0 && i < Math.max(release.size(), o.release.size()); i++) {
                c = Long.compare(releaseAt(i), o.releaseAt(i));
            }
            if (c == 0) {
                c = Integer.compare(preRank(), o.preRank());
            }
            if (c == 0) {
                c = Long.compare(preNum, o.preNum);
            }
            if (c == 0) {
                c = Long.compare(post, o.post); // -1(없음)이 가장 낮다
            }
            if (c == 0) {
                c = Long.compare(dev < 0 ? Long.MAX_VALUE : dev, o.dev < 0 ? Long.MAX_VALUE : o.dev);
            }
            if (c == 0) {
                c = local == null ? (o.local == null ? 0 : -1) : (o.local == null ? 1 : local.compareTo(o.local));
            }
            return c;
        }

        /** 개발판만 있는 경우(1.0.dev1)는 모든 사전 배포판보다 낮다. 사전 배포판이 없으면 가장 높다. */
        private int preRank() {
            if (preKind == null) {
                return post < 0 && dev >= 0 ? -1 : 3;
            }
            return preKind.equals("a") ? 0 : preKind.equals("b") ? 1 : 2;
        }
    }

    // ---------------------------------------------------------------- 지정자

    record Spec(String op, String version, boolean wildcard) {
    }

    /** 쉼표로 이어진 지정자 묶음. 빈 묶음은 "아무 버전". */
    public record SpecifierSet(List<Spec> specs) {

        /** 지정자에 사전 배포판이 적혀 있으면 사전 배포판도 고를 수 있다. */
        boolean mentionsPrerelease() {
            return specs.stream().anyMatch(s -> !s.wildcard() && !s.op().equals("!=")
                    && Version.parse(s.version()).map(Version::prerelease).orElse(false));
        }

        /** {@code ==}(와일드카드 없음)·{@code ===}로 고정했는지 (철회된 릴리스도 고를 수 있다). */
        boolean pinned() {
            return specs.stream().anyMatch(s -> s.op().equals("===") || s.op().equals("==") && !s.wildcard());
        }

        public boolean contains(String raw) {
            Optional<Version> v = Version.parse(raw);
            for (Spec s : specs) {
                if (s.op().equals("===")) {
                    if (!raw.trim().equalsIgnoreCase(s.version())) {
                        return false;
                    }
                    continue;
                }
                if (v.isEmpty() || !test(s, v.get())) {
                    return false;
                }
            }
            return !specs.isEmpty() || v.isPresent();
        }
    }

    private static final Pattern SPEC = Pattern.compile("^\\s*(===|==|!=|~=|<=|>=|<|>)\\s*([^\\s,;]+)\\s*$");

    /** 해석할 수 없는 지정자면 빈 값. 빈 문자열은 "아무 버전". */
    public static Optional<SpecifierSet> parse(String spec) {
        String s = spec == null ? "" : spec.trim();
        if (s.startsWith("(") && s.endsWith(")")) {
            s = s.substring(1, s.length() - 1); // 옛 표기 requests (>=2.0)
        }
        List<Spec> out = new ArrayList<>();
        if (s.isEmpty()) {
            return Optional.of(new SpecifierSet(out));
        }
        for (String part : s.split(",")) {
            Matcher m = SPEC.matcher(part);
            if (!m.matches()) {
                return Optional.empty();
            }
            String op = m.group(1);
            String ver = m.group(2);
            boolean wildcard = ver.endsWith(".*");
            if (wildcard && !(op.equals("==") || op.equals("!="))) {
                return Optional.empty();
            }
            String base = wildcard ? ver.substring(0, ver.length() - 2) : ver;
            if (!op.equals("===") && Version.parse(base).isEmpty()) {
                return Optional.empty();
            }
            if (op.equals("~=") && Version.parse(base).get().release().size() < 2) {
                return Optional.empty();
            }
            out.add(new Spec(op, base, wildcard));
        }
        return Optional.of(new SpecifierSet(out));
    }

    private static boolean test(Spec s, Version v) {
        Version target = Version.parse(s.version()).orElseThrow();
        switch (s.op()) {
            case "==":
                return s.wildcard() ? prefixMatch(target, v) : equalsVersion(target, v);
            case "!=":
                return s.wildcard() ? !prefixMatch(target, v) : !equalsVersion(target, v);
            case "<=":
                return v.publicPart().compareTo(target) <= 0;
            case ">=":
                return v.publicPart().compareTo(target) >= 0;
            case "<":
                // <V는 V의 사전 배포판을 포함하지 않는다(V 자체가 사전 배포판이면 예외)
                return v.compareTo(target) < 0 && (target.prerelease() || !v.prerelease()
                        || !sameRelease(v, target));
            case ">":
                // >V는 V의 사후 배포판·로컬 버전을 포함하지 않는다
                return v.compareTo(target) > 0 && (target.postRelease() || !v.postRelease() || !sameRelease(v, target))
                        && !(v.local() != null && v.publicPart().compareTo(target) == 0);
            case "~=": {
                List<Long> prefix = target.release().subList(0, target.release().size() - 1);
                Version p = new Version(target.epoch(), prefix, null, -1, -1, -1, null);
                return v.publicPart().compareTo(target) >= 0 && prefixMatch(p, v);
            }
            default:
                return false;
        }
    }

    /** 같은 epoch와 release(끝의 0은 무시)인지. */
    private static boolean sameRelease(Version a, Version b) {
        if (a.epoch() != b.epoch()) {
            return false;
        }
        for (int i = 0; i < Math.max(a.release().size(), b.release().size()); i++) {
            if (a.releaseAt(i) != b.releaseAt(i)) {
                return false;
            }
        }
        return true;
    }

    /** 지정자에 로컬 버전이 없으면 후보의 로컬 부분은 무시한다. */
    private static boolean equalsVersion(Version target, Version v) {
        return (target.local() == null ? v.publicPart() : v).compareTo(target) == 0;
    }

    /** ==2.1.* : epoch가 같고 release 앞부분이 2.1인 모든 버전(사전·사후 배포판 포함). */
    private static boolean prefixMatch(Version prefix, Version v) {
        if (prefix.epoch() != v.epoch()) {
            return false;
        }
        for (int i = 0; i < prefix.release().size(); i++) {
            if (prefix.release().get(i) != v.releaseAt(i)) {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- 고르기

    /**
     * pip이 설치할 버전을 고른다: 맞는 것 중 가장 높은 버전.
     *
     * @param yanked 모든 파일이 철회된 릴리스
     */
    public static Optional<String> pick(SpecifierSet set, Collection<String> versions, Set<String> yanked) {
        boolean allowYanked = set.pinned();
        Optional<String> finals = best(set, versions, yanked, allowYanked, false);
        if (finals.isPresent() && !set.mentionsPrerelease()) {
            return finals;
        }
        // 지정자가 사전 배포판을 적었거나, 정식판 중 맞는 것이 없으면 사전 배포판까지 본다
        Optional<String> any = best(set, versions, yanked, allowYanked, true);
        return any.isPresent() ? any : finals;
    }

    private static Optional<String> best(SpecifierSet set, Collection<String> versions, Set<String> yanked,
            boolean allowYanked, boolean includePre) {
        String best = null;
        Version bestV = null;
        for (String raw : versions) {
            Optional<Version> v = Version.parse(raw);
            if (v.isEmpty() || !includePre && v.get().prerelease() || !allowYanked && yanked.contains(raw)
                    || !set.contains(raw)) {
                continue;
            }
            if (bestV == null || v.get().compareTo(bestV) > 0) {
                best = raw;
                bestV = v.get();
            }
        }
        return Optional.ofNullable(best);
    }
}
