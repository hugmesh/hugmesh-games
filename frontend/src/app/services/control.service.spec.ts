import { TestBed } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { ControlService } from './control.service';
import { GameAction } from '../models/game-state.model';

/**
 * Wire-format coverage for ControlService. The recent JWT-first
 * security fix removed the manually-attached X-Casdoor-Id header;
 * these tests pin that the request body shape is correct, the
 * inputSuppressed gate works, and no auth header is fabricated
 * client-side (the http-auth interceptor handles Authorization).
 */
describe('ControlService', () => {
  let svc: ControlService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withXhr()), provideHttpClientTesting()],
    });
    svc = TestBed.inject(ControlService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('POSTs an action + pressed flag + timestamp to /api/controls/{characterId}', () => {
    svc.sendControl('MOVE_FORWARD' as GameAction, true);

    const req = httpMock.expectOne(r => r.url.endsWith('/api/controls/1'));
    expect(req.request.method).toBe('POST');
    const body = req.request.body as { action: string; pressed: boolean; timestamp: number };
    expect(body.action).toBe('MOVE_FORWARD');
    expect(body.pressed).toBeTrue();
    expect(typeof body.timestamp).toBe('number');
    req.flush(null, { status: 204, statusText: 'No Content' });
  });

  it('does NOT attach an X-Casdoor-Id header (Authorization comes from http-auth interceptor)', () => {
    svc.sendControl('JUMP' as GameAction, true);

    const req = httpMock.expectOne(r => r.url.endsWith('/api/controls/1'));
    expect(req.request.headers.has('X-Casdoor-Id')).toBeFalse();
    req.flush(null, { status: 204, statusText: 'No Content' });
  });

  it('drops the event entirely when inputSuppressed$ is true', () => {
    svc.setInputSuppressed(true);
    svc.sendControl('MOVE_FORWARD' as GameAction, true);

    httpMock.expectNone(r => r.url.includes('/api/controls'));
  });

  it('uses the configured characterId in the path', () => {
    svc.setCharacterId(42);
    svc.sendControl('INTERACT' as GameAction, true);

    httpMock.expectOne(r => r.url.endsWith('/api/controls/42'))
            .flush(null, { status: 204, statusText: 'No Content' });
  });

  it('emits the event on controlPressed$ before the HTTP call', (done) => {
    let emitted = 0;
    svc.controlPressed$.subscribe(e => {
      emitted++;
      expect(e.action).toBe('CAST_INSTANT_1');
      done();
    });
    svc.sendControl('CAST_INSTANT_1' as GameAction, true);
    expect(emitted).toBe(1);
    httpMock.expectOne(r => r.url.endsWith('/api/controls/1'))
            .flush(null, { status: 204, statusText: 'No Content' });
  });
});
