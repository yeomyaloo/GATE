# gate-review 검출·오탐·미탐 측정 (2026-11-13)

> Measuring detection, false positives and misses of the security reviewer on the gate-lab ground truth.
> **Status:** static analysis measured. **AI measurement pending** (needs the user's `ANTHROPIC_API_KEY`).

## 방법
- **정답지:** [docs/lab/ground-truth.json](../lab/ground-truth.json) (2단계에서 작성).
  - 취약점 4개(V1 IDOR, V2 SQL 인젝션, V3 JWT 검증 누락, V4 오류 정보 노출)의 파일·줄 범위·CWE
  - 깨끗해야 할 파일 5개
- **중립화:** 취약한 버전과 고친 버전을 **따로** 검토한다.
  - 둘 다 패키지·경로·`@Profile` 이름을 `app`으로 바꾼다.
  - 그래서 리뷰어는 이름으로도, 두 버전을 비교해서도 어느 쪽이 취약한지 알 수 없다.
  - 줄 수는 그대로라 정답지의 줄 번호를 그대로 쓴다.
- **채점:**
  - **검출:** 정답 취약점의 파일·줄 범위와 겹치고, CWE 종류가 같은 지적.
    - CWE 종류는 묶음으로 본다. 예: 89·564는 SQL, 639·862는 권한.
  - **미탐:** 어떤 지적도 맞히지 못한 정답.
  - **오탐:** 어떤 정답과도 맞지 않는 지적.
    - 위치가 맞아도 종류가 다르면 오탐이다.
    - 고친 버전에 대한 지적은 모두 오탐이다.
  - 같은 취약점을 여러 번 가리킨 지적은 오탐이 아니라 **중복**으로 따로 센다.
- **비교 대상:** 정적 분석만 · AI만 · 합침(BLOCK만) · 합침(경고 포함).
  - 합칠 때의 규칙은 같다: AI만의 지적은 언제나 경고다.
- **재현:**

  ```bash
  ./gradlew :gate-review:cliJar
  java -jar gate-review/build/libs/gate-review.jar measure --out docs/measurements/review-accuracy-static-gate.json
  java -jar gate-review/build/libs/gate-review.jar measure --rules p/java --out docs/measurements/review-accuracy-static-pjava.json
  java -jar gate-review/build/libs/gate-review.jar measure --estimate --runs 3                   # 호출 없이 예상 비용만
  java -jar gate-review/build/libs/gate-review.jar measure --ai --budget-usd 3 --runs 3 --out ...  # AI (키 필요)
  ```

  - Semgrep 1.182.1(고정)이 필요하다. 경로는 `GATE_SEMGREP`으로 지정한다.
- **원자료:**
  - [review-accuracy-static-gate.json](review-accuracy-static-gate.json)
  - [review-accuracy-static-pjava.json](review-accuracy-static-pjava.json)

## 결과: 정적 분석 (2026-11-13)

| 규칙 묶음 | V1 IDOR | V2 SQLi | V3 JWT | V4 오류 노출 | 검출 | 오탐 (취약 버전) | 오탐 (고친 버전) |
|---|---|---|---|---|---|---|---|
| **GATE 규칙** (`gate-java.yml`, 3개) | ✗ | ✓ | ✓ | ✓ | **3/4** | 0 | **0** |
| 기성 규칙 `p/java` (Semgrep 레지스트리, 기준선) | ✗ | ✓ | ✗ | ✗ | 1/4 | 0 | 0 |

- **GATE 규칙**은 지적 6건을 냈다. 모두 정답 위치였다(중복 3건: V4 4줄).
  - 판정으로 나누면 BLOCK은 V2·V3이다.
  - V4는 규칙 심각도가 WARNING이라 경고다.
- **`p/java`**는 지적 2건을 냈다. 둘 다 V2이며 규칙은 `tainted-sql-string`, `spring-sqli`이다.
  - JWT 서명 검증 누락과 오류 응답의 예외 노출은 기성 규칙에 해당 패턴이 없었다.
- **V1(IDOR)은 두 묶음 모두 놓쳤다. 예상대로다.** "이 주문이 요청한 사람의 것인지"는 규칙 패턴이 아니라 의미를 알아야 판단할 수 있다.
  - AI가 이것을 보완하는지가 아래 AI 측정의 핵심이다.

### 이 수치를 읽을 때 주의할 점 (공정성)
- **GATE 규칙은 정답을 아는 같은 저자가 썼다.** 3/4는 "실습장에 맞춘 규칙"의 결과다. 일반 코드에서의 검출률로 읽으면 안 된다.
  - 그래서 손대지 않은 기성 규칙(`p/java`)을 기준선으로 함께 적었다.
  - 대신 규칙은 실습장 전용 이름이 아니라 일반 Java·Spring 패턴으로 썼다.
  - 각 규칙에는 `semgrep --test` 사례(잡아야 할 것·잡으면 안 되는 것)가 있다.
- 실습장은 작다(취약점 4개, Java 파일 약 12개). 비율이 아니라 **어느 것을 잡고 놓쳤는지**가 의미 있는 결과다.
- `p/java`는 레지스트리의 그날 버전이다. 날짜(2026-11-13)와 함께 읽는다.

## 결과: AI (대기)

사용자의 API 키가 준비되면 같은 명령으로 측정하고 여기에 채운다.

| 항목 | 값 |
|---|---|
| 모델 | Claude Opus 5.5 (`claude-opus-5-5`), effort `high` |
| 반복 | 3회 (같은 입력에도 결과가 달라질 수 있어 회차별 결과와 범위를 남긴다) |
| 예상 비용 (`measure --estimate`) | 1회(두 버전) 약 $0.49, 최대 약 $0.73. **3회 약 $1.5, 최대 약 $2.2** (예산 상한 $10) |
| 예산 장치 | `--budget-usd` 필수, 누적 비용이 넘으면 남은 호출을 하지 않음 |
| 함께 기록 | 지어낸 인용으로 버린 지적 수, 토큰, 비용, 거절·오류 상태 |

- 예상 비용의 근거:
  - 입력은 코드 글자 수 ÷ 3으로 넉넉하게 어림했다(버전마다 약 1.2만 토큰).
  - 출력은 추론을 포함해 요청당 1만 토큰(최대 1.6만)으로 가정했다.
  - 프롬프트 캐시 쓰기·읽기 토큰은 따로 세지 않는다. 지침이 짧아 영향이 작다.

가설: AI가 V1(IDOR)을 찾으면 "합침(경고 포함)"이 4/4가 된다. 그래도 V1은 AI만의 지적이라 BLOCK이 아니라 경고다. 가설이 틀려도 그대로 기록한다.
