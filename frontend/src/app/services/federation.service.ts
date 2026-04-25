import { inject, Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, of, shareReplay } from 'rxjs';
import { catchError, map, tap } from 'rxjs/operators';

import { environment } from '../../environments/environment';

/**
 * One row of the runtime federation manifest, as returned by the BFF's
 * `GET /api/federation/manifest`. Mirrors `FederationManifestDto.FederationRemoteDto`
 * on the server side — keep the shape in sync.
 */
export interface FederationRemote {
  slug: string;
  displayName: string;
  /** `'host' | 'remote-shell' | 'remote-game' | 'fragment'` */
  kind: 'host' | 'remote-shell' | 'remote-game' | 'fragment' | string;
  remoteEntryUrl: string;
  exposedModule: string | null;
  gameSlug: string | null;
  version: string;
  /** Free-form JSON string. Parse on demand if you need the flags inside. */
  metadata: string | null;
}

export interface FederationManifest {
  generatedAt: string;
  remotes: FederationRemote[];
}

/**
 * Fetches the DB-backed federation manifest exactly once per page load
 * and caches the result. The Angular host's bootstrap calls this before
 * `bootstrapApplication` and feeds the result into native-federation's
 * `initFederation(...)`. Game-card click handlers also call into this
 * to resolve `gameSlug → remote-game module URL`.
 *
 * <p>Public on purpose: the host needs the `login` remote URL before
 * any user authenticates, so the BFF endpoint stays unauthenticated.
 */
@Injectable({ providedIn: 'root' })
export class FederationService {
  private readonly http = inject(HttpClient);
  private readonly base = environment.apiBase;

  /**
   * Cached manifest stream. If `main.ts` preloaded the manifest into
   * `window.__SNAPDRAGON_FEDERATION_MANIFEST__`, use that — saves a
   * duplicate HTTP roundtrip on first paint. Otherwise lazy-fetch.
   */
  private readonly manifest$: Observable<FederationManifest> = (() => {
    const preloaded = (globalThis as any).__SNAPDRAGON_FEDERATION_MANIFEST__;
    if (preloaded && Array.isArray(preloaded.remotes)) {
      return of<FederationManifest>(preloaded).pipe(shareReplay(1));
    }
    return this.http
      .get<FederationManifest>(`${this.base}/api/federation/manifest`)
      .pipe(
        tap(m => console.log(`[Federation] manifest with ${m.remotes.length} remotes`)),
        catchError(err => {
          console.warn('[Federation] manifest fetch failed; falling back to empty', err);
          return of<FederationManifest>({ generatedAt: new Date().toISOString(), remotes: [] });
        }),
        shareReplay(1),
      );
  })();

  /** Fire-and-cache: the manifest is fetched on first subscription. */
  load(): Observable<FederationManifest> {
    return this.manifest$;
  }

  /**
   * Resolve a game slug to its remote-game module entry. Returns
   * `undefined` if no enabled remote-game row matches.
   */
  remoteForGame(slug: string): Observable<FederationRemote | undefined> {
    return this.manifest$.pipe(
      map(m => m.remotes.find(r => r.kind === 'remote-game' && r.gameSlug === slug)),
    );
  }

  /** Find the first enabled remote with the given kind. */
  firstByKind(kind: FederationRemote['kind']): Observable<FederationRemote | undefined> {
    return this.manifest$.pipe(
      map(m => m.remotes.find(r => r.kind === kind)),
    );
  }
}
