package io.gate.prompt.input;

/** 화면에 보이지 않게 지시를 숨기는 데 쓰이는 글자 (제어 문자, 제로폭, 양방향 제어, 유니코드 태그 문자). */
public final class HiddenText {

    private HiddenText() {
    }

    public static boolean isHidden(int c) {
        return Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t'
                || c >= 0x200B && c <= 0x200F || c >= 0x202A && c <= 0x202E || c >= 0x2060 && c <= 0x2064
                || c >= 0x2066 && c <= 0x2069 || c == 0xFEFF || c >= 0xE0000 && c <= 0xE007F;
    }

    /** 줄바꿈·탭까지 포함해 모든 제어 문자를 숨김 문자로 본다 (한 줄짜리 인자 검사용). */
    public static boolean hasControlOrHidden(String s) {
        return s.codePoints().anyMatch(c -> Character.isISOControl(c) || isHidden(c));
    }

    public static boolean hasHidden(String s) {
        return s.codePoints().anyMatch(HiddenText::isHidden);
    }
}
