import {
  Component, CUSTOM_ELEMENTS_SCHEMA, ElementRef, HostListener, OnInit, ViewChild, inject
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { GameLibraryService } from '../services/game-library.service';
import { CharacterService } from '../services/character.service';
import { RemoteLoaderService } from '../services/remote-loader.service';
import { SessionService } from '../services/session.service';
import { AvailableGame, GameWithChapters } from '../models/available-game.model';
import { GameCanvasComponent } from '../game-canvas/game-canvas.component';
import { GameHudComponent } from '../game-hud/game-hud.component';
import { GameControlsComponent } from '../game-controls/game-controls.component';
import { Observable, of } from 'rxjs';
import { catchError, map, switchMap } from 'rxjs/operators';

/** Minted by `POST /api/games/{slug}/launch` — see GameLaunchController. */
interface LaunchResponse {
  uid: string;
  expiresAt: string;
  gameSlug: string;
}

const SCENE_ICONS: Record<string, string> = {
  cave:         '🏔',
  field:        '🌿',
  dodecahedron: '⬡',
};

const GAME_ICONS: Record<string, string> = {
  'three-wizards': '🧙',
  'dodecahedron':  '⬡',
};

// Scene types that are pure visual demos — no character, no HUD, no
// movement controls overlay. Add new chrome-less games here.
const CHROMELESS_SCENE_TYPES = new Set(['dodecahedron']);

// Maps chapter.sceneType → federation_modules.gameSlug. Cave + field
// both belong to the Three Wizards umbrella game, so they share the
// 'three-wizards' remote bundle. Chapters whose sceneType isn't a key
// here stay on the legacy <app-game-canvas> iframe path.
const SCENE_TYPE_TO_GAME_SLUG: Record<string, string> = {
  cave:         'three-wizards',
  field:        'three-wizards',
  dodecahedron: 'dodecahedron',
};

// Game slugs whose chapter rendering is delegated to a federated remote
// module instead of the legacy iframe. The host loads the remote's
// bundle via RemoteLoaderService when the player picks the chapter.
const FEDERATED_GAME_SLUGS = new Set(['dodecahedron', 'three-wizards']);

@Component({
  selector: 'app-home',
  standalone: true,
  imports: [CommonModule, GameCanvasComponent, GameHudComponent, GameControlsComponent],
  // Federated remote elements (e.g. <app-dodecahedron-remote>) are
  // bootstrapped by their own bundle, not Angular's host compiler.
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
  private readonly http = inject(HttpClient);

  characterId = 1;
  /** Game-level cards on the home page. Each carries its embedded
   *  chapters; click → modal shows the chapter list for that game. */
  games$: Observable<GameWithChapters[]> = of([]);
  activeLevel: AvailableGame | null = null;
  /** Game whose chapter modal is open. Null when no modal is shown. */
  modalGame: GameWithChapters | null = null;
  isFullscreen = false;
  activeRemoteSlug: string | null = null;
  activeLaunchUid: string | null = null;

  sceneIcon(chapter: AvailableGame): string {
    return SCENE_ICONS[chapter.sceneType] ?? '⚔';
  }

  gameIcon(game: GameWithChapters): string {
    return GAME_ICONS[game.slug] ?? '🎮';
  }

  /** Game-level summary: how many of the embedded chapters are unlocked. */
  unlockedChapterCount(game: GameWithChapters): number {
    return game.chapters.filter(c => c.unlocked).length;
  }

  /** Pure visual demos suppress the HUD + control overlays. */
  isChromeless(chapter: AvailableGame | null): boolean {
    return !!chapter && CHROMELESS_SCENE_TYPES.has(chapter.sceneType);
  }

  isFederated(chapter: AvailableGame | null): boolean {
    if (!chapter) return false;
    const gameSlug = SCENE_TYPE_TO_GAME_SLUG[chapter.sceneType];
    return !!gameSlug && FEDERATED_GAME_SLUGS.has(gameSlug);
  }

  ngOnInit(): void {
    this.games$ = this.library.listAvailable(this.characterId).pipe(
      map(games => [...games].sort((a, b) => a.sortOrder - b.sortOrder)),
      catchError(() => of([]))
    );
    this.characterSvc.load(this.characterId).pipe(catchError(() => of(null))).subscribe();

    this.session.connect(this.characterId).pipe(
      switchMap(r => r?.isController ? of(r) : this.session.claimControl()),
      catchError(() => of(null)),
    ).subscribe();
  }

  /** Open the chapter modal for the clicked game. Disabled for
   *  games with zero unlocked chapters. */
  openGame(game: GameWithChapters): void {
    if (this.unlockedChapterCount(game) === 0) return;
    this.modalGame = game;
  }

  closeModal(): void {
    this.modalGame = null;
  }

  /** Modal click outside the inner card closes the modal. */
  onModalBackdrop(ev: MouseEvent): void {
    if (ev.target === ev.currentTarget) this.closeModal();
  }

  @HostListener('document:keydown.escape')
  onEscape(): void {
    if (this.modalGame) this.closeModal();
  }

  @HostListener('window:message', ['$event'])
  onWindowMessage(ev: MessageEvent): void {
    const data = ev.data as { type?: string; slug?: string; chapterId?: number } | null;
    if (!data || data.type !== 'TW_LEVEL_TRANSITION') return;
    if (typeof data.slug !== 'string' || typeof data.chapterId !== 'number') return;
    this.onLevelTransition({ slug: data.slug, chapterId: data.chapterId });
  }

  onLevelTransition(e: { slug: string; chapterId: number }): void {
    this.games$ = this.library.listAvailable(this.characterId).pipe(
      map(games => [...games].sort((a, b) => a.sortOrder - b.sortOrder)),
      catchError(() => of([]))
    );
    this.library.getLevel(e.chapterId).subscribe(chapter => {
      this.activeLevel = {
        id: chapter.id,
        gameId: chapter.gameId,
        name: chapter.name,
        description: chapter.description,
        slug: chapter.slug,
        sceneType: chapter.sceneType,
        assetUrl: chapter.assetUrl,
        thumbnailUrl: chapter.thumbnailUrl,
        sortOrder: chapter.sortOrder,
        unlocked: true,
        requiredQuestIds: [],
        completedQuestIds: [],
        totalQuests: 0,
        completedQuests: 0,
      };
    });
  }

  /** Launch the chosen chapter. Closes the modal in the process. */
  selectChapter(chapter: AvailableGame): void {
    if (!chapter.unlocked) return;
    this.modalGame = null;
    this.activeLevel = chapter;
    this.activeLaunchUid = null;
    if (this.isFederated(chapter)) {
      const gameSlug = SCENE_TYPE_TO_GAME_SLUG[chapter.sceneType];
      this.activeRemoteSlug = gameSlug;
      this.remoteLoader.loadGameRemote(gameSlug).subscribe({
        next: r => console.log(`[home] federated remote loaded: ${r.slug}`),
        error: e => console.warn('[home] federated remote load failed; falling back to iframe', e),
      });
      this.http.post<LaunchResponse>(`/api/games/${gameSlug}/launch`, {})
        .subscribe({
          next: r => {
            this.activeLaunchUid = r.uid;
            console.log(`[home] launch minted uid=${r.uid.substring(0, 8)}… expires=${r.expiresAt}`);
          },
          error: e => console.warn('[home] /launch failed; continuing without uid', e),
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
    if (this.activeLaunchUid) {
      const uid = this.activeLaunchUid;
      this.http.delete<void>(`/api/games/launches/${uid}`).pipe(
        catchError(() => of(null)),
      ).subscribe();
    }
    this.activeLevel = null;
    this.activeRemoteSlug = null;
    this.activeLaunchUid = null;
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
