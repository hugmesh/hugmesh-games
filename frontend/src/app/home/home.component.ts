import {
  Component, CUSTOM_ELEMENTS_SCHEMA, ElementRef, HostListener, OnInit, ViewChild, inject
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { GameLibraryService } from '../services/game-library.service';
import { CharacterService } from '../services/character.service';
import { RemoteLoaderService } from '../services/remote-loader.service';
import { AvailableGame } from '../models/available-game.model';
import { GameCanvasComponent } from '../game-canvas/game-canvas.component';
import { GameHudComponent } from '../game-hud/game-hud.component';
import { GameControlsComponent } from '../game-controls/game-controls.component';
import { Observable, of } from 'rxjs';
import { catchError, map } from 'rxjs/operators';

const SCENE_ICONS: Record<string, string> = {
  cave:         '🏔',
  field:        '🌿',
  dodecahedron: '⬡',
};

// Scene types that are pure visual demos — no character, no HUD, no
// movement controls overlay. Add new chrome-less games here.
const CHROMELESS_SCENE_TYPES = new Set(['dodecahedron']);

// Game slugs whose level rendering is delegated to a federated remote
// module instead of the legacy iframe. The host loads the remote's
// bundle via RemoteLoaderService when the player picks the card.
const FEDERATED_GAME_SLUGS = new Set(['dodecahedron']);

@Component({
  selector: 'app-home',
  standalone: true,
  imports: [CommonModule, GameCanvasComponent, GameHudComponent, GameControlsComponent],
  // Federated remote elements (e.g. <app-dodecahedron-remote>) are
  // bootstrapped by their own bundle, not Angular's host compiler.
  // CUSTOM_ELEMENTS_SCHEMA tells the host template to accept any
  // element with a dash in its name as a web-component-style mount.
  schemas: [CUSTOM_ELEMENTS_SCHEMA],
  templateUrl: './home.component.html',
  styleUrl: './home.component.scss',
})
export class HomeComponent implements OnInit {
  @ViewChild('canvasContainer') canvasContainer!: ElementRef<HTMLDivElement>;
  @ViewChild('canvasSection') canvasSection!: ElementRef<HTMLElement>;

  private readonly library = inject(GameLibraryService);
  private readonly characterSvc = inject(CharacterService);
  private readonly remoteLoader = inject(RemoteLoaderService);

  characterId = 1;
  levels$: Observable<AvailableGame[]> = of([]);
  activeLevel: AvailableGame | null = null;
  isFullscreen = false;
  /** Set when the chosen level is served by a federated remote. */
  activeRemoteSlug: string | null = null;

  sceneIcon(level: AvailableGame): string {
    return SCENE_ICONS[level.sceneType] ?? '⚔';
  }

  /** Pure visual demos suppress the HUD + control overlays. */
  isChromeless(level: AvailableGame | null): boolean {
    return !!level && CHROMELESS_SCENE_TYPES.has(level.sceneType);
  }

  /** Federated render path: the matching <app-...-remote> custom element renders inline. */
  isFederated(level: AvailableGame | null): boolean {
    if (!level) return false;
    // Map sceneType → games-table.slug used in federation_modules.gameSlug.
    const gameSlug = level.sceneType === 'dodecahedron' ? 'dodecahedron' : null;
    return gameSlug !== null && FEDERATED_GAME_SLUGS.has(gameSlug);
  }

  ngOnInit(): void {
    this.levels$ = this.library.listAvailableLevels(this.characterId).pipe(
      map(levels => [...levels].sort((a, b) => a.sortOrder - b.sortOrder)),
      catchError(() => of([]))
    );
    this.characterSvc.load(this.characterId).pipe(catchError(() => of(null))).subscribe();
  }

  /** Called when Godot requests a mid-game level transition via postMessage. */
  onLevelTransition(e: { slug: string; levelId: number }): void {
    // Reload the levels list so unlock state refreshes after the transition.
    this.levels$ = this.library.listAvailableLevels(this.characterId).pipe(
      map(levels => [...levels].sort((a, b) => a.sortOrder - b.sortOrder)),
      catchError(() => of([]))
    );
    // The GameCanvas swaps the iframe src itself; we just track which level is active.
    this.library.getLevel(e.levelId).subscribe(level => {
      this.activeLevel = {
        id: level.id,
        gameId: level.gameId,
        name: level.name,
        description: level.description,
        slug: level.slug,
        sceneType: level.sceneType,
        assetUrl: level.assetUrl,
        thumbnailUrl: level.thumbnailUrl,
        sortOrder: level.sortOrder,
        unlocked: true,
        requiredQuestIds: [],
        completedQuestIds: [],
        totalQuests: 0,
        completedQuests: 0,
      };
    });
  }

  selectLevel(level: AvailableGame): void {
    if (!level.unlocked) return;
    this.activeLevel = level;
    if (this.isFederated(level)) {
      const gameSlug = level.sceneType; // 1:1 today; could route through federation manifest later
      this.activeRemoteSlug = gameSlug;
      this.remoteLoader.loadGameRemote(gameSlug).subscribe({
        next: r => console.log(`[home] federated remote loaded: ${r.slug}`),
        error: e => console.warn('[home] federated remote load failed; falling back to iframe', e),
      });
    } else {
      this.activeRemoteSlug = null;
    }
    setTimeout(() => {
      this.canvasSection?.nativeElement.scrollIntoView({ behavior: 'smooth', block: 'start' });
    }, 50);
  }

  closeLevel(): void {
    if (this.isFullscreen) document.exitFullscreen();
    this.activeLevel = null;
    this.activeRemoteSlug = null;
    this.isFullscreen = false;
  }

  toggleFullscreen(): void {
    if (!this.isFullscreen) {
      this.canvasContainer.nativeElement.requestFullscreen();
    } else {
      document.exitFullscreen();
    }
  }

  @HostListener('document:fullscreenchange')
  onFullscreenChange(): void {
    this.isFullscreen = !!document.fullscreenElement;
  }
}
