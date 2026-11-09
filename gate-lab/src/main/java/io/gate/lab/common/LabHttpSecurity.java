package io.gate.lab.common;

import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

/** 두 버전이 함께 쓰는 HTTP 보안 설정: 공개 경로, 무상태 세션, 401 응답. */
public final class LabHttpSecurity {

    public static final String[] PUBLIC_PATHS = {"/api/auth/login", "/api/products/**", "/openapi.yaml", "/error"};

    private LabHttpSecurity() {
    }

    public static HttpSecurity common(HttpSecurity http) throws Exception {
        return http
                .csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable())
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .authorizeHttpRequests(a -> a
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .anyRequest().authenticated());
    }
}
