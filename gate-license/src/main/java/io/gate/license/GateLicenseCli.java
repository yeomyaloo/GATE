package io.gate.license;

import io.gate.common.license.License;
import io.gate.common.license.Usage;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 라이선스 키 발급·확인, 사용 정보(opt-in) 설정.
 * <pre>
 * gate-license status
 * gate-license verify &lt;KEY&gt;
 * gate-license issue --private-key FILE --licensee NAME --seats N --expires YYYY-MM-DD [--modules a,b] [--id ID]
 * gate-license usage status|preview|off
 * gate-license usage on --url URL
 * </pre>
 * 개인 키 파일(PKCS#8, base64)은 저장소 밖에 둔다. 이 도구는 경로를 받아 읽기만 하고, 키 내용을 출력하지 않는다.
 */
public final class GateLicenseCli {

    static final int OK = 0;
    static final int INVALID = 1;
    static final int ERROR = 3;

    private final Path home;
    private final LocalDate today;

    GateLicenseCli(Path home, LocalDate today) {
        this.home = home;
        this.today = today;
    }

    public static void main(String[] args) {
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(System.err, true, StandardCharsets.UTF_8);
        System.exit(new GateLicenseCli(Path.of(System.getProperty("user.home")), LocalDate.now(ZoneOffset.UTC))
                .run(args, System::getenv, out, err));
    }

    int run(String[] args, java.util.function.Function<String, String> env, PrintStream out, PrintStream err) {
        try {
            if (args.length == 0) {
                err.print(usage());
                return ERROR;
            }
            switch (args[0]) {
                case "status": {
                    License.Info i = License.current(env, home, today);
                    out.println(i.status() + "  " + i.notice());
                    return i.status() == License.Status.VALID || i.status() == License.Status.NONE ? OK : INVALID;
                }
                case "verify": {
                    if (args.length != 2) {
                        err.print(usage());
                        return ERROR;
                    }
                    License.Info i = License.current(Map.of(License.ENV, args[1])::get, home, today);
                    out.println(i.status() + "  " + i.notice());
                    return i.status() == License.Status.VALID ? OK : INVALID;
                }
                case "issue":
                    return issue(args, out, err);
                case "usage":
                    return usageCommand(args, out, err);
                default:
                    err.print(usage());
                    return ERROR;
            }
        } catch (IOException | GeneralSecurityException | IllegalArgumentException e) {
            err.println("gate-license: " + e.getMessage());
            return ERROR;
        }
    }

    private int issue(String[] args, PrintStream out, PrintStream err) throws IOException, GeneralSecurityException {
        Map<String, String> opt = options(args);
        for (String required : new String[] {"private-key", "licensee", "seats", "expires"}) {
            if (!opt.containsKey(required)) {
                err.println("missing --" + required);
                err.print(usage());
                return ERROR;
            }
        }
        int seats = Integer.parseInt(opt.get("seats"));
        LocalDate expires = LocalDate.parse(opt.get("expires"));
        if (seats < 1 || !expires.isAfter(today)) {
            throw new IllegalArgumentException("seats must be positive and expires must be in the future");
        }
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("id", opt.getOrDefault("id", "LIC-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase()));
        fields.put("licensee", opt.get("licensee"));
        fields.put("seats", String.valueOf(seats));
        fields.put("issuedAt", License.nowIso());
        fields.put("expiresAt", expires.toString());
        if (opt.containsKey("modules")) {
            fields.put("modules", opt.get("modules"));
        }
        String key = License.sign(fields, readPrivateKey(Path.of(opt.get("private-key"))));
        // 발급한 키가 실제 공개 키로 검증되는지 바로 확인한다 (다른 개인 키로 서명한 실수 방지)
        License.Info check = License.current(Map.of(License.ENV, key)::get, home, today);
        if (check.status() != License.Status.VALID) {
            err.println("gate-license: the private key does not match GATE's public key; no key issued");
            return ERROR;
        }
        out.println(key);
        err.println("issued " + fields.get("id") + " for " + fields.get("licensee") + ", " + seats + " seats, until "
                + expires);
        return OK;
    }

    static PrivateKey readPrivateKey(Path file) throws IOException, GeneralSecurityException {
        String b64 = Files.readString(file).strip();
        return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder()
                .decode(b64)));
    }

    private int usageCommand(String[] args, PrintStream out, PrintStream err) throws IOException {
        String sub = args.length > 1 ? args[1] : "status";
        switch (sub) {
            case "status": {
                Usage.Settings s = Usage.load(home);
                out.println(s.enabled() ? "usage reporting: ON to " + s.url() + " (install id " + s.installId() + ")"
                        : "usage reporting: OFF (nothing is sent)");
                return OK;
            }
            case "preview": {
                Usage.Settings s = Usage.load(home);
                out.println("When turned on, each CLI run sends exactly this (nothing else):");
                out.println(Usage.payload(s.installId() != null ? s.installId() : "<random id created when turned on>",
                        "pkg", "0.1.0", License.current(System::getenv, home, today), today));
                out.println("Never sent: code, file paths, package names, scan results, user name, host name, "
                        + "repository URL. See PRIVACY.md.");
                return OK;
            }
            case "on": {
                Map<String, String> opt = options(args);
                if (!opt.containsKey("url")) {
                    err.println("usage on needs --url (the address you choose to receive the data)");
                    return ERROR;
                }
                Usage.Settings s = Usage.enable(home, opt.get("url"));
                out.println("usage reporting: ON to " + s.url() + " (install id " + s.installId() + ")");
                return OK;
            }
            case "off":
                Usage.disable(home);
                out.println("usage reporting: OFF (install id deleted)");
                return OK;
            default:
                err.print(usage());
                return ERROR;
        }
    }

    private static Map<String, String> options(String[] args) {
        Map<String, String> opt = new HashMap<>();
        for (int i = 1; i < args.length; i++) {
            if (args[i].startsWith("--") && i + 1 < args.length) {
                opt.put(args[i].substring(2), args[++i]);
            }
        }
        return opt;
    }

    static String usage() {
        return String.join(System.lineSeparator(),
                "gate-license - GATE commercial license keys and opt-in usage reporting",
                "",
                "  gate-license status                      show the license in GATE_LICENSE_KEY or ~/.gate/license.key",
                "  gate-license verify <KEY>                check a key",
                "  gate-license issue --private-key FILE --licensee NAME --seats N --expires YYYY-MM-DD"
                        + " [--modules a,b] [--id ID]",
                "  gate-license usage status|preview|off",
                "  gate-license usage on --url URL          opt in to sending usage info (see PRIVACY.md)",
                "") + System.lineSeparator();
    }
}
