import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import {
  BADGE_HERE,
  BADGE_NEW_TAB,
  BADGE_UNAVAILABLE,
  GamesCatalogComponent,
  availabilityOf,
} from './catalog.component';
import { Game } from './games-api.service';
import { bundleIconUrls } from './games.constants';
import { SESSION_KEY, isGamesAdminSession, sessionRoles } from './session-roles';

const GAMES: Game[] = [
  { id: 1, name: 'Chess', description: 'Classic', slug: 'chess', thumbnailUrl: null, sortOrder: 2 },
  { id: 2, name: 'Cave', description: 'Three Wizards chapter', slug: 'cave', thumbnailUrl: null, sortOrder: 1 },
  { id: 3, name: 'RPG Maker', description: null, slug: 'rpg-maker', thumbnailUrl: null, sortOrder: 3 },
];

const MANIFEST = {
  generatedAt: 'now',
  remotes: [
    { slug: 'wasm-chess', displayName: 'Chess', kind: 'wasm-game', remoteEntryUrl: '/_remotes/wasm-fragment/chess/index.html', exposedModule: null, gameSlug: 'chess', version: '1', metadata: null },
    { slug: 'wasm-cave', displayName: 'Cave', kind: 'wasm-game', remoteEntryUrl: '/_remotes/wasm-fragment/cave/index.html', exposedModule: null, gameSlug: 'cave', version: '1', metadata: null },
    { slug: 'rpg-maker', displayName: 'RPG Maker', kind: 'godot-remote', remoteEntryUrl: null, exposedModule: null, gameSlug: 'rpg-maker', version: '1', metadata: null },
  ],
};

/** Write the host's `hug.session` record (only `roles` matters here). */
function asSession(roles: string[] | null): void {
  if (roles === null) localStorage.removeItem(SESSION_KEY);
  else localStorage.setItem(SESSION_KEY, JSON.stringify({ id: 'x', roles }));
}

describe('GamesCatalogComponent (M1)', () => {
  let http: HttpTestingController;
  let fixture: ComponentFixture<GamesCatalogComponent>;

  beforeEach(async () => {
    asSession(['games', 'user']); // a games member unless a test says otherwise
    await TestBed.configureTestingModule({
      imports: [GamesCatalogComponent],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(GamesCatalogComponent);
    fixture.detectChanges();
  });

  afterEach(() => {
    http.verify();
    asSession(null);
  });

  function el(): HTMLElement {
    return fixture.nativeElement;
  }

  function flushBoth(games = GAMES, manifest: object = MANIFEST) {
    http.expectOne('/games/api/games').flush(games);
    http.expectOne('/games/api/federation/manifest').flush(manifest);
    fixture.detectChanges();
  }

  it('calls only absolute /games/api paths with credentials', () => {
    const g = http.expectOne('/games/api/games');
    const m = http.expectOne('/games/api/federation/manifest');
    expect(g.request.withCredentials).toBeTrue();
    expect(m.request.withCredentials).toBeTrue();
    g.flush([]);
    m.flush({ generatedAt: '', remotes: [] });
    http.expectNone('/games/api/games/launches');
  });

  it('renders the loading skeleton (6 placeholder cards) while both calls are pending', () => {
    expect(el().querySelector('[data-testid="games-catalog"]')).not.toBeNull();
    expect(el().querySelectorAll('[data-testid="games-loading"] .hg-skeleton').length).toBe(6);
    flushBoth([], { generatedAt: '', remotes: [] });
  });

  it('shows the empty message when no games are published', () => {
    flushBoth([], { generatedAt: '', remotes: [] });
    expect(el().querySelector('[data-testid="games-empty"]')?.textContent).toContain(
      'No games are published on this mesh yet.',
    );
  });

  it('renders one card per game, sorted, with exactly one badge each', () => {
    flushBoth();
    fixture.detectChanges();
    const cards = el().querySelectorAll('[data-testid^="games-card-"]');
    expect(cards.length).toBe(3);
    expect(cards[0].getAttribute('data-testid')).toBe('games-card-cave'); // sortOrder 1
    expect(el().querySelector('[data-testid="games-badge-chess"]')?.textContent?.trim()).toBe(BADGE_HERE);
    expect(el().querySelector('[data-testid="games-badge-cave"]')?.textContent?.trim()).toBe(BADGE_NEW_TAB);
    expect(el().querySelector('[data-testid="games-badge-rpg-maker"]')?.textContent?.trim()).toBe(
      BADGE_UNAVAILABLE,
    );
    for (const c of Array.from(cards)) {
      expect(c.querySelectorAll('[data-testid^="games-badge-"]').length).toBe(1);
    }
  });

  it('disables the unavailable card (aria-disabled + helptext) and links play for the others', () => {
    flushBoth();
    fixture.detectChanges();
    const rpg = el().querySelector('[data-testid="games-card-rpg-maker"]')!;
    expect(rpg.getAttribute('aria-disabled')).toBe('true');
    const rpgBtn = el().querySelector<HTMLButtonElement>('[data-testid="games-play-rpg-maker"]')!;
    expect(rpgBtn.disabled).toBeTrue();
    expect(rpg.textContent).toContain('No playable bundle');
    const chess = el().querySelector<HTMLAnchorElement>('[data-testid="games-play-chess"]')!;
    expect(chess.tagName).toBe('A');
    expect(chess.getAttribute('href')).toBe('/play/chess');
    expect(chess.getAttribute('href')!.startsWith('/games')).toBeFalse(); // relative to the mount, not absolute
  });

  it('never asks for launches for a member, a bare platform admin, a superadmin or no session', () => {
    flushBoth();
    http.expectNone('/games/api/games/launches');
    for (const roles of [['admin', 'user'], ['superadmin'], ['games', 'admin', 'user'], null]) {
      asSession(roles);
      fixture.componentInstance.load();
      flushBoth();
      http.expectNone('/games/api/games/launches');
    }
    expect(el().querySelector('[data-testid="games-recent"]')).toBeNull();
  });

  it('shows the recent-launches strip for a games admin session', () => {
    asSession(['admin', 'games', 'games_admin', 'user']); // the live e2e-gamesadmin session
    flushBoth();
    const future = new Date(Date.now() + 60_000).toISOString();
    http.expectOne('/games/api/games/launches').flush([
      { uid: 'u1', gameSlug: 'chess', expiresAt: future },
      { uid: 'u2', gameSlug: 'chess', expiresAt: future, terminatedAt: future },
    ]);
    fixture.detectChanges();
    expect(el().querySelector('[data-testid="games-recent"]')).not.toBeNull();
    expect(el().querySelector('[data-testid="games-resume-u1"]')).not.toBeNull();
    expect(el().querySelector('[data-testid="games-resume-u2"]')).toBeNull();
  });

  it('hides the strip (and stays loaded, not forbidden) when launches returns 403', () => {
    asSession(['ROLE_GAMES_ADMIN']); // prefixed spelling canonicalises too
    flushBoth();
    http.expectOne('/games/api/games/launches').flush('nope', { status: 403, statusText: 'Forbidden' });
    fixture.detectChanges();
    expect(el().querySelector('[data-testid="games-recent"]')).toBeNull();
    expect(el().querySelector('[data-testid="games-forbidden"]')).toBeNull();
    expect(el().querySelectorAll('[data-testid^="games-card-"]').length).toBe(3);
  });

  it('shows the unreachable panel with Retry on a 5xx from either call, and retries', () => {
    http.expectOne('/games/api/federation/manifest').flush(MANIFEST);
    http.expectOne('/games/api/games').flush([], { status: 503, statusText: 'down' });
    fixture.detectChanges();
    const panel = el().querySelector('[data-testid="games-unreachable"]')!;
    expect(panel.textContent).toContain('The games service is not reachable on this deployment.');
    el().querySelector<HTMLButtonElement>('[data-testid="games-retry"]')!.click();
    fixture.detectChanges();
    flushBoth();
    fixture.detectChanges();
    expect(el().querySelectorAll('[data-testid^="games-card-"]').length).toBe(3);
  });

  it('shows the unreachable panel on a network error (status 0)', () => {
    http.expectOne('/games/api/federation/manifest').flush(MANIFEST);
    http.expectOne('/games/api/games').error(new ProgressEvent('error'));
    fixture.detectChanges();
    expect(el().querySelector('[data-testid="games-unreachable"]')).not.toBeNull();
  });

  it('names the games service on a 403 without a gateway denial header', () => {
    http.expectOne('/games/api/federation/manifest').flush(MANIFEST);
    http.expectOne('/games/api/games').flush('', { status: 403, statusText: 'Forbidden' });
    fixture.detectChanges();
    const panel = el().querySelector('[data-testid="games-forbidden"]')!;
    expect(panel.textContent).toContain('The games service refused your account (403).');
    expect(panel.textContent).not.toContain('at the gateway');
  });

  it('shows the forbidden panel with the X-Route-Denied value on 403', () => {
    http.expectOne('/games/api/federation/manifest').flush(MANIFEST);
    http.expectOne('/games/api/games').flush('', {
      status: 403,
      statusText: 'Forbidden',
      headers: { 'X-Route-Denied': '/games/api/**' },
    });
    fixture.detectChanges();
    const panel = el().querySelector('[data-testid="games-forbidden"]')!;
    expect(panel.textContent).toContain('Your account does not hold games access at the gateway.');
    expect(panel.textContent).toContain('/games/api/**');
    expect(el().querySelector('[data-testid="games-back-home"]')?.getAttribute('href')).toBe('/home');
  });

  it('treats 401 as forbidden too', () => {
    http.expectOne('/games/api/federation/manifest').flush(MANIFEST);
    http.expectOne('/games/api/games').flush('', { status: 401, statusText: 'Unauthorized' });
    fixture.detectChanges();
    expect(el().querySelector('[data-testid="games-forbidden"]')).not.toBeNull();
  });

  it('falls back to the letter tile when the thumbnail fails to load', () => {
    flushBoth();
    fixture.detectChanges();
    const chess = el().querySelector('[data-testid="games-card-chess"]')!;
    const img = chess.querySelector('img')!;
    expect(img.getAttribute('src')).toBe('/_remotes/wasm-fragment/chess/icon.svg');
    img.dispatchEvent(new Event('error'));
    fixture.detectChanges();
    expect(chess.querySelector('img')).toBeNull();
    expect(chess.querySelector('.letter')?.textContent?.trim()).toBe('C');
  });

  it('takes a Godot bundle\'s icons from its manifest row: icon.svg, then index.icon.png, then the letter', () => {
    const godot: Game = { id: 9, name: 'Connect Four (Godot)', description: null, slug: 'connect-four-godot', thumbnailUrl: null, sortOrder: 9 };
    flushBoth([godot], {
      generatedAt: '',
      remotes: [{
        slug: 'games-connect-four-godot', displayName: 'Connect Four (Godot)', kind: 'wasm-game',
        remoteEntryUrl: '/_remotes/wasm-fragment/connect-four-godot/index.html', exposedModule: null,
        gameSlug: 'connect-four-godot', version: '0.1.0',
        metadata: '{"sse":true,"hostAuthoritative":true,"engine":"godot","core":"hugmesh-connect-four"}',
      }],
    });
    const card = el().querySelector('[data-testid="games-card-connect-four-godot"]')!;
    expect(card.querySelector('img')!.getAttribute('src')).toBe('/_remotes/wasm-fragment/connect-four-godot/icon.svg');
    card.querySelector('img')!.dispatchEvent(new Event('error'));
    fixture.detectChanges();
    expect(card.querySelector('img')!.getAttribute('src')).toBe('/_remotes/wasm-fragment/connect-four-godot/index.icon.png');
    card.querySelector('img')!.dispatchEvent(new Event('error'));
    fixture.detectChanges();
    expect(card.querySelector('img')).toBeNull();
    expect(card.querySelector('.letter')?.textContent?.trim()).toBe('C');
  });
});

describe('bundleIconUrls', () => {
  const row = (slug: string, metadata: unknown, url: string | null = `/_remotes/wasm-fragment/${slug}/index.html`) =>
    ({ remoteEntryUrl: url, metadata });

  it('gives every bundle icon.svg first, and a Godot export index.icon.png after it', () => {
    expect(bundleIconUrls('chess', row('chess', '{"sse":true}'))).toEqual(['/_remotes/wasm-fragment/chess/icon.svg']);
    expect(bundleIconUrls('character-forge', row('character-forge', '{"engine":"godot","singlePlayer":true}'))).toEqual([
      '/_remotes/wasm-fragment/character-forge/icon.svg',
      '/_remotes/wasm-fragment/character-forge/index.icon.png',
    ]);
  });

  it('reads metadata as a JSON string or an object, and ignores junk', () => {
    expect(bundleIconUrls('x', row('x', { engine: 'godot' }))).toContain('/_remotes/wasm-fragment/x/index.icon.png');
    expect(bundleIconUrls('x', row('x', 'not json'))).toEqual(['/_remotes/wasm-fragment/x/icon.svg']);
    expect(bundleIconUrls('x', row('x', null))).toEqual(['/_remotes/wasm-fragment/x/icon.svg']);
  });

  it('uses the manifest row\'s directory, and falls back to the slug for a SAB bundle with no row', () => {
    expect(bundleIconUrls('chess', row('chess', null, '/_remotes/wasm-fragment/chess-v2/index.html?x=1'))).toEqual([
      '/_remotes/wasm-fragment/chess-v2/icon.svg',
    ]);
    expect(bundleIconUrls('chess-godot', null)).toEqual([
      '/_remotes/wasm-fragment/chess-godot/icon.svg',
      '/_remotes/wasm-fragment/chess-godot/index.icon.png',
    ]);
    // a protocol-relative or absolute URL is not trusted as a directory
    expect(bundleIconUrls('y', row('y', null, '//evil.example/y/index.html'))).toEqual(['/_remotes/wasm-fragment/y/icon.svg']);
  });
});

describe('sessionRoles / isGamesAdminSession', () => {
  const store = (v: string | null) => ({ getItem: () => v });

  it('canonicalises role slugs and admits only the derived games_admin role', () => {
    expect(sessionRoles(store(JSON.stringify({ roles: ['ROLE_GAMES_ADMIN', ' Admin ', 3] })))).toEqual(['games_admin', 'admin']);
    expect(isGamesAdminSession(['games_admin'])).toBeTrue();
    expect(isGamesAdminSession(['admin', 'games', 'user'])).toBeFalse();
    expect(isGamesAdminSession(['superadmin'])).toBeFalse();
  });

  it('is empty for no session, bad JSON, or no storage', () => {
    expect(sessionRoles(store(null))).toEqual([]);
    expect(sessionRoles(store('{oops'))).toEqual([]);
    expect(sessionRoles(store('{"roles":"admin"}'))).toEqual([]);
    expect(sessionRoles(null)).toEqual([]);
  });
});

describe('availabilityOf', () => {
  const wasm = new Set(['chess', 'cave', 'mystery']);
  const game = (slug: string): Game => ({ id: 1, name: slug, description: null, slug, thumbnailUrl: null, sortOrder: 0 });

  it('is here for web-native, new-tab for the five SAB bundles, unavailable without a bundle', () => {
    expect(availabilityOf(game('chess'), wasm)).toBe('here');
    expect(availabilityOf(game('cave'), wasm)).toBe('new-tab');
    expect(availabilityOf(game('three-wizards'), wasm)).toBe('unavailable');
    expect(availabilityOf(game('mystery'), wasm)).toBe('here');
  });
});
