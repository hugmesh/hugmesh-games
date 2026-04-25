import { bootstrapApplication } from '@angular/platform-browser';
import { Component, inject } from '@angular/core';
import { DomSanitizer, SafeResourceUrl } from '@angular/platform-browser';

/**
 * Self-mounting federated remote for the chrome-less Dodecahedron Demo.
 *
 * The host shell loads this bundle by injecting a <script src="…/main.js">
 * tag pointed at the manifest's `remoteEntryUrl`'s sibling `main.js`.
 * On load, this file calls bootstrapApplication, which mounts
 * <app-dodecahedron-remote> wherever the host placed that element.
 *
 * Why not real ESM federation: see PLAN section A — full
 * @angular-architects/native-federation requires an angular.json
 * builder swap that's risky for this branch's monolithic host. The
 * dynamic-script approach achieves the same per-remote-nginx shape
 * with zero risk to the host build.
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
  // The remote's iframe still loads the existing Godot WASM bundle
  // from the BFF; the federated wrapper just owns its mount + chrome.
  // Angular requires an iframe `[src]` binding to flow through the
  // sanitizer (default policy strips it as unsafe), so we wrap the
  // URL with bypassSecurityTrustResourceUrl.
  private readonly sanitizer = inject(DomSanitizer);
  readonly safeUrl: SafeResourceUrl = this.sanitizer.bypassSecurityTrustResourceUrl(
    '/api/assets/levels/dodecahedron/index.html',
  );
}

bootstrapApplication(DodecahedronRemoteComponent).catch(err => {
  // eslint-disable-next-line no-console
  console.error('[games-dodecahedron] bootstrap failed', err);
});
