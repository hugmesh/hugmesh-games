import {
  Component, ElementRef, HostListener, Input, OnDestroy, OnInit, ViewChild,
  inject,
  ChangeDetectionStrategy
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
 * `chapter_id=<id>` query goes onto the iframe URL because the patched
 * Godot index.html inside each bundle reads
 * `URLSearchParams.get('chapter_id')` to prefetch
 * `/api/chapters/{id}/scene` before the WASM finishes compiling. Without
 * the param the prefetch defaults to chapter 1 — fine for cave but
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
      <div class="frame-wrap canvas-wrapper" #wrap>
        <iframe
          #gameFrame
          class="wasm-frame game-frame"
          title="Three Wizards level"
          [src]="safeUrl"
          allow="fullscreen; pointer-lock; autoplay"></iframe>
        <div class="canvas-controls" role="toolbar" aria-label="Game window controls">
          <!-- Glyph in an aria-hidden span and the human-readable label in
               a screen-reader span so getByRole('button', { name: … })
               sees the same accessible name the legacy canvas exposes. -->
          <button type="button" class="ctrl" (click)="reload()" title="Reload game">
            <span aria-hidden="true">↻</span>
            <span class="sr">Reload</span>
          </button>
          <button type="button" class="ctrl" (click)="openInNewTab()" title="Open game in a new tab">
            <span aria-hidden="true">↗</span>
            <span class="sr">Open in new tab</span>
          </button>
          <button type="button" class="ctrl" (click)="openInPopupWindow()" title="Open game in a separate window">
            <span aria-hidden="true">⧉</span>
            <span class="sr">Open in new window</span>
          </button>
          <button type="button" class="ctrl" (click)="toggleFullscreen()" [title]="isFullscreen ? 'Exit fullscreen' : 'Enter fullscreen'">
            <span aria-hidden="true">⛶</span>
            <span class="sr">{{ isFullscreen ? 'Exit fullscreen' : 'Fullscreen' }}</span>
          </button>
        </div>
        @if (paused) {
          <!-- appFocusTrap-equivalent inline (federated remote, own build —
               can't import the host shell's FocusTrapDirective across the
               federation boundary; see
               frontend/src/app/directives/focus-trap.directive.ts for the
               sibling implementation and keep the two in sync). Keeps
               Tab/Shift+Tab cycling within the overlay instead of escaping
               into the now-inert game frame behind it. -->
          <div class="paused-overlay"
               #pausedOverlay
               data-testid="canvas-paused"
               role="dialog"
               aria-label="Game paused"
               aria-modal="true"
               tabindex="-1"
               (keydown)="onPausedOverlayKeydown($event)">
            <p class="paused-msg">Game paused — another window is playing.</p>
            <button type="button"
                    class="resume-btn"
                    data-testid="canvas-resume-btn"
                    (click)="resumeGame()">
              ▶ Resume here
            </button>
          </div>
        }
      </div>
    </div>
  `,
  changeDetection: ChangeDetectionStrategy.Eager,
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
    .sr {
      position: absolute; width: 1px; height: 1px;
      padding: 0; margin: -1px; overflow: hidden;
      clip: rect(0,0,0,0); white-space: nowrap; border: 0;
    }
    .paused-overlay {
      position: absolute; inset: 0; background: rgba(8,9,15,.82);
      display: flex; flex-direction: column; align-items: center;
      justify-content: center; gap: 12px; z-index: 6; color: #e8e8f0;
      backdrop-filter: blur(2px);
    }
    .paused-msg { margin: 0; font-size: 1rem; }
    .resume-btn {
      appearance: none; border: 1px solid #b388ff; background: rgba(124,77,255,.85);
      color: #fff; padding: 8px 16px; border-radius: 4px; font-weight: 600;
      cursor: pointer;
    }
    .resume-btn:hover, .resume-btn:focus-visible { background: #b388ff; outline: none; }
  `],
})
export class ThreeWizardsRemoteComponent implements OnInit, OnDestroy {
  private readonly sanitizer = inject(DomSanitizer);

  /** Reflects the document.fullscreenElement state for the toolbar
   *  label/icon. The ancestor element actually requesting fullscreen
   *  is the .frame-wrap div (NOT the iframe — sandboxed iframes can
   *  refuse fullscreen on some browsers). */
  isFullscreen = false;

  /** True when this window's playback has been suspended — either by
   *  the user popping the game out into a new tab/window, or by a peer
   *  tab claiming presence on the same-origin BroadcastChannel. Drives
   *  the grey-out overlay + the Resume CTA. Bound by template via
   *  data-testid="canvas-paused" for e2e assertions. */
  paused = false;

  /** Plain (un-sanitised) iframe URL — kept in sync with safeUrl on
   *  every refresh so the new-tab / new-window buttons can pass a
   *  real string to window.open. */
  private _frameUrl: string =
    '/_remotes/wasm-fragment/cave/index.html';

  /** Same-origin presence channel — every mounted federated game shell
   *  broadcasts on mount, and pauses itself when a peer with a later
   *  mount timestamp announces presence. Channel name matches
   *  GameCanvasComponent so legacy + federated mounts speak the same
   *  protocol. */
  private static readonly PRESENCE_CHANNEL = 'snapdragon-game-presence';
  private readonly _windowId =
      `tw-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`;
  private readonly _mountedAt = Date.now();
  private _presence: BroadcastChannel | null = null;

  @ViewChild('root') rootEl?: ElementRef<HTMLDivElement>;
  @ViewChild('wrap') wrapEl?: ElementRef<HTMLDivElement>;
  @ViewChild('gameFrame') gameFrame?: ElementRef<HTMLIFrameElement>;
  @ViewChild('pausedOverlay') pausedOverlayEl?: ElementRef<HTMLDivElement>;

  /** Standard focusable-element selector for the paused-overlay focus
   *  trap. Re-queried live on every Tab press (not cached) — mirrors
   *  frontend/src/app/directives/focus-trap.directive.ts. */
  private static readonly FOCUSABLE_SELECTOR = [
    'a[href]', 'button:not([disabled])', 'input:not([disabled])',
    'select:not([disabled])', 'textarea:not([disabled])',
    '[tabindex]:not([tabindex="-1"])',
  ].join(',');

  /** Element that held focus before the paused-overlay opened — restored
   *  on resume so closing the overlay doesn't strand focus. */
  private _pausedPreviouslyFocused: HTMLElement | null = null;

  private _focusableInPausedOverlay(): HTMLElement[] {
    const host = this.pausedOverlayEl?.nativeElement;
    if (!host) return [];
    return Array.from(
      host.querySelectorAll<HTMLElement>(ThreeWizardsRemoteComponent.FOCUSABLE_SELECTOR),
    );
  }

  /** Tab/Shift+Tab handler bound on the paused-overlay div itself — traps
   *  focus so a keyboard user can't tab out into the (aria-hidden-by-
   *  intent) game frame behind the overlay while it's up. */
  onPausedOverlayKeydown(ev: KeyboardEvent): void {
    if (ev.key !== 'Tab') return;
    const host = this.pausedOverlayEl?.nativeElement;
    const focusable = this._focusableInPausedOverlay();
    if (focusable.length === 0) {
      if (host) { ev.preventDefault(); host.focus(); }
      return;
    }
    const first = focusable[0];
    const last = focusable[focusable.length - 1];
    const active = document.activeElement;
    if (ev.shiftKey) {
      // Also wrap when focus sits on the overlay host itself (tabindex=-1
      // fallback) or has escaped outside it — not just from the first
      // focusable — so Shift+Tab can't leak into the paused game frame.
      if (active === host || active === first || !host?.contains(active)) {
        ev.preventDefault(); last.focus();
      }
    } else {
      if (active === last) { ev.preventDefault(); first.focus(); }
    }
  }

  ngOnInit(): void {
    if (typeof BroadcastChannel === 'undefined') return;
    try {
      this._presence = new BroadcastChannel(ThreeWizardsRemoteComponent.PRESENCE_CHANNEL);
      this._presence.addEventListener('message', this._onPresenceMessage);
      this._broadcastPresence();
    } catch { this._presence = null; }
  }

  ngOnDestroy(): void {
    if (this._presence) {
      try { this._presence.removeEventListener('message', this._onPresenceMessage); } catch { /* */ }
      try { this._presence.close(); } catch { /* */ }
      this._presence = null;
    }
  }

  private readonly _onPresenceMessage = (ev: MessageEvent): void => {
    const d = ev.data as { kind?: string; windowId?: string; mountedAt?: number } | null;
    if (!d || d.windowId === this._windowId) return;
    if (d.kind === 'mounted' && typeof d.mountedAt === 'number' && d.mountedAt >= this._mountedAt) {
      this.pauseGame();
    }
  };

  private _broadcastPresence(): void {
    this._presence?.postMessage({
      kind: 'mounted', windowId: this._windowId, mountedAt: this._mountedAt,
    });
  }

  /** Suspend playback in this window: show the grey-out overlay and
   *  forward SD_GAME_PAUSE to the iframe so the Godot bridge can stop
   *  the scene tree. Idempotent. Remembers the currently-focused element
   *  and moves focus into the overlay once it's rendered. Deferred to
   *  the next animation frame (NOT a microtask) so Angular's change
   *  detection has actually mounted the `@if (paused)` overlay DOM before
   *  we query it for a focus target — a microtask can run before CD
   *  flushes, leaving the overlay unmounted and focus stranded. */
  pauseGame(): void {
    if (this.paused) return;
    this.paused = true;
    this._pausedPreviouslyFocused = document.activeElement as HTMLElement | null;
    this._postToIframe({ type: 'SD_GAME_PAUSE' });
    requestAnimationFrame(() => {
      const focusable = this._focusableInPausedOverlay();
      const target = focusable[0] ?? this.pausedOverlayEl?.nativeElement ?? null;
      target?.focus();
    });
  }

  /** Inverse of pauseGame — re-claim playback. Re-broadcasts presence so
   *  any sibling tabs pause themselves, and restores focus to whatever
   *  held it before the overlay opened. */
  resumeGame(): void {
    if (!this.paused) return;
    this.paused = false;
    this._postToIframe({ type: 'SD_GAME_RESUME' });
    this._broadcastPresence();
    const target = this._pausedPreviouslyFocused;
    this._pausedPreviouslyFocused = null;
    if (target && typeof target.focus === 'function' && document.contains(target)) {
      target.focus();
    }
  }

  private _postToIframe(msg: { type: string }): void {
    try {
      this.gameFrame?.nativeElement?.contentWindow?.postMessage(msg, '*');
    } catch { /* iframe gone */ }
  }

  /** Toolbar: hard-reload the iframe by re-issuing the current URL. */
  reload(): void {
    this._refresh();
  }

  /** Toolbar: open the current level URL in a new browser tab.
   *  Pauses local playback so the brief window before the new tab
   *  claims presence doesn't leak keyboard input. */
  openInNewTab(): void {
    if (!this._frameUrl) return;
    this.pauseGame();
    window.open(this._frameUrl, '_blank', 'noopener');
  }

  /** Toolbar: open the current level URL in a detached popup window
   *  (no opener, separate session). Sized to roughly the in-host
   *  iframe so the user gets a similar viewport. Pauses local
   *  playback — only one window should hold the keyboard. */
  openInPopupWindow(): void {
    if (!this._frameUrl) return;
    const w = Math.min(1280, window.screen?.availWidth  ?? 1280);
    const h = Math.min( 800, window.screen?.availHeight ?? 800);
    const features = [
      'noopener', 'noreferrer',
      `width=${w}`, `height=${h}`,
      'menubar=no', 'toolbar=no', 'location=no', 'resizable=yes',
    ].join(',');
    const handle = window.open(this._frameUrl, '_blank', features);
    if (handle) this.pauseGame();
  }

  /** Toolbar: toggle fullscreen on the .canvas-wrapper container.
   *  Modern browsers cascade fullscreen permission to descendant
   *  iframes once an ancestor is in fullscreen — no need to call
   *  the iframe's own requestFullscreen. */
  toggleFullscreen(): void {
    const el = this.wrapEl?.nativeElement
      ?? (this.rootEl?.nativeElement?.querySelector('.canvas-wrapper') as HTMLElement | null);
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
    this._chapterId = value ?? null;
    this._refresh();
  }
  get chapterId(): number | null { return this._chapterId; }
  private _chapterId: number | null = null;

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
    const id = this._chapterId ?? '';
    // Asset bytes come from the federated wasm-fragment service —
    // the host's nginx proxies /_remotes/wasm-fragment/ to that
    // container, which serves the Godot bundles directly (no api
    // round-trip). Cache-bust on every (slug,id) change so the
    // iframe remounts cleanly across cave→field transitions.
    const params = new URLSearchParams();
    if (id !== '') params.set('chapter_id', String(id));
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
