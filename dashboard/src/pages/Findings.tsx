import { useEffect, useState } from 'react';
import { Alert, Button, Card, Group, Loader, Select, Stack, Table, Text } from '@mantine/core';
import {
  api,
  DECISIONS,
  locationText,
  MODULES,
  SEVERITIES,
  type Decision,
  type Finding,
  type Module,
  type Severity,
} from '../api';
import { go } from '../route';
import { DecisionBadge, SeverityBadge, time } from '../ui';

const PAGE = 50;

/** 결과 목록: 모듈·판정·심각도 필터, 시각 기준 "더 보기". 필터는 주소(#/findings?...)에 남는다. */
export function Findings({ params }: { params: URLSearchParams }) {
  const module = (params.get('module') ?? undefined) as Module | undefined;
  const decision = (params.get('decision') ?? undefined) as Decision | undefined;
  const severity = (params.get('severity') ?? undefined) as Severity | undefined;
  const [rows, setRows] = useState<Finding[] | null>(null);
  const [more, setMore] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    setRows(null);
    api
      .findings({ module, decision, severity, limit: PAGE })
      .then((r) => {
        setRows(r);
        setMore(r.length === PAGE);
      })
      .catch((e: Error) => setError(e.message));
  }, [module, decision, severity]);

  const setFilter = (key: string, value: string | null) => {
    const next = new URLSearchParams(params);
    if (value) next.set(key, value);
    else next.delete(key);
    go(`/findings?${next.toString()}`);
  };

  const loadMore = () => {
    if (!rows || rows.length === 0) return;
    api
      .findings({ module, decision, severity, limit: PAGE, before: rows[rows.length - 1].detectedAt })
      .then((r) => {
        setRows([...rows, ...r]);
        setMore(r.length === PAGE);
      })
      .catch((e: Error) => setError(e.message));
  };

  return (
    <Stack>
      <Group>
        <Select label="모듈" placeholder="전체" clearable data={MODULES} value={module ?? null}
          onChange={(v) => setFilter('module', v)} />
        <Select label="판정" placeholder="전체" clearable data={DECISIONS} value={decision ?? null}
          onChange={(v) => setFilter('decision', v)} />
        <Select label="심각도" placeholder="전체" clearable data={SEVERITIES} value={severity ?? null}
          onChange={(v) => setFilter('severity', v)} />
      </Group>
      {error && <Alert color="red">{error}</Alert>}
      {!rows ? (
        <Loader />
      ) : (
        <Card withBorder padding={0}>
          <Table.ScrollContainer minWidth={1100}>
          <Table highlightOnHover striped data-testid="findings-table" style={{ whiteSpace: 'nowrap' }}>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>시각</Table.Th>
                <Table.Th>모듈</Table.Th>
                <Table.Th>판정</Table.Th>
                <Table.Th>심각도</Table.Th>
                <Table.Th>규칙</Table.Th>
                <Table.Th>위치</Table.Th>
                <Table.Th>제목</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {rows.map((f) => (
                <Table.Tr key={f.id} style={{ cursor: 'pointer' }} onClick={() => go(`/findings/${f.id}`)}>
                  <Table.Td>{time(f.detectedAt)}</Table.Td>
                  <Table.Td>{f.module}</Table.Td>
                  <Table.Td>
                    <DecisionBadge value={f.decision} />
                  </Table.Td>
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
                  <Table.Td>{f.title}</Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
          </Table.ScrollContainer>
          {rows.length === 0 && (
            <Text p="md" c="dimmed">
              조건에 맞는 결과가 없습니다.
            </Text>
          )}
        </Card>
      )}
      {more && (
        <Button variant="light" onClick={loadMore}>
          더 보기
        </Button>
      )}
    </Stack>
  );
}
