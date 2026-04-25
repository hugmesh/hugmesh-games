import { Component, inject } from '@angular/core';
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
  readonly safeUrl: SafeResourceUrl = this.sanitizer.bypassSecurityTrustResourceUrl(
    '/api/assets/levels/dodecahedron/index.html',
  );
}
