package io.gate.pkg.scan;

import io.gate.common.finding.Decision;
import io.gate.common.finding.Finding;
import io.gate.common.finding.Location;
import io.gate.common.finding.Severity;
import io.gate.pkg.check.Check;
import io.gate.pkg.check.CheckContext;
import io.gate.pkg.check.ComboRule;
import io.gate.pkg.check.ExistCheck;
import io.gate.pkg.check.InstallScriptCheck;
import io.gate.pkg.check.MaintainerCheck;
import io.gate.pkg.check.NewPackageCheck;
import io.gate.pkg.check.PopularityCheck;
import io.gate.pkg.check.Rules;
import io.gate.pkg.check.Thresholds;
import io.gate.pkg.check.TypoCheck;
import io.gate.pkg.check.TypoDetector;
import io.gate.pkg.check.TypoSignal;
import io.gate.pkg.check.VulnCheck;
import io.gate.pkg.registry.DownloadsResult;
import io.gate.pkg.registry.Ecosystem;
import io.gate.pkg.registry.LookupResult;
import io.gate.pkg.registry.OsvClient;
import io.gate.pkg.registry.PackageInfo;
import io.gate.pkg.registry.PackageRequest;
import io.gate.pkg.registry.RegistryClient;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 패키지 목록을 검사해 판정을 낸다.
 *
 * <p>패키지 하나의 흐름: 메타데이터·다운로드·(철자 흉내 의심 시) 대상 다운로드를 병렬로 조회 →
 * 기준 버전이 정해지면 OSV 조회 → 검사 실행 → 조합 규칙(PKG-COMBO) 적용.
 */
public final class PackageScanner {

    private static final int MAX_THREADS = 8;

    private final Map<Ecosystem, RegistryClient> registries = new EnumMap<>(Ecosystem.class);
    private final Map<Ecosystem, TypoDetector> typoDetectors = new EnumMap<>(Ecosystem.class);
    private final OsvClient osv;
    private final List<Check> checks;
    private final Clock clock;
    private final Thresholds thresholds;

    /** OSV·철자 흉내 없이 (1주차 구성, 테스트용). */
    public PackageScanner(List<RegistryClient> registries, List<Check> checks, Clock clock, Thresholds thresholds) {
        this(registries, null, List.of(), checks, clock, thresholds);
    }

    public PackageScanner(List<RegistryClient> registries, OsvClient osv, List<TypoDetector> typoDetectors,
            List<Check> checks, Clock clock, Thresholds thresholds) {
        registries.forEach(r -> this.registries.put(r.ecosystem(), r));
        typoDetectors.forEach(d -> this.typoDetectors.put(d.ecosystem(), d));
        this.osv = osv;
        this.checks = List.copyOf(checks);
        this.clock = clock;
        this.thresholds = thresholds;
    }

    /** 1주차 검사 목록 (EXIST·NEW·SCRIPT·POP). */
    public static List<Check> week1Checks() {
        return List.of(new ExistCheck(), new NewPackageCheck(), new InstallScriptCheck(), new PopularityCheck());
    }

    /** 전체 검사 목록. PKG-COMBO는 이 결과들을 보고 스캐너가 마지막에 적용한다. */
    public static List<Check> allChecks() {
        List<Check> all = new ArrayList<>(week1Checks());
        all.add(new MaintainerCheck());
        all.add(new VulnCheck());
        all.add(new TypoCheck());
        return List.copyOf(all);
    }

    public ScanReport scan(List<PackageRequest> requests) {
        UUID runId = UUID.randomUUID();
        if (requests.isEmpty()) {
            return new ScanReport(runId, List.of());
        }
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(MAX_THREADS, requests.size() * 3), r -> {
            Thread t = new Thread(r, "gate-pkg-scan");
            t.setDaemon(true);
            return t;
        });
        try {
            List<CompletableFuture<PackageVerdict>> futures = new ArrayList<>();
            for (PackageRequest request : requests) {
                futures.add(scanOne(runId, request, pool));
            }
            return new ScanReport(runId, futures.stream().map(CompletableFuture::join).toList());
        } finally {
            pool.shutdownNow();
        }
    }

    private CompletableFuture<PackageVerdict> scanOne(UUID runId, PackageRequest request, ExecutorService pool) {
        RegistryClient registry = registries.get(request.ecosystem());
        if (registry == null) {
            return CompletableFuture.completedFuture(PackageVerdict.of(request,
                    List.of(unchecked(runId, request, "no registry client for " + request.ecosystem().id()))));
        }
        CompletableFuture<LookupResult> lookup = CompletableFuture.supplyAsync(() -> registry.lookup(request), pool);
        CompletableFuture<DownloadsResult> downloads =
                CompletableFuture.supplyAsync(() -> registry.weeklyDownloads(request.name()), pool);

        TypoDetector detector = typoDetectors.get(request.ecosystem());
        Optional<TypoDetector.TypoMatch> typoMatch = detector == null ? Optional.empty() : detector.find(request.name());
        CompletableFuture<TypoSignal> typo = typoMatch
                .map(m -> CompletableFuture.supplyAsync(
                        () -> new TypoSignal(m, registry.weeklyDownloads(m.target())), pool))
                .orElseGet(() -> CompletableFuture.completedFuture(null));

        CompletableFuture<OsvClient.Result> vulns = lookup.thenApplyAsync(l -> {
            if (osv == null || !(l instanceof LookupResult.Found found) || found.info().versionMissing()) {
                return null;
            }
            PackageInfo info = found.info();
            return osv.query(info.ecosystem(), info.name(), info.resolvedVersion());
        }, pool);

        return CompletableFuture.allOf(lookup, downloads, typo, vulns)
                .thenApply(ignored -> evaluate(runId, request, lookup.join(), downloads.join(), vulns.join(),
                        typo.join()))
                .exceptionally(e -> PackageVerdict.of(request,
                        List.of(unchecked(runId, request, "internal error: " + e.getMessage()))));
    }

    private PackageVerdict evaluate(UUID runId, PackageRequest request, LookupResult lookup,
            DownloadsResult downloads, OsvClient.Result vulns, TypoSignal typo) {
        Instant now = clock.instant();
        if (lookup instanceof LookupResult.NotFound notFound) {
            return PackageVerdict.of(request, List.of(ExistCheck.packageMissing(runId, request, notFound.url(), now)));
        }
        if (lookup instanceof LookupResult.Unavailable unavailable) {
            Finding finding = unchecked(runId, request, unavailable.reason());
            return PackageVerdict.of(request, List.of(withUrl(finding, unavailable.url())));
        }
        CheckContext ctx = new CheckContext(runId, ((LookupResult.Found) lookup).info(), downloads, vulns, typo, now,
                thresholds);
        List<Finding> findings = new ArrayList<>();
        for (Check check : checks) {
            findings.addAll(check.evaluate(ctx));
        }
        ComboRule.evaluate(ctx, findings).ifPresent(findings::add);
        return PackageVerdict.of(request, findings);
    }

    private Finding unchecked(UUID runId, PackageRequest request, String reason) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("reason", reason);
        return Rules.finding(runId, Rules.UNCHECKED, "Could not check '" + request.name() + "'",
                Severity.INFO, Decision.WARN,
                new Location.Package(request.ecosystem().id(), request.name(), request.versionSpec()),
                evidence, clock.instant());
    }

    private static Finding withUrl(Finding f, String url) {
        Map<String, Object> evidence = new LinkedHashMap<>(f.evidence());
        evidence.put("url", url);
        return new Finding(f.id(), f.runId(), f.ruleId(), f.title(), f.severity(), f.decision(), f.location(),
                evidence, f.source(), f.detectedAt());
    }
}
