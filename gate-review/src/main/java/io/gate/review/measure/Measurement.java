package io.gate.review.measure;

import io.gate.common.finding.Decision;
import io.gate.review.SourceFile;
import io.gate.review.ai.AiFinding;
import io.gate.review.ai.AiResult;
import io.gate.review.ai.AiReviewer;
import io.gate.review.merge.ReviewMerger;
import io.gate.review.merge.ReviewReport;
import io.gate.review.staticscan.SemgrepResult;
import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 실습장 정답지로 리뷰어를 측정한다 (설계안 3장).
 * <ol>
 *   <li>vulnerable·fixed를 따로, 같은 중립 이름(app)으로 바꿔 검토한다 → AI가 이름이나 두 버전 비교로 답을 알 수 없다</li>
 *   <li>정적 분석은 결과가 같으므로 버전마다 한 번, AI는 {@code runs}번 반복한다</li>
 *   <li>방법 4가지를 각각 채점: 정적만 · AI만(인용 확인 통과분) · 합침(BLOCK만) · 합침(WARN 포함)</li>
 *   <li>예산을 넘기면 남은 AI 호출을 하지 않는다 (상태로 기록)</li>
 * </ol>
 */
public final class Measurement {

    public static final List<String> VARIANTS = List.of("vulnerable", "fixed");

    @FunctionalInterface
    public interface StaticScan {
        SemgrepResult scan(List<SourceFile> files) throws IOException;
    }

    /** 방법별 점수. AI를 쓰지 않았으면 aiOnly는 null. */
    public record Methods(Scorer.Score staticOnly, Scorer.Score aiOnly, Scorer.Score combinedBlock,
            Scorer.Score combinedAll) {
    }

    public record VariantRun(String variant, Methods scores, AiResult.Status aiStatus, String aiDetail,
            int aiRejected, long inputTokens, long outputTokens, double costUsd, long aiMillis) {
    }

    public record Run(int index, List<VariantRun> variants) {
    }

    public record Result(String staticEngine, String staticConfig, String model, String effort, int requestedRuns,
            double budgetUsd, double spentUsd, List<Run> runs, List<String> staticErrors) {
    }

    private final GroundTruth truth;
    private final List<SourceFile> lab;
    private final StaticScan scan;
    private final Clock clock;

    public Measurement(GroundTruth truth, List<SourceFile> lab, StaticScan scan, Clock clock) {
        this.truth = truth;
        this.lab = List.copyOf(lab);
        this.scan = scan;
        this.clock = clock;
    }

    /**
     * @param ai        없으면 정적 분석만 측정한다 (runs는 1로 본다)
     * @param pricing   토큰 → 비용 계산 (AI가 없으면 쓰지 않음)
     * @param budgetUsd AI 호출 누적 비용 상한. 넘으면 이후 호출을 건너뛴다
     */
    public Result run(String staticConfig, Optional<AiReviewer> ai, String model, String effort, int runs,
            Pricing pricing, double budgetUsd) throws IOException {
        Map<String, Neutralizer.Neutralized> prepared = new LinkedHashMap<>();
        Map<String, SemgrepResult> staticResults = new LinkedHashMap<>();
        List<String> staticErrors = new ArrayList<>();
        String engine = null;
        for (String v : VARIANTS) {
            Neutralizer.Neutralized n = Neutralizer.prepare(lab, v);
            if (!Neutralizer.leaks(n.files()).isEmpty()) {
                throw new IllegalStateException("variant names leak into the reviewed code: "
                        + Neutralizer.leaks(n.files()));
            }
            SemgrepResult st = scan.scan(n.files());
            prepared.put(v, n);
            staticResults.put(v, st);
            staticErrors.addAll(st.errors());
            engine = "semgrep " + st.version();
        }

        int effectiveRuns = ai.isPresent() ? runs : 1;
        double spent = 0;
        List<Run> out = new ArrayList<>();
        for (int i = 1; i <= effectiveRuns; i++) {
            List<VariantRun> variants = new ArrayList<>();
            for (String v : VARIANTS) {
                Neutralizer.Neutralized n = prepared.get(v);
                SemgrepResult st = staticResults.get(v);
                AiResult aiResult;
                long started = System.nanoTime();
                if (ai.isEmpty()) {
                    aiResult = AiResult.skipped("no API key: static analysis only");
                } else if (spent >= budgetUsd) {
                    aiResult = AiResult.skipped(String.format("budget of $%.2f reached", budgetUsd));
                } else {
                    aiResult = ai.get().review(n.files(), st.hits());
                }
                long millis = (System.nanoTime() - started) / 1_000_000;
                double cost = aiResult.status() == AiResult.Status.SKIPPED ? 0
                        : pricing.cost(aiResult.inputTokens(), aiResult.outputTokens());
                spent += cost;
                variants.add(score(v, n, st, aiResult, ai.isPresent(), cost, millis));
            }
            out.add(new Run(i, variants));
        }
        return new Result(engine, staticConfig, ai.isPresent() ? model : null, ai.isPresent() ? effort : null,
                effectiveRuns, budgetUsd, spent, out, staticErrors);
    }

    private VariantRun score(String variant, Neutralizer.Neutralized n, SemgrepResult st, AiResult ai,
            boolean aiUsed, double cost, long millis) {
        ReviewReport report = new ReviewMerger(clock).merge(n.files(), st.hits(), st.errors(), "semgrep", ai);
        Function<Flag, Flag> back = f -> f.withPath(n.original(f.path()));
        List<GroundTruth.Vulnerability> expected = expectedIn(variant, n);

        List<Flag> staticFlags = st.hits().stream().map(Flag::of).map(back).toList();
        Set<AiFinding> rejected = report.rejected().stream().map(ReviewReport.Rejected::finding)
                .collect(Collectors.toSet());
        List<Flag> aiFlags = ai.findings().stream().filter(a -> !rejected.contains(a)).map(Flag::of).map(back)
                .toList();
        List<Flag> blockFlags = report.findings().stream().filter(f -> f.decision() == Decision.BLOCK)
                .map(Flag::of).map(back).toList();
        List<Flag> allFlags = report.findings().stream().map(Flag::of).map(back).toList();

        List<String> clean = truth.expectedClean();
        Methods m = new Methods(Scorer.score(expected, clean, staticFlags),
                aiUsed ? Scorer.score(expected, clean, aiFlags) : null,
                Scorer.score(expected, clean, blockFlags),
                Scorer.score(expected, clean, allFlags));
        return new VariantRun(variant, m, ai.status(), ai.detail(), report.rejected().size(), ai.inputTokens(),
                ai.outputTokens(), cost, millis);
    }

    /** 이 버전에서 검토한 파일에 있는 정답만 (고친 버전이면 없음). */
    private List<GroundTruth.Vulnerability> expectedIn(String variant, Neutralizer.Neutralized n) {
        Set<String> shown = n.files().stream().map(f -> n.original(f.path())).collect(Collectors.toSet());
        return truth.vulnerabilities().stream().filter(v -> shown.contains(v.file())).toList();
    }
}
