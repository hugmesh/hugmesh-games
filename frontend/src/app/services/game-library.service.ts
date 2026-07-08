import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, map } from 'rxjs';
import { GameListing } from '../models/game-listing.model';
import { Chapter } from '../models/chapter.model';
import { AvailableGame, GameWithChapters } from '../models/available-game.model';
import { environment } from '../../environments/environment';

@Injectable({ providedIn: 'root' })
export class GameLibraryService {
  private readonly http = inject(HttpClient);
  private readonly base = environment.apiBase;

  /** Umbrella games. */
  listGames(): Observable<GameListing[]> {
    return this.http.get<GameListing[]>(`${this.base}/api/games`);
  }

  getGame(id: number): Observable<GameListing> {
    return this.http.get<GameListing>(`${this.base}/api/games/${id}`);
  }

  /** All chapters across all games. */
  listChapters(): Observable<Chapter[]> {
    return this.http.get<Chapter[]>(`${this.base}/api/chapters`);
  }

  /** Chapters belonging to one umbrella game. */
  listChaptersByGame(gameId: number): Observable<Chapter[]> {
    return this.http.get<Chapter[]>(`${this.base}/api/games/${gameId}/chapters`);
  }

  /** One chapter (WASM bundle metadata). */
  getChapter(id: number): Observable<Chapter> {
    return this.http.get<Chapter>(`${this.base}/api/chapters/${id}`);
  }

  /** Games with nested unlocked levels for a character. */
  listAvailable(characterId: number): Observable<GameWithChapters[]> {
    return this.http.get<GameWithChapters[]>(
      `${this.base}/api/games/available/${characterId}`
    );
  }

  /** Convenience: flatten available games → flat list of chapters
   *  (used by older flat-list views; the new home view uses
   *  {@link listAvailable} directly to render game cards then a
   *  per-game chapter modal). */
  listAvailableLevels(characterId: number): Observable<AvailableGame[]> {
    return this.listAvailable(characterId).pipe(
      map(games => games.flatMap(g => g.chapters))
    );
  }
}
