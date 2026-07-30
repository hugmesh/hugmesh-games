import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
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
        provideHttpClient(withXhr()),
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

  /**
   * Legacy-URL derivation (D7 host launch fix). Maker-built chapters
   * register RELATIVE asset_urls ('desert/index.html') that only resolve
   * under /api/assets/levels/<GAME slug>/... — these pins cover the three
   * shapes: absolute passthrough (boot-shipped games, byte-for-byte
   * unchanged), relative + launch uid (uid-gated URL with the full
   * _webNativeUrl param set), and relative without a uid (ungated
   * fallback, same params minus launch_uid). None of these render the
   * template, so the raw `_frameUrl` string is inspected directly.
   */
  describe('_legacyUrl / gameSlug threading', () => {
    function frameUrl(): string {
      return (comp as unknown as { _frameUrl: string })._frameUrl;
    }

    it('keeps an ABSOLUTE assetUrl byte-for-byte — no session params even when inputs are set', () => {
      comp.gameSlug = 'cave';
      comp.launchUid = 'uid-should-not-appear';
      comp.sessionId = 'sess-should-not-appear';
      comp.slotIndex = 1;
      comp.localCharacterId = 7;
      comp.game = { id: 3, slug: 'cave', assetUrl: '/api/assets/levels/cave/index.html' };
      comp.ngOnChanges();
      expect(frameUrl()).toMatch(/^\/api\/assets\/levels\/cave\/index\.html\?chapter_id=3&t=\d+$/);
    });

    it('joins a RELATIVE assetUrl under the uid-gated route with the full param set', () => {
      comp.gameSlug = 'six-realms';
      comp.launchUid = 'uid-abc123';
      comp.sessionId = 'sess-42';
      comp.slotIndex = 2;
      comp.localCharacterId = 5;
      comp.game = { id: 9, slug: 'six-realms-desert', assetUrl: 'desert/index.html' };
      comp.ngOnChanges();
      const url = frameUrl();
      expect(url.startsWith('/api/assets/levels/six-realms/uid-abc123/desert/index.html?')).toBeTrue();
      const params = new URLSearchParams(url.split('?')[1]);
      expect(params.get('chapter_id')).toBe('9');
      expect(params.get('launch_uid')).toBe('uid-abc123');
      expect(params.get('session_id')).toBe('sess-42');
      expect(params.get('slot_index')).toBe('2');
      expect(params.get('character_id')).toBe('5');
      expect(params.get('t')).toMatch(/^\d+$/);
    });

    it('falls back to the ungated route (same params minus launch_uid) when no uid was minted', () => {
      comp.gameSlug = 'six-realms';
      comp.launchUid = null;
      comp.sessionId = 'sess-42';
      comp.slotIndex = 0;
      comp.localCharacterId = 5;
      comp.game = { id: 9, slug: 'six-realms-desert', assetUrl: 'desert/index.html' };
      comp.ngOnChanges();
      const url = frameUrl();
      expect(url.startsWith('/api/assets/levels/six-realms/desert/index.html?')).toBeTrue();
      const params = new URLSearchParams(url.split('?')[1]);
      expect(params.has('launch_uid')).toBeFalse();
      expect(params.get('chapter_id')).toBe('9');
      expect(params.get('session_id')).toBe('sess-42');
      expect(params.get('slot_index')).toBe('0');
      expect(params.get('character_id')).toBe('5');
    });

    it('remounts exactly once when gameSlug/launchUid arrive asynchronously (mount key covers them)', () => {
      comp.game = { id: 9, slug: 'six-realms-desert', assetUrl: 'desert/index.html' };
      comp.ngOnChanges();
      const mountSpy = spyOn<any>(comp, '_mountLevel').and.callThrough();
      // The /launch POST resolves after the initial bind — home re-binds
      // gameSlug + launchUid together.
      comp.gameSlug = 'six-realms';
      comp.launchUid = 'uid-abc123';
      comp.ngOnChanges();
      // Identical re-bind: must be a no-op (no iframe recycle).
      comp.ngOnChanges();
      expect(mountSpy).toHaveBeenCalledTimes(1);
      expect(frameUrl().startsWith('/api/assets/levels/six-realms/uid-abc123/desert/index.html?')).toBeTrue();
    });
  });

  /**
   * Keyboard-trap remediation coverage for the paused-overlay
   * (appFocusTrap on .paused-overlay). Unlike the specs above, these
   * render the real template so the directive actually runs against the
   * live DOM. `onFrameLoad` is stubbed out so the real <iframe src="">
   * this renders can't fire its own async focus-grab (see
   * GameCanvasComponent.onFrameLoad) and race these assertions — that
   * behaviour has its own dedicated coverage already via the "Movement
   * doesn't happen in the WASM iframe" fix, unrelated to this trap.
   */
  describe('paused-overlay focus trap', () => {
    let outsideTrigger: HTMLButtonElement;

    beforeEach(() => {
      spyOn(comp, 'onFrameLoad');
      // Mount a real chapter first so `frameSrc` goes through
      // `_mountLevel` (DomSanitizer.bypassSecurityTrustResourceUrl) —
      // otherwise the iframe's default `frameSrc = ''` plain string
      // trips Angular's NG0904 "unsafe value in a resource URL context"
      // the moment the template actually renders.
      comp.game = { id: 1, slug: 'cave', assetUrl: '/api/assets/levels/cave/index.html' };
      comp.ngOnChanges();
      outsideTrigger = document.createElement('button');
      outsideTrigger.textContent = 'outside trigger';
      document.body.appendChild(outsideTrigger);
      document.body.appendChild(fixture.nativeElement);
      outsideTrigger.focus();
      fixture.detectChanges(); // initial render — paused=false, no overlay yet
    });

    afterEach(() => {
      outsideTrigger.remove();
      fixture.nativeElement.remove();
    });

    function resumeButton(): HTMLElement {
      return fixture.nativeElement.querySelector('[data-testid="canvas-resume-btn"]');
    }

    function tabEvent(shiftKey: boolean): KeyboardEvent {
      return new KeyboardEvent('keydown', { key: 'Tab', shiftKey, bubbles: true, cancelable: true });
    }

    it('moves focus onto the Resume button when the overlay opens and remembers the trigger', () => {
      comp.pauseGame('manual');
      fixture.detectChanges();
      expect(document.activeElement).toBe(resumeButton());
    });

    it('Tab from the (only) Resume button wraps back to itself', () => {
      comp.pauseGame('manual');
      fixture.detectChanges();
      const resume = resumeButton();
      resume.focus();

      const ev = tabEvent(false);
      const preventSpy = spyOn(ev, 'preventDefault').and.callThrough();
      resume.dispatchEvent(ev);

      expect(preventSpy).toHaveBeenCalled();
      expect(document.activeElement).toBe(resume);
    });

    it('Shift+Tab from the Resume button wraps back to itself', () => {
      comp.pauseGame('manual');
      fixture.detectChanges();
      const resume = resumeButton();
      resume.focus();

      const ev = tabEvent(true);
      const preventSpy = spyOn(ev, 'preventDefault').and.callThrough();
      resume.dispatchEvent(ev);

      expect(preventSpy).toHaveBeenCalled();
      expect(document.activeElement).toBe(resume);
    });

    it('restores focus to the pre-pause trigger element after resumeGame()', () => {
      comp.pauseGame('manual');
      fixture.detectChanges();
      expect(document.activeElement).toBe(resumeButton());

      comp.resumeGame();
      fixture.detectChanges();

      expect(document.activeElement).toBe(outsideTrigger);
    });
  });
});
