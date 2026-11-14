import { useEffect, useState } from 'react';
import { Alert, Anchor, Badge, Card, Code, Group, Loader, Stack, Table, Text, Title } from '@mantine/core';
import { api, locationText, MODULE_LABEL, type Finding } from '../api';
import { go } from '../route';
import { DecisionBadge, SeverityBadge, time } from '../ui';

const SOURCE_LABEL = { RULE: '규칙', AI: 'AI 단독 (차단 불가)', RULE_AND_AI: '규칙 + AI 일치' };

/**
 * 결과 상세. 근거에는 공격 문자열(프롬프트 인젝션 문장, 스크립트 조각, 취약 코드)이 그대로 들어 있다.
 * 모든 값은 React의 기본 이스케이프로 글자로만 표시한다 (HTML로 넣지 않는다).
 */
export function FindingDetail({ id }: { id: string }) {
  const [f, setF] = useState<Finding | null>(null);
  const [history, setHistory] = useState<Finding[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    setF(null);
    api
      .finding(id)
      .then((x) => {
        setF(x);
        return api.findings({ fingerprint: x.fingerprint, limit: 20 });
      })
      .then(setHistory)
      .catch((e: Error) => setError(e.message));
  }, [id]);

  if (error) return <Alert color="red">{error}</Alert>;
  if (!f) return <Loader />;

  return (
    <Stack>
      <Anchor onClick={() => go('/findings')}>← 목록으로</Anchor>
      <Group>
        <DecisionBadge value={f.decision} />
        <SeverityBadge value={f.severity} />
        <Badge variant="outline">{MODULE_LABEL[f.module]}</Badge>
        <Badge variant="outline" color="gray">
          {SOURCE_LABEL[f.source]}
        </Badge>
      </Group>
      <Title order={3} data-testid="finding-title">
        {f.title}
      </Title>
      <Card withBorder>
        <Table variant="vertical" layout="fixed">
          <Table.Tbody>
            <Table.Tr>
              <Table.Th w={140}>규칙</Table.Th>
              <Table.Td>
                <Code>{f.ruleId}</Code>
              </Table.Td>
            </Table.Tr>
            <Table.Tr>
              <Table.Th>위치</Table.Th>
              <Table.Td>
                <Code>{locationText(f.location)}</Code>
              </Table.Td>
            </Table.Tr>
            <Table.Tr>
              <Table.Th>발견 시각</Table.Th>
              <Table.Td>{time(f.detectedAt)}</Table.Td>
            </Table.Tr>
            <Table.Tr>
              <Table.Th>지문</Table.Th>
              <Table.Td>
                <Text ff="monospace" size="xs" style={{ wordBreak: 'break-all' }}>
                  {f.fingerprint}
                </Text>
              </Table.Td>
            </Table.Tr>
          </Table.Tbody>
        </Table>
      </Card>

      <Card withBorder data-testid="evidence">
        <Title order={5} mb="sm">
          근거
        </Title>
        <Table variant="vertical" layout="fixed">
          <Table.Tbody>
            {Object.entries(f.evidence).map(([k, v]) => (
              <Table.Tr key={k}>
                <Table.Th w={140}>{k}</Table.Th>
                <Table.Td>
                  {typeof v === 'string' || typeof v === 'number' || typeof v === 'boolean' ? (
                    <Code block style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>
                      {String(v)}
                    </Code>
                  ) : (
                    <Code block style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>
                      {JSON.stringify(v, null, 2)}
                    </Code>
                  )}
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      </Card>

      <Card withBorder>
        <Title order={5} mb="sm">
          같은 결과의 기록 ({history.length})
        </Title>
        <Table>
          <Table.Tbody>
            {history.map((h) => (
              <Table.Tr key={h.id}>
                <Table.Td>{time(h.detectedAt)}</Table.Td>
                <Table.Td>
                  <DecisionBadge value={h.decision} />
                </Table.Td>
                <Table.Td>
                  {h.id === f.id ? (
                    <Text size="sm" c="dimmed">
                      지금 보는 결과
                    </Text>
                  ) : (
                    <Anchor size="sm" onClick={() => go(`/findings/${h.id}`)}>
                      열기
                    </Anchor>
                  )}
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      </Card>
    </Stack>
  );
}
