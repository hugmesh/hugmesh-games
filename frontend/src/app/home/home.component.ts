import {
  Component, CUSTOM_ELEMENTS_SCHEMA, ElementRef, HostListener, OnInit, ViewChild, inject
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { GameLibraryService } from '../services/game-library.service';
import { CharacterService } from '../services/character.service';
import { RemoteLoaderService } from '../services/remote-loader.service';
import { SessionService } from '../services/session.service';
import { AvailableGame } from '../models/available-game.model';
import { GameCanvasComponent } from '../game-canvas/game-canvas.component';
import { GameHudComponent } from '../game-hud/game-hud.component';
import { GameControlsComponent } from '../game-controls/game-controls.component';
import { Observable, of } from 'rxjs';
import { catchError, map, switchMap } from 'rxjs/operators';

const SCENE_ICONS: Record<string, string> = {
  cave:         '🏔',
  field:        '🌿',
  dodecahedron: '⬡',
};

// Scene types that are pure visual demos — no character, no HUD, no
// movement controls overlay. Add new chrome-less games here.
const CHROMELESS_SCENE_TYPES = new Set(['dodecahedron']);

// Maps level.sceneType → federation_modules.gameSlug. Cave + field
// both belong to the Three Wizards umbrella game, so they share the
// 'three-wizards' remote bundle. Levels whose sceneType isn't a key
// here stay on the legacy <app-game-canvas> iframe path.
const SCENE_TYPE_TO_GAME_SLUG: Record<string, string> = {
  cave:         'three-wizards',
  field:        'three-wizards',
  dodecahedron: 'dodecahedron',
};

// Game slugs whose level rendering is delegated to a federated remote
// module instead of the legacy iframe. The host loads the remote's
// bundle via RemoteLoaderService when the player picks the card.
const FEDERATED_GAME_SLUGS = new Set(['dodecahedron', 'three-wizards']);

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
  private readonly session = inject(SessionService);

  characterId = 1;
  levels$: Observable<AvailableGame[]> = of([]);
  activeLevel: AvailableGame | null = null;
  isFullscreen = false;
  /** Set when the chosen level is served by a federated remote. Maps
   *  sceneType → federation gameSlug via SCENE_TYPE_TO_GAME_SLUG. */
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
    const gameSlug = SCENE_TYPE_TO_GAME_SLUG[level.sceneType];
    return !!gameSlug && FEDERATED_GAME_SLUGS.has(gameSlug);
  }

  ngOnInit(): void {
    this.levels$ = this.library.listAvailableLevels(this.characterId).pipe(
      map(levels => [...levels].sort((a, b) => a.sortOrder - b.sortOrder)),
      catchError(() => of([]))
    );
    this.characterSvc.load(this.characterId).pipe(catchError(() => of(null))).subscribe();

    // Auto-claim the controller role when the player lands on home —
    // every /api/controls/* POST carries X-Casdoor-Id and the server
    // rejects it with 403 unless that user holds the role. This lived
    // in GameCanvasComponent.ngOnInit before, but with the federated
    // three-wizards remote replacing GameCanvas for cave + field the
    // session bind has to happen at the level above the renderer fork
    // so it fires regardless of which path mounts.
    this.session.connect(this.characterId).pipe(
      switchMap(r => r?.isController ? of(r) : this.session.claimControl()),
      catchError(() => of(null)),
    ).subscribe();
  }

  /**
   * Window-level listener for level transitions. Godot's game_bridge.gd
   * posts {type:'TW_LEVEL_TRANSITION',slug,levelId} via
   * window.parent.postMessage; the message bubbles to the top window
   * regardless of whether the iframe is rendered directly by
   * GameCanvasComponent or nested inside a federated remote (they're
   * sibling frames at the window level), so catching it here decouples
   * transition handling from the renderer.
   */
  @HostListener('window:message', ['$event'])
  onWindowMessage(ev: MessageEvent): void {
    const data = ev.data as { type?: string; slug?: string; levelId?: number } | null;
    if (!data || data.type !== 'TW_LEVEL_TRANSITION') return;
    if (typeof data.slug !== 'string' || typeof data.levelId !== 'number') return;
    this.onLevelTransition({ slug: data.slug, levelId: data.levelId });
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
      // sceneType keys SCENE_TYPE_TO_GAME_SLUG which yields the
      // federation_modules.gameSlug — for cave/field that's
      // 'three-wizards', for dodecahedron it's 'dodecahedron'.
      const gameSlug = SCENE_TYPE_TO_GAME_SLUG[level.sceneType];
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
