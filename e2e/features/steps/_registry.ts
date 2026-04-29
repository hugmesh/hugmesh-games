// Shared per-scenario state. playwright-bdd recreates the test
// instance for each scenario so we reset these maps in the After()
// hook in features/support/hooks.ts. Keys are usernames so steps can
// reference a player by name without threading a token through.

import { BrowserContext, Page } from '@playwright/test';

export const pageRegistry  = new Map<string, Page>();
export const ctxRegistry   = new Map<string, BrowserContext>();
export const sessionByName = new Map<string, { id: string; gameSlug: string; }>();

/** Last HTTP status code captured by an API-style step. Cleared on
 *  scenario teardown. Module-global instead of test-info attached so
 *  the assertion step doesn't need to thread the World through. */
export const responseState = { lastStatus: 0 };

export function resetRegistry(): void {
  pageRegistry.clear();
  ctxRegistry.clear();
  sessionByName.clear();
  responseState.lastStatus = 0;
}
