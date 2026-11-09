# ⚠️ gate-lab — INTENTIONALLY VULNERABLE. DO NOT EXPOSE.

> **이 앱은 일부러 취약하게 만든 실습용 앱입니다. 절대 외부에 공개 배포하지 마세요.**
> 로컬(127.0.0.1)에서만 띄우세요. 실제 데이터나 비밀값을 넣지 마세요.
>
> **This application is deliberately insecure. Never deploy it publicly.**
> Run it on 127.0.0.1 only, with fake data.

## 무엇인가 (What)
GATE의 ⑤ 취약점 실습장입니다. 같은 주문 API를 두 프로필로 띄웁니다.

| 프로필 | 주소 | 내용 |
|---|---|---|
| `vulnerable` | 127.0.0.1:8081 | 취약점이 있는 구현 |
| `fixed` | 127.0.0.1:8082 | 같은 기능을 고친 구현 |

취약점 설명과 재현 방법은 [docs/lab/vulnerabilities.md](../docs/lab/vulnerabilities.md)(2단계 2주차)에 있습니다. 설계는 [docs/design/stage2-gate-lab.md](../docs/design/stage2-gate-lab.md)에 있습니다.

## 안전장치 (Safety guards)
- `server.address`가 루프백(127.0.0.1, ::1)이 아니면 **시작을 거부**합니다
- 환경 변수 `GATE_LAB_ACK=local-only`가 없으면 시작하지 않습니다
- `vulnerable`과 `fixed` 중 정확히 하나의 프로필이 켜져 있어야 합니다
- 데모 계정(alice, bob) 비밀번호는 시작할 때마다 무작위로 만들어 콘솔에만 출력합니다 (`GATE_LAB_DEMO_PASSWORD`로 지정 가능)
- JWT 서명 키도 시작할 때마다 무작위입니다 (`GATE_LAB_JWT_SECRET`, 32바이트 이상으로 지정 가능)

## 실행 (Run, local only)
```bash
./gradlew :gate-lab:bootJar
GATE_LAB_ACK=local-only java -jar gate-lab/build/libs/gate-lab.jar --spring.profiles.active=vulnerable
GATE_LAB_ACK=local-only java -jar gate-lab/build/libs/gate-lab.jar --spring.profiles.active=fixed
```
