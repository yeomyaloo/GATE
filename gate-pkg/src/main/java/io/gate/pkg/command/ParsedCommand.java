package io.gate.pkg.command;

import io.gate.pkg.registry.PackageRequest;
import java.util.List;

/**
 * 셸 명령에서 찾은 설치 대상.
 *
 * @param packages  레지스트리에서 검사할 수 있는 패키지
 * @param unchecked 설치 명령이지만 검사할 수 없는 부분과 그 이유 (매니페스트 설치, 사설 레지스트리, URL·Git 설치,
 *                  셸 치환 등). 하나라도 있으면 훅은 통과시키지 않고 확인을 요청한다
 */
public record ParsedCommand(List<PackageRequest> packages, List<String> unchecked) {

    public ParsedCommand {
        packages = List.copyOf(packages);
        unchecked = List.copyOf(unchecked);
    }

    /** 설치와 관련된 것을 하나라도 찾았는지. false면 훅은 아무것도 하지 않는다. */
    public boolean isInstall() {
        return !packages.isEmpty() || !unchecked.isEmpty();
    }
}
