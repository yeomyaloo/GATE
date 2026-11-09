package io.gate.authz.probe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RedactorTest {

    @Test
    void sensitiveValuesAreMaskedButKeysKept() {
        String out = Redactor.excerpt("{\"id\":2,\"owner\":\"bob\",\"shippingAddress\":\"Busan, Sample-gil 22\","
                + "\"email\":\"bob@example.invalid\",\"nested\":{\"accessToken\":\"eyJabc\"}}");
        assertTrue(out.contains("\"owner\":\"bob\""), out);
        assertTrue(out.contains("\"shippingAddress\":\"***(20 chars)\""), out);
        assertFalse(out.contains("Busan"));
        assertFalse(out.contains("bob@example"));
        assertFalse(out.contains("eyJabc"));
    }

    @Test
    void nonJsonBearerTokensAreMaskedAndLongBodiesTruncated() {
        assertFalse(Redactor.excerpt("Authorization: Bearer abc.def.ghi").contains("abc.def"));
        assertEquals(Redactor.MAX_EXCERPT + 1, Redactor.excerpt("x".repeat(1000)).length());
        assertEquals("eyJhbG***", Redactor.maskToken("eyJhbGciOi"));
    }
}
