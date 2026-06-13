import {
  Component, EffectRef, HostListener, Input, OnChanges, OnDestroy, OnInit,
  ElementRef, ViewChild, effect, inject,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { DomSanitizer, SafeResourceUrl } from '@angular/platform-browser';
import { SessionControlService } from '../services/session-control.service';

/**
 * Same-origin broadcast channel used to coordinate "only one window plays
 * at a time" — every mounted GameCanvasComponent emits a heartbeat on
 * mount, and pauses itself when a peer with a later mount timestamp
 * announces presence. The channel name is intentionally stable across
 * federated remotes so a host-shell game and a popout's federated game
 * both speak the same protocol.
 */
const PRESENCE_CHANNEL = 'snapdragon-game-presence';

/** Minimal shape the canvas needs — satisfied by both GameListing and AvailableChapter. */
export interface PlayableGame {
  id: number;
  slug?: string;
  assetUrl: string | null;
}

/**
 * Legacy iframe renderer for levels whose sceneType isn't mapped to a
 * federated remote yet. Today this only handles cases that fall
 * outside SCENE_TYPE_TO_GAME_SLUG in HomeComponent — cave + field +
 * dodecahedron all route through their federated remotes now.
 *
 * Session role binding (SessionService.connect / claimControl) and
 * window-level TW_CHAPTER_TRANSITION handling moved up to HomeComponent
 * because they have to fire regardless of which renderer mounts. The
 * GAME_STATE postMessage emit was dead code (no listener inside the
 * Godot index.html) so it's gone too.
 *
 * Window controls: this component renders a small floating toolbar
 * with three "viewport" actions:
 *   - Fullscreen (Fullscreen API on the wrapper, NOT the iframe — the
 *     iframe sandbox can refuse fullscreen on some browsers; the
 *     wrapper always has the right ancestry to request it).
 *   - Open in new tab (window.open with `_blank` and the iframe URL
 *     so the user can play the game on its own tab).
 *   - Open in new window (window.open with explicit width+height +
 *     noopener so the popup is detached and doesn't share session
 *     storage with the host shell — useful for multi-window play).
 */
@Component({
  selector: 'app-game-canvas',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './game-canvas.component.html',
  styleUrl: './game-canvas.component.scss',
})
export class GameCanvasComponent implements OnChanges, OnInit, OnDestroy {
  @Input() game!: PlayableGame;

  /**
   * Web-native launch mode. When set, the iframe loads this wasm-fragment
   * base URL (e.g. `/_remotes/wasm-fragment/chess/index.html`) with the
   * session query params appended, INSTEAD of deriving the URL from
   * `game.assetUrl`. This reuses the host's iframe chrome (toolbar,
   * presence/pause, focus-grab, fullscreen) for the 14 web-native games,
   * threading the exact same session params the federated three-wizards
   * remote passes. Null ⇒ legacy assetUrl path (byte-for-byte unchanged).
   */
  @Input() webNativeBaseUrl: string | null = null;

  /** Launch UID minted by `POST /api/games/{slug}/launch`. Threaded onto
   *  the web-native iframe URL as `?launch_uid=` for audit/traceability. */
  @Input() launchUid: string | null = null;

  /** Multiplayer session id — written to the iframe URL as `?session_id=`
   *  so the bundle's host bridge scopes control/view egress to the
   *  per-session SSE sinks. */
  @Input() sessionId: string | null = null;

  /** This player's slot inside the multiplayer session (0..maxPlayers-1). */
  @Input() slotIndex: number | null = null;

  /** Character id this player is driving in the session. */
  @Input() localCharacterId: number | null = null;

  @ViewChild('gameFrame') gameFrame!: ElementRef<HTMLIFrameElement>;
  @ViewChild('wrapper')   wrapper!:   ElementRef<HTMLDivElement>;

  gameLoaded = false;
  frameSrc: SafeResourceUrl = '';

  /** Reflected from the document Fullscreen API for the toolbar label. */
  isFullscreen = false;

  /** True when another window of the same origin has taken focus, OR
   *  this window opened a popout itself. Drives the grey-out overlay
   *  + the Resume CTA. Bound by template via data-testid="canvas-paused"
   *  so the e2e suite can assert on it without inspecting CSS. */
  paused = false;

  /** The plain (un-sanitised) iframe URL — used by the "open in new
   *  tab" / "open in new window" buttons that need a real string, not
   *  a SafeResourceUrl. Kept in sync with frameSrc on every mount. */
  private _frameUrl: string | null = null;

  /** Id of the level currently mounted in the iframe. Prevents redundant
   *  remounts (and the SSE / WASM tear-down they trigger) when Angular
   *  fires `ngOnChanges` for unrelated input identity changes. */
  private _mountedId: number | null = null;

  /** Identity of the inputs the iframe was last mounted for. A web-native
   *  game keeps the same `game.id` while its launch_uid / session params
   *  arrive asynchronously (the `/launch` POST resolves after the initial
   *  bind), so id alone can't gate the remount — we key on the full set. */
  private _mountedKey: string | null = null;

  /** Per-instance window id so the presence-channel handler can tell
   *  its own broadcasts from a peer's. Random-enough for a tab-scope id. */
  private readonly _windowId =
      `gc-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`;

  /** Monotonic-ish mount timestamp. A peer with a HIGHER value wins
   *  ("most recently mounted plays"), and older instances pause. */
  private readonly _mountedAt = Date.now();

  /** Lazy presence-channel — created on init, closed on destroy. The
   *  BroadcastChannel is a same-origin singleton owned by the user
   *  agent so multiple tabs / windows of snapdragon.games share one. */
  private _presence: BroadcastChannel | null = null;

  private readonly sessionControl = inject(SessionControlService);
  private _sessionControlEffect: EffectRef | null = null;

  constructor(private sanitizer: DomSanitizer) {
    // Mirror the SessionControlService's `paused` signal onto the
    // iframe — when the BFF emits SESSION_PAUSED / PLAYER_PAUSED (for
    // self) via the session-control SSE channel, the service flips
    // `paused`, and we forward SD_GAME_PAUSE to the bridge. Same on
    // SESSION_RESUMED / PLAYER_RESUMED.
    this._sessionControlEffect = effect(() => {
      const remotePaused = this.sessionControl.paused();
      if (remotePaused && !this.paused) {
        this.pauseGame('manual');
      } else if (!remotePaused && this.paused) {
        // Only auto-resume when the local pause originated from the
        // session-control channel; a peer-mounted pause still waits
        // for an explicit Resume click.
        if (this._pauseOrigin === 'session-control') {
          this.resumeGame();
        }
      }
    });
  }
  /** Tracks whether the most recent `pauseGame` came from peer-presence
   *  or from the session-control SSE channel. Needed so the effect
   *  above doesn't undo a peer-presence pause when the SSE side
   *  happens to be un-paused. */
  private _pauseOrigin: 'peer-mounted' | 'self-popout' | 'manual' | 'session-control' | null = null;

  ngOnChanges(): void {
    if (!this.game) return;
    const key = this._mountKey(this.game);
    if (key === this._mountedKey) return;
    this._mountLevel(this.game);
  }

  /** Stable identity of everything that feeds the iframe URL. A change
   *  here (new chapter id, or the async-arriving web-native session
   *  params) triggers a single clean remount; identical re-binds are
   *  no-ops so the SSE/WASM state isn't recycled needlessly. */
  private _mountKey(level: PlayableGame): string {
    return [
      level.id,
      this.webNativeBaseUrl ?? '',
      this.launchUid ?? '',
      this.sessionId ?? '',
      this.slotIndex ?? '',
      this.localCharacterId ?? '',
    ].join('|');
  }

  ngOnInit(): void {
    if (typeof BroadcastChannel === 'undefined') return;
    this._presence = new BroadcastChannel(PRESENCE_CHANNEL);
    this._presence.addEventListener('message', this._onPresenceMessage);
    // Announce presence so any older peer pauses itself. This window
    // wins by default — peers see our _mountedAt and react.
    this._broadcastPresence();
  }

  ngOnDestroy(): void {
    if (this._presence) {
      try { this._presence.removeEventListener('message', this._onPresenceMessage); } catch { /* noop */ }
      try { this._presence.close(); } catch { /* noop */ }
      this._presence = null;
    }
  }

  /** Lambda so we can remove it cleanly on destroy. */
  private readonly _onPresenceMessage = (ev: MessageEvent): void => {
    const d = ev.data as { kind?: string; windowId?: string; mountedAt?: number } | null;
    if (!d || d.windowId === this._windowId) return;
    if (d.kind === 'mounted' && typeof d.mountedAt === 'number' && d.mountedAt >= this._mountedAt) {
      // A peer with at-least-as-recent a mount has claimed playback —
      // pause ourselves.
      this.pauseGame('peer-mounted');
    } else if (d.kind === 'resumed') {
      // Best-effort — the peer is resuming, no action needed here.
    }
  };

  private _broadcastPresence(): void {
    this._presence?.postMessage({
      kind: 'mounted',
      windowId: this._windowId,
      mountedAt: this._mountedAt,
    });
  }

  /** Pause the in-iframe game. Sets the grey-out overlay AND posts
   *  SD_GAME_PAUSE so the bridge can stop the Godot scene tree.
   *  Called both from cross-window coordination and from the
   *  "open in new window" button itself (the new popup wants exclusive
   *  control of the player's inputs). */
  pauseGame(reason: 'peer-mounted' | 'self-popout' | 'manual' | 'session-control' = 'manual'): void {
    if (this.paused) return;
    this.paused = true;
    this._pauseOrigin = reason;
    this._postToIframe({ type: 'SD_GAME_PAUSE', reason });
  }

  /** Inverse of `pauseGame`. Bound to the Resume CTA in the overlay. */
  resumeGame(): void {
    if (!this.paused) return;
    this.paused = false;
    this._postToIframe({ type: 'SD_GAME_RESUME' });
    // Re-claim playback so any peer that was idle now pauses.
    this._broadcastPresence();
    this._presence?.postMessage({
      kind: 'resumed',
      windowId: this._windowId,
      mountedAt: this._mountedAt,
    });
  }

  private _postToIframe(msg: { type: string; reason?: string }): void {
    try {
      this.gameFrame?.nativeElement?.contentWindow?.postMessage(msg, '*');
    } catch { /* iframe gone */ }
  }

  onFrameLoad(): void {
    this.gameLoaded = true;
    // Auto-grab keyboard focus on iframe mount so the player doesn't
    // have to click on the canvas before WASD lands in Godot's
    // `_unhandled_input`. The previous behaviour (from the README's
    // "Movement doesn't happen in the WASM iframe" troubleshooting
    // note) was a quiet UX papercut: every fresh load required a
    // click before keys did anything. CLAUDE.md's "Known gaps" listed
    // this as the remaining iframe-focus item.
    //
    // Schedule the focus on the next microtask so the browser has a
    // beat to complete the iframe's own load handlers — focusing
    // mid-load can race the WASM-frame's own focus-grab and end up
    // with neither side holding it.
    queueMicrotask(() => {
      try {
        this.gameFrame?.nativeElement?.focus({ preventScroll: true });
      } catch { /* iframe detached mid-mount */ }
    });
  }

  /** Toolbar: hard-reload the iframe by re-issuing the same URL with a
   *  fresh cache-bust. Drops Godot WASM + SSE — the user gets a clean
   *  load. */
  reload(): void {
    if (this.game) {
      this._mountedId = null;
      this._mountedKey = null;
      this._mountLevel(this.game);
    }
  }

  /** Toolbar: open the current game in a new tab. The new tab will
   *  broadcast a takeover-claim on its own mount; we pre-pause locally
   *  so the brief window between click and the new tab's broadcast
   *  doesn't leak keyboard input into the old iframe. */
  openInNewTab(): void {
    if (!this._frameUrl) return;
    this.pauseGame('self-popout');
    window.open(this._frameUrl, '_blank', 'noopener');
  }

  /** Toolbar: open the current game in a detached popup window
   *  (separate session, no opener back-reference). Pauses the in-
   *  this-window game on success — only one window should hold the
   *  keyboard at a time, and the popup announces presence via the
   *  same BroadcastChannel so older peers learn to pause too. */
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
    // Pause local playback as soon as the popout is requested — the
    // BroadcastChannel announce will fire on the popup's own mount
    // too, but we don't want a noticeable lag here. The user clicked
    // the button intending the popout to take over.
    if (handle) this.pauseGame('self-popout');
  }

  /** Toolbar: enter / exit fullscreen on the wrapper element. The
   *  wrapper (not the iframe) is the request target so the document's
   *  Fullscreen API has the right ancestor; modern browsers grant
   *  child-iframe fullscreen automatically once an ancestor is FS. */
  toggleFullscreen(): void {
    const wrap = this.wrapper?.nativeElement;
    if (!wrap) return;
    if (document.fullscreenElement) {
      document.exitFullscreen?.();
    } else {
      wrap.requestFullscreen?.().catch(() => {/* user-cancelled — fine */});
    }
  }

  @HostListener('document:fullscreenchange')
  onFullscreenChange(): void {
    this.isFullscreen = !!document.fullscreenElement;
  }

  private _mountLevel(level: PlayableGame): void {
    this.gameLoaded = false;
    this._mountedId = level.id;
    this._mountedKey = this._mountKey(level);
    const url = this.webNativeBaseUrl
      ? this._webNativeUrl(this.webNativeBaseUrl, level)
      : this._legacyUrl(level);
    this._frameUrl = url;
    this.frameSrc = this.sanitizer.bypassSecurityTrustResourceUrl(url);
  }

  /** Legacy path: derive the bundle URL from the level's assetUrl/slug. */
  private _legacyUrl(level: PlayableGame): string {
    const base = level.assetUrl
      ?? (level.slug ? `/api/assets/levels/${level.slug}/index.html`
                      : '/api/assets/levels/cave/index.html');
    // Cache-bust on actual mount only — the timestamp changes only when the
    // chapter id changes, so stable in-place renders don't recycle the iframe
    // (which would kill the SSE channels and WASM state).
    return `${base}?chapter_id=${level.id}&t=${Date.now()}`;
  }

  /**
   * Web-native path: the wasm-fragment base URL + the session query
   * params, identical in shape to the federated three-wizards remote
   * (chapter_id, launch_uid, session_id, slot_index, character_id, t).
   * The bundle's host bridge reads these to scope its control/view
   * egress onto the per-session SSE sinks.
   */
  private _webNativeUrl(base: string, level: PlayableGame): string {
    const params = new URLSearchParams();
    params.set('chapter_id', String(level.id));
    if (this.launchUid) params.set('launch_uid', this.launchUid);
    if (this.sessionId) params.set('session_id', this.sessionId);
    if (this.slotIndex !== null) params.set('slot_index', String(this.slotIndex));
    if (this.localCharacterId !== null) params.set('character_id', String(this.localCharacterId));
    params.set('t', String(Date.now()));
    return `${base}?${params}`;
  }
}
