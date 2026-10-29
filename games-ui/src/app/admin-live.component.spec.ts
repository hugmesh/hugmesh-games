import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { BehaviorSubject } from 'rxjs';
import { GamesAdminLiveComponent } from './admin-live.component';
import { GameSession, GamesApiService } from './games-api.service';

const S1: GameSession = {
  id: 'sess-aaaaaaaaaaaaaaaaaaaa',
  name: 'Friday night',
  gameSlug: 'chess',
  creatorCasdoorId: 'alice',
  maxPlayers: 2,
  activePlayers: 1,
  visibility: 'public',
  createdAt: 't0',
  closedAt: null,
  pausedAt: null,
  pausedByCasdoorId: null,
  pauseReason: null,
  members: [],
};
const S2: GameSession = { ...S1, id: 'sess-bbbbbbbbbbbbbbbbbbbb', name: null, gameSlug: 'go', pausedAt: 't1', pauseReason: 'admin' };

/** A fake EventSource the component attaches listeners to; specs push events. */
class FakeEventSource {
  static last: FakeEventSource | null = null;
  readonly listeners = new Map<string, ((ev: Event) => void)[]>();
  onopen: ((ev: Event) => void) | null = null;
  onerror: ((ev: Event) => void) | null = null;
  closed = false;
  constructor(public readonly url: string) {
    FakeEventSource.last = this;
  }
  addEventListener(name: string, fn: (ev: Event) => void) {
    this.listeners.set(name, [...(this.listeners.get(name) ?? []), fn]);
  }
  emit(name: string, data: string) {
    for (const fn of this.listeners.get(name) ?? []) fn(new MessageEvent(name, { data }));
  }
  close() {
    this.closed = true;
  }
}

describe('GamesAdminLiveComponent (A2)', () => {
  let http: HttpTestingController;
  let fixture: ComponentFixture<GamesAdminLiveComponent>;
  let params: BehaviorSubject<ReturnType<typeof convertToParamMap>>;
  let openSpy: jasmine.Spy;

  async function setup(id: string | null) {
    params = new BehaviorSubject(convertToParamMap(id ? { id } : {}));
    await TestBed.configureTestingModule({
      imports: [GamesAdminLiveComponent],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: ActivatedRoute, useValue: { paramMap: params.asObservable(), parent: null } },
      ],
    }).compileComponents();
    const api = TestBed.inject(GamesApiService);
    openSpy = spyOn(api, 'openSessionStream').and.callFake(
      (sid: string) => new FakeEventSource(api.sessionStreamUrl(sid)) as unknown as EventSource,
    );
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(GamesAdminLiveComponent);
    fixture.detectChanges();
  }

  afterEach(() => {
    http.verify();
    TestBed.resetTestingModule();
  });

  const el = (): HTMLElement => fixture.nativeElement;

  function flushList(rows: GameSession[] = [S1, S2]) {
    http.expectOne((r) => r.url === '/games/api/admin/multiplayer-sessions').flush(rows);
    http.expectOne('/games/api/games').flush([{ id: 1, name: 'Chess', slug: 'chess', description: null, thumbnailUrl: null, sortOrder: 0 }]);
    fixture.detectChanges();
  }

  it('lists sessions with a row per session, paused pill and pause/resume buttons', async () => {
    await setup(null);
    expect(el().querySelector('[data-testid="games-admin-live"]')).not.toBeNull();
    flushList();
    expect(el().querySelector(`[data-testid="games-admin-live-row-${S1.id}"]`)).not.toBeNull();
    expect(el().querySelector(`[data-testid="games-admin-live-row-${S2.id}"]`)?.textContent).toContain('paused');
    expect(el().querySelector(`[data-testid="games-admin-pause-${S1.id}"]`)?.textContent?.trim()).toBe('Pause');
    expect(el().querySelector(`[data-testid="games-admin-pause-${S2.id}"]`)?.textContent?.trim()).toBe('Resume');
    expect(el().querySelector('[data-testid="games-admin-live-detail"]')).toBeNull();
  });

  it('shows the empty message when there are no sessions', async () => {
    await setup(null);
    flushList([]);
    expect(el().querySelector('[data-testid="games-admin-live-empty"]')?.textContent).toContain('No multiplayer sessions right now.');
  });

  it('filters the list client-side by the search box', async () => {
    await setup(null);
    flushList();
    fixture.componentInstance.search.set('friday');
    fixture.detectChanges();
    expect(el().querySelectorAll('[data-testid^="games-admin-live-row-"]').length).toBe(1);
  });

  it('re-queries with ?gameSlug when the game filter changes', async () => {
    await setup(null);
    flushList();
    fixture.componentInstance.onGameFilter('chess');
    const req = http.expectOne((r) => r.url === '/games/api/admin/multiplayer-sessions' && r.params.get('gameSlug') === 'chess');
    req.flush([S1]);
    fixture.detectChanges();
    expect(el().querySelectorAll('[data-testid^="games-admin-live-row-"]').length).toBe(1);
  });

  it('pauses via POST /games/api/sessions/{id}/pause and refreshes the row from the response', async () => {
    await setup(null);
    flushList();
    el().querySelector<HTMLButtonElement>(`[data-testid="games-admin-pause-${S1.id}"]`)!.click();
    const req = http.expectOne(`/games/api/sessions/${S1.id}/pause`);
    expect(req.request.method).toBe('POST');
    req.flush({ ...S1, pausedAt: 'now' });
    fixture.detectChanges();
    expect(el().querySelector(`[data-testid="games-admin-pause-${S1.id}"]`)?.textContent?.trim()).toBe('Resume');
  });

  it('resumes via POST .../resume', async () => {
    await setup(null);
    flushList();
    el().querySelector<HTMLButtonElement>(`[data-testid="games-admin-pause-${S2.id}"]`)!.click();
    http.expectOne(`/games/api/sessions/${S2.id}/resume`).flush({ ...S2, pausedAt: null });
    fixture.detectChanges();
    expect(el().querySelector(`[data-testid="games-admin-pause-${S2.id}"]`)?.textContent?.trim()).toBe('Pause');
  });

  it('shows the admin forbidden panel on a 403 list', async () => {
    await setup(null);
    http.expectOne((r) => r.url === '/games/api/admin/multiplayer-sessions').flush('', {
      status: 403,
      statusText: 'Forbidden',
      headers: { 'X-Route-Denied': '/games/api/admin/**' },
    });
    http.expectOne('/games/api/games').flush([]);
    fixture.detectChanges();
    expect(el().querySelector('[data-testid="games-admin-forbidden"]')?.textContent).toContain('/games/api/admin/**');
  });

  it('shows the unreachable panel on a network failure', async () => {
    await setup(null);
    http.expectOne((r) => r.url === '/games/api/admin/multiplayer-sessions').error(new ProgressEvent('error'));
    http.expectOne('/games/api/games').flush([]);
    fixture.detectChanges();
    expect(el().querySelector('[data-testid="games-unreachable"]')).not.toBeNull();
  });

  it('opens the detail terminal for sessions/:id after a successful probe and renders colour-coded events', async () => {
    await setup(S1.id);
    flushList();
    // the auth probe of the stream URL
    http.expectOne(`/games/api/admin/sessions/${S1.id}/stream`).flush('');
    fixture.detectChanges();
    expect(openSpy).toHaveBeenCalledWith(S1.id);
    const es = FakeEventSource.last!;
    es.onopen?.(new Event('open'));
    es.emit('movement', '{"x":1}');
    es.emit('session-control', '{"type":"PAUSED"}');
    fixture.detectChanges();
    const term = el().querySelector('[data-testid="games-admin-live-terminal"]')!;
    expect(term.textContent).toContain('movement');
    expect(term.textContent).toContain('{"x":1}');
    expect(term.textContent).toContain('session-control');
    // per-channel toggle hides a channel
    fixture.componentInstance.toggleChannel('movement', false);
    fixture.detectChanges();
    expect(term.textContent).not.toContain('{"x":1}');
    // clear empties the terminal
    el().querySelector<HTMLButtonElement>('[data-testid="games-admin-live-clear"]')!.click();
    fixture.detectChanges();
    expect(term.textContent).toContain('Waiting for events');
    // stream error -> reconnecting banner
    es.onerror?.(new Event('error'));
    fixture.detectChanges();
    expect(el().querySelector('[data-testid="games-admin-live-stream-banner"]')?.textContent).toContain('Stream disconnected — reconnecting');
    // leaving closes the source
    fixture.destroy();
    expect(es.closed).toBeTrue();
  });

  it('shows the forbidden panel (not a terminal) when the stream probe is refused', async () => {
    await setup(S1.id);
    flushList();
    http.expectOne(`/games/api/admin/sessions/${S1.id}/stream`).flush('', {
      status: 403,
      statusText: 'Forbidden',
      headers: { 'X-Route-Denied': '/games/api/admin/**' },
    });
    fixture.detectChanges();
    expect(openSpy).not.toHaveBeenCalled();
    const detail = el().querySelector('[data-testid="games-admin-live-detail"]')!;
    expect(detail.querySelector('[data-testid="games-admin-forbidden"]')).not.toBeNull();
    expect(detail.querySelector('[data-testid="games-admin-live-terminal"]')).toBeNull();
  });

  it('recovers only on the confirming second click', async () => {
    await setup(S1.id);
    flushList();
    http.expectOne(`/games/api/admin/sessions/${S1.id}/stream`).flush('');
    fixture.detectChanges();
    const btn = () => el().querySelector<HTMLButtonElement>('[data-testid="games-admin-recover"]')!;
    btn().click();
    fixture.detectChanges();
    http.expectNone(`/games/api/admin/realtime/${S1.id}/recover`);
    btn().click();
    const req = http.expectOne(`/games/api/admin/realtime/${S1.id}/recover`);
    expect(req.request.method).toBe('POST');
    req.flush(null);
    fixture.detectChanges();
    expect(el().querySelector('[data-testid="games-admin-recover-result"]')?.textContent).toContain('Recovery requested');
  });
});
