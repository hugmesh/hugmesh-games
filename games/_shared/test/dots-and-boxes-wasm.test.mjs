// Functional test for the browser-loadable Dots-and-Boxes engine WASM module.
//
// Loads games/dots-and-boxes/wasm/hugmesh_dots_and_boxes.wasm with
// WebAssembly.instantiate and EMPTY imports (proving it runs in a bare browser
// via instantiateStreaming with no host glue), then drives edges through the
// C-ABI / JSON-over-linear-memory shim. If this passes, the same .wasm the
// browser bundle ships is rules-correct.
//
// The board is a 3x3 grid of BOXES over a 4x4 grid of DOTS. Edge indexing
// (mirrors the engine):
//   horizontal edges: index = row*GRID + col           (row 0..DOTS, col 0..GRID)
//   vertical edges:   index = HCOUNT + row*DOTS + col   (row 0..GRID, col 0..DOTS)
// with GRID=3, DOTS=4, HCOUNT=12, EDGES=24, BOXES=9.
// Box (0,0) is bounded by: top=H(0,0)=0, bottom=H(1,0)=3, left=V(0,0)=12,
// right=V(0,1)=13.
//
// Run:  node games/_shared/test/dots-and-boxes-wasm.test.mjs

import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const WASM_PATH = join(
  here,
  '..',
  '..',
  'dots-and-boxes',
  'wasm',
  'hugmesh_dots_and_boxes.wasm',
);

// ── edge-index helpers (the engine's geometry) ───────────────────────────────
const GRID = 3;
const DOTS = 4;
const HCOUNT = DOTS * GRID; // 12
const h = (row, col) => row * GRID + col;
const v = (row, col) => HCOUNT + row * DOTS + col;

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
  function strIn(s) {
    const buf = enc.encode(s);
    const ptr = ex.alloc(buf.length);
    new Uint8Array(mem.buffer, ptr, buf.length).set(buf);
    return [ptr, buf.length];
  }
  function readPacked(packed) {
    const big = BigInt.asUintN(64, BigInt(packed));
    const ptr = Number(big >> 32n);
    const len = Number(big & 0xffffffffn);
    if (ptr === 0 || len === 0) return '';
    const s = dec.decode(new Uint8Array(mem.buffer, ptr, len).slice());
    ex.dealloc(ptr, len);
    return s;
  }
  function callStr(fn, s) {
    const [ptr, len] = strIn(s);
    const out = readPacked(fn(ptr, len));
    ex.dealloc(ptr, len);
    return out;
  }
  // Claim an edge by index → parsed dab.state | dab error object.
  const claim = (idx) => JSON.parse(callStr(ex.dab_apply, String(idx)));
  const legal = () => JSON.parse(readPacked(ex.dab_legal()));
  const state = () => JSON.parse(readPacked(ex.dab_state()));
  const status = () => JSON.parse(readPacked(ex.dab_status())).status;

  console.log(`Loaded ${WASM_PATH} (${bytes.length} bytes), zero host imports.`);

  // ── 1. fresh board: dab_new + 24 legal edges ───────────────────────────────
  assert(ex.dab_new() === 1, 'dab_new() returns 1 (empty board created)');
  const startLegal = legal();
  assert(Array.isArray(startLegal), 'dab_legal() is a JSON array');
  assert(
    startLegal.length === 24,
    `dab_legal() at start has 24 edges (got ${startLegal.length})`,
  );
  const s0 = state();
  assert(s0.type === 'dab.full', 'dab_state() type is "dab.full"');
  assert(s0.edges.length === 24 && s0.edges.every((e) => e === false),
    'dab_state() start: 24 unclaimed edges');
  assert(s0.owners.length === 9 && s0.owners.every((o) => o === null),
    'dab_state() start: 9 unowned boxes');
  assert(s0.turn === 'one' && s0.score[0] === 0 && s0.score[1] === 0,
    'dab_state() start: player one to move, 0-0');
  assert(status() === 'ongoing', 'dab_status() at start is "ongoing"');

  // ── 2. complete box (0,0): claim + extra turn + box owner + score ───────────
  // Lay three sides of box (0,0) with non-completing moves (turn alternates),
  // then P2 closes it with the bottom edge → claims the box, scores, moves again.
  //   top    = h(0,0)=0   left = v(0,0)=12   right = v(0,1)=13   bottom = h(1,0)=3
  const o1 = claim(h(0, 0)); // P1 top   -> pass to P2
  assert(o1.type === 'dab.state', 'first claim returns a dab.state payload');
  assert(o1.boxes === 0 && o1.extraTurn === false && o1.by === 'one' && o1.next === 'two',
    'top edge: 0 boxes, no extra turn, turn passes one→two');

  const o2 = claim(v(0, 0)); // P2 left  -> pass to P1
  assert(o2.by === 'two' && o2.boxes === 0 && o2.next === 'one',
    'left edge: P2 completes nothing, turn passes two→one');

  const o3 = claim(v(0, 1)); // P1 right -> pass to P2
  assert(o3.by === 'one' && o3.boxes === 0 && o3.next === 'two',
    'right edge: P1 completes nothing, turn passes one→two');

  // P2 closes box (0,0) with the bottom edge.
  const o4 = claim(h(1, 0));
  assert(o4.type === 'dab.state', 'closing claim returns a dab.state payload');
  assert(o4.by === 'two', 'box (0,0) closed BY player two');
  assert(o4.boxes === 1, 'closing claim completed exactly 1 box');
  assert(o4.extraTurn === true, 'completing a box grants an EXTRA TURN');
  assert(o4.next === 'two', 'same player (two) keeps the turn after a box');
  assert(o4.score[0] === 0 && o4.score[1] === 1,
    `box-owner player two scored 1 (score now ${JSON.stringify(o4.score)})`);
  assert(o4.status === 'ongoing', 'game still ongoing after the first box');

  // The full-board snapshot confirms ownership: box index 0 = box (0,0).
  const sBox = state();
  assert(sBox.owners[0] === 'two', 'dab_state() owners[0] === "two" (box (0,0) owner)');
  assert(sBox.turn === 'two', 'dab_state() turn still "two" after the extra turn');
  assert(sBox.score[1] === 1, 'dab_state() score reflects player two = 1');

  // ── 3. already-claimed edge is rejected (no state change) ───────────────────
  const beforeLen = legal().length;
  const dup = claim(h(0, 0)); // already claimed in step 2
  assert(dup.type === 'dots-and-boxes.error',
    'a second claim of an already-drawn edge returns the error shape');
  assert(dup.error === 'already-claimed',
    `rejected edge carries error "already-claimed" (got "${dup.error}")`);
  assert(legal().length === beforeLen,
    'a rejected claim does NOT consume an edge (legal count unchanged)');
  assert(state().turn === 'two',
    'a rejected claim does NOT change the turn');

  // An out-of-bounds index is also rejected.
  const oob = claim(24); // EDGES == 24, so index 24 is out of range
  assert(oob.type === 'dots-and-boxes.error' && oob.error === 'out-of-bounds',
    `out-of-bounds edge rejected with "out-of-bounds" (got "${oob.error}")`);

  // ── 4. play a full game to the end; last box ends it with the right winner ──
  // Replay an independently-verified full 24-edge order that finishes 7–2 for
  // player one (the same order pinned in the engine's `P1_WINS_GAME`).
  assert(ex.dab_new() === 1, 'dab_new() resets to a fresh board');
  const P1_WINS = [
    2, 4, 9, 12, 20,
    1, 10, 14, 16, 17,
    5, 21, 18, 19, 23,
    7, 0, 3, 8, 11,
    22, 6, 15, 13,
  ];
  let last = null;
  let overSeenEarly = false;
  for (let i = 0; i < P1_WINS.length; i++) {
    const o = claim(P1_WINS[i]);
    assert(o.type === 'dab.state', `full-game move ${i + 1} (edge ${P1_WINS[i]}) accepted`);
    if (i < P1_WINS.length - 1 && o.status !== 'ongoing') overSeenEarly = true;
    last = o;
  }
  assert(!overSeenEarly, 'game stayed "ongoing" until the final edge');
  assert(legal().length === 0, 'all 24 edges claimed at end');

  // The FINAL closing claim ends the game (no extra turn even though it closes a
  // box), and names player one the winner with a 7–2 score.
  assert(last.boxes >= 1, 'the last edge closes at least one box');
  assert(last.extraTurn === false,
    'NO extra turn on the final move — the game is over');
  assert(last.status === 'win:one',
    `final status is "win:one" (got "${last.status}")`);
  assert(last.score[0] === 7 && last.score[1] === 2,
    `final score is 7–2 for player one (got ${JSON.stringify(last.score)})`);
  assert(status() === 'win:one',
    `dab_status() after the game is "win:one" (got "${status()}")`);

  // All nine boxes owned; player one owns the majority.
  const sEnd = state();
  const ownedByOne = sEnd.owners.filter((o) => o === 'one').length;
  const ownedByTwo = sEnd.owners.filter((o) => o === 'two').length;
  assert(ownedByOne + ownedByTwo === 9, 'every box is owned at game end');
  assert(ownedByOne === 7 && ownedByTwo === 2,
    `box ownership split 7–2 for player one (got ${ownedByOne}–${ownedByTwo})`);

  // ── 5. claims after the game is over are rejected with "game-over" ──────────
  const afterEnd = claim(0);
  assert(afterEnd.type === 'dots-and-boxes.error' && afterEnd.error === 'game-over',
    `a claim after game-over is rejected with "game-over" (got "${afterEnd.error}")`);

  // ── 6. dab_from_edges replays an ordered claim list into identical state ────
  // Rebuild the post-step-2 position (the four edges that gave P2 box (0,0)) and
  // confirm the from-state path reconstructs turn + score + ownership exactly.
  const replayOk = ex.dab_from_edges(...strIn(JSON.stringify([h(0, 0), v(0, 0), v(0, 1), h(1, 0)])));
  assert(replayOk === 1, 'dab_from_edges() replays a valid claim list (returns 1)');
  const sReplay = state();
  assert(sReplay.owners[0] === 'two' && sReplay.score[1] === 1 && sReplay.turn === 'two',
    'dab_from_edges() reconstructs box (0,0)=two, score 0–1, turn two');
  // A from_edges with an already-claimed duplicate is rejected (returns 0).
  const replayBad = ex.dab_from_edges(...strIn(JSON.stringify([0, 0])));
  assert(replayBad === 0, 'dab_from_edges() with a duplicate edge returns 0 (rejected)');

  // ── report ──────────────────────────────────────────────────────────────────
  console.log(`\n${passed} assertions passed, ${failures.length} failed.`);
  if (failures.length > 0) {
    console.error('FAILURES:\n  - ' + failures.join('\n  - '));
    process.exit(1);
  }
  console.log('OK — browser-loadable dots-and-boxes WASM is rules-correct.');
}

main().catch((e) => {
  console.error('test crashed:', e);
  process.exit(1);
});
