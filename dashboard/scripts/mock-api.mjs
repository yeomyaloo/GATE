// 표본 데이터로 gate-server 조회 API를 흉내 내는 서버 (화면 확인·자동 캡처용, 의존성 없음).
// 실제 서버와 같은 경로·모양으로 응답하고, 같은 보안 헤더를 붙인다. 127.0.0.1에만 연다.
//   node scripts/mock-api.mjs [port]      (기본 8080; vite 개발 서버가 /api를 여기로 넘긴다)
import { createServer } from 'node:http';
import { readFileSync, existsSync } from 'node:fs';
import { dirname, join, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const data = JSON.parse(readFileSync(join(here, '..', 'fixtures', 'sample.json'), 'utf8'));
const dist = join(here, '..', 'dist');
const port = Number(process.argv[2] ?? 8080);

const CSP = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; "
  + "font-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'";
const TYPES = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml' };

function summary() {
  const modules = ['PKG', 'REVIEW', 'AUTHZ', 'PROMPT'].map((m) => {
    const runs = data.runs.filter((r) => r.module === m);
    const last = runs.sort((a, b) => b.startedAt.localeCompare(a.startedAt))[0];
    const counts = { BLOCK: 0, WARN: 0, PASS: 0 };
    data.findings.filter((f) => f.module === m).forEach((f) => counts[f.decision]++);
    return { module: m, runs: runs.length, lastRunId: last?.id ?? null, lastRunAt: last?.startedAt ?? null, findings: counts };
  });
  const byDecision = { BLOCK: 0, WARN: 0, PASS: 0 };
  const bySeverity = { CRITICAL: 0, HIGH: 0, MEDIUM: 0, LOW: 0, INFO: 0 };
  data.findings.forEach((f) => { byDecision[f.decision]++; bySeverity[f.severity]++; });
  return { modules, totals: { runs: data.runs.length, findings: data.findings.length, byDecision, bySeverity } };
}

function findings(q) {
  let out = [...data.findings].sort((a, b) => b.detectedAt.localeCompare(a.detectedAt));
  for (const k of ['module', 'decision', 'severity', 'fingerprint']) {
    const v = q.get(k);
    if (v) out = out.filter((f) => f[k] === v.toUpperCase() || f[k] === v);
  }
  const before = q.get('before');
  if (before) out = out.filter((f) => f.detectedAt < before);
  return out.slice(0, Number(q.get('limit') ?? 50));
}

createServer((req, res) => {
  const url = new URL(req.url, 'http://127.0.0.1');
  res.setHeader('Content-Security-Policy', CSP);
  res.setHeader('X-Content-Type-Options', 'nosniff');
  const json = (status, body) => {
    res.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
    res.end(JSON.stringify(body));
  };
  if (url.pathname === '/api/summary') return json(200, summary());
  if (url.pathname === '/api/findings') return json(200, findings(url.searchParams));
  const one = /^\/api\/findings\/([0-9a-f-]{36})$/.exec(url.pathname);
  if (one) {
    const f = data.findings.find((x) => x.id === one[1]);
    return f ? json(200, f) : json(404, { error: 'not found' });
  }
  // 빌드된 화면 (gate-server와 같은 방식으로 같은 주소에서 제공)
  const file = normalize(join(dist, url.pathname === '/' ? 'index.html' : url.pathname));
  if (file.startsWith(dist) && existsSync(file)) {
    res.writeHead(200, { 'Content-Type': TYPES[file.slice(file.lastIndexOf('.'))] ?? 'application/octet-stream' });
    return res.end(readFileSync(file));
  }
  json(404, { error: 'not found' });
}).listen(port, '127.0.0.1', () => console.log(`mock gate-server on http://127.0.0.1:${port}`));
