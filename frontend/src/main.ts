// Federation runtime must initialise BEFORE Angular bootstraps so the
// remote-import URLs resolve correctly. Pull the manifest from the BFF
// (DB-backed via /api/federation/manifest), shape it into the form
// native-federation expects, then call initFederation().

import { bootstrapApplication } from '@angular/platform-browser';
import { initFederation } from '@angular-architects/native-federation';

import { AppComponent } from './app/app.component';
import { appConfig } from './app/app.config';
import { environment } from './environments/environment';

interface BffManifest {
  generatedAt?: string;
  remotes: Array<{
    slug: string;
    remoteEntryUrl: string;
    kind: string;
  }>;
}

/**
 * Fetch the BFF-shaped manifest, transform to native-federation's
 * `{<name>: <remoteEntryUrl>}` map, and stash both shapes on `window`
 * so:
 *   • initFederation() can consume the map,
 *   • the Angular FederationService can read the BFF shape without a
 *     duplicate HTTP roundtrip.
 *
 * Falls back to an empty federation map on network failure — the host
 * still boots, just without any remotes available.
 */
async function bootstrap(): Promise<void> {
  const url = `${environment.apiBase}/api/federation/manifest`;
  let bffManifest: BffManifest | null = null;
  try {
    const resp = await fetch(url, { credentials: 'omit' });
    if (resp.ok) {
      bffManifest = await resp.json();
    } else {
      console.warn('[bootstrap] federation manifest preload returned', resp.status);
    }
  } catch (err) {
    console.warn('[bootstrap] federation manifest preload failed; continuing with no remotes', err);
  }

  if (bffManifest) {
    (globalThis as any).__SNAPDRAGON_FEDERATION_MANIFEST__ = bffManifest;

    // Native-federation expects `{name: remoteEntryUrl}`. We only feed
    // it the rows that are actually loadable JS bundles (skip
    // `host`, `fragment`, etc.).
    const fedMap: Record<string, string> = {};
    for (const r of bffManifest.remotes ?? []) {
      if (r.kind === 'remote-game' || r.kind === 'remote-shell') {
        fedMap[r.slug] = r.remoteEntryUrl;
      }
    }

    try {
      await initFederation(fedMap);
      console.log(`[bootstrap] initFederation() ok — ${Object.keys(fedMap).length} remotes wired`);
    } catch (err) {
      console.warn('[bootstrap] initFederation failed; remotes unavailable', err);
    }
  }

  await bootstrapApplication(AppComponent, appConfig);
}

bootstrap().catch(err => console.error(err));
