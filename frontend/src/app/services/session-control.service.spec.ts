import { TestBed } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { SessionControlService, SessionControlEvent } from './session-control.service';
import { AuthService } from './auth.service';

/**
 * Coverage for the `superseded` latch — the guard that stops an old,
 * taken-over tab from evicting the player its successor is playing as.
 *
 * The latch is exercised through the private BroadcastChannel handler the way
 * the browser reaches it, because WHICH tab receives the message is the entire
 * signal: `BroadcastChannel` never delivers to the context that posted, so a
 * tab that receives a `takeover-claim` for its own casdoorId is by definition
 * the OLD one.
 */
describe('SessionControlService — superseded latch', () => {
  let svc: SessionControlService;
  let httpMock: HttpTestingController;

  const ME = 'casdoor-alice';

  class FakeAuthService {
    getClaims(): Record<string, unknown> | null { return { sub: ME }; }
  }

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        { provide: AuthService, useClass: FakeAuthService },
      ],
    });
    svc = TestBed.inject(SessionControlService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify({ ignoreCancelled: true }));

  /** Deliver a BroadcastChannel message the way a sibling tab would. */
  function deliverTakeover(over: Record<string, unknown> = {}): void {
    (svc as unknown as { onTakeoverMessage(m: unknown): void }).onTakeoverMessage({
      kind: 'takeover-claim',
      casdoorId: ME,
      gameSlug: 'shadows-of-the-net',
      launchUid: 'new-tab-uid',
      sessionId: 'sess-7',
      ts: Date.now(),
      ...over,
    });
  }

  function sseEvent(over: Partial<SessionControlEvent>): SessionControlEvent {
    return {
      sessionId: 'sess-7', action: 'PLAYER_RESUMED', casdoorId: ME,
      launchUid: null, reason: null, occurredAt: '2026-07-30T00:00:00Z',
      ...over,
    };
  }

  it('starts un-superseded', () => {
    expect(svc.superseded()).toBeFalse();
  });

  it('latches on receiving a takeover-claim for me', () => {
    deliverTakeover();
    expect(svc.superseded()).toBeTrue();
    httpMock.expectOne('/api/sessions/sess-7/players/me/pause').flush(null);
  });

  it('ignores a takeover-claim aimed at a different player', () => {
    deliverTakeover({ casdoorId: 'casdoor-bob' });
    expect(svc.superseded()).toBeFalse();
  });

  it('does NOT clear on the PLAYER_RESUMED the new tab fires', () => {
    // PLAYER_RESUMED is broadcast to every subscriber of the session and
    // matches on casdoorId, so it reaches the OLD tab too and un-pauses it.
    // Treating that as "I'm live again" would hand the latch straight back to
    // the tab it was raised against, and the old tab's eventual pagehide would
    // evict the new one.
    deliverTakeover();
    httpMock.expectOne('/api/sessions/sess-7/players/me/pause').flush(null);

    svc.onSessionControlEvent(sseEvent({ action: 'PLAYER_RESUMED' }));

    expect(svc.paused()).withContext('the view un-freezes').toBeFalse();
    expect(svc.superseded()).withContext('but the tab is still the old one').toBeTrue();
  });

  it('does NOT clear on SESSION_RESUMED either', () => {
    deliverTakeover();
    httpMock.expectOne('/api/sessions/sess-7/players/me/pause').flush(null);

    svc.onSessionControlEvent(sseEvent({ action: 'SESSION_RESUMED', casdoorId: null }));

    expect(svc.superseded()).toBeTrue();
  });

  it('does not latch on a takeover-released message', () => {
    deliverTakeover({ kind: 'takeover-released' });
    expect(svc.superseded()).toBeFalse();
  });

  // ── the one thing that DOES clear it: this tab re-claiming the game ──────
  //
  // `mintLaunch` calls `claimTakeover` every time THIS tab launches a
  // chapter, so a player who was superseded, went back to the first tab and
  // pressed play is live again there. Leaving the latch set would make that
  // live tab refuse to release its own seat — the Close button and the
  // pagehide leg would both go quiet and the seat would ghost for the whole
  // reaper window this fix exists to shorten. It is the exact mirror of how
  // the latch is raised: BroadcastChannel does not deliver to the sender, so
  // the tab that POSTS a claim is the new live one and the sibling that
  // RECEIVES it takes the latch in its place.

  it('clears when this tab claims the takeover for itself', () => {
    deliverTakeover();
    httpMock.expectOne('/api/sessions/sess-7/players/me/pause').flush(null);
    expect(svc.superseded()).toBeTrue();

    svc.claimTakeover({ gameSlug: 'shadows-of-the-net', launchUid: 'my-uid', sessionId: 'sess-7' });

    expect(svc.superseded())
      .withContext('the tab that posts the claim is the live one, by the same asymmetry')
      .toBeFalse();
  });

  it('re-latches if the OTHER tab claims again after this one did', () => {
    svc.claimTakeover({ gameSlug: 'shadows-of-the-net', launchUid: 'my-uid', sessionId: 'sess-7' });
    expect(svc.superseded()).toBeFalse();

    deliverTakeover();

    expect(svc.superseded()).toBeTrue();
    httpMock.expectOne('/api/sessions/sess-7/players/me/pause').flush(null);
  });
});
