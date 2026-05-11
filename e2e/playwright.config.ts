import { defineConfig, devices } from '@playwright/test';
import { defineBddConfig } from 'playwright-bdd';

const baseURL = process.env.E2E_BASE_URL ?? 'https://host.docker.internal:8080';

// playwright-bdd compiles every .feature in features/ into a
// generated .spec.js file under .features-gen/, picks them up via
// `testDir`, and wires the World fixture imported from
// features/support/fixtures.ts. Step definitions live under
// features/steps/ + features/support/hooks.ts. Hooks file is
// imported from import once per worker via the `imports` option.
const bddTestDir = defineBddConfig({
  features: 'features/**/*.feature',
  steps:    [
    'features/steps/**/*.ts',
    'features/support/**/*.ts',
  ],
});

export default defineConfig({
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
    // Trace + video retention is forced ON for every run so the e2e
    // artefact harvest (`make docs-screenshots-from-e2e`) always has
    // a per-step PNG + a full webm to fall back on. The per-step PNG
    // hook in features/support/fixtures.ts gives us granular shots;
    // the video covers everything between steps (federation init,
    // WASM compile, lobby transitions) so a reviewer can scrub the
    // whole scenario timeline.
    trace: 'retain-on-failure',
    screenshot: 'on',
    video: 'on',
    actionTimeout: 15_000,
    navigationTimeout: 30_000,
  },
  projects: [
    {
      // Existing handwritten Playwright specs — unchanged contract.
      name: 'chromium',
      testDir: './tests',
      use: { ...devices['Desktop Chrome'] },
    },
    {
      // BDD project — generated specs from .feature files plus their
      // step definitions. Only enabled when `npx bddgen` has run
      // (or `npm run bdd` / make e2e-bdd) so a stale `.features-gen/`
      // doesn't break the chromium project's discovery.
      name: 'bdd',
      testDir: bddTestDir,
      use: { ...devices['Desktop Chrome'] },
    },
  ],
});
