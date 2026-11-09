package io.gate.lab.fixed;

import io.gate.lab.common.LabHttpSecurity;
import javax.crypto.SecretKey;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 토큰 검증: Spring Security 리소스 서버. HS256 서명을 검증하고 알고리즘을 HS256으로 고정하며
 * 만료 시각(exp)을 확인한다(기본 검증기). 서명이 없거나({@code alg: none}) 다른 키로 서명된 토큰은 401.
 */
@Configuration
@Profile("fixed")
public class SecurityConfig {

    @Bean
    JwtDecoder jwtDecoder(SecretKey jwtSigningKey) {
        return NimbusJwtDecoder.withSecretKey(jwtSigningKey).macAlgorithm(MacAlgorithm.HS256).build();
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return LabHttpSecurity.common(http)
                .oauth2ResourceServer(o -> o.jwt(j -> { }))
                .build();
    }
}
