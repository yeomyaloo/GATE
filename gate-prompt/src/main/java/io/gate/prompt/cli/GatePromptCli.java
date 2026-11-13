package io.gate.prompt.cli;

import io.gate.prompt.measure.Corpus;
import io.gate.prompt.measure.MeasureReport;
import io.gate.prompt.measure.PromptMeasurement;
import io.gate.prompt.model.ScriptedModel;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * gate-prompt 명령줄.
 * <pre>
 * gate-prompt measure [--out FILE]
 * </pre>
 * 공격·정상 사례 묶음을 방어 방식 4가지로 실행해 비교한다. {@code GATE_SERVER_URL}이 있으면 실습장 고친 버전과 같은 방식의 결과를
 * gate-server에 저장한다(제한 시간 2초, 실패해도 종료 코드에 영향 없음).
 * 종료 코드: 0 출력 관문(OUTPUT_ONLY·BOTH)을 통과한 공격 없음 · 1 통과한 공격 있음 · 3 실행 오류.
 */
public final class GatePromptCli {

    static final int OK = 0;
    static final int ATTACK_GOT_THROUGH = 1;
    static final int ERROR = 3;

    private final Function<String, String> env;

    GatePromptCli(Function<String, String> env) {
        this.env = env;
    }

    public static void main(String[] args) {
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(System.err, true, StandardCharsets.UTF_8);
        System.exit(new GatePromptCli(System::getenv).run(args, out, err));
    }

    int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 0 || !args[0].equals("measure")) {
            err.println("usage: gate-prompt measure [--out FILE]");
            return ERROR;
        }
        Path outFile = null;
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--out") && i + 1 < args.length) {
                outFile = Path.of(args[++i]);
            } else {
                err.println("unknown option: " + args[i]);
                return ERROR;
            }
        }
        Instant started = Instant.now();
        List<PromptMeasurement.Outcome> outcomes = new PromptMeasurement(new ScriptedModel()).run(Corpus.load());
        Instant finished = Instant.now();
        out.print(MeasureReport.text(outcomes));
        try {
            if (outFile != null) {
                Files.writeString(outFile, MeasureReport.json(outcomes, finished) + System.lineSeparator(),
                        StandardCharsets.UTF_8);
                out.println("raw results: " + outFile);
            }
        } catch (IOException e) {
            err.println("gate-prompt: " + e.getMessage());
            return ERROR;
        }
        report(outcomes, started, finished, out, err);
        var summary = MeasureReport.summarize(outcomes);
        boolean through = summary.get(PromptMeasurement.Defense.OUTPUT_ONLY).attacksSucceeded() > 0
                || summary.get(PromptMeasurement.Defense.BOTH).attacksSucceeded() > 0;
        return through ? ATTACK_GOT_THROUGH : OK;
    }

    private void report(List<PromptMeasurement.Outcome> outcomes, Instant started, Instant finished, PrintStream out,
            PrintStream err) {
        String server = env.apply("GATE_SERVER_URL");
        if (server == null || server.isBlank() || !server.toLowerCase(Locale.ROOT).matches("^https?://.*")) {
            return;
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(server.replaceAll("/+$", "") + "/api/scan-runs"))
                    .timeout(Duration.ofSeconds(2)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(MeasureReport.serverBody(outcomes, started, finished)))
                    .build();
            int status = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
                    .send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
            out.println(status / 100 == 2 ? "reported to " + server : "report rejected: HTTP " + status);
        } catch (IOException | IllegalArgumentException e) {
            err.println("could not report to gate-server: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
