import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { BehaviorSubject, Observable, of } from 'rxjs';
import { catchError, tap } from 'rxjs/operators';
import { environment } from '../../environments/environment';

export interface ConnectResponse {
  sessionToken: string;
  isController: boolean;
  activePlayers: number;
}

/**
 * Wraps the session lifecycle. The backend's ControlController rejects controls
 * with 403 when the JWT's subject isn't the holder of the controller role, so
 * every player who wants to send input must first call connect() — and the
 * first player to connect becomes the controller automatically.
 *
 * The game-canvas component auto-connects on mount so solo play "just works".
 */
@Injectable({ providedIn: 'root' })
export class SessionService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBase}/api/sessions`;

  /** Live "am I the controller" flag that the HUD / controls read from. */
  readonly isController$ = new BehaviorSubject<boolean>(false);

  connect(characterId: number = 1): Observable<ConnectResponse | null> {
    return this.http.post<ConnectResponse>(
      `${this.base}/connect`,
      null,
      { params: new HttpParams().set('characterId', String(characterId)) },
    ).pipe(
      tap(r => this.isController$.next(r.isController)),
      catchError(err => {
        console.warn('[session] connect failed', err?.status);
        return of(null);
      }),
    );
  }

  claimControl(): Observable<{ granted: boolean } | null> {
    return this.http.post<{ granted: boolean }>(`${this.base}/control`, null).pipe(
      tap(r => { if (r.granted) this.isController$.next(true); }),
      catchError(() => of(null)),
    );
  }

  disconnect(): Observable<void> {
    return this.http.delete<void>(`${this.base}/me`).pipe(
      tap(() => this.isController$.next(false)),
      catchError(() => of(undefined as unknown as void)),
    );
  }
}
