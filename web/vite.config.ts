import { fileURLToPath } from 'node:url';
import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

// Dev: `npm run dev` proxies /api to the HomeTV server, so the SPA and the API share an origin.
export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  server: {
    proxy: {
      '/api': { target: process.env.HOMETV_API_TARGET ?? 'http://127.0.0.1:8000', changeOrigin: false },
    },
  },
  build: {
    outDir: 'dist',
    assetsDir: 'assets', // the server marks /assets/* immutable (prd.md §12)
    // The only chunk above Vite's 500 kB default is hls.js (~595 kB, ~186 kB gzip), a lazy chunk
    // loaded only for an `hls` decision off iOS. Everything else stays far below this limit.
    chunkSizeWarningLimit: 650,
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['tests/setup.ts'],
    include: ['src/**/*.test.{ts,tsx}', 'tests/**/*.test.{ts,tsx}'],
    exclude: ['tests/e2e/**', 'node_modules/**'],
    css: { modules: { classNameStrategy: 'non-scoped' } },
  },
});
