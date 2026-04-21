import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { GameListing } from '../models/game-listing.model';
import { environment } from '../../environments/environment';

@Injectable({ providedIn: 'root' })
export class GameLibraryService {
  private readonly http = inject(HttpClient);

  listGames(): Observable<GameListing[]> {
    return this.http.get<GameListing[]>(`${environment.apiBase}/api/games`);
  }

  getGame(id: number): Observable<GameListing> {
    return this.http.get<GameListing>(`${environment.apiBase}/api/games/${id}`);
  }
}
