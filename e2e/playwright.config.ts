import { defineConfig, devices } from '@playwright/test';

const baseURL = process.env.E2E_BASE_URL ?? 'https://host.docker.internal:8080';

export default defineConfig({
  testDir: './tests',
  // Per-spec parallelism — each spec opens its own browser contexts and
  // doesn't share state with the others, so running multiple in parallel
  // keeps the per-role matrix from blowing the wall-clock budget. The
  // WASM bundle is heavy; cap at 2 workers so memory on the e2e
  // container stays sane.
  fullyParallel: true,
  workers: 2,
  retries: 0,
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
