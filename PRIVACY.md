# Privacy · 개인정보와 데이터 전송

GATE is a tool that blocks software which sends data behind your back, so GATE does not do it either.
This page lists every network request GATE can make.

GATE는 몰래 데이터를 보내는 소프트웨어를 막는 도구이므로, GATE 자신도 그렇게 하지 않습니다.
이 문서에 GATE가 보낼 수 있는 모든 요청을 적었습니다.

## 1. Usage reporting (opt-in) · 사용 정보 보내기 (직접 켜야 동작)

**Off by default.** Nothing is sent until you turn it on.
**기본값은 꺼짐**입니다. 켜기 전에는 아무것도 보내지 않습니다.

```bash
java -jar gate-license/build/libs/gate-license.jar usage preview          # see exactly what would be sent
java -jar gate-license/build/libs/gate-license.jar usage on --url <URL>   # you choose the receiving address
java -jar gate-license/build/libs/gate-license.jar usage off              # stop and delete the install id
```

When it is on, each command-line run (and gate-server start) sends one small JSON message.
켜면 명령줄 도구를 실행할 때마다(gate-server는 시작할 때) 작은 JSON 하나를 보냅니다.

| Sent · 보내는 것 | Never sent · 보내지 않는 것 |
|---|---|
| install id: a random UUID created when you turn it on, deleted when you turn it off · 켤 때 만든 무작위 UUID(끄면 삭제) | code, file paths · 코드, 파일 경로 |
| GATE version, module name (pkg, review, ...) · GATE 버전, 모듈 이름 | package names, scan results · 패키지 이름, 검사 결과 |
| date (UTC, day only) · 날짜(UTC, 날짜만) | user name, host name, repository URL · 사용자명, 호스트명, 저장소 주소 |
| license status and license key id · 라이선스 상태와 키 ID | anything used to identify a company from its IP address · IP로 회사를 알아내는 조회 |

- The receiving address is the one you set. GATE has no built-in collection server.
  받는 주소는 사용자가 정한 주소입니다. GATE에 미리 정해진 수집 서버는 없습니다.
- Setting `GATE_USAGE=off` turns it off regardless of the settings file (for CI).
  `GATE_USAGE=off`로 설정하면 설정 파일과 상관없이 꺼집니다(CI용).
- A failed send is ignored. It has a 1-second limit and never changes results.
  보내기에 실패하면 무시합니다. 제한 시간은 1초이고, 결과에 영향을 주지 않습니다.
- The settings are stored in `~/.gate/usage.properties`.
  설정은 `~/.gate/usage.properties`에 저장됩니다.

## 2. License check · 라이선스 확인

The key is checked offline. No request is made.
키는 오프라인으로 확인합니다. 요청을 보내지 않습니다.

## 3. Requests GATE makes to do its job · 기능 수행에 필요한 요청

These only happen when you run that feature.
아래 요청은 해당 기능을 실행할 때만 생깁니다.

| Module · 모듈 | Destination · 보내는 곳 | What · 내용 |
|---|---|---|
| gate-pkg | npm registry, PyPI, OSV | the package names and versions being checked · 검사할 패키지 이름·버전 |
| gate-review | Anthropic API (`api.anthropic.com`), only when `ANTHROPIC_API_KEY` is set · 키가 있을 때만 | the changed source files under review · 검토할 변경 파일 |
| gate-review (PR workflow) | GitHub API | review comments on that pull request · 그 PR의 리뷰 댓글 |
| gate-authz | the target listed in its config (loopback by default) · 설정에 적은 대상(기본은 루프백) | authorization test requests · 권한 검사 요청 |
| all CLIs · 모든 CLI | `GATE_SERVER_URL`, only when set · 설정했을 때만 | results, to your own gate-server · 검사 결과(내 gate-server로) |
