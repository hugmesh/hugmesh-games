import { Injectable, createComponent, EnvironmentInjector, Type, inject } from '@angular/core';
import { createCustomElement } from '@angular/elements';
import { Observable, defer, from, of, shareReplay } from 'rxjs';
import { map, switchMap } from 'rxjs/operators';

import { FederationRemote, FederationService } from './federation.service';

// `@angular-architects/native-federation` is loaded LAZILY in
// `registerRemoteAsCustomElement` instead of statically imported here.
// Karma's webpack-based test bundler can't parse the package's ESM
// `index.js` (the Module-parse-failed error blocks every spec that
// transitively imports this file), so a static import would
// effectively make this service untestable. The dynamic import is
// equivalent at runtime — the federation runtime still loads, just
// on-demand the first time a game card is clicked.
type LoadRemoteModuleFn = (opts: { remoteName: string; exposedModule: string }) => Promise<Record<string, unknown>>;

/**
 * Loads a federated remote module via native-federation's runtime,
 * then registers its exported component as a Web Component so the
 * host's templates can render it as `<app-<slug>-remote>`.
 *
 * Pipeline per slug:
 *   1. Resolve the federation_modules row (RemoteService).
 *   2. `loadRemoteModule({remoteName, exposedModule:'./GameRoot'})`
 *      pulls the remote's exposed component class. Native-federation
 *      handles ESM import + shared-deps deduplication.
 *   3. `createCustomElement(...)` wraps the Angular component as a
 *      Web Component using THIS host's environmentInjector — no
 *      separate Angular tree, no zone collisions.
 *   4. `customElements.define(...)` registers the tag, the browser
 *      upgrades any existing instance the host has rendered.
 *
 * Cached per slug via shareReplay; subsequent calls are free.
 */
@Injectable({ providedIn: 'root' })
export class RemoteLoaderService {

  private readonly federation = inject(FederationService);
  private readonly envInjector = inject(EnvironmentInjector);
  private readonly cache = new Map<string, Observable<FederationRemote>>();

  /**
   * Resolve `gameSlug` to its remote-game manifest entry, load the
   * remote module, register its `<app-<slug>-remote>` Web Component.
   * Resolves to the manifest row when the registration is in place.
   */
  loadGameRemote(gameSlug: string): Observable<FederationRemote> {
    const cached = this.cache.get(gameSlug);
    if (cached) return cached;

    const stream = this.federation.remoteForGame(gameSlug).pipe(
      switchMap(remote => {
        if (!remote) {
          return of<never>();
        }
        return defer(() => from(this.registerRemoteAsCustomElement(remote))).pipe(map(() => remote));
      }),
      shareReplay(1),
    );
    this.cache.set(gameSlug, stream);
    return stream;
  }

  private async registerRemoteAsCustomElement(remote: FederationRemote): Promise<void> {
    const tagName = `app-${remote.gameSlug}-remote`;
    if (customElements.get(tagName)) {
      return;
    }

    // Variable-specifier dynamic import keeps native-federation OUT of
    // karma's static dependency graph — webpack's parser only follows
    // string-literal imports, so passing through a variable is opaque.
    // At runtime the import map (set up by initFederation) resolves
    // the bare specifier; in tests this method is never reached, so
    // the import never executes.
    const specifier = '@angular-architects/native-federation';
    const fed = await import(/* webpackIgnore: true */ specifier) as { loadRemoteModule: LoadRemoteModuleFn };

    // remote.slug is the federation manifest's `name`; remote.exposedModule
    // is the './GameRoot' path the federation.config.js exposes block uses.
    const mod = await fed.loadRemoteModule({
      remoteName: remote.slug,
      exposedModule: remote.exposedModule ?? './GameRoot',
    });

    // Find the first exported class — federation.config.js exposes
    // a single component per remote so the first match is correct.
    const ComponentClass = Object.values(mod).find(
      v => typeof v === 'function',
    ) as Type<unknown> | undefined;

    if (!ComponentClass) {
      throw new Error(`[remote-loader] no class export found in ${remote.slug}/${remote.exposedModule}`);
    }

    // Wrap the component as a Web Component using THIS host's injector
    // so the remote's render shares zone + change detection with the
    // host shell. Different from createElement() with a fresh app —
    // here the remote's component lifecycle runs in the host's tree.
    const element = createCustomElement(ComponentClass, { injector: this.envInjector });
    customElements.define(tagName, element);
    console.log(`[remote-loader] registered <${tagName}> from ${remote.slug}`);
  }
}

// Defer + createComponent kept around for future imperative-mount paths.
void createComponent;
