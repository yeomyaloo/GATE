package io.gate.lab.vulnerable;

import io.gate.lab.common.LabHttpSecurity;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

@Configuration
@Profile("vulnerable")
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return LabHttpSecurity.common(http)
                .addFilterBefore(new BearerTokenFilter(), AnonymousAuthenticationFilter.class)
                .build();
    }
}
