package io.gate.common.license;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GATE 라이선스 확인 (docs/design/licensing.md).
 *
 * <p>GATE는 PolyForm Noncommercial 1.0.0으로 배포된다: 개인·비상업적 사용은 무료, 기업의 상업적 사용은 상업 라이선스가 필요하다.
 * 상업 라이선스 키는 {@code base64url(내용 JSON) "." base64url(Ed25519 서명)} 형식이고 <b>오프라인으로 검증</b>한다
 * (키 확인 때문에 데이터가 밖으로 나가지 않는다). 키가 없어도 모든 기능은 동작하고 안내 문구만 보인다.
 */
public final class License {

    /** 라이선스 서명 공개 키 (Ed25519, X.509). 개인 키는 저장소 밖에 있다. */
    static final String PUBLIC_KEY = "MCowBQYDK2VwAyEAfz511dwBRnUDpVLYnmTJmEAcLumgUtG8txQnpfOkmk0=";
    public static final String ENV = "GATE_LICENSE_KEY";
    public static final String COMMERCIAL_URL = "https://github.com/yeomyaloo/GATE/blob/main/COMMERCIAL.md";
    static final int MAX_KEY = 4_096;

    public enum Status { VALID, EXPIRED, INVALID, NONE }

    /**
     * @param licensee  회사명 (VALID·EXPIRED일 때)
     * @param keyId     키 ID (VALID·EXPIRED일 때)
     * @param expiresAt 만료일 (포함)
     */
    public record Info(Status status, String keyId, String licensee, int seats, LocalDate expiresAt,
            List<String> modules) {
        public Info {
            modules = modules == null ? List.of() : List.copyOf(modules);
        }

        static Info of(Status s) {
            return new Info(s, null, null, 0, null, List.of());
        }

        /** 사람이 읽는 한 줄 (영어: 로그·CI 출력에 그대로 쓴다). */
        public String notice() {
            return switch (status) {
                case VALID -> "GATE: commercial license " + keyId + " for " + licensee + " (valid until " + expiresAt
                        + ")";
                case EXPIRED -> "GATE: commercial license " + keyId + " for " + licensee + " expired on " + expiresAt
                        + ". Renew: " + COMMERCIAL_URL;
                case INVALID -> "GATE: the license key could not be verified. Noncommercial use only (PolyForm "
                        + "Noncommercial 1.0.0); commercial use needs a license: " + COMMERCIAL_URL;
                case NONE -> "GATE: free for noncommercial use (PolyForm Noncommercial 1.0.0). Commercial use needs a "
                        + "license: " + COMMERCIAL_URL;
            };
        }
    }

    private License() {
    }

    /** 환경 변수 {@value #ENV}, 없으면 {@code ~/.gate/license.key}에서 키를 읽어 확인한다. */
    public static Info current(Function<String, String> env, Path home, LocalDate today) {
        String key = env.apply(ENV);
        if (key == null || key.isBlank()) {
            Path file = home.resolve(".gate").resolve("license.key");
            try {
                key = Files.isRegularFile(file) && Files.size(file) <= MAX_KEY ? Files.readString(file) : null;
            } catch (java.io.IOException e) {
                key = null;
            }
        }
        return key == null || key.isBlank() ? Info.of(Status.NONE) : verify(key.strip(), publicKey(), today);
    }

    public static Info current() {
        return current(System::getenv, Path.of(System.getProperty("user.home")), LocalDate.now(ZoneOffset.UTC));
    }

    static PublicKey publicKey() {
        try {
            return KeyFactory.getInstance("Ed25519")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(PUBLIC_KEY)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("license public key is unusable", e);
        }
    }

    /** 키를 확인한다. 서명이 맞지 않거나 형식이 틀리면 INVALID (내용을 믿지 않는다). */
    public static Info verify(String key, PublicKey publicKey, LocalDate today) {
        if (key.length() > MAX_KEY) {
            return Info.of(Status.INVALID);
        }
        String[] parts = key.split("\\.");
        if (parts.length != 2) {
            return Info.of(Status.INVALID);
        }
        try {
            Base64.Decoder b64 = Base64.getUrlDecoder();
            byte[] payload = b64.decode(parts[0]);
            Signature sig = Signature.getInstance("Ed25519");
            sig.initVerify(publicKey);
            sig.update(payload);
            if (!sig.verify(b64.decode(parts[1]))) {
                return Info.of(Status.INVALID);
            }
            Map<String, String> fields = parseFlatJson(new String(payload, StandardCharsets.UTF_8));
            LocalDate expires = LocalDate.parse(fields.get("expiresAt"));
            Info info = new Info(Status.VALID, require(fields, "id"), require(fields, "licensee"),
                    Integer.parseInt(require(fields, "seats")), expires,
                    fields.getOrDefault("modules", "").isBlank() ? List.of()
                            : List.of(fields.get("modules").split(",")));
            return today.isAfter(expires) ? new Info(Status.EXPIRED, info.keyId(), info.licensee(), info.seats(),
                    expires, info.modules()) : info;
        } catch (GeneralSecurityException | RuntimeException e) {
            return Info.of(Status.INVALID);
        }
    }

    /** 발급 도구(gate-license)용: 내용을 서명해 키 문자열을 만든다. */
    public static String sign(Map<String, String> fields, java.security.PrivateKey privateKey)
            throws GeneralSecurityException {
        byte[] payload = toFlatJson(fields).getBytes(StandardCharsets.UTF_8);
        Signature sig = Signature.getInstance("Ed25519");
        sig.initSign(privateKey);
        sig.update(payload);
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        return b64.encodeToString(payload) + "." + b64.encodeToString(sig.sign());
    }

    /** 키 내용 필드 순서 (발급 시 이 순서로 쓴다). */
    public static final List<String> FIELDS = List.of("id", "licensee", "seats", "issuedAt", "expiresAt", "modules");

    private static String require(Map<String, String> f, String name) {
        String v = f.get(name);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("missing " + name);
        }
        return v;
    }

    // 외부 라이브러리 없이(gate-common은 의존성이 없다) 문자열 값만 있는 단순 JSON 객체를 읽고 쓴다
    private static final Pattern PAIR = Pattern.compile("\"([a-zA-Z]+)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    static Map<String, String> parseFlatJson(String json) {
        String t = json.strip();
        if (!t.startsWith("{") || !t.endsWith("}")) {
            throw new IllegalArgumentException("not an object");
        }
        Map<String, String> out = new LinkedHashMap<>();
        Matcher m = PAIR.matcher(t);
        while (m.find()) {
            if (out.put(m.group(1), m.group(2).replace("\\\"", "\"").replace("\\\\", "\\")) != null) {
                throw new IllegalArgumentException("duplicate " + m.group(1));
            }
        }
        return out;
    }

    static String toFlatJson(Map<String, String> fields) {
        List<String> parts = new ArrayList<>();
        for (String k : FIELDS) {
            if (fields.containsKey(k)) {
                parts.add("\"" + k + "\":\"" + fields.get(k).replace("\\", "\\\\").replace("\"", "\\\"") + "\"");
            }
        }
        for (String k : fields.keySet()) {
            if (!FIELDS.contains(k)) {
                throw new IllegalArgumentException("unknown field " + k);
            }
        }
        return "{" + String.join(",", parts) + "}";
    }

    /** 오늘 날짜(UTC) 기준 발급 시각 문자열. */
    public static String nowIso() {
        return Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString();
    }
}
