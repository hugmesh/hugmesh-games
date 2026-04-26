import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, map } from 'rxjs';
import { GameListing } from '../models/game-listing.model';
import { Level } from '../models/level.model';
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

  /** All levels across all games. */
  listLevels(): Observable<Level[]> {
    return this.http.get<Level[]>(`${this.base}/api/levels`);
  }

  /** Levels belonging to one umbrella game. */
  listLevelsByGame(gameId: number): Observable<Level[]> {
    return this.http.get<Level[]>(`${this.base}/api/games/${gameId}/levels`);
  }

  /** One level (WASM bundle metadata). */
  getLevel(id: number): Observable<Level> {
    return this.http.get<Level>(`${this.base}/api/chapters/${id}`);
  }

  /** Games with nested unlocked levels for a character. */
  listAvailable(characterId: number): Observable<GameWithChapters[]> {
    return this.http.get<GameWithChapters[]>(
      `${this.base}/api/games/available/${characterId}`
    );
  }

  /** Convenience: flatten available games → flat list of levels (used by home view). */
  listAvailableLevels(characterId: number): Observable<AvailableGame[]> {
    return this.listAvailable(characterId).pipe(
      map(games => games.flatMap(g => g.levels))
    );
  }
}
