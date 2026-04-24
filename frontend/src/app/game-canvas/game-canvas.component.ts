import {
  Component, Input, Output, EventEmitter,
  OnChanges, OnDestroy, OnInit, HostListener,
  ElementRef, ViewChild,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { DomSanitizer, SafeResourceUrl } from '@angular/platform-browser';
import { Subscription } from 'rxjs';
import { GameStateService } from '../services/game-state.service';
import { GameState } from '../models/game-state.model';
import { GameLibraryService } from '../services/game-library.service';

/** Minimal shape the canvas needs — satisfied by both GameListing and AvailableLevel. */
export interface PlayableGame {
  id: number;
  slug?: string;
  assetUrl: string | null;
}

/** Payload posted by Godot (game_bridge.gd `request_level_transition`). */
interface LevelTransitionMessage {
  type: 'TW_LEVEL_TRANSITION';
  slug: string;
  levelId: number;
}

@Component({
  selector: 'app-game-canvas',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './game-canvas.component.html',
  styleUrl: './game-canvas.component.scss',
})
export class GameCanvasComponent implements OnInit, OnChanges, OnDestroy {
  @Input() game!: PlayableGame;
  /** Emitted when Godot requests a level transition so parent can update state. */
  @Output() levelTransition = new EventEmitter<{ slug: string; levelId: number }>();

  @ViewChild('gameFrame') gameFrame!: ElementRef<HTMLIFrameElement>;

  gameLoaded = false;
  frameSrc: SafeResourceUrl = '';

  /** Id of the level currently mounted in the iframe. Prevents redundant
   *  remounts (and the SSE / WASM tear-down they trigger) when Angular
   *  fires `ngOnChanges` for unrelated input identity changes. */
  private _mountedId: number | null = null;
  private _sub: Subscription | null = null;

  constructor(
    private gameState: GameStateService,
    private sanitizer: DomSanitizer,
    private library: GameLibraryService,
  ) {}

  ngOnInit(): void {
    if (!this._sub) {
      this._sub = this.gameState.state$.subscribe(s => this._postState(s));
    }
  }

  ngOnChanges(): void {
    if (!this.game) return;
    // Skip remount when the same level reference comes through again.
    // Without this, every parent-component change detection would tear down
    // the iframe (and the SSE channels inside it) just because the game
    // object identity changed.
    if (this.game.id === this._mountedId) return;
    this._mountLevel(this.game);
  }

  ngOnDestroy(): void {
    this._sub?.unsubscribe();
  }

  onFrameLoad(): void {
    this.gameLoaded = true;
  }

  /** Listens for `TW_LEVEL_TRANSITION` postMessage from the Godot iframe. */
  @HostListener('window:message', ['$event'])
  onWindowMessage(ev: MessageEvent): void {
    const data = ev.data as LevelTransitionMessage | null;
    if (!data || data.type !== 'TW_LEVEL_TRANSITION') return;

    // Unload current WASM and look up the next level's asset_url from the API.
    this.gameLoaded = false;
    this.frameSrc = '';
    this.levelTransition.emit({ slug: data.slug, levelId: data.levelId });

    this.library.getLevel(data.levelId).subscribe({
      next: level => {
        this._mountLevel({
          id: level.id,
          slug: level.slug,
          assetUrl: level.assetUrl,
        });
      },
      error: () => {
        // Fallback: construct URL by slug convention
        this._mountLevel({
          id: data.levelId,
          slug: data.slug,
          assetUrl: `/api/assets/levels/${data.slug}/index.html`,
        });
      },
    });
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

  private _postState(state: GameState): void {
    const frame = this.gameFrame?.nativeElement;
    if (frame?.contentWindow) {
      frame.contentWindow.postMessage({ type: 'GAME_STATE', payload: state }, '*');
    }
  }
}
