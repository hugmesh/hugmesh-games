// Functional test for the browser-loadable Minesweeper engine WASM module.
//
// Loads games/minesweeper/wasm/hugmesh_minesweeper_wasm.wasm with
// WebAssembly.instantiate and EMPTY imports (proving it runs in a bare browser
// via instantiateStreaming with no host glue), then drives a SEEDED, fully
// DETERMINISTIC board move-by-move through the C-ABI / JSON-over-linear-memory
// shim. If this passes, the same .wasm the browser bundle ships is rules-correct.
//
// It mirrors games/_shared/test/connect-four-wasm.test.mjs one-for-one (same
// marshalling helpers, same assertion harness), swapping column drops for cell
// reveals/flags and reusing the engine's OWN wire shapes (minesweeper.snapshot /
// minesweeper.error).
//
// THE SEEDED BOARD (all assertions are pinned to it):
//   mine_new({ width:5, height:5, mines:3, seed:4 })
//   first reveal at (0,0) places mines DETERMINISTICALLY at (4,1), (0,2), (3,3)
//   and flood-fills a connected zero-region of 11 safe cells.
//
// Run:  node games/_shared/test/minesweeper-wasm.test.mjs

import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const WASM_PATH = join(
  here,
  '..',
  '..',
  'minesweeper',
  'wasm',
  'hugmesh_minesweeper_wasm.wasm',
);

// The pinned seeded board.
const W = 5;
const H = 5;
const MINES = 3;
const SEED = 4;
const SAFE_CELLS = W * H - MINES; // 22
// Deterministic mine positions for (W,H,MINES,SEED) after the first reveal at (0,0).
const MINE_XY = [
  [4, 1],
  [0, 2],
  [3, 3],
];
const MINE_SET = new Set(MINE_XY.map(([x, y]) => `${x},${y}`));

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

  // Op helpers reusing the engine's own wire shapes.
  const newGame = (cfg) => {
    const [ptr, len] = strIn(JSON.stringify(cfg));
    const r = ex.mine_new(ptr, len);
    ex.dealloc(ptr, len);
    return r;
  };
  const reveal = (x, y) => JSON.parse(callStr(ex.mine_reveal, `${x},${y}`));
  const flag = (x, y) => JSON.parse(callStr(ex.mine_flag, `${x},${y}`));
  const state = () => JSON.parse(readPacked(ex.mine_state()));
  const status = () => JSON.parse(readPacked(ex.mine_status()));
  // index into a flat row-major mask string at (x,y).
  const bit = (mask, x, y) => mask[y * W + x];

  console.log(`Loaded ${WASM_PATH} (${bytes.length} bytes), zero host imports.`);

  // ── 1. seed the board + fresh state ─────────────────────────────────────────
  assert(newGame({ width: W, height: H, mines: MINES, seed: SEED }) === 1,
    'mine_new(seeded 5x5/3) returns 1 (board created)');
  const fresh = state();
  assert(fresh.type === 'minesweeper.snapshot',
    `mine_state() emits the engine's minesweeper.snapshot shape (got "${fresh.type}")`);
  assert(fresh.width === W && fresh.height === H && fresh.mines === MINES && fresh.seed === SEED,
    `fresh state echoes the seeded config (${fresh.width}x${fresh.height}/${fresh.mines} seed ${fresh.seed})`);
  assert(fresh.status === 'playing', 'fresh state is "playing"');
  assert(fresh.started === false, 'fresh state: mines not placed until first reveal');
  assert(fresh.safeRevealed === 0 && fresh.safeCells === SAFE_CELLS,
    `fresh state: 0 of ${SAFE_CELLS} safe cells revealed`);
  assert(/^#{5}\/#{5}\/#{5}\/#{5}\/#{5}$/.test(fresh.board),
    `fresh board is all hidden (got "${fresh.board}")`);

  // ── 2. unparseable / out-of-bounds coordinates return the error shape ────────
  const badCoord = JSON.parse(callStr(ex.mine_reveal, 'xyz'));
  assert(badCoord.type === 'minesweeper.error' && badCoord.error === 'bad-coord',
    `unparseable coordinate returns error "bad-coord" (got "${badCoord.error}")`);
  const oob = JSON.parse(callStr(ex.mine_reveal, '9,9'));
  assert(oob.type === 'minesweeper.error' && oob.error === 'out-of-bounds',
    `out-of-bounds coordinate returns error "out-of-bounds" (got "${oob.error}")`);
  // those rejected reveals must NOT have started the game
  assert(state().started === false, 'a rejected reveal did not place mines (still not started)');

  // ── 3. reveal a safe zero-adjacency cell → FLOOD FILL opens a region ─────────
  const flooded = reveal(0, 0);
  assert(flooded.type === 'minesweeper.snapshot',
    'reveal(0,0) returns a minesweeper.snapshot payload');
  assert(flooded.started === true, 'first reveal placed the mines (started flips true)');
  assert(flooded.status === 'playing', 'flood reveal keeps the game playing (safe cells remain)');
  assert(flooded.safeRevealed === 11,
    `flood-fill on the zero-adjacency cell (0,0) opened a region of 11 cells (got ${flooded.safeRevealed})`);
  assert(flooded.safeRevealed > 1,
    'the flood opened MORE than one cell (a connected zero-region, not a single click)');
  assert(bit(flooded.revealedMask, 0, 0) === '1' && bit(flooded.revealedMask, 0, 1) === '1',
    'the flood region includes the clicked cell and its connected neighbours');
  // mines are deterministically placed where we expect
  for (const [mx, my] of MINE_XY) {
    assert(bit(flooded.mineMask, mx, my) === '1',
      `seeded mine present at (${mx},${my})`);
  }
  const totalMines = flooded.mineMask.split('').filter((c) => c === '1').length;
  assert(totalMines === MINES, `exactly ${MINES} mines placed (got ${totalMines})`);

  // ── 4. FLAG a known mine ─────────────────────────────────────────────────────
  const [fx, fy] = MINE_XY[0]; // (4,1)
  const flagged = flag(fx, fy);
  assert(flagged.type === 'minesweeper.snapshot', 'flag returns a snapshot');
  assert(bit(flagged.flaggedMask, fx, fy) === '1', `cell (${fx},${fy}) is now flagged`);
  assert(flagged.flags === 1, `flag count is 1 (got ${flagged.flags})`);
  assert(bit(flagged.revealedMask, fx, fy) === '0', 'the flagged mine is not revealed');

  // ── 5. a FLAGGED cell is NOT revealed by a click (the flag interlock) ────────
  const clickFlagged = reveal(fx, fy);
  assert(clickFlagged.type === 'minesweeper.snapshot',
    'revealing a flagged cell returns a snapshot (a benign no-op, not an error)');
  assert(clickFlagged.status === 'playing',
    'clicking a flagged mine did NOT detonate it — game still playing');
  assert(bit(clickFlagged.revealedMask, fx, fy) === '0',
    `the flagged mine (${fx},${fy}) stayed hidden after the click`);
  assert(bit(clickFlagged.flaggedMask, fx, fy) === '1',
    'the flag is still planted after the blocked click');

  // ── 6. reveal a MINE → LOSS ──────────────────────────────────────────────────
  const [bx, by] = MINE_XY[1]; // (0,2), unflagged
  const boom = reveal(bx, by);
  assert(boom.type === 'minesweeper.snapshot', 'revealing a mine returns a snapshot');
  assert(boom.status === 'lost', `revealing the unflagged mine (${bx},${by}) loses the game (got "${boom.status}")`);
  assert(status().status === 'lost', 'mine_status() reports "lost" after the detonation');
  // actions after game over are rejected
  const afterLoss = JSON.parse(callStr(ex.mine_reveal, '1,1'));
  assert(afterLoss.type === 'minesweeper.error' && afterLoss.error === 'game-over',
    `a reveal after the loss is rejected with "game-over" (got "${afterLoss.error}")`);

  // ── 7. WIN by revealing every safe cell ──────────────────────────────────────
  // Re-seed the SAME deterministic board; reveal each non-mine cell. The last
  // safe reveal flips the game to "won". (A flood may open several at once and
  // win early — we only act while still playing, mirroring the engine test.)
  newGame({ width: W, height: H, mines: MINES, seed: SEED });
  reveal(0, 0); // first click (safe, places the same mines)
  for (let y = 0; y < H && state().status === 'playing'; y++) {
    for (let x = 0; x < W; x++) {
      if (MINE_SET.has(`${x},${y}`)) continue; // never step on a mine
      if (state().status !== 'playing') break;
      const r = reveal(x, y);
      assert(r.type !== 'minesweeper.error' || r.error === 'game-over',
        `safe reveal (${x},${y}) is never a hard error (got "${r.error || r.status}")`);
      assert(r.status !== 'lost', `solving never stepped on a mine at (${x},${y})`);
    }
  }
  const finalState = state();
  assert(finalState.status === 'won',
    `revealing every safe cell WINS the game (got "${finalState.status}")`);
  assert(finalState.safeRevealed === SAFE_CELLS,
    `all ${SAFE_CELLS} safe cells revealed at the win (got ${finalState.safeRevealed})`);
  assert(status().status === 'won', 'mine_status() reports "won" after solving the board');
  // a win never exposes the minefield: the mine cells stay hidden in the board.
  for (const [mx, my] of MINE_XY) {
    assert(bit(finalState.revealedMask, mx, my) === '0',
      `mine (${mx},${my}) stays hidden on a win (board not exposed)`);
  }

  // ── 8. empty config falls back to the classic 9x9/10 beginner board ─────────
  ex.mine_new(0, 0); // null/empty buffer → all defaults
  const def = state();
  assert(def.width === 9 && def.height === 9 && def.mines === 10 && def.seed === 0,
    `empty config → default 9x9/10 seed 0 board (got ${def.width}x${def.height}/${def.mines} seed ${def.seed})`);

  // ── report ──────────────────────────────────────────────────────────────────
  console.log(`\n${passed} assertions passed, ${failures.length} failed.`);
  if (failures.length > 0) {
    console.error('FAILURES:\n  - ' + failures.join('\n  - '));
    process.exit(1);
  }
  console.log('OK — browser-loadable Minesweeper WASM is rules-correct.');
}

main().catch((e) => {
  console.error('test crashed:', e);
  process.exit(1);
});
