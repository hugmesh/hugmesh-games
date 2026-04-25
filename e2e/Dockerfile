# Pre-baked Playwright image — Chromium + all OS deps already inside.
# Cross-platform: anyone with Docker can run `make e2e` regardless of host OS.
FROM mcr.microsoft.com/playwright:v1.49.0-jammy

WORKDIR /work

COPY package.json package-lock.json* ./
RUN npm install --no-audit --no-fund

COPY playwright.config.ts ./
COPY tests/ ./tests/

RUN mkdir -p /work/results

CMD ["npx", "playwright", "test", "--reporter=list"]
