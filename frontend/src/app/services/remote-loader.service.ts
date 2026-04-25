import { Injectable, inject } from '@angular/core';
import { Observable, defer, from, of, shareReplay } from 'rxjs';
import { map, switchMap } from 'rxjs/operators';

import { FederationRemote, FederationService } from './federation.service';

/**
 * Loads a federated remote's bundle into the host page at runtime.
 *
 * Today this uses the script-tag-injection pattern — the remote was
 * built with `outputHashing: "none"` so we know its main.js path
 * without having to parse its index.html. Once the remote project
 * graduates to real `@angular-architects/native-federation:build`, the
 * loadRemote() call here changes to native-federation's
 * `loadRemoteModule(...)` but the public surface (an Observable that
 * resolves once the remote has registered its custom element) stays
 * the same.
 *
 * Each remote is loaded at most once per page; subsequent calls hit a
 * shared cache via shareReplay(1).
 */
@Injectable({ providedIn: 'root' })
export class RemoteLoaderService {

  private readonly federation = inject(FederationService);
  private readonly cache = new Map<string, Observable<FederationRemote>>();

  /**
   * Resolve `slug` to its federation_modules row, then ensure the
   * remote's main.js + polyfills.js are loaded. Resolves to the
   * manifest entry when the bundle is in DOM and ready.
   */
  loadGameRemote(gameSlug: string): Observable<FederationRemote> {
    const cached = this.cache.get(gameSlug);
    if (cached) return cached;

    const stream = this.federation.remoteForGame(gameSlug).pipe(
      switchMap(remote => {
        if (!remote) {
          return of<never>();
        }
        return defer(() => from(this.injectRemote(remote))).pipe(map(() => remote));
      }),
      shareReplay(1),
    );
    this.cache.set(gameSlug, stream);
    return stream;
  }

  private async injectRemote(remote: FederationRemote): Promise<void> {
    // remoteEntryUrl points at `/_remotes/<slug>/remoteEntry.json`.
    // The matching main bundle lives next door at `main.js`. With
    // outputHashing: 'none' the filename is stable.
    const base = remote.remoteEntryUrl.replace(/remoteEntry\.json$/, '');
    const polyfills = `${base}polyfills.js`;
    const main = `${base}main.js`;

    // Load polyfills first so zone.js + ES shims are present before
    // main.js executes.
    if (!document.querySelector(`script[data-snapdragon-remote="${remote.slug}-polyfills"]`)) {
      await this.appendScript(polyfills, `${remote.slug}-polyfills`);
    }
    if (!document.querySelector(`script[data-snapdragon-remote="${remote.slug}-main"]`)) {
      await this.appendScript(main, `${remote.slug}-main`);
    }
  }

  private appendScript(src: string, marker: string): Promise<void> {
    return new Promise((resolve, reject) => {
      const tag = document.createElement('script');
      tag.src = src;
      tag.type = 'module';
      tag.dataset['snapdragonRemote'] = marker;
      tag.onload = () => resolve();
      tag.onerror = err => reject(err);
      document.head.appendChild(tag);
    });
  }
}
