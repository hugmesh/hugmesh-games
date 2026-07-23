// Functional test for the browser-loadable Tron light-cycles engine WASM module.
//
// Loads games/tron/wasm/hugmesh_tron_wasm.wasm with WebAssembly.instantiate
// and EMPTY imports (proving it runs in a bare browser via instantiateStreaming
// with no host glue), then drives DETERMINISTIC, hand-checked scenarios through
// the C-ABI / JSON-over-linear-memory shim — the realtime tick loop:
//
//   tron_new(seed)              — default 20x20 match seeded by `seed`
//   tron_new_sized(seed,w,h)    — sized match (forced-terminal setups)
//   tron_set_dir(player,dir)    — queue a heading (player 0/1, dir 0..3) → 1|0
//   tron_tick()                 — advance one fixed step → Outcome JSON (packed)
//   tron_snapshot()             — full-state snapshot JSON (packed)
//   tron_status()               — { status } (packed)
//
// If this passes, the same .wasm the browser bundle ships is simulation-correct
// AND deterministic (same seed + same input sequence ⇒ identical outcome).
//
// Run:  node games/_shared/test/tron-wasm.test.mjs

import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const WASM_PATH = join(here, '..', '..', 'tron', 'wasm', 'hugmesh_tron_wasm.wasm');

// Direction codes (must match the shim: 0=up 1=down 2=left 3=right).
const UP = 0;
const DOWN = 1;
const LEFT = 2;
const RIGHT = 3;
// Player codes (0=One, 1=Two).
const ONE = 0;
const TWO = 1;

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
  const dec = new TextDecoder();

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
  const tick = () => JSON.parse(readPacked(ex.tron_tick()));
  const snapshot = () => JSON.parse(readPacked(ex.tron_snapshot()));
  const status = () => JSON.parse(readPacked(ex.tron_status())).status;

  console.log(`Loaded ${WASM_PATH} (${bytes.length} bytes), zero host imports.`);

  // ── 1. opening: tron_new + the mirror-facing start snapshot ─────────────────
  assert(ex.tron_new(1n) === 1, 'tron_new(1) returns 1 (default 20x20 match created)');
  const open = snapshot();
  assert(open.type === 'tron.snapshot', 'tron_snapshot() emits a tron.snapshot');
  assert(open.width === 20 && open.height === 20, `default grid is 20x20 (got ${open.width}x${open.height})`);
  assert(open.tick === 0, `opening tick counter is 0 (got ${open.tick})`);
  assert(open.status === 'playing', `opening status is "playing" (got "${open.status}")`);
  // Mirror facing pair: One on the left facing right, Two on the right facing left, same row.
  assert(open.one.dir === 'right', `One faces right at the opening (got "${open.one.dir}")`);
  assert(open.two.dir === 'left', `Two faces left at the opening (got "${open.two.dir}")`);
  assert(open.one.y === open.two.y, 'both cycles start on the same row');
  assert(open.one.x < open.two.x, 'One is left of Two');
  assert(open.one.alive === true && open.two.alive === true, 'both cycles start alive');
  assert(open.board.length === 20 * 20, `flat board is w*h chars (got ${open.board.length})`);
  assert(status() === 'playing', 'tron_status() at the opening is "playing"');

  // ── 2. a tick advances both heads one cell and lays trail ───────────────────
  const ox = open.one.x, oy = open.one.y, tx = open.two.x, ty = open.two.y;
  const out1 = tick();
  assert(out1.type === 'tron.tick', 'tron_tick() emits a tron.tick outcome');
  assert(out1.tick === 1, `first tick is tick 1 (got ${out1.tick})`);
  assert(out1.oneCrashed === false && out1.twoCrashed === false, 'neither cycle crashed on tick 1');
  assert(out1.status === 'playing', `still playing after tick 1 (got "${out1.status}")`);
  const after1 = snapshot();
  assert(after1.one.x === ox + 1 && after1.one.y === oy, `One moved one cell right (got ${after1.one.x},${after1.one.y})`);
  assert(after1.two.x === tx - 1 && after1.two.y === ty, `Two moved one cell left (got ${after1.two.x},${after1.two.y})`);
  // The vacated start cells are solid trail ('1' for One, '2' for Two).
  assert(after1.board[oy * 20 + ox] === '1', "One's vacated start cell is a solid trail");
  assert(after1.board[ty * 20 + tx] === '2', "Two's vacated start cell is a solid trail");

  // ── 3. illegal 180° reversal is rejected; legal 90° turn accepted ───────────
  // One faces right after tick 1; a direct reversal to left must be rejected.
  assert(ex.tron_set_dir(ONE, LEFT) === 0, 'tron_set_dir(One, left) rejected — illegal 180° reversal');
  // A legal 90° turn (down) is accepted.
  assert(ex.tron_set_dir(ONE, DOWN) === 1, 'tron_set_dir(One, down) accepted — legal 90° turn');
  // A bad player code is rejected.
  assert(ex.tron_set_dir(7, UP) === 0, 'tron_set_dir(badPlayer, up) rejected');
  // A bad direction code is rejected.
  assert(ex.tron_set_dir(ONE, 9) === 0, 'tron_set_dir(One, badDir) rejected');
  // The accepted Down turn takes effect on the next tick: One moves down one row.
  const beforeTurn = snapshot();
  tick();
  const afterTurn = snapshot();
  assert(
    afterTurn.one.x === beforeTurn.one.x && afterTurn.one.y === beforeTurn.one.y + 1,
    `One turned down (got ${afterTurn.one.x},${afterTurn.one.y} from ${beforeTurn.one.x},${beforeTurn.one.y})`,
  );

  // ── 4. FORCED WIN: One drives into the top wall while Two runs a clear runway ─
  // Tall board so Two's downward runway far exceeds One's distance to the ceiling.
  // One straight UP at the wall, Two straight DOWN its own column → only One
  // crashes → a clean Win for Two (never a Draw). (Mirrors the engine's
  // crash_into_wall_loses test.)
  assert(ex.tron_new_sized(1n, 20, 24) === 1, 'tron_new_sized(1,20,24) created a tall match');
  const winOpen = snapshot();
  const winOy = winOpen.one.y;
  let winTick = null;
  for (let t = 1; t <= winOy + 2; t++) {
    ex.tron_set_dir(ONE, UP); // straight at the ceiling
    ex.tron_set_dir(TWO, DOWN); // straight down its own clear column
    const o = tick();
    if (o.status !== 'playing') {
      assert(o.oneCrashed === true, 'One hit the ceiling (one_crashed)');
      assert(o.twoCrashed === false, 'Two is alive on its clear runway');
      assert(o.status === 'win:two', `terminal status is win:two (got "${o.status}")`);
      winTick = t;
      break;
    }
    assert(o.status === 'playing', `tick ${t} still playing before the crash`);
  }
  assert(winTick !== null, 'One must hit the top wall and lose to Two');
  const winFinal = snapshot();
  assert(winFinal.status === 'win:two', `snapshot reports win:two after the crash (got "${winFinal.status}")`);
  assert(winFinal.one.alive === false, 'One is not alive after crashing into the wall');
  assert(winFinal.two.alive === true, 'Two survives the runway');
  assert(status() === 'win:two', 'tron_status() reports win:two at the terminal');
  // A set_dir AFTER the game is over is rejected.
  assert(ex.tron_set_dir(TWO, LEFT) === 0, 'tron_set_dir rejected after the game is over');
  // A tick after game over is an inert no-op (tick counter frozen, no new crash).
  const frozenTick = winFinal.tick;
  const inert = tick();
  assert(inert.oneCrashed === false && inert.twoCrashed === false, 'post-game tick records no new crash');
  assert(snapshot().tick === frozenTick, `tick counter frozen after game over (still ${frozenTick})`);

  // ── 5. SYMMETRIC HEAD-ON: even gap on the same row ⇒ Draw ────────────────────
  // Width 9: one_x=2, two_x=6 → gap 4 (even). Driving straight at each other they
  // share the centre cell on the same tick → mutual crash → Draw. (Mirrors the
  // engine's simultaneous_head_on_is_a_draw test.)
  assert(ex.tron_new_sized(1n, 9, 5) === 1, 'tron_new_sized(1,9,5) created an even-gap match');
  const hoOpen = snapshot();
  assert((hoOpen.two.x - hoOpen.one.x) % 2 === 0, 'even gap between the cycles → they share a centre cell');
  let drawTick = null;
  for (let t = 1; t < 20; t++) {
    const o = tick(); // default headings drive them straight at each other
    if (o.status !== 'playing') {
      assert(o.oneCrashed === true && o.twoCrashed === true, 'both crash on the head-on tick');
      assert(o.status === 'draw', `head-on resolves to a draw (got "${o.status}")`);
      drawTick = t;
      break;
    }
  }
  assert(drawTick !== null, 'the head-on must resolve to a Draw');
  const drawFinal = snapshot();
  assert(drawFinal.status === 'draw', `snapshot reports draw (got "${drawFinal.status}")`);
  assert(drawFinal.one.alive === false && drawFinal.two.alive === false, 'both cycles crashed in the draw');
  assert(status() === 'draw', 'tron_status() reports draw at the terminal');

  // ── 6. DETERMINISM: same seed + same input sequence ⇒ identical outcome ─────
  // Replay a scripted sequence twice on two fresh same-seed matches and assert the
  // per-tick outcomes AND the final snapshot are byte-identical (the property the
  // host-authoritative guest re-validation relies on).
  const script = [
    [DOWN, UP], [null, null], [RIGHT, LEFT], [null, DOWN],
    [DOWN, null], [null, null], [LEFT, RIGHT], [null, null],
    [UP, DOWN], [null, null], [RIGHT, LEFT], [null, null],
  ];
  function run(seedBig) {
    ex.tron_new_sized(seedBig, 24, 24);
    const outs = [];
    for (const [d1, d2] of script) {
      if (d1 !== null) ex.tron_set_dir(ONE, d1);
      if (d2 !== null) ex.tron_set_dir(TWO, d2);
      const o = tick();
      outs.push(`${o.tick}:${o.oneCrashed}:${o.twoCrashed}:${o.status}`);
      if (o.status !== 'playing') break;
    }
    return { outs, snap: JSON.stringify(snapshot()) };
  }
  const a = run(0xC0FFEEn);
  const b = run(0xC0FFEEn);
  assert(
    JSON.stringify(a.outs) === JSON.stringify(b.outs),
    'per-tick outcomes are identical for the same seed + input sequence',
  );
  assert(a.snap === b.snap, 'final snapshot JSON is identical for the same seed + input sequence');
  // A different seed must be able to change the start row (the PRNG drives layout).
  ex.tron_new_sized(0n, 40, 40);
  const rowBase = snapshot().one.y;
  let rowDiffers = false;
  for (let s = 1n; s < 50n; s++) {
    ex.tron_new_sized(s, 40, 40);
    if (snapshot().one.y !== rowBase) { rowDiffers = true; break; }
  }
  assert(rowDiffers, 'some seed shifts the start row (the seeded layout is real)');

  // ── 7. no-game guards: snapshot/status/tick degrade to {} / empty ───────────
  // (Fresh instance so GAME is None.) Re-instantiate to prove the None path.
  const { instance: fresh } = await WebAssembly.instantiate(bytes, {});
  const fx = fresh.exports;
  const freshDec = new TextDecoder();
  function readPackedF(packed) {
    const big = BigInt.asUintN(64, BigInt(packed));
    const ptr = Number(big >> 32n);
    const len = Number(big & 0xffffffffn);
    if (ptr === 0 || len === 0) return '';
    const s = freshDec.decode(new Uint8Array(fx.memory.buffer, ptr, len).slice());
    fx.dealloc(ptr, len);
    return s;
  }
  assert(readPackedF(fx.tron_snapshot()) === '{}', 'tron_snapshot() on a fresh (no-game) instance is {}');
  assert(readPackedF(fx.tron_tick()) === '{}', 'tron_tick() on a fresh (no-game) instance is {}');
  assert(fx.tron_set_dir(ONE, UP) === 0, 'tron_set_dir on a fresh (no-game) instance is rejected (0)');

  // ── 8. CROSS-INSTANCE LOCKSTEP: two INDEPENDENT module instances (the host
  //       browser + the guest browser) fed the SAME seed + SAME tick-stamped
  //       input sequence advance bit-for-bit identically. This is the exact
  //       guarantee the host-authoritative guest re-validation relies on: a
  //       guest that re-applies the host's relayed inputs at the SAME tick must
  //       land on byte-identical state, tick by tick. (Section 6 replays on ONE
  //       instance; this replays on TWO, which is the real two-browser shape.) ─
  function bindInstance(exports) {
    const d = new TextDecoder();
    const rp = (packed) => {
      const big = BigInt.asUintN(64, BigInt(packed));
      const ptr = Number(big >> 32n);
      const len = Number(big & 0xffffffffn);
      if (ptr === 0 || len === 0) return '';
      const s = d.decode(new Uint8Array(exports.memory.buffer, ptr, len).slice());
      exports.dealloc(ptr, len);
      return s;
    };
    return {
      exports,
      tick: () => JSON.parse(rp(exports.tron_tick())),
      snapshot: () => JSON.parse(rp(exports.tron_snapshot())),
    };
  }
  const { instance: hostInst } = await WebAssembly.instantiate(bytes, {});
  const { instance: guestInst } = await WebAssembly.instantiate(bytes, {});
  const H = bindInstance(hostInst.exports);
  const G = bindInstance(guestInst.exports);
  // A tick-stamped input script: each entry is the input(s) to apply BEFORE the
  // tick at that index (i.e. the input is stamped with the tick it applies at —
  // exactly how the relay carries the tick so both sides queue it for the same
  // tick rather than apply-on-arrival).
  const lockstepScript = [
    { one: DOWN }, {}, { one: RIGHT, two: UP }, { two: LEFT },
    { one: UP }, {}, { one: LEFT, two: DOWN }, {},
    { one: DOWN, two: RIGHT }, {}, { two: UP }, { one: RIGHT },
  ];
  const LOCK_SEED = 0xBADC0DEn;
  H.exports.tron_new_sized(LOCK_SEED, 30, 30);
  G.exports.tron_new_sized(LOCK_SEED, 30, 30);
  let lockstepHeld = true;
  let lockstepTicks = 0;
  for (let i = 0; i < lockstepScript.length; i++) {
    const inp = lockstepScript[i];
    // Apply the SAME tick-stamped inputs on BOTH instances before advancing.
    if (inp.one !== undefined) {
      H.exports.tron_set_dir(ONE, inp.one);
      G.exports.tron_set_dir(ONE, inp.one);
    }
    if (inp.two !== undefined) {
      H.exports.tron_set_dir(TWO, inp.two);
      G.exports.tron_set_dir(TWO, inp.two);
    }
    const ho = H.tick();
    const go = G.tick();
    // Per-tick outcome JSON must match across the two browsers.
    if (JSON.stringify(ho) !== JSON.stringify(go)) { lockstepHeld = false; break; }
    // And so must the full snapshot (board + both cycles + status).
    if (JSON.stringify(H.snapshot()) !== JSON.stringify(G.snapshot())) { lockstepHeld = false; break; }
    lockstepTicks++;
    if (ho.status !== 'playing') break;
  }
  assert(lockstepTicks > 0, 'cross-instance lockstep advanced at least one tick');
  assert(
    lockstepHeld,
    'two INDEPENDENT instances (host+guest) stay bit-identical under the same seed + same tick-stamped inputs',
  );

  // ── 9. NO LEAK: repeated packed reads that ALWAYS dealloc must not grow the
  //       module's linear memory without bound (the realtime tick loop calls
  //       tron_tick + tron_snapshot every frame for a whole match). Reusing the
  //       host instance, hammer the packed ops and assert memory is stable. ───
  H.exports.tron_new_sized(7n, 40, 40);
  const pagesBefore = H.exports.memory.buffer.byteLength;
  for (let i = 0; i < 2000; i++) {
    H.tick(); // reads + deallocs the packed Outcome
    H.snapshot(); // reads + deallocs the packed snapshot
  }
  const pagesAfter = H.exports.memory.buffer.byteLength;
  assert(
    pagesAfter <= pagesBefore + 64 * 1024, // at most one extra 64KiB page of slack
    `linear memory is stable across 2000 tick+snapshot reads (before=${pagesBefore} after=${pagesAfter})`,
  );

  // ── report ──────────────────────────────────────────────────────────────────
  console.log(`\n${passed} assertions passed, ${failures.length} failed.`);
  if (failures.length > 0) {
    console.error('FAILURES:\n  - ' + failures.join('\n  - '));
    process.exit(1);
  }
  console.log('OK — browser-loadable Tron WASM is simulation-correct and deterministic.');
}

main().catch((e) => {
  console.error('test crashed:', e);
  process.exit(1);
});
