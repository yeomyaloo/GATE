// gate-server 조회 API (읽기 전용). 응답에 든 글(근거·제목)은 공격 문자열일 수 있으므로 화면에서는 항상 글자로만 표시한다.

export type Module = 'PKG' | 'REVIEW' | 'AUTHZ' | 'PROMPT';
export type Decision = 'BLOCK' | 'WARN' | 'PASS';
export type Severity = 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW' | 'INFO';

export const MODULES: Module[] = ['PKG', 'REVIEW', 'AUTHZ', 'PROMPT'];
export const DECISIONS: Decision[] = ['BLOCK', 'WARN', 'PASS'];
export const SEVERITIES: Severity[] = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'INFO'];

export const MODULE_LABEL: Record<Module, string> = {
  PKG: '① 패키지 검문소',
  REVIEW: '② AI 코드 리뷰',
  AUTHZ: '③ 권한 자동 테스트',
  PROMPT: '④ 프롬프트 관문',
};

export type Counts = Record<Decision, number>;

export interface Summary {
  modules: { module: Module; runs: number; lastRunId: string | null; lastRunAt: string | null; findings: Counts }[];
  totals: { runs: number; findings: number; byDecision: Counts; bySeverity: Record<Severity, number> };
}

export type Location =
  | { kind: 'package'; ecosystem: string; name: string; version?: string }
  | { kind: 'file'; path: string; line: number }
  | { kind: 'endpoint'; method: string; path: string };

export interface Finding {
  id: string;
  runId: string;
  module: Module;
  ruleId: string;
  title: string;
  severity: Severity;
  decision: Decision;
  location: Location;
  evidence: Record<string, unknown>;
  source: 'RULE' | 'AI' | 'RULE_AND_AI';
  fingerprint: string;
  detectedAt: string;
}

export interface FindingFilter {
  module?: Module;
  decision?: Decision;
  severity?: Severity;
  fingerprint?: string;
  before?: string;
  limit?: number;
}

async function get<T>(path: string): Promise<T> {
  const res = await fetch(path, { headers: { Accept: 'application/json' }, credentials: 'same-origin' });
  if (!res.ok) {
    throw new Error(`${res.status} ${path}`);
  }
  return (await res.json()) as T;
}

export const api = {
  summary: () => get<Summary>('/api/summary'),
  findings: (f: FindingFilter = {}) => {
    const q = new URLSearchParams();
    Object.entries(f).forEach(([k, v]) => {
      if (v !== undefined && v !== '') q.set(k, String(v));
    });
    const qs = q.toString();
    return get<Finding[]>(`/api/findings${qs ? `?${qs}` : ''}`);
  },
  finding: (id: string) => get<Finding>(`/api/findings/${encodeURIComponent(id)}`),
};

/** 위치를 한 줄 글로 (패키지 · 파일:줄 · 엔드포인트). */
export function locationText(l: Location): string {
  switch (l.kind) {
    case 'package':
      return `${l.ecosystem}:${l.name}${l.version ? `@${l.version}` : ''}`;
    case 'file':
      return `${l.path}:${l.line}`;
    case 'endpoint':
      return `${l.method} ${l.path}`;
  }
}
