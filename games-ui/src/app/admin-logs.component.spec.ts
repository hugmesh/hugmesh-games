import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { GamesAdminLogsComponent, buildTree } from './admin-logs.component';
import { SessionLogEntry } from './games-api.service';

const row = (over: Partial<SessionLogEntry>): SessionLogEntry => ({
  sessionId: 's1',
  sessionName: 'Night',
  gameSlug: 'chess',
  visibility: 'public',
  creatorCasdoorId: 'alice',
  memberCasdoorId: 'alice',
  characterId: 1,
  slotIndex: 0,
  wasCreator: true,
  joinedAt: 't0',
  leftAt: null,
  sessionClosedAt: null,
  sessionPausedAt: null,
  sessionPauseReason: null,
  ...over,
});

const ROWS: SessionLogEntry[] = [
  row({}),
  row({ memberCasdoorId: 'bob', wasCreator: false, slotIndex: 1 }),
  row({ sessionId: 's2', sessionName: null, gameSlug: 'go', sessionPausedAt: 'tp', sessionPauseReason: 'afk' }),
];

describe('GamesAdminLogsComponent (A3)', () => {
  let http: HttpTestingController;
  let fixture: ComponentFixture<GamesAdminLogsComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [GamesAdminLogsComponent],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(GamesAdminLogsComponent);
    fixture.detectChanges();
  });

  afterEach(() => http.verify());
  const el = (): HTMLElement => fixture.nativeElement;

  function query(id: string) {
    fixture.componentInstance.query.set(id);
    fixture.componentInstance.load();
    fixture.detectChanges();
  }

  it('starts with the prompt and makes no call', () => {
    expect(el().querySelector('[data-testid="games-admin-logs"]')).not.toBeNull();
    expect(el().querySelector('[data-testid="games-admin-logs-query"]')).not.toBeNull();
    expect(el().querySelector('[data-testid="games-admin-logs-prompt"]')?.textContent).toContain('Enter a player id to load their session log.');
    http.expectNone((r) => r.url.startsWith('/games/api/session-logs'));
  });

  it('loads the tree for a player id: game -> sessions -> events, counts, paused marker', () => {
    query('alice');
    http.expectOne('/games/api/session-logs/admin/alice').flush(ROWS);
    fixture.detectChanges();
    const tree = el().querySelector('[data-testid="games-admin-logs-tree"]')!;
    expect(tree.querySelectorAll(':scope > .node').length).toBe(2);
    expect(tree.textContent).toContain('chess');
    expect(tree.textContent).toContain('2 events');
    // expand chess -> session s1 -> entries
    (tree.querySelector(':scope > .node > .node-head') as HTMLButtonElement).click();
    fixture.detectChanges();
    const sessionHead = tree.querySelector('.children .node-head') as HTMLButtonElement;
    expect(sessionHead.textContent).toContain('Night');
    sessionHead.click();
    fixture.detectChanges();
    expect(tree.querySelectorAll('.events tbody tr').length).toBe(2);
    // the go session is paused
    const go = tree.querySelectorAll(':scope > .node')[1] as HTMLElement;
    (go.querySelector('.node-head') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(go.textContent).toContain('paused');
  });

  it('search auto-expands matching nodes and hides the rest', () => {
    query('alice');
    http.expectOne('/games/api/session-logs/admin/alice').flush(ROWS);
    fixture.detectChanges();
    fixture.componentInstance.search.set('bob');
    fixture.detectChanges();
    const tree = el().querySelector('[data-testid="games-admin-logs-tree"]')!;
    expect(tree.querySelectorAll(':scope > .node').length).toBe(1);
    expect(tree.querySelectorAll('.events tbody tr').length).toBe(1);
    expect(tree.textContent).toContain('bob');
  });

  it('shows "No sessions recorded" for an empty list and for a 404', () => {
    query('ghost');
    http.expectOne('/games/api/session-logs/admin/ghost').flush([]);
    fixture.detectChanges();
    expect(el().querySelector('[data-testid="games-admin-logs-none"]')?.textContent).toContain('No sessions recorded for ghost.');
    query('ghost2');
    http.expectOne('/games/api/session-logs/admin/ghost2').flush('', { status: 404, statusText: 'nf' });
    fixture.detectChanges();
    expect(el().querySelector('[data-testid="games-admin-logs-none"]')?.textContent).toContain('No sessions recorded for ghost2.');
  });

  it('shows the admin forbidden panel on 403 and the unreachable panel on 5xx', () => {
    query('alice');
    http.expectOne('/games/api/session-logs/admin/alice').flush('', { status: 403, statusText: 'F', headers: { 'X-Route-Denied': '/games/api/session-logs/admin/**' } });
    fixture.detectChanges();
    expect(el().querySelector('[data-testid="games-admin-forbidden"]')?.textContent).toContain('/games/api/session-logs/admin/**');
    query('alice');
    http.expectOne('/games/api/session-logs/admin/alice').flush('', { status: 500, statusText: 'E' });
    fixture.detectChanges();
    expect(el().querySelector('[data-testid="games-unreachable"]')).not.toBeNull();
  });
});

describe('buildTree', () => {
  it('groups by game then session, keeping entries in order', () => {
    const t = buildTree(ROWS);
    expect(t.map((g) => g.slug)).toEqual(['chess', 'go']);
    expect(t[0].sessions[0].entries.length).toBe(2);
    expect(t[1].sessions[0].paused).toBeTrue();
  });
});
