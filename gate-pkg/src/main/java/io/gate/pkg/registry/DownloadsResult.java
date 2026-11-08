package io.gate.pkg.registry;

/** 주간 다운로드 수 조회 결과. */
public sealed interface DownloadsResult permits DownloadsResult.Known, DownloadsResult.Unknown {

    record Known(long weekly, String url) implements DownloadsResult {
    }

    record Unknown(String url, String reason) implements DownloadsResult {
    }
}
