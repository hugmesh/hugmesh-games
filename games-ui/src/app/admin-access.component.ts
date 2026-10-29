import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiFailure, Game, GameAccessRule, GamesApiService, RoleSummary, classifyFailure, refusedBy } from './games-api.service';
import { GamesAdminForbiddenComponent, GamesUnreachableComponent } from './status-panels.component';

export const ACCESS_EXPLANATION =
  'Rules decide who sees and can launch this game; a single all rule opens it to every games member.';
export const ACCESS_EMPTY =
  'No rules — this game is hidden from everyone until a rule is added.';

export type AccessScope = 'all' | 'role' | 'user';

/**
 * A4 Access — path 'access'. Per-game catalog visibility / launch policy
 * (the BFF's game_access table): pick a game, list its rules, remove with an
 * inline confirm, add with an inline form. Roles come from
 * GET /games/api/admin/roles (a Casdoor proxy); when that call fails the value
 * field falls back to free text with a one-line note, so the screen stays
 * usable on a mesh where the proxy's realm credentials are not wired.
 * State is signals (zoneless host).
 */
@Component({
  selector: 'hug-games-admin-access',
  standalone: true,
  imports: [FormsModule, GamesAdminForbiddenComponent, GamesUnreachableComponent],
  templateUrl: './admin-access.component.html',
  styleUrl: './admin-access.component.scss',
})
export class GamesAdminAccessComponent implements OnInit {
  private readonly api = inject(GamesApiService);

  readonly explanation = ACCESS_EXPLANATION;
  readonly emptyText = ACCESS_EMPTY;

  readonly gamesState = signal<'loading' | 'loaded' | 'forbidden' | 'unreachable'>('loading');
  readonly games = signal<Game[]>([]);
  readonly selectedSlug = signal('');

  readonly rulesState = signal<'idle' | 'loading' | 'loaded' | 'forbidden' | 'unreachable'>('idle');
  readonly failure = signal<ApiFailure | null>(null);
  readonly rules = signal<GameAccessRule[]>([]);

  readonly roles = signal<RoleSummary[]>([]);
  readonly rolesUnavailable = signal(false);

  // inline add form
  readonly newScope = signal<AccessScope>('all');
  readonly newValue = signal('');
  readonly adding = signal(false);
  readonly actionError = signal<string | null>(null);
  /** Index of the rule whose Remove awaits confirmation. */
  readonly confirming = signal<number | null>(null);

  ngOnInit(): void {
    this.loadGames();
    this.api.roles().subscribe({
      next: (r) => {
        const roles = (r ?? []).filter((x) => x.enabled !== false);
        this.roles.set(roles);
        this.rolesUnavailable.set(roles.length === 0);
      },
      error: () => {
        this.roles.set([]);
        this.rolesUnavailable.set(true);
      },
    });
  }

  loadGames(): void {
    this.gamesState.set('loading');
    this.api.listGames().subscribe({
      next: (g) => {
        const games = [...(g ?? [])].sort((a, b) => a.name.localeCompare(b.name));
        this.games.set(games);
        this.gamesState.set('loaded');
        if (!this.selectedSlug() && games.length) {
          this.selectedSlug.set(games[0].slug);
          this.loadRules();
        }
      },
      error: (err) => {
        const f = classifyFailure(err);
        this.failure.set(f);
        this.gamesState.set(f.kind === 'forbidden' ? 'forbidden' : 'unreachable');
      },
    });
  }

  selectGame(slug: string): void {
    this.selectedSlug.set(slug);
    this.loadRules();
  }

  loadRules(): void {
    const slug = this.selectedSlug();
    if (!slug) {
      this.rulesState.set('idle');
      return;
    }
    this.rulesState.set('loading');
    this.failure.set(null);
    this.actionError.set(null);
    this.confirming.set(null);
    this.api.gameAccess(slug).subscribe({
      next: (rows) => {
        this.rules.set(rows ?? []);
        this.rulesState.set('loaded');
      },
      error: (err) => {
        const f = classifyFailure(err);
        this.failure.set(f);
        this.rulesState.set(f.kind === 'forbidden' ? 'forbidden' : 'unreachable');
      },
    });
  }

  add(): void {
    this.actionError.set(null);
    const scope = this.newScope();
    const value = scope === 'all' ? null : this.newValue().trim();
    if (scope !== 'all' && !value) {
      this.actionError.set(`A ${scope} rule needs a value.`);
      return;
    }
    this.adding.set(true);
    this.api.grantGameAccess(this.selectedSlug(), scope, value).subscribe({
      next: (row) => {
        this.rules.update((rs) => [...rs, row]);
        this.newValue.set('');
        this.adding.set(false);
      },
      error: (err) => {
        const f = classifyFailure(err);
        this.actionError.set(
          f.kind === 'forbidden'
            ? `${refusedBy(f)} adding the rule (${f.status}).`
            : `Could not add the rule (${f.status || 'network'}).`,
        );
        this.adding.set(false);
      },
    });
  }

  /** First click arms the inline confirm; second click removes. */
  remove(i: number): void {
    this.actionError.set(null);
    if (this.confirming() !== i) {
      this.confirming.set(i);
      return;
    }
    const rule = this.rules()[i];
    this.api.revokeGameAccess(this.selectedSlug(), rule.scope, rule.scopeValue).subscribe({
      next: () => {
        this.rules.update((rs) => rs.filter((_, k) => k !== i));
        this.confirming.set(null);
      },
      error: (err) => {
        const f = classifyFailure(err);
        this.actionError.set(
          f.kind === 'forbidden'
            ? `${refusedBy(f)} removing the rule (${f.status}).`
            : `Could not remove the rule (${f.status || 'network'}).`,
        );
        this.confirming.set(null);
      },
    });
  }

  cancelRemove(): void {
    this.confirming.set(null);
  }
}
