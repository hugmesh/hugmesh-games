# Pre-baked Playwright image — Chromium + all OS deps already inside.
# Cross-platform: anyone with Docker can run `make e2e` regardless of host OS.
FROM mcr.microsoft.com/playwright:v1.49.0-jammy

WORKDIR /work

COPY package.json package-lock.json* ./
RUN npm install --no-audit --no-fund

COPY playwright.config.ts ./
COPY tests/ ./tests/
COPY features/ ./features/

# Pre-generate the BDD .spec.js files at build time so `playwright
# test` (default CMD) discovers both projects without an extra step.
# bddgen exits 0 with a no-op when there are no .feature changes, so
# this also runs idempotently when Compose rebuilds the image.
RUN npx bddgen

RUN mkdir -p /work/results

# Default: run the chromium project only (existing 21-test contract).
# Override via `docker compose run --rm e2e npx playwright test --project=bdd`
# or `make e2e-bdd` to run the BDD project.
CMD ["npx", "playwright", "test", "--reporter=list", "--project=chromium"]
