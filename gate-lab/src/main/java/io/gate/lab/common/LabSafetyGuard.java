package io.gate.lab.common;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * "절대 외부 공개 금지"를 코드로 지키는 안전장치 (설계안 4장).
 * 컨텍스트를 만드는 도중에 확인하므로, 조건이 맞지 않으면 포트를 열기 전에 시작이 중단된다.
 * <ul>
 *   <li>{@code gate.lab.ack}(환경 변수 {@code GATE_LAB_ACK})가 {@code local-only}여야 한다</li>
 *   <li>{@code server.address}가 루프백이어야 한다. 컨테이너 안에서만 예외로 0.0.0.0을 허용한다
 *       (compose가 호스트의 127.0.0.1에만 포트를 연다)</li>
 *   <li>{@code vulnerable}·{@code fixed} 중 정확히 하나의 프로필이 켜져 있어야 한다</li>
 * </ul>
 */
@Component
public class LabSafetyGuard {

    private static final Logger log = LoggerFactory.getLogger(LabSafetyGuard.class);
    static final String ACK = "local-only";

    private final String activeVariant;

    // 생성자는 하나만 둔다 (여러 개면 Spring이 주입할 생성자를 고르지 못한다)
    public LabSafetyGuard(LabProperties props, Environment env) {
        this.activeVariant = verify(props, env.getProperty("server.address"), List.of(env.getActiveProfiles()),
                path -> Files.exists(Path.of(path)));
    }

    /** 조건을 확인하고 켜진 버전 이름(vulnerable·fixed)을 돌려준다. 맞지 않으면 예외. */
    static String verify(LabProperties props, String serverAddress, List<String> profiles,
            Predicate<String> fileExists) {
        if (!ACK.equals(props.ack())) {
            throw new IllegalStateException("gate-lab is intentionally vulnerable and must stay local. "
                    + "Set GATE_LAB_ACK=local-only to confirm you will not expose it.");
        }
        boolean vulnerable = profiles.contains("vulnerable");
        boolean fixed = profiles.contains("fixed");
        if (vulnerable == fixed) {
            throw new IllegalStateException("Activate exactly one profile: vulnerable or fixed (active: " + profiles
                    + ")");
        }
        String variant = vulnerable ? "vulnerable" : "fixed";

        if (serverAddress == null || serverAddress.isBlank()) {
            throw new IllegalStateException("server.address must be set to a loopback address (127.0.0.1)");
        }
        if (isLoopback(serverAddress)) {
            return variant;
        }
        boolean containerAll = props.inContainer() && serverAddress.equals("0.0.0.0") && fileExists.test("/.dockerenv");
        if (!containerAll) {
            throw new IllegalStateException("gate-lab refuses to listen on " + serverAddress
                    + ". Only loopback addresses are allowed (0.0.0.0 only inside a container with "
                    + "GATE_LAB_IN_CONTAINER=true, published to 127.0.0.1 on the host).");
        }
        return variant;
    }

    public String activeVariant() {
        return activeVariant;
    }

    static boolean isLoopback(String address) {
        String a = address.strip();
        if (a.equals("localhost")) {
            return true;
        }
        // 이름 해석을 하지 않도록 IP 리터럴만 판단한다
        if (!a.matches("^[0-9.]+$") && !a.contains(":")) {
            return false;
        }
        try {
            return InetAddress.getByName(a).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void banner() {
        log.warn("");
        log.warn("==================================================================");
        log.warn("  GATE gate-lab [{}] - INTENTIONALLY VULNERABLE PRACTICE APP", activeVariant.toUpperCase());
        log.warn("  Local use only. Never expose this to a network or the internet.");
        log.warn("==================================================================");
    }
}
