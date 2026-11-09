package io.gate.authz.lab;

import io.gate.lab.LabApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 테스트용 실습장(gate-lab) 두 버전을 한 번만 띄워 여러 테스트 클래스가 함께 쓴다 (127.0.0.1, 임의 포트).
 * 데모 비밀번호는 테스트에서만 쓰는 가짜 값이다.
 */
public final class LabServers {

    public static final String DEMO_PASSWORD = "test-only-authz-demo-password";

    private static ConfigurableApplicationContext vulnerable;
    private static ConfigurableApplicationContext fixed;

    private LabServers() {
    }

    public static synchronized String vulnerableUrl() {
        if (vulnerable == null) {
            vulnerable = start("vulnerable");
        }
        return url(vulnerable);
    }

    public static synchronized String fixedUrl() {
        if (fixed == null) {
            fixed = start("fixed");
        }
        return url(fixed);
    }

    private static ConfigurableApplicationContext start(String variant) {
        ConfigurableApplicationContext ctx = new SpringApplicationBuilder(LabApplication.class).profiles(variant)
                .run("--gate.lab.ack=local-only", "--server.port=0", "--server.address=127.0.0.1",
                        "--gate.lab.demo-password=" + DEMO_PASSWORD, "--spring.main.banner-mode=off");
        Runtime.getRuntime().addShutdownHook(new Thread(ctx::close));
        return ctx;
    }

    private static String url(ConfigurableApplicationContext ctx) {
        return "http://127.0.0.1:" + ctx.getEnvironment().getProperty("local.server.port");
    }
}
