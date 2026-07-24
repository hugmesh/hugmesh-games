import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { BehaviorSubject, Observable } from 'rxjs';
import { tap } from 'rxjs/operators';
import { CharacterState } from '../models/character.model';
import { GameStateService } from './game-state.service';
import { environment } from '../../environments/environment';

/**
 * Single source of truth for the player's character. Emits on:
 *   • initial /api/characters/{id} GET (load())
 *   • subsequent PUT patches (update())
 *   • inbound SSE `character` events broadcast by the API on stat changes
 *     (spell MP drain, item use, quest reward, etc.)
 *
 * Components binding to `character$` get live updates from any of those
 * paths — no need to poll, and no diverging copies of HP/MP across the HUD.
 */
@Injectable({ providedIn: 'root' })
export class CharacterService {
  private readonly http = inject(HttpClient);
  private readonly gameState = inject(GameStateService);

  private readonly _character$ = new BehaviorSubject<CharacterState | null>(null);
  readonly character$ = this._character$.asObservable();

  constructor() {
    // Forward SSE `character` events into our BehaviorSubject so anything
    // bound to character$ updates in real time (HUD HP/MP bars, dashboard,
    // skill-cost displays, etc.).
    this.gameState.character$.subscribe(c => this._character$.next(c));
  }

  load(id: number = 1): Observable<CharacterState> {
    return this.http.get<CharacterState>(`${environment.apiBase}/api/characters/${id}`).pipe(
      tap(c => this._character$.next(c))
    );
  }

  /**
   * List the authenticated caller's own characters. Backs the home-page
   * character picker: `GET /api/characters` returns only the caller's
   * roster (CharacterController.list matches on `casdoor_id == jwt.sub`;
   * admins get everyone, but a plain player never sees another player's
   * characters). Distinct from {@link load}, which fetches ONE character
   * by id and pushes it onto `character$`; this is a plain list read and
   * deliberately does NOT touch the live `character$` subject.
   */
  listMine(): Observable<CharacterState[]> {
    return this.http.get<CharacterState[]>(`${environment.apiBase}/api/characters`);
  }

  update(id: number, patch: Partial<CharacterState>): Observable<CharacterState> {
    return this.http.put<CharacterState>(`${environment.apiBase}/api/characters/${id}`, patch).pipe(
      tap(c => this._character$.next(c))
    );
  }
}
