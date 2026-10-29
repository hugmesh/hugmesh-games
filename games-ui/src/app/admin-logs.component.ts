import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiFailure, GamesApiService, SessionLogEntry, classifyFailure } from './games-api.service';
import { GamesAdminForbiddenComponent, GamesUnreachableComponent } from './status-panels.component';

/** game -> sessions -> member rows, built from the flat SessionLogEntry list. */
export interface LogSession {
  id: string;
  name: string | null;
  paused: boolean;
  pauseReason: string | null;
  closed: boolean;
  visibility: string | null;
  entries: SessionLogEntry[];
  open: boolean;
}

export interface LogGame {
  slug: string;
  sessions: LogSession[];
  open: boolean;
}

export function buildTree(rows: SessionLogEntry[]): LogGame[] {
  const games = new Map<string, Map<string, LogSession>>();
  for (const r of rows) {
    const bySession = games.get(r.gameSlug) ?? new Map<string, LogSession>();
    games.set(r.gameSlug, bySession);
    const s = bySession.get(r.sessionId) ?? {
      id: r.sessionId,
      name: r.sessionName,
      paused: !!r.sessionPausedAt,
      pauseReason: r.sessionPauseReason,
      closed: !!r.sessionClosedAt,
      visibility: r.visibility,
      entries: [],
      open: false,
    };
    s.entries.push(r);
    bySession.set(r.sessionId, s);
  }
  return [...games.entries()]
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([slug, bySession]) => ({ slug, sessions: [...bySession.values()], open: false }));
}

export type LogsState = 'prompt' | 'loading' | 'loaded' | 'none' | 'forbidden' | 'unreachable';

/**
 * A3 Session logs — path 'logs'. A player's casdoor id ->
 * GET /games/api/session-logs/admin/{casdoorId} -> collapsible tree
 * game -> sessions -> events (member joins/leaves) with counts and a paused
 * marker; a search filter auto-expands matching nodes. State is signals.
 */
@Component({
  selector: 'hug-games-admin-logs',
  standalone: true,
  imports: [FormsModule, GamesAdminForbiddenComponent, GamesUnreachableComponent],
  templateUrl: './admin-logs.component.html',
  styleUrl: './admin-logs.component.scss',
})
export class GamesAdminLogsComponent {
  private readonly api = inject(GamesApiService);

  readonly query = signal('');
  readonly loadedFor = signal<string | null>(null);
  readonly search = signal('');
  readonly state = signal<LogsState>('prompt');
  readonly failure = signal<ApiFailure | null>(null);
  readonly tree = signal<LogGame[]>([]);

  /** The tree filtered by the search box; matching nodes are force-opened. */
  readonly visible = computed<LogGame[]>(() => {
    const q = this.search().trim().toLowerCase();
    const tree = this.tree();
    if (!q) return tree;
    const out: LogGame[] = [];
    for (const g of tree) {
      const gameMatch = g.slug.toLowerCase().includes(q);
      const sessions = g.sessions
        .map((s) => {
          const sessionMatch =
            gameMatch || s.id.toLowerCase().includes(q) || (s.name ?? '').toLowerCase().includes(q);
          const entries = sessionMatch
            ? s.entries
            : s.entries.filter((e) =>
                [e.memberCasdoorId, e.characterId, e.slotIndex, e.joinedAt, e.leftAt]
                  .map((v) => String(v ?? '').toLowerCase())
                  .some((v) => v.includes(q)),
              );
          return entries.length ? { ...s, entries, open: true } : null;
        })
        .filter((s): s is LogSession => s !== null);
      if (sessions.length) out.push({ ...g, sessions, open: true });
    }
    return out;
  });

  load(): void {
    const id = this.query().trim();
    if (!id) {
      this.state.set('prompt');
      return;
    }
    this.state.set('loading');
    this.failure.set(null);
    this.loadedFor.set(id);
    this.api.sessionLogs(id).subscribe({
      next: (rows) => {
        const tree = buildTree(rows ?? []);
        this.tree.set(tree);
        this.state.set(tree.length ? 'loaded' : 'none');
      },
      error: (err) => {
        const f = classifyFailure(err);
        this.failure.set(f);
        if (f.kind === 'notFound') {
          this.tree.set([]);
          this.state.set('none');
        } else {
          this.state.set(f.kind === 'forbidden' ? 'forbidden' : 'unreachable');
        }
      },
    });
  }

  toggleGame(g: LogGame): void {
    this.tree.update((t) => t.map((x) => (x.slug === g.slug ? { ...x, open: !x.open } : x)));
  }

  toggleSession(s: LogSession): void {
    this.tree.update((t) =>
      t.map((g) => ({
        ...g,
        sessions: g.sessions.map((x) => (x.id === s.id ? { ...x, open: !x.open } : x)),
      })),
    );
  }

  countEntries(g: LogGame): number {
    return g.sessions.reduce((n, s) => n + s.entries.length, 0);
  }
}
