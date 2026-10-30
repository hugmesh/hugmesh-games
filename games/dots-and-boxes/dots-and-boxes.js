// games/dots-and-boxes/dots-and-boxes.js
//
// The Dots-and-Boxes GAME implementation for the shared web-native game shell
// (games/_shared/{wasm-host,sse-host-client,game-frame}.js). It is a single
// `GAME` object that:
//
//   • init(host, container, ctx)  — seeds the engine (dab_new), builds the
//                                    canvas + HUD, returns the initial state.
//   • render(stateJson, ctx)      — paints the 4x4 dot grid, every claimed edge,
//                                    every owned box (tinted + labelled by its
//                                    owner), the score / turn HUD, and game-over.
//   • onLocalInput(evt, ctx)      — maps a canvas click near an unclaimed edge
//                                    into an edge index, applies the claim
//                                    AUTHORITATIVELY through the wasm engine, and
//                                    returns the relay payload + new state.
//   • onRemoteEvent(channel, json, ctx) — re-applies the opponent's relayed
//                                    claim/world against the same wasm engine
//                                    (the guest re-validates).
//
// HOST-AUTHORITATIVE: the local browser running this engine is the source of
// truth (docs/multiplayer-chess-design.md). render() never mutates the engine;
// every state transition flows through dab_apply / dab_from_edges so the board
// can never drift from the rules engine.
//
// Drawing uses only canvas primitives + Unicode (no image assets). The engine is
// the verified hugmesh_dots_and_boxes.wasm; we drive it through the shared
// WasmHost with the EXACT call pattern proven in
// games/_shared/test/dots-and-boxes-wasm.test.mjs:
//   host.callStatus('dab_new')               -> 1
//   host.callPacked('dab_legal')             -> [0,1,2,...]      (unclaimed edges)
//   host.callPacked('dab_state')             -> { type:'dab.full', edges, owners, turn, score, status }
//   host.callPacked('dab_status')            -> { status }
//   host.call('dab_apply', '7')              -> { type:'dab.state', ... } | { type:'dots-and-boxes.error', ... }
//   host.callStatus('dab_from_edges', '[0,3,12]') -> 0|1
//
// Vanilla ES module. No build step, no dependencies.

// ── board geometry (mirrors the engine: GRID=3 boxes, DOTS=4 dots) ───────────
const GRID = 3;
const DOTS = GRID + 1; // 4
const HCOUNT = DOTS * GRID; // 12 horizontal edges (index 0..11)
const EDGES = HCOUNT + GRID * DOTS; // 24

/** Horizontal-edge index for dot-row `row` (0..DOTS), box-col `col` (0..GRID). */
export function hIndex(row, col) {
  return row * GRID + col;
}
/** Vertical-edge index for box-row `row` (0..GRID), dot-col `col` (0..DOTS). */
export function vIndex(row, col) {
  return HCOUNT + row * DOTS + col;
}

/**
 * Decode an edge index into { orient:'h'|'v', row, col }. Inverse of hIndex /
 * vIndex; matches the engine's `Edge::coords`.
 */
export function edgeCoords(idx) {
  if (idx < HCOUNT) {
    return { orient: 'h', row: Math.floor(idx / GRID), col: idx % GRID };
  }
  const v = idx - HCOUNT;
  return { orient: 'v', row: Math.floor(v / DOTS), col: v % DOTS };
}

// Palette — clears the WCAG-audited non-text contrast bar at 3:1, and reuses the
// HugMesh accent tokens the rest of the portal uses.
const COLORS = {
  dot: '#f2eefb',
  edgeOpen: 'rgba(168, 172, 196, 0.30)', // faint hint of a drawable edge
  edgeClaimed: '#b388ff', // a drawn line
  edgeLast: '#ffd740', // the most-recently drawn line
  boxOne: 'rgba(124, 77, 255, 0.40)', // player one's territory
  boxTwo: 'rgba(255, 92, 92, 0.40)', // player two's territory
  oneInk: '#d9c9ff',
  twoInk: '#ffd0d0',
  label: '#1a1a1a',
};

/** The human label for a player wire value. */
function playerName(wire) {
  return wire === 'two' ? 'Player 2' : 'Player 1';
}

/** True once the engine reports a terminal status. */
export function isGameOver(status) {
  return typeof status === 'string' && (status.startsWith('win:') || status === 'draw');
}

/** Human-readable status line from an engine status + a turn wire value. */
export function statusLine(status, turn) {
  switch (true) {
    case status === 'win:one':
      return 'Player 1 wins';
    case status === 'win:two':
      return 'Player 2 wins';
    case status === 'draw':
      return 'Draw';
    case status === 'ongoing':
    default:
      return `${playerName(turn)} to move`;
  }
}

// ── the GAME object ──────────────────────────────────────────────────────────

export function createDotsAndBoxesGame() {
  // Per-instance UI + layout state.
  const ui = {
    canvas: null,
    cx: null,
    pad: 0, // outer padding in px
    step: 0, // distance between adjacent dots in px
    dotR: 0, // dot radius in px
    hit: 0, // click-hit half-thickness around an edge
    legal: [], // [edgeIdx, ...] currently unclaimed edges
    hud: null, // { turn, status, reset } elements
    onResize: null,
    banner: null, // transient HUD override (errors / desync / session)
  };

  // Latest authoritative full-board snapshot we rendered.
  let state = freshState();

  function freshState() {
    return {
      edges: new Array(EDGES).fill(false),
      owners: new Array(GRID * GRID).fill(null),
      turn: 'one',
      score: [0, 0],
      status: 'ongoing',
      lastEdge: null,
    };
  }

  /** Pull the full board out of the engine into our `state` shape. */
  function readEngine(host, lastEdge) {
    const full = host.callPacked('dab_state') || {};
    return {
      edges: Array.isArray(full.edges) && full.edges.length === EDGES
        ? full.edges.slice()
        : new Array(EDGES).fill(false),
      owners: Array.isArray(full.owners) ? full.owners.slice() : new Array(GRID * GRID).fill(null),
      turn: full.turn || 'one',
      score: Array.isArray(full.score) ? full.score.slice(0, 2) : [0, 0],
      status: full.status || 'ongoing',
      lastEdge: lastEdge !== undefined ? lastEdge : state.lastEdge,
    };
  }

  function refreshLegal(host) {
    ui.legal = host.callPacked('dab_legal') || [];
  }

  const GAME = {
    slug: 'dots-and-boxes',

    async init(host, container, ctx) {
      host.callStatus('dab_new'); // returns 1 — empty board
      refreshLegal(host);
      buildDom(container, ui, ctx);
      state = readEngine(host, null);
      return state;
    },

    render(stateJson, ctx) {
      if (stateJson && typeof stateJson === 'object' && Array.isArray(stateJson.edges)) {
        state = {
          edges: stateJson.edges.slice(),
          owners: Array.isArray(stateJson.owners) ? stateJson.owners.slice() : state.owners,
          turn: stateJson.turn || state.turn,
          score: Array.isArray(stateJson.score) ? stateJson.score.slice(0, 2) : state.score,
          status: stateJson.status || 'ongoing',
          lastEdge: stateJson.lastEdge !== undefined ? stateJson.lastEdge : state.lastEdge,
        };
      }
      drawBoard(ui, state);
      drawHud(ui, state);
    },

    onLocalInput(evt, ctx) {
      const host = ctx.host;
      if (!evt) return null;

      // A fresh board (HUD "New game" button or a synthetic reset event).
      if (evt.type === 'reset') {
        host.callStatus('dab_new');
        refreshLegal(host);
        ui.banner = null;
        state = readEngine(host, null);
        const world = worldOf(state);
        return { input: { kind: 'control', type: 'reset', payload: {} }, world, state };
      }

      if (evt.type !== 'click') return null;
      if (isGameOver(state.status)) return null;

      const edge = canvasToEdge(ui, evt);
      if (edge === null) return null; // click wasn't near a drawable edge
      if (state.edges[edge]) return null; // already drawn → ignore

      return applyClaim(edge);

      // Apply an edge claim authoritatively through the engine, refresh derived
      // state, and build the relay payloads. The host-authoritative `world`
      // broadcast carries the FULL board so the guest re-syncs deterministically.
      function applyClaim(edgeIdx) {
        const outcome = host.call('dab_apply', String(edgeIdx));
        if (!outcome || outcome.type === 'dots-and-boxes.error') {
          flashError(ui, outcome && outcome.error);
          return { state };
        }
        refreshLegal(host);
        ui.banner = null;
        state = readEngine(host, edgeIdx);
        const world = worldOf(state);
        return {
          // The relay payload the guest re-applies (input channel), AND the
          // host-authoritative world broadcast.
          input: { kind: 'control', type: 'claim', payload: { edge: edgeIdx } },
          world,
          state,
        };
      }
    },

    onRemoteEvent(channel, json, ctx) {
      const host = ctx.host;
      if (!json) return null;

      // session-control: pause/resume/close — surface on the HUD, no engine move.
      if (channel === 'session-control') {
        if (json.action && String(json.action).includes('CLOSED')) {
          ui.banner = 'Session closed';
        }
        return state;
      }

      const payload = json.payload || json;
      let edge = null;
      let worldEdges = null;
      let isReset = false;
      if (payload && typeof payload === 'object') {
        const inner = payload.payload && typeof payload.payload === 'object' ? payload.payload : payload;
        if (Number.isInteger(inner.edge)) edge = inner.edge;
        if (Array.isArray(payload.edges)) worldEdges = payload.edges;
        if (payload.type === 'reset' || inner.type === 'reset') isReset = true;
      }
      if (json.type === 'reset') isReset = true;

      // A relayed new-game — reset locally so this side starts fresh too.
      if (isReset && edge === null && !worldEdges) {
        host.callStatus('dab_new');
        refreshLegal(host);
        ui.banner = null;
        state = readEngine(host, null);
        return state;
      }

      // The opponent's intended claim (guest→host `input`). Re-apply against the
      // engine; the guest re-validates by letting the engine accept/reject.
      if (Number.isInteger(edge)) {
        let outcome = null;
        if (isEdgeShaped(edge)) {
          try {
            outcome = host.call('dab_apply', String(edge));
          } catch (_e) {
            outcome = { type: 'dots-and-boxes.error', error: 'engine-trap' };
          }
        } else {
          outcome = { type: 'dots-and-boxes.error', error: 'bad-edge' };
        }
        if (outcome && outcome.type !== 'dots-and-boxes.error') {
          ui.banner = null;
          refreshLegal(host);
          state = readEngine(host, edge);
          return state;
        }
        // Engine rejected the relayed claim. If the host also sent a world board,
        // fall through and trust the authoritative full board.
      }

      // The host's authoritative `world` broadcast. Replaying the claimed-edge
      // SET through dab_from_edges reconstructs the claimed-edge BITMAP (+ the
      // legal-move set) exactly — that part is order-invariant. But owners,
      // score, turn and status are ORDER-DEPENDENT (the extra-turn rule means
      // who completes a box, and whose turn it is, depend on the claim order,
      // which the bare set does not carry). So we take the BITMAP from the
      // replay and OVERLAY the host's authoritative owners/score/turn/status —
      // never re-derive them from the arbitrary replay order. The host is the
      // source of truth; trusting its resolved fields is what keeps the guest
      // from silently diverging on score / ownership / whose-move-it-is.
      if (worldEdges) {
        const claimedOrder = orderedClaims(worldEdges, payload && payload.lastEdge);
        const ok = host.callStatus('dab_from_edges', JSON.stringify(claimedOrder));
        if (ok === 1) {
          ui.banner = null;
          refreshLegal(host);
          const rebuilt = readEngine(host,
            Number.isInteger(payload && payload.lastEdge) ? payload.lastEdge : state.lastEdge);
          // Bitmap (edges) + legal come from the deterministic replay; the
          // order-dependent fields come from the host's authoritative payload.
          state = {
            ...rebuilt,
            owners: Array.isArray(payload.owners) ? payload.owners.slice() : rebuilt.owners,
            score: Array.isArray(payload.score) ? payload.score.slice(0, 2) : rebuilt.score,
            turn: payload.turn || rebuilt.turn,
            status: payload.status || rebuilt.status,
          };
          return state;
        }
      }

      // Could neither apply nor recover — the guest's board has diverged from the
      // authoritative host. Surface it rather than silently dropping the event.
      if (Number.isInteger(edge) || worldEdges) {
        ui.banner = 'Host out of sync';
        return { ...state };
      }
      return null;
    },

    destroy() {
      if (ui.onResize && typeof window !== 'undefined' &&
          typeof window.removeEventListener === 'function') {
        window.removeEventListener('resize', ui.onResize);
        ui.onResize = null;
      }
    },

    // Exposed for tests.
    _ui: ui,
    _getState: () => state,
  };

  return GAME;
}

/** The host-authoritative `world` payload for a board snapshot. */
function worldOf(state) {
  // The list of claimed edge indices (for the guest's dab_from_edges replay),
  // plus the AUTHORITATIVE owners/score/status/turn/lastEdge. Replaying the edge
  // SET reconstructs the claimed-edge bitmap exactly (any permutation claims the
  // same edges), but owners/score/turn are ORDER-DEPENDENT — who draws a box's
  // 4th side, and the extra-turn parity, both depend on the claim ORDER, which
  // the bare set does not carry. So we ship the host's resolved owners/score/
  // turn/status and the guest OVERLAYS them onto the reconstructed board rather
  // than (incorrectly) re-deriving them from an arbitrary replay order.
  const edges = [];
  for (let i = 0; i < state.edges.length; i++) if (state.edges[i]) edges.push(i);
  return {
    edges,
    owners: state.owners.slice(),
    score: state.score.slice(0, 2),
    status: state.status,
    turn: state.turn,
    lastEdge: state.lastEdge,
  };
}

/**
 * Produce a claim order for dab_from_edges from a set of claimed edges. The
 * engine's replay only requires each claim be legal-in-order (every edge claimed
 * exactly once on a board where the rest are open), which ANY permutation of a
 * valid full-or-partial claimed set satisfies. The reconstructed claimed-edge
 * BITMAP is order-invariant (the same edges end up claimed regardless of order),
 * but the OWNERS / SCORE / TURN are NOT — who draws a box's 4th side, and the
 * extra-turn parity, depend on the order, which the bare set does not carry. The
 * caller therefore overlays the host's authoritative owners/score/turn/status
 * onto the rebuilt bitmap; this function only needs to yield SOME legal order. We
 * put `lastEdge` last so the rebuilt `lastEdge` highlight matches the host's most
 * recent draw.
 */
function orderedClaims(edges, lastEdge) {
  const set = (Array.isArray(edges) ? edges : []).filter((e) => isEdgeShaped(e));
  if (!Number.isInteger(lastEdge) || !set.includes(lastEdge)) return set.slice();
  return set.filter((e) => e !== lastEdge).concat(lastEdge);
}

/** A well-formed edge index: an integer in 0..EDGES. */
function isEdgeShaped(e) {
  return Number.isInteger(e) && e >= 0 && e < EDGES;
}

// ── DOM / canvas plumbing ─────────────────────────────────────────────────────

function buildDom(container, ui, ctx) {
  const canvas = container.querySelector('#board') || makeCanvas(container);
  ui.canvas = canvas;
  ui.cx = canvas.getContext('2d');
  layout(ui);

  canvas.addEventListener('click', (e) => {
    if (ctx && typeof ctx.handleLocalInput === 'function') ctx.handleLocalInput(e);
  });

  const turnEl = container.querySelector('#turn');
  const statusEl = container.querySelector('#status');
  const resetBtn = container.querySelector('#reset');
  ui.hud = { turn: turnEl, status: statusEl, reset: resetBtn };
  if (resetBtn && ctx) {
    resetBtn.addEventListener('click', () => {
      if (typeof ctx.handleLocalInput === 'function') {
        ctx.handleLocalInput({ type: 'reset' });
      }
    });
  }

  if (typeof window !== 'undefined' && typeof window.addEventListener === 'function') {
    ui.onResize = () => {
      layout(ui);
      ctx.GAME.render(ctx.GAME._getState(), ctx);
    };
    window.addEventListener('resize', ui.onResize);
    if (ctx && typeof ctx.onTeardown === 'function') {
      ctx.onTeardown(() => {
        if (ui.onResize) {
          window.removeEventListener('resize', ui.onResize);
          ui.onResize = null;
        }
      });
    }
  }
}

function makeCanvas(container) {
  const c = typeof document !== 'undefined' ? document.createElement('canvas') : null;
  if (c) {
    c.id = 'board';
    container.appendChild(c);
  }
  return c;
}


/** Vertical px the page needs besides the board: everything above it (its
 *  offsetTop: body padding + HUD + gap) plus the body's bottom padding.
 *  24 (the old fixed margin) when there is no layout to measure, e.g. in the
 *  node smoke tests. */
function boardReserve(canvas) {
  try {
    const top = canvas && typeof canvas.offsetTop === 'number' ? canvas.offsetTop : NaN;
    if (!Number.isFinite(top) || top <= 0) return 24;
    const body = typeof document !== 'undefined' ? document.body : null;
    const cs = body && typeof getComputedStyle === 'function' ? getComputedStyle(body) : null;
    return top + ((cs && parseFloat(cs.paddingBottom)) || 0);
  } catch {
    return 24;
  }
}

function layout(ui) {
  if (!ui.canvas) return;
  let target = 480;
  if (typeof window !== 'undefined' && window.innerWidth) {
    // Leave room for what sits above and below the board (body padding, the
    // HUD, which wraps on narrow screens, the gap). Sizing to the full
    // viewport height overflowed the frame by the HUD's height whenever the
    // board was height-limited (the in-shell player clipped chess's last rank).
    const availH = (window.innerHeight || window.innerWidth) - boardReserve(ui.canvas);
    target = Math.min(window.innerWidth - 24, availH);
    target = Math.max(240, Math.min(target, 640));
  }
  const px = target;
  ui.canvas.width = px;
  ui.canvas.height = px;
  if (ui.canvas.style) {
    ui.canvas.style.width = px + 'px';
    ui.canvas.style.height = px + 'px';
  }
  // Leave a margin; the dot grid spans (DOTS-1) steps inside it.
  ui.pad = Math.round(px * 0.12);
  ui.step = (px - 2 * ui.pad) / (DOTS - 1);
  ui.dotR = Math.max(3, Math.round(ui.step * 0.10));
  ui.hit = Math.max(10, Math.round(ui.step * 0.34));
}

/** Dot center (in canvas px) for dot at (dotRow, dotCol), both 0..DOTS-1. */
function dotXY(ui, dotRow, dotCol) {
  return { x: ui.pad + dotCol * ui.step, y: ui.pad + dotRow * ui.step };
}

/**
 * Map a canvas click to the nearest DRAWABLE edge index, or null if the click
 * isn't within `ui.hit` px of an edge's midpoint band. Horizontal edge H(row,col)
 * connects dot (row,col)→(row,col+1); vertical edge V(row,col) connects dot
 * (row,col)→(row+1,col).
 */
function canvasToEdge(ui, evt) {
  if (!ui.canvas) return null;
  const rect = typeof ui.canvas.getBoundingClientRect === 'function'
    ? ui.canvas.getBoundingClientRect()
    : { left: 0, top: 0, width: ui.canvas.width, height: ui.canvas.height };
  let x = evt.offsetX;
  let y = evt.offsetY;
  if (x === undefined || y === undefined) {
    x = (evt.clientX || 0) - rect.left;
    y = (evt.clientY || 0) - rect.top;
  }
  const scaleX = ui.canvas.width / (rect.width || ui.canvas.width);
  const scaleY = ui.canvas.height / (rect.height || ui.canvas.height);
  x *= scaleX;
  y *= scaleY;

  let best = null;
  let bestDist = ui.hit;

  // Horizontal edges: midpoint between two horizontally-adjacent dots.
  for (let row = 0; row < DOTS; row++) {
    for (let col = 0; col < GRID; col++) {
      const a = dotXY(ui, row, col);
      const b = dotXY(ui, row, col + 1);
      const d = pointToSegment(x, y, a.x, a.y, b.x, b.y);
      if (d < bestDist) {
        bestDist = d;
        best = hIndex(row, col);
      }
    }
  }
  // Vertical edges: midpoint between two vertically-adjacent dots.
  for (let row = 0; row < GRID; row++) {
    for (let col = 0; col < DOTS; col++) {
      const a = dotXY(ui, row, col);
      const b = dotXY(ui, row + 1, col);
      const d = pointToSegment(x, y, a.x, a.y, b.x, b.y);
      if (d < bestDist) {
        bestDist = d;
        best = vIndex(row, col);
      }
    }
  }
  return best;
}

/** Shortest distance from point (px,py) to the segment (ax,ay)-(bx,by). */
function pointToSegment(px, py, ax, ay, bx, by) {
  const dx = bx - ax;
  const dy = by - ay;
  const len2 = dx * dx + dy * dy || 1;
  let t = ((px - ax) * dx + (py - ay) * dy) / len2;
  t = Math.max(0, Math.min(1, t));
  const cx = ax + t * dx;
  const cy = ay + t * dy;
  return Math.hypot(px - cx, py - cy);
}

// ── drawing ───────────────────────────────────────────────────────────────────

function drawBoard(ui, state) {
  const cx = ui.cx;
  if (!cx) return;
  const w = ui.canvas.width;
  const h = ui.canvas.height;
  cx.clearRect(0, 0, w, h);

  // 1. owned boxes (tinted territory + an owner glyph).
  for (let br = 0; br < GRID; br++) {
    for (let bc = 0; bc < GRID; bc++) {
      const owner = state.owners[br * GRID + bc];
      if (!owner) continue;
      const tl = dotXY(ui, br, bc);
      cx.fillStyle = owner === 'two' ? COLORS.boxTwo : COLORS.boxOne;
      cx.fillRect(tl.x, tl.y, ui.step, ui.step);
      // Owner mark centred in the box.
      cx.fillStyle = owner === 'two' ? COLORS.twoInk : COLORS.oneInk;
      cx.textAlign = 'center';
      cx.textBaseline = 'middle';
      cx.font = `${Math.floor(ui.step * 0.5)}px system-ui, sans-serif`;
      cx.fillText(owner === 'two' ? '2' : '1', tl.x + ui.step / 2, tl.y + ui.step / 2);
    }
  }

  // 2. faint hints for still-drawable edges (so a player sees where lines go).
  cx.lineCap = 'round';
  for (let i = 0; i < EDGES; i++) {
    if (state.edges[i]) continue;
    strokeEdge(ui, cx, i, COLORS.edgeOpen, Math.max(2, ui.dotR * 0.6));
  }

  // 3. claimed edges (the most-recent one highlighted).
  for (let i = 0; i < EDGES; i++) {
    if (!state.edges[i]) continue;
    const isLast = i === state.lastEdge;
    strokeEdge(ui, cx, i, isLast ? COLORS.edgeLast : COLORS.edgeClaimed,
      Math.max(3, ui.dotR * (isLast ? 1.5 : 1.2)));
  }

  // 4. the dots, on top.
  cx.fillStyle = COLORS.dot;
  for (let r = 0; r < DOTS; r++) {
    for (let c = 0; c < DOTS; c++) {
      const p = dotXY(ui, r, c);
      cx.beginPath();
      cx.arc(p.x, p.y, ui.dotR, 0, Math.PI * 2);
      cx.fill();
    }
  }
}

/** Stroke one edge index in the given style + width. */
function strokeEdge(ui, cx, idx, style, width) {
  const { orient, row, col } = edgeCoords(idx);
  let a;
  let b;
  if (orient === 'h') {
    a = dotXY(ui, row, col);
    b = dotXY(ui, row, col + 1);
  } else {
    a = dotXY(ui, row, col);
    b = dotXY(ui, row + 1, col);
  }
  cx.strokeStyle = style;
  cx.lineWidth = width;
  cx.beginPath();
  cx.moveTo(a.x, a.y);
  cx.lineTo(b.x, b.y);
  cx.stroke();
}

function drawHud(ui, state) {
  if (!ui.hud) return;
  const over = isGameOver(state.status);
  if (ui.hud.turn) {
    ui.hud.turn.textContent =
      `P1 ${state.score[0]} — ${state.score[1]} P2`;
  }
  if (ui.hud.status) {
    ui.hud.status.textContent = ui.banner ? ui.banner : statusLine(state.status, state.turn);
    ui.hud.status.classList && ui.hud.status.classList.toggle('over', over);
  }
  // Reset is always available (start a new game any time).
}

function flashError(ui, reason) {
  if (ui.hud && ui.hud.status) {
    ui.hud.status.textContent = `Illegal move${reason ? ` (${reason})` : ''}`;
  }
}

export default createDotsAndBoxesGame;
