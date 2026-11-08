package io.gate.pkg.registry;

/** 레지스트리 조회 결과: 있음 / 없음(404) / 지금은 확인 불가. */
public sealed interface LookupResult permits LookupResult.Found, LookupResult.NotFound, LookupResult.Unavailable {

    record Found(PackageInfo info) implements LookupResult {
    }

    /** 레지스트리가 "그런 패키지 없음"이라고 명확히 답한 경우. */
    record NotFound(String url) implements LookupResult {
    }

    /** 네트워크 오류, 시간 초과, 서버 오류, 해석할 수 없는 응답, 잘못된 이름. */
    record Unavailable(String url, String reason) implements LookupResult {
    }
}
