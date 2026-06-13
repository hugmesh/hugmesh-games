// Functional test for the browser-loadable Tetris engine WASM module.
//
// Loads games/tetris/wasm/snapdragon_tetris_wasm.wasm with WebAssembly.instantiate
// and EMPTY imports (proving it runs in a bare browser via instantiateStreaming with
// no host glue), then drives a DETERMINISTIC, seed-based game through the C-ABI /
// JSON-over-linear-memory shim. If this passes, the same .wasm the browser bundle
// ships is rules-correct.
//
// SINGLE-PLAYER PUZZLE: there is NO relay, NO opponent, NO server. Everything is
// driven from a fixed seed so the entire game is reproducible. Every assertion
// below is an EXACT value observed from the real engine for the named seed — the
// piece bag, the hard-drop lock, the deterministic game-over, the status
// transition, and the no-op (blocked) move are all pinned.
//
// Run:  node games/_shared/test/tetris-wasm.test.mjs

import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const WASM_PATH = join(here, '..', '..', 'tetris', 'wasm', 'snapdragon_tetris_wasm.wasm');

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

  // ── linear-memory marshalling helper (the documented ABI) ───────────────────
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
  // Parse a packed JSON return (the snapshot / status payloads).
  const snap = (packed) => JSON.parse(readPacked(packed));
  // Count locked (non-empty) board cells in a flat board string.
  const countFilled = (board) => {
    let n = 0;
    for (const c of board) if (c !== '.') n++;
    return n;
  };

  console.log(`Loaded ${WASM_PATH} (${bytes.length} bytes), zero host imports.`);

  // ── 0. allocator contract present (the shared WasmHost requires it) ──────────
  assert(typeof ex.alloc === 'function' && typeof ex.dealloc === 'function',
    'module exports the alloc/dealloc allocator contract');
  assert(typeof ex.memory !== 'undefined', 'module exports its linear memory');

  // ── 1. tetris_new(seed) builds a fresh, deterministic opening ────────────────
  // Seed 1 is fully pinned: a freshly-dealt T spawns at (rot 0, x 3, y 0), the
  // next piece is an S, nothing is held, the well is empty, and the game is
  // playing with a zero score/lines/level.
  assert(Number(ex.tetris_new(1n)) === 1, 'tetris_new(1) returns 1 (game created)');

  const open = snap(ex.tetris_snapshot());
  assert(open.type === 'tetris.snapshot', 'tetris_snapshot() emits a tetris.snapshot');
  assert(open.width === 10 && open.height === 20, `well is 10x20 (got ${open.width}x${open.height})`);
  assert(open.board.length === 200, `flat board is WIDTH*HEIGHT = 200 chars (got ${open.board.length})`);
  assert(/^\.{200}$/.test(open.board), 'opening well is entirely empty');
  assert(open.active.piece === 't', `seed 1 spawns a T first (got "${open.active.piece}")`);
  assert(open.active.rot === 0 && open.active.x === 3 && open.active.y === 0,
    `seed 1 T spawns at rot 0, x 3, y 0 (got rot ${open.active.rot}, x ${open.active.x}, y ${open.active.y})`);
  assert(open.next === 's', `seed 1 next piece is S (got "${open.next}")`);
  assert(open.held === null && open.canHold === true, 'opening has nothing held and hold is available');
  assert(open.score === 0 && open.lines === 0 && open.level === 0, 'opening score/lines/level are all 0');
  assert(open.status === 'playing', `opening status is "playing" (got "${open.status}")`);

  const openStatus = snap(ex.tetris_status());
  assert(openStatus.status === 'playing', 'tetris_status() at the opening is "playing"');

  // ── 2. deterministic 7-bag piece sequence for the seed ───────────────────────
  // Hard-dropping advances through the bag; seed 1 deals exactly t,s,j,z,l,i,o,…
  // The first 7 are a permutation of all seven kinds (the 7-bag invariant).
  ex.tetris_new(1n);
  const seq = [snap(ex.tetris_snapshot()).active.piece];
  for (let i = 0; i < 6; i++) seq.push(snap(ex.tetris_hard_drop()).active.piece);
  assert(seq.join(',') === 't,s,j,z,l,i,o',
    `seed 1 first-bag sequence is exactly t,s,j,z,l,i,o (got ${seq.join(',')})`);
  const bag = [...seq].sort().join(',');
  assert(bag === 'i,j,l,o,s,t,z',
    `the first 7 pieces are a permutation of all seven kinds (got ${bag})`);

  // ── 3. a blocked horizontal nudge is a no-op (snapshot unchanged) ────────────
  // Seed 1's T at x 3 moves left to x 2. Shoving it past the wall eventually
  // blocks; the snapshot then reflects the unchanged (rejected) position — there
  // is no separate "rejected" wire for a no-op nudge.
  ex.tetris_new(1n);
  assert(snap(ex.tetris_left()).active.x === 2, 'a legal left nudge moves the T from x 3 to x 2');
  // Shove it flush against the left wall, then assert one more left is a no-op.
  let x = 2;
  for (let i = 0; i < 12; i++) x = snap(ex.tetris_left()).active.x;
  const wallX = x;
  const stillWall = snap(ex.tetris_left()).active.x;
  assert(stillWall === wallX,
    `a blocked left nudge at the wall is a no-op (x stayed ${wallX})`);
  assert(wallX === 0,
    `the T's bounding box rests flush against the left wall (x ${wallX})`);

  // ── 4. soft drop falls one row and scores 1; does NOT lock ───────────────────
  ex.tetris_new(1n);
  const sd0 = snap(ex.tetris_snapshot());
  const sd1 = snap(ex.tetris_soft_drop());
  assert(sd1.active.y === sd0.active.y + 1, `soft drop falls one row (y ${sd0.active.y} -> ${sd1.active.y})`);
  assert(sd1.score === sd0.score + 1, `soft drop awards 1 point per cell (score ${sd0.score} -> ${sd1.score})`);
  assert(countFilled(sd1.board) === 0, 'soft drop never locks (the well is still empty)');

  // ── 5. a gravity tick falls one row when unobstructed (does not lock high) ────
  ex.tetris_new(11n);
  const tk0 = snap(ex.tetris_snapshot());
  const tk1 = snap(ex.tetris_tick());
  assert(tk1.active.y === tk0.active.y + 1, `tetris_tick falls one row when high (y ${tk0.active.y} -> ${tk1.active.y})`);
  assert(tk1.status === 'playing' && countFilled(tk1.board) === 0,
    'a single tick on a high piece neither locks nor ends the game');

  // ── 6. hard drop locks exactly four cells; tetris_filled mirrors the board ────
  // Seed 7's first piece hard-drops to the floor and locks its four cells.
  ex.tetris_new(7n);
  const hd = snap(ex.tetris_hard_drop());
  assert(countFilled(hd.board) === 4, `one hard drop locks exactly 4 cells (got ${countFilled(hd.board)})`);
  assert(hd.score > 0, `hard drop scores the drop distance (score ${hd.score})`);
  assert(hd.status === 'playing', 'the game continues after the first lock');
  // tetris_filled(col,row) must agree with the flat board string for every cell.
  let mismatches = 0;
  for (let row = 0; row < 20; row++) {
    for (let col = 0; col < 10; col++) {
      const boardFilled = hd.board[row * 10 + col] !== '.';
      const exFilled = Number(ex.tetris_filled(col, row)) === 1;
      if (boardFilled !== exFilled) mismatches++;
    }
  }
  assert(mismatches === 0, `tetris_filled(col,row) matches the board string for all 200 cells (${mismatches} mismatches)`);
  // Out-of-range cells are not filled.
  assert(Number(ex.tetris_filled(-1, 0)) === 0 && Number(ex.tetris_filled(10, 0)) === 0 &&
         Number(ex.tetris_filled(0, 20)) === 0,
    'tetris_filled returns 0 for out-of-range cells');

  // ── 7. hold stashes the active piece, brings in the next, locks hold ─────────
  // Seed 2: holding the first piece stashes it and spawns a fresh piece; a second
  // hold for that piece is rejected (one hold per piece) — a no-op snapshot.
  ex.tetris_new(2n);
  const ho0 = snap(ex.tetris_snapshot());
  const ho1 = snap(ex.tetris_hold());
  assert(ho1.held === ho0.active.piece,
    `first hold stashes the active ${ho0.active.piece} (held now "${ho1.held}")`);
  assert(ho1.active.piece !== null && ho1.canHold === false,
    `hold spawns a new active piece "${ho1.active.piece}" and disables a second hold`);
  const ho2 = snap(ex.tetris_hold());
  assert(ho2.held === ho1.held && ho2.active.piece === ho1.active.piece,
    'a second hold for the same piece is a no-op (held + active unchanged)');

  // ── 8. deterministic game-over: a fixed seed always terminates identically ───
  // Seed 123, hard-dropping every piece, ends after EXACTLY 11 drops with the
  // terminal status "gameover:188:0". Reproduced exactly on a fresh game.
  function playToOver(seed) {
    ex.tetris_new(seed);
    let drops = 0;
    let st;
    do {
      st = snap(ex.tetris_hard_drop());
      drops++;
      if (drops > 2000) break;
    } while (st.status === 'playing');
    return { drops, st };
  }
  const run1 = playToOver(123n);
  assert(run1.st.status === 'gameover:188:0',
    `seed 123 terminates with status "gameover:188:0" (got "${run1.st.status}")`);
  assert(run1.drops === 11, `seed 123 ends after exactly 11 hard drops (got ${run1.drops})`);
  assert(run1.st.score === 188 && run1.st.lines === 0,
    `seed 123 final score/lines are 188/0 (got ${run1.st.score}/${run1.st.lines})`);

  // tetris_status reflects the same terminal wire string.
  const overStatus = snap(ex.tetris_status());
  assert(overStatus.status === 'gameover:188:0',
    `tetris_status() after game-over is "gameover:188:0" (got "${overStatus.status}")`);

  // Replay from scratch reproduces the identical terminal state (determinism).
  const run2 = playToOver(123n);
  assert(run2.drops === run1.drops && run2.st.status === run1.st.status && run2.st.score === run1.st.score,
    'the same seed reproduces the identical game-over state (deterministic)');

  // ── 9. actions after game-over are no-ops (the terminal state is frozen) ──────
  // (run2 left the global game terminal.) Every op returns the same terminal
  // snapshot; nothing mutates.
  const frozen = snap(ex.tetris_snapshot());
  const afterMove = snap(ex.tetris_left());
  const afterTick = snap(ex.tetris_tick());
  const afterDrop = snap(ex.tetris_hard_drop());
  assert(afterMove.status === frozen.status && afterMove.score === frozen.score &&
         afterTick.status === frozen.status && afterDrop.score === frozen.score,
    'moves / ticks / drops after game-over are rejected and leave the terminal state unchanged');

  // ── report ──────────────────────────────────────────────────────────────────
  console.log(`\n${passed} assertions passed, ${failures.length} failed.`);
  if (failures.length > 0) {
    console.error('FAILURES:\n  - ' + failures.join('\n  - '));
    process.exit(1);
  }
  console.log('OK — browser-loadable Tetris WASM is rules-correct and deterministic.');
}

main().catch((e) => {
  console.error('test crashed:', e);
  process.exit(1);
});
