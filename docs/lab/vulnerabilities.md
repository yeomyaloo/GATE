# gate-lab 취약점 설명서 (Vulnerability catalogue)

> ⚠️ **gate-lab은 일부러 취약하게 만든 실습용 앱입니다. 외부에 공개 배포하지 마세요.** 127.0.0.1에서만 띄웁니다.

같은 주문 API를 두 프로필로 띄워 비교합니다: `vulnerable`(127.0.0.1:8081), `fixed`(127.0.0.1:8082).
모든 공격은 `gate-lab/src/test/java/io/gate/lab/exploit/LabScenario.java`에서 **두 버전에 똑같이** 보내고,
뚫린 버전에서는 성공하고 고친 버전에서는 막히는지 확인합니다(CI에서 매번 실행).
4단계 측정용 정답(파일·줄·CWE)은 [ground-truth.json](ground-truth.json)에만 있습니다.

## 띄우기 (로컬 전용)
```bash
docker compose --profile lab up -d --build lab-vulnerable lab-fixed
```
데모 계정은 alice·bob이고 비밀번호는 컨테이너 로그에만 나옵니다(`docker compose --profile lab logs lab-vulnerable`). 아래 예시의 `$PW`, `$TOKEN`은 직접 채웁니다.
```bash
TOKEN=$(curl -s http://127.0.0.1:8081/api/auth/login -H 'Content-Type: application/json' \
  -d "{\"username\":\"alice\",\"password\":\"$PW\"}" | python3 -c 'import sys,json;print(json.load(sys.stdin)["accessToken"])')
```

---

## V1. IDOR / BOLA: 남의 주문 읽기·고치기·지우기
| 항목 | 내용 |
|---|---|
| 분류 | CWE-639, OWASP API1:2023 Broken Object Level Authorization |
| 위치 | `vulnerable/OrderDetailController.java`: 주문 번호로만 조회하고 요청한 사용자가 주인인지 확인하지 않는다 |
| 영향 | 로그인한 누구나 남의 주문(배송 주소 포함)을 읽고, 수량·메모·주소를 바꾸고, 지울 수 있다. 주문 번호가 순번이라 차례로 시도하면 모두 수집된다 |
| 재현 | `curl -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8081/api/orders/2` (alice 토큰으로 bob의 주문) |
| 고친 방법 | `fixed/OrderDetailController.java`: 조회 결과를 요청한 사용자의 ID로 거르고, 아니면 **404**(403이 아닌 이유: 그 번호의 주문이 있는지도 알려 주지 않기 위해) |
| 테스트 | `v1_readSomeoneElsesOrder`, `v1_modifySomeoneElsesOrder`, `v1_deleteSomeoneElsesOrder`, `v1_enumerateOrderIds` |

## V2. SQL 인젝션: 상품 검색
| 항목 | 내용 |
|---|---|
| 분류 | CWE-89 |
| 위치 | `vulnerable/ProductSearchController.java`: 검색어를 SQL 문자열에 그대로 이어 붙인다 |
| 영향 | `UNION`으로 다른 표(사용자 이름·비밀번호 해시)를 읽고, `hidden` 조건을 무력화해 숨김 상품을 본다. 따옴표 하나로 SQL 오류를 일으키며 V4와 겹치면 SQL 원문까지 노출된다 |
| 재현 | `curl -G http://127.0.0.1:8081/api/products/search --data-urlencode "q=' UNION SELECT id, username || ':' || password_hash, 0 FROM users --"` |
| 고친 방법 | `fixed/ProductSearchController.java`: 바인딩 변수(`?`)로만 전달하고, LIKE 특수 문자(`% _ \`)를 이스케이프해 글자 그대로 찾는다. 검색어 길이 제한(100자) |
| 테스트 | `v2_unionDumpsUserTable`, `v2_bypassesHiddenFilter`, `v2_likeWildcardsAreLiteralInFixed`, `v2_normalSearchWorks` |

## V3. JWT 검증 누락
| 항목 | 내용 |
|---|---|
| 분류 | CWE-347, OWASP API2:2023 Broken Authentication |
| 위치 | `vulnerable/BearerTokenFilter.java`: 토큰의 payload만 Base64로 풀어 `sub`를 믿는다. 서명·알고리즘·만료를 확인하지 않는다 |
| 영향 | 키 없이 누구나 원하는 사용자로 행세한다 (`alg: none` 토큰, 다른 키로 서명한 토큰, 만료된 토큰 모두 통과) |
| 재현 | `curl -H "Authorization: Bearer $(printf '{"alg":"none"}' \| base64 \| tr -d '=').$(printf '{"sub":"bob"}' \| base64 \| tr -d '=')." http://127.0.0.1:8081/api/me` |
| 고친 방법 | `fixed/SecurityConfig.java`: Spring Security 리소스 서버. HS256으로 알고리즘을 고정하고 서명·만료를 검증한다. 서명 키는 시작할 때 무작위 또는 `GATE_LAB_JWT_SECRET`(32바이트 이상) |
| 테스트 | `v3_unsignedAlgNoneToken`, `v3_tokenSignedWithAttackersKey`, `v3_expiredToken` |

## V4. 에러 메시지 노출
| 항목 | 내용 |
|---|---|
| 분류 | CWE-209, OWASP API8:2023 Security Misconfiguration |
| 위치 | `vulnerable/ApiErrorHandler.java`: 예외 클래스 이름, 메시지(Spring DB 예외는 실행한 SQL을 담는다), 원인, 스택 트레이스를 응답에 넣는다 |
| 영향 | 내부 구조(프레임워크·라이브러리·버전), 테이블과 쿼리 모양이 드러나 V2 같은 공격을 다듬기 쉬워진다 |
| 재현 | `curl -G http://127.0.0.1:8081/api/products/search --data-urlencode "q='"` → 500 응답 본문에 `SELECT id, name, price FROM products ...`와 스택 트레이스 |
| 고친 방법 | `fixed/ApiErrorHandler.java`: RFC 9457 ProblemDetail로 일반 메시지와 `traceId`만 준다. 자세한 내용은 같은 `traceId`로 서버 로그에만 남긴다 |
| 테스트 | `v4_sqlErrorLeaksQueryAndStackTrace`, `v4_typeMismatchLeaksInternals`, `v4_malformedJsonLeaksParserDetails` |

---

## 결과 요약 (테스트로 증명, 2026-11-09)
| 공격 | vulnerable | fixed |
|---|---|---|
| V1 남의 주문 조회·수정·삭제, 번호 차례로 수집 | 성공 | 404, 데이터 변화 없음 |
| V2 UNION으로 사용자 표 노출, 숨김 상품 노출 | 성공 | 결과 0건 (글자 그대로 검색) |
| V3 `alg:none`·다른 키·만료 토큰으로 행세 | 성공 | 401 |
| V4 SQL·예외 클래스·스택 트레이스 노출 | 노출 | 일반 메시지 + traceId만 |
| 정상 흐름(로그인, 내 주문 CRUD, 검색) | 동작 | 동작 |
