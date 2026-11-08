package io.gate.pkg.registry;

import java.util.Set;

/**
 * 기준 버전과 직전 버전의 게시자 정보 (npm만 제공). PKG-MAINT가 "계정 탈취 후 악성 버전 게시" 패턴을 찾는 데 쓴다.
 *
 * @param publisher           기준 버전을 게시한 계정 (npm {@code _npmUser.name})
 * @param previousVersion     기준 버전 바로 전에 게시된 버전. 없으면 null
 * @param previousPublisher   직전 버전 게시 계정
 * @param previousMaintainers 직전 버전 시점의 관리자 목록
 */
public record PublisherHistory(String publisher, String previousVersion, String previousPublisher,
        Set<String> previousMaintainers) {

    public PublisherHistory {
        previousMaintainers = previousMaintainers == null ? Set.of() : Set.copyOf(previousMaintainers);
    }
}
