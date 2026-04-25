import { Component, Input, inject } from '@angular/core';
import { DomSanitizer, SafeResourceUrl } from '@angular/platform-browser';

/**
 * Federated TWiaT game shell. The host swaps levels (cave / field /
 * future TWiaT levels) by setting the `levelSlug` input — the remote
 * recomputes its iframe `[src]` to the matching Godot bundle. Asset
 * paths still flow through the BFF (`/api/assets/levels/<slug>/index.html`)
 * so this remote stays storage-agnostic.
 *
 * The host loads it via:
 *   loadRemoteModule({ remoteName: 'games-three-wizards',
 *                      exposedModule: './GameRoot' })
 * and registers it as `<app-games-three-wizards-remote>`. Until that
 * cutover lands in home.component.html, the legacy <app-game-canvas>
 * stays the live render path for cave + field.
 */
@Component({
  selector: 'app-games-three-wizards-remote',
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

  /** Set by the host on level transition; defaults to cave. */
  @Input()
  set levelSlug(value: string) {
    this._levelSlug = value || 'cave';
    this.safeUrl = this.sanitizer.bypassSecurityTrustResourceUrl(
      `/api/assets/levels/${this._levelSlug}/index.html`,
    );
  }
  get levelSlug(): string { return this._levelSlug; }
  private _levelSlug = 'cave';

  safeUrl: SafeResourceUrl = this.sanitizer.bypassSecurityTrustResourceUrl(
    '/api/assets/levels/cave/index.html',
  );
}
