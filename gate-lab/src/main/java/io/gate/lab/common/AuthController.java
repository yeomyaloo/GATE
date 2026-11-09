package io.gate.lab.common;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import javax.crypto.SecretKey;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** 로그인: 아이디·비밀번호를 확인하고 HS256 JWT(유효 1시간)를 발급한다. */
@RestController
public class AuthController {

    static final Duration TOKEN_TTL = Duration.ofHours(1);

    public record LoginRequest(String username, String password) {
    }

    private final LabRepository repository;
    private final PasswordEncoder encoder;
    private final JwtEncoder jwtEncoder;

    public AuthController(LabRepository repository, PasswordEncoder encoder, SecretKey jwtSigningKey) {
        this.repository = repository;
        this.encoder = encoder;
        this.jwtEncoder = NimbusJwtEncoder.withSecretKey(jwtSigningKey).build();
    }

    @PostMapping("/api/auth/login")
    public Map<String, Object> login(@RequestBody LoginRequest request) {
        if (request == null || request.username() == null || request.password() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "username and password are required");
        }
        var user = repository.findUser(request.username())
                .filter(u -> encoder.matches(request.password(), u.passwordHash()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid credentials"));
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("gate-lab")
                .subject(user.username())
                .issuedAt(now)
                .expiresAt(now.plus(TOKEN_TTL))
                .claim("role", user.role())
                .build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
        return Map.of("accessToken", token, "tokenType", "Bearer", "expiresIn", TOKEN_TTL.toSeconds());
    }
}
