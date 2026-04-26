import { Component, Input, inject } from '@angular/core';
import { DomSanitizer, SafeResourceUrl } from '@angular/platform-browser';

/**
 * The exposed federated module. Loaded by the host via
 * native-federation's loadRemoteModule({remoteName: 'games-dodecahedron',
 * exposedModule: './GameRoot'}). The host then registers it as a custom
 * element so its template can reference <app-dodecahedron-remote>
 * without dual-bootstrapping Angular.
 */
@Component({
  selector: 'app-dodecahedron-remote',
  standalone: true,
  template: `
    <div class="remote-root">
      <h2>Dodecahedron Demo</h2>
      <p class="hint">
        Loaded as a federated remote module from
        <code>games-dodecahedron</code>.
      </p>
      <iframe
        class="wasm-frame"
        title="Spinning Dodecahedron"
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
export class DodecahedronRemoteComponent {
  private readonly sanitizer = inject(DomSanitizer);

  /** Launch UID minted by `POST /api/games/dodecahedron/launch`.
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

  // Asset bytes come from the federated wasm-fragment service — the
  // host's nginx proxies /_remotes/wasm-fragment/ to that container,
  // which serves the Godot bundles directly (no api round-trip).
  safeUrl: SafeResourceUrl = this.sanitizer.bypassSecurityTrustResourceUrl(
    '/_remotes/wasm-fragment/dodecahedron/index.html',
  );

  private _refresh(): void {
    const base = '/_remotes/wasm-fragment/dodecahedron/index.html';
    const url = this._launchUid
      ? `${base}?launch_uid=${encodeURIComponent(this._launchUid)}`
      : base;
    this.safeUrl = this.sanitizer.bypassSecurityTrustResourceUrl(url);
  }
}
