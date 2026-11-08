package io.gate.pkg.registry;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * 레지스트리에서 읽은 패키지 정보 중 검사에 쓰는 값만 생태계 구분 없이 담는다.
 *
 * @param resolvedVersion   검사 기준 버전. 요청한 정확한 버전이 레지스트리에 없으면 null
 * @param resolutionNote    버전 범위처럼 정확히 풀지 못하고 latest로 대신했을 때의 설명. 없으면 null
 * @param createdAt         패키지 첫 게시 시각. 알 수 없으면 null
 * @param versionPublishedAt 기준 버전의 게시 시각. 알 수 없으면 null
 * @param installScripts    설치 중 자동 실행되는 스크립트 (npm 생명주기 스크립트 등)
 * @param sdistOnly         PyPI에서 wheel 없이 소스 배포본만 있는지 (npm은 항상 false)
 */
public record PackageInfo(
        Ecosystem ecosystem,
        String name,
        String registryUrl,
        String requestedSpec,
        String latestVersion,
        String resolvedVersion,
        String resolutionNote,
        Instant createdAt,
        Instant versionPublishedAt,
        Map<String, String> installScripts,
        boolean sdistOnly) {

    public PackageInfo {
        Objects.requireNonNull(ecosystem, "ecosystem");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(registryUrl, "registryUrl");
        installScripts = installScripts == null ? Map.of() : Map.copyOf(installScripts);
    }

    public boolean versionMissing() {
        return resolvedVersion == null;
    }

    /** 결과 위치에 적을 버전: 기준 버전, 없으면 요청한 값. */
    public String locationVersion() {
        return resolvedVersion != null ? resolvedVersion : requestedSpec;
    }
}
