package io.gate.pkg.scan;

import io.gate.common.finding.Decision;
import io.gate.common.finding.Finding;
import io.gate.pkg.registry.PackageRequest;
import java.util.List;

/**
 * 패키지 하나의 최종 판정과 그 근거가 된 결과들.
 *
 * @param checkedVersion 실제로 검사한 버전(레지스트리에서 정한 기준 버전). 패키지를 찾지 못했으면 null
 */
public record PackageVerdict(PackageRequest request, String checkedVersion, Decision decision,
        List<Finding> findings) {

    public PackageVerdict {
        findings = List.copyOf(findings);
    }

    static PackageVerdict of(PackageRequest request, List<Finding> findings) {
        return of(request, null, findings);
    }

    static PackageVerdict of(PackageRequest request, String checkedVersion, List<Finding> findings) {
        return new PackageVerdict(request, checkedVersion,
                Verdicts.strongest(findings.stream().map(Finding::decision).toList()), findings);
    }
}
