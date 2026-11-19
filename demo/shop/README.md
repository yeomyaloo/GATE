# gate-review demo PR

This branch exists only to demonstrate the `gate-review` pull request check. It adds an "order export"
controller the way an AI coding tool might write it. The code is not part of the Gradle build and is not run.

Two planted problems:
- the `status` filter is concatenated into SQL (SQL injection, CWE-89) — expected **BLOCK** from the Semgrep rule
- the order detail endpoint does not check that the order belongs to the caller (IDOR, CWE-639) — only the AI
  review can see this; expected **WARN** (AI-only findings never block). Without an API key it is not reported.

이 브랜치는 `gate-review` PR 검사를 보여 주기 위한 데모입니다. 병합하지 않습니다.
