import { Badge } from '@mantine/core';
import type { Decision, Severity } from './api';

export const DECISION_COLOR: Record<Decision, string> = { BLOCK: 'red', WARN: 'yellow', PASS: 'teal' };
export const SEVERITY_COLOR: Record<Severity, string> = {
  CRITICAL: 'grape',
  HIGH: 'red',
  MEDIUM: 'orange',
  LOW: 'blue',
  INFO: 'gray',
};

export function DecisionBadge({ value }: { value: Decision }) {
  return (
    <Badge color={DECISION_COLOR[value]} variant="filled" radius="sm" style={{ minWidth: "max-content", overflow: "visible" }}>
      {value}
    </Badge>
  );
}

export function SeverityBadge({ value }: { value: Severity }) {
  return (
    <Badge color={SEVERITY_COLOR[value]} variant="light" radius="sm" style={{ minWidth: "max-content", overflow: "visible" }}>
      {value}
    </Badge>
  );
}

export function time(iso: string | null): string {
  if (!iso) return '-';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}
