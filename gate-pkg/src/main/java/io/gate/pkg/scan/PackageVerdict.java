package io.gate.pkg.scan;

import io.gate.common.finding.Decision;
import io.gate.common.finding.Finding;
import io.gate.pkg.registry.PackageRequest;
import java.util.List;

/** 패키지 하나의 최종 판정과 그 근거가 된 결과들. */
public record PackageVerdict(PackageRequest request, Decision decision, List<Finding> findings) {

    public PackageVerdict {
        findings = List.copyOf(findings);
    }

    static PackageVerdict of(PackageRequest request, List<Finding> findings) {
        return new PackageVerdict(request, Verdicts.strongest(findings.stream().map(Finding::decision).toList()),
                findings);
    }
}
