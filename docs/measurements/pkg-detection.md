# gate-pkg 탐지율 측정: 실제 악성 패키지 (2026-11-19)

> How many real malicious packages does gate-pkg catch — by name alone (before OSV knows), and with the full check?

## 방법
- **자료:** OSV의 악성 패키지 항목(`MAL-`). 원자료는 [OpenSSF malicious-packages](https://github.com/ossf/malicious-packages)이고 라이선스는 Apache-2.0이다.
  - OSV 전체 묶음(npm 약 222MB, PyPI 약 38MB)은 저장소 밖 작업 폴더에 받았다.
  - 저장소에는 표본의 이름·OSV ID·결과만 남겼다.
- **규모:** 악성 패키지 이름 npm **224,569개**, PyPI **12,077개** 중 무작위로 뽑았다(시드 42).
- **두 가지를 따로 쟀다.** 그대로 재면 gate-pkg가 OSV를 조회하므로, OSV에 올라간 악성 패키지는 당연히 잡힌다. 그래서 둘을 나눴다.

  | 지표 | 방법 | 의미 |
  |---|---|---|
  | ① 이름만으로 | 표본 300개(생태계마다)를 철자 흉내 탐지기에만 넣는다. 네트워크 없음 | **OSV에 올라가기 전(0일)** 에 이름만 보고 잡는 비율 |
  | ② 전체 검사 | 표본 중 50개(생태계마다)를 실제 `gate-pkg check`로. 레지스트리·OSV 조회 | 오늘 설치하려 하면 막히는가 |

- **재현:** `./gradlew :gate-pkg:cliJar && python3 scripts/measure_detection.py --data-dir <저장소 밖 폴더> --sample 300 --full-sample 50 --out docs/measurements/pkg-detection.json`
- **원자료:** [pkg-detection.json](pkg-detection.json)
- 패키지를 설치하거나 실행하지 않는다. 이름과 메타데이터만 다룬다.

## 결과

| | npm | PyPI |
|---|---|---|
| ① 이름만으로 잡음 (표본 300) | **1 (0.3%)** | **24 (8.0%)** |
| ② 전체 검사 BLOCK (표본 50) | 45 | 49 |
| ② 전체 검사 WARN | 3 | 1 |
| ② 전체 검사 PASS | 2 | 0 |

- ② 전체 검사에서 걸린 규칙:
  - npm: 인기도 `PKG-POP` 41, OSV `PKG-VULN` 40, 없음 `PKG-EXIST` 5, 게시자 변경 `PKG-MAINT` 2, 철자 흉내 `PKG-TYPO` 1
  - PyPI: `PKG-EXIST` 49. PyPI는 악성 패키지를 지우므로 "없음"으로 막힌다
- ① 이름만으로 잡은 PyPI 24개는 모두 유명 패키지의 철자 흉내다. 예: `beautiflusoup`(beautifulsoup4), `discrod-py`(discord-py), `gunicorm`(gunicorn), `ppillow`(pillow), `huggingface-hubs`(huggingface-hub)

## 읽는 법
1. **이름만으로는 거의 못 잡는다. 특히 npm에서 그렇다(0.3%).** 실제 악성 패키지 대부분은 유명 패키지 흉내가 아니다.
   - 회사 내부 이름을 흉내 낸 의존성 혼동 공격이 많다. 예: `@kucoin-gbiz-next/tools`, `@locallife/init`
   - 무작위 이름과 스팸 패키지도 많다. 예: `@crabas0npm/officia-quibusdam-praesentium`, `2023-coin-master-free-spins-…`
   - 철자 흉내 탐지기는 원래 이런 것을 잡는 도구가 아니다. 1단계 오탐 측정(정상 600개 중 경고·차단 0)과 함께 보면, 이 탐지기는 **좁고 정확한 도구**다.
2. **0일 방어는 이름이 아니라 다른 신호가 맡는다.** npm ②의 대부분은 OSV가 아니라도 `PKG-POP`(주간 다운로드가 아주 적음)으로 걸렸다(41개).
   - 갓 나온 악성 패키지는 다운로드가 거의 없으므로, OSV에 올라가기 전에도 이 규칙이 막는다.
3. **npm PASS 2건은 놓친 것이 아니다.** `@mastra/google-cloud-pubsub`, `@toptal/picasso-popper`는 정상 패키지이고, 탈취된 **특정 버전**(1.0.6, 2.1.0)만 악성이다.
   - 오늘 설치하면 받는 최신판은 OSV 목록에 없다.
   - 그 버전을 고정해서 설치하면 `PKG-VULN`으로 막힌다. 1주차의 버전 범위 해석 덕분에 범위로 적어도 실제로 받을 버전을 검사한다.
4. **npm WARN 3건:** 인기도만 걸린 스코프 패키지 1건, 다운로드 수를 받지 못한 `PKG-UNCHECKED` 2건이다. 막지는 않았지만 Claude Code 훅에서는 사용자에게 확인을 묻는다.

## 한계
- ② 전체 검사는 표본이 작다(생태계마다 50개). 오늘 레지스트리 상태 기준이고, 지워진 패키지는 "없음"으로 막힌 것이다.
- ① 이름만으로 잰 값은 **OSV에 올라간 뒤의 이름**을 넣은 것이다. 실제 0일 상황에서 다운로드 수가 얼마였는지는 알 수 없다.
- `PKG-POP`은 다운로드 수 API에 의존한다. API가 요청을 제한하면 "검사 불가"(경고)로 떨어진다. 1주차 묶음 조회로 줄였지만 없앨 수는 없다.
