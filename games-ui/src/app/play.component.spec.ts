import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { IN_SHELL_NOTE, GamePlayComponent, MAX_FRAME_PX, fittedFrameHeight } from './play.component';
import { NEW_TAB_SLUGS } from './games.constants';

function routeFor(slug: string, query: Record<string, string> = {}) {
  return {
    snapshot: { paramMap: convertToParamMap({ slug }), queryParamMap: convertToParamMap(query) },
    parent: null,
  } as unknown as ActivatedRoute;
}

describe('GamePlayComponent (M2)', () => {
  let http: HttpTestingController;
  let fixture: ComponentFixture<GamePlayComponent>;

  async function setup(slug: string, query: Record<string, string> = {}) {
    await TestBed.configureTestingModule({
      imports: [GamePlayComponent],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: ActivatedRoute, useValue: routeFor(slug, query) },
      ],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(GamePlayComponent);
    fixture.detectChanges();
  }

  afterEach(() => {
    http.verify();
    TestBed.resetTestingModule();
  });

  const el = (): HTMLElement => fixture.nativeElement;

  function flushName(slug: string, name: string) {
    http.expectOne('/games/api/games').flush([{ id: 1, name, slug, description: null, thumbnailUrl: null, sortOrder: 0 }]);
  }

  it('shows the launching spinner, then POSTs the launch with credentials', async () => {
    await setup('chess');
    expect(el().querySelector('[data-testid="games-play"]')).not.toBeNull();
    expect(el().querySelector('[data-testid="games-play-launching"]')).not.toBeNull();
    const launch = http.expectOne('/games/api/games/chess/launch');
    expect(launch.request.method).toBe('POST');
    expect(launch.request.withCredentials).toBeTrue();
    flushName('chess', 'Chess');
    launch.flush({ uid: 'U1', expiresAt: '2026-10-28T12:00:00Z', gameSlug: 'chess' });
    http.expectOne((r) => r.url.startsWith('/_remotes/wasm-fragment/chess/index.html')).flush('<html></html>');
    fixture.detectChanges();
    const frame = el().querySelector<HTMLIFrameElement>('[data-testid="games-play-frame"]')!;
    expect(frame).not.toBeNull();
    expect(frame.getAttribute('src')).toBe('/_remotes/wasm-fragment/chess/index.html?launch_uid=U1');
    expect(frame.getAttribute('title')).toBe('Chess');
    expect(frame.getAttribute('allow')).toContain('fullscreen');
    expect(el().querySelector('[data-testid="games-play-fullscreen"]')).not.toBeNull();
    expect(el().querySelector('[data-testid="games-play-expires"]')?.textContent).toContain('Launch valid until 2026-10-28T12:00:00Z');
    expect(el().querySelector('[data-testid="games-play-note"]')?.textContent).toContain(IN_SHELL_NOTE);
    expect(el().querySelector('[data-testid="games-play-back"]')?.getAttribute('href')).toBe('/');
    // route leave -> best-effort DELETE of the launch
    fixture.destroy();
    const del = http.expectOne('/games/api/games/launches/U1');
    expect(del.request.method).toBe('DELETE');
    del.flush(null, { status: 204, statusText: 'No Content' });
  });

  it('renders the new-tab opener (no iframe) for a SharedArrayBuffer bundle', async () => {
    expect(NEW_TAB_SLUGS).toContain('shadows-of-the-net');
    await setup('shadows-of-the-net');
    flushName('shadows-of-the-net', 'Shadows of the Net');
    http.expectOne('/games/api/games/shadows-of-the-net/launch').flush({ uid: 'U2', expiresAt: 'later', gameSlug: 'shadows-of-the-net' });
    fixture.detectChanges();
    expect(el().querySelector('[data-testid="games-play-frame"]')).toBeNull();
    const btn = el().querySelector<HTMLButtonElement>('[data-testid="games-play-open-tab"]')!;
    expect(btn.textContent).toContain('Open Shadows of the Net in a new tab');
    expect(el().querySelector('[data-testid="games-play-open-tab-panel"]')?.textContent).toContain('cross-origin isolation');
    const open = spyOn(window, 'open').and.returnValue(null);
    btn.click();
    expect(open).toHaveBeenCalledWith('/_remotes/wasm-fragment/shadows-of-the-net/index.html?launch_uid=U2', '_blank', 'noopener');
    fixture.destroy();
    http.expectOne('/games/api/games/launches/U2').flush(null);
  });

  it('shows "not available to your account" on a launch 403', async () => {
    await setup('chess');
    flushName('chess', 'Chess');
    http.expectOne('/games/api/games/chess/launch').flush('', { status: 403, statusText: 'Forbidden' });
    fixture.detectChanges();
    expect(el().querySelector('[data-testid="games-play-not-available"]')?.textContent).toContain(
      'This game is not available to your account.',
    );
    expect(el().querySelector('[data-testid="games-play-frame"]')).toBeNull();
  });

  it('shows "Unknown game." on a launch 404', async () => {
    await setup('nope');
    http.expectOne('/games/api/games').flush([]);
    http.expectOne('/games/api/games/nope/launch').flush('', { status: 404, statusText: 'Not Found' });
    fixture.detectChanges();
    expect(el().querySelector('[data-testid="games-play-unknown"]')?.textContent).toContain('Unknown game.');
  });

  it('surfaces a gateway bundle refusal (403 + X-Resource-Denied) instead of a blank frame', async () => {
    await setup('chess');
    flushName('chess', 'Chess');
    http.expectOne('/games/api/games/chess/launch').flush({ uid: 'U3', expiresAt: 'later', gameSlug: 'chess' });
    http
      .expectOne((r) => r.url.startsWith('/_remotes/wasm-fragment/chess/index.html'))
      .flush('', { status: 403, statusText: 'Forbidden', headers: { 'X-Resource-Denied': 'game:chess' } });
    fixture.detectChanges();
    const panel = el().querySelector('[data-testid="games-play-bundle-refused"]')!;
    expect(panel.textContent).toContain('The gateway refused the game bundle (X-Resource-Denied: game:chess).');
    expect(el().querySelector('[data-testid="games-play-frame"]')).toBeNull();
    fixture.destroy();
    http.expectOne('/games/api/games/launches/U3').flush(null);
  });

  it('shows the unreachable panel when the launch call fails with 5xx and retries', async () => {
    await setup('chess');
    flushName('chess', 'Chess');
    http.expectOne('/games/api/games/chess/launch').flush('', { status: 502, statusText: 'Bad Gateway' });
    fixture.detectChanges();
    expect(el().querySelector('[data-testid="games-unreachable"]')).not.toBeNull();
    el().querySelector<HTMLButtonElement>('[data-testid="games-retry"]')!.click();
    fixture.detectChanges();
    http.expectOne('/games/api/games/chess/launch').flush({ uid: 'U4', expiresAt: 'later', gameSlug: 'chess' });
    http.expectOne((r) => r.url.startsWith('/_remotes/wasm-fragment/chess/index.html')).flush('ok');
    fixture.detectChanges();
    expect(el().querySelector('[data-testid="games-play-frame"]')).not.toBeNull();
    fixture.destroy();
    http.expectOne('/games/api/games/launches/U4').flush(null);
  });

  it('reuses a resumed launch uid without POSTing a new launch', async () => {
    await setup('chess', { uid: 'OLD', exp: '2026-10-28T13:00:00Z' });
    flushName('chess', 'Chess');
    http.expectNone('/games/api/games/chess/launch');
    http.expectOne((r) => r.url.startsWith('/_remotes/wasm-fragment/chess/index.html')).flush('ok');
    fixture.detectChanges();
    expect(el().querySelector('[data-testid="games-play-frame"]')?.getAttribute('src')).toContain('launch_uid=OLD');
    expect(el().querySelector('[data-testid="games-play-expires"]')?.textContent).toContain('2026-10-28T13:00:00Z');
    fixture.destroy();
    http.expectOne('/games/api/games/launches/OLD').flush(null);
  });

  it('grows the frame to a bundle taller than the frame, so nothing is clipped', async () => {
    await setup('chess', { uid: 'TALL' });
    flushName('chess', 'Chess');
    http.expectOne((r) => r.url.startsWith('/_remotes/wasm-fragment/chess/index.html')).flush('ok');
    fixture.detectChanges();
    const host = el();
    document.body.appendChild(host); // real layout, so clientHeight is measured
    try {
      const wrap = host.querySelector<HTMLElement>('[data-testid="games-play-frame-wrap"]')!;
      const frame = host.querySelector<HTMLIFrameElement>('[data-testid="games-play-frame"]')!;
      const cssHeight = frame.clientHeight;
      expect(cssHeight).toBeGreaterThan(0);
      expect(wrap.style.height).toBe(''); // the CSS height until the bundle says otherwise
      const loaded = new Promise<void>((resolve) => frame.addEventListener('load', () => resolve(), { once: true }));
      // same-origin content taller than the frame (srcdoc inherits the origin)
      frame.srcdoc = `<!doctype html><body style="margin:0"><div style="height:${cssHeight + 300}px"></div></body>`;
      await loaded;
      await new Promise((r) => setTimeout(r, 50));
      fixture.detectChanges();
      expect(wrap.style.height).toBe(`${cssHeight + 300}px`);
      expect(frame.clientHeight).toBeGreaterThanOrEqual(frame.contentDocument!.documentElement.scrollHeight);
    } finally {
      host.remove();
    }
    fixture.destroy();
    http.expectOne('/games/api/games/launches/TALL').flush(null);
  });
});

describe('fittedFrameHeight', () => {
  it('keeps the CSS height when the content fits, grows to the content otherwise, and caps', () => {
    expect(fittedFrameHeight(600, 560)).toBeNull();
    expect(fittedFrameHeight(600, 601)).toBeNull(); // sub-pixel rounding is not a reason to grow
    expect(fittedFrameHeight(448, 488.4)).toBe(489); // the live chess case: 448px frame, 488px content
    expect(fittedFrameHeight(300, 99999)).toBe(MAX_FRAME_PX);
    expect(fittedFrameHeight(300, Number.NaN)).toBeNull();
  });
});
