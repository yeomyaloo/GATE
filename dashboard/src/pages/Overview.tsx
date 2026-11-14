import { useEffect, useState } from 'react';
import { BarChart, DonutChart } from '@mantine/charts';
import { Alert, Anchor, Card, Group, Loader, SimpleGrid, Stack, Table, Text, Title } from '@mantine/core';
import { api, DECISIONS, locationText, MODULE_LABEL, SEVERITIES, type Finding, type Summary } from '../api';
import { go } from '../route';
import { DecisionBadge, DECISION_COLOR, SEVERITY_COLOR, SeverityBadge, time } from '../ui';

/** 개요: KPI 5개, 모듈 카드 4개, 모듈별 판정 막대, 심각도 도넛, 최근 차단 10개. */
export function Overview() {
  const [summary, setSummary] = useState<Summary | null>(null);
  const [blocks, setBlocks] = useState<Finding[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    Promise.all([api.summary(), api.findings({ decision: 'BLOCK', limit: 10 })])
      .then(([s, b]) => {
        setSummary(s);
        setBlocks(b);
      })
      .catch((e: Error) => setError(e.message));
  }, []);

  if (error) return <Alert color="red" title="gate-server에 연결하지 못했습니다">{error}</Alert>;
  if (!summary) return <Loader />;

  const t = summary.totals;
  const highRisk = (t.bySeverity.CRITICAL ?? 0) + (t.bySeverity.HIGH ?? 0);
  const kpis = [
    { label: '검사 실행', value: t.runs, hint: '모든 모듈' },
    { label: '발견', value: t.findings, hint: 'BLOCK + WARN + PASS' },
    { label: '차단 (BLOCK)', value: t.byDecision.BLOCK, hint: '규칙 근거가 있는 차단', color: 'red' },
    { label: '경고 (WARN)', value: t.byDecision.WARN, hint: 'AI 단독 판단 포함', color: 'yellow' },
    { label: '치명·높음', value: highRisk, hint: 'CRITICAL + HIGH', color: 'grape' },
  ];
  const byModule = summary.modules.map((m) => ({ module: m.module, ...m.findings }));
  const bySeverity = SEVERITIES.filter((s) => (t.bySeverity[s] ?? 0) > 0).map((s) => ({
    name: s,
    value: t.bySeverity[s],
    color: `${SEVERITY_COLOR[s]}.6`,
  }));

  return (
    <Stack gap="lg">
      <SimpleGrid cols={{ base: 2, md: 5 }} data-testid="kpis">
        {kpis.map((k) => (
          <Card key={k.label} withBorder padding="md">
            <Text size="sm" c="dimmed">
              {k.label}
            </Text>
            <Text fz={32} fw={700} c={k.color}>
              {k.value.toLocaleString()}
            </Text>
            <Text size="xs" c="dimmed">
              {k.hint}
            </Text>
          </Card>
        ))}
      </SimpleGrid>

      <SimpleGrid cols={{ base: 1, sm: 2, lg: 4 }}>
        {summary.modules.map((m) => (
          <Card key={m.module} withBorder padding="md">
            <Group justify="space-between">
              <Title order={4}>{MODULE_LABEL[m.module]}</Title>
              <Anchor size="sm" onClick={() => go(`/findings?module=${m.module}`)}>
                결과 보기
              </Anchor>
            </Group>
            <Text size="sm" c="dimmed">
              실행 {m.runs}회 · 마지막 {time(m.lastRunAt)}
            </Text>
            <Group gap="xs" mt="sm">
              {DECISIONS.map((d) => (
                <Text key={d} size="sm">
                  <Text span c={DECISION_COLOR[d]} fw={700}>
                    {d}
                  </Text>{' '}
                  {m.findings[d]}
                </Text>
              ))}
            </Group>
          </Card>
        ))}
      </SimpleGrid>

      <SimpleGrid cols={{ base: 1, md: 2 }}>
        <Card withBorder padding="md">
          <Title order={5} mb="sm">
            모듈별 판정
          </Title>
          <BarChart
            h={260}
            data={byModule}
            dataKey="module"
            type="stacked"
            series={DECISIONS.map((d) => ({ name: d, color: `${DECISION_COLOR[d]}.6` }))}
            withLegend
          />
        </Card>
        <Card withBorder padding="md">
          <Title order={5} mb="sm">
            심각도 분포
          </Title>
          {bySeverity.length === 0 ? (
            <Text c="dimmed">아직 결과가 없습니다.</Text>
          ) : (
            <Group justify="center">
              <DonutChart data={bySeverity} size={200} thickness={28} withLabelsLine withLabels />
            </Group>
          )}
        </Card>
      </SimpleGrid>

      <Card withBorder padding="md">
        <Title order={5} mb="sm">
          최근 차단
        </Title>
        <Table.ScrollContainer minWidth={960}>
        <Table highlightOnHover style={{ whiteSpace: 'nowrap' }}>
          <Table.Thead>
            <Table.Tr>
              <Table.Th>시각</Table.Th>
              <Table.Th>모듈</Table.Th>
              <Table.Th>심각도</Table.Th>
              <Table.Th>규칙</Table.Th>
              <Table.Th>위치</Table.Th>
              <Table.Th>제목</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {blocks.map((f) => (
              <Table.Tr key={f.id} style={{ cursor: 'pointer' }} onClick={() => go(`/findings/${f.id}`)}>
                <Table.Td>{time(f.detectedAt)}</Table.Td>
                <Table.Td>{f.module}</Table.Td>
                <Table.Td>
                  <SeverityBadge value={f.severity} />
                </Table.Td>
                <Table.Td>
                  <Text ff="monospace" size="sm">
                    {f.ruleId}
                  </Text>
                </Table.Td>
                <Table.Td>
                  <Text ff="monospace" size="sm">
                    {locationText(f.location)}
                  </Text>
                </Table.Td>
                <Table.Td>
                  <DecisionBadge value={f.decision} /> {f.title}
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
        </Table.ScrollContainer>
      </Card>
    </Stack>
  );
}
