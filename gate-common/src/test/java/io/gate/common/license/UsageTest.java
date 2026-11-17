package io.gate.common.license;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 사용 정보는 켜기 전에는 한 건도 보내지 않고, 켠 뒤에도 정해진 항목만 보낸다 (가짜 서버로 확인). */
class UsageTest {

    @TempDir
    Path home;

    HttpServer server;
    final List<String> received = new CopyOnWriteArrayList<>();
    String url;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/usage", ex -> {
            received.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            ex.sendResponseHeaders(204, -1);
            ex.close();
        });
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort() + "/usage";
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    static final License.Info NONE = new License.Info(License.Status.NONE, null, null, 0, null, List.of());

    boolean report(Map<String, String> env) {
        return Usage.report("pkg", "0.1.0", env::get, home, NONE, Usage::httpPost);
    }

    @Test
    void offByDefaultSendsNothing() {
        assertEquals(Usage.Settings.OFF, Usage.load(home));
        assertFalse(report(Map.of()));
        assertEquals(List.of(), received);
    }

    @Test
    void enabledSendsOnlyTheAllowedFields() throws Exception {
        Usage.Settings s = Usage.enable(home, url);
        assertTrue(report(Map.of()));
        assertEquals(1, received.size());
        String body = received.get(0);
        assertTrue(body.startsWith("{\"installId\":\"" + s.installId() + "\",\"module\":\"pkg\",\"version\":\"0.1.0\""),
                body);
        assertTrue(body.contains("\"licenseStatus\":\"NONE\",\"licenseKeyId\":null"), body);
        // 경로·사용자명·호스트명은 들어가지 않는다
        for (String forbidden : new String[] {System.getProperty("user.name"), home.toString(),
            InetAddress.getLocalHost().getHostName(), System.getProperty("user.dir")}) {
            assertFalse(body.contains(forbidden), "body must not contain " + forbidden + ": " + body);
        }
    }

    @Test
    void disablingStopsSendingAndForgetsTheInstallId() throws Exception {
        Usage.enable(home, url);
        Usage.disable(home);
        assertFalse(report(Map.of()));
        assertEquals(List.of(), received);
        assertEquals(null, Usage.load(home).installId());
    }

    @Test
    void environmentSwitchOverridesTheSettings() throws Exception {
        Usage.enable(home, url);
        assertFalse(report(Map.of(Usage.OFF_ENV, "off")));
        assertEquals(List.of(), received);
    }

    @Test
    void failuresAreIgnored() throws Exception {
        Usage.enable(home, "http://127.0.0.1:1/nothing-listens-here");
        assertFalse(report(Map.of()), "a failed send never throws");
    }

    @Test
    void onlyHttpUrlsAreAccepted() {
        assertThrows(IllegalArgumentException.class, () -> Usage.enable(home, "file:///etc/passwd"));
        assertThrows(IllegalArgumentException.class, () -> Usage.enable(home, null));
    }

    @Test
    void payloadStripsUnexpectedCharacters() {
        String p = Usage.payload("id\"},\"x\":\"y", "pkg", "0.1.0", NONE, java.time.LocalDate.of(2026, 11, 17));
        assertFalse(p.contains("\"x\""), p);
    }
}
