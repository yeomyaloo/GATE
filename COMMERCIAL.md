# Commercial use of GATE · GATE 상업적 사용

GATE is licensed under the [PolyForm Noncommercial License 1.0.0](LICENSE). This page summarizes it. If this page and [LICENSE](LICENSE) differ, LICENSE wins.

GATE는 [PolyForm Noncommercial License 1.0.0](LICENSE)으로 배포됩니다. 이 문서는 요약이며, 내용이 다르면 [LICENSE](LICENSE)가 우선합니다.

## Free · 무료로 쓸 수 있는 경우

- Personal study, research, experiments, hobby projects · 개인 공부, 연구, 실험, 취미 프로젝트
- Charities, schools and universities, public research, public safety or health organizations, government institutions · 비영리 단체, 학교·대학, 공공 연구·안전·보건 기관, 정부 기관
- Reading the code, and changing it for these purposes · 위 목적으로 코드를 읽고 고쳐 쓰는 것

## Needs a commercial license · 상업 라이선스가 필요한 경우

Any use for a company's business that is not listed above. For example:
- scanning your company's repositories or pull requests in CI
- running gate-server for a team
- including GATE in a product or a paid service

위에 해당하지 않는 회사 업무용 사용입니다. 예:
- 회사 저장소나 PR을 CI에서 검사
- 팀용으로 gate-server 운영
- 제품이나 유료 서비스에 GATE를 포함

## How to get one · 받는 방법

1. Open a GitHub issue titled **"Commercial license inquiry"** in this repository. Include your company name, the modules you plan to use and the number of users. **Do not post confidential information in the issue.**
   이 저장소에 **"Commercial license inquiry"** 제목으로 GitHub 이슈를 열어 주세요. 회사명, 쓸 모듈, 사용자 수를 적어 주시면 됩니다. **이슈에 기밀 정보는 적지 마세요.**
2. After the agreement you receive a license key. 계약 후 라이선스 키를 받습니다.
3. Install the key with one of these:
   키는 다음 중 하나로 설치합니다.
   - environment variable `GATE_LICENSE_KEY` · 환경 변수 `GATE_LICENSE_KEY`
   - the file `~/.gate/license.key` · 파일 `~/.gate/license.key`

   Check it with `java -jar gate-license/build/libs/gate-license.jar status`.
   `java -jar gate-license/build/libs/gate-license.jar status`로 확인합니다.

## About the key · 키에 대해

- The key is checked **offline** with a public key built into GATE. Checking it sends nothing anywhere.
  키는 GATE에 들어 있는 공개 키로 **오프라인 확인**합니다. 확인하느라 아무것도 보내지 않습니다.
- Without a key every feature still works. The command-line tools print one notice line to stderr; JSON output and exit codes do not change.
  키가 없어도 모든 기능이 동작합니다. 명령줄 도구가 오류 출력(stderr)에 안내 한 줄을 표시할 뿐, JSON 출력과 종료 코드는 바뀌지 않습니다.
- GATE never sends data unless you turn on usage reporting yourself. See [PRIVACY.md](PRIVACY.md).
  사용 정보 보내기를 직접 켜지 않는 한 GATE는 데이터를 보내지 않습니다. [PRIVACY.md](PRIVACY.md) 참고.
