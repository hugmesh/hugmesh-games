// Federation runtime + bootstrap entry. NOTHING in this file may
// statically import @angular/* (or any other dep that goes through
// the import map) — those bare specifiers can only be resolved AFTER
// initFederation() writes the map. The Angular bootstrap is dynamic-
// imported below once initFederation completes; that's native-
// federation's documented "break-the-chicken-and-egg" pattern.

import { initFederation, loadRemoteModule } from '@angular-architects/native-federation';
import { environment } from './environments/environment';

// Stash the federation runtime's `loadRemoteModule` on the global so
// services that need it can pick it up WITHOUT statically importing
// `@angular-architects/native-federation` themselves. Why this matters:
// karma's webpack-based test bundler can't parse the package's ESM
// `index.js` ("Module parse failed" — see RemoteLoaderService comment),
// and a direct dynamic `import('@angular-architects/native-federation')`
// at the call site fails at runtime because es-module-shims tries to
// resolve the bare specifier against the import map (which only holds
// remote slugs, not the federation package). Pre-stashing on the global
// from main.ts (whose static import IS bundled by esbuild) is the only
// path that keeps both the karma graph and the production runtime happy.
(globalThis as any).__HUGMESH_LOAD_REMOTE_MODULE__ = loadRemoteModule;

interface BffManifest {
  generatedAt?: string;
  remotes: Array<{ slug: string; remoteEntryUrl: string; kind: string }>;
}

(async () => {
  // Pull the BFF-shaped manifest first so we can shape it for
  // initFederation. Cache the BFF shape on window for FederationService.
  const fedMap: Record<string, string> = {};
  try {
    const resp = await fetch(`${environment.apiBase}/api/federation/manifest`,
                             { credentials: 'omit' });
    if (resp.ok) {
      const bffManifest: BffManifest = await resp.json();
      (globalThis as any).__HUGMESH_FEDERATION_MANIFEST__ = bffManifest;
      for (const r of bffManifest.remotes ?? []) {
        if (r.kind === 'remote-game' || r.kind === 'remote-shell') {
          fedMap[r.slug] = r.remoteEntryUrl;
        }
      }
    }
  } catch (err) {
    console.warn('[bootstrap] manifest preload failed; remotes unavailable', err);
  }

  try {
    await initFederation(fedMap);
    console.log(`[bootstrap] initFederation() ok — ${Object.keys(fedMap).length} remotes wired`);
  } catch (err) {
    console.warn('[bootstrap] initFederation failed; continuing without remotes', err);
  }

  // Dynamic import of bootstrap.ts AFTER the import map exists.
  // Webpack/esbuild treat `import('./bootstrap')` as a code-split
  // point; the resulting chunk is loaded only here, by which time
  // the federation runtime has hydrated bare-specifier resolution.
  await import('./bootstrap');
})();
