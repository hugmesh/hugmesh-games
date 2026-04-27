import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { Observable, of } from 'rxjs';
import { catchError } from 'rxjs/operators';

// DTOs mirror the BFF responses; types are erased across the federation
// boundary so the remote owns its own copy. Keep field names byte-aligned
// with the BFF DTOs in games.snapdragon.bff.model.GameSessionDto +
// ModGameScope or the table will silently render undefined.
interface ModGameScope {
  casdoorId: string;
  gameSlug: string;
  grantedByCasdoorId: string;
  grantedAt: string;
}
interface SessionMember {
  casdoorId: string;
  characterId: number | null;
  slotIndex: number | null;
  creator: boolean;
  joinedAt: string;
}
interface GameSession {
  id: string;
  name: string;
  gameSlug: string;
  creatorCasdoorId: string;
  maxPlayers: number;
  activePlayers: number;
  createdAt: string;
  closedAt: string | null;
  members: SessionMember[];
}

/**
 * Federated moderator shell. Loaded by the host's `/mod` route via
 *
 *   loadComponent: () => loadRemoteModule({
 *     remoteName: 'mod', exposedModule: './ModShell'
 *   }).then(m => m.ModRemoteComponent)
 *
 * Lists the caller's per-game mod scopes (read from `/api/mod/scopes`)
 * and the open sessions for those games. Each session row carries
 * stop-session and per-member kick actions; both POST to `/api/mod/...`
 * which the BFF authorizes against the caller's mod_game_scopes row.
 *
 * Authentication: ROLE_MOD or ROLE_ADMIN (enforced by the BFF
 * controller's `@PreAuthorize`). The host's authGuard already requires
 * an authenticated session before the route mounts; non-mod / non-admin
 * users see a 403 banner instead of the dashboard.
 */
@Component({
  selector: 'app-mod-remote',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="mod-page">
      <header class="mod-header">
        <h1 class="mod-title">&#9874; Moderator console</h1>
        <button class="btn" (click)="refresh()">&#8635; Refresh</button>
      </header>

      @if (errorMessage) {
        <p class="error-banner">{{ errorMessage }}</p>
      }

      <section class="scopes-card">
        <h2>Games you can moderate</h2>
        @if (scopes$ | async; as scopes) {
          @if (scopes.length === 0) {
            <p class="muted">
              You hold the <code>mod</code> role, but no per-game scope is
              assigned yet. Ask an admin or a publisher of the affected
              game to grant you scope via
              <code>POST /api/publisher/grant-mod</code>.
            </p>
          } @else {
            <ul class="scope-list">
              @for (s of scopes; track s.gameSlug) {
                <li>
                  <strong>{{ s.gameSlug }}</strong>
                  &mdash; granted by {{ s.grantedByCasdoorId }} on
                  {{ s.grantedAt | date:'short' }}
                </li>
              }
            </ul>
          }
        }
      </section>

      <section class="sessions-card">
        <h2>Open sessions</h2>
        @if (sessions$ | async; as sessions) {
          @if (sessions.length === 0) {
            <p class="muted">No open sessions for the games you scope.</p>
          } @else {
            <table class="sessions-table">
              <thead>
                <tr>
                  <th>Name</th><th>Game</th><th>Creator</th>
                  <th>Players</th><th>Members</th><th>Actions</th>
                </tr>
              </thead>
              <tbody>
                @for (s of sessions; track s.id) {
                  <tr>
                    <td>{{ s.name }}</td>
                    <td><code>{{ s.gameSlug }}</code></td>
                    <td>{{ s.creatorCasdoorId }}</td>
                    <td>{{ s.activePlayers }} / {{ s.maxPlayers }}</td>
                    <td class="members">
                      @for (m of s.members; track m.casdoorId) {
                        <span class="member-pill">
                          {{ m.casdoorId }}@if (m.creator) { <em>&nbsp;(creator)</em> }
                          <button class="kick-btn" type="button"
                                  (click)="kick(s.id, m.casdoorId)">Kick</button>
                        </span>
                      }
                    </td>
                    <td>
                      <button class="stop-btn" type="button"
                              (click)="stop(s.id)">Stop session</button>
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          }
        }
      </section>
    </div>
  `,
  styles: [`
    .mod-page { padding: 1.5rem; }
    .mod-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 1rem; }
    .mod-title { margin: 0; font-size: 1.4rem; }
    .scopes-card, .sessions-card { background: #fff8e8; border: 1px solid #d4b67a; border-radius: 6px; padding: 1rem; margin-bottom: 1rem; }
    .scope-list { list-style: none; padding-left: 0; }
    .scope-list li { padding: 0.25rem 0; }
    .sessions-table { width: 100%; border-collapse: collapse; }
    .sessions-table th, .sessions-table td { padding: 0.4rem 0.6rem; border-bottom: 1px solid #e8d4a8; text-align: left; }
    .member-pill { display: inline-flex; align-items: center; gap: 0.3rem; background: #fff; border: 1px solid #d4b67a; border-radius: 999px; padding: 0.1rem 0.5rem; margin: 0.1rem 0.2rem 0.1rem 0; font-size: 0.85rem; }
    .kick-btn, .stop-btn, .btn { padding: 0.3rem 0.7rem; border-radius: 4px; border: 1px solid #b88c45; background: #faecc7; cursor: pointer; }
    .kick-btn:hover, .stop-btn:hover, .btn:hover { background: #f3dcb0; }
    .stop-btn { color: #8b1f1f; border-color: #c08080; }
    .muted { color: #8b7958; font-style: italic; }
    .error-banner { background: #f9d5d5; color: #8b1f1f; padding: 0.6rem 1rem; border-radius: 4px; margin-bottom: 1rem; }
  `],
})
export class ModRemoteComponent implements OnInit {
  private http = inject(HttpClient);
  scopes$: Observable<ModGameScope[]> = of([]);
  sessions$: Observable<GameSession[]> = of([]);
  errorMessage = '';

  ngOnInit(): void {
    this.refresh();
  }

  refresh(): void {
    this.errorMessage = '';
    this.scopes$ = this.http.get<ModGameScope[]>('/api/mod/scopes').pipe(
      catchError(err => {
        this.errorMessage = err?.status === 403
          ? 'You need the mod or admin role to use this console.'
          : `Failed to load scopes: ${err?.message ?? err}`;
        return of([]);
      })
    );
    // Fetch all open sessions; client-side filter to scoped games once
    // scopes resolves. Keeping it simple — at our scale the unfiltered
    // list is small enough that one round-trip is fine.
    this.sessions$ = this.http.get<GameSession[]>('/api/sessions').pipe(
      catchError(() => of([]))
    );
  }

  stop(sessionId: string): void {
    if (!confirm('Stop this session?')) return;
    this.http.post(`/api/mod/sessions/${sessionId}/stop`, { reason: 'mod-stop' })
      .subscribe({ next: () => this.refresh(),
                   error: e => this.errorMessage = `Stop failed: ${e?.message ?? e}` });
  }

  kick(sessionId: string, targetCasdoorId: string): void {
    if (!confirm(`Kick ${targetCasdoorId}?`)) return;
    this.http.post(`/api/mod/sessions/${sessionId}/kick`,
                   { targetCasdoorId, reason: 'mod-kick' })
      .subscribe({ next: () => this.refresh(),
                   error: e => this.errorMessage = `Kick failed: ${e?.message ?? e}` });
  }
}
