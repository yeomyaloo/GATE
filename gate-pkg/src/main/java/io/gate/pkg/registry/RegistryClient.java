package io.gate.pkg.registry;

/** 생태계별 레지스트리 조회. 구현은 예외를 던지지 않고 결과 타입으로 실패를 알린다. */
public interface RegistryClient {

    Ecosystem ecosystem();

    LookupResult lookup(PackageRequest request);

    DownloadsResult weeklyDownloads(String name);

    /**
     * 여러 패키지의 다운로드 수를 미리 한꺼번에 받아 둔다 (지원하는 레지스트리만). 이후 {@link #weeklyDownloads}는 받아 둔 값을 쓴다.
     * 실패해도 아무 일도 하지 않는다: 그때는 패키지마다 따로 묻는다.
     */
    default void prefetchDownloads(java.util.Collection<String> names) {
    }
}
