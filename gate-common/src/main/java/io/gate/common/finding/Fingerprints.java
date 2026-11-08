package io.gate.common.finding;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 규칙 + 위치로 SHA-256 지문을 만든다. */
public final class Fingerprints {

    private Fingerprints() {
    }

    public static String of(String ruleId, Location location) {
        String canonical = ruleId + "|" + canonical(location);
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    // Java 17에서는 sealed 타입 switch 패턴 매칭이 정식 기능이 아니므로 instanceof로 분기한다.
    private static String canonical(Location location) {
        if (location instanceof Location.Package p) {
            return "package|" + p.ecosystem() + "|" + p.name() + "|" + p.version();
        }
        if (location instanceof Location.File f) {
            return "file|" + f.path() + "|" + f.line();
        }
        if (location instanceof Location.Endpoint e) {
            return "endpoint|" + e.method() + "|" + e.path();
        }
        throw new IllegalArgumentException("unknown location kind: " + location.kind());
    }
}
