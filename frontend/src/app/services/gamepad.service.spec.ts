import { TestBed } from '@angular/core/testing';

import { GamepadService } from './gamepad.service';

/**
 * hugmesh-games#13 (decision 103c): the gamepad mirror must never POST an
 * event under the literal slug 'unknown' — it used to be the default, the
 * BFF's gate 2 refuses it (no games row), and before that it was persisted
 * under a slug no game owns — and a failed mirror must not be silent.
 *
 * `emit` is reached through the private `onConnect` handler (the same path a
 * real `gamepadconnected` fires), because a `GamepadEvent` cannot be
 * constructed around a fake `Gamepad` in a browser test.
 */
describe('GamepadService', () => {
  let svc: GamepadService;
  let fetchSpy: jasmine.Spy;
  let warnSpy: jasmine.Spy;
  let frame: { postMessage: jasmine.Spy };

  const pad = { index: 0, id: 'Pro Controller', mapping: 'standard', buttons: [], axes: [] };

  function connect(): void {
    (svc as any).onConnect({ gamepad: pad });
  }

  async function settle(): Promise<void> {
    await new Promise(resolve => setTimeout(resolve, 0));
  }

  function postedBodies(): any[] {
    return fetchSpy.calls.all().map(c => JSON.parse((c.args[1] as RequestInit).body as string));
  }

  beforeEach(() => {
    TestBed.configureTestingModule({});
    svc = TestBed.inject(GamepadService);
    fetchSpy = spyOn(window, 'fetch').and.returnValue(Promise.resolve(new Response(null, { status: 202 })));
    warnSpy = spyOn(console, 'warn');
    frame = { postMessage: jasmine.createSpy('postMessage') };
    (window as any).__HUGMESH_ACTIVE_CHARACTER_ID__ = 7;
  });

  afterEach(() => {
    delete (window as any).__HUGMESH_ACTIVE_CHARACTER_ID__;
  });

  it('sends nothing, to the BFF or the frame, when bound without a slug', () => {
    svc.bindGameWindow(frame as unknown as Window, '', 'sess-1');
    connect();
    expect(fetchSpy).not.toHaveBeenCalled();
    expect(frame.postMessage).not.toHaveBeenCalled();
  });

  it("never POSTs the literal slug 'unknown', even when a caller passes it", () => {
    svc.bindGameWindow(frame as unknown as Window, 'unknown', 'sess-1');
    connect();
    expect(fetchSpy).not.toHaveBeenCalled();
    expect(postedBodies().some(b => b.gameSlug === 'unknown')).toBeFalse();
  });

  it('mirrors under the real slug once one is bound', () => {
    svc.bindGameWindow(frame as unknown as Window, 'chess', 'sess-1');
    connect();
    expect(fetchSpy).toHaveBeenCalledTimes(1);
    const [url] = fetchSpy.calls.mostRecent().args as [string, RequestInit];
    expect(url).toBe('/api/input/7');
    expect(postedBodies()[0]).toEqual(jasmine.objectContaining({
      gameSlug: 'chess', sessionId: 'sess-1', kind: 'gamepad', type: 'connected',
    }));
    expect(frame.postMessage).toHaveBeenCalledTimes(1);
    expect(frame.postMessage.calls.mostRecent().args[0].gameSlug).toBe('chess');
  });

  it('logs a refused mirror (non-2xx) once, not per event', async () => {
    fetchSpy.and.returnValue(Promise.resolve(new Response(null, { status: 405 })));
    svc.bindGameWindow(frame as unknown as Window, 'chess', 'sess-1');
    connect();
    connect();
    connect();
    await settle();
    expect(fetchSpy).toHaveBeenCalledTimes(3);
    expect(warnSpy).toHaveBeenCalledTimes(1);
    expect(String(warnSpy.calls.mostRecent().args[0])).toContain('HTTP 405');
  });

  it('logs a rejected mirror fetch once instead of swallowing it', async () => {
    fetchSpy.and.returnValue(Promise.reject(new TypeError('network down')));
    svc.bindGameWindow(frame as unknown as Window, 'chess', 'sess-1');
    connect();
    connect();
    await settle();
    expect(warnSpy).toHaveBeenCalledTimes(1);
    expect(String(warnSpy.calls.mostRecent().args[0])).toContain('network down');
  });

  it('logs again after a new game is bound', async () => {
    fetchSpy.and.returnValue(Promise.resolve(new Response(null, { status: 405 })));
    svc.bindGameWindow(frame as unknown as Window, 'chess', 'sess-1');
    connect();
    await settle();
    svc.bindGameWindow(frame as unknown as Window, 'tron', 'sess-2');
    connect();
    await settle();
    expect(warnSpy).toHaveBeenCalledTimes(2);
  });
});
