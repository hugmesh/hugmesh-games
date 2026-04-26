import {
  Component, HostListener, Input, OnChanges,
  ElementRef, ViewChild,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { DomSanitizer, SafeResourceUrl } from '@angular/platform-browser';

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
 * window-level TW_LEVEL_TRANSITION handling moved up to HomeComponent
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
export class GameCanvasComponent implements OnChanges {
  @Input() game!: PlayableGame;

  @ViewChild('gameFrame') gameFrame!: ElementRef<HTMLIFrameElement>;
  @ViewChild('wrapper')   wrapper!:   ElementRef<HTMLDivElement>;

  gameLoaded = false;
  frameSrc: SafeResourceUrl = '';

  /** Reflected from the document Fullscreen API for the toolbar label. */
  isFullscreen = false;

  /** The plain (un-sanitised) iframe URL — used by the "open in new
   *  tab" / "open in new window" buttons that need a real string, not
   *  a SafeResourceUrl. Kept in sync with frameSrc on every mount. */
  private _frameUrl: string | null = null;

  /** Id of the level currently mounted in the iframe. Prevents redundant
   *  remounts (and the SSE / WASM tear-down they trigger) when Angular
   *  fires `ngOnChanges` for unrelated input identity changes. */
  private _mountedId: number | null = null;

  constructor(private sanitizer: DomSanitizer) {}

  ngOnChanges(): void {
    if (!this.game) return;
    if (this.game.id === this._mountedId) return;
    this._mountLevel(this.game);
  }

  onFrameLoad(): void {
    this.gameLoaded = true;
  }

  /** Toolbar: hard-reload the iframe by re-issuing the same URL with a
   *  fresh cache-bust. Drops Godot WASM + SSE — the user gets a clean
   *  load. */
  reload(): void {
    if (this.game) {
      this._mountedId = null;
      this._mountLevel(this.game);
    }
  }

  /** Toolbar: open the current game in a new tab. */
  openInNewTab(): void {
    if (!this._frameUrl) return;
    window.open(this._frameUrl, '_blank', 'noopener');
  }

  /** Toolbar: open the current game in a detached popup window
   *  (separate session, no opener back-reference). */
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
    const base = level.assetUrl
      ?? (level.slug ? `/api/assets/levels/${level.slug}/index.html`
                      : '/api/assets/levels/cave/index.html');
    // Cache-bust on actual mount only — the timestamp changes only when the
    // level id changes, so stable in-place renders don't recycle the iframe
    // (which would kill the SSE channels and WASM state).
    const url = `${base}?level_id=${level.id}&t=${Date.now()}`;
    this._frameUrl = url;
    this.frameSrc = this.sanitizer.bypassSecurityTrustResourceUrl(url);
  }
}
