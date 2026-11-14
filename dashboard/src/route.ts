import { useEffect, useState } from 'react';

// 해시 주소로 화면을 고른다 (라우터 라이브러리 없이): #/ · #/findings?module=PKG · #/findings/<id>
export type Route =
  | { page: 'overview' }
  | { page: 'findings'; params: URLSearchParams }
  | { page: 'finding'; id: string };

export function parseRoute(hash: string): Route {
  const raw = hash.replace(/^#/, '') || '/';
  const [path, query = ''] = raw.split('?');
  const detail = /^\/findings\/([0-9a-fA-F-]{36})$/.exec(path);
  if (detail) return { page: 'finding', id: detail[1] };
  if (path === '/findings') return { page: 'findings', params: new URLSearchParams(query) };
  return { page: 'overview' };
}

export function useRoute(): Route {
  const [route, setRoute] = useState(() => parseRoute(window.location.hash));
  useEffect(() => {
    const on = () => setRoute(parseRoute(window.location.hash));
    window.addEventListener('hashchange', on);
    return () => window.removeEventListener('hashchange', on);
  }, []);
  return route;
}

export function go(hash: string) {
  window.location.hash = hash;
}
