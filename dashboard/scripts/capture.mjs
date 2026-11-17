// 대시보드 화면 테스트 + 자동 캡처 (마지막 단계 설계안 5장, Playwright).
//   node scripts/capture.mjs [--base URL] [--out DIR] [--probe]
//     --base   대시보드 주소 (기본 http://127.0.0.1:8080: gate-server 또는 scripts/mock-api.mjs)
//     --out    캡처 저장 폴더 (기본 ../docs/images)
//     --probe  공격 문자열이 든 결과 하나를 gate-server에 저장한 뒤 화면이 글자로만 보여 주는지 확인 (CI의 실제 서버용)
//   PW_CHANNEL=msedge 처럼 지정하면 설치된 브라우저를 쓴다 (브라우저를 따로 내려받지 않고 로컬에서 캡처할 때)
//
// 실패 조건: 콘솔 오류·페이지 오류, 서버 연결 오류 화면, 모듈 카드 누락, 빈 결과 목록,
//           근거에 든 HTML이 실제 요소가 되거나 스크립트가 실행됨
import { chromium } from 'playwright';
import { mkdirSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const arg = (name, fallback) => {
  const i = process.argv.indexOf(`--${name}`);
  return i > 0 && process.argv[i + 1] && !process.argv[i + 1].startsWith('--') ? process.argv[i + 1] : fallback;
};
const base = arg('base', 'http://127.0.0.1:8080').replace(/\/+$/, '');
const out = resolve(arg('out', join(here, '..', '..', 'docs', 'images')));
const probe = process.argv.includes('--probe');
if (!/^http:\/\/(127\.0\.0\.1|localhost)(:\d+)?$/.test(base)) {
  throw new Error(`only a local dashboard can be captured (got ${base})`);
}
mkdirSync(out, { recursive: true });

// 화면에 글자로만 보여야 하는 공격 문자열. 스크립트가 실행되면 window.__gateXss가 생긴다
const XSS = '<img src=x onerror="window.__gateXss=1"><script>window.__gateXss=2</script>';

async function storeProbe() {
  const now = new Date().toISOString();
  const res = await fetch(`${base}/api/scan-runs`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      module: 'PROMPT', trigger: 'CLI', target: 'dashboard escaping probe', startedAt: now, finishedAt: now,
      findings: [{
        ruleId: 'PROMPT-HTML-REMOVED', title: `Escaping probe ${XSS}`, severity: 'LOW', decision: 'WARN',
        location: { kind: 'endpoint', method: 'POST', path: '/api/assistant/review-summary' },
        evidence: { probe: XSS, note: 'stored by dashboard/scripts/capture.mjs to check that evidence is shown as text' },
        source: 'RULE', detectedAt: now,
      }],
    }),
  });
  if (!res.ok) throw new Error(`probe was not stored: HTTP ${res.status}`);
}

const problems = [];
const check = (ok, message) => { if (!ok) problems.push(message); };

if (probe) await storeProbe();

const channel = process.env.PW_CHANNEL || undefined;
const browser = await chromium.launch({ channel });
const page = await browser.newPage({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 1 });
page.on('console', (m) => { if (m.type() === 'error') problems.push(`console: ${m.text()}`); });
page.on('pageerror', (e) => problems.push(`page error: ${e.message}`));
page.on('response', (r) => {
  if (r.url().startsWith(base) && r.status() >= 400) problems.push(`HTTP ${r.status()} ${r.url()}`);
});

// 1. 개요
await page.goto(`${base}/#/`);
await page.getByText('검사 실행').first().waitFor({ timeout: 15000 });
await page.waitForTimeout(800); // 차트 애니메이션
const overview = await page.locator('main').innerText();
check(!overview.includes('연결하지 못했습니다'), 'overview shows a connection error');
for (const label of ['① 패키지 검문소', '② AI 코드 리뷰', '③ 권한 자동 테스트', '④ 프롬프트 관문']) {
  check(overview.includes(label), `overview is missing the module card ${label}`);
}
await page.screenshot({ path: join(out, 'dashboard-overview.png'), fullPage: true });

// 2. 결과 목록
await page.goto(`${base}/#/findings`);
await page.locator('table tbody tr').first().waitFor({ timeout: 15000 });
check(await page.locator('table tbody tr').count() > 0, 'the findings list is empty');
await page.screenshot({ path: join(out, 'dashboard-findings.png'), fullPage: true });

// 3. 결과 상세: 근거에 HTML이 든 결과를 골라 글자로만 보이는지 확인
const list = await (await fetch(`${base}/api/findings?limit=200`)).json();
const target = list.find((f) => (f.title + JSON.stringify(f.evidence)).includes('<img'));
check(target, 'no finding with an HTML attack string to check (run with --probe against gate-server)');
if (target) {
  await page.goto(`${base}/#/findings/${target.id}`);
  await page.getByText(target.ruleId).first().waitFor({ timeout: 15000 });
  const text = await page.locator('main').innerText();
  check(text.includes('<img'), 'the attack string is not visible as text on the detail page');
  check(await page.locator('main img').count() === 0, 'evidence HTML became a real <img> element');
  check(await page.locator('main script').count() === 0, 'evidence HTML became a real <script> element');
  check(await page.evaluate(() => window.__gateXss === undefined), 'a script from the evidence ran');
  await page.screenshot({ path: join(out, 'dashboard-detail.png'), fullPage: true });
}

await browser.close();
if (problems.length) {
  console.error(`dashboard check FAILED:\n- ${problems.join('\n- ')}`);
  process.exit(1);
}
console.log(`dashboard check passed; screenshots in ${out}`);
