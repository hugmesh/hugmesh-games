import { Component, Input, inject } from '@angular/core';
import { DomSanitizer, SafeResourceUrl } from '@angular/platform-browser';

/**
 * Federated TWiaT game shell. Renders an iframe that points at the
 * BFF's per-level Godot bundle, parameterised by the host's currently-
 * active level (cave + field today; future TWiaT levels just need a
 * row in the levels table with sceneType=cave|field|… and the right
 * SCENE_TYPE_TO_GAME_SLUG mapping in home.component.ts).
 *
 * The `levelSlug` and `levelId` inputs are set by the host's home
 * template:
 *
 *   <app-three-wizards-remote
 *     [levelSlug]="activeLevel.sceneType"
 *     [levelId]="activeLevel.id"></app-three-wizards-remote>
 *
 * The selector is `app-three-wizards-remote` to match the tag
 * RemoteLoaderService registers via createCustomElement
 * (`app-${remote.gameSlug}-remote`, where `remote.gameSlug` is
 * 'three-wizards' from the federation_modules seed).
 *
 * `level_id=<id>` query goes onto the iframe URL because the patched
 * Godot index.html inside each bundle reads
 * `URLSearchParams.get('level_id')` to prefetch
 * `/api/levels/{id}/scene` before the WASM finishes compiling. Without
 * the param the prefetch defaults to level 1 — fine for cave but
 * wrong for field.
 */
@Component({
  selector: 'app-three-wizards-remote',
  standalone: true,
  template: `
    <div class="remote-root">
      <h2>Three Wizards In A Trenchcoat</h2>
      <p class="hint">
        Loaded as a federated remote module from
        <code>games-three-wizards</code>. Active level:
        <code>{{ levelSlug }}</code>.
      </p>
      <iframe
        class="wasm-frame"
        title="Three Wizards level"
        [src]="safeUrl"
        allow="fullscreen; pointer-lock; autoplay"></iframe>
    </div>
  `,
  styles: [`
    :host { display: block; width: 100%; height: 100%; }
    .remote-root { display: flex; flex-direction: column; height: 100%; padding: 8px;
                   background: #0c0e16; color: #d8dde6; font-family: sans-serif; }
    h2 { margin: 0 0 4px; font-size: 16px; color: #cfd5e0; }
    .hint { margin: 0 0 8px; font-size: 12px; color: #8b94a8; }
    .hint code { background: #1a1d28; padding: 1px 4px; border-radius: 3px; }
    .wasm-frame { flex: 1; border: 0; border-radius: 4px; background: #000;
                  width: 100%; height: 100%; min-height: 480px; }
  `],
})
export class ThreeWizardsRemoteComponent {
  private readonly sanitizer = inject(DomSanitizer);

  /** Set by the host on initial mount and on level transition. */
  @Input()
  set levelSlug(value: string) {
    this._levelSlug = value || 'cave';
    this._refresh();
  }
  get levelSlug(): string { return this._levelSlug; }
  private _levelSlug = 'cave';

  /** Mirrors the level row id; fed into the iframe URL so the patched
   *  Godot index.html can prefetch /api/levels/{id}/scene. */
  @Input()
  set levelId(value: number | null | undefined) {
    this._levelId = value ?? null;
    this._refresh();
  }
  get levelId(): number | null { return this._levelId; }
  private _levelId: number | null = null;

  /** Launch UID minted by `POST /api/games/three-wizards/launch`.
   *  Threaded through as a query param for audit/traceability —
   *  wasm-fragment doesn't validate it (Godot bundles are public
   *  under federation; see PLAN section H.1's architectural close). */
  @Input()
  set launchUid(value: string | null | undefined) {
    this._launchUid = value ?? null;
    this._refresh();
  }
  get launchUid(): string | null { return this._launchUid; }
  private _launchUid: string | null = null;

  safeUrl: SafeResourceUrl = this.sanitizer.bypassSecurityTrustResourceUrl(
    '/_remotes/wasm-fragment/cave/index.html',
  );

  private _refresh(): void {
    const id = this._levelId ?? '';
    // Asset bytes come from the federated wasm-fragment service —
    // the host's nginx proxies /_remotes/wasm-fragment/ to that
    // container, which serves the Godot bundles directly (no api
    // round-trip). Cache-bust on every (slug,id) change so the
    // iframe remounts cleanly across cave→field transitions.
    const params = new URLSearchParams();
    if (id !== '') params.set('level_id', String(id));
    if (this._launchUid) params.set('launch_uid', this._launchUid);
    params.set('t', String(Date.now()));
    const url = `/_remotes/wasm-fragment/${this._levelSlug}/index.html?${params}`;
    this.safeUrl = this.sanitizer.bypassSecurityTrustResourceUrl(url);
  }
}
