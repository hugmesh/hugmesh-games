// Functional test for the browser-loadable Bomberman engine WASM module.
//
// Loads games/bomberman/wasm/hugmesh_bomberman_wasm.wasm with
// WebAssembly.instantiate and EMPTY imports (proving it runs in a bare browser via
// instantiateStreaming with no host glue), then drives a DETERMINISTIC two-player
// tick simulation through the C-ABI / JSON-over-linear-memory shim. If this passes,
// the same .wasm the browser bundle ships is rules-correct.
//
// CATEGORY: REALTIME DUEL (host-authoritative tick loop). The engine is a
// fixed-timestep simulation: bomberman_input(player,action) records intent and
// bomberman_tick() advances ONE discrete step resolving both players at once. The
// scenario below is seed-independent: P1 drops a bomb on its spawn cell (1,1) and
// then waits — the bomb's own cell always flames, so on the FUSE-th tick P1 dies to
// its own blast while P2 (idle, far away at the opposite corner) survives → the
// terminal status is "win:p2". FUSE = 8, so detonation/death/terminal land exactly
// on tick 8 for every seed.
//
// Run:  node games/_shared/test/bomberman-wasm.test.mjs

import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const WASM_PATH = join(here, '..', '..', 'bomberman', 'wasm', 'hugmesh_bomberman_wasm.wasm');

// FUSE / board geometry constants mirrored from hugmesh-bomberman/src/lib.rs.
const FUSE = 8;
const WIDTH = 13;
const HEIGHT = 11;

// ── tiny assertion harness ───────────────────────────────────────────────────
let passed = 0;
const failures = [];
function assert(cond, msg) {
  if (cond) {
    passed++;
    console.log(`  PASS  ${msg}`);
  } else {
    failures.push(msg);
    console.error(`  FAIL  ${msg}`);
  }
}

async function main() {
  const bytes = await readFile(WASM_PATH);
  // EMPTY imports — the module must self-contain (zero host imports).
  const { instance } = await WebAssembly.instantiate(bytes, {});
  const ex = instance.exports;
  const mem = ex.memory;
  const enc = new TextEncoder();
  const dec = new TextDecoder();

  // ── linear-memory marshalling helpers (the documented ABI) ─────────────────
  // Pass a UTF-8 string IN: alloc → write → returns [ptr, len] for an op call.
  function strIn(s) {
    const buf = enc.encode(s);
    const ptr = ex.alloc(buf.length);
    new Uint8Array(mem.buffer, ptr, buf.length).set(buf);
    return [ptr, buf.length];
  }
  // Read a packed (ptr<<32|len) u64 return as a UTF-8 string, then free it.
  function readPacked(packed) {
    const big = BigInt.asUintN(64, BigInt(packed));
    const ptr = Number(big >> 32n);
    const len = Number(big & 0xffffffffn);
    if (ptr === 0 || len === 0) return '';
    const s = dec.decode(new Uint8Array(mem.buffer, ptr, len).slice());
    ex.dealloc(ptr, len);
    return s;
  }
  // Record one player's action; returns the i32 accept(1)/reject(0) status.
  function input(token) {
    const [ptr, len] = strIn(token);
    const r = ex.bomberman_input(ptr, len) | 0;
    ex.dealloc(ptr, len);
    return r;
  }
  // Advance one tick; returns the parsed snapshot AFTER the tick.
  function tick() {
    return JSON.parse(readPacked(ex.bomberman_tick()));
  }
  function snapshot() {
    return JSON.parse(readPacked(ex.bomberman_snapshot()));
  }
  function status() {
    return JSON.parse(readPacked(ex.bomberman_status()));
  }

  console.log(`Loaded ${WASM_PATH} (${bytes.length} bytes), zero host imports.`);

  // ── 1. new(seed) builds a fresh, deterministic match ────────────────────────
  assert(ex.bomberman_new(0n) === 1, 'bomberman_new(0) returns 1 (match created)');

  const open = snapshot();
  assert(open.type === 'bomberman.snapshot', 'bomberman_snapshot() emits a bomberman.snapshot');
  assert(open.width === WIDTH && open.height === HEIGHT, `board is ${WIDTH}x${HEIGHT} (got ${open.width}x${open.height})`);
  assert(open.board.length === WIDTH * HEIGHT, `board string is ${WIDTH * HEIGHT} cells (got ${open.board.length})`);
  assert(open.tick === 0, `fresh match is at tick 0 (got ${open.tick})`);
  assert(open.status === 'playing', `fresh match status is "playing" (got "${open.status}")`);
  assert(Array.isArray(open.players) && open.players.length === 2, 'snapshot has two players');
  const p1Open = open.players.find((p) => p.id === 'p1');
  const p2Open = open.players.find((p) => p.id === 'p2');
  assert(p1Open && p1Open.x === 1 && p1Open.y === 1 && p1Open.alive, 'P1 spawns alive at the top-left corner (1,1)');
  assert(p2Open && p2Open.x === WIDTH - 2 && p2Open.y === HEIGHT - 2 && p2Open.alive, `P2 spawns alive at the bottom-right corner (${WIDTH - 2},${HEIGHT - 2})`);
  assert(Array.isArray(open.bombs) && open.bombs.length === 0, 'no bombs on the board at the opening');
  assert(Array.isArray(open.flames) && open.flames.length === 0, 'no flames on the board at the opening');

  const openStatus = status();
  assert(openStatus.status === 'playing', 'bomberman_status() at the opening is "playing"');

  // The border is an indestructible wall (top row all '#').
  assert(/^#{13}$/.test(open.board.slice(0, WIDTH)), 'top row of the board is an indestructible wall');

  // Same seed → byte-identical board (determinism is the whole platform contract).
  ex.bomberman_new(0n);
  const openAgain = snapshot();
  assert(openAgain.board === open.board, 'same seed yields a byte-identical board (determinism)');

  // ── 2. illegal / unparseable inputs are rejected (return 0) ─────────────────
  ex.bomberman_new(7n);
  assert(input('garbage') === 0, 'a token with no ":" separator is rejected (0)');
  assert(input('p3:up') === 0, 'an unknown player "p3" is rejected (0)');
  assert(input('p1:fly') === 0, 'an unknown action "fly" is rejected (0)');
  assert(input('') === 0, 'an empty token is rejected (0)');
  // A valid token IS accepted.
  assert(input('p1:right') === 1, 'a valid "p1:right" is accepted (1)');
  // Recording an input does NOT advance the tick (still tick 0).
  assert(snapshot().tick === 0, 'recording an input does not advance the tick');

  // ── 3. movement: one accepted move steps the pawn exactly one cell on tick ──
  // (1,1)→(2,1): (2,1) is a kept-clear escape lane, so always walkable.
  ex.bomberman_new(7n);
  assert(input('p1:right') === 1, 'P1 right accepted');
  let s = tick();
  assert(s.tick === 1, `tick advanced to 1 (got ${s.tick})`);
  const p1Moved = s.players.find((p) => p.id === 'p1');
  assert(p1Moved.x === 2 && p1Moved.y === 1, `P1 stepped one cell right to (2,1) (got (${p1Moved.x},${p1Moved.y}))`);
  // A move into the border wall is a silent no-op (resolved at tick time).
  ex.bomberman_new(7n);
  input('p1:up'); // (1,1) up → (1,0) is the border wall.
  s = tick();
  const p1Wall = s.players.find((p) => p.id === 'p1');
  assert(p1Wall.x === 1 && p1Wall.y === 1, 'a move into a wall is a no-op (P1 stays at (1,1))');

  // ── 4. bomb placement shows up on the board, ticks its fuse down ────────────
  ex.bomberman_new(7n);
  assert(input('p1:bomb') === 1, 'P1 bomb accepted');
  s = tick();
  assert(s.bombs.length === 1, `one bomb is live after placing (got ${s.bombs.length})`);
  assert(s.bombs[0].x === 1 && s.bombs[0].y === 1, 'the bomb sits on P1\'s cell (1,1)');
  assert(s.bombs[0].owner === 'p1', 'the bomb is owned by p1');
  assert(s.bombs[0].fuse === FUSE - 1, `the bomb\'s fuse counted down to ${FUSE - 1} on the placement tick (got ${s.bombs[0].fuse})`);
  const p1Bombed = s.players.find((p) => p.id === 'p1');
  assert(p1Bombed.bombs === 1, 'P1 now has one active bomb (capacity consumed)');

  // ── 5. the DETERMINISTIC forced win: P1 bombs (1,1) then waits → win:p2 ──────
  // Seed-independent: the bomb's own cell always flames, so P1 (standing on it,
  // waiting) dies on the FUSE-th tick; P2 is idle in the opposite corner, far
  // outside any blast, so it survives. Verified across several seeds.
  for (const seed of [0, 1, 42, 12345]) {
    ex.bomberman_new(BigInt(seed));
    assert(input('p1:bomb') === 1, `seed ${seed}: P1 bomb accepted`);
    let detonationTick = null;
    let terminal = null;
    // tick 1 places the bomb; nothing detonates until the fuse expires.
    for (let t = 1; t <= FUSE + 2; t++) {
      s = tick();
      if (s.flames.length > 0 && detonationTick === null) {
        detonationTick = s.tick;
      }
      if (s.status !== 'playing') {
        terminal = s;
        break;
      }
    }
    assert(detonationTick === FUSE, `seed ${seed}: the bomb detonates exactly on tick ${FUSE} (got ${detonationTick})`);
    assert(terminal !== null && terminal.status === 'win:p2', `seed ${seed}: terminal status is "win:p2" (got "${terminal && terminal.status}")`);
    const p1 = terminal.players.find((p) => p.id === 'p1');
    const p2 = terminal.players.find((p) => p.id === 'p2');
    assert(!p1.alive, `seed ${seed}: P1 died to its own bomb`);
    assert(p2.alive, `seed ${seed}: P2 survived in the opposite corner`);
    assert(terminal.tick === FUSE, `seed ${seed}: the terminal tick is ${FUSE} (got ${terminal.tick})`);
  }

  // ── 6. blast geometry on the detonation tick (deterministic flame cross) ─────
  // From (1,1) the cross is bounded by the border walls (up/left) and reaches two
  // cells right + down over the kept-clear escape lanes: centre + (2,1),(3,1),
  // (1,2),(1,3). The exact set is seed-independent (those cells are always floor).
  ex.bomberman_new(7n);
  input('p1:bomb');
  let boom = null;
  for (let t = 1; t <= FUSE; t++) {
    s = tick();
    if (s.flames.length > 0) {
      boom = s;
      break;
    }
  }
  assert(boom !== null, 'a detonation tick was observed');
  const flameSet = new Set(boom.flames.map(([x, y]) => `${x},${y}`));
  assert(flameSet.has('1,1'), 'the bomb cell (1,1) flames');
  assert(flameSet.has('2,1') && flameSet.has('3,1'), 'the right arm flames out to range 2: (2,1),(3,1)');
  assert(flameSet.has('1,2') && flameSet.has('1,3'), 'the down arm flames out to range 2: (1,2),(1,3)');
  assert(!flameSet.has('1,0') && !flameSet.has('0,1'), 'the up/left arms hit the border wall — no flame on (1,0)/(0,1)');
  assert(boom.bombs.length === 0, 'the bomb is consumed by its own explosion');

  // ── 7. ticking a finished match is a no-op; input is rejected after game over ─
  // (Continue from the finished match above.)
  ex.bomberman_new(0n);
  input('p1:bomb');
  let over = null;
  for (let t = 1; t <= FUSE + 2; t++) {
    s = tick();
    if (s.status !== 'playing') {
      over = s;
      break;
    }
  }
  assert(over && over.status === 'win:p2', 'reached the terminal win:p2 state');
  assert(input('p2:up') === 0, 'an input after game over is rejected (0)');
  const afterTick = tick();
  assert(afterTick.status === 'win:p2', 'ticking a finished match re-reports the terminal status');
  assert(afterTick.tick === over.tick, 'ticking a finished match does NOT advance the tick counter');
  assert(status().status === 'win:p2', 'bomberman_status() reports the terminal "win:p2"');

  // ── report ──────────────────────────────────────────────────────────────────
  console.log(`\n${passed} assertions passed, ${failures.length} failed.`);
  if (failures.length > 0) {
    console.error('FAILURES:\n  - ' + failures.join('\n  - '));
    process.exit(1);
  }
  console.log('OK — browser-loadable Bomberman WASM is rules-correct (deterministic tick loop).');
}

main().catch((e) => {
  console.error('test crashed:', e);
  process.exit(1);
});
