import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';

export interface SessionLogEntry {
  sessionId:           string;
  sessionName:         string;
  gameSlug:            string;
  visibility:          'public' | 'friends' | 'private';
  creatorCasdoorId:    string;
  memberCasdoorId:     string;
  characterId:         number | null;
  slotIndex:           number | null;
  wasCreator:          boolean;
  joinedAt:            string;
  leftAt:              string | null;
  sessionClosedAt:     string | null;
  sessionPausedAt:     string | null;
  sessionPauseReason:  string | null;
}

/**
 * Wraps `/api/session-logs/*`. Three call sites:
 * <ul>
 *   <li>{@code me()} — caller's own history.</li>
 *   <li>{@code admin(casdoorId)} — anyone's history (admin only).</li>
 *   <li>{@code scoped(casdoorId)} — anyone's history scoped to the
 *       caller's mod/publisher games.</li>
 * </ul>
 */
@Injectable({ providedIn: 'root' })
export class SessionLogService {
  private http = inject(HttpClient);

  me(limit = 50): Observable<SessionLogEntry[]> {
    return this.http.get<SessionLogEntry[]>('/api/session-logs/me', {
      params: new HttpParams().set('limit', limit),
    });
  }

  admin(casdoorId: string, limit = 100): Observable<SessionLogEntry[]> {
    return this.http.get<SessionLogEntry[]>(`/api/session-logs/admin/${encodeURIComponent(casdoorId)}`, {
      params: new HttpParams().set('limit', limit),
    });
  }

  scoped(casdoorId: string, limit = 100): Observable<SessionLogEntry[]> {
    return this.http.get<SessionLogEntry[]>(`/api/session-logs/scoped/${encodeURIComponent(casdoorId)}`, {
      params: new HttpParams().set('limit', limit),
    });
  }
}
