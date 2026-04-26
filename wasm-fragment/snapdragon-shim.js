// Snapdragon Games — wasm-fragment iframe shim.
//
// Loaded into every Godot bundle's index.html (injected via nginx
// sub_filter — see wasm-fragment/nginx.conf). Two responsibilities:
//
//   1. Capture console.error / console.warn / window.onerror /
//      unhandledrejection from the WASM bundle (Godot's printerr
//      and any host-side JS the bundle wires up) and post them to
//      the parent window with type=SD_WASM_CONSOLE. The host shell's
//      WasmConsoleForwarderService picks them up, mirrors locally,
//      and POSTs to /api/diagnostics/wasm-console for SSE fan-out.
//
//   2. Receive SD_INPUT_EVENT messages from the parent (gamepad
//      polls relayed from the host's GamepadService, plus mouse /
//      keyboard / touch events the host hasn't handled in its own
//      controls) and stash them on a window-level queue the
//      GDScript bridge_glue.gd can drain via JavaScriptBridge.
//      Stashing on a queue (vs. firing a global event) is the
//      pattern that already works for movement events — Godot's
//      polling loop reads window._twMovementQueue every frame.
//
// Pure ES5-friendly so it runs inside whatever the Godot HTML5
// shell exports (no module syntax, no optional chaining). Idempotent
// — guards against double-install if the shim somehow loads twice.

(function () {
  'use strict';
  if (window.__SNAPDRAGON_SHIM_INSTALLED__) return;
  window.__SNAPDRAGON_SHIM_INSTALLED__ = true;

  // ── tag every captured line with what the iframe knows about
  // itself: the game slug from the path (/cave/, /dodecahedron/, …)
  // and the level_id query param the host appended on iframe mount.
  var pathSlug = (function () {
    var m = window.location.pathname.match(/\/([^\/]+)\/[^\/]*$/);
    return m ? m[1] : null;
  })();
  var levelSlug = pathSlug;
  var qs = new URLSearchParams(window.location.search);
  var levelId = qs.get('level_id');

  // ── 1. console + error capture ────────────────────────────────────
  function postConsole(level, message, source, line, column, stack) {
    try {
      window.parent.postMessage({
        type: 'SD_WASM_CONSOLE',
        level: level,
        gameSlug: pathSlug,
        levelSlug: levelSlug,
        levelId: levelId,
        message: typeof message === 'string' ? message : safeStringify(message),
        source: source || null,
        line: typeof line === 'number' ? line : null,
        column: typeof column === 'number' ? column : null,
        stack: stack || null,
      }, '*');
    } catch (e) { /* parent gone — drop silently */ }
  }

  function safeStringify(v) {
    if (v === undefined) return 'undefined';
    if (v === null) return 'null';
    try { return JSON.stringify(v); } catch (e) { return String(v); }
  }

  ['error', 'warn', 'info'].forEach(function (level) {
    var orig = console[level].bind(console);
    console[level] = function () {
      try { orig.apply(null, arguments); } catch (e) { /**/ }
      var msg = Array.prototype.map.call(arguments, function (a) {
        return typeof a === 'string' ? a : safeStringify(a);
      }).join(' ');
      postConsole(level, msg, null, null, null, null);
    };
  });

  var origOnError = window.onerror;
  window.onerror = function (message, source, line, column, err) {
    try { if (origOnError) origOnError.apply(window, arguments); }
    catch (e) { /**/ }
    var stack = (err && err.stack) || null;
    postConsole('error', message, source, line, column, stack);
    return false;
  };

  window.addEventListener('unhandledrejection', function (e) {
    var reason = e && e.reason;
    var msg = reason && reason.message ? reason.message : safeStringify(reason);
    var stack = reason && reason.stack ? reason.stack : null;
    postConsole('error', 'unhandledrejection: ' + msg, null, null, null, stack);
  });

  // ── 2. inbound SD_INPUT_EVENT queue ───────────────────────────────
  // Godot's bridge_glue.gd polls these via JavaScriptBridge.eval each
  // frame and dispatches them through HostBridge so the GDExtension's
  // gamepad_received / mouse_received / keyboard_received / etc.
  // signals fire inside the game.
  window._sdInputQueue = window._sdInputQueue || [];

  window.addEventListener('message', function (e) {
    var data = e && e.data;
    if (!data || data.type !== 'SD_INPUT_EVENT') return;
    // Cap the queue so a runaway producer can't OOM the WASM heap.
    if (window._sdInputQueue.length > 1000) {
      window._sdInputQueue.length = 0;
      postConsole('warn', '[snapdragon-shim] _sdInputQueue overflow — dropped 1000 inputs',
                  'snapdragon-shim.js', 0, 0, null);
    }
    window._sdInputQueue.push({
      kind:  data.kind,
      type:  data.eventType,
      payload: data.payload || {},
      ts:    data.ts || Date.now(),
    });
  });

  // ── handshake ──────────────────────────────────────────────────────
  // Tell the parent the shim's ready so the host can start emitting
  // gamepad polls. Without this, the first second of inputs would
  // silently land in a queue the GDScript bridge hasn't subscribed to.
  try {
    window.parent.postMessage({ type: 'SD_SHIM_READY',
                                gameSlug: pathSlug,
                                levelSlug: levelSlug,
                                levelId: levelId }, '*');
  } catch (e) { /* parent gone — drop silently */ }
})();
