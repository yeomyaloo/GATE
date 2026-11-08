package io.gate.pkg.command;

import java.util.ArrayList;
import java.util.List;

/**
 * 셸 명령을 "단순 명령" 조각으로 나누고 각 조각을 토큰으로 자른다. 완전한 셸 파서가 아니라
 * 설치 명령을 찾는 데 필요한 만큼만 한다.
 * <ul>
 *   <li>조각 경계: {@code ; && || | & ( )} 줄바꿈 (따옴표 밖에서만)</li>
 *   <li>따옴표: 작은따옴표는 그대로, 큰따옴표 안에서는 {@code \"}만 처리, 따옴표 밖 {@code \}는 다음 글자를 그대로</li>
 *   <li>{@code $(...)}, 백틱, {@code ${...}}, {@code $VAR}가 작은따옴표 밖에 있으면 그 조각을 "동적"으로 표시한다.
 *       실행 전에는 실제 값을 알 수 없으므로, 설치 명령이면 통과시키지 않고 확인을 요청하게 된다</li>
 * </ul>
 */
public final class ShellSplitter {

    /** 단순 명령 하나. raw는 원문 일부, dynamic은 셸 치환이 있는지. */
    public record Segment(List<String> tokens, String raw, boolean dynamic) {
        public Segment {
            tokens = List.copyOf(tokens);
        }
    }

    private ShellSplitter() {
    }

    public static List<Segment> split(String command) {
        List<Segment> segments = new ArrayList<>();
        List<String> tokens = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        StringBuilder raw = new StringBuilder();
        boolean inToken = false;
        boolean dynamic = false;
        char quote = 0;

        int i = 0;
        int n = command.length();
        while (i < n) {
            char c = command.charAt(i);

            if (quote == '\'') {
                raw.append(c);
                if (c == '\'') {
                    quote = 0;
                } else {
                    token.append(c);
                }
                i++;
                continue;
            }
            if (quote == '"') {
                raw.append(c);
                if (c == '"') {
                    quote = 0;
                } else if (c == '\\' && i + 1 < n && "\"\\$`".indexOf(command.charAt(i + 1)) >= 0) {
                    token.append(command.charAt(i + 1));
                    raw.append(command.charAt(i + 1));
                    i++;
                } else {
                    if (isExpansionStart(command, i)) {
                        dynamic = true;
                    }
                    token.append(c);
                }
                i++;
                continue;
            }

            // 따옴표 밖
            if (c == '\'' || c == '"') {
                quote = c;
                inToken = true;
                raw.append(c);
                i++;
                continue;
            }
            if (c == '\\' && i + 1 < n) {
                if (command.charAt(i + 1) != '\n') { // 줄 이어쓰기는 무시
                    token.append(command.charAt(i + 1));
                    inToken = true;
                }
                raw.append(c).append(command.charAt(i + 1));
                i += 2;
                continue;
            }
            int sep = separatorLength(command, i);
            if (sep > 0) {
                if (inToken) {
                    tokens.add(token.toString());
                    token.setLength(0);
                    inToken = false;
                }
                flush(segments, tokens, raw, dynamic);
                tokens = new ArrayList<>();
                raw.setLength(0);
                dynamic = false;
                i += sep;
                continue;
            }
            if (Character.isWhitespace(c)) {
                if (inToken) {
                    tokens.add(token.toString());
                    token.setLength(0);
                    inToken = false;
                }
                raw.append(c);
                i++;
                continue;
            }
            if (isExpansionStart(command, i)) {
                dynamic = true;
            }
            token.append(c);
            raw.append(c);
            inToken = true;
            i++;
        }
        if (inToken) {
            tokens.add(token.toString());
        }
        flush(segments, tokens, raw, dynamic);
        return segments;
    }

    private static void flush(List<Segment> segments, List<String> tokens, StringBuilder raw, boolean dynamic) {
        if (!tokens.isEmpty()) {
            segments.add(new Segment(tokens, raw.toString().strip(), dynamic));
        }
    }

    private static int separatorLength(String s, int i) {
        char c = s.charAt(i);
        if ((c == '&' || c == '|') && i + 1 < s.length() && s.charAt(i + 1) == c) {
            return 2;
        }
        // 2>&1 같은 리다이렉트의 &는 경계가 아니다
        if (c == '&' && i > 0 && (s.charAt(i - 1) == '>' || s.charAt(i - 1) == '<')) {
            return 0;
        }
        return ";&|()\n".indexOf(c) >= 0 ? 1 : 0;
    }

    private static boolean isExpansionStart(String s, int i) {
        char c = s.charAt(i);
        if (c == '`') {
            return true;
        }
        if (c != '$' || i + 1 >= s.length()) {
            return false;
        }
        char next = s.charAt(i + 1);
        return next == '(' || next == '{' || next == '_' || Character.isLetter(next);
    }
}
