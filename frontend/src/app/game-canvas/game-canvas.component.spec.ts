import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { GameCanvasComponent } from './game-canvas.component';
import { SessionControlService } from '../services/session-control.service';

/**
 * Pins the legacy GameCanvasComponent's pause / resume / iframe
 * postMessage contract — the same surface area window-controls-steps.ts
 * exercises end-to-end. Cross-tab BroadcastChannel coordination and the
 * SessionControlService signal effect are covered by
 * admin/oversight-and-pause.feature; the pieces here are the in-process
 * pause/resume flips and the iframe stub safety.
 */
describe('GameCanvasComponent', () => {
  let fixture: ComponentFixture<GameCanvasComponent>;
  let comp: GameCanvasComponent;
  let pausedSignal: ReturnType<typeof signal<boolean>>;

  beforeEach(() => {
    pausedSignal = signal(false);
    const sessionControlStub: Pick<SessionControlService, 'paused'> = {
      paused: pausedSignal,
    };
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: SessionControlService, useValue: sessionControlStub },
      ],
    });
    fixture = TestBed.createComponent(GameCanvasComponent);
    comp = fixture.componentInstance;
  });

  afterEach(() => {
    // The component opens a BroadcastChannel in ngOnInit; ngOnDestroy
    // closes it. Fixture.destroy() drives ngOnDestroy so the next test
    // starts with a clean channel.
    fixture.destroy();
  });

  type PostedMessage = { type: string; reason?: string };

  function attachIframeStub(): { posted: PostedMessage[] } {
    const posted: PostedMessage[] = [];
    const stub = {
      contentWindow: {
        postMessage: (msg: PostedMessage) => { posted.push(msg); },
        focus: () => { /* iframe focus stub */ },
      },
      focus: () => { /* */ },
    };
    (comp as unknown as { gameFrame: { nativeElement: unknown } }).gameFrame = {
      nativeElement: stub,
    };
    return { posted };
  }

  it('pauseGame flips `paused` to true and posts SD_GAME_PAUSE to the iframe', () => {
    const { posted } = attachIframeStub();
    expect(comp.paused).toBeFalse();
    comp.pauseGame('self-popout');
    expect(comp.paused).toBeTrue();
    expect(posted).toEqual([{ type: 'SD_GAME_PAUSE', reason: 'self-popout' }]);
  });

  it('pauseGame is idempotent — a second call with paused=true is a no-op', () => {
    const { posted } = attachIframeStub();
    comp.pauseGame('manual');
    comp.pauseGame('manual');
    expect(comp.paused).toBeTrue();
    expect(posted.length).toBe(1);
  });

  it('resumeGame flips `paused` to false and posts SD_GAME_RESUME', () => {
    const { posted } = attachIframeStub();
    comp.pauseGame('manual');
    posted.length = 0; // discard the pause message
    comp.resumeGame();
    expect(comp.paused).toBeFalse();
    expect(posted).toEqual([{ type: 'SD_GAME_RESUME' }]);
  });

  it('resumeGame is a no-op when not paused — no postMessage and no signal flip', () => {
    const { posted } = attachIframeStub();
    comp.resumeGame();
    expect(comp.paused).toBeFalse();
    expect(posted.length).toBe(0);
  });

  it('postMessage onto a missing iframe silently no-ops (no throw)', () => {
    // gameFrame ViewChild is undefined before AfterViewInit — exercising
    // the catch branch in _postToIframe to make sure a takeover SSE
    // during the brief mount-prefocus window doesn't crash the host.
    expect(() => comp.pauseGame('session-control')).not.toThrow();
    expect(comp.paused).toBeTrue();
  });
});
