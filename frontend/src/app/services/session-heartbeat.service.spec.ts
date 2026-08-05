import { TestBed, fakeAsync, tick, discardPeriodicTasks } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { SessionHeartbeatService } from './session-heartbeat.service';

/**
 * The seat-keeping leg for the nineteen games that emit no idle signal.
 *
 * `SessionMemberReaper` releases a seat whose `last_seen_at` went stale, and
 * the only in-repo game that refreshes it on its own is
 * `shadows-of-the-net` (its `scripts/session_bridge.gd` polls the roster).
 * Everything else — chess, go, reversi, connect-four, gomoku, battleship,
 * three-wizards, … — produces nothing at all while a player thinks, so
 * without this service two people in a turn-based room are both reaped
 * mid-game and, if the one reaped created the room, `closeOnHostDrop`
 * destroys it for everybody.
 *
 * These specs therefore assert the SHAPE that makes that impossible: a beat
 * happens on a timer while a seat is held, it stops the moment the seat is
 * dropped, and the period stays inside the server's staleness window.
 */
describe('SessionHeartbeatService', () => {
  let svc: SessionHeartbeatService;
  let httpMock: HttpTestingController;

  const rosterUrl = (id: string) => `/api/sessions/${id}`;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withXhr()), provideHttpClientTesting()],
    });
    svc = TestBed.inject(SessionHeartbeatService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  const BEAT = SessionHeartbeatService.INTERVAL_MS;

  it('beats the roster endpoint on the interval while a seat is held', fakeAsync(() => {
    svc.track('sess-7');

    tick(BEAT);

    const reqs = httpMock.match(r => r.url === rosterUrl('sess-7') && r.method === 'GET');
    expect(reqs.length).toBe(1);
    reqs.forEach(r => r.flush({}));
    discardPeriodicTasks();
  }));

  it('keeps beating for as long as the player stays seated', fakeAsync(() => {
    // Turn-based games go minutes without input. One beat is not a heartbeat.
    svc.track('sess-7');

    tick(BEAT * 3);
    httpMock.match(r => r.url === rosterUrl('sess-7')).forEach(r => r.flush({}));

    expect(httpMock.match(() => true).length).toBe(0);
    discardPeriodicTasks();
  }));

  it('emits nothing before a seat is held', fakeAsync(() => {
    tick(BEAT * 3);
    httpMock.expectNone(() => true);
  }));

  it('stops beating the moment the seat is released', fakeAsync(() => {
    svc.track('sess-7');
    tick(BEAT);
    httpMock.match(r => r.url === rosterUrl('sess-7')).forEach(r => r.flush({}));

    svc.track(null);
    tick(BEAT * 5);

    expect(httpMock.match(() => true).length)
      .withContext('a released seat must not keep polling a room this tab has left')
      .toBe(0);
  }));

  it('follows the player to a new session without leaving the old timer running', fakeAsync(() => {
    svc.track('sess-7');
    svc.track('sess-8');

    tick(BEAT);

    expect(httpMock.match(r => r.url === rosterUrl('sess-7')).length).toBe(0);
    const reqs = httpMock.match(r => r.url === rosterUrl('sess-8'));
    expect(reqs.length).toBe(1);
    reqs.forEach(r => r.flush({}));
    discardPeriodicTasks();
  }));

  it('re-tracking the same session does not double the beat rate', fakeAsync(() => {
    // Two timers would double every request AND survive a single track(null).
    svc.track('sess-7');
    svc.track('sess-7');

    tick(BEAT);

    const reqs = httpMock.match(r => r.url === rosterUrl('sess-7'));
    expect(reqs.length).toBe(1);
    reqs.forEach(r => r.flush({}));
    discardPeriodicTasks();
  }));

  it('keeps beating after a failed beat', fakeAsync(() => {
    // A 500 or a dropped packet must not silently end the player's liveness —
    // that is exactly the state the reaper collects.
    svc.track('sess-7');

    tick(BEAT);
    httpMock.match(r => r.url === rosterUrl('sess-7'))
      .forEach(r => r.flush('boom', { status: 500, statusText: 'Server Error' }));

    tick(BEAT);
    const reqs = httpMock.match(r => r.url === rosterUrl('sess-7'));
    expect(reqs.length).toBe(1);
    reqs.forEach(r => r.flush({}));
    discardPeriodicTasks();
  }));

  it('stops beating when the injector that owns it is torn down', fakeAsync(() => {
    svc.track('sess-7');
    svc.ngOnDestroy();

    tick(BEAT * 5);

    expect(httpMock.match(() => true).length).toBe(0);
  }));

  // The two below pin the TIMER, not the request. Dropping the seat already
  // silences the beat through the `!id` guard inside it, so a leaked interval
  // is invisible to every spec above — it just spins forever in a host shell
  // that outlives many sessions. Same class of leak as a `pagehide` listener
  // that survives its service.

  it('clears its interval when the seat is released', () => {
    const handle = 4242 as unknown as ReturnType<typeof setInterval>;
    spyOn(window, 'setInterval').and.returnValue(handle);
    const clearSpy = spyOn(window, 'clearInterval');

    svc.track('sess-7');
    svc.track(null);

    expect(clearSpy).toHaveBeenCalledWith(handle);
  });

  it('clears its interval on teardown', () => {
    const handle = 4243 as unknown as ReturnType<typeof setInterval>;
    spyOn(window, 'setInterval').and.returnValue(handle);
    const clearSpy = spyOn(window, 'clearInterval');

    svc.track('sess-7');
    svc.ngOnDestroy();

    expect(clearSpy).toHaveBeenCalledWith(handle);
  });

  it('beats several times inside the BFF staleness window', () => {
    // The contract between the two halves: app.sessions.member-timeout-ms
    // defaults to 180 s, and browsers clamp setInterval in a hidden tab to
    // about once a minute. A backgrounded tab is still a seated player, so
    // the window has to clear several throttled beats — and the foreground
    // period has to stay ABOVE the BFF's 15 s write rate-limiter or every
    // other beat is swallowed server-side and the real period doubles.
    const BFF_MEMBER_TIMEOUT_MS = 180_000;
    const BFF_HEARTBEAT_MIN_INTERVAL_MS = 15_000;
    const BACKGROUND_TIMER_CLAMP_MS = 60_000;

    expect(BEAT).toBeGreaterThan(BFF_HEARTBEAT_MIN_INTERVAL_MS);
    expect(BFF_MEMBER_TIMEOUT_MS / BEAT).toBeGreaterThanOrEqual(4);
    expect(BFF_MEMBER_TIMEOUT_MS / BACKGROUND_TIMER_CLAMP_MS).toBeGreaterThanOrEqual(3);
  });
});
