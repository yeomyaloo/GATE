package io.gate.authz.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gate.authz.lab.LabServers;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** CLI와 설정 파일. 실습장을 대상으로 실제로 실행한다. */
class CliTest {

    @TempDir
    Path dir;

    final Map<String, String> env = Map.of("PW_A", LabServers.DEMO_PASSWORD, "PW_B", LabServers.DEMO_PASSWORD);

    record Run(int code, String out, String err) {
    }

    Run run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = new GateAuthzCli(env::get).run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Run(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    Path config(String base, String extra) throws Exception {
        Path f = dir.resolve("gate-authz.yml");
        Files.writeString(f, """
                spec: %s/openapi.yaml
                baseUrl: %s
                login:
                  path: /api/auth/login
                  body: { username: "{username}", password: "{password}" }
                users:
                  a: { username: alice, passwordEnv: PW_A }
                  b: { username: bob, passwordEnv: PW_B }
                maxRequestsPerSecond: 50
                %s
                """.formatted(base, base, extra));
        return f;
    }

    @Test
    void findingsExitTwoWithJson() throws Exception {
        Run r = run("scan", "--config", config(LabServers.vulnerableUrl(), "allowMutation: true").toString(),
                "--format", "json");
        assertEquals(GateAuthzCli.FOUND, r.code(), r.err());
        JsonNode root = JsonMapper.builder().build().readTree(r.out());
        assertEquals("BLOCK", root.path("decision").asString());
        assertEquals(3, root.path("findings").size(), r.out());
        assertFalse(r.out().contains(LabServers.DEMO_PASSWORD), "password never printed");
        assertFalse(r.out().contains("eyJ"), "tokens never printed");
    }

    @Test
    void fixedVariantExitsZeroWithText() throws Exception {
        Run r = run("scan", "--config", config(LabServers.fixedUrl(), "allowMutation: true").toString());
        assertEquals(GateAuthzCli.PASS, r.code(), r.out() + r.err());
        assertTrue(r.out().contains("PASS") && r.out().contains("GET /api/orders/{id}"), r.out());
    }

    @Test
    void specFileRelativeToConfig() throws Exception {
        Files.copy(Path.of("..", "gate-lab", "src", "main", "resources", "static", "openapi.yaml"),
                dir.resolve("spec.yaml"));
        Path cfg = config(LabServers.vulnerableUrl(), "");
        Files.writeString(cfg, Files.readString(cfg).replaceFirst("spec: .*", "spec: spec.yaml"));
        assertEquals(GateAuthzCli.FOUND, run("scan", "--config", cfg.toString()).code());
    }

    @Test
    void nonLoopbackTargetIsRefusedBeforeAnyRequest() throws Exception {
        Run r = run("scan", "--config", config("http://192.0.2.10:8080", "").toString());
        assertEquals(GateAuthzCli.UNCHECKABLE, r.code());
        assertTrue(r.err().contains("not allowed"), r.err());
    }

    @Test
    void passwordInConfigIsRejected() throws Exception {
        Path cfg = config(LabServers.vulnerableUrl(), "");
        Files.writeString(cfg, Files.readString(cfg).replace("password: \"{password}\"", "password: \"hunter2\""));
        Run r = run("scan", "--config", cfg.toString());
        assertEquals(GateAuthzCli.UNCHECKABLE, r.code());
        assertTrue(r.err().contains("must be \"{password}\""), r.err());
    }

    @Test
    void missingPasswordEnvAndBadUsage() throws Exception {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = new GateAuthzCli(name -> null).run(
                new String[] {"scan", "--config", config(LabServers.vulnerableUrl(), "").toString()},
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(err, true, StandardCharsets.UTF_8));
        assertEquals(GateAuthzCli.UNCHECKABLE, code);
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("PW_A"), "names the missing variable");
        assertEquals(GateAuthzCli.UNCHECKABLE, run("scan").code());
        assertEquals(GateAuthzCli.UNCHECKABLE, run("nope").code());
        assertTrue(run("version").out().contains(GateAuthzCli.VERSION));
    }
}
