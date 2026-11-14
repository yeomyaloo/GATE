import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// 빌드 결과(dist)는 gate-server가 같은 주소에서 제공한다 (설계안 결정 1).
// 개발 중에는 /api 요청을 로컬 gate-server(127.0.0.1:8080)로 넘긴다.
export default defineConfig({
  plugins: [react()],
  build: {
    outDir: 'dist',
    sourcemap: false,
    // 인라인 스크립트를 만들지 않는다 (CSP script-src 'self')
    modulePreload: { polyfill: false },
    chunkSizeWarningLimit: 1200,
  },
  server: {
    host: '127.0.0.1',
    port: 5173,
    proxy: {
      '/api': 'http://127.0.0.1:8080',
    },
  },
});
