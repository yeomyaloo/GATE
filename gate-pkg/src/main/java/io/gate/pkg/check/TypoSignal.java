package io.gate.pkg.check;

import io.gate.pkg.registry.DownloadsResult;

/** 철자 흉내 의심 정보: 비슷한 인기 패키지와 그 패키지의 주간 다운로드 수. */
public record TypoSignal(TypoDetector.TypoMatch match, DownloadsResult targetDownloads) {
}
