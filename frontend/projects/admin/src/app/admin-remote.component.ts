import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { RouterLink } from '@angular/router';
import { Observable, of } from 'rxjs';
import { catchError } from 'rxjs/operators';

// Type shapes mirror the host's admin-stats.model + session.model. They
// describe the BFF JSON, not host runtime classes — federation can't
// share interfaces (TypeScript types are erased at runtime), so each
// side owns its own. Keep field names byte-for-byte aligned with the
// host's models or the table will silently render undefined.
interface PerUserStats {
  casdoorId: string;
  characterName: string | null;
  sessionCount: number;
  totalMinutes: number;
  online: boolean;
}
interface AdminStats {
  totalUsers: number;
  totalSessions: number;
  currentlyActive: number;
  averageSessionMinutes: number;
  totalCharacters: number;
  perUser: PerUserStats[];
}
interface PlayerSession {
  id: number;
  characterId: number;
  casdoorId: string;
  lastActiveAt: string;
  isController: boolean;
}

/**
 * Federated admin shell. Loaded by the host's `/admin` route via
 *
 *   loadComponent: () => loadRemoteModule({ remoteName: 'admin',
 *                          exposedModule: './AdminShell' })
 *                          .then(m => m.AdminRemoteComponent)
 *
 * HttpClient + Router are shared via federation.config.js's shareAll;
 * AdminService isn't shared, so the calls it wrapped (/api/admin/stats,
 * /sessions, DELETE /sessions/{casdoorId}) are inlined here. The host's
 * Bearer-JWT interceptor still wraps these requests because HttpClient
 * is the singleton root-injector instance (shareAll, singleton: true).
 *
 * The 403 case carries the same "log in with an isAdmin account" hint
 * the host had — same message keeps the e2e/QA story identical.
 */
@Component({
  selector: 'app-admin-remote',
  standalone: true,
  imports: [CommonModule, RouterLink],
  template: `
    <div class="admin-page">
      <header class="admin-header">
        <div>
          <h1 class="admin-title">&#9878; Admin Panel</h1>
          <span class="admin-subtitle">
            Server management — federated remote · <code>admin</code>
          </span>
        </div>
        <button class="btn" (click)="refresh()">&#8635; Refresh</button>
      </header>

      @if (errorMessage) {
        <p class="error-banner" role="alert" aria-live="assertive">{{ errorMessage }}</p>
      }

      @if (stats$ | async; as stats) {
        <section class="stats-grid">
          <div class="stat-card">
            <span class="stat-label">Total users</span>
            <span class="stat-value">{{ stats.totalUsers }}</span>
          </div>
          <div class="stat-card">
            <span class="stat-label">Total sessions</span>
            <span class="stat-value">{{ stats.totalSessions }}</span>
          </div>
          <div class="stat-card stat-card--accent">
            <span class="stat-label">Currently active</span>
            <span class="stat-value">{{ stats.currentlyActive }}</span>
          </div>
          <div class="stat-card">
            <span class="stat-label">Avg session (min)</span>
            <span class="stat-value">{{ stats.averageSessionMinutes }}</span>
          </div>
          <div class="stat-card">
            <span class="stat-label">Characters</span>
            <span class="stat-value">{{ stats.totalCharacters }}</span>
          </div>
        </section>

        <section class="admin-section">
          <h2 class="section-title">Per-user usage</h2>
          <div class="table-wrapper">
            <table class="data-table">
              <thead>
                <tr>
                  <th>Character</th><th>Casdoor ID</th><th>Sessions</th>
                  <th>Total minutes</th><th>Status</th><th></th>
                </tr>
              </thead>
              <tbody>
                @for (u of stats.perUser; track u.casdoorId) {
                  <tr>
                    <td>{{ u.characterName || '—' }}</td>
                    <td class="td-mono">{{ u.casdoorId }}</td>
                    <td>{{ u.sessionCount }}</td>
                    <td>{{ u.totalMinutes }}</td>
                    <td>
                      <span class="badge"
                            [class.badge--yes]="u.online"
                            [class.badge--no]="!u.online">
                        {{ u.online ? 'Online' : 'Offline' }}
                      </span>
                    </td>
                    <td>
                      @if (u.online) {
                        <button class="btn btn-sm btn-danger" (click)="kick(u.casdoorId)">Kick</button>
                      }
                    </td>
                  </tr>
                } @empty {
                  <tr><td colspan="6" class="td-empty">No user activity yet.</td></tr>
                }
              </tbody>
            </table>
          </div>
        </section>
      }

      <section class="admin-section">
        <h2 class="section-title">Active sessions right now</h2>
        <div class="table-wrapper">
          <table class="data-table">
            <thead>
              <tr>
                <th>ID</th><th>Character</th><th>Casdoor ID</th>
                <th>Last active</th><th>Controller</th>
              </tr>
            </thead>
            <tbody>
              @for (s of (sessions$ | async); track s.id) {
                <tr>
                  <td class="td-id">{{ s.id }}</td>
                  <td>{{ s.characterId }}</td>
                  <td class="td-mono">{{ s.casdoorId }}</td>
                  <td class="td-mono">{{ s.lastActiveAt }}</td>
                  <td>
                    <span class="badge"
                          [class.badge--yes]="s.isController"
                          [class.badge--no]="!s.isController">
                      {{ s.isController ? 'Yes' : 'No' }}
                    </span>
                  </td>
                </tr>
              } @empty {
                <tr><td colspan="5" class="td-empty">No active sessions.</td></tr>
              }
            </tbody>
          </table>
        </div>
      </section>

      <p class="admin-nav-back">
        <a routerLink="/">← Back to game</a> &nbsp;·&nbsp;
        <a routerLink="/dashboard">Player dashboard →</a>
      </p>
    </div>
  `,
  styles: [`
    :host { display: block; background: #0a0a12; min-height: 100vh; color: #e8e8f0; }
    .admin-page { min-height: 100vh; background: #0a0a12; padding-bottom: 48px; }
    .admin-header { padding: 32px 40px 24px; border-bottom: 1px solid #1e1e32;
                    background: rgba(18,18,30,0.8);
                    display: flex; align-items: baseline; gap: 16px; justify-content: space-between; }
    .admin-header > div { display: flex; flex-direction: column; gap: 2px; }
    .admin-title { font-size: 1.5rem; font-weight: 800; margin: 0;
                   background: linear-gradient(135deg, #7c4dff 0%, #b388ff 100%);
                   -webkit-background-clip: text; -webkit-text-fill-color: transparent;
                   background-clip: text; }
    .admin-subtitle { font-size: 0.82rem; color: #7070a0; }
    .admin-subtitle code { background: rgba(255,255,255,0.05); padding: 1px 5px;
                            border-radius: 3px; font-size: 0.78rem; }

    .btn { background: #12121e; border: 1px solid #1e1e32; color: #e8e8f0;
           border-radius: 6px; padding: 8px 18px; font-size: 0.85rem;
           font-weight: 600; cursor: pointer;
           transition: border-color 0.15s, background 0.15s; }
    .btn:hover { border-color: #7c4dff; background: rgba(124,77,255,0.08); }
    .btn-sm { padding: 5px 12px; font-size: 0.78rem; }
    .btn-danger { background: #ff6b6b; border-color: #ff6b6b; color: #fff; }
    .btn-danger:hover { background: #ff8080; }

    .stats-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
                  gap: 16px; padding: 24px 40px 0; }
    .stat-card { background: #12121e; border: 1px solid #1e1e32; border-radius: 10px;
                 padding: 18px 20px; display: flex; flex-direction: column; gap: 6px; }
    .stat-card .stat-label { font-size: 0.72rem; color: #7070a0;
                             text-transform: uppercase; letter-spacing: 0.06em; }
    .stat-card .stat-value { font-size: 1.8rem; font-weight: 800; }
    .stat-card--accent { border-color: #7c4dff;
                         background: linear-gradient(135deg, rgba(124,77,255,0.12) 0%, #12121e 100%); }
    .stat-card--accent .stat-value { color: #b388ff; }

    .admin-section { background: #12121e; border: 1px solid #1e1e32; border-radius: 12px;
                      overflow: hidden; margin: 32px 24px; max-width: 1100px;
                      margin-left: auto; margin-right: auto; }
    .section-title { font-size: 0.88rem; font-weight: 700; letter-spacing: 0.04em;
                      margin: 0; color: #e8e8f0; padding: 14px 20px;
                      border-bottom: 1px solid #1e1e32;
                      background: rgba(10,10,18,0.4); }

    .table-wrapper { overflow-x: auto; }
    .data-table { width: 100%; border-collapse: collapse; font-size: 0.83rem; }
    .data-table th { text-align: left; padding: 10px 16px; font-size: 0.68rem;
                      font-weight: 700; letter-spacing: 0.1em; text-transform: uppercase;
                      color: #7070a0; border-bottom: 1px solid #1e1e32; white-space: nowrap; }
    .data-table td { padding: 10px 16px; border-bottom: 1px solid rgba(30,30,50,0.6);
                      color: #e8e8f0; vertical-align: middle; }
    .data-table tr:last-child td { border-bottom: none; }
    .data-table tr:hover td { background: rgba(124,77,255,0.04); }

    .td-id { color: #7070a0; font-family: monospace; font-size: 0.8rem; }
    .td-mono { font-family: monospace; font-size: 0.78rem; color: #7070a0; }
    .td-empty { text-align: center; color: #7070a0; padding: 32px 16px; font-size: 0.85rem; }

    .badge { display: inline-block; font-size: 0.68rem; font-weight: 700;
              letter-spacing: 0.06em; text-transform: uppercase; border-radius: 4px;
              padding: 2px 8px; }
    .badge--yes { color: #4ade80; background: rgba(74,222,128,0.1);
                  border: 1px solid rgba(74,222,128,0.3); }
    .badge--no { color: #7070a0; background: rgba(112,112,160,0.08);
                 border: 1px solid rgba(112,112,160,0.2); }

    .error-banner { margin: 16px 40px 0; background: rgba(255,107,107,0.1);
                    border: 1px solid #ff6b6b; color: #ff6b6b;
                    padding: 12px 16px; border-radius: 8px; }
    .admin-nav-back { padding: 24px 40px; color: #7070a0; font-size: 0.88rem; }
    .admin-nav-back a { color: #7070a0; text-decoration: none; }
    .admin-nav-back a:hover { color: #b388ff; }
  `],
})
export class AdminRemoteComponent implements OnInit {
  private readonly http = inject(HttpClient);

  stats$: Observable<AdminStats | null> = of(null);
  sessions$: Observable<PlayerSession[]> = of([]);
  errorMessage = '';

  ngOnInit(): void { this.refresh(); }

  refresh(): void {
    this.errorMessage = '';
    this.stats$ = this.http.get<AdminStats>('/api/admin/stats').pipe(
      catchError(err => {
        this.errorMessage = err.status === 403
          ? 'Admin access required — log in with an isAdmin account.'
          : 'Failed to load stats.';
        return of(null);
      }),
    );
    this.sessions$ = this.http.get<PlayerSession[]>('/api/admin/sessions').pipe(
      catchError(() => of<PlayerSession[]>([])),
    );
  }

  kick(casdoorId: string): void {
    if (!confirm(`Force-disconnect ${casdoorId}?`)) return;
    this.http.delete<void>(`/api/admin/sessions/${casdoorId}`)
      .pipe(catchError(() => of(null)))
      .subscribe(() => this.refresh());
  }
}
