package io.gate.review.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gate.review.SourceFile;
import java.util.Map;
import org.junit.jupiter.api.Test;

class QuoteVerifierTest {

    static final SourceFile FILE = new SourceFile("src/A.java", String.join("\n",
            "class A {",                                   // 1
            "  String q(String in) {",                     // 2
            "    String sql = \"SELECT * FROM t WHERE x = '\"",  // 3
            "        + in + \"'\";",                       // 4
            "    return run(sql);",                        // 5
            "  }",                                         // 6
            "}"));                                         // 7

    static AiFinding at(int line, int end, String quote) {
        return new AiFinding("src/A.java", line, end, "CWE-89", "t", "HIGH", "e", quote, "high");
    }

    @Test
    void whitespaceDifferencesAndMultiLineQuotesAreAccepted() {
        assertTrue(QuoteVerifier.rejectReason(at(3, 4, "\"SELECT * FROM t WHERE x = '\" + in + \"'\";"),
                Map.of(FILE.path(), FILE)).isEmpty());
        assertTrue(QuoteVerifier.rejectReason(at(5, 5, "return   run( sql );"), Map.of(FILE.path(), FILE)).isEmpty());
    }

    @Test
    void quoteMustBeNearTheReportedLine() {
        // 1줄을 가리키며 5줄의 코드를 인용: 창(앞뒤 2줄)을 벗어나면 거부
        assertTrue(QuoteVerifier.rejectReason(at(1, 1, "return run(sql);"), Map.of(FILE.path(), FILE)).isPresent());
        assertTrue(QuoteVerifier.rejectReason(at(3, 3, "return run(sql);"), Map.of(FILE.path(), FILE)).isEmpty(),
                "within the +/-2 line window");
    }

    @Test
    void squashRemovesAllWhitespace() {
        assertEquals("a+b", QuoteVerifier.squash(" a\n +  b "));
    }
}
