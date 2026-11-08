package io.gate.pkg.scan;

import io.gate.common.finding.Decision;
import io.gate.common.finding.Finding;
import java.util.List;
import java.util.UUID;

/** 검사 1회(scan_run)의 결과. 패키지 순서는 요청 순서를 따른다. */
public record ScanReport(UUID runId, List<PackageVerdict> packages) {

    public ScanReport {
        packages = List.copyOf(packages);
    }

    public Decision decision() {
        return Verdicts.strongest(packages.stream().map(PackageVerdict::decision).toList());
    }

    public List<Finding> findings() {
        return packages.stream().flatMap(p -> p.findings().stream()).toList();
    }
}
