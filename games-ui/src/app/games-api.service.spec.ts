import { HttpErrorResponse, HttpHeaders, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed, fakeAsync, tick } from '@angular/core/testing';
import { GamesApiService, classifyFailure } from './games-api.service';

describe('classifyFailure', () => {
  const err = (status: number, headers: Record<string, string> = {}) =>
    new HttpErrorResponse({ status, headers: new HttpHeaders(headers), url: '/x' });

  it('maps 401/403 to forbidden and reads the gateway denial header', () => {
    expect(classifyFailure(err(403, { 'X-Route-Denied': '/games/**' }))).toEqual(
      jasmine.objectContaining({ kind: 'forbidden', status: 403, denied: '/games/**' }),
    );
    expect(classifyFailure(err(401)).kind).toBe('forbidden');
    expect(classifyFailure(err(403, { 'X-Resource-Denied': 'game:chess' })).denied).toBe('game:chess');
  });

  it('maps 404 to notFound, 0/5xx to unreachable, everything else to other', () => {
    expect(classifyFailure(err(404)).kind).toBe('notFound');
    expect(classifyFailure(err(0)).kind).toBe('unreachable');
    expect(classifyFailure(err(503)).kind).toBe('unreachable');
    expect(classifyFailure(err(422)).kind).toBe('other');
    expect(classifyFailure(new Error('boom')).kind).toBe('unreachable');
  });
});

describe('GamesApiService', () => {
  let api: GamesApiService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    api = TestBed.inject(GamesApiService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('only ever calls absolute same-origin /games/api paths, never bare /api', () => {
    api.listGames().subscribe();
    api.adminStats().subscribe();
    api.sessionLogs('a b').subscribe();
    const reqs = http.match(() => true);
    expect(reqs.length).toBe(3);
    for (const r of reqs) {
      expect(r.request.url.startsWith('/games/api/')).toBeTrue();
      expect(r.request.withCredentials).toBeTrue();
    }
    expect(reqs[2].request.url).toBe('/games/api/session-logs/admin/a%20b');
    reqs.forEach((r) => r.flush([]));
    expect(api.sessionStreamUrl('s1')).toBe('/games/api/admin/sessions/s1/stream');
  });

  it('probeStream treats a timeout (bytes admitted, stream never completes) as success', fakeAsync(() => {
    let done = false;
    api.probeStream('s1', 50).subscribe({ complete: () => (done = true) });
    const req = http.expectOne('/games/api/admin/sessions/s1/stream');
    expect(req.request.headers.get('Accept')).toBe('text/event-stream');
    tick(60);
    expect(done).toBeTrue();
    expect(req.cancelled).toBeTrue();
  }));

  it('probeStream propagates a 403 so the caller can show the forbidden panel', fakeAsync(() => {
    let failed: unknown = null;
    api.probeStream('s1', 50).subscribe({ error: (e) => (failed = e) });
    http.expectOne('/games/api/admin/sessions/s1/stream').flush('', { status: 403, statusText: 'F' });
    tick(1);
    expect(failed).toBeInstanceOf(HttpErrorResponse);
  }));
});
