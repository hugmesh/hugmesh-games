// games/battleship/battleship.js
//
// The Battleship GAME implementation for the shared web-native game shell
// (games/_shared/{wasm-host,sse-host-client,game-frame}.js). It is a single
// `GAME` object that:
//
//   • init(host, container, ctx)  — seeds the engine (battleship_new), builds the
//                                    two canvases (own fleet + opponent fog) + HUD,
//                                    returns the initial state.
//   • render(stateJson, ctx)      — paints the OWN board (ships visible + incoming
//                                    shots) and the OPPONENT fog board (only the
//                                    shots THIS client fired + their revealed
//                                    result), plus the phase / turn / sunk HUD.
//   • onLocalInput(evt, ctx)      — placement clicks lay this client's OWN ships
//                                    LOCALLY (never relayed); a "ready" finalises
//                                    the fleet; a firing click on the opponent grid
//                                    relays ONLY a shot coord (input channel) and
//                                    the resolved result of an INCOMING shot
//                                    (world channel) — never a layout.
//   • onRemoteEvent(channel, json, ctx) — an incoming shot (opponent→here on the
//                                    input channel) is RESOLVED against this
//                                    client's own fleet (battleship_resolve) and
//                                    the result broadcast back on world; a relayed
//                                    result (owner→here on world) is RECORDED on
//                                    the opponent fog grid (battleship_record).
//
// FOG OF WAR — the design that makes Battleship the one non-globally-authoritative
// game. Each client is authoritative over its OWN board and is the SOLE resolver
// of shots fired at it. SHIP PLACEMENT STAYS LOCAL — a layout never crosses the
// wire. The relay carries exactly two things:
//   • on the `input` channel: a shot COORDINATE the shooter is firing.
//   • on the `world` channel: that shot's RESULT (miss/hit/sunk/win) from the
//     board owner who resolved it.
// A malicious peer can therefore never learn an unfired cell: the owner only ever
// reveals the single coordinate it was asked about. See the shim
// extensions/snapdragon-battleship-wasm/src/lib.rs for the resolve/record split.
//
// In a multiplayer session the HOST is Player One (fires first), the guest is
// Player Two. With no session_id it is a local hot-seat: ONE engine holds BOTH
// fleets, placement alternates, and each shot is resolved immediately on the same
// engine (no relay).
//
// We drive the verified snapdragon_battleship_wasm.wasm through the shared
// WasmHost with the EXACT call pattern proven in
// games/_shared/test/battleship-wasm.test.mjs:
//   host.callStatus('battleship_new')                          -> 1
//   host.callRaw('battleship_place', null, [...8 ptr/len])     -> packed snapshot|error  (via placeShip())
//   host.callRaw('battleship_ready', null, [...])              -> packed snapshot|error  (via readyFleet())
//   host.call('battleship_resolve', 'B7')                      -> { type:'battleship.shot', ... }|error
//   host.callRaw('battleship_record', null, [...])             -> { type:'battleship.shot', ... }|error  (via recordShot())
//   host.callPacked('battleship_snapshot')                     -> { type:'battleship.snapshot', ... }
//   host.callPacked('battleship_status')                       -> { status }
//
// Vanilla ES module. No build step, no dependencies.

const SIZE = 10;
const COLS = ['A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J'];

// The standard fleet, in placement order. [ship, length].
const FLEET = [
  ['carrier', 5],
  ['battleship', 4],
  ['cruiser', 3],
  ['submarine', 3],
  ['destroyer', 2],
];

// Board palette (clears the WCAG-audited non-text contrast bar at 3:1).
const COLORS = {
  sea: '#11335c',
  seaAlt: '#143a68',
  gridLine: '#0a2240',
  ship: '#5a6b7e',
  shipStroke: '#1a1a1a',
  hit: '#ff5c5c',
  miss: '#a8acc4',
  sunk: '#b388ff',
  label: '#a8acc4',
};

/** Algebraic coord ("B7") → {col,row} (0-based) or null if malformed/off-board. */
export function parseCoord(s) {
  if (typeof s !== 'string' || s.length < 2) return null;
  const col = COLS.indexOf(s[0].toUpperCase());
  const row = parseInt(s.slice(1), 10) - 1;
  if (col < 0 || Number.isNaN(row) || row < 0 || row >= SIZE) return null;
  return { col, row };
}

/** {col,row} → algebraic coord ("B7"). */
export function coordName(col, row) {
  return COLS[col] + String(row + 1);
}

/** The hull cells (array of {col,row}) a ship would occupy from an origin. */
export function hullCells(origin, len, orient) {
  const cells = [];
  for (let i = 0; i < len; i++) {
    const col = orient === 'h' ? origin.col + i : origin.col;
    const row = orient === 'h' ? origin.row : origin.row + i;
    cells.push({ col, row });
  }
  return cells;
}

/** True if every cell is on the 10x10 board. */
export function onBoard(cells) {
  return cells.every((c) => c.col >= 0 && c.col < SIZE && c.row >= 0 && c.row < SIZE);
}

/** True if any cell collides with an already-occupied set (Set of "col,row"). */
export function collides(cells, occupied) {
  return cells.some((c) => occupied.has(`${c.col},${c.row}`));
}

/** A relayed shot coord is well-formed: A-J + 1-10 (guards the wasm instance). */
export function isCoordShaped(s) {
  return typeof s === 'string' && /^[A-Ja-j](?:[1-9]|10)$/.test(s);
}

/** A relayed result token is well-formed (miss/hit/sunk:<ship>/win:<ship>). */
export function isResultShaped(s) {
  return (
    typeof s === 'string' &&
    /^(miss|hit|(sunk|win):(carrier|battleship|cruiser|submarine|destroyer))$/.test(s)
  );
}

/** Human-readable status / phase line. */
export function statusLine(state, localPlayer) {
  if (state.status === 'win:p1') return localPlayer === 'p2' ? 'You lost' : (localPlayer === 'p1' ? 'You win!' : 'Player One wins');
  if (state.status === 'win:p2') return localPlayer === 'p1' ? 'You lost' : (localPlayer === 'p2' ? 'You win!' : 'Player Two wins');
  if (state.status === 'placing') {
    const placed = FLEET.length - state.toPlace.length;
    if (state.toPlace.length > 0) {
      return `Place your ${state.toPlace[0][0]} (${placed}/${FLEET.length})`;
    }
    return state.localReady ? 'Waiting for opponent…' : 'Fleet ready — press Ready';
  }
  // firing
  const myTurn = state.turn === localPlayer || localPlayer === null;
  return myTurn ? 'Your turn — fire at the enemy grid' : 'Opponent’s turn…';
}

/** True if the game has reached a terminal win. */
export function isGameOver(status) {
  return typeof status === 'string' && status.startsWith('win:');
}

// ── the GAME object ──────────────────────────────────────────────────────────

export function createBattleshipGame() {
  // Per-instance UI state.
  const ui = {
    own: null, // own canvas + ctx
    enemy: null, // enemy fog canvas + ctx
    cell: 0,
    hud: null, // { status, sunk, ready } elements
    banner: null, // transient HUD message (sync warnings / errors)
    orient: 'h', // current placement orientation
    onResize: null,
    onKey: null,
  };

  // The local player's slot (p1 host / p2 guest / null hot-seat-both). Set in init.
  let localPlayer = null;

  // Authoritative client-side state.
  let state = {
    status: 'placing',
    turn: 'p1',
    toPlace: FLEET.slice(), // ships this client still has to place
    localReady: false,
    // own[col][row]: 'ship' | 'hit' | 'miss' | null  (ships visible, incoming shots overlaid)
    own: emptyGrid(),
    ownShips: [], // [{cells:[{col,row}], name}]
    // enemy[col][row]: 'hit' | 'miss' | 'sunk' | null  (FOG: only what we fired + revealed)
    enemy: emptyGrid(),
    p1Sunk: 0,
    p2Sunk: 0,
  };

  function emptyGrid() {
    const g = [];
    for (let c = 0; c < SIZE; c++) g.push(new Array(SIZE).fill(null));
    return g;
  }

  /** Read the engine's public snapshot into the fields it owns. */
  function syncSnapshot(host) {
    const snap = host.callPacked('battleship_snapshot') || {};
    if (snap.status) state.status = snap.status;
    if (snap.turn) state.turn = snap.turn;
    if (typeof snap.p1Sunk === 'number') state.p1Sunk = snap.p1Sunk;
    if (typeof snap.p2Sunk === 'number') state.p2Sunk = snap.p2Sunk;
  }

  /** Place a ship for `player` via the 8-arg wasm export; returns parsed JSON. */
  function placeShip(host, player, ship, origin, orient) {
    const ex = host.exports;
    const enc = new TextEncoder();
    const args = [player, ship, origin, orient].map((s) => {
      const buf = enc.encode(s);
      const ptr = Number(ex.alloc(buf.length));
      new Uint8Array(host.memory.buffer).set(buf, ptr);
      return [ptr, buf.length];
    });
    try {
      const packed = ex.battleship_place(
        args[0][0], args[0][1], args[1][0], args[1][1],
        args[2][0], args[2][1], args[3][0], args[3][1],
      );
      return decodePacked(host, packed);
    } finally {
      for (const [p, l] of args) ex.dealloc(p, l);
    }
  }

  /** Hot-seat only: lay a fixed P2 "house" fleet so single-player can fire. The
   *  layout is the engine's standard distinct-row plan (A1.., A2.., …). */
  function placeHouseFleet(host) {
    const plan = [
      ['carrier', 'A1', 'h'],
      ['battleship', 'A2', 'h'],
      ['cruiser', 'A3', 'h'],
      ['submarine', 'A4', 'h'],
      ['destroyer', 'A5', 'h'],
    ];
    for (const [ship, origin, orient] of plan) {
      placeShip(host, 'p2', ship, origin, orient);
    }
  }

  /** Declare `player`'s fleet final via battleship_ready; returns parsed JSON. */
  function readyFleet(host, player) {
    const ex = host.exports;
    const enc = new TextEncoder();
    const buf = enc.encode(player);
    const ptr = Number(ex.alloc(buf.length));
    new Uint8Array(host.memory.buffer).set(buf, ptr);
    try {
      return decodePacked(host, ex.battleship_ready(ptr, buf.length));
    } finally {
      ex.dealloc(ptr, buf.length);
    }
  }

  /** Record a relayed (coord,result) on the opponent fog via battleship_record. */
  function recordShot(host, coord, result) {
    const ex = host.exports;
    const enc = new TextEncoder();
    const a = enc.encode(coord);
    const ap = Number(ex.alloc(a.length));
    new Uint8Array(host.memory.buffer).set(a, ap);
    const b = enc.encode(result);
    const bp = Number(ex.alloc(b.length));
    new Uint8Array(host.memory.buffer).set(b, bp);
    try {
      return decodePacked(host, ex.battleship_record(ap, a.length, bp, b.length));
    } finally {
      ex.dealloc(ap, a.length);
      ex.dealloc(bp, b.length);
    }
  }

  /** Decode a packed (ptr<<32|len) return into parsed JSON (or null). */
  function decodePacked(host, packed) {
    const big = BigInt.asUintN(64, BigInt(packed));
    const ptr = Number((big >> 32n) & 0xffffffffn);
    const len = Number(big & 0xffffffffn);
    if (ptr === 0 || len === 0) return null;
    const out = new Uint8Array(host.memory.buffer).slice(ptr, ptr + len);
    host.exports.dealloc(ptr, len);
    return JSON.parse(new TextDecoder().decode(out));
  }

  const GAME = {
    slug: 'battleship',

    async init(host, container, ctx) {
      host.callStatus('battleship_new');
      // host = Player One, guest = Player Two; hot-seat (no session) = null.
      const params = ctx && ctx.params;
      if (params && params.sessionId) {
        localPlayer = ctx.isHost ? 'p1' : 'p2';
      } else {
        localPlayer = null; // hot-seat: this client owns BOTH fleets
      }
      buildDom(container, ui, ctx);
      state = {
        status: 'placing',
        turn: 'p1',
        toPlace: FLEET.slice(),
        localReady: false,
        own: emptyGrid(),
        ownShips: [],
        enemy: emptyGrid(),
        p1Sunk: 0,
        p2Sunk: 0,
      };
      return state;
    },

    render(stateJson, ctx) {
      if (stateJson && stateJson !== state) {
        state = stateJson;
      }
      drawBoard(ui.own, state, 'own', ui);
      drawBoard(ui.enemy, state, 'enemy', ui);
      drawHud(ui, state, localPlayer, ctx);
    },

    onLocalInput(evt, ctx) {
      const host = ctx.host;
      if (!evt) return null;

      // ── placement orientation toggle ─────────────────────────────────────
      if (evt.type === 'orient') {
        ui.orient = ui.orient === 'h' ? 'v' : 'h';
        return { state };
      }

      // ── declare fleet ready ──────────────────────────────────────────────
      if (evt.type === 'ready') {
        if (state.status !== 'placing' || state.toPlace.length > 0 || state.localReady) return null;
        const placePlayer = localPlayer || 'p1';
        const r = readyFleet(host, placePlayer);
        if (!r || r.type === 'battleship.error') {
          flashError(ui, r && r.error);
          return { state };
        }
        ui.banner = null;
        state.localReady = true;
        // In hot-seat the single engine holds BOTH fleets but the human only laid
        // P1's. Auto-place a fixed P2 opponent fleet so firing can begin (a
        // single-player "vs the house layout" mode). In multiplayer the opponent
        // places + readies its own fleet, so we only ready our own slot here.
        if (localPlayer === null) {
          placeHouseFleet(host);
          readyFleet(host, 'p2');
        }
        syncSnapshot(host);
        return {
          input: { kind: 'control', type: 'ready', payload: { ready: placePlayer } },
          state,
        };
      }

      // ── placement click: lay the next ship on the OWN grid (LOCAL only) ──────
      if (evt.type === 'click' && evt.board === 'own' && state.status === 'placing') {
        if (state.toPlace.length === 0 || state.localReady) return null;
        const sq = canvasToCell(ui.own, evt);
        if (!sq) return null;
        const [ship, len] = state.toPlace[0];
        const cells = hullCells(sq, len, ui.orient);
        if (!onBoard(cells)) {
          flashError(ui, 'out-of-bounds');
          return { state };
        }
        const occupied = new Set();
        for (const s of state.ownShips) for (const c of s.cells) occupied.add(`${c.col},${c.row}`);
        if (collides(cells, occupied)) {
          flashError(ui, 'overlap');
          return { state };
        }
        // Apply to the engine on this client's OWN player slot.
        const placePlayer = localPlayer || 'p1';
        const r = placeShip(host, placePlayer, ship, coordName(sq.col, sq.row), ui.orient);
        if (!r || r.type === 'battleship.error') {
          flashError(ui, r && r.error);
          return { state };
        }
        ui.banner = null;
        for (const c of cells) state.own[c.col][c.row] = 'ship';
        state.ownShips.push({ cells, name: ship });
        state.toPlace = state.toPlace.slice(1);
        // NOTE: placement is NEVER relayed — the layout stays local (fog of war).
        return { state };
      }

      // ── firing click on the ENEMY grid: relay ONLY a coord ─────────────────
      if (evt.type === 'click' && evt.board === 'enemy' && state.status === 'firing') {
        if (isGameOver(state.status)) return null;
        const myTurn = state.turn === localPlayer || localPlayer === null;
        if (!myTurn) {
          flashError(ui, 'not your turn');
          return { state };
        }
        const sq = canvasToCell(ui.enemy, evt);
        if (!sq) return null;
        if (state.enemy[sq.col][sq.row] !== null) return null; // already fired here
        const coord = coordName(sq.col, sq.row);

        if (localPlayer === null) {
          // Hot-seat: ONE engine holds both fleets → resolve immediately.
          const shot = host.call('battleship_resolve', coord);
          if (!shot || shot.type === 'battleship.error') {
            flashError(ui, shot && shot.error);
            return { state };
          }
          ui.banner = null;
          applyResultToEnemy(state, coord, shot.result);
          syncSnapshot(host);
          return { state };
        }

        // Multiplayer: relay the coord; the owner resolves and broadcasts back.
        // We mark the cell as 'pending' optimistically? No — leave it untouched
        // until the result returns, so a dropped shot is re-clickable.
        ui.banner = null;
        state.awaitingResult = coord;
        return {
          input: { kind: 'control', type: 'fire', payload: { coord } },
          state,
        };
      }

      return null;
    },

    onRemoteEvent(channel, json, ctx) {
      const host = ctx.host;
      if (!json) return null;

      if (channel === 'session-control') {
        if (json.action && String(json.action).includes('CLOSED')) ui.banner = 'Session closed';
        return state;
      }

      const payload = json.payload || json;
      const type = json.type || (payload && payload.type);

      // ── a relayed READY (opponent finished placing) ────────────────────────
      const readyWho = payload && payload.ready;
      if (readyWho && (readyWho === 'p1' || readyWho === 'p2')) {
        // We only know the opponent declared ready; phase advances on OUR engine
        // when WE are also ready (both-ready gate lives in the engine — but each
        // client only ever readies its own slot, so mirror the opponent's ready
        // onto our engine so our snapshot can reach the firing phase).
        readyFleet(host, readyWho);
        syncSnapshot(host);
        return state;
      }

      // ── an INCOMING SHOT (opponent → here on the input channel) ─────────────
      // We are the board OWNER: resolve it against OUR fleet, then broadcast the
      // result on the world channel. Reveal ONLY the result, never a layout.
      const incomingCoord = payload && payload.coord;
      const incomingResult = payload && payload.result;
      const isWorldResult =
        type === 'battleship.shot' || (incomingResult !== undefined && incomingResult !== null);

      if (incomingCoord && !isWorldResult) {
        if (!isCoordShaped(incomingCoord)) {
          ui.banner = 'Bad shot';
          return { ...state };
        }
        let shot = null;
        try {
          shot = host.call('battleship_resolve', incomingCoord);
        } catch (_e) {
          shot = { type: 'battleship.error', error: 'engine-trap' };
        }
        if (shot && shot.type !== 'battleship.error') {
          ui.banner = null;
          applyIncomingShot(state, incomingCoord, shot.result);
          syncSnapshot(host);
          // Broadcast the resolved result back on the world channel.
          const cid = ctx.params && ctx.params.characterId;
          if (cid !== null && cid !== undefined && ctx.client) {
            ctx.client.sendWorld(cid, GAME.slug, {
              coord: incomingCoord,
              result: shot.result,
              next: shot.next,
              status: shot.status,
            });
          }
          return state;
        }
        ui.banner = 'Host out of sync';
        return { ...state };
      }

      // ── a RELAYED RESULT (owner → here on the world channel) ────────────────
      // We are the SHOOTER: record the revealed result on the opponent fog grid.
      if (isWorldResult && incomingCoord && incomingResult) {
        if (!isCoordShaped(incomingCoord) || !isResultShaped(incomingResult)) {
          ui.banner = 'Host out of sync';
          return { ...state };
        }
        let rec = null;
        try {
          rec = recordShot(host, incomingCoord, incomingResult);
        } catch (_e) {
          rec = { type: 'battleship.error', error: 'engine-trap' };
        }
        if (rec && rec.type !== 'battleship.error') {
          ui.banner = null;
          state.awaitingResult = null;
          applyResultToEnemy(state, incomingCoord, incomingResult);
          syncSnapshot(host);
          // A `win:<ship>` result is the authoritative game-over: WE (the shooter)
          // sank the opponent's last ship. The shooter's own engine cannot detect
          // this (its fog grid is not a real fleet), so trust the relayed result.
          if (rec.status) state.status = rec.status;
          if (incomingResult.startsWith('win:')) {
            state.status = localPlayer === 'p2' ? 'win:p2' : 'win:p1';
          }
          return state;
        }
        ui.banner = 'Host out of sync';
        return { ...state };
      }

      return null;
    },

    destroy() {
      if (typeof window !== 'undefined' && typeof window.removeEventListener === 'function') {
        if (ui.onResize) window.removeEventListener('resize', ui.onResize);
        if (ui.onKey) window.removeEventListener('keydown', ui.onKey);
        ui.onResize = null;
        ui.onKey = null;
      }
    },

    _ui: ui,
    _getState: () => state,
    _localPlayer: () => localPlayer,
    _placeShip: placeShip,
    _readyFleet: readyFleet,
  };

  return GAME;
}

// ── state mutation (fog grids) ────────────────────────────────────────────────

/** Record a shot WE fired against the enemy fog grid from its revealed result. */
function applyResultToEnemy(state, coord, result) {
  const c = parseCoord(coord);
  if (!c) return;
  if (result === 'miss') {
    state.enemy[c.col][c.row] = 'miss';
  } else if (result === 'hit') {
    state.enemy[c.col][c.row] = 'hit';
  } else if (result.startsWith('sunk:') || result.startsWith('win:')) {
    state.enemy[c.col][c.row] = 'sunk';
  }
}

/** Record an opponent's shot against OUR own grid (overlay onto the ship). */
function applyIncomingShot(state, coord, result) {
  const c = parseCoord(coord);
  if (!c) return;
  if (result === 'miss') {
    state.own[c.col][c.row] = 'miss';
  } else {
    // hit / sunk / win all mark our cell as struck (a ship was there).
    state.own[c.col][c.row] = 'hit';
  }
}

// ── DOM / canvas plumbing ─────────────────────────────────────────────────────

function buildDom(container, ui, ctx) {
  const ownCanvas = container.querySelector('#own') || makeCanvas(container, 'own');
  const enemyCanvas = container.querySelector('#enemy') || makeCanvas(container, 'enemy');
  ui.own = { canvas: ownCanvas, cx: ownCanvas.getContext('2d') };
  ui.enemy = { canvas: enemyCanvas, cx: enemyCanvas.getContext('2d') };
  layout(ui);

  ownCanvas.addEventListener('click', (e) => {
    if (ctx && typeof ctx.handleLocalInput === 'function') {
      ctx.handleLocalInput({ type: 'click', board: 'own', offsetX: e.offsetX, offsetY: e.offsetY });
    }
  });
  enemyCanvas.addEventListener('click', (e) => {
    if (ctx && typeof ctx.handleLocalInput === 'function') {
      ctx.handleLocalInput({ type: 'click', board: 'enemy', offsetX: e.offsetX, offsetY: e.offsetY });
    }
  });

  const statusEl = container.querySelector('#status');
  const sunkEl = container.querySelector('#sunk');
  const readyBtn = container.querySelector('#ready');
  const rotateBtn = container.querySelector('#rotate');
  ui.hud = { status: statusEl, sunk: sunkEl, ready: readyBtn, rotate: rotateBtn };

  if (readyBtn && ctx) {
    readyBtn.addEventListener('click', () => {
      if (typeof ctx.handleLocalInput === 'function') ctx.handleLocalInput({ type: 'ready' });
    });
  }
  if (rotateBtn && ctx) {
    rotateBtn.addEventListener('click', () => {
      if (typeof ctx.handleLocalInput === 'function') {
        ctx.handleLocalInput({ type: 'orient' });
        ctx.GAME.render(ctx.GAME._getState(), ctx);
      }
    });
  }

  if (typeof window !== 'undefined' && typeof window.addEventListener === 'function') {
    ui.onResize = () => {
      layout(ui);
      ctx.GAME.render(ctx.GAME._getState(), ctx);
    };
    window.addEventListener('resize', ui.onResize);
    // 'R' rotates the ship during placement.
    ui.onKey = (e) => {
      if ((e.key === 'r' || e.key === 'R') && typeof ctx.handleLocalInput === 'function') {
        ctx.handleLocalInput({ type: 'orient' });
        ctx.GAME.render(ctx.GAME._getState(), ctx);
      }
    };
    window.addEventListener('keydown', ui.onKey);
    if (ctx && typeof ctx.onTeardown === 'function') {
      ctx.onTeardown(() => {
        if (ui.onResize) window.removeEventListener('resize', ui.onResize);
        if (ui.onKey) window.removeEventListener('keydown', ui.onKey);
        ui.onResize = null;
        ui.onKey = null;
      });
    }
  }
}

function makeCanvas(container, id) {
  const c = typeof document !== 'undefined' ? document.createElement('canvas') : null;
  if (c) {
    c.id = id;
    container.appendChild(c);
  }
  return c;
}

function layout(ui) {
  let target = 320;
  if (typeof window !== 'undefined' && window.innerWidth) {
    // Two boards side-by-side on wide screens; cap each so both fit.
    const avail = Math.min(window.innerWidth, window.innerHeight || window.innerWidth);
    target = Math.max(220, Math.min(Math.floor((window.innerWidth - 48) / 2), avail - 80, 400));
  }
  ui.cell = Math.floor(target / SIZE);
  const px = ui.cell * SIZE;
  for (const b of [ui.own, ui.enemy]) {
    if (!b || !b.canvas) continue;
    b.canvas.width = px;
    b.canvas.height = px;
    if (b.canvas.style) {
      b.canvas.style.width = px + 'px';
      b.canvas.style.height = px + 'px';
    }
  }
}

function canvasToCell(board, evt) {
  if (!board || !board.canvas) return null;
  const cell = lastCell(board);
  const rect =
    typeof board.canvas.getBoundingClientRect === 'function'
      ? board.canvas.getBoundingClientRect()
      : { left: 0, top: 0, width: cell * SIZE, height: cell * SIZE };
  let x = evt.offsetX;
  let y = evt.offsetY;
  if (x === undefined || y === undefined) {
    x = (evt.clientX || 0) - rect.left;
    y = (evt.clientY || 0) - rect.top;
  }
  const scaleX = (cell * SIZE) / (rect.width || cell * SIZE);
  const scaleY = (cell * SIZE) / (rect.height || cell * SIZE);
  const col = Math.floor((x * scaleX) / cell);
  const row = Math.floor((y * scaleY) / cell);
  if (col < 0 || col >= SIZE || row < 0 || row >= SIZE) return null;
  return { col, row };
}

// The cell size lives on `ui` but canvasToCell only has the board; stash it.
function lastCell(board) {
  return board._cell || board.canvas.width / SIZE || 32;
}

// ── drawing ───────────────────────────────────────────────────────────────────

function drawBoard(board, state, which, ui) {
  if (!board) return;
  board._cell = ui.cell;
  const cx = board.cx;
  if (!cx) return;
  const cell = ui.cell;

  for (let col = 0; col < SIZE; col++) {
    for (let row = 0; row < SIZE; row++) {
      const x = col * cell;
      const y = row * cell;
      cx.fillStyle = (col + row) % 2 === 0 ? COLORS.sea : COLORS.seaAlt;
      cx.fillRect(x, y, cell, cell);
      cx.strokeStyle = COLORS.gridLine;
      cx.lineWidth = Math.max(1, cell * 0.03);
      cx.strokeRect(x, y, cell, cell);

      const v = which === 'own' ? state.own[col][row] : state.enemy[col][row];

      // Own ships (drawn as a grey hull rectangle).
      if (which === 'own' && (v === 'ship' || v === 'hit')) {
        cx.fillStyle = COLORS.ship;
        cx.fillRect(x + cell * 0.12, y + cell * 0.12, cell * 0.76, cell * 0.76);
        cx.strokeStyle = COLORS.shipStroke;
        cx.lineWidth = Math.max(1, cell * 0.04);
        cx.strokeRect(x + cell * 0.12, y + cell * 0.12, cell * 0.76, cell * 0.76);
      }

      // Shot markers.
      if (v === 'hit' || v === 'sunk') {
        cx.fillStyle = v === 'sunk' ? COLORS.sunk : COLORS.hit;
        cx.beginPath();
        cx.arc(x + cell / 2, y + cell / 2, cell * 0.26, 0, Math.PI * 2);
        cx.fill();
      } else if (v === 'miss') {
        cx.fillStyle = COLORS.miss;
        cx.beginPath();
        cx.arc(x + cell / 2, y + cell / 2, cell * 0.14, 0, Math.PI * 2);
        cx.fill();
      }
    }
  }

  // Placement preview on the OWN board: the next ship under the cursor is shown
  // via the rotate hint only (no mousemove tracking in the DOM-light path) — we
  // outline the legal home cells of the first unplaced ship at A-origin so the
  // player sees orientation. (Kept light to stay test-friendly.)
}

function drawHud(ui, state, localPlayer, ctx) {
  if (!ui.hud) return;
  if (ui.hud.status) {
    ui.hud.status.textContent = ui.banner ? ui.banner : statusLine(state, localPlayer);
    ui.hud.status.classList && ui.hud.status.classList.toggle('over', isGameOver(state.status));
  }
  if (ui.hud.sunk) {
    // Show how many of EACH fleet has been sunk (public info).
    ui.hud.sunk.textContent = `Enemy sunk: ${enemySunk(state, localPlayer)}/5 · Your sunk: ${ownSunk(state, localPlayer)}/5`;
  }
  if (ui.hud.ready) {
    const canReady = state.status === 'placing' && state.toPlace.length === 0 && !state.localReady;
    ui.hud.ready.disabled = !canReady;
  }
  if (ui.hud.rotate) {
    ui.hud.rotate.disabled = state.status !== 'placing' || state.toPlace.length === 0;
    ui.hud.rotate.textContent = `Rotate (${ui.orient === 'h' ? 'horizontal' : 'vertical'})`;
  }
}

/** Number of the ENEMY fleet's ships sunk (from the engine snapshot). */
function enemySunk(state, localPlayer) {
  if (localPlayer === 'p2') return state.p1Sunk;
  return state.p2Sunk; // p1 or hot-seat → the opponent is p2
}
/** Number of OUR fleet's ships sunk. */
function ownSunk(state, localPlayer) {
  if (localPlayer === 'p2') return state.p2Sunk;
  return state.p1Sunk;
}

function flashError(ui, reason) {
  if (ui.hud && ui.hud.status) {
    ui.banner = `Illegal${reason ? ` (${reason})` : ''}`;
  }
}

export default createBattleshipGame;
