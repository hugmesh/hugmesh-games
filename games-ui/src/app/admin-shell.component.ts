import { Component, inject } from '@angular/core';
import { ActivatedRoute, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { mountRoute } from './games.constants';

/** The admin tab bar. Paths are RELATIVE to the mount route (host /games/admin). */
export const ADMIN_TABS: readonly { path: string; label: string; testid: string }[] = [
  { path: '', label: 'Overview', testid: 'games-admin-tab-overview' },
  { path: 'sessions', label: 'Live sessions', testid: 'games-admin-tab-sessions' },
  { path: 'logs', label: 'Session logs', testid: 'games-admin-tab-logs' },
  { path: 'access', label: 'Access', testid: 'games-admin-tab-access' },
  { path: 'settings', label: 'Settings', testid: 'games-admin-tab-settings' },
];

/**
 * A0 — the games admin shell (host mount /games/admin behind gamesAdminGuard):
 * title, tab bar of relative child links, and the outlet the five admin
 * screens render into. The shared forbidden / unreachable panels are rendered
 * by each screen (they own the call that failed).
 */
@Component({
  selector: 'hug-games-admin-shell',
  standalone: true,
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  template: `
    <section class="hg-page" data-testid="games-admin-shell" aria-labelledby="games-admin-title">
      <header class="hg-head">
        <h1 id="games-admin-title">Games admin</h1>
        <span class="hg-muted">Oversight of the games service on this mesh.</span>
      </header>
      <nav class="tabs" aria-label="Games admin sections">
        @for (tab of tabs; track tab.path) {
          <a
            class="tab"
            [routerLink]="tab.path === '' ? ['./'] : [tab.path]"
            [relativeTo]="mount"
            routerLinkActive="tab--active"
            [routerLinkActiveOptions]="{ exact: tab.path === '' }"
            ariaCurrentWhenActive="page"
            [attr.data-testid]="tab.testid"
            >{{ tab.label }}</a
          >
        }
      </nav>
      <router-outlet></router-outlet>
    </section>
  `,
  styleUrl: './hg-primitives.scss',
  styles: [
    `
      .tabs {
        display: flex;
        flex-wrap: wrap;
        gap: 0.25rem;
        border-bottom: 1px solid var(--hug-border);
        padding-bottom: 0.25rem;
      }
      .tab {
        text-decoration: none;
        color: var(--hug-text-muted);
        padding: 0.5rem 0.9rem;
        border-radius: 0.5rem 0.5rem 0 0;
        border-bottom: 2px solid transparent;
        min-height: 2.5rem;
        display: inline-flex;
        align-items: center;
      }
      .tab:hover {
        color: var(--hug-text);
      }
      .tab--active {
        color: var(--hug-text);
        border-bottom-color: var(--hug-accent-soft);
      }
    `,
  ],
})
export class GamesAdminShellComponent {
  private readonly route = inject(ActivatedRoute);
  /** This component sits on the '' child of the mount; links resolve against the mount. */
  readonly mount = mountRoute(this.route);
  readonly tabs = ADMIN_TABS;
}
