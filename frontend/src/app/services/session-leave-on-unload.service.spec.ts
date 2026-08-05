import { TestBed } from '@angular/core/testing';

import { SessionLeaveOnUnloadService } from './session-leave-on-unload.service';
import { SessionControlService } from './session-control.service';
import { AuthService } from './auth.service';

/**
 * Specs for the browser half of the closed-tab ghost fix.
 *
 * The negative cases carry the weight. Firing the unload-leave is easy; NOT
 * firing it from a tab that has been superseded by a second tab of the same
 * player is the part that, if it regresses, evicts somebody who is sitting
 * right in front of the running game — a strictly worse bug than the ghost
 * seat this service exists to prevent.
 *
 * The transport is asserted directly rather than through `HttpTestingController`
 * because it deliberately is NOT `HttpClient`: an in-flight `HttpClient`
 * request is cancelled when the document tears down, and `navigator.sendBeacon`
 * cannot carry the bearer `Authorization` header `/api/sessions/**` requires.
 * `fetch(..., { keepalive: true })` is the only shape that survives teardown
 * AND authenticates, so the specs pin that shape.
 */
describe('SessionLeaveOnUnloadService', () => {
  let svc: SessionLeaveOnUnloadService;
  let control: SessionControlService;
  let fetchSpy: jasmine.Spy;

  /** Stand-in for AuthService — only the token accessor is reached. */
  class FakeAuthService {
    token: string | null = 'jwt-abc';
    getAccessToken(): string | null { return this.token; }
  }
  let auth: FakeAuthService;

  beforeEach(() => {
    auth = new FakeAuthService();
    TestBed.configureTestingModule({
      providers: [{ provide: AuthService, useValue: auth }],
    });
    svc = TestBed.inject(SessionLeaveOnUnloadService);
    control = TestBed.inject(SessionControlService);
    fetchSpy = spyOn(window, 'fetch').and.returnValue(Promise.resolve(new Response(null, { status: 200 })));
    svc.start();
  });

  /** Fire the real event the service listens for. */
  function closeTab(): void {
    window.dispatchEvent(new PageTransitionEvent('pagehide', { persisted: false }));
  }

  it('releases the seat on pagehide with a keepalive fetch, not sendBeacon', () => {
    svc.track('sess-7');

    closeTab();

    expect(fetchSpy).toHaveBeenCalledTimes(1);
    const [url, init] = fetchSpy.calls.mostRecent().args as [string, RequestInit];
    expect(url).toBe('/api/sessions/sess-7/me?unload=true');
    expect(init.method).toBe('DELETE');
    // keepalive is the whole point: without it the browser cancels the
    // request as the document tears down and the seat leaks anyway.
    expect(init.keepalive).toBeTrue();
    // sendBeacon has no header slot; /api/sessions/** is .authenticated(),
    // so a beacon would be silently 401'd.
    expect((init.headers as Record<string, string>)['Authorization']).toBe('Bearer jwt-abc');
  });

  it('does NOT fire from a tab another tab has taken over', () => {
    // The takeover freezes the old tab but leaves it open, so its pagehide
    // still fires — minutes or hours later. left_at is per MEMBER, not per
    // tab, so honouring it would evict the player the NEW tab is playing as.
    svc.track('sess-7');
    control.superseded.set(true);

    closeTab();

    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it('stays silent when this tab holds no seat', () => {
    closeTab();
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it('stays silent once the seat has been released in-app', () => {
    svc.track('sess-7');
    svc.track(null);

    closeTab();

    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it('fires at most once even though pagehide can fire twice per document', () => {
    // A bfcache entry fires pagehide, and so does the eventual real close.
    svc.track('sess-7');

    closeTab();
    closeTab();

    expect(fetchSpy).toHaveBeenCalledTimes(1);
  });

  it('stays silent with no token rather than firing a request that would 401', () => {
    auth.token = null;
    svc.track('sess-7');

    closeTab();

    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it('percent-encodes the session id it puts in the path', () => {
    svc.track('a/b');

    closeTab();

    expect(fetchSpy.calls.mostRecent().args[0]).toBe('/api/sessions/a%2Fb/me?unload=true');
  });

  it('swallows a rejected fetch — there is nobody left to tell', async () => {
    fetchSpy.and.returnValue(Promise.reject(new Error('network gone')));
    svc.track('sess-7');

    expect(() => closeTab()).not.toThrow();
    // Let the rejection settle so an unhandled rejection would surface.
    await Promise.resolve();
  });

  it('start() is idempotent so the seat is not released twice', () => {
    svc.start();
    svc.start();
    svc.track('sess-7');

    closeTab();

    expect(fetchSpy).toHaveBeenCalledTimes(1);
  });

  it('detaches the window listener when the injector is torn down', () => {
    // A `pagehide` handler bound to a destroyed service outlives the state it
    // reads — the `superseded` latch and the tracked session id — so it can
    // fire a leave on behalf of a seat nobody owns any more. `ngOnDestroy`
    // removing the listener is the fix; nothing pinned it before, so deleting
    // the removeEventListener call left the whole suite green.
    svc.track('sess-7');

    svc.ngOnDestroy();
    closeTab();

    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it('can be restarted after a teardown', () => {
    // The idempotence flag has to come back down with the listener, or a
    // service re-started in a new injector never attaches again.
    svc.ngOnDestroy();
    svc.start();
    svc.track('sess-7');

    closeTab();

    expect(fetchSpy).toHaveBeenCalledTimes(1);
  });
});
