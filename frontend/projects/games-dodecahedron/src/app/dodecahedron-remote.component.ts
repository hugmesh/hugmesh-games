import {
  Component, ElementRef, HostListener, Input, ViewChild,
  inject,
} from '@angular/core';
import { DomSanitizer, SafeResourceUrl } from '@angular/platform-browser';

/**
 * The exposed federated module. Loaded by the host via
 * native-federation's loadRemoteModule({remoteName: 'games-dodecahedron',
 * exposedModule: './GameRoot'}). The host then registers it as a custom
 * element so its template can reference <app-dodecahedron-remote>
 * without dual-bootstrapping Angular.
 *
 * Window controls (fullscreen / open in new tab / open in new window)
 * mirror the three-wizards remote so every game on snapdragon.games
 * has the same toolbar, regardless of which federated remote rendered
 * it.
 */
@Component({
  selector: 'app-dodecahedron-remote',
  standalone: true,
  template: `
    <div class="remote-root" #root>
      <h2>Dodecahedron Demo</h2>
      <p class="hint">
        Loaded as a federated remote module from
        <code>games-dodecahedron</code>.
      </p>
      <div class="frame-wrap">
        <iframe
          class="wasm-frame"
          title="Spinning Dodecahedron"
          [src]="safeUrl"
          allow="fullscreen; pointer-lock; autoplay"></iframe>
        <div class="canvas-controls" role="toolbar" aria-label="Game window controls">
          <button type="button" class="ctrl" (click)="openInNewTab()" title="Open game in a new tab">↗</button>
          <button type="button" class="ctrl" (click)="openInPopupWindow()" title="Open game in a separate window">⧉</button>
          <button type="button" class="ctrl" (click)="toggleFullscreen()" [title]="isFullscreen ? 'Exit fullscreen' : 'Fullscreen'">⛶</button>
        </div>
      </div>
    </div>
  `,
  styles: [`
    :host { display: block; width: 100%; height: 100%; }
    .remote-root { display: flex; flex-direction: column; height: 100%; padding: 8px;
                   background: #0c0e16; color: #d8dde6; font-family: sans-serif; }
    h2 { margin: 0 0 4px; font-size: 16px; color: #cfd5e0; }
    .hint { margin: 0 0 8px; font-size: 12px; color: #8b94a8; }
    .hint code { background: #1a1d28; padding: 1px 4px; border-radius: 3px; }
    .frame-wrap { position: relative; flex: 1; min-height: 480px; }
    .wasm-frame { border: 0; border-radius: 4px; background: #000;
                  width: 100%; height: 100%; }
    .canvas-controls {
      position: absolute; top: 8px; right: 8px; display: flex; gap: 6px;
      z-index: 5; opacity: 0.45; transition: opacity 120ms ease-in-out;
    }
    .frame-wrap:hover .canvas-controls,
    .frame-wrap:focus-within .canvas-controls,
    .frame-wrap:fullscreen .canvas-controls { opacity: 1; }
    .ctrl {
      appearance: none; border: 1px solid rgba(124,77,255,.55);
      background: rgba(10,10,18,.78); color: #e8e8f0;
      width: 30px; height: 30px; display: inline-flex; align-items: center;
      justify-content: center; font-size: 1rem; font-weight: 600;
      border-radius: 4px; cursor: pointer; backdrop-filter: blur(2px);
      transition: background 120ms, color 120ms, border-color 120ms;
    }
    .ctrl:hover, .ctrl:focus-visible {
      background: rgba(124,77,255,.85); color: #fff;
      border-color: #b388ff; outline: none;
    }
  `],
})
export class DodecahedronRemoteComponent {
  private readonly sanitizer = inject(DomSanitizer);

  isFullscreen = false;
  private _frameUrl = '/_remotes/wasm-fragment/dodecahedron/index.html';

  @ViewChild('root') rootEl?: ElementRef<HTMLDivElement>;

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

  openInNewTab(): void {
    if (!this._frameUrl) return;
    window.open(this._frameUrl, '_blank', 'noopener');
  }

  openInPopupWindow(): void {
    if (!this._frameUrl) return;
    const w = Math.min(1280, window.screen?.availWidth  ?? 1280);
    const h = Math.min( 800, window.screen?.availHeight ?? 800);
    const features = [
      'noopener', 'noreferrer',
      `width=${w}`, `height=${h}`,
      'menubar=no', 'toolbar=no', 'location=no', 'resizable=yes',
    ].join(',');
    window.open(this._frameUrl, '_blank', features);
  }

  toggleFullscreen(): void {
    const el = this.rootEl?.nativeElement?.querySelector('.frame-wrap') as HTMLElement | null;
    if (!el) return;
    if (document.fullscreenElement) {
      document.exitFullscreen?.();
    } else {
      el.requestFullscreen?.().catch(() => {/* user-cancelled — fine */});
    }
  }

  @HostListener('document:fullscreenchange')
  onFullscreenChange(): void {
    this.isFullscreen = !!document.fullscreenElement;
  }

  private _refresh(): void {
    const base = '/_remotes/wasm-fragment/dodecahedron/index.html';
    const url = this._launchUid
      ? `${base}?launch_uid=${encodeURIComponent(this._launchUid)}`
      : base;
    this._frameUrl = url;
    this.safeUrl = this.sanitizer.bypassSecurityTrustResourceUrl(url);
  }
}
