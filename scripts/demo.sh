#!/usr/bin/env bash
# GATE 데모 시나리오 (마지막 단계 설계안 5장). 모듈 4개를 차례로 실행하고 모든 결과를 gate-server에 저장한다.
#
#   1. AI가 존재하지 않는 패키지를 추천 → Claude Code 훅(gate-pkg)이 설치를 막음
#   2. AI가 만든 코드 PR에 SQL 인젝션 → gate-review가 BLOCK (API 키가 없으면 정적 분석만)
#   3. 배포 전 권한 검사 → gate-authz가 실습장 뚫린 버전에서 IDOR 발견
#   4. AI 기능의 프롬프트 인젝션 → gate-prompt 출력 관문이 공격을 모두 막음
#   5. 대시보드(gate-server)에서 모듈 4개의 결과를 한 화면에 확인
#
# 필요한 것: docker compose로 띄운 db·server와 실습장(lab 프로필), Java 17, jq, 고정 버전 Semgrep(GATE_SEMGREP 또는 PATH).
#   docker compose up -d --wait && docker compose --profile lab up -d --wait lab-vulnerable lab-fixed
#   scripts/demo.sh
# 실습장 데모 비밀번호는 .env의 GATE_LAB_DEMO_PASSWORD에서 읽는다. 사용 정보 보내기는 이 스크립트에서 끈다.
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
server=${GATE_SERVER_URL:-http://127.0.0.1:8080}
export GATE_SERVER_URL=$server GATE_USAGE=off
cd "$root"

step() { printf '\n\033[1m== %s\033[0m\n' "$*"; }
fail() { printf '\033[31mDEMO FAILED: %s\033[0m\n' "$*" >&2; exit 1; }

step "0. build the command-line tools"
./gradlew --no-daemon -q :gate-pkg:cliJar :gate-review:cliJar :gate-authz:cliJar :gate-prompt:cliJar
curl -fsS "$server/actuator/health" | grep -q '"status":"UP"' || fail "gate-server is not running at $server"

# ---------------------------------------------------------------------------------------------
step "1. AI suggests a package that does not exist -> gate-pkg hook denies the install"
fake=gate-demo-fastjson-validator-ai
out=$(printf '{"tool_name":"Bash","tool_input":{"command":"npm install %s"}}' "$fake" \
  | java -jar gate-pkg/build/libs/gate-pkg.jar hook claude)
echo "$out" | jq '.hookSpecificOutput | {permissionDecision, permissionDecisionReason}'
echo "$out" | grep -q '"permissionDecision":"deny"' || fail "the hook did not deny $fake"

# ---------------------------------------------------------------------------------------------
step "2. A pull request reintroduces SQL string concatenation -> gate-review blocks it"
repo=$(mktemp -d)
trap 'rm -rf "$repo"' EXIT
git_() { git -C "$repo" -c user.name=demo -c user.email=demo@example.invalid -c commit.gpgsign=false "$@"; }
git_ init -q
mkdir -p "$repo/src/main/java/demo"
cp gate-lab/src/main/java/io/gate/lab/fixed/ProductSearchController.java "$repo/src/main/java/demo/"
git_ add -A && git_ commit -q -m base && git_ tag base
sed 's/package io.gate.lab.vulnerable;/package io.gate.lab.fixed;/' \
  gate-lab/src/main/java/io/gate/lab/vulnerable/ProductSearchController.java \
  > "$repo/src/main/java/demo/ProductSearchController.java"
git_ commit -q -am "simplify product search"
set +e
java -jar gate-review/build/libs/gate-review.jar review --repo "$repo" --base base \
  --rules "$root/gate-review/semgrep/rules/gate-java.yml"
code=$?
set -e
test "$code" = 2 || fail "gate-review exit code $code (expected 2 = BLOCK)"

# ---------------------------------------------------------------------------------------------
step "3. Authorization test before release -> gate-authz finds IDOR on the vulnerable lab"
pw=${GATE_LAB_DEMO_PASSWORD:-$(grep '^GATE_LAB_DEMO_PASSWORD=' .env 2>/dev/null | cut -d= -f2 || true)}
test -n "$pw" || fail "set GATE_LAB_DEMO_PASSWORD (or put it in .env) so gate-authz can log in to the lab"
export GATE_AUTHZ_PASSWORD_A=$pw GATE_AUTHZ_PASSWORD_B=$pw
set +e
java -jar gate-authz/build/libs/gate-authz.jar scan --config gate-authz/gate-authz.lab.yml
code=$?
set -e
test "$code" = 2 || fail "gate-authz exit code $code (expected 2 = findings)"

# ---------------------------------------------------------------------------------------------
step "4. Prompt injection against the AI order assistant -> gate-prompt output gate stops every attack"
java -jar gate-prompt/build/libs/gate-prompt.jar measure | sed -n '1,9p'  # sed는 끝까지 읽는다(파이프 끊김 방지)

# ---------------------------------------------------------------------------------------------
step "5. One screen for everything -> $server"
curl -fsS "$server/api/summary" | tee /tmp/gate-demo-summary.json \
  | jq -r '.modules[] | "\(.module)\t runs=\(.runs)\t BLOCK=\(.findings.BLOCK) WARN=\(.findings.WARN) PASS=\(.findings.PASS)"'
missing=$(jq -r '[.modules[] | select(.runs < 1) | .module] | join(",")' /tmp/gate-demo-summary.json)
test -z "$missing" || fail "no stored results for: $missing"
printf '\n\033[32mDemo complete. Open %s in a browser.\033[0m\n' "$server"
