# PKG-TYPO 오탐률 측정 (2026-11-09)

> Measuring false positives of the typosquat rule on legitimate, popular packages.

## 방법
- **대상:** 인기 목록 스냅샷(상위 5,000) 바로 바깥인 **5,001~7,000위** 패키지. 널리 쓰이는 정상 패키지라고 가정한다
  - npm: `npm-high-impact@1.13.0` 다운로드 순위
  - PyPI: `hugovk/top-pypi-packages` 30일 다운로드 순위
- **표본:** 생태계별 300개, 무작위(시드 42). 두 번의 측정 모두 같은 표본이다
- **실행:** 실제 `gate-pkg check`(전체 파이프라인, 실제 레지스트리·OSV). 패키지를 설치하거나 실행하지 않는다
- **오탐 정의:** 정상 패키지에 `PKG-TYPO`가 경고(WARN)나 차단(BLOCK)을 낸 경우
- **재현:** `./gradlew :gate-pkg:cliJar && python3 scripts/measure_typo_fp.py --sample 300`
- 원자료: [pkg-typo-fp-before.json](pkg-typo-fp-before.json) (수정 전), [pkg-typo-fp.json](pkg-typo-fp.json) (수정 후)

## 결과

| 생태계 | 표본 | 수정 전 경고 | 수정 전 **차단** | 수정 후 경고 | 수정 후 **차단** |
|---|---|---|---|---|---|
| npm | 300 | 29 (9.7%) | 0 (0.0%) | 4 (1.3%) | **0 (0.0%)** |
| PyPI | 300 | 16 (5.3%) | **4 (1.3%)** | 20 (6.7%) | **0 (0.0%)** |
| 합계 | 600 | 45 (7.5%) | 4 (0.7%) | 24 (4.0%) | **0 (0.0%)** |

## 수정 전에 드러난 문제와 고친 내용

| 문제 | 예 | 수정 |
|---|---|---|
| **정상 패키지 차단 4건 (PyPI)**. "대상 다운로드의 1% 미만"만으로 차단했는데, 대상이 매우 큰 패키지면 주간 수만 건의 정상 패키지도 1% 미만이 된다 | `bpython`(ipython의 0.39%, 주간 약 4만), `google-reauth`, `pytest-cover`, `acachecontrol` | 비율로 차단하려면 **자기 주간 다운로드도 1만 미만**이어야 한다(`typoMaxWeeklyDownloads`). 실제 철자 흉내 패키지는 대개 수십~수백 건이다 |
| **npm 스코프 규칙의 소음**. "`@아무스코프/인기이름`"을 흉내로 봤더니 정상 스코프 패키지가 대량으로 걸렸다 (npm 경고 29건 중 25건) | `@types/marked`, `@xstate/react`, `@langchain/openai` | 스코프 규칙을 `types-node` ↔ `@types/node` 같은 **`@types` 흉내만** 남겼다 |
| **npm 다운로드 API 요청 제한(429)**. 짧게 몰린 요청을 거절한다 (60건 중 17건) | 측정 중 대량의 "검사 불가" | 429·503에 짧게 최대 2번 다시 시도(`RetryingFetcher`) |

## 남은 경고(수정 후 24건)의 성격
모두 "**비슷하지만 자리 잡은 패키지**"(WARN·LOW)다. 예: `postgres`↔`postgrest`, `progressbar`↔`progressbar2`, `mypy-boto3-iot`↔`mypy-boto3-iam`, `clipboard`↔`clipboardy`.
지금 설계에서는 경고가 훅의 `ask`(사용자 확인)가 된다. **PyPI 설치 15번 중 약 1번꼴로 확인 창이 뜬다는 뜻**이라, 이 경우를 경고가 아닌 기록(INFO)으로 낮출지가 다음 결정 사항이다.

## 한계
- 5,001~7,000위가 모두 정상이라는 가정에 기댄다. 이 안에 실제 철자 흉내 패키지가 섞여 있으면 오탐률이 실제보다 높게 나온다
- **탐지율(진짜 철자 흉내를 잡는 비율)은 재지 않았다.** 확인된 사례(`lodahs`, `reqeusts` 등)만 테스트로 확인했다. OSV 악성 패키지(`MAL-`) 목록으로 탐지율을 재는 것이 다음 과제다
- 측정 중 npm 다운로드 API 할당량을 소진해 **두 번째 측정에서 npm 181개는 다운로드 수를 받지 못했다.** 같은 표본에 탐지기(`TypoDetector`)만 오프라인으로 돌려 보니 일치 건수가 npm 4건·PyPI 20건으로 측정값과 같았다. 따라서 `PKG-TYPO` 수치에는 영향이 없다. 평소 사용(훅으로 1~3개)에서는 재현되지 않았다
