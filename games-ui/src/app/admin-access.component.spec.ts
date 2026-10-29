import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { ACCESS_EMPTY, ACCESS_EXPLANATION, GamesAdminAccessComponent } from './admin-access.component';

const GAMES = [
  { id: 1, name: 'Chess', slug: 'chess', description: null, thumbnailUrl: null, sortOrder: 0 },
  { id: 2, name: 'Go', slug: 'go', description: null, thumbnailUrl: null, sortOrder: 1 },
];
const RULES = [
  { id: 10, gameId: 1, scope: 'all', scopeValue: null, grantedBy: 'seed', grantedAt: 't0' },
  { id: 11, gameId: 1, scope: 'role', scopeValue: 'games', grantedBy: 'alice', grantedAt: 't1' },
];
const ROLES = [{ name: 'games', displayName: 'Games', description: null, enabled: true }];

describe('GamesAdminAccessComponent (A4)', () => {
  let http: HttpTestingController;
  let fixture: ComponentFixture<GamesAdminAccessComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [GamesAdminAccessComponent],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(GamesAdminAccessComponent);
    fixture.detectChanges();
  });

  afterEach(() => http.verify());
  const el = (): HTMLElement => fixture.nativeElement;

  function boot(rules: object = RULES, roles: object = ROLES, rolesStatus = 200) {
    http.expectOne('/games/api/games').flush(GAMES);
    if (rolesStatus === 200) http.expectOne('/games/api/admin/roles').flush(roles);
    else http.expectOne('/games/api/admin/roles').flush('', { status: rolesStatus, statusText: 'x' });
    fixture.detectChanges();
    http.expectOne('/games/api/admin/games/chess/access').flush(rules);
    fixture.detectChanges();
  }

  it('loads games, selects the first and lists its rules with the explanation', () => {
    expect(el().querySelector('[data-testid="games-admin-access"]')).not.toBeNull();
    boot();
    expect(el().textContent).toContain(ACCESS_EXPLANATION);
    expect(el().querySelector<HTMLSelectElement>('[data-testid="games-admin-access-game"]')).not.toBeNull();
    expect(el().querySelector('[data-testid="games-admin-access-row-0"]')?.textContent).toContain('all');
    expect(el().querySelector('[data-testid="games-admin-access-row-1"]')?.textContent).toContain('games');
    expect(el().querySelector('[data-testid="games-admin-access-add"]')).not.toBeNull();
  });

  it('shows the empty warning when a game has no rules', () => {
    boot([]);
    expect(el().querySelector('[data-testid="games-admin-access-empty"]')?.textContent).toContain(ACCESS_EMPTY);
  });

  it('reloads rules when another game is selected', () => {
    boot();
    fixture.componentInstance.selectGame('go');
    http.expectOne('/games/api/admin/games/go/access').flush([]);
    fixture.detectChanges();
    expect(el().querySelector('[data-testid="games-admin-access-empty"]')).not.toBeNull();
  });

  it('adds a role rule via POST with {scope, value} and appends the row', () => {
    boot();
    fixture.componentInstance.newScope.set('role');
    fixture.componentInstance.newValue.set('games');
    fixture.detectChanges();
    expect(el().querySelector('select[data-testid="games-admin-access-value"]')).not.toBeNull();
    el().querySelector<HTMLButtonElement>('[data-testid="games-admin-access-add"]')!.click();
    const req = http.expectOne('/games/api/admin/games/chess/access');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ scope: 'role', value: 'games' });
    req.flush({ id: 12, gameId: 1, scope: 'role', scopeValue: 'games', grantedBy: 'me', grantedAt: 't2' });
    fixture.detectChanges();
    expect(el().querySelector('[data-testid="games-admin-access-row-2"]')).not.toBeNull();
  });

  it('adds an all rule with value null, and refuses an empty role/user value without calling', () => {
    boot();
    fixture.componentInstance.newScope.set('all');
    el().querySelector<HTMLButtonElement>('[data-testid="games-admin-access-add"]')!.click();
    const req = http.expectOne('/games/api/admin/games/chess/access');
    expect(req.request.body).toEqual({ scope: 'all', value: null });
    req.flush({ id: 13, gameId: 1, scope: 'all', scopeValue: null, grantedBy: 'me', grantedAt: 't' });
    fixture.detectChanges();
    fixture.componentInstance.newScope.set('user');
    fixture.componentInstance.newValue.set('  ');
    fixture.componentInstance.add();
    http.expectNone((r) => r.method === 'POST');
    fixture.detectChanges();
    expect(el().querySelector('[data-testid="games-admin-access-error"]')?.textContent).toContain('needs a value');
  });

  it('removes a rule only on the confirming second click, via DELETE ?scope=&value=', () => {
    boot();
    const btn = () => el().querySelector<HTMLButtonElement>('[data-testid="games-admin-access-remove-1"]')!;
    btn().click();
    fixture.detectChanges();
    http.expectNone((r) => r.method === 'DELETE');
    btn().click();
    const req = http.expectOne((r) => r.method === 'DELETE' && r.url === '/games/api/admin/games/chess/access');
    expect(req.request.params.get('scope')).toBe('role');
    expect(req.request.params.get('value')).toBe('games');
    req.flush(null, { status: 204, statusText: 'nc' });
    fixture.detectChanges();
    expect(el().querySelectorAll('[data-testid^="games-admin-access-row-"]').length).toBe(1);
  });

  it('falls back to a free-text role input with a note when the role catalog is unavailable', () => {
    boot(RULES, [], 502);
    fixture.componentInstance.newScope.set('role');
    fixture.detectChanges();
    expect(el().querySelector('input[data-testid="games-admin-access-value"]')).not.toBeNull();
    expect(el().querySelector('[data-testid="games-admin-access-roles-note"]')?.textContent).toContain('not available on this mesh');
  });

  it('shows the admin forbidden panel when the rules call is refused', () => {
    http.expectOne('/games/api/games').flush(GAMES);
    http.expectOne('/games/api/admin/roles').flush(ROLES);
    fixture.detectChanges();
    http.expectOne('/games/api/admin/games/chess/access').flush('', { status: 403, statusText: 'F', headers: { 'X-Route-Denied': '/games/api/admin/**' } });
    fixture.detectChanges();
    expect(el().querySelector('[data-testid="games-admin-forbidden"]')?.textContent).toContain('/games/api/admin/**');
  });

  it('shows the unreachable panel when the games list fails', () => {
    http.expectOne('/games/api/games').flush('', { status: 503, statusText: 'down' });
    http.expectOne('/games/api/admin/roles').flush(ROLES);
    fixture.detectChanges();
    expect(el().querySelector('[data-testid="games-unreachable"]')).not.toBeNull();
  });
});
