package io.gate.review;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** CWE 번호 읽기와 "같은 종류" 판단. 정적 분석과 AI가 서로 다른 CWE 번호로 같은 문제를 가리킬 수 있다. */
public final class Cwe {

    private static final Pattern NUMBER = Pattern.compile("(?i)CWE-(\\d+)");

    /** 같은 종류로 보는 CWE 묶음. */
    static final List<Set<Integer>> FAMILIES = List.of(
            Set.of(89, 564, 943),                 // SQL·쿼리 인젝션
            Set.of(347, 345, 287, 290, 1270),     // 인증·서명 검증
            Set.of(209, 200, 497, 550, 211),      // 오류·내부 정보 노출
            Set.of(639, 284, 285, 862, 863, 566), // 권한(객체 단위 포함)
            Set.of(78, 77),                       // 명령 인젝션
            Set.of(79, 80),                       // XSS
            Set.of(22, 23, 36));                  // 경로 조작

    private Cwe() {
    }

    /** "CWE-89: ..." 같은 문자열에서 "CWE-89"를 뽑는다. */
    public static Optional<String> normalize(String text) {
        if (text == null) {
            return Optional.empty();
        }
        Matcher m = NUMBER.matcher(text);
        return m.find() ? Optional.of("CWE-" + Integer.parseInt(m.group(1))) : Optional.empty();
    }

    public static boolean sameFamily(String a, String b) {
        Optional<Integer> x = number(a);
        Optional<Integer> y = number(b);
        if (x.isEmpty() || y.isEmpty()) {
            return false;
        }
        if (x.get().equals(y.get())) {
            return true;
        }
        return FAMILIES.stream().anyMatch(f -> f.contains(x.get()) && f.contains(y.get()));
    }

    private static Optional<Integer> number(String text) {
        return normalize(text).map(s -> Integer.parseInt(s.substring(4)));
    }
}
