import { Component, OnInit, inject, signal } from '@angular/core';
import { forkJoin } from 'rxjs';
import { AdminStats, ApiFailure, GamesApiService, PlayerSession, classifyFailure, refusedBy } from './games-api.service';
import { GamesAdminForbiddenComponent, GamesUnreachableComponent } from './status-panels.component';

/** One stat tile: the DTO's scalar keys rendered generically, label/value. */
export interface StatTile {
  key: string;
  label: string;
  value: string;
}

const STAT_LABELS: Record<string, string> = {
  totalUsers: 'Users',
  totalSessions: 'Sessions',
  currentlyActive: 'Active now',
  averageSessionMinutes: 'Average session (min)',
  totalCharacters: 'Characters',
};

/** Every scalar (number/boolean/string) key of the stats DTO becomes a tile —
 *  nothing is invented, nothing is hidden; arrays/objects are left out. */
export function statTiles(stats: AdminStats | Record<string, unknown>): StatTile[] {
  return Object.entries(stats)
    .filter(([, v]) => v === null || ['number', 'string', 'boolean'].includes(typeof v))
    .map(([key, v]) => ({
      key,
      label: STAT_LABELS[key] ?? key.replace(/([A-Z])/g, ' $1').replace(/^./, (c) => c.toUpperCase()),
      value:
        typeof v === 'number' && !Number.isInteger(v) ? v.toFixed(1) : v === null ? '—' : String(v),
    }));
}

export type AdminLoadState = 'loading' | 'loaded' | 'forbidden' | 'unreachable';

/**
 * A1 Overview — path '' (host /games/admin). Stat tiles from
 * GET /games/api/admin/stats and the active player-session table from
 * GET /games/api/admin/sessions with an inline two-click 'End session'.
 */
@Component({
  selector: 'hug-games-admin-overview',
  standalone: true,
  imports: [GamesAdminForbiddenComponent, GamesUnreachableComponent],
  templateUrl: './admin-overview.component.html',
  styleUrl: './admin-overview.component.scss',
})
export class GamesAdminOverviewComponent implements OnInit {
  private readonly api = inject(GamesApiService);

  readonly state = signal<AdminLoadState>('loading');
  readonly failure = signal<ApiFailure | null>(null);
  readonly tiles = signal<StatTile[]>([]);
  readonly perUser = signal<AdminStats['perUser']>([]);
  readonly sessions = signal<PlayerSession[]>([]);
  /** casdoorId whose 'End session' is awaiting its confirming second click. */
  readonly confirming = signal<string | null>(null);
  readonly ending = signal<string | null>(null);
  readonly endError = signal<string | null>(null);

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.state.set('loading');
    this.failure.set(null);
    forkJoin({ stats: this.api.adminStats(), sessions: this.api.adminSessions() }).subscribe({
      next: ({ stats, sessions }) => {
        this.tiles.set(statTiles(stats ?? {}));
        this.perUser.set(stats?.perUser ?? []);
        this.sessions.set(sessions ?? []);
        this.state.set('loaded');
      },
      error: (err) => {
        const f = classifyFailure(err);
        this.failure.set(f);
        this.state.set(f.kind === 'forbidden' ? 'forbidden' : 'unreachable');
      },
    });
  }

  /** First click arms the inline confirm row; second click ends the session. */
  endSession(s: PlayerSession): void {
    this.endError.set(null);
    if (this.confirming() !== s.casdoorId) {
      this.confirming.set(s.casdoorId);
      return;
    }
    this.ending.set(s.casdoorId);
    this.api.endPlayerSession(s.casdoorId).subscribe({
      next: () => {
        this.sessions.update((rows) => rows.filter((x) => x.casdoorId !== s.casdoorId));
        this.confirming.set(null);
        this.ending.set(null);
      },
      error: (err) => {
        const f = classifyFailure(err);
        this.endError.set(
          f.kind === 'forbidden'
            ? `${refusedBy(f)} ending ${s.casdoorId}'s session (${f.status}).`
            : `Could not end ${s.casdoorId}'s session (${f.status || 'network'}).`,
        );
        this.confirming.set(null);
        this.ending.set(null);
      },
    });
  }

  cancelEnd(): void {
    this.confirming.set(null);
  }
}
