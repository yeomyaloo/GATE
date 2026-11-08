package io.gate.pkg.check;

import java.time.Duration;

/**
 * 검사 임계값. 기본값은 설계안 4장에서 확정한 값이다 (2026-11-09 승인). 실제 사용 후 조정한다.
 *
 * @param newPackage          첫 게시 후 이 기간 안이면 PKG-NEW
 * @param newVersion          요청 버전 게시 후 이 기간 안이면 PKG-NEW
 * @param minWeeklyDownloads  주간 다운로드가 이 값 미만이면 PKG-POP
 * @param typoMaxShare        철자 흉내 의심 패키지의 다운로드가 대상의 이 비율 미만이면 PKG-TYPO 차단
 * @param typoNewWindow       철자 흉내 의심 패키지가 첫 게시 후 이 기간 안이면 PKG-TYPO 차단
 * @param typoMaxWeeklyDownloads 다운로드 비율로 차단하려면 자기 주간 다운로드도 이 값 미만이어야 한다.
 *                            측정(2026-11-09)에서 bpython·google-reauth 등 주간 2만~8만 건의 정상 패키지가
 *                            "대상의 1% 미만"만으로 차단되어 추가했다
 */
public record Thresholds(Duration newPackage, Duration newVersion, long minWeeklyDownloads, double typoMaxShare,
        Duration typoNewWindow, long typoMaxWeeklyDownloads) {

    public static Thresholds defaults() {
        return new Thresholds(Duration.ofDays(7), Duration.ofHours(24), 100, 0.01, Duration.ofDays(90), 10_000);
    }
}
