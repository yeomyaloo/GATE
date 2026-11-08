package io.gate.pkg.registry;

/** 생태계별 레지스트리 조회. 구현은 예외를 던지지 않고 결과 타입으로 실패를 알린다. */
public interface RegistryClient {

    Ecosystem ecosystem();

    LookupResult lookup(PackageRequest request);

    DownloadsResult weeklyDownloads(String name);
}
