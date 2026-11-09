# GATE 진행 기록 (Progress Log)

> 새 세션은 이 파일부터 읽는다. 단계를 마칠 때마다 결정과 이유, 막힌 것, 다음 할 일을 남긴다.
> New sessions start here. Each stage records decisions (and why), blockers, and next steps.

## 현재 상태 (Current status)

| 단계 | 내용 | 상태 |
|---|---|---|
| 0 | 뼈대 (Skeleton) | ✅ 완료 (CI로 검증, 2026-11-08) |
| 1 | ① gate-pkg 패키지 설치 검문소 | ✅ 완료 (2026-11-09) · [측정](measurements/pkg-typo-fp.md) ([설계안](design/stage1-gate-pkg.md)) |
| 2 | ⑤ gate-lab 취약점 실습장 | ✅ 완료 (2026-11-09) · [취약점 설명서](lab/vulnerabilities.md) |
| 3 | ③ gate-authz 권한 검사 자동 테스트 | 🟡 설계 결정 확정, 착수 대기 ([설계안](design/stage3-gate-authz.md)) |
| 4 | ② gate-review AI 보안 코드 리뷰어 (선택) | ⚪ 시작 전 |
| 5 | ④ gate-prompt 프롬프트 인젝션 관문 (선택) | ⚪ 시작 전 |
| ★ | 대시보드·데모·정리 | ⚪ 시작 전 |

---

## 3단계: ③ gate-authz — 설계 (2026-11-09)

### 확정된 결정과 이유
| 항목 | 결정 | 이유 |
|---|---|---|
| 자격 증명 | 설정에 로그인 방법(경로·본문·토큰 필드), 비밀번호는 환경 변수 | 검사마다 새 토큰, 비밀값이 파일에 남지 않음 |
| 수정·삭제 | `allowMutation: true`일 때만, 테스트가 만든 B의 객체에만 | 기본은 조회만 해서 안전 |
| 실행 형태 | 독립 Java CLI (결과는 gate-server로 선택 전송) | 서버가 임의 주소로 요청하는 SSRF 위험 회피 |
| 범위 | BOLA(조회·수정·삭제) + 인증 누락 | 완료 기준에 맞춤. BFLA는 이후 |

### 설계 요점
- 판정은 응답 코드만 믿지 않는다: 수정·삭제는 B가 다시 읽어 실제로 바뀌었는지·사라졌는지 확인 (2단계의 "공격이 실제로 닿는지" 교훈)
- 기준선(B가 자기 객체에 접근 성공)이 없으면 판단 불가(`AUTHZ-INCONCLUSIVE`)로 기록하고 통과로 처리하지 않는다
- 대상은 기본적으로 루프백만. 다른 호스트는 `allowedHosts`에 이름을 명시해야 실행. 명세의 `servers` 주소를 그대로 믿지 않는다
- 완료 기준 테스트는 테스트 안에서 gate-lab 두 버전을 직접 띄워 실행 (Docker 불필요)

### 다음 할 일
설계안대로 1주차(뼈대, OpenAPI 읽기, 로그인, 객체 확보, 조회 BOLA·인증 누락, 안전장치) 착수 확인 받기

---

## 2단계: ⑤ gate-lab — 설계 (2026-11-09)

### 확정된 결정과 이유
| 항목 | 결정 | 이유 |
|---|---|---|
| DB | H2 메모리 DB (PostgreSQL 호환 모드) | 운영 DB와 분리, Docker 없이도 공격 테스트 실행 |
| 구조 | 한 앱 + 프로필 2개 (`vulnerable` 127.0.0.1:8081, `fixed` 127.0.0.1:8082) | 같은 API 명세로 두 버전 비교 (3단계), 취약·수정 코드를 패키지로 나란히 |
| 데모 계정 | 시작할 때 무작위 비밀번호, 콘솔에만 출력 (`GATE_LAB_DEMO_PASSWORD`로 지정 가능) | 비밀값을 코드·문서·커밋에 남기지 않음 |
| OpenAPI | 손으로 쓴 `openapi.yaml` + 컨트롤러 일치 테스트 | 외부 라이브러리 없이 3단계 입력 확보 |

### 설계 요점
- 취약점 4종: V1 IDOR/BOLA(CWE-639), V2 SQL 인젝션(CWE-89), V3 JWT 검증 누락(CWE-347), V4 에러 메시지 노출(CWE-209)
- 안전장치: 루프백 주소가 아니면 시작 거부, `GATE_LAB_ACK=local-only` 필요, compose `lab` 프로필로만 기동, 시작 배너, 가짜 데이터만
- 4단계 측정을 위해 취약 코드에 "여기가 취약점" 주석을 달지 않고, 정답은 `docs/lab/ground-truth.json`에만 둔다

### 1주차 구현 (2026-11-09) — 완료
| 항목 | 내용 |
|---|---|
| 모듈 | `gate-lab` (Spring Boot 4.1.2: webmvc, jdbc, security-oauth2-resource-server, H2 2.4.240). 모두 Spring 공식 스타터·BOM 관리 |
| 안전장치 | `LabSafetyGuard`: `GATE_LAB_ACK=local-only` 없으면 거부, 루프백 주소가 아니면 거부(컨테이너 안에서 명시적으로 허용한 0.0.0.0만 예외), 프로필은 vulnerable·fixed 중 정확히 하나. 다른 빈보다 먼저 확인해 포트를 열기 전에 중단 |
| 데이터 | 스키마·가짜 데이터를 앱이 직접 생성. 데모 계정(alice·bob) 비밀번호와 JWT 서명 키는 시작할 때마다 무작위(콘솔에만 출력, 환경 변수로 지정 가능) |
| 로그인 | `POST /api/auth/login` → HS256 JWT(1시간). 두 버전 공통 |
| V3 JWT | vulnerable: Bearer 토큰의 payload만 Base64로 풀어 `sub`를 믿는다(서명·알고리즘·만료 미검증). fixed: Spring Security 리소스 서버, HS256 고정, 서명·만료 검증 |
| V1 IDOR | vulnerable: `GET·PUT·DELETE /api/orders/{id}`가 주인 확인 없음. fixed: 주인이 아니면 404(존재 여부도 숨김) |
| 공통 API | `GET /api/me`, `GET·POST /api/orders` |

**테스트 (30개 통과, Docker 없이 로컬에서 실행)**
| 공격 | vulnerable | fixed |
|---|---|---|
| `alg:none` 서명 없는 위조 토큰으로 bob 행세 | 성공 (200, bob) | 401 |
| 공격자 키로 서명한 토큰 | 성공 | 401 |
| 만료된 토큰 | 성공 | 401 |
| alice가 bob의 주문 조회 (배송 주소 노출) | 성공 | 404, 주소 노출 없음 |
| alice가 bob의 주문 수정 | 성공 (bob이 보는 값이 바뀜) | 404, 원래 값 유지 |
| alice가 bob의 주문 삭제 | 성공 | 404, 주문 유지 |
| 주문 번호 1~3 차례로 시도해 남의 주문 수집 | 2건 이상 | 0건 |
| 정상 흐름(로그인 → 내 주문 CRUD), 잘못된 자격 증명 401 | 동작 | 동작 |

안전장치 테스트 12개: 동의 없음·공개 주소(0.0.0.0, ::, 사설 IP, 도메인)·프로필 0개/2개는 거부, 실제 앱 기동도 공개 주소·동의 없음에서 실패

**실제 jar로 확인:** 동의 없이 시작하면 종료 코드 1로 거부. `vulnerable`로 띄우면 배너·데모 계정 안내가 콘솔에 나오고, **127.0.0.1:8081에서만 LISTEN**. 서명 없는 위조 토큰으로 `/api/me`가 bob을 돌려줌

**알게 된 것**
- 1단계 서버와 같은 실수를 다시 했다: 생성자 두 개 → Spring이 빈을 만들지 못함. 이번에는 로컬 테스트에서 바로 잡혔다(H2 덕분). 빈에는 생성자를 하나만 두는 것을 규칙으로 삼는다
- H2 메모리 DB 이름이 고정이면 같은 JVM의 여러 테스트 앱이 DB를 함께 써서 충돌한다 → DB 이름에 `${random.uuid}`
- `SpringApplicationBuilder.properties()`는 `application.yml`보다 우선순위가 낮다 → 테스트에서는 명령줄 인자로 넘김

### 2주차 구현 (2026-11-09) — 완료 → **2단계 완료**
| 항목 | 내용 |
|---|---|
| V2 SQL 인젝션 | vulnerable: 검색어를 SQL에 이어 붙임 → `UNION`으로 사용자 이름·비밀번호 해시 노출, 숨김 상품 조건 무력화. fixed: 바인딩 변수 + LIKE 특수 문자 이스케이프 + 길이 제한 |
| V4 에러 노출 | vulnerable: 예외 클래스·메시지(SQL 포함)·원인·스택 트레이스 응답. fixed: RFC 9457 ProblemDetail(일반 메시지 + `traceId`), 자세한 내용은 같은 `traceId`로 서버 로그에만 |
| OpenAPI | `/openapi.yaml`(인증 없이 제공). 두 버전 모두 실제 컨트롤러의 `/api/**` 경로·메서드와 명세가 정확히 같아야 통과하는 테스트 |
| 컨테이너 | `gate-lab/Dockerfile`(digest 고정, 비root), compose `lab` 프로필(`lab-vulnerable`·`lab-fixed`, 호스트 127.0.0.1에만). 컨테이너 안에서만 `GATE_LAB_IN_CONTAINER=true` + `/.dockerenv`로 0.0.0.0 허용 |
| CI | compose-smoke 잡이 실습장 두 버전을 컨테이너로 잠깐 띄워 위조 토큰이 vulnerable에서만 통하는지, 포트가 127.0.0.1에만 열렸는지 확인 후 내림 |
| 문서 | [취약점 설명서](lab/vulnerabilities.md), 4단계용 [정답지](lab/ground-truth.json) + 정답지가 코드와 어긋나면 실패하는 테스트 |

**테스트:** gate-lab 48개 통과 (공격 시나리오 16개 × 두 버전, 안전장치 12개, 정답지 2개, 명세 일치 포함). Docker 없이 로컬에서 실행

### 2단계 완료 기준 확인
| 기준 | 결과 |
|---|---|
| 각 취약점을 재현하는 방법 | ✅ [취약점 설명서](lab/vulnerabilities.md)에 curl 재현 방법, 테스트로 자동 재현 |
| 고친 버전에서 막히는 것을 테스트로 증명 | ✅ 같은 공격을 두 버전에 보내 vulnerable 성공 / fixed 차단 (V1~V4 모두) |
| 외부 공개 금지 | ✅ README 맨 위 경고, 시작 거부 장치, 127.0.0.1 전용 포트를 CI에서도 확인 |

**알게 된 것**
- **거짓 통과를 잡은 구조**: 테스트가 URL을 이중 인코딩해 SQL 인젝션 문자열이 서버에 닿지 않았는데도, 고친 버전 쪽 테스트는 "막혔다"며 통과했다. 같은 공격이 뚫린 버전에서 "성공해야 한다"고 함께 확인했기 때문에 드러났다. 보안 테스트는 공격이 실제로 닿는지(양성 대조군)를 반드시 함께 확인한다
- 정답 노출 검사에서 클래스 이름 `VulnerableSecurityConfig`가 걸려 두 버전 모두 `SecurityConfig`로 바꿨다. 패키지 이름(`vulnerable`/`fixed`)과 `@Profile("vulnerable")`은 사람이 읽기 쉽게 남겼다 → **4단계에서 리뷰어에게 코드를 줄 때 경로·프로필 이름을 중립적으로 바꿔야 한다**(정답지에 명시)
- CI YAML에 Python으로 `\n`을 넣다가 실제 줄바꿈이 되어 또 깨졌다. 푸시 전 YAML 문법 확인으로 잡았다

### 다음 할 일
3단계(③ gate-authz) 설계안 작성: OpenAPI 명세를 읽고 사용자 A의 토큰으로 B의 데이터 접근을 자동 시도 → 실습장 vulnerable에서 IDOR 발견, fixed에서는 발견 없음

---

## 1단계: ① gate-pkg — 설계 (2026-11-09)

### 확정된 결정과 이유
| 항목 | 결정 | 이유 |
|---|---|---|
| CLI 형태 | 순수 Java CLI jar (Spring 없음) | JVM 기동 0.2~0.4초는 레지스트리 조회 시간보다 작다. 서버 없이 단독으로 동작한다. 네이티브 빌드는 필요하면 나중에 추가 |
| 대상 | npm + PyPI | AI가 지어낸 패키지 연구 대부분이 이 둘을 다룬다. npm 먼저, PyPI 이어서 |
| 검사 불가 시 | 확인 요청 (훅 `ask`, CI는 경고) | 개발 흐름을 막지 않으면서 검사 공백을 숨기지 않는다 |

### 설계 전 확인한 사실
- npm: 없는 패키지는 404. 전체 문서에 `time`·`maintainers`가 있고, 버전 문서(`/{name}/{version}`)에 `scripts`·`_npmUser`가 있다. 축약 문서(install-v1)에는 게시 시각이 없다
- PyPI JSON: 없는 패키지는 404. `ownership.roles`는 현재 값만 있어서 관리자 변경 이력은 얻을 수 없다
- 다운로드 수: npm `api.npmjs.org/downloads`, PyPI `pypistats.org` recent API
- OSV `/v1/query`: 패키지와 버전으로 조회되고, 악성 패키지 보고(`MAL-`)도 포함된다
- Claude Code PreToolUse 훅: stdin `tool_input.command`, 응답 `permissionDecision` deny / ask

### 설계 승인 (2026-11-09)
"결정 필요" 4개 항목 모두 제안대로 확정: 임계값 그대로 시작, OSV HIGH는 WARN, 오탐 예외 파일 포함, 3주차부터 이 저장소에 훅 적용


### 1주차 구현 (2026-11-09) — 완료
| 항목 | 내용 |
|---|---|
| 모듈 | `gate-pkg` (순수 Java 17, 외부 라이브러리는 Jackson 3.1.7 하나, Spring Boot BOM으로 고정) |
| HTTP | `JdkHttpFetcher`: JDK HttpClient, 요청 제한 시간 3초, User-Agent에 프로젝트 주소 |
| 캐시 | `FileCache`·`CachingFetcher`: `~/.gate/cache`(또는 `GATE_CACHE_DIR`), 메타데이터 1시간 / 다운로드 24시간 / OSV 6시간 / **404는 10분**, 429·5xx는 저장 안 함, 깨진 캐시는 무시 |
| npm 클라이언트 | 전체 문서 1회 조회로 게시 시각·버전·생명주기 스크립트 추출. 이름은 URL에 넣기 전에 npm 규칙으로 검증(경로 조작 방지). `binding.gyp`도 설치 시 실행으로 간주 |
| 검사 | `PKG-EXIST`(패키지 없음 BLOCK·CRITICAL / 버전 없음 BLOCK·HIGH), `PKG-NEW`, `PKG-SCRIPT`, `PKG-POP`, 확인 불가 시 `PKG-UNCHECKED`(WARN → 훅에서 ask) |
| 스캐너 | 패키지마다, 그리고 메타데이터·다운로드 조회를 병렬로 실행. 판정은 가장 강한 것으로 합침 |

**테스트:** 녹화본 기반 49개 통과 (캐시 7, npm 클라이언트 18, 규칙 15, 스캐너 9). 네트워크를 쓰지 않는다.
실제 레지스트리 `liveTest` 3개 통과 (캐시 없이 패키지 3개 1.3초). CI에서 매주 월요일과 수동 실행 때 돌린다.

**알게 된 것 / 결정**
- npm 다운로드 API는 통계가 아직 없는 새 패키지에 404를 준다 → 다운로드 0으로 처리
- 버전 범위(`^4`)는 1주차에서 풀지 않고 latest로 검사하며, 근거(`resolutionNote`)에 남긴다. semver 라이브러리 추가 여부는 3주차 명령 파서 때 판단
- 패키지가 새것이면 "새 버전" 경고는 중복이므로 내지 않는다
- 모듈을 추가하자 CI의 compose 검증이 실패했다. 서버 이미지 빌드에 `gate-pkg` 폴더가 없어서 Gradle 9가 설정 단계에서 멈췄다 → Dockerfile에 `gate-pkg/build.gradle.kts`만 복사. **모듈을 추가할 때마다 Dockerfile에도 한 줄 추가해야 한다**

### 2주차 구현 (2026-11-09) — 완료
| 항목 | 내용 |
|---|---|
| PyPI 클라이언트 | `pypi.org/pypi/{name}/json` + `pypistats` 주간 다운로드. 이름은 PEP 503으로 정규화(pypistats는 정규화된 이름만 받음). `==1.0`과 `1.0.0`처럼 표기가 다른 버전도 찾음. wheel 없이 sdist만 있으면 `PKG-SCRIPT`(LOW) |
| `PKG-MAINT` (npm) | 요청 버전 게시자(`_npmUser`)가 게시 시각상 직전 버전의 게시자·관리자에 없으면 WARN·HIGH |
| `PKG-VULN` | OSV `/v1/query`. GHSA·PYSEC·CVE 별칭을 하나로 묶음(`requests==2.19.0`: 원본 10건 → 실제 5건). `MAL-`·CRITICAL은 BLOCK, 나머지 WARN, 심각도 모르면 MEDIUM |
| `PKG-TYPO` | 인기 목록 스냅샷(npm·PyPI 각 5,000개, `scripts/update_popular_lists.py`). 기법 5종: 구분자, 비슷한 글자, 접두·접미어, npm 스코프, 편집 거리. 판정: 새것(90일)이거나 대상 다운로드의 1% 미만이면 BLOCK·HIGH, 자리 잡은 패키지면 WARN·LOW, 다운로드를 모르면 WARN·MEDIUM |
| `PKG-COMBO` | `PKG-NEW` + (`PKG-SCRIPT` 또는 `PKG-POP`)이면 BLOCK·HIGH. 스캐너가 다른 검사 뒤에 적용 |

**테스트:** 녹화본 기반 136개 통과 (1주차 49개 포함). 실제 API `liveTest` 7개 통과.

**실제 API로 확인한 결과 (2026-11-09)**
| 요청 | 판정 | 근거 |
|---|---|---|
| npm `lodash`, `express`, `react`, `@types/node`, `axios`, `typescript` / PyPI `requests`, `numpy`, `django`, `Flask_SQLAlchemy` | PASS (결과 0건) | 오탐 없음 |
| npm `lodahs` | **BLOCK** | `PKG-TYPO`(lodash 흉내) + `PKG-VULN`(OSV에 악성 패키지 보고 있음) |
| PyPI `reqeusts` | **BLOCK** | `PKG-EXIST`(PyPI에 없음) |
| 무작위 이름 (npm·PyPI) | **BLOCK** | `PKG-EXIST` |
| PyPI `requests==2.19.0` | WARN | `PKG-VULN` 5건, 최고 HIGH |

**알게 된 것 / 결정**
- PyPI에서는 `Flask_SQLAlchemy`와 `flask-sqlalchemy`가 같은 프로젝트다(PEP 503). 그래서 PyPI는 구분자 차이를 철자 흉내로 보지 않는다
- 1주차 테스트 하나(`fresh-helper-utils`)의 기대값을 WARN에서 BLOCK으로 바꿨다. 1주차에는 조합 규칙이 없어서 WARN이었고, 테스트 주석에 "2주차에 BLOCK으로 올라간다"고 적혀 있던 설계상 변경이다. 테스트는 지우지 않았다
- Windows에서 Python이 CRLF로 파일을 써서 목록 검색이 실패했다 → 스크립트를 LF 고정으로 수정
- 남은 측정 과제(4주차): 인기 목록 바로 바깥(5,001~7,000위)의 정상 패키지로 `PKG-TYPO` 오탐률 측정

### 3주차 구현 (2026-11-09) — 완료
| 항목 | 내용 |
|---|---|
| 명령 파서 | `ShellSplitter`(따옴표·`&& \|\| ; \|`·리다이렉트·줄 이어쓰기)와 `InstallCommandParser`. npm·yarn·pnpm·pip·`python -m pip`·uv·poetry, `sudo`·`env`·`VAR=` 접두어, `bash -c "…"` 안쪽 재해석. 매니페스트 설치·사설 레지스트리·URL/Git/로컬 경로·셸 치환(`$(…)`, `$VAR`)은 "검사 불가"로 기록 |
| 오탐 예외 | `.gate/pkg-allow.txt` (`생태계:이름@버전  # 이유`, 버전·이유 필수). 결과를 지우지 않고 PASS로 바꿔 `allowedBy`를 남김. `PKG-EXIST`와 OSV 악성 보고는 풀 수 없음 |
| CLI | `check <npm\|pypi> <spec>... [--format text\|json]` (종료 코드 0/1/2/3), `hook claude`, `version`. 실행용 단일 jar `gate-pkg.jar`(2.8MB) |
| 훅 | BLOCK→`deny`, WARN·검사 불가→`ask`, PASS→출력 없음. `allow`는 내지 않음. 내부 오류는 모두 `ask` |
| 래퍼 | `gate-pkg/bin/gate-pkg-hook`: 명령에 패키지 관리자 이름이 없으면 JVM 없이 종료(빠른 경로). jar 없음·java 없음·비정상 종료는 모두 `ask`로 바꾸고 항상 0으로 끝남 |
| 저장소 적용 | `.claude/settings.json`에 훅 적용 (matcher `Bash\|PowerShell`) |

**설계 중 확인한 사실 (Claude Code 훅 문서)**
- 훅이 2가 아닌 0이 아닌 코드로 끝나면(예: java 없음) **명령이 그대로 실행된다(fail-open)** → 래퍼가 모든 실패를 `ask`로 바꾸고 항상 0으로 끝나게 했다
- Windows에서는 PowerShell 도구도 있으므로 matcher를 `Bash|PowerShell`로 했다
- deny 이유는 Claude가, ask 이유는 사용자가 읽는다 → deny 이유에 "다른 철자로 재시도하지 말 것, 사용자에게 확인할 것"을 넣었다

**완료 기준 데모 (2026-11-09, 이 세션의 실제 Bash 도구 호출)**
| Claude가 실행하려 한 명령 | 결과 |
|---|---|
| `npm install --dry-run lodahs` | **훅이 차단(deny)**. `PKG-VULN`(OSV `MAL-2025-25502`) + `PKG-TYPO`(lodash 흉내). npm은 이미 `0.0.1-security` 자리 표시 패키지로 바꿔 둔 상태 |
| `pip install --dry-run reqeusts` | **훅이 차단(deny)**. `PKG-EXIST` |
| `npm install --dry-run --no-save lodash` | 훅 개입 없이 실행됨 |
(데모는 안전을 위해 `--dry-run`으로 했다. 훅이 동작하지 않았더라도 설치 스크립트가 실행되거나 파일이 생기지 않는다.)

래퍼 측정(이 PC, Windows + Git Bash): 설치가 아닌 명령 약 0.3초(셸 기동 시간, JVM 미기동), 설치 명령은 캐시 없이 약 2~3초, 캐시가 있으면 약 1.8초(JVM 기동 포함)

**테스트:** 245개 통과 (파서 표 기반 78개, 예외 파일 7개, CLI·훅 계약 24개 포함). CI에 래퍼 계약 확인 추가(설치가 아닌 명령은 출력 없음, jar가 없거나 매니페스트 설치면 ask)

**알게 된 것 / 결정**
- `2>&1` 같은 리다이렉트가 패키지 이름으로 잘못 잡히는 버그를 테스트로 발견해 고쳤다
- 깨끗한 PASS에도 실제로 검사한 버전을 보여 주도록 `PackageVerdict`에 `checkedVersion`을 추가했다
- 악성 패키지 차단 이유에 "예외 파일에 추가하라"는 안내가 나오던 문제를 고쳤다 (풀 수 없는 결과에는 안내하지 않음)
- `npx`(패키지를 내려받아 바로 실행)는 설계안 범위 밖이라 넣지 않았다. AI 도구가 MCP 서버 실행에 자주 쓰므로 다음 단계 후보로 남긴다

### 4주차 구현 (2026-11-09) — 완료 → **1단계 완료**
| 항목 | 내용 |
|---|---|
| 결과 저장 API | gate-server `POST /api/scan-runs`. 입력은 gate-common `Finding` 규칙으로 검증(근거 없는 BLOCK·WARN, AI 단독 BLOCK은 400), 같은 지문은 하나로. 실제 PostgreSQL 통합 테스트(`ScanRunApiIT`)는 CI에서 실행 |
| CLI 결과 전송 | `GATE_SERVER_URL`이 있을 때만, 제한 시간 1초, 실패해도 판정 영향 없음. trigger는 CLI·CLAUDE_HOOK·GITHUB_ACTION 자동 구분 |
| `diff` 명령 | `gate-pkg diff --base <ref> [--head <ref>]`: `package.json`·`requirements*.txt`·`pyproject.toml`에서 **새로 추가·변경된 직접 의존성만** 검사. git ref는 형식 검증 후 셸 없이 실행(옵션 주입 방지). GitHub Actions에서는 해당 파일·줄에 주석, Job Summary 표 |
| GitHub Action | `.github/actions/gate-pkg` 복합 Action(입력값은 환경 변수로 전달 → 스크립트 주입 방지). BLOCK 실패, WARN 경고(`fail-on: warn` 선택), 검사 불가는 경고만. PR 워크플로 `gate-pkg.yml` |
| Action 데모 | CI `action-demo` 잡: 임시 저장소에서 lodahs를 추가하는 커밋 → Action이 **실제로 실패(종료 코드 2)**하고 `package.json`에 "GATE BLOCK npm:lodahs" 주석. 푸시마다 확인 |
| 측정 | [PKG-TYPO 오탐률](measurements/pkg-typo-fp.md): 정상 패키지 600개 중 **차단 4건(0.7%) → 0건**, 경고 45건(7.5%) → 24건(4.0%) |

**측정으로 바꾼 것 (설계 확정 결정 1번 "실제로 돌려 본 뒤 조정")**
- `PKG-TYPO` 차단 조건: "대상 다운로드의 1% 미만"에 **"자기 주간 다운로드 1만 미만"**을 추가. `bpython`·`google-reauth` 등 주간 수만 건의 정상 패키지가 차단되고 있었다
- 스코프 흉내 규칙을 **`@types` 흉내만**으로 축소. `@types/marked`·`@xstate/react` 같은 정상 스코프 패키지를 대량으로 잡고 있었다
- 429·503에 짧게 다시 시도(`RetryingFetcher`): npm 다운로드 API가 몰린 요청 60건 중 17건을 거절했다
- 테스트 하나(`@evil-corp/lodash`는 걸려야 함)를 규칙 축소에 맞춰 뺐고, 대신 측정에서 오탐이던 패키지들이 걸리지 않는지 확인하는 테스트를 넣었다

**CI에서 잡은 문제**
- 서버 컨트롤러에 생성자가 두 개라 Spring이 컨텍스트를 만들지 못했다 → 생성자 하나로. CI 주석에 `Caused by` 줄을 함께 남기도록 개선
- 워크플로 YAML의 `printf` 줄바꿈이 실제 줄바꿈으로 들어가 워크플로 전체가 무효가 됐다 → 수정. 이후 푸시 전에 YAML 문법을 확인한다

**테스트:** gate-pkg 284개, gate-server 단위 6개 + 통합 5개(CI). CI 잡: 빌드·테스트, compose 검증, Action 데모, 주간 실제 레지스트리

**알려진 한계 (다음 과제 후보)**
1. **npm 버전 범위를 풀지 않는다.** `^4.21.2`도 최신판(예: 5.3.0) 기준으로 검사한다. `package.json`은 대부분 범위로 적으므로 PR 검사에서 영향이 크다 → semver 범위 해석 추가
2. **npm 다운로드 API 요청 제한.** 의존성이 수십 개 추가되는 PR에서는 다운로드 수를 못 받아 "검사 불가"가 늘 수 있다 → 묶음 조회 API(`/downloads/point/last-week/a,b,c`) 사용
3. ~~"비슷하지만 자리 잡은 패키지" 경고가 PyPI에서 6.7%~~ → **해결 (2026-11-09, 사용자 결정)**: 경고 대신 기록(PASS·INFO)으로 낮춤. 저장된 측정 원자료로 계산하면 PKG-TYPO 경고·차단 600개 중 0건
4. 탐지율(진짜 철자 흉내를 잡는 비율)은 아직 재지 않았다 → OSV `MAL-` 목록으로 측정
5. `npx`(받아서 바로 실행)는 범위 밖 → AI 도구의 MCP 서버 실행에 자주 쓰여 우선순위가 높다

### 1단계 완료 기준 확인
| 기준 | 결과 |
|---|---|
| 존재하지 않는 패키지 설치 시 훅이 실제로 막는다 | ✅ `pip install reqeusts` → deny (3주차, 이 저장소에서 실제 Claude Code Bash 호출) |
| 철자 흉내 패키지 설치 시 훅이 실제로 막는다 | ✅ `npm install lodahs` → deny |
| 정상 패키지는 통과 | ✅ `npm install lodash` → 개입 없음. 정상 패키지 600개 표본에서 차단 0건 |
| 각 검사 항목에 테스트 | ✅ 규칙 9종(EXIST·NEW·SCRIPT·POP·MAINT·VULN·TYPO·COMBO·UNCHECKED) 모두 해당·비해당 테스트 |

### 다음 할 일
1. 위 "알려진 한계"(1·2·4·5번) 중 1단계 보강으로 할 것을 고르기
2. 2단계(⑤ gate-lab 취약점 실습장) 설계안 작성 → 확인 → 구현
---

## 0단계: 뼈대 — 2026-11-08

### 확정된 결정과 이유
| 항목 | 결정 | 이유 |
|---|---|---|
| 목적 | 이직 포트폴리오 | README·설계 결정 기록·측정 수치에 무게. 1~3단계 완성도 우선 |
| 백엔드 | Java 17 + Spring Boot **4.1.2** | 사용자 선택. 4.1.2는 2026-10-22 공개된 현재 최신 안정판(4.2는 RC) |
| 빌드 | Gradle **9.8.2** (wrapper, `distributionSha256Sum` 고정) | 멀티 모듈·버전 카탈로그. 9.9.0은 공개 이틀째라 안정판 9.8.2 선택 |
| 프론트 | React + Vite (TS) | 마지막 단계에서 추가. 0단계에서는 폴더도 만들지 않음 |
| 저장소 | 단일 저장소(모노레포) | 공통 모델 공유, compose 하나로 기동, 포트폴리오 링크 하나 |
| 공개·라이선스 | GitHub 공개, MIT | 개인 비상업 포트폴리오 |
| DB | PostgreSQL **18.6** (alpine3.24, digest 고정) | `location`·`evidence`를 JSONB로 저장 |
| 캐시 | Redis **8.8.4** (alpine3.23, digest 고정) | 1단계 외부 API(npm·PyPI·OSV) 요청 제한 대비 |
| 런타임 이미지 | eclipse-temurin 17.0.21_9 JDK→JRE alpine 멀티스테이지, digest 고정, 비root 사용자 | 재현 가능한 빌드, 최소 권한 |
| 스키마 관리 | Flyway (`V1__init.sql`) | 변경 이력을 코드로 남김 |
| 의존성 버전 | Spring Boot BOM에 맡김 (JUnit, Flyway, PostgreSQL 드라이버, Testcontainers) | 직접 고정 버전 수를 줄여 충돌 방지. 추가 라이브러리는 모두 Spring 공식 스타터 또는 해당 공식 프로젝트 |

### 공통 데이터 모델
- `scan_run`(검사 1회) 1 : N `finding`(발견 사항)
- `finding` = 무엇이(`rule_id`) · 어디서(`location` JSONB) · 위험도(`severity`) · 근거(`evidence` JSONB) · 시각(`detected_at`) + `decision`(BLOCK/WARN/PASS) + `source`(RULE/AI/RULE_AND_AI) + `fingerprint`
- 규칙(코드와 DB 양쪽에서 강제)
  - BLOCK·WARN에는 근거 필수 (코드)
  - AI 단독(`source=AI`)은 BLOCK 불가 (코드 + DB CHECK)
  - 같은 실행 안에서 같은 지문 중복 금지 (DB UNIQUE)
- 결과 저장 API는 **아직 만들지 않음**. 1단계에서 실제로 필요할 때 만든다 (요청하지 않은 기능 추가 금지)

### 한 일
- Gradle 멀티 모듈: `gate-common`(순수 Java 모델), `gate-server`(Spring Boot)
- `gate-common`: `Finding`, `Location`(sealed: package/file/endpoint), `Severity`, `Decision`, `GateModule`, `Trigger`, `FindingSource`, `Fingerprints`
- `gate-server`: 헬스체크(`/actuator/health`, db·redis 컴포넌트 표시), Flyway 스키마
- `docker-compose.yml`: db·cache·server, 모든 포트 `127.0.0.1` 바인딩, healthcheck 의존 기동, 비밀값은 `.env`에서 필수값으로 읽음(`:?` 문법)
- README(영·한), 다이어그램 3장(`docs/images/`), MIT 라이선스, `.gitignore`(`.env` 제외), `.gitattributes`

### 테스트 결과
| 테스트 | 결과 |
|---|---|
| `gate-common` `FindingTest` (6개) | ✅ 통과 |
| `gate-server` `HealthCheckIT` (2개: 헬스 UP, 테이블 생성) | ✅ CI에서 통과 (로컬은 Docker 없어 건너뜀) |
| `./gradlew build` | ✅ 성공 (로컬·CI) |
| CI `compose-smoke`: `docker compose up --wait` → 헬스 UP(db·redis) → `scan_run`·`finding` 테이블 확인 | ✅ 통과 |

### 막힌 것과 해결 (Blockers → resolved)
- 작업 PC에 Docker가 없다(설치하지 않기로 함). → **GitHub Actions(`.github/workflows/ci.yml`)에서 0단계 완료 기준을 검증**하도록 했다. GitHub 러너에는 Docker가 있다.
  - `build` 잡: `./gradlew build` + `HealthCheckIT`가 **실제로 실행됐는지(skipped=0)** 확인. 건너뛰면 실패 처리
  - `compose-smoke` 잡: 임시 `.env`(무작위 비밀번호) → `docker compose up --wait` → 헬스체크 → Flyway 테이블 확인 → `down -v`
  - 실패한 테스트 메시지는 Actions 주석(annotation)으로 남겨 로그인 없이도 원인을 볼 수 있게 했다
  - Action 버전은 커밋 SHA로 고정. 공개 2주 미만 릴리스는 피했다 (checkout v7.0.1, setup-java v6.1.0, setup-gradle v6.4.0, upload-artifact v7.0.2)
- CI 첫 실행에서 `HealthCheckIT`가 실패했다. Testcontainers `DockerImageName`이 `이름:태그@digest` 형식을 받지 않아서다. → 테스트 코드는 `이름@digest`만 쓰고 태그는 주석으로 남겼다 (compose는 `태그@digest` 그대로)

### 로컬에서 직접 확인하고 싶을 때 (Docker 설치 시)
```bash
cp .env.example .env            # GATE_DB_PASSWORD 채우기
docker compose up -d --build
docker compose ps               # db, cache, server 모두 healthy
curl http://localhost:8080/actuator/health   # status UP, db UP, redis UP
./gradlew :gate-server:test     # HealthCheckIT 2개가 skipped 아닌 passed
git status                      # .env가 보이지 않아야 함
```

### 결정 필요 (1단계 시작 시)
1. **gate-pkg CLI 형태**: Claude Code 훅은 설치 명령마다 실행되므로 JVM 기동 시간(약 0.5~1초)이 부담이 될 수 있다.
   - ① Java CLI jar ② GraalVM 네이티브 빌드 ③ 훅은 가벼운 스크립트로 두고 gate-server API 호출
2. 1단계 대상 레지스트리 범위: npm만 / npm + PyPI / 그 이상
3. 1단계 설계안(검사 규칙·임계값·API·화면)은 코드 전에 확인받는다

### 다음 할 일 (Next)
→ 1단계 섹션으로 이동
