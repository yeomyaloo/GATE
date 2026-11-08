package io.gate.pkg.cache;

import io.gate.pkg.http.HttpResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 외부 API 응답을 파일로 보관하는 캐시. 키는 SHA-256으로 만들어 파일 이름으로 쓴다.
 * 파일 하나에 {@code storedAt}, {@code expiresAt}, {@code status}, {@code body}를 담는다.
 *
 * <p>캐시는 성능 장치일 뿐이므로, 읽기·쓰기 실패는 "캐시 없음"으로 처리하고 검사를 계속한다.
 */
public final class FileCache {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Path dir;
    private final Clock clock;

    public FileCache(Path dir, Clock clock) {
        this.dir = dir;
        this.clock = clock;
    }

    /** 기본 위치: {@code GATE_CACHE_DIR} 환경 변수, 없으면 {@code ~/.gate/cache}. */
    public static Path defaultDir() {
        String env = System.getenv("GATE_CACHE_DIR");
        if (env != null && !env.isBlank()) {
            return Path.of(env);
        }
        return Path.of(System.getProperty("user.home"), ".gate", "cache");
    }

    public Optional<HttpResult> get(String key) {
        Path file = fileFor(key);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            JsonNode node = JSON.readTree(Files.readString(file, StandardCharsets.UTF_8));
            Instant expiresAt = Instant.parse(node.path("expiresAt").asString());
            if (!clock.instant().isBefore(expiresAt)) {
                return Optional.empty();
            }
            return Optional.of(new HttpResult(node.path("status").asInt(), node.path("body").asString()));
        } catch (IOException | RuntimeException e) {
            // 깨진 캐시 파일은 없는 것으로 본다
            return Optional.empty();
        }
    }

    public void put(String key, HttpResult result, Duration ttl) {
        Instant now = clock.instant();
        ObjectNode node = JSON.createObjectNode();
        node.put("storedAt", now.toString());
        node.put("expiresAt", now.plus(ttl).toString());
        node.put("status", result.status());
        node.put("body", result.body());
        try {
            Files.createDirectories(dir);
            Path tmp = Files.createTempFile(dir, "entry", ".tmp");
            Files.writeString(tmp, JSON.writeValueAsString(node), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, fileFor(key), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, fileFor(key), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | UncheckedIOException e) {
            // 캐시 저장 실패는 검사 결과에 영향을 주지 않는다
        }
    }

    public static String key(String method, String url, String body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((method + "\n" + url + "\n" + (body == null ? "" : body)).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private Path fileFor(String key) {
        return dir.resolve(key + ".json");
    }
}
