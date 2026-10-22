import { defineConfig, devices } from '@playwright/test';

// Optional smoke test against a running HomeTV server (`HOMETV_E2E_URL`). Not part of `npm test`.
export default defineConfig({
  testDir: 'tests/e2e',
  timeout: 60_000,
  use: {
    baseURL: process.env.HOMETV_E2E_URL ?? 'http://127.0.0.1:8000',
    trace: 'retain-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
});
