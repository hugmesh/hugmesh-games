import { Component, OnInit, computed, inject, signal, ChangeDetectionStrategy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { GameLibraryService } from '../services/game-library.service';
import { GameListing } from '../models/game-listing.model';

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
  visibility?: string;
  createdAt: string;
  closedAt: string | null;
  pausedAt?: string | null;
  pausedByCasdoorId?: string | null;
  pauseReason?: string | null;
  members: SessionMember[];
}

/**
 * Admin-only session log surface at {@code /admin/session-log}.
 *
 * <p>Shape requested by the admin user: a searchable list of active
 * sessions, surfaced by selecting + expanding a list of active games.
 * The tree is sourced from:
 *
 * <ul>
 *   <li>{@code GET /api/games} — every umbrella game in the catalog;
 *       drives the top-level tree nodes.</li>
 *   <li>{@code GET /api/admin/multiplayer-sessions} — every open
 *       multiplayer session (admin oversight endpoint, bypasses the
 *       friend-graph / visibility filter so an admin sees private and
 *       friends-only sessions). Loaded once and grouped client-side
 *       by {@code gameSlug}; the alternative would have been one
 *       request per expanded game node, which is wasteful on stacks
 *       with a single-digit game count.</li>
 * </ul>
 *
 * <p>The search box filters BOTH the game tree (only games with a
 * matching session stay visible) AND the session rows within each
 * game (only matching sessions are listed). Empty search shows
 * everything.
 */
@Component({
  selector: 'app-admin-session-log-page',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <section class="admin-log-page">
      <header class="admin-log-page-header">
        <h2>Active sessions by game</h2>
        <p>
          Every open multiplayer session, grouped by game. Click a game to
          expand. The list bypasses friend / private visibility filters so
          you see every session on this stack, not just the ones a player
          would see in the lobby picker.
        </p>
        <div class="controls">
          <input class="search-input"
                 type="search"
                 placeholder="Search by session name, creator, game…"
                 [(ngModel)]="search"
                 (ngModelChange)="onSearchChange($event)"
                 aria-label="Search active sessions" />
          <button class="btn" type="button" (click)="refresh()">&#8635; Refresh</button>
        </div>
        <p class="muted small">
          {{ totalSessionsVisible() }} of {{ sessions().length }} open
          ·
          {{ visibleGames().length }} of {{ games().length }} games
        </p>
      </header>

      @if (loading()) {
        <p class="muted">Loading…</p>
      } @else if (error()) {
        <p class="error" role="alert">{{ error() }}</p>
      } @else if (games().length === 0) {
        <p class="muted">No games in the catalog yet.</p>
      } @else {
        <ul class="game-tree" role="tree">
          @for (g of visibleGames(); track g.id) {
            <li role="treeitem" [attr.aria-expanded]="isExpanded(g.slug)">
              <button class="game-row"
                      type="button"
                      [class.expanded]="isExpanded(g.slug)"
                      (click)="toggleGame(g.slug)">
                <span class="caret" aria-hidden="true">{{ isExpanded(g.slug) ? '▾' : '▸' }}</span>
                <span class="game-name">{{ g.name }}</span>
                <span class="game-slug">{{ g.slug }}</span>
                <span class="game-count" [attr.aria-label]="sessionsInGame(g.slug).length + ' active sessions'">
                  {{ sessionsInGame(g.slug).length }} active
                </span>
                @if (pausedCount(g.slug) > 0) {
                  <span class="game-paused-count"
                        [attr.aria-label]="pausedCount(g.slug) + ' paused sessions'">
                    {{ pausedCount(g.slug) }} paused
                  </span>
                }
              </button>
              @if (isExpanded(g.slug)) {
                @if (sessionsInGame(g.slug).length === 0) {
                  <p class="muted indent">No active sessions for this game.</p>
                } @else {
                  <ul class="session-rows" role="group">
                    @for (s of sessionsInGame(g.slug); track s.id) {
                      <li>
                        <div class="session-row" [class.row-paused]="!!s.pausedAt">
                          <div class="session-row-head">
                            <strong>{{ s.name }}</strong>
                            <span class="vis-tag" [attr.data-vis]="s.visibility || 'public'">
                              {{ s.visibility || 'public' }}
                            </span>
                            <span class="count-pill">
                              {{ s.activePlayers }} / {{ s.maxPlayers }}
                            </span>
                            @if (s.pausedAt) {
                              <span class="paused-pill"
                                    [attr.title]="'Paused: ' + (s.pauseReason || 'manual')">
                                ⏸ paused
                              </span>
                            }
                          </div>
                          <div class="session-row-meta">
                            <span>by {{ s.creatorCasdoorId }}</span>
                            <span>·</span>
                            <span>created {{ s.createdAt | date:'short' }}</span>
                            <span>·</span>
                            <code class="session-id">{{ s.id }}</code>
                          </div>
                          @if (s.members.length) {
                            <ul class="members">
                              @for (m of s.members; track m.casdoorId) {
                                <li>
                                  <span class="slot">slot {{ m.slotIndex ?? '–' }}</span>
                                  {{ m.casdoorId }}
                                  <span class="char">char #{{ m.characterId ?? '–' }}</span>
                                  @if (m.creator) { <em>(creator)</em> }
                                </li>
                              }
                            </ul>
                          }
                        </div>
                      </li>
                    }
                  </ul>
                }
              }
            </li>
          }
        </ul>
      }
    </section>
  `,
  changeDetection: ChangeDetectionStrategy.Eager,
  styles: [`
    .admin-log-page { max-width: 1100px; margin: 24px auto; padding: 0 20px; color: #d8dde6; font-family: sans-serif; }
    .admin-log-page-header h2 { margin: 0 0 4px; color: #f0f2fa; }
    .admin-log-page-header p  { margin: 0 0 12px; color: #9aa3b8; font-size: 14px; }
    .controls { display: flex; gap: .5rem; margin: .5rem 0; }
    .search-input { flex: 1; padding: .5rem .7rem; border: 1px solid #2a2a40; border-radius: 4px; background: #0f0f1c; color: #e8e8f0; }
    .search-input:focus { outline: 2px solid #7c4dff; }
    .btn { background: #1a1a2e; border: 1px solid #2a2a40; color: #e8e8f0; border-radius: 4px; padding: .35rem .9rem; cursor: pointer; }
    .btn:hover { border-color: #7c4dff; }
    .muted { color: #9aa3b8; font-style: italic; }
    .small { font-size: .8rem; }
    .error { color: #ff8a65; }

    .game-tree { list-style: none; padding: 0; margin: 0; }
    .game-tree > li { margin-bottom: .35rem; border: 1px solid #1e1e32; border-radius: 6px; background: #12121e; }
    .game-row { width: 100%; display: flex; align-items: center; gap: .6rem; padding: .55rem .8rem; background: transparent; color: #e8e8f0; border: 0; cursor: pointer; text-align: left; }
    .game-row:hover { background: rgba(124,77,255,0.06); }
    .game-row.expanded { border-bottom: 1px solid #1e1e32; }
    .caret { width: 1ch; color: #b388ff; }
    .game-name { font-weight: 600; }
    .game-slug { font-family: ui-monospace, monospace; font-size: .8rem; color: #9aa3b8; }
    .game-count { margin-left: auto; font-size: .8rem; color: #b388ff; }
    .game-paused-count { font-size: .78rem; color: #ffd180; }
    .paused-pill { color: #ffd180; background: rgba(255,209,128,0.12); border-radius: 999px; padding: 1px 8px; font-size: .76rem; }
    .session-row.row-paused { background: rgba(255,209,128,0.06); border-left: 2px solid #ffd180; padding-left: .4rem; }
    .session-rows { list-style: none; padding: 0 .8rem .6rem; margin: 0; }
    .session-rows li { padding: .35rem 0; border-top: 1px dashed #1e1e32; }
    .session-rows li:first-child { border-top: 0; }
    .session-row-head { display: flex; align-items: center; gap: .55rem; }
    .session-row-meta { display: flex; flex-wrap: wrap; gap: .35rem; font-size: .78rem; color: #9aa3b8; margin: .2rem 0 .15rem; }
    .session-id { background: rgba(255,255,255,0.04); padding: 1px 5px; border-radius: 3px; font-family: ui-monospace, monospace; }
    .vis-tag { font-style: italic; font-size: .76rem; padding: 1px 6px; border-radius: 3px; }
    .vis-tag[data-vis='public']  { color: #4caf50; background: rgba(76,175,80,0.1); }
    .vis-tag[data-vis='friends'] { color: #b388ff; background: rgba(179,136,255,0.1); }
    .vis-tag[data-vis='private'] { color: #ff8a65; background: rgba(255,138,101,0.1); }
    .count-pill { background: #7c4dff; color: #fff; border-radius: 999px; padding: 1px 8px; font-size: .76rem; }
    .members { list-style: none; padding: 0; margin: .2rem 0 .1rem; font-size: .8rem; color: #d8dde6; }
    .members li { padding: 1px 0; display: flex; gap: .4rem; align-items: center; }
    .slot { background: #2a2a40; color: #e8e8f0; border-radius: 4px; padding: 1px 6px; font-size: .72rem; min-width: 3.4em; text-align: center; }
    .char { color: #9aa3b8; font-size: .76rem; }
    .indent { padding-left: 1.6rem; }
  `],
})
export class AdminSessionLogPageComponent implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly gameLibrary = inject(GameLibraryService);

  readonly games = signal<GameListing[]>([]);
  readonly sessions = signal<GameSession[]>([]);
  readonly loading = signal(true);
  readonly error = signal<string | null>(null);

  readonly expanded = signal<Set<string>>(new Set());
  search = '';
  readonly searchSignal = signal('');

  /** Count of paused sessions for a given game slug (post-search filter). */
  pausedCount(slug: string): number {
    return this.sessionsInGame(slug).filter(s => !!s.pausedAt).length;
  }

  /** Sessions for a given game slug, filtered by the active search query. */
  sessionsInGame(slug: string): GameSession[] {
    const q = this.searchSignal().trim().toLowerCase();
    return this.sessions().filter(s => {
      if (s.gameSlug !== slug) return false;
      if (!q) return true;
      return s.name.toLowerCase().includes(q)
          || s.creatorCasdoorId.toLowerCase().includes(q)
          || s.gameSlug.toLowerCase().includes(q);
    });
  }

  /** Games whose name or slug matches the query, OR that have at least one
   *  session matching the query. Empty query → every game. */
  readonly visibleGames = computed<GameListing[]>(() => {
    const q = this.searchSignal().trim().toLowerCase();
    if (!q) return this.games();
    return this.games().filter(g => {
      if (g.name.toLowerCase().includes(q)) return true;
      if (g.slug.toLowerCase().includes(q)) return true;
      return this.sessionsInGame(g.slug).length > 0;
    });
  });

  /** Sum across all currently-visible game tree nodes — drives the
   *  header summary "X of Y open". */
  readonly totalSessionsVisible = computed(() => {
    return this.visibleGames().reduce(
      (sum, g) => sum + this.sessionsInGame(g.slug).length, 0);
  });

  ngOnInit(): void { this.refresh(); }

  refresh(): void {
    this.loading.set(true);
    this.error.set(null);
    this.gameLibrary.listGames().subscribe({
      next: list => this.games.set(list ?? []),
      error: () => { /* ignored — sessions still useful */ },
    });
    this.http.get<GameSession[]>('/api/admin/multiplayer-sessions').subscribe({
      next: list => { this.sessions.set(list ?? []); this.loading.set(false); },
      error: () => {
        this.sessions.set([]);
        this.loading.set(false);
        this.error.set('Could not load active sessions.');
      },
    });
  }

  toggleGame(slug: string): void {
    const next = new Set(this.expanded());
    if (next.has(slug)) next.delete(slug);
    else next.add(slug);
    this.expanded.set(next);
  }

  isExpanded(slug: string): boolean {
    return this.expanded().has(slug);
  }

  onSearchChange(v: string): void {
    this.searchSignal.set(v);
    // Auto-expand any game with matching sessions so the matches are visible
    // without forcing the admin to click every row.
    if (v.trim()) {
      const next = new Set(this.expanded());
      for (const g of this.visibleGames()) {
        if (this.sessionsInGame(g.slug).length > 0) next.add(g.slug);
      }
      this.expanded.set(next);
    }
  }
}
