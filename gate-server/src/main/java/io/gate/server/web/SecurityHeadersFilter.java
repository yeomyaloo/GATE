package io.gate.server.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 모든 응답에 보안 헤더를 붙인다 (마지막 단계 설계안 2장).
 * 검사 결과의 근거에는 공격 문자열(프롬프트 인젝션 문장, 악성 스크립트 조각)이 그대로 들어 있다.
 * 화면이 실수로 그것을 HTML로 넣더라도 실행되지 않게, 스크립트는 같은 출처의 파일만 허용한다(인라인 금지).
 */
@Component
public class SecurityHeadersFilter extends OncePerRequestFilter {

    /**
     * 화면의 UI 키트(Mantine)는 실행 중에 style 태그(CSS 변수)를 넣는다. 그래서 style-src에만 'unsafe-inline'을 허용한다
     * (설계안에서 미리 정한 조정). 스타일 주입은 스크립트를 실행하지 못한다. 스크립트 규칙(script-src 'self')은 그대로다.
     */
    public static final String CSP = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
            + "img-src 'self' data:; "
            + "font-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; "
            + "form-action 'self'";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Content-Security-Policy", CSP);
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        if (request.getRequestURI().startsWith("/api/")) {
            response.setHeader("Cache-Control", "no-store");
        }
        chain.doFilter(request, response);
    }
}
