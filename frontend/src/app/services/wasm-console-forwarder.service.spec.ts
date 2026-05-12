import { TestBed } from '@angular/core/testing';
import { WasmConsoleForwarderService } from './wasm-console-forwarder.service';
import { AuthService } from './auth.service';

/**
 * Pins the admin + debug-mode gate on the WASM console forwarder.
 * Non-admins must NEVER round-trip captured iframe console lines, and
 * even admins forward only when the BFF flag is on. Local mirroring
 * to host devtools is unconditional (helpful for any dev).
 */
describe('WasmConsoleForwarderService', () => {
  let svc: WasmConsoleForwarderService;
  let authStub: { isAdmin: jasmine.Spy };
  let fetchSpy: jasmine.Spy;

  // Each test installs a window 'message' listener via svc.start().
  // Without removing it between tests, a previous test's listener
  // POSTs on this test's dispatch and breaks isolation. We capture
  // the listener inside `start()` and remove it in `afterEach`.
  let installedListener: ((ev: MessageEvent) => void) | null = null;

  beforeEach(() => {
    authStub = { isAdmin: jasmine.createSpy('isAdmin').and.returnValue(false) };
    TestBed.configureTestingModule({
      providers: [
        WasmConsoleForwarderService,
        { provide: AuthService, useValue: authStub },
      ],
    });
    svc = TestBed.inject(WasmConsoleForwarderService);
    installedListener = null;
    const origAdd = window.addEventListener.bind(window);
    spyOn(window, 'addEventListener').and.callFake(
      (type: string, cb: EventListenerOrEventListenerObject, opts?: boolean | AddEventListenerOptions) => {
        if (type === 'message') {
          installedListener = cb as (ev: MessageEvent) => void;
        }
        origAdd(type as keyof WindowEventMap, cb as EventListener, opts);
      });
    fetchSpy = spyOn(window, 'fetch').and.callFake((url: string | Request | URL) => {
      const u = typeof url === 'string' ? url : (url as Request).url ?? String(url);
      if (u.includes('/api/admin/debug-mode')) {
        return Promise.resolve(new Response(
          JSON.stringify({ wasmConsoleEnabled: true }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        ));
      }
      return Promise.resolve(new Response(null, { status: 204 }));
    });
  });

  afterEach(() => {
    if (installedListener) {
      window.removeEventListener('message', installedListener);
      installedListener = null;
    }
  });

  function dispatch(): void {
    window.dispatchEvent(new MessageEvent('message', {
      data: {
        type: 'SD_WASM_CONSOLE',
        level: 'error',
        message: 'boom',
        gameSlug: 'three-wizards',
      },
    }));
  }

  /** Drain enough microtasks for the debug-mode fetch chain
   *  (fetch → Response → .json() → .then(set debugEnabled)) to settle.
   *  A single `await Promise.resolve()` isn't enough; we yield a few
   *  times to make the test deterministic regardless of how Chrome
   *  schedules microtasks vs setTimeout. */
  async function settle(): Promise<void> {
    for (let i = 0; i < 5; i++) await Promise.resolve();
    await new Promise(r => setTimeout(r, 0));
    for (let i = 0; i < 5; i++) await Promise.resolve();
  }

  it('non-admin: never POSTs the captured line to /api/diagnostics', async () => {
    authStub.isAdmin.and.returnValue(false);
    svc.start();
    await Promise.resolve();
    fetchSpy.calls.reset();
    dispatch();
    await Promise.resolve();
    expect(fetchSpy.calls.allArgs()
      .some(args => String(args[0]).includes('/api/diagnostics/wasm-console'))).toBeFalse();
  });

  it('admin + debug flag on: POSTs to /api/diagnostics/wasm-console', async () => {
    authStub.isAdmin.and.returnValue(true);
    svc.start();
    // Wait for the debug-mode fetch promise chain to resolve.
    await settle();
    fetchSpy.calls.reset();
    dispatch();
    await settle();
    const posted = fetchSpy.calls.allArgs()
      .some(args => String(args[0]).includes('/api/diagnostics/wasm-console'));
    expect(posted).toBeTrue();
  });

  it('admin + debug flag off: does NOT POST', async () => {
    authStub.isAdmin.and.returnValue(true);
    fetchSpy.and.callFake((url: string | Request | URL) => {
      const u = typeof url === 'string' ? url : (url as Request).url ?? String(url);
      if (u.includes('/api/admin/debug-mode')) {
        return Promise.resolve(new Response(
          JSON.stringify({ wasmConsoleEnabled: false }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        ));
      }
      return Promise.resolve(new Response(null, { status: 204 }));
    });
    svc.start();
    await settle();
    fetchSpy.calls.reset();
    dispatch();
    await settle();
    expect(fetchSpy.calls.allArgs()
      .some(args => String(args[0]).includes('/api/diagnostics/wasm-console'))).toBeFalse();
  });
});
