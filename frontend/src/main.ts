import { bootstrapApplication } from '@angular/platform-browser';

import { AppComponent } from './app/app.component';
import { appConfig } from './app/app.config';
import { environment } from './environments/environment';

/**
 * Bootstrap order:
 *   1. Fetch the DB-backed federation manifest from the BFF (a single
 *      HTTP call, ~kB of JSON). The manifest tells the shell which
 *      remote modules exist (login, admin, per-game, wasm-fragment),
 *      what kind they are, and where to load them.
 *   2. Cache the result on `window.__SNAPDRAGON_FEDERATION_MANIFEST__`
 *      so the host's federation runtime — once we wire in
 *      native-federation's `initFederation(...)` — has it ready and
 *      doesn't refetch.
 *   3. Bootstrap the Angular shell. Routes that depend on remote
 *      modules read the manifest via `FederationService` (which uses
 *      the cached observable, not a fresh HTTP call).
 *
 * Why pre-bootstrap the fetch instead of eager-load inside `main.ts`'s
 * Angular boot: native-federation expects the manifest to be available
 * BEFORE component bootstrap so it can resolve remote import URLs.
 */
async function preloadFederationManifest(): Promise<void> {
  const url = `${environment.apiBase}/api/federation/manifest`;
  try {
    const resp = await fetch(url, { credentials: 'omit' });
    if (resp.ok) {
      const manifest = await resp.json();
      (globalThis as any).__SNAPDRAGON_FEDERATION_MANIFEST__ = manifest;
      console.log(`[bootstrap] federation manifest preloaded — ${manifest.remotes?.length ?? 0} remotes`);
    } else {
      console.warn('[bootstrap] federation manifest preload returned', resp.status);
    }
  } catch (err) {
    console.warn('[bootstrap] federation manifest preload failed; falling back to lazy fetch', err);
  }
}

(async () => {
  await preloadFederationManifest();
  await bootstrapApplication(AppComponent, appConfig).catch(err => console.error(err));
})();
