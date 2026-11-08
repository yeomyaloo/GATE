package io.gate.pkg.check;

import java.time.Duration;

/**
 * 검사 임계값. 기본값은 설계안 4장에서 확정한 값이다 (2026-11-09 승인). 실제 사용 후 조정한다.
 *
 * @param newPackage          첫 게시 후 이 기간 안이면 PKG-NEW
 * @param newVersion          요청 버전 게시 후 이 기간 안이면 PKG-NEW
 * @param minWeeklyDownloads  주간 다운로드가 이 값 미만이면 PKG-POP
 */
public record Thresholds(Duration newPackage, Duration newVersion, long minWeeklyDownloads) {

    public static Thresholds defaults() {
        return new Thresholds(Duration.ofDays(7), Duration.ofHours(24), 100);
    }
}
