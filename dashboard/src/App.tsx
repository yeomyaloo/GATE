import { Anchor, AppShell, Group, NavLink, Text, Title } from '@mantine/core';
import { FindingDetail } from './pages/FindingDetail';
import { Findings } from './pages/Findings';
import { Overview } from './pages/Overview';
import { go, useRoute } from './route';

export function App() {
  const route = useRoute();
  return (
    <AppShell header={{ height: 56 }} navbar={{ width: 200, breakpoint: 'md', collapsed: { mobile: true } }} padding="md">
      <AppShell.Header>
        <Group h="100%" px="md" justify="space-between">
          <Group gap="lg">
            <Title order={3}>GATE</Title>
            {/* 좁은 화면에서는 왼쪽 메뉴 대신 머리글 링크 */}
            <Group gap="sm" hiddenFrom="md">
              <Anchor size="sm" onClick={() => go('/')}>개요</Anchor>
              <Anchor size="sm" onClick={() => go('/findings')}>결과 목록</Anchor>
            </Group>
          </Group>
          <Text size="sm" c="dimmed" visibleFrom="sm">
            Guard for AI-assisted Toolchain &amp; Endpoints · 읽기 전용
          </Text>
        </Group>
      </AppShell.Header>
      <AppShell.Navbar p="xs">
        <NavLink label="개요" active={route.page === 'overview'} onClick={() => go('/')} />
        <NavLink label="결과 목록" active={route.page !== 'overview'} onClick={() => go('/findings')} />
      </AppShell.Navbar>
      <AppShell.Main>
        {route.page === 'overview' && <Overview />}
        {route.page === 'findings' && <Findings params={route.params} />}
        {route.page === 'finding' && <FindingDetail id={route.id} />}
      </AppShell.Main>
    </AppShell>
  );
}
