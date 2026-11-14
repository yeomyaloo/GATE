package io.gate.server.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.gate.common.finding.Decision;
import io.gate.common.finding.GateModule;
import io.gate.server.web.SecurityHeadersFilter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Docker 없이 확인할 수 있는 부분: 조회 조건 검증과 보안 헤더. */
class QueryControllerTest {

    @Test
    void filtersAreValidatedAgainstEnums() {
        assertThat(QueryController.enumName("pkg", GateModule::valueOf, "module")).isEqualTo("PKG");
        assertThat(QueryController.enumName(null, GateModule::valueOf, "module")).isNull();
        assertThatThrownBy(() -> QueryController.enumName("PKG' OR 1=1--", GateModule::valueOf, "module"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unknown module");
        assertThatThrownBy(() -> QueryController.enumName("MAYBE", Decision::valueOf, "decision"))
                .hasMessageContaining("unknown decision");
    }

    @Test
    void limitHasABound() {
        assertThat(QueryController.limit(null)).isEqualTo(QueryController.DEFAULT_LIMIT);
        assertThat(QueryController.limit(200)).isEqualTo(200);
        assertThatThrownBy(() -> QueryController.limit(201)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> QueryController.limit(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> QueryController.uuid("x")).hasMessageContaining("UUID");
    }

    @Test
    void securityHeadersForbidInlineScripts() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/findings");
        MockHttpServletResponse res = new MockHttpServletResponse();
        new SecurityHeadersFilter().doFilter(req, res, new MockFilterChain());
        String csp = res.getHeader("Content-Security-Policy");
        String scriptSrc = java.util.Arrays.stream(csp.split(";")).map(String::strip)
                .filter(d -> d.startsWith("script-src")).findFirst().orElseThrow();
        assertThat(scriptSrc).isEqualTo("script-src 'self'").as("no inline scripts, no eval");
        assertThat(csp).doesNotContain("unsafe-eval").contains("object-src 'none'").contains("frame-ancestors 'none'");
        assertThat(res.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(res.getHeader("Cache-Control")).isEqualTo("no-store");

        MockHttpServletResponse page = new MockHttpServletResponse();
        new SecurityHeadersFilter().doFilter(new MockHttpServletRequest("GET", "/"), page, new MockFilterChain());
        assertThat(page.getHeader("Cache-Control")).as("static pages may be cached").isNull();
    }
}
