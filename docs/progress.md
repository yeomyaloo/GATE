# GATE 진행 기록 (Progress Log)

> 새 세션은 이 파일부터 읽는다. 단계를 마칠 때마다 결정과 이유, 막힌 것, 다음 할 일을 남긴다.
> New sessions start here. Each stage records decisions (and why), blockers, and next steps.

## 현재 상태 (Current status)

| 단계 | 내용 | 상태 |
|---|---|---|
| 0 | 뼈대 (Skeleton) | ✅ 완료 (CI로 검증, 2026-11-08) |
| 1 | ① gate-pkg 패키지 설치 검문소 | 🔵 구현 중: 2주차 완료, 3주차 대기 ([설계안](design/stage1-gate-pkg.md)) |
| 2 | ⑤ gate-lab 취약점 실습장 | ⚪ 시작 전 |
| 3 | ③ gate-authz 권한 검사 자동 테스트 | ⚪ 시작 전 |
| 4 | ② gate-review AI 보안 코드 리뷰어 (선택) | ⚪ 시작 전 |
| 5 | ④ gate-prompt 프롬프트 인젝션 관문 (선택) | ⚪ 시작 전 |
| ★ | 대시보드·데모·정리 | ⚪ 시작 전 |

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

### 다음 할 일 (3주차)
설치 명령 파서(npm·yarn·pnpm·pip·uv·poetry), `check`·`hook` CLI, 실행 가능한 단일 jar와 래퍼 스크립트, 오탐 예외 파일(`.gate/pkg-allow.txt`), Claude Code 훅 데모, 이 저장소에 훅 적용
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
