import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { ApiBridgeService } from './api-bridge.service';

/**
 * Pins the Godot-iframe ↔ host-shell API bridge contract.
 *
 *   Iframe → host:    postMessage({type:'SD_API_REQUEST',  payload:{requestId,method,path,sessionId,body}})
 *   Iframe → host:    postMessage({type:'SD_VIEW_EGRESS',  payload:{slot,characterId,sessionId,...}})
 *   Host  → iframe:   postMessage({type:'SD_API_RESPONSE', payload:{requestId,status,body}})
 *
 * The bridge attaches the X-Session-Id header when the request payload
 * carries a non-empty sessionId, which is what flips the BFF's
 * ControlController into multi-player per-session sink routing.
 */
describe('ApiBridgeService', () => {
  let svc: ApiBridgeService;
  let httpMock: HttpTestingController;
  let postMessageSpy: jasmine.Spy;
  let fakeSource: { postMessage: jasmine.Spy };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    svc = TestBed.inject(ApiBridgeService);
    httpMock = TestBed.inject(HttpTestingController);
    postMessageSpy = jasmine.createSpy('source.postMessage');
    fakeSource = { postMessage: postMessageSpy };
    svc.start();
  });

  afterEach(() => httpMock.verify());

  function dispatch(type: string, payload: unknown): void {
    window.dispatchEvent(Object.assign(
      new MessageEvent('message', { data: { type, payload } }),
      { source: fakeSource as unknown as Window },
    ));
  }

  it('SD_API_REQUEST: GETs the path and posts the response back to the iframe', async () => {
    dispatch('SD_API_REQUEST', {
      requestId: 7, method: 'GET', path: '/api/sessions/abc', sessionId: '', body: null,
    });
    const req = httpMock.expectOne('/api/sessions/abc');
    expect(req.request.method).toBe('GET');
    expect(req.request.headers.get('X-Session-Id')).toBeNull();
    req.flush({ id: 'abc', name: 'Demo' });
    await Promise.resolve();
    expect(postMessageSpy).toHaveBeenCalled();
    const sent = postMessageSpy.calls.mostRecent().args[0];
    expect(sent.type).toBe('SD_API_RESPONSE');
    expect(sent.payload.requestId).toBe(7);
    expect(sent.payload.status).toBe(200);
    expect(sent.payload.body.id).toBe('abc');
  });

  it('attaches X-Session-Id when payload has a non-empty sessionId', async () => {
    dispatch('SD_API_REQUEST', {
      requestId: 8, method: 'POST', path: '/api/controls/3', sessionId: 'sess-α',
      body: { action: 'MOVE_FORWARD', pressed: true, timestamp: 0 },
    });
    const req = httpMock.expectOne('/api/controls/3');
    expect(req.request.method).toBe('POST');
    expect(req.request.headers.get('X-Session-Id')).toBe('sess-α');
    expect(req.request.body.action).toBe('MOVE_FORWARD');
    req.flush(null, { status: 204, statusText: 'No Content' });
    await Promise.resolve();
    expect(postMessageSpy.calls.mostRecent().args[0].payload.status).toBe(204);
  });

  it('errors flow back as the matching status + error body', async () => {
    dispatch('SD_API_REQUEST', {
      requestId: 9, method: 'GET', path: '/api/sessions/missing', sessionId: '', body: null,
    });
    const req = httpMock.expectOne('/api/sessions/missing');
    req.flush({ message: 'session not found' }, { status: 404, statusText: 'Not Found' });
    await Promise.resolve();
    const sent = postMessageSpy.calls.mostRecent().args[0];
    expect(sent.payload.requestId).toBe(9);
    expect(sent.payload.status).toBe(404);
    expect(sent.payload.body.message).toBe('session not found');
  });

  it('SD_VIEW_EGRESS: POSTs to /api/view with the X-Session-Id header (fire-and-forget)', () => {
    dispatch('SD_VIEW_EGRESS', {
      slot: 0, characterId: 1, sessionId: 'sess-β',
      x: 1.0, y: 2.0, z: 3.0, yaw: 0.5, pitch: 0.0, fov: 90,
    });
    const req = httpMock.expectOne('/api/view');
    expect(req.request.method).toBe('POST');
    expect(req.request.headers.get('X-Session-Id')).toBe('sess-β');
    expect(req.request.body.slot).toBe(0);
    req.flush(null, { status: 202, statusText: 'Accepted' });
    // No postMessage round-trip for view egress.
  });

  it('ignores unrelated messages', () => {
    dispatch('SD_OTHER_THING', { foo: 'bar' });
    httpMock.expectNone(() => true);
    expect(postMessageSpy).not.toHaveBeenCalled();
  });
});
