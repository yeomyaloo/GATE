package io.gate.lab.common;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/** 두 버전이 함께 쓰는 보안 부품: 비밀번호 해시, JWT 서명 키. */
@Configuration
public class LabSecurityBeans {

    static final int MIN_SECRET_BYTES = 32;

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /** HS256 서명 키. 지정하지 않으면 시작할 때마다 무작위 32바이트. */
    @Bean
    SecretKey jwtSigningKey(LabProperties props) {
        byte[] bytes;
        if (props.jwtSecret() == null || props.jwtSecret().isBlank()) {
            bytes = new byte[MIN_SECRET_BYTES];
            new SecureRandom().nextBytes(bytes);
        } else {
            bytes = props.jwtSecret().getBytes(StandardCharsets.UTF_8);
            if (bytes.length < MIN_SECRET_BYTES) {
                throw new IllegalStateException("GATE_LAB_JWT_SECRET must be at least " + MIN_SECRET_BYTES + " bytes");
            }
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    /** 로그인은 AuthController가 직접 처리한다. 기본 사용자 자동 생성을 막기 위한 빈. */
    @Bean
    UserDetailsService noUserDetailsService() {
        return username -> {
            throw new UsernameNotFoundException("not used");
        };
    }
}
