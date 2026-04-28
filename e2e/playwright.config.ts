import { defineConfig, devices } from '@playwright/test';

const baseURL = process.env.E2E_BASE_URL ?? 'https://host.docker.internal:8080';

export default defineConfig({
  testDir: './tests',
  // Single-worker. Two workers cause flaky failures: per-role login
  // through Casdoor + federation manifest fetches contend on a small
  // dev stack and `waitForURL` after `button.login-btn` click stalls
  // intermittently. Serial execution keeps the matrix reliable;
  // total wall-clock is ~2 min for the full suite.
  fullyParallel: false,
  workers: 1,
  // One retry catches the rare login-page flake without papering
  // over real regressions — a deterministic bug fails twice in a row.
  retries: 1,
  timeout: 180_000,
  reporter: [
    ['list'],
    ['html', { outputFolder: '/work/results/html', open: 'never' }],
  ],
  outputDir: '/work/results/artifacts',
  use: {
    baseURL,
    // The whole point — accept the self-signed cert without rejecting.
    ignoreHTTPSErrors: true,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
    actionTimeout: 15_000,
    navigationTimeout: 30_000,
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
  ],
});
