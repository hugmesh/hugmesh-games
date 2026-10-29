import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { GamesAdminOverviewComponent, statTiles } from './admin-overview.component';

const STATS = {
  totalUsers: 4,
  totalSessions: 12,
  currentlyActive: 1,
  averageSessionMinutes: 7.25,
  totalCharacters: 6,
  perUser: [{ casdoorId: 'alice', characterName: 'Al', sessionCount: 3, totalMinutes: 20, online: true }],
};
const SESSIONS = [
  { id: 1, casdoorId: 'alice', characterId: 7, isController: true, connectedAt: 't0', lastPing: 't1' },
  { id: 2, casdoorId: 'bob', characterId: null, isController: false, connectedAt: 't0', lastPing: 't1' },
];

describe('GamesAdminOverviewComponent (A1)', () => {
  let http: HttpTestingController;
  let fixture: ComponentFixture<GamesAdminOverviewComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [GamesAdminOverviewComponent],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(GamesAdminOverviewComponent);
    fixture.detectChanges();
  });

  afterEach(() => http.verify());
  const el = (): HTMLElement => fixture.nativeElement;

  function flush(stats: object = STATS, sessions: object = SESSIONS) {
    http.expectOne('/games/api/admin/stats').flush(stats);
    http.expectOne('/games/api/admin/sessions').flush(sessions);
    fixture.detectChanges();
  }

  it('shows a loading state, then stat tiles for every scalar DTO key and the session table', () => {
    expect(el().querySelector('[data-testid="games-admin-overview"]')).not.toBeNull();
    expect(el().querySelector('[data-testid="games-admin-loading"]')).not.toBeNull();
    flush();
    const tiles = el().querySelectorAll('[data-testid="games-admin-stats"] .tile');
    expect(tiles.length).toBe(5); // perUser (array) is not a tile
    expect(el().querySelector('[data-stat="averageSessionMinutes"]')?.textContent).toBe('7.3');
    const table = el().querySelector('[data-testid="games-admin-sessions-table"]')!;
    expect(table.querySelectorAll('tbody tr').length).toBe(2);
    expect(el().querySelector('[data-testid="games-admin-per-user"]')).not.toBeNull();
  });

  it('shows the empty message when there are no sessions', () => {
    flush(STATS, []);
    expect(el().querySelector('[data-testid="games-admin-sessions-empty"]')?.textContent).toContain('No active player sessions.');
    expect(el().querySelector('[data-testid="games-admin-sessions-table"]')).toBeNull();
  });

  it('ends a session only on the confirming second click and removes the row on 2xx', () => {
    flush();
    const btn = () => el().querySelector<HTMLButtonElement>('[data-testid="games-admin-end-alice"]')!;
    btn().click();
    fixture.detectChanges();
    http.expectNone('/games/api/admin/sessions/alice');
    expect(el().textContent).toContain("End alice's session?");
    btn().click();
    fixture.detectChanges();
    const del = http.expectOne('/games/api/admin/sessions/alice');
    expect(del.request.method).toBe('DELETE');
    del.flush(null, { status: 204, statusText: 'No Content' });
    fixture.detectChanges();
    expect(el().querySelector('[data-session-id="1"]')).toBeNull();
    expect(el().querySelector('[data-session-id="2"]')).not.toBeNull();
  });

  it('keeps the row and shows an inline error when ending fails', () => {
    flush();
    const btn = () => el().querySelector<HTMLButtonElement>('[data-testid="games-admin-end-bob"]')!;
    btn().click();
    fixture.detectChanges();
    btn().click();
    http.expectOne('/games/api/admin/sessions/bob').flush('', { status: 403, statusText: 'Forbidden' });
    fixture.detectChanges();
    expect(el().querySelector('[data-session-id="2"]')).not.toBeNull();
    expect(el().querySelector('[data-testid="games-admin-end-error"]')?.textContent).toContain('refused');
  });

  it('renders the shared admin forbidden panel with X-Route-Denied on 403', () => {
    http.expectOne('/games/api/admin/sessions').flush([]);
    http.expectOne('/games/api/admin/stats').flush('', {
      status: 403,
      statusText: 'Forbidden',
      headers: { 'X-Route-Denied': '/games/api/admin/**' },
    });
    fixture.detectChanges();
    const panel = el().querySelector('[data-testid="games-admin-forbidden"]')!;
    expect(panel.textContent).toContain('ROLE_GAMES_ADMIN = games AND (admin OR mod)');
    expect(panel.textContent).toContain('X-Route-Denied: /games/api/admin/**');
  });

  it('renders the unreachable panel on 5xx', () => {
    http.expectOne('/games/api/admin/sessions').flush([]);
    http.expectOne('/games/api/admin/stats').flush('', { status: 500, statusText: 'err' });
    fixture.detectChanges();
    expect(el().querySelector('[data-testid="games-unreachable"]')).not.toBeNull();
  });
});

describe('statTiles', () => {
  it('renders scalars generically and skips arrays/objects', () => {
    const tiles = statTiles({ a: 1, b: 'x', c: true, d: null, e: [1], f: { g: 1 }, someLongKey: 2 });
    expect(tiles.map((t) => t.key)).toEqual(['a', 'b', 'c', 'd', 'someLongKey']);
    expect(tiles.find((t) => t.key === 'someLongKey')?.label).toBe('Some Long Key');
    expect(tiles.find((t) => t.key === 'd')?.value).toBe('—');
  });
});
