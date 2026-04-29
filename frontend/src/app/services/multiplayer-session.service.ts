import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

/** DTO mirror of GameSessionDto.MemberDto on the BFF. */
export interface SessionMember {
  casdoorId: string;
  characterId: number | null;
  slotIndex: number | null;
  creator: boolean;
  joinedAt: string;
}

/** Session visibility tier. {@code public} sessions are listed for any
 *  player; {@code friends} are listed only for the creator's friends;
 *  {@code private} are invite-only (the creator shares the id manually). */
export type SessionVisibility = 'public' | 'friends' | 'private';

/** DTO mirror of GameSessionDto on the BFF. */
export interface GameSession {
  id: string;
  name: string;
  gameSlug: string;
  creatorCasdoorId: string;
  maxPlayers: number;
  activePlayers: number;
  visibility: SessionVisibility;
  createdAt: string;
  closedAt: string | null;
  members: SessionMember[];
}

export interface CreateSessionRequest {
  gameSlug: string;
  name: string;
  maxPlayers?: number;
  visibility?: SessionVisibility;
  characterId?: number;
  slotIndex?: number;
}

export interface JoinSessionRequest {
  characterId?: number;
  slotIndex?: number;
}

/**
 * Wraps `/api/sessions/*` (the multiplayer surface added by the
 * MultiplayerSessionController). Distinct from the legacy
 * `SessionService` which still owns `/api/sessions/connect` for the
 * single-player path.
 *
 * The home-page session picker uses {@link listOpen} + {@link create} +
 * {@link join} to let players pick a session before launching the
 * game; the resulting session id threads through the launch + iframe
 * URL so the BFF's per-session SSE sinks scope the gameplay correctly.
 */
@Injectable({ providedIn: 'root' })
export class MultiplayerSessionService {
  private http = inject(HttpClient);

  listOpen(gameSlug?: string): Observable<GameSession[]> {
    const q = gameSlug ? `?gameSlug=${encodeURIComponent(gameSlug)}` : '';
    return this.http.get<GameSession[]>(`/api/sessions${q}`);
  }

  get(sessionId: string): Observable<GameSession> {
    return this.http.get<GameSession>(`/api/sessions/${sessionId}`);
  }

  create(req: CreateSessionRequest): Observable<GameSession> {
    return this.http.post<GameSession>('/api/sessions', req);
  }

  join(sessionId: string, req: JoinSessionRequest = {}): Observable<GameSession> {
    return this.http.post<GameSession>(`/api/sessions/${sessionId}/join`, req);
  }

  leave(sessionId: string): Observable<void> {
    return this.http.delete<void>(`/api/sessions/${sessionId}/me`);
  }

  close(sessionId: string): Observable<void> {
    return this.http.delete<void>(`/api/sessions/${sessionId}`);
  }
}
