import {
  Component, Input, OnChanges,
  ElementRef, ViewChild,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { DomSanitizer, SafeResourceUrl } from '@angular/platform-browser';

/** Minimal shape the canvas needs — satisfied by both GameListing and AvailableLevel. */
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

  gameLoaded = false;
  frameSrc: SafeResourceUrl = '';

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

  private _mountLevel(level: PlayableGame): void {
    this.gameLoaded = false;
    this._mountedId = level.id;
    const base = level.assetUrl
      ?? (level.slug ? `/api/assets/levels/${level.slug}/index.html`
                      : '/api/assets/levels/cave/index.html');
    // Cache-bust on actual mount only — the timestamp changes only when the
    // level id changes, so stable in-place renders don't recycle the iframe
    // (which would kill the SSE channels and WASM state).
    this.frameSrc = this.sanitizer.bypassSecurityTrustResourceUrl(
      `${base}?level_id=${level.id}&t=${Date.now()}`
    );
  }
}
