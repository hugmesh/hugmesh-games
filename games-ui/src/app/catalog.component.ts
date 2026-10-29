import { Component, OnInit, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';
import {
  ApiFailure,
  FederationRemote,
  Game,
  GameLaunch,
  GamesApiService,
  classifyFailure,
} from './games-api.service';
import {
  NEW_TAB_REASON,
  NEW_TAB_SLUGS,
  WEB_NATIVE_SLUGS,
  bundleIconUrl,
  mountRoute,
} from './games.constants';
import { GamesForbiddenComponent, GamesUnreachableComponent } from './status-panels.component';

/** How a catalog row can be played on THIS deployment. */
export type Availability = 'here' | 'new-tab' | 'unavailable';

export interface CatalogCard {
  game: Game;
  availability: Availability;
  badge: string;
  /** Thumbnail candidates in order: catalog thumbnailUrl, bundle icon, none. */
  thumbnail: string | null;
  fallbackThumbnail: string | null;
  letter: string;
}

export const BADGE_HERE = 'Plays here';
export const BADGE_NEW_TAB = 'Opens in a new tab';
export const BADGE_UNAVAILABLE = 'Not available on this deployment';

/**
 * Decide the card's badge from the catalog row + the federation manifest. A
 * game is playable only when a `wasm-game` manifest row exists for its slug
 * (that is what the wasm-fragment tier actually serves); the Godot federated
 * remotes (three-wizards / rpg-maker / vn-maker) have no such row on the mesh
 * and are shown disabled rather than hidden, so a member is told, not left
 * guessing.
 */
export function availabilityOf(game: Game, wasmGames: ReadonlySet<string>): Availability {
  if (!wasmGames.has(game.slug)) return 'unavailable';
  if (NEW_TAB_SLUGS.includes(game.slug)) return 'new-tab';
  if (WEB_NATIVE_SLUGS.includes(game.slug)) return 'here';
  // A bundle the manifest lists but neither constant names: play it in-shell
  // (the conservative assumption is "web-native"; a SAB bundle would show the
  // isolation failure in its own frame, not a blank page).
  return 'here';
}

export function toCard(game: Game, wasmGames: ReadonlySet<string>): CatalogCard {
  const availability = availabilityOf(game, wasmGames);
  const badge =
    availability === 'here' ? BADGE_HERE : availability === 'new-tab' ? BADGE_NEW_TAB : BADGE_UNAVAILABLE;
  const bundleIcon = availability === 'unavailable' ? null : bundleIconUrl(game.slug);
  return {
    game,
    availability,
    badge,
    thumbnail: game.thumbnailUrl || bundleIcon,
    fallbackThumbnail: game.thumbnailUrl ? bundleIcon : null,
    letter: (game.name || game.slug || '?').trim().charAt(0).toUpperCase(),
  };
}

export type CatalogState = 'loading' | 'loaded' | 'empty' | 'error' | 'forbidden';

/**
 * M1 Catalog — path '' (host mount /games). Shows a games member every game
 * they can play and lets them launch it. All view state is signals: the host
 * shell (and this remote's own bootstrap) run zoneless, so a plain field set
 * inside an HttpClient callback would never repaint.
 */
@Component({
  selector: 'hug-games-catalog',
  standalone: true,
  imports: [RouterLink, GamesForbiddenComponent, GamesUnreachableComponent],
  templateUrl: './catalog.component.html',
  styleUrl: './catalog.component.scss',
})
export class GamesCatalogComponent implements OnInit {
  private readonly api = inject(GamesApiService);
  private readonly route = inject(ActivatedRoute);

  /** Relative-link anchor: the mount route (parent of this '' route). */
  readonly mount = mountRoute(this.route);
  readonly newTabReason = NEW_TAB_REASON;
  readonly skeleton = [0, 1, 2, 3, 4, 5];

  readonly state = signal<CatalogState>('loading');
  readonly failure = signal<ApiFailure | null>(null);
  readonly cards = signal<CatalogCard[]>([]);
  /** Recent launches — only rendered when the BFF lets this caller list them. */
  readonly launches = signal<GameLaunch[]>([]);
  /** Thumbnails that failed to load (by slug) — the letter tile takes over. */
  readonly brokenThumbs = signal<ReadonlySet<string>>(new Set());

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.state.set('loading');
    this.failure.set(null);
    forkJoin({ games: this.api.listGames(), manifest: this.api.federationManifest() }).subscribe({
      next: ({ games, manifest }) => {
        const wasmGames = new Set(
          (manifest?.remotes ?? [])
            .filter((r: FederationRemote) => r.kind === 'wasm-game' && r.gameSlug)
            .map((r) => r.gameSlug as string),
        );
        const sorted = [...(games ?? [])].sort(
          (a, b) => (a.sortOrder ?? 0) - (b.sortOrder ?? 0) || a.name.localeCompare(b.name),
        );
        const cards = sorted.map((g) => toCard(g, wasmGames));
        this.cards.set(cards);
        this.state.set(cards.length ? 'loaded' : 'empty');
        this.loadLaunches();
      },
      error: (err) => {
        const f = classifyFailure(err);
        this.failure.set(f);
        this.state.set(f.kind === 'forbidden' ? 'forbidden' : 'error');
      },
    });
  }

  /** Best-effort: `GET /api/games/launches` is admin-gated on the BFF today, so
   *  a plain member gets 403 — the strip simply stays hidden (it must never
   *  flip the catalog into the forbidden state). When it does render (an admin
   *  holder) it lists EVERY player's active launches, not the caller's, and
   *  the strip says so. */
  private loadLaunches(): void {
    this.api.listLaunches().subscribe({
      next: (rows) => {
        const now = Date.now();
        this.launches.set(
          (rows ?? []).filter((l) => !l.terminatedAt && new Date(l.expiresAt).getTime() > now),
        );
      },
      error: () => this.launches.set([]),
    });
  }

  thumbFor(card: CatalogCard): string | null {
    if (this.brokenThumbs().has(card.game.slug)) return null;
    return card.thumbnail;
  }

  onThumbError(card: CatalogCard): void {
    if (card.fallbackThumbnail && card.thumbnail !== card.fallbackThumbnail) {
      const next = { ...card, thumbnail: card.fallbackThumbnail, fallbackThumbnail: null };
      this.cards.update((cards) => cards.map((c) => (c.game.slug === card.game.slug ? next : c)));
      return;
    }
    this.brokenThumbs.update((s) => new Set([...s, card.game.slug]));
  }

  nameFor(slug: string): string {
    return this.cards().find((c) => c.game.slug === slug)?.game.name ?? slug;
  }

  badgeClass(card: CatalogCard): string {
    return card.availability === 'here'
      ? 'hg-badge--ok'
      : card.availability === 'new-tab'
        ? 'hg-badge--tab'
        : 'hg-badge--off';
  }
}
