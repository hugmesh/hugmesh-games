import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, TimeoutError, catchError, map, of, throwError, timeout } from 'rxjs';

// ── DTOs — the games BFF's own shapes (backend/src/main/java/games/hugmesh/bff) ──

/** `games` table row (GET /api/games). */
export interface Game {
  id: number;
  name: string;
  description: string | null;
  slug: string;
  thumbnailUrl: string | null;
  sortOrder: number;
  createdAt?: string | null;
  currentVersionDir?: string | null;
  domain?: string | null;
}

/** One `federation_modules` row (GET /api/federation/manifest). */
export interface FederationRemote {
  slug: string;
  displayName: string | null;
  kind: string;
  remoteEntryUrl: string | null;
  exposedModule: string | null;
  gameSlug: string | null;
  version: string | null;
  metadata: string | null;
}

export interface FederationManifest {
  generatedAt: string;
  remotes: FederationRemote[];
}

/** POST /api/games/{slug}/launch -> GameLaunchController.LaunchResponse. */
export interface LaunchResponse {
  uid: string;
  expiresAt: string;
  gameSlug: string;
}

/** `game_launches` row (GET /api/games/launches — admin-gated on the BFF). */
export interface GameLaunch {
  uid: string;
  gameSlug: string;
  casdoorUserId?: string | null;
  startedAt?: string | null;
  expiresAt: string;
  terminatedAt?: string | null;
}

/** AdminStatsDto — rendered generically (label/value), nothing invented. */
export interface AdminStats {
  totalUsers: number;
  totalSessions: number;
  currentlyActive: number;
  averageSessionMinutes: number;
  totalCharacters: number;
  perUser: PerUserStats[];
}

export interface PerUserStats {
  casdoorId: string;
  characterName: string | null;
  sessionCount: number;
  totalMinutes: number;
  online: boolean;
}

/** SessionDto — one `player_sessions` row (GET /api/admin/sessions). Note: the
 *  DTO carries no game field; the table shows what the BFF exposes. */
export interface PlayerSession {
  id: number;
  casdoorId: string;
  characterId: number | null;
  isController: boolean;
  connectedAt: string | null;
  lastPing: string | null;
}

/** GameSessionDto — one multiplayer session (oversight list, pause/resume). */
export interface GameSession {
  id: string;
  name: string | null;
  gameSlug: string;
  creatorCasdoorId: string;
  maxPlayers: number;
  activePlayers: number;
  visibility: string;
  createdAt: string | null;
  closedAt: string | null;
  pausedAt: string | null;
  pausedByCasdoorId: string | null;
  pauseReason: string | null;
  members: SessionMember[];
}

export interface SessionMember {
  casdoorId: string;
  characterId: number | null;
  slotIndex: number | null;
  creator: boolean;
  joinedAt: string | null;
}

/** SessionLogEntry — one member-of-session row (GET /api/session-logs/admin/{id}). */
export interface SessionLogEntry {
  sessionId: string;
  sessionName: string | null;
  gameSlug: string;
  visibility: string | null;
  creatorCasdoorId: string | null;
  memberCasdoorId: string;
  characterId: number | null;
  slotIndex: number | null;
  wasCreator: boolean;
  joinedAt: string | null;
  leftAt: string | null;
  sessionClosedAt: string | null;
  sessionPausedAt: string | null;
  sessionPauseReason: string | null;
}

/** `game_access` row (GET /api/admin/games/{slug}/access). */
export interface GameAccessRule {
  id: number;
  gameId: number;
  scope: 'all' | 'role' | 'user' | string;
  scopeValue: string | null;
  grantedBy: string | null;
  grantedAt: string | null;
}

/** RoleProxyController.RoleSummary (GET /api/admin/roles). */
export interface RoleSummary {
  name: string;
  displayName: string | null;
  description: string | null;
  enabled: boolean;
}

export interface DebugModeStatus {
  wasmConsoleEnabled: boolean;
}

/**
 * How a failed call is presented. `forbidden` = the gateway or BFF refused the
 * caller (401/403); `unreachable` = the games service did not answer (network,
 * 5xx, or the fe route not resolving because the games profile is down);
 * `notFound` = 404; `other` = any other non-2xx.
 */
export type ApiFailureKind = 'forbidden' | 'unreachable' | 'notFound' | 'other';

export interface ApiFailure {
  kind: ApiFailureKind;
  status: number;
  /** X-Route-Denied / X-Resource-Denied header value when the gateway sent one. */
  denied: string | null;
  message: string;
}

/** Classify an HttpClient error into the state the screens render. */
export function classifyFailure(err: unknown): ApiFailure {
  if (err instanceof HttpErrorResponse) {
    const denied =
      err.headers?.get('X-Route-Denied') ?? err.headers?.get('X-Resource-Denied') ?? null;
    if (err.status === 401 || err.status === 403) {
      return { kind: 'forbidden', status: err.status, denied, message: err.message };
    }
    if (err.status === 404) {
      return { kind: 'notFound', status: 404, denied, message: err.message };
    }
    if (err.status === 0 || err.status >= 500) {
      return { kind: 'unreachable', status: err.status, denied, message: err.message };
    }
    return { kind: 'other', status: err.status, denied, message: err.message };
  }
  return { kind: 'unreachable', status: 0, denied: null, message: String(err) };
}

/**
 * The ONE place this remote talks to the games BFF. Every path is absolute and
 * same-origin — `/games/api/...` — so it resolves identically standalone and
 * under the host shell (a relative URL would resolve against the document and
 * miss the mount). The fe-gateway route `games-bff` (Path=/games/api/**,
 * StripPrefix=1) forwards it to the BFF as /api/..., turning the httpOnly
 * bff_session cookie into the systems-Casdoor bearer on the way. The remote
 * never calls bare /api/*.
 */
@Injectable({ providedIn: 'root' })
export class GamesApiService {
  static readonly BASE = '/games/api';

  private readonly http = inject(HttpClient);

  private url(path: string): string {
    return `${GamesApiService.BASE}${path}`;
  }

  // ── member ───────────────────────────────────────────────────────────────
  listGames(): Observable<Game[]> {
    return this.http.get<Game[]>(this.url('/games'), { withCredentials: true });
  }

  federationManifest(): Observable<FederationManifest> {
    return this.http.get<FederationManifest>(this.url('/federation/manifest'), {
      withCredentials: true,
    });
  }

  launch(slug: string): Observable<LaunchResponse> {
    return this.http.post<LaunchResponse>(
      this.url(`/games/${encodeURIComponent(slug)}/launch`),
      null,
      { withCredentials: true },
    );
  }

  /** NOTE: `GET /api/games/launches` is `hasRole('ADMIN')` on the BFF today, so a
   *  games-only member receives 403; the catalog hides the strip on that. */
  listLaunches(): Observable<GameLaunch[]> {
    return this.http.get<GameLaunch[]>(this.url('/games/launches'), { withCredentials: true });
  }

  terminateLaunch(uid: string): Observable<void> {
    return this.http.delete<void>(this.url(`/games/launches/${encodeURIComponent(uid)}`), {
      withCredentials: true,
    });
  }

  /** Probe the bundle document so a gateway refusal (403 + X-Resource-Denied)
   *  is surfaced as text instead of a blank frame. Returns the raw response so
   *  the caller can read headers. */
  probeBundle(url: string): Observable<unknown> {
    return this.http.get(url, {
      withCredentials: true,
      responseType: 'text',
      observe: 'response',
    });
  }

  // ── admin ────────────────────────────────────────────────────────────────
  adminStats(): Observable<AdminStats> {
    return this.http.get<AdminStats>(this.url('/admin/stats'), { withCredentials: true });
  }

  adminSessions(): Observable<PlayerSession[]> {
    return this.http.get<PlayerSession[]>(this.url('/admin/sessions'), { withCredentials: true });
  }

  endPlayerSession(casdoorId: string): Observable<void> {
    return this.http.delete<void>(
      this.url(`/admin/sessions/${encodeURIComponent(casdoorId)}`),
      { withCredentials: true },
    );
  }

  multiplayerSessions(gameSlug?: string | null): Observable<GameSession[]> {
    let params = new HttpParams();
    if (gameSlug) params = params.set('gameSlug', gameSlug);
    return this.http.get<GameSession[]>(this.url('/admin/multiplayer-sessions'), {
      params,
      withCredentials: true,
    });
  }

  pauseSession(id: string, reason?: string | null): Observable<GameSession> {
    return this.http.post<GameSession>(
      this.url(`/sessions/${encodeURIComponent(id)}/pause`),
      reason ? { reason } : null,
      { withCredentials: true },
    );
  }

  resumeSession(id: string): Observable<GameSession> {
    return this.http.post<GameSession>(
      this.url(`/sessions/${encodeURIComponent(id)}/resume`),
      null,
      { withCredentials: true },
    );
  }

  recoverRealtime(sessionId: string): Observable<void> {
    return this.http.post<void>(
      this.url(`/admin/realtime/${encodeURIComponent(sessionId)}/recover`),
      null,
      { withCredentials: true },
    );
  }

  /** Absolute same-origin SSE URL for one session's merged event stream. */
  sessionStreamUrl(sessionId: string): string {
    return this.url(`/admin/sessions/${encodeURIComponent(sessionId)}/stream`);
  }

  /**
   * Authorisation probe for the SSE stream. EventSource only ever fires a
   * generic `error`, so a gateway 401/403 (with X-Route-Denied) is invisible to
   * it; this GET surfaces that status before the long-lived connection opens.
   * The stream never completes, so a short timeout is the SUCCESS signal
   * (bytes were admitted) and is mapped to a completed observable; the
   * aborted fetch closes the probe connection.
   */
  probeStream(sessionId: string, timeoutMs = 1500): Observable<void> {
    return this.http
      .get(this.sessionStreamUrl(sessionId), {
        withCredentials: true,
        responseType: 'text',
        observe: 'response',
        headers: { Accept: 'text/event-stream' },
      })
      .pipe(
        timeout(timeoutMs),
        map(() => undefined),
        catchError((err) => (err instanceof TimeoutError ? of(undefined) : throwError(() => err))),
      );
  }

  /**
   * Open the per-session SSE stream. Indirected through this method (not a
   * bare `new EventSource` in the component) so specs can stub it and never
   * open a real connection. `withCredentials` carries the bff_session cookie.
   */
  openSessionStream(sessionId: string): EventSource {
    return new EventSource(this.sessionStreamUrl(sessionId), { withCredentials: true });
  }

  sessionLogs(casdoorId: string): Observable<SessionLogEntry[]> {
    return this.http.get<SessionLogEntry[]>(
      this.url(`/session-logs/admin/${encodeURIComponent(casdoorId)}`),
      { withCredentials: true },
    );
  }

  gameAccess(slug: string): Observable<GameAccessRule[]> {
    return this.http.get<GameAccessRule[]>(
      this.url(`/admin/games/${encodeURIComponent(slug)}/access`),
      { withCredentials: true },
    );
  }

  grantGameAccess(slug: string, scope: string, value: string | null): Observable<GameAccessRule> {
    return this.http.post<GameAccessRule>(
      this.url(`/admin/games/${encodeURIComponent(slug)}/access`),
      { scope, value },
      { withCredentials: true },
    );
  }

  /** The BFF's revoke takes `?scope=&value=` query params (not a body). */
  revokeGameAccess(slug: string, scope: string, value: string | null): Observable<void> {
    let params = new HttpParams().set('scope', scope);
    if (value) params = params.set('value', value);
    return this.http.delete<void>(this.url(`/admin/games/${encodeURIComponent(slug)}/access`), {
      params,
      withCredentials: true,
    });
  }

  roles(): Observable<RoleSummary[]> {
    return this.http.get<RoleSummary[]>(this.url('/admin/roles'), { withCredentials: true });
  }

  debugMode(): Observable<DebugModeStatus> {
    return this.http.get<DebugModeStatus>(this.url('/admin/debug-mode'), { withCredentials: true });
  }

  setWasmConsole(enabled: boolean): Observable<DebugModeStatus> {
    return this.http.put<DebugModeStatus>(
      this.url('/admin/debug-mode/wasm-console'),
      { enabled },
      { withCredentials: true },
    );
  }
}
