import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { CharacterState } from '../models/character.model';
import { PlayerSession } from '../models/session.model';
import { AdminStats } from '../models/admin-stats.model';
import { environment } from '../../environments/environment';

@Injectable({ providedIn: 'root' })
export class AdminService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBase}/api/admin`;

  getStats(): Observable<AdminStats> {
    return this.http.get<AdminStats>(`${this.base}/stats`);
  }

  getSessions(): Observable<PlayerSession[]> {
    return this.http.get<PlayerSession[]>(`${this.base}/sessions`);
  }

  kickPlayer(casdoorId: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/sessions/${casdoorId}`);
  }

  getCharacters(): Observable<CharacterState[]> {
    return this.http.get<CharacterState[]>(`${this.base}/characters`);
  }

  updateCharacter(id: number, patch: Partial<CharacterState>): Observable<CharacterState> {
    return this.http.put<CharacterState>(`${this.base}/characters/${id}`, patch);
  }
}
