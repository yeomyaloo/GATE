package io.gate.review.merge;

import io.gate.common.finding.Decision;
import io.gate.common.finding.Finding;
import io.gate.common.finding.FindingSource;
import io.gate.common.finding.Location;
import io.gate.common.finding.Severity;
import io.gate.review.Cwe;
import io.gate.review.SourceFile;
import io.gate.review.ai.AiFinding;
import io.gate.review.ai.AiResult;
import io.gate.review.ai.QuoteVerifier;
import io.gate.review.staticscan.StaticHit;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 정적 분석과 AI 결과를 합쳐 판정한다 (설계안 2장).
 * <ul>
 *   <li>같은 파일·가까운 줄(앞뒤 {@link #LINE_TOLERANCE}줄)·같은 종류의 CWE면 하나로 합쳐 {@code RULE_AND_AI}: BLOCK</li>
 *   <li>정적 분석만: 규칙 심각도대로 (ERROR→BLOCK, WARNING·INFO→WARN), {@code RULE}</li>
 *   <li>AI만: 언제나 WARN, {@code AI}. AI 판단만으로는 차단하지 않는다(공통 모델도 금지)</li>
 *   <li>인용 확인에 실패한 AI 지적은 결과에서 빼고 따로 기록</li>
 * </ul>
 */
public final class ReviewMerger {

    static final int LINE_TOLERANCE = 3;

    private final Clock clock;

    public ReviewMerger(Clock clock) {
        this.clock = clock;
    }

    public ReviewReport merge(List<SourceFile> files, List<StaticHit> hits, List<String> staticErrors,
            String staticEngine, AiResult ai) {
        UUID runId = UUID.randomUUID();
        Map<String, SourceFile> byPath = files.stream()
                .collect(Collectors.toMap(SourceFile::path, Function.identity(), (a, b) -> a, LinkedHashMap::new));

        List<ReviewReport.Rejected> rejected = new ArrayList<>();
        List<AiFinding> verified = new ArrayList<>();
        if (ai.status() == AiResult.Status.OK) {
            for (AiFinding f : ai.findings()) {
                Optional<String> reason = QuoteVerifier.rejectReason(f, byPath);
                if (reason.isPresent()) {
                    rejected.add(new ReviewReport.Rejected(f, reason.get()));
                } else {
                    verified.add(f);
                }
            }
        }

        List<Finding> findings = new ArrayList<>();
        List<AiFinding> unmatchedAi = new ArrayList<>(verified);
        for (StaticHit hit : hits) {
            Optional<AiFinding> match = unmatchedAi.stream().filter(a -> matches(hit, a)).findFirst();
            match.ifPresent(unmatchedAi::remove);
            findings.add(match.isPresent()
                    ? combined(runId, hit, match.get(), byPath, staticEngine, ai)
                    : ruleOnly(runId, hit, byPath, staticEngine));
        }
        for (AiFinding a : unmatchedAi) {
            findings.add(aiOnly(runId, a, ai));
        }
        return new ReviewReport(runId, findings, rejected, ai, staticEngine, staticErrors);
    }

    static boolean matches(StaticHit hit, AiFinding ai) {
        boolean near = ai.line() <= hit.endLine() + LINE_TOLERANCE && ai.endLine() >= hit.startLine() - LINE_TOLERANCE;
        return hit.path().equals(ai.file()) && near && Cwe.sameFamily(hit.cwe(), ai.cwe());
    }

    private Finding ruleOnly(UUID runId, StaticHit hit, Map<String, SourceFile> files, String engine) {
        Severity severity = ruleSeverity(hit.severity());
        Decision decision = hit.severity().equalsIgnoreCase("ERROR") ? Decision.BLOCK : Decision.WARN;
        return new Finding(UUID.randomUUID(), runId, "REVIEW-" + hit.ruleId(), firstSentence(hit.message()), severity,
                decision, new Location.File(hit.path(), hit.startLine()), ruleEvidence(hit, files, engine),
                FindingSource.RULE, clock.instant());
    }

    private Finding aiOnly(UUID runId, AiFinding a, AiResult ai) {
        return new Finding(UUID.randomUUID(), runId, "REVIEW-AI-" + Cwe.normalize(a.cwe()).orElse("UNKNOWN"),
                a.title(), aiSeverity(a.severity()), Decision.WARN, new Location.File(a.file(), a.line()),
                aiEvidence(a, ai), FindingSource.AI, clock.instant());
    }

    private Finding combined(UUID runId, StaticHit hit, AiFinding a, Map<String, SourceFile> files, String engine,
            AiResult ai) {
        Severity rule = ruleSeverity(hit.severity());
        Severity model = aiSeverity(a.severity());
        Severity strongest = rule.ordinal() <= model.ordinal() ? rule : model;
        Map<String, Object> evidence = new LinkedHashMap<>(ruleEvidence(hit, files, engine));
        evidence.put("ai", aiEvidence(a, ai));
        return new Finding(UUID.randomUUID(), runId, "REVIEW-" + hit.ruleId(), firstSentence(hit.message()),
                strongest, Decision.BLOCK, new Location.File(hit.path(), hit.startLine()), evidence,
                FindingSource.RULE_AND_AI, clock.instant());
    }

    private static Map<String, Object> ruleEvidence(StaticHit hit, Map<String, SourceFile> files, String engine) {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("engine", engine);
        ev.put("rule", hit.ruleId());
        if (hit.cwe() != null) {
            ev.put("cwe", hit.cwe());
        }
        ev.put("lines", hit.startLine() + "-" + hit.endLine());
        SourceFile file = files.get(hit.path());
        if (file != null) {
            ev.put("code", file.line(hit.startLine()).strip());
        }
        ev.put("message", hit.message());
        return ev;
    }

    private static Map<String, Object> aiEvidence(AiFinding a, AiResult ai) {
        Map<String, Object> ev = new LinkedHashMap<>();
        if (ai.model() != null) {
            ev.put("model", ai.model());
        }
        Cwe.normalize(a.cwe()).ifPresent(c -> ev.put("cwe", c));
        ev.put("lines", a.line() + "-" + a.endLine());
        ev.put("evidence", a.evidence() == null ? "" : a.evidence());
        ev.put("quotedCode", a.quotedCode() == null ? "" : a.quotedCode());
        ev.put("confidence", a.confidence() == null ? "unknown" : a.confidence());
        return ev;
    }

    static Severity ruleSeverity(String semgrep) {
        switch (semgrep.toUpperCase(Locale.ROOT)) {
            case "ERROR":
                return Severity.HIGH;
            case "WARNING":
                return Severity.MEDIUM;
            default:
                return Severity.LOW;
        }
    }

    static Severity aiSeverity(String s) {
        try {
            return Severity.valueOf(s == null ? "MEDIUM" : s.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Severity.MEDIUM;
        }
    }

    static String firstSentence(String message) {
        int dot = message.indexOf(". ");
        return dot > 0 ? message.substring(0, dot + 1) : message;
    }
}
