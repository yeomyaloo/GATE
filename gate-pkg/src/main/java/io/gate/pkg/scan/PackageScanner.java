package io.gate.pkg.scan;

import io.gate.common.finding.Decision;
import io.gate.common.finding.Finding;
import io.gate.common.finding.Location;
import io.gate.common.finding.Severity;
import io.gate.pkg.check.Check;
import io.gate.pkg.check.CheckContext;
import io.gate.pkg.check.ExistCheck;
import io.gate.pkg.check.InstallScriptCheck;
import io.gate.pkg.check.NewPackageCheck;
import io.gate.pkg.check.PopularityCheck;
import io.gate.pkg.check.Rules;
import io.gate.pkg.check.Thresholds;
import io.gate.pkg.registry.DownloadsResult;
import io.gate.pkg.registry.Ecosystem;
import io.gate.pkg.registry.LookupResult;
import io.gate.pkg.registry.PackageRequest;
import io.gate.pkg.registry.RegistryClient;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 패키지 목록을 검사해 판정을 낸다.
 * 패키지마다, 그리고 패키지 안에서 메타데이터·다운로드 조회를 병렬로 실행한다.
 */
public final class PackageScanner {

    private static final int MAX_THREADS = 8;

    private final Map<Ecosystem, RegistryClient> registries = new EnumMap<>(Ecosystem.class);
    private final List<Check> checks;
    private final Clock clock;
    private final Thresholds thresholds;

    public PackageScanner(List<RegistryClient> registries, List<Check> checks, Clock clock, Thresholds thresholds) {
        registries.forEach(r -> this.registries.put(r.ecosystem(), r));
        this.checks = List.copyOf(checks);
        this.clock = clock;
        this.thresholds = thresholds;
    }

    /** 1주차 검사 목록 (EXIST·NEW·SCRIPT·POP). */
    public static List<Check> week1Checks() {
        return List.of(new ExistCheck(), new NewPackageCheck(), new InstallScriptCheck(), new PopularityCheck());
    }

    public ScanReport scan(List<PackageRequest> requests) {
        UUID runId = UUID.randomUUID();
        if (requests.isEmpty()) {
            return new ScanReport(runId, List.of());
        }
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(MAX_THREADS, requests.size() * 2), r -> {
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
        return lookup.thenCombine(downloads, (l, d) -> evaluate(runId, request, l, d))
                .exceptionally(e -> PackageVerdict.of(request,
                        List.of(unchecked(runId, request, "internal error: " + e.getMessage()))));
    }

    private PackageVerdict evaluate(UUID runId, PackageRequest request, LookupResult lookup,
            DownloadsResult downloads) {
        Instant now = clock.instant();
        if (lookup instanceof LookupResult.NotFound notFound) {
            return PackageVerdict.of(request, List.of(ExistCheck.packageMissing(runId, request, notFound.url(), now)));
        }
        if (lookup instanceof LookupResult.Unavailable unavailable) {
            Finding finding = unchecked(runId, request, unavailable.reason());
            return PackageVerdict.of(request, List.of(withUrl(finding, unavailable.url())));
        }
        CheckContext ctx = new CheckContext(runId, ((LookupResult.Found) lookup).info(), downloads, now, thresholds);
        List<Finding> findings = new ArrayList<>();
        for (Check check : checks) {
            findings.addAll(check.evaluate(ctx));
        }
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
