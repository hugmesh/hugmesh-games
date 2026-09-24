import { DestroyRef, Injectable, NgZone, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';

/**
 * Browser Gamepad API → HugMesh input-event stream.
 *
 * <p>The Gamepad API has no "event" model worth using — `gamepadconnected`
 * / `gamepaddisconnected` fire reliably, but button + axis state is
 * a polled snapshot off `navigator.getGamepads()`. We poll every
 * animation frame, diff against the previous snapshot, and emit
 * input events of the canonical `kind: gamepad` shape (matching the
 * Liquibase 026 reference seeds).
 *
 * <p>Two emit destinations:
 * <ol>
 *   <li><b>POST /api/input/{characterId}</b> — persisted on the BFF
 *       side and fanned out on the SSE input channel for cross-tab
 *       observers (admin dashboard live-feed, replay tooling).</li>
 *   <li><b>postMessage to the active game iframe</b> —
 *       {@code {type: 'SD_INPUT_EVENT', ...}}. The wasm-fragment
 *       Godot bundle's GDScript glue listens for this in
 *       `JavaScriptBridge` and forwards each payload to
 *       `HostBridge.dispatch_event(kind, payload)` so the Rust
 *       extension's `gamepad_received` signal fires inside the
 *       game process. This is the path that lets a connected
 *       Nintendo Pro Controller drive the player without the BFF
 *       round-trip — every per-tick input lands in Godot in <1ms.</li>
 * </ol>
 *
 * <p>We deliberately run the poll loop OUTSIDE Angular's NgZone (via
 * `runOutsideAngular`) — change detection on every animation frame
 * would melt the host shell.
 *
 * <p>To enable, inject this service ONCE in the host's bootstrap
 * (or in HomeComponent for game-aware lifetime) and call
 * {@link start}. Re-calling start is a no-op.
 */
@Injectable({ providedIn: 'root' })
export class GamepadService {
  private readonly zone = inject(NgZone);

  /** Set of currently-connected controller indexes — emitted as a
   *  signal so the host UI can show a "Pro Controller connected"
   *  pill or similar. */
  readonly connected = signal<number[]>([]);

  /** Active iframe window the inputs forward to. Set via
   *  {@link bindGameWindow}; cleared when the iframe unmounts. */
  private gameWindow: Window | null = null;

  /** Active game slug — embedded in the postMessage envelope so the
   *  Godot side can route to the right scene if the host ever
   *  multiplexes more than one game at once, and the `gameSlug` of every
   *  BFF mirror POST. `null` until {@link bindGameWindow} supplies a real
   *  one: nothing is emitted before then (hugmesh-games#13). It used to
   *  default to the literal `'unknown'`, which the BFF's gate 2 now refuses
   *  (no games row, so no `features.gamepad`) and which would otherwise
   *  have been persisted into `input_events` under a slug no game owns. */
  private gameSlug: string | null = null;

  /** One console warning per bound game when the BFF mirror fails, so a
   *  refusal (405 from gates 2/3) or an outage is visible without logging
   *  every frame of a held stick. Reset by {@link bindGameWindow}. */
  private mirrorFailureLogged = false;

  /** Active session id (uuid). Threaded onto every emit so the BFF
   *  can join with game_launches for the full audit row. */
  private sessionId: string | null = null;

  /** Frame-id of the running poll loop, for cancel on stop(). */
  private rafId: number | null = null;

  /** Last-tick snapshot per controller index — diffed each tick. */
  private prev = new Map<number, GamepadSnapshot>();

  /**
   * Begin polling. Idempotent. Uses Angular's NgZone to keep the
   * 60Hz loop OUTSIDE change detection.
   */
  start(): void {
    if (this.rafId !== null) return;
    if (typeof window === 'undefined') return;
    if (!('getGamepads' in (navigator as any))) return;
    window.addEventListener('gamepadconnected',    this.onConnect);
    window.addEventListener('gamepaddisconnected', this.onDisconnect);
    this.zone.runOutsideAngular(() => this.tick());
  }

  /** Stop polling + drop listeners. Idempotent. */
  stop(): void {
    if (this.rafId !== null) {
      cancelAnimationFrame(this.rafId);
      this.rafId = null;
    }
    if (typeof window !== 'undefined') {
      window.removeEventListener('gamepadconnected',    this.onConnect);
      window.removeEventListener('gamepaddisconnected', this.onDisconnect);
    }
    this.prev.clear();
  }

  /** Register the game's iframe contentWindow + slug so the
   *  service can postMessage inputs into it. Pass null on unmount. */
  bindGameWindow(target: Window | null, slug: string, sessionId: string | null): void {
    this.gameWindow = target;
    this.gameSlug   = slug && slug !== 'unknown' ? slug : null;
    this.sessionId  = sessionId;
    this.mirrorFailureLogged = false;
  }

  // ── implementation ──────────────────────────────────────────────────
  private readonly onConnect = (e: GamepadEvent) => {
    this.zone.run(() => {
      this.connected.update(set => Array.from(new Set([...set, e.gamepad.index])));
    });
    this.emit({
      type: 'connected',
      payload: {
        controllerId: e.gamepad.index,
        name:    e.gamepad.id,
        mapping: e.gamepad.mapping || 'standard',
        buttons: e.gamepad.buttons.length,
        axes:    e.gamepad.axes.length,
      },
    });
  };

  private readonly onDisconnect = (e: GamepadEvent) => {
    this.zone.run(() => {
      this.connected.update(set => set.filter(i => i !== e.gamepad.index));
    });
    this.prev.delete(e.gamepad.index);
    this.emit({ type: 'disconnected', payload: { controllerId: e.gamepad.index } });
  };

  private tick = () => {
    const pads = (navigator as any).getGamepads?.() as (Gamepad | null)[] | undefined;
    if (pads) {
      for (const pad of pads) {
        if (!pad) continue;
        const prev = this.prev.get(pad.index);
        // Buttons — diff press state
        for (let i = 0; i < pad.buttons.length; i++) {
          const cur  = pad.buttons[i].pressed;
          const last = prev?.buttons[i] ?? false;
          if (cur !== last) {
            this.emit({
              type: cur ? 'button_down' : 'button_up',
              payload: {
                controllerId: pad.index,
                button: i,
                value: pad.buttons[i].value,
              },
            });
          }
        }
        // Axes — diff with a small dead-zone so an analog stick at
        // rest doesn't flood the wire with jitter.
        for (let i = 0; i < pad.axes.length; i++) {
          const cur  = pad.axes[i];
          const last = prev?.axes[i] ?? 0;
          if (Math.abs(cur - last) > 0.02) {
            this.emit({
              type: 'axis',
              payload: {
                controllerId: pad.index,
                axis: i,
                value: Math.round(cur * 1000) / 1000,
              },
            });
          }
        }
        this.prev.set(pad.index, snapshotOf(pad));
      }
    }
    this.rafId = requestAnimationFrame(this.tick);
  };

  /**
   * Forward one canonical gamepad input event to:
   *   1) the active game iframe (via postMessage), and
   *   2) the BFF when a sessionId is bound (best-effort fire-and-forget).
   *
   * Nothing is sent to either destination until a real game slug is
   * bound. BFF failures never block the input loop (the POST is
   * fire-and-forget), but they are no longer silent: the first failure
   * per bound game — a rejected fetch OR a non-2xx answer such as the
   * 405 the BFF's feature-flag gate returns — logs one console warning.
   */
  private emit(spec: { type: string; payload: Record<string, unknown> }): void {
    if (!this.gameSlug) return;
    const envelope = {
      type: 'SD_INPUT_EVENT',
      gameSlug: this.gameSlug,
      sessionId: this.sessionId,
      kind: 'gamepad',
      eventType: spec.type,
      payload: spec.payload,
      ts: Date.now(),
    };
    if (this.gameWindow) {
      try { this.gameWindow.postMessage(envelope, '*'); } catch { /* ignore — closed iframe */ }
    }
    // BFF mirror — non-blocking.
    if (this.sessionId) {
      const bffEvent = {
        gameSlug: this.gameSlug,
        sessionId: this.sessionId,
        kind: 'gamepad',
        type: spec.type,
        payload: spec.payload,
      };
      // Character id is encoded as part of the URL upstream; here we
      // only mirror in-session inputs for replay, so we land them on a
      // placeholder character id of 0 if the host hasn't set one yet.
      // (HomeComponent updates this once a game card is clicked.)
      const characterId = (window as any).__HUGMESH_ACTIVE_CHARACTER_ID__ || 0;
      if (characterId > 0) {
        const tok = (() => {
          try { return JSON.parse(localStorage.getItem('tw_auth') || '{}').accessToken; }
          catch { return null; }
        })();
        const hdr: Record<string, string> = { 'Content-Type': 'application/json' };
        if (tok) hdr['Authorization'] = 'Bearer ' + tok;
        fetch(`/api/input/${characterId}`, {
          method: 'POST', headers: hdr, body: JSON.stringify(bffEvent),
        }).then(
          res => { if (!res.ok) this.logMirrorFailure(`HTTP ${res.status}`); },
          err => this.logMirrorFailure(err),
        );
      }
    }
  }

  private logMirrorFailure(why: unknown): void {
    if (this.mirrorFailureLogged) return;
    this.mirrorFailureLogged = true;
    console.warn(
      `[GamepadService] BFF input mirror failed for '${this.gameSlug}' (${String(why)}); ` +
      'further failures for this game are not logged.');
  }
}

interface GamepadSnapshot {
  buttons: boolean[];
  axes: number[];
}
function snapshotOf(pad: Gamepad): GamepadSnapshot {
  return {
    buttons: pad.buttons.map(b => b.pressed),
    axes:    [...pad.axes],
  };
}
