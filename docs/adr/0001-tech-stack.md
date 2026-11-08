# ADR 0001: 기술 스택과 저장소 구조 (Tech stack and repository layout)

- 상태 (Status): 채택 (Accepted)
- 날짜 (Date): 2026-11-08

## 배경 (Context)
GATE는 이직 포트폴리오 프로젝트다. 1~3단계만으로도 완성품이 되어야 하고, 설계 결정과 측정 결과를 문서로 보여 줘야 한다. 이 프로젝트 자체가 공급망 보안을 다루므로 빌드도 재현 가능하고 고정되어 있어야 한다.

GATE is a portfolio project. Stages 0–3 must stand on their own, and design decisions must be documented. Because the project is about supply-chain security, its own build must be reproducible and pinned.

## 결정 (Decision)
- **Java 17 + Spring Boot 4.1.2**, **Gradle 9.8.2** 멀티 모듈 (`gate-common`, `gate-server`, 이후 모듈별 추가)
- **단일 저장소(모노레포)**: 공통 결과 모델을 모든 모듈이 직접 참조하고, `docker compose` 하나로 전체를 띄운다
- **PostgreSQL**(결과 저장, JSONB) + **Redis**(외부 API 캐시)
- **React + Vite (TS)** 대시보드는 마지막 단계에서 추가
- 버전 고정: Gradle 배포판 SHA-256, Docker 이미지 태그 + digest, 라이브러리는 버전 카탈로그 + Spring Boot BOM

## 결과 (Consequences)
- 좋은 점: 모듈 간 모델 공유가 쉽고, 리뷰어가 저장소 하나로 전체를 볼 수 있다
- 감수할 점: 1단계 CLI를 JVM으로 만들면 Claude Code 훅에서 기동 시간이 부담될 수 있다 → 1단계에서 별도 결정 (`docs/progress.md` 결정 필요 항목)
- 버전을 고정했으므로 보안 업데이트는 수동으로 올려야 한다 → 이후 Dependabot/Renovate 도입 검토
