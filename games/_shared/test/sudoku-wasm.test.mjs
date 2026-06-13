// Functional test for the browser-loadable Sudoku engine WASM module.
//
// Loads games/sudoku/wasm/snapdragon_sudoku_wasm.wasm with WebAssembly.instantiate and
// EMPTY imports (proving it runs in a bare browser via instantiateStreaming with
// no host glue), then drives a FIXED, well-known puzzle through the C-ABI /
// JSON-over-linear-memory shim. Sudoku is single-player: there is no relay, no
// host/guest — the whole game runs in the browser against this wasm.
//
// It mirrors games/_shared/test/connect-four-wasm.test.mjs one-for-one (same
// marshalling helpers, same assertion harness), swapping column drops for
// (row,col,digit) cell placements and reusing the engine's OWN wire shapes
// (sudoku.snapshot / sudoku.error / sudoku.hint).
//
// Drives the FIXED engine teaching puzzle (the same KNOWN_PUZZLE / KNOWN_SOLUTION
// used in the engine's own cargo tests) so every assertion is deterministic:
//   • place a CORRECT digit in an empty cell                          → accepted
//   • a conflicting digit (row / column / box) is REJECTED            → sudoku.error
//   • a GIVEN clue cell cannot be overwritten                         → given-cell
//   • completing the grid correctly WINS (status flips to "solved")   → snapshot
//
// Run:  node games/_shared/test/sudoku-wasm.test.mjs

import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const WASM_PATH = join(
  here,
  '..',
  '..',
  'sudoku',
  'wasm',
  'snapdragon_sudoku_wasm.wasm',
);

// The engine's own teaching puzzle + its unique solution (81 chars, '.' empty).
// Identical to KNOWN_PUZZLE / KNOWN_SOLUTION in extensions/snapdragon-sudoku.
const KNOWN_PUZZLE =
  '53..7....' +
  '6..195...' +
  '.98....6.' +
  '8...6...3' +
  '4..8.3..1' +
  '7...2...6' +
  '.6....28.' +
  '...419..5' +
  '....8..79';
const KNOWN_SOLUTION =
  '534678912' +
  '672195348' +
  '198342567' +
  '859761423' +
  '426853791' +
  '713924856' +
  '961537284' +
  '287419635' +
  '345286179';

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
  // Load a puzzle string → returns the i32 status (1 ok / 0 rejected).
  function fromStr(s) {
    const [ptr, len] = strIn(s);
    const r = ex.sudoku_from_str(ptr, len);
    ex.dealloc(ptr, len);
    return r;
  }
  // (row,col,n) placement → parsed JSON (snapshot or sudoku.error).
  const place = (r, c, n) => JSON.parse(readPacked(ex.sudoku_place(r, c, n)));
  const clear = (r, c) => JSON.parse(readPacked(ex.sudoku_clear(r, c)));
  const state = () => JSON.parse(readPacked(ex.sudoku_state()));
  const status = () => JSON.parse(readPacked(ex.sudoku_status()));
  const hint = () => JSON.parse(readPacked(ex.sudoku_hint()));
  const isGiven = (r, c) => ex.sudoku_is_given(r, c) === 1;

  console.log(`Loaded ${WASM_PATH} (${bytes.length} bytes), zero host imports.`);

  // ── 1. load the fixed puzzle: snapshot shape + status + given mask ──────────
  assert(fromStr(KNOWN_PUZZLE) === 1, 'sudoku_from_str() loads the known puzzle (returns 1)');

  const s0 = state();
  assert(
    s0.type === 'sudoku.snapshot',
    `sudoku_state() emits the engine's sudoku.snapshot shape (got "${s0.type}")`,
  );
  assert(s0.status === 'playing', `fresh puzzle is "playing" (got "${s0.status}")`);
  assert(
    s0.board === KNOWN_PUZZLE.replace(/0/g, '.'),
    'sudoku_state() board mirrors the loaded puzzle (empties as ".")',
  );
  assert(
    s0.givens === s0.board,
    'an unplayed loaded puzzle has givens === board',
  );
  assert(
    s0.givenCount === [...KNOWN_PUZZLE].filter((c) => c !== '.' && c !== '0').length,
    `givenCount counts the clues (got ${s0.givenCount})`,
  );

  const st0 = status();
  assert(st0.status === 'playing', 'sudoku_status() at start is "playing"');

  // (0,0) is the given '5'; (0,2) is empty in the known puzzle.
  assert(isGiven(0, 0), 'sudoku_is_given(0,0) === true for the clue 5');
  assert(!isGiven(0, 2), 'sudoku_is_given(0,2) === false for an empty cell');
  assert(!isGiven(9, 0) && !isGiven(0, 9), 'sudoku_is_given out-of-range === false');

  // ── 2. place a CORRECT digit in an empty cell → accepted ────────────────────
  // The unique solution has (0,2) = 4 (a value with no row/col/box conflict).
  {
    const ok = place(0, 2, 4);
    assert(
      ok.type === 'sudoku.snapshot',
      `placing the correct digit 4 at (0,2) is accepted (got "${ok.type}")`,
    );
    assert(ok.board.charAt(2) === '4', 'the placed 4 appears in the snapshot board at index 2');
    assert(ok.status === 'playing', 'puzzle still "playing" after one placement');
    assert(
      ok.givens.charAt(2) === '.',
      'the player entry is NOT a given (givens string keeps index 2 empty)',
    );
    // Clear it again so the rest of the conflict tests start from the pristine cell.
    const cl = clear(0, 2);
    assert(cl.type === 'sudoku.snapshot' && cl.board.charAt(2) === '.', 'sudoku_clear(0,2) empties the cell');
  }

  // ── 3. a CONFLICTING digit (row / column / box) is REJECTED ─────────────────
  {
    // 5 is already at (0,0) → ROW conflict for (0,2).
    const rowConf = place(0, 2, 5);
    assert(
      rowConf.type === 'sudoku.error' && rowConf.error === 'conflict',
      `row-conflicting 5 at (0,2) rejected with "conflict" (got "${rowConf.error}")`,
    );
    // 6 is already at (1,2) → COLUMN conflict for (0,2).
    const colConf = place(0, 2, 6);
    assert(
      colConf.type === 'sudoku.error' && colConf.error === 'conflict',
      `column-conflicting 6 at (0,2) rejected with "conflict" (got "${colConf.error}")`,
    );
    // 9 is already at (2,1) in the top-left box → BOX conflict for (0,2).
    const boxConf = place(0, 2, 9);
    assert(
      boxConf.type === 'sudoku.error' && boxConf.error === 'conflict',
      `box-conflicting 9 at (0,2) rejected with "conflict" (got "${boxConf.error}")`,
    );
    // The rejected cell stayed empty (no mutation on a rejected placement).
    assert(state().board.charAt(2) === '.', 'a rejected placement leaves the cell untouched');
    // Bad-digit and out-of-range also reported distinctly.
    const badDigit = place(0, 2, 0);
    assert(
      badDigit.type === 'sudoku.error' && badDigit.error === 'bad-digit',
      `digit 0 rejected with "bad-digit" (got "${badDigit.error}")`,
    );
    const oob = place(9, 0, 1);
    assert(
      oob.type === 'sudoku.error' && oob.error === 'out-of-range',
      `out-of-range (9,0) rejected with "out-of-range" (got "${oob.error}")`,
    );
  }

  // ── 4. a GIVEN clue cell cannot be overwritten ──────────────────────────────
  {
    const overwrite = place(0, 0, 1); // (0,0) is the given 5
    assert(
      overwrite.type === 'sudoku.error' && overwrite.error === 'given-cell',
      `overwriting the given (0,0) rejected with "given-cell" (got "${overwrite.error}")`,
    );
    const clearGiven = clear(0, 0);
    assert(
      clearGiven.type === 'sudoku.error' && clearGiven.error === 'given-cell',
      `clearing the given (0,0) rejected with "given-cell" (got "${clearGiven.error}")`,
    );
    assert(state().board.charAt(0) === '5', 'the given (0,0) is still 5 after the rejected ops');
  }

  // ── 5. a hint points at an empty cell with the correct solution digit ───────
  {
    const h = hint();
    assert(h.type === 'sudoku.hint', `sudoku_hint() emits a sudoku.hint (got "${h.type}")`);
    // First empty cell in the known puzzle is (0,2); its solution digit is 4.
    assert(
      h.row === 0 && h.col === 2 && h.digit === 4,
      `hint is (0,2)=4 per the unique solution (got (${h.row},${h.col})=${h.digit})`,
    );
  }

  // ── 6. completing the grid correctly WINS (status flips to "solved") ────────
  {
    // Re-load pristine, then fill EVERY empty cell with its solution digit.
    assert(fromStr(KNOWN_PUZZLE) === 1, 'reload the known puzzle for the full play-through');
    let last = null;
    let placements = 0;
    for (let i = 0; i < 81; i++) {
      const r = Math.floor(i / 9);
      const c = i % 9;
      if (isGiven(r, c)) continue;
      const d = KNOWN_SOLUTION.charCodeAt(i) - 48; // '0' === 48
      last = place(r, c, d);
      placements++;
      assert(
        last.type === 'sudoku.snapshot',
        `solution digit ${d} at (${r},${c}) accepted (placement ${placements})`,
      );
    }
    assert(placements > 0, `played ${placements} cells to completion`);
    assert(
      last.status === 'solved',
      `the final correct placement flips status to "solved" (got "${last.status}")`,
    );
    assert(last.filled === 81, `a solved board has all 81 cells filled (got ${last.filled})`);
    assert(
      last.board === KNOWN_SOLUTION,
      'the completed board equals the unique solution',
    );
    // sudoku_status() agrees, and a hint is no longer available on a full board.
    assert(status().status === 'solved', 'sudoku_status() reports "solved" once complete');
    const noHint = hint();
    assert(
      noHint.type === 'sudoku.error' && noHint.error === 'no-hint',
      `no hint on a full board (got "${noHint.error}")`,
    );
  }

  // ── 7. a SEEDED generated puzzle is deterministic + playable to solved ──────
  {
    // sudoku_new(seed_lo, seed_hi, clues): the u64 seed split into two u32 halves.
    assert(ex.sudoku_new(31337, 0, 32) === 1, 'sudoku_new(seed=31337, clues=32) returns 1');
    const gen = state();
    assert(gen.type === 'sudoku.snapshot' && gen.status === 'playing', 'generated puzzle starts "playing"');
    assert(gen.givenCount >= 17 && gen.givenCount <= 81, `generated givenCount in [17,81] (got ${gen.givenCount})`);
    const givens1 = gen.givens;

    // Determinism: re-seeding the same seed reproduces the same givens.
    ex.sudoku_new(31337, 0, 32);
    assert(state().givens === givens1, 'same seed reproduces the same generated puzzle (deterministic)');

    // Drive a hint chain to completion: repeatedly take the hint and place it.
    let solved = false;
    for (let step = 0; step < 81 && !solved; step++) {
      const h = hint();
      if (h.type !== 'sudoku.hint') break;
      const out = place(h.row, h.col, h.digit);
      if (out.type !== 'sudoku.snapshot') break;
      if (out.status === 'solved') solved = true;
    }
    assert(solved, 'a generated puzzle is playable to "solved" via the hint chain');
    assert(status().status === 'solved', 'seeded puzzle status is "solved" after the hint chain');
  }

  // ── report ──────────────────────────────────────────────────────────────────
  console.log(`\n${passed} assertions passed, ${failures.length} failed.`);
  if (failures.length > 0) {
    console.error('FAILURES:\n  - ' + failures.join('\n  - '));
    process.exit(1);
  }
  console.log('OK — browser-loadable Sudoku WASM is rules-correct.');
}

main().catch((e) => {
  console.error('test crashed:', e);
  process.exit(1);
});
