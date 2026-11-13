package io.gate.review.cli;

import com.anthropic.models.messages.OutputConfig;
import io.gate.review.SourceFile;
import io.gate.review.ai.AiReviewer;
import io.gate.review.measure.GroundTruth;
import io.gate.review.measure.Measurement;
import io.gate.review.measure.Neutralizer;
import io.gate.review.measure.Pricing;
import io.gate.review.measure.Scorer;
import io.gate.review.staticscan.ScanWorkspace;
import io.gate.review.staticscan.SemgrepRunner;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code gate-review measure}: 실습장 정답지로 검출·오탐·미탐을 잰다.
 * <pre>
 * gate-review measure [--repo DIR] [--rules PATH|p/java] [--out FILE]
 *                     [--ai --budget-usd X [--runs N] [--effort LEVEL]] [--estimate]
 * </pre>
 * AI 측정은 비용이 드므로 {@code --ai}와 예산({@code --budget-usd})을 모두 명시해야 한다.
 * {@code --estimate}는 호출 없이 예상 토큰·비용만 출력한다.
 */
final class MeasureCommand {

    static final String GROUND_TRUTH = "docs/lab/ground-truth.json";
    static final String LAB_SOURCES = "gate-lab/src/main/java";

    private final Function<String, String> env;
    private final Function<Optional<OutputConfig.Effort>, Optional<AiReviewer>> aiFactory;
    private final Supplier<SemgrepRunner> semgrep;

    MeasureCommand(Function<String, String> env, Function<Optional<OutputConfig.Effort>, Optional<AiReviewer>> ai,
            Supplier<SemgrepRunner> semgrep) {
        this.env = env;
        this.aiFactory = ai;
        this.semgrep = semgrep;
    }

    int run(String[] args, PrintStream out, PrintStream err) {
        Path repo = Path.of("").toAbsolutePath();
        String rules = GateReviewCli.DEFAULT_RULES;
        Path outFile = null;
        boolean useAi = false;
        boolean estimate = false;
        int runs = 3;
        Double budget = null;
        OutputConfig.Effort effort = OutputConfig.Effort.HIGH;
        try {
            for (int i = 1; i < args.length; i++) {
                String a = args[i];
                boolean hasValue = i + 1 < args.length;
                if (hasValue && a.equals("--repo")) {
                    repo = Path.of(args[++i]);
                } else if (hasValue && a.equals("--rules")) {
                    rules = args[++i];
                } else if (hasValue && a.equals("--out")) {
                    outFile = Path.of(args[++i]);
                } else if (hasValue && a.equals("--runs")) {
                    runs = Integer.parseInt(args[++i]);
                } else if (hasValue && a.equals("--budget-usd")) {
                    budget = Double.parseDouble(args[++i]);
                } else if (hasValue && a.equals("--effort")) {
                    effort = OutputConfig.Effort.of(args[++i].toLowerCase(Locale.ROOT));
                } else if (a.equals("--ai")) {
                    useAi = true;
                } else if (a.equals("--estimate")) {
                    estimate = true;
                } else {
                    err.println("unknown option: " + a);
                    return GateReviewCli.ERROR;
                }
            }
        } catch (NumberFormatException e) {
            err.println("invalid number: " + e.getMessage());
            return GateReviewCli.ERROR;
        }
        if (runs < 1 || runs > 10) {
            err.println("--runs must be between 1 and 10");
            return GateReviewCli.ERROR;
        }
        try {
            GroundTruth truth = GroundTruth.parse(Files.readString(repo.resolve(GROUND_TRUTH), StandardCharsets.UTF_8));
            List<SourceFile> lab = labFiles(repo);
            if (estimate) {
                out.print(estimate(lab, runs));
                return GateReviewCli.PASS;
            }
            Optional<AiReviewer> ai = Optional.empty();
            if (useAi) {
                if (budget == null || budget <= 0) {
                    err.println("--ai needs an explicit --budget-usd (AI calls cost money)");
                    return GateReviewCli.ERROR;
                }
                ai = aiFactory.apply(Optional.of(effort));
                if (ai.isEmpty()) {
                    err.println("--ai needs ANTHROPIC_API_KEY in the environment");
                    return GateReviewCli.ERROR;
                }
            }
            // 규칙 파일은 절대 경로로 (검사는 임시 폴더에서 돈다). p/java 같은 레지스트리 이름은 그대로
            String config = Files.exists(repo.resolve(rules)) ? repo.resolve(rules).toAbsolutePath().toString()
                    : rules;
            SemgrepRunner runner = semgrep.get();
            Measurement m = new Measurement(truth, lab, files -> ScanWorkspace.scan(runner, config, files),
                    Clock.systemUTC());
            Measurement.Result r = m.run(rules, ai, Pricing.OPUS_5_5.model(), effort.toString(), runs,
                    Pricing.OPUS_5_5, budget == null ? 0 : budget);
            out.print(summary(r));
            if (outFile != null) {
                Files.writeString(outFile, JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build()
                        .writeValueAsString(r) + System.lineSeparator(), StandardCharsets.UTF_8);
                out.println("raw results: " + outFile);
            }
            return GateReviewCli.PASS;
        } catch (IOException | UncheckedIOException | IllegalArgumentException | IllegalStateException e) {
            err.println("gate-review measure: " + e.getMessage());
            return GateReviewCli.ERROR;
        }
    }

    static List<SourceFile> labFiles(Path repo) throws IOException {
        Path root = repo.resolve(LAB_SOURCES);
        List<SourceFile> files = new ArrayList<>();
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : s.filter(x -> x.toString().endsWith(".java")).sorted().toList()) {
                files.add(new SourceFile(repo.relativize(p).toString().replace('\\', '/'),
                        Files.readString(p, StandardCharsets.UTF_8)));
            }
        }
        return files;
    }

    static String estimate(List<SourceFile> lab, int runs) {
        StringBuilder sb = new StringBuilder("Estimated AI cost (no API call made)\n");
        long totalIn = 0;
        for (String v : Measurement.VARIANTS) {
            long in = Pricing.estimateInputTokens(Neutralizer.prepare(lab, v).files(), List.of());
            totalIn += in;
            sb.append(String.format("  %-10s input ~%,d tokens, output assumed ~%,d tokens%n", v, in,
                    Pricing.ASSUMED_OUTPUT_TOKENS));
        }
        Pricing p = Pricing.OPUS_5_5;
        double perRun = p.cost(totalIn, Pricing.ASSUMED_OUTPUT_TOKENS * Measurement.VARIANTS.size());
        double worst = p.cost(totalIn, 16_000L * Measurement.VARIANTS.size());
        sb.append(String.format("  model %s ($%.0f / $%.0f per 1M input / output tokens)%n", p.model(),
                p.inputPerMillion(), p.outputPerMillion()));
        sb.append(String.format("  per run (2 variants): ~$%.2f, at most ~$%.2f%n", perRun, worst));
        sb.append(String.format("  %d runs: ~$%.2f, at most ~$%.2f (a format retry can add one more request)%n", runs,
                perRun * runs, worst * runs));
        return sb.toString();
    }

    static String summary(Measurement.Result r) {
        StringBuilder sb = new StringBuilder("GATE gate-review measurement  static: ").append(r.staticEngine())
                .append(" (").append(r.staticConfig()).append(")  AI: ")
                .append(r.model() == null ? "not used" : r.model() + " effort " + r.effort())
                .append("  runs: ").append(r.requestedRuns()).append("\n\n");
        sb.append(String.format("%-16s %-28s %-14s %-14s%n", "method", "detected (vulnerable)", "FP vulnerable",
                "FP fixed"));
        String[] names = {"static only", "AI only", "combined BLOCK", "combined all"};
        for (int k = 0; k < names.length; k++) {
            final int idx = k;
            List<Scorer.Score> vuln = new ArrayList<>();
            List<Scorer.Score> fixed = new ArrayList<>();
            for (Measurement.Run run : r.runs()) {
                for (Measurement.VariantRun v : run.variants()) {
                    Scorer.Score s = pick(v.scores(), idx);
                    if (s != null) {
                        (v.variant().equals("vulnerable") ? vuln : fixed).add(s);
                    }
                }
            }
            if (vuln.isEmpty()) {
                continue;
            }
            sb.append(String.format("%-16s %-28s %-14s %-14s%n", names[k], detected(vuln), fpRange(vuln),
                    fpRange(fixed)));
        }
        long in = 0;
        long outTok = 0;
        int rejected = 0;
        List<String> statuses = new ArrayList<>();
        for (Measurement.Run run : r.runs()) {
            for (Measurement.VariantRun v : run.variants()) {
                in += v.inputTokens();
                outTok += v.outputTokens();
                rejected += v.aiRejected();
                statuses.add(run.index() + "/" + v.variant() + ":" + v.aiStatus()
                        + (v.aiDetail() == null ? "" : " (" + v.aiDetail() + ")"));
            }
        }
        if (r.model() != null) {
            sb.append(String.format("%nAI tokens: %,d in / %,d out, cost ~$%.2f of $%.2f budget, discarded (quote not"
                    + " found): %d%n", in, outTok, r.spentUsd(), r.budgetUsd(), rejected));
            sb.append("AI status: ").append(String.join(", ", statuses)).append('\n');
        }
        if (!r.staticErrors().isEmpty()) {
            sb.append("Static analysis errors: ").append(r.staticErrors()).append('\n');
        }
        return sb.toString();
    }

    private static Scorer.Score pick(Measurement.Methods m, int idx) {
        return switch (idx) {
            case 0 -> m.staticOnly();
            case 1 -> m.aiOnly();
            case 2 -> m.combinedBlock();
            default -> m.combinedAll();
        };
    }

    /** 예: "V2 V3 V4 (V1 0/3)" - 모든 회차에서 찾은 것은 이름만, 일부만 찾은 것은 횟수를 붙인다. */
    private static String detected(List<Scorer.Score> scores) {
        List<String> ids = new ArrayList<>();
        scores.forEach(s -> {
            s.detected().forEach(id -> { if (!ids.contains(id)) ids.add(id); });
            s.missed().forEach(id -> { if (!ids.contains(id)) ids.add(id); });
        });
        ids.sort(null);
        List<String> parts = new ArrayList<>();
        for (String id : ids) {
            long hit = scores.stream().filter(s -> s.detected().contains(id)).count();
            parts.add(hit == scores.size() ? id : id + " " + hit + "/" + scores.size());
        }
        long full = ids.stream().filter(id -> scores.stream().allMatch(s -> s.detected().contains(id))).count();
        return full + "/" + ids.size() + ": " + String.join(" ", parts);
    }

    private static String fpRange(List<Scorer.Score> scores) {
        if (scores.isEmpty()) {
            return "-";
        }
        int min = scores.stream().mapToInt(s -> s.falsePositives().size()).min().orElse(0);
        int max = scores.stream().mapToInt(s -> s.falsePositives().size()).max().orElse(0);
        return min == max ? String.valueOf(min) : min + "-" + max;
    }
}
