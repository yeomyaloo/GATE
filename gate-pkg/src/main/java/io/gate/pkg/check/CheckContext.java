package io.gate.pkg.check;

import io.gate.common.finding.Location;
import io.gate.pkg.registry.DownloadsResult;
import io.gate.pkg.registry.PackageInfo;
import java.time.Instant;
import java.util.UUID;

/** 검사 하나가 판단에 쓰는 입력. 시각(now)을 밖에서 넣어서 테스트가 결정적으로 동작하게 한다. */
public record CheckContext(UUID runId, PackageInfo info, DownloadsResult downloads, Instant now,
        Thresholds thresholds) {

    public Location.Package location() {
        return new Location.Package(info.ecosystem().id(), info.name(), info.locationVersion());
    }
}
