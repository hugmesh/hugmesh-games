import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { MultiplayerSessionService, GameSession } from './multiplayer-session.service';

/**
 * Wire-format coverage for MultiplayerSessionService. Pins:
 *   • the right URL for each verb
 *   • body shape (gameSlug + name + maxPlayers etc.)
 *   • query-string handling for listOpen's optional gameSlug
 *
 * No auth header fabrication; the host's interceptor wraps Authorization
 * via shareAll-singleton HttpClient.
 */
describe('MultiplayerSessionService', () => {
  let svc: MultiplayerSessionService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    svc = TestBed.inject(MultiplayerSessionService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  function sessionFixture(over: Partial<GameSession> = {}): GameSession {
    return {
      id: 'sess-1', name: 'Demo', gameSlug: 'three-wizards',
      creatorCasdoorId: 'alice', maxPlayers: 3, activePlayers: 1,
      visibility: 'public',
      createdAt: '2026-04-27T10:00:00Z', closedAt: null,
      members: [],
      ...over,
    };
  }

  it('listOpen() with no slug GETs /api/sessions', () => {
    svc.listOpen().subscribe();
    const req = httpMock.expectOne('/api/sessions');
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('listOpen(slug) GETs /api/sessions?gameSlug=…', () => {
    svc.listOpen('three-wizards').subscribe();
    const req = httpMock.expectOne(r => r.urlWithParams === '/api/sessions?gameSlug=three-wizards');
    expect(req.request.method).toBe('GET');
    req.flush([sessionFixture()]);
  });

  it('create() POSTs name + gameSlug + maxPlayers + characterId + slotIndex', () => {
    svc.create({ gameSlug: 'three-wizards', name: 'Tower run',
                 maxPlayers: 3, characterId: 1, slotIndex: 0 }).subscribe();
    const req = httpMock.expectOne('/api/sessions');
    expect(req.request.method).toBe('POST');
    const body = req.request.body;
    expect(body.gameSlug).toBe('three-wizards');
    expect(body.name).toBe('Tower run');
    expect(body.maxPlayers).toBe(3);
    expect(body.characterId).toBe(1);
    expect(body.slotIndex).toBe(0);
    req.flush(sessionFixture({ name: 'Tower run' }));
  });

  it('join() POSTs to /api/sessions/{id}/join with characterId + slotIndex', () => {
    svc.join('sess-7', { characterId: 2, slotIndex: 1 }).subscribe();
    const req = httpMock.expectOne('/api/sessions/sess-7/join');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ characterId: 2, slotIndex: 1 });
    req.flush(sessionFixture());
  });

  it('leave() DELETEs /api/sessions/{id}/me', () => {
    svc.leave('sess-7').subscribe();
    const req = httpMock.expectOne('/api/sessions/sess-7/me');
    expect(req.request.method).toBe('DELETE');
    req.flush(null, { status: 200, statusText: 'OK' });
  });

  it('close() DELETEs /api/sessions/{id}', () => {
    svc.close('sess-7').subscribe();
    const req = httpMock.expectOne('/api/sessions/sess-7');
    expect(req.request.method).toBe('DELETE');
    req.flush(null, { status: 200, statusText: 'OK' });
  });

  it('get() GETs /api/sessions/{id}', () => {
    svc.get('sess-7').subscribe(s => expect(s.id).toBe('sess-1'));
    const req = httpMock.expectOne('/api/sessions/sess-7');
    expect(req.request.method).toBe('GET');
    req.flush(sessionFixture());
  });
});
