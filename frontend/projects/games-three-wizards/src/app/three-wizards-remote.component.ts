import {
  Component, ElementRef, HostListener, Input, ViewChild,
  inject,
} from '@angular/core';
import { DomSanitizer, SafeResourceUrl } from '@angular/platform-browser';

/**
 * Federated TWiaT game shell. Renders an iframe that points at the
 * BFF's per-level Godot bundle, parameterised by the host's currently-
 * active level (cave + field today; future TWiaT levels just need a
 * row in the levels table with sceneType=cave|field|… and the right
 * SCENE_TYPE_TO_GAME_SLUG mapping in home.component.ts).
 *
 * The `chapterSlug` and `chapterId` inputs are set by the host's home
 * template:
 *
 *   <app-three-wizards-remote
 *     [chapterSlug]="activeLevel.sceneType"
 *     [chapterId]="activeLevel.id"></app-three-wizards-remote>
 *
 * The selector is `app-three-wizards-remote` to match the tag
 * RemoteLoaderService registers via createCustomElement
 * (`app-${remote.gameSlug}-remote`, where `remote.gameSlug` is
 * 'three-wizards' from the federation_modules seed).
 *
 * `level_id=<id>` query goes onto the iframe URL because the patched
 * Godot index.html inside each bundle reads
 * `URLSearchParams.get('level_id')` to prefetch
 * `/api/chapters/{id}/scene` before the WASM finishes compiling. Without
 * the param the prefetch defaults to level 1 — fine for cave but
 * wrong for field.
 */
@Component({
  selector: 'app-three-wizards-remote',
  standalone: true,
  template: `
    <div class="remote-root" #root>
      <h2>Three Wizards In A Trenchcoat</h2>
      <p class="hint">
        Loaded as a federated remote module from
        <code>games-three-wizards</code>. Active level:
        <code>{{ chapterSlug }}</code>.
      </p>
      <div class="frame-wrap">
        <iframe
          class="wasm-frame"
          title="Three Wizards level"
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
export class ThreeWizardsRemoteComponent {
  private readonly sanitizer = inject(DomSanitizer);

  /** Reflects the document.fullscreenElement state for the toolbar
   *  label/icon. The ancestor element actually requesting fullscreen
   *  is the .frame-wrap div (NOT the iframe — sandboxed iframes can
   *  refuse fullscreen on some browsers). */
  isFullscreen = false;

  /** Plain (un-sanitised) iframe URL — kept in sync with safeUrl on
   *  every refresh so the new-tab / new-window buttons can pass a
   *  real string to window.open. */
  private _frameUrl: string =
    '/_remotes/wasm-fragment/cave/index.html';

  @ViewChild('root') rootEl?: ElementRef<HTMLDivElement>;

  /** Toolbar: open the current level URL in a new browser tab. */
  openInNewTab(): void {
    if (!this._frameUrl) return;
    window.open(this._frameUrl, '_blank', 'noopener');
  }

  /** Toolbar: open the current level URL in a detached popup window
   *  (no opener, separate session). Sized to roughly the in-host
   *  iframe so the user gets a similar viewport. */
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

  /** Toolbar: toggle fullscreen on the .frame-wrap container.
   *  Modern browsers cascade fullscreen permission to descendant
   *  iframes once an ancestor is in fullscreen — no need to call
   *  the iframe's own requestFullscreen. */
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

  /** Set by the host on initial mount and on level transition. */
  @Input()
  set chapterSlug(value: string) {
    this._levelSlug = value || 'cave';
    this._refresh();
  }
  get chapterSlug(): string { return this._levelSlug; }
  private _levelSlug = 'cave';

  /** Mirrors the level row id; fed into the iframe URL so the patched
   *  Godot index.html can prefetch /api/chapters/{id}/scene. */
  @Input()
  set chapterId(value: number | null | undefined) {
    this._levelId = value ?? null;
    this._refresh();
  }
  get chapterId(): number | null { return this._levelId; }
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

  /**
   * Multiplayer session id (UUID) — when set, written to the iframe URL
   * as `?session_id=<uuid>` so the Godot bundle's game_bridge.gd can
   * forward it to HostBridge.set_session_id. The control + view egress
   * paths then carry it through to the BFF, which routes them onto the
   * per-session SSE sinks.
   */
  @Input()
  set sessionId(value: string | null | undefined) {
    this._sessionId = value || null;
    this._refresh();
  }
  get sessionId(): string | null { return this._sessionId; }
  private _sessionId: string | null = null;

  /** This player's slot inside the multiplayer session (0..maxPlayers-1). */
  @Input()
  set slotIndex(value: number | null | undefined) {
    this._slotIndex = (value ?? null);
    this._refresh();
  }
  get slotIndex(): number | null { return this._slotIndex; }
  private _slotIndex: number | null = null;

  /** Character id this player is driving in the session. */
  @Input()
  set localCharacterId(value: number | null | undefined) {
    this._localCharacterId = (value ?? null);
    this._refresh();
  }
  get localCharacterId(): number | null { return this._localCharacterId; }
  private _localCharacterId: number | null = null;

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
    // Multiplayer threading: when the host's session picker has
    // assigned the player to a session + slot, propagate to Godot.
    // game_bridge.gd reads these on _ready and calls
    // HostBridge.set_session_id + register_player_slot, which flips
    // every subsequent control + view egress payload into the
    // per-session sinks on the BFF.
    if (this._sessionId) params.set('session_id', this._sessionId);
    if (this._slotIndex !== null) params.set('slot_index', String(this._slotIndex));
    if (this._localCharacterId !== null) params.set('character_id', String(this._localCharacterId));
    params.set('t', String(Date.now()));
    const url = `/_remotes/wasm-fragment/${this._levelSlug}/index.html?${params}`;
    this._frameUrl = url;
    this.safeUrl = this.sanitizer.bypassSecurityTrustResourceUrl(url);
  }
}
