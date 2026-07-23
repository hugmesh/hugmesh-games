// Functional test for the browser-loadable Connect Four engine WASM module.
//
// Loads games/connect-four/wasm/hugmesh_connect_four.wasm with
// WebAssembly.instantiate and EMPTY imports (proving it runs in a bare browser
// via instantiateStreaming with no host glue), then drives known games move-by-
// move through the C-ABI / JSON-over-linear-memory shim. If this passes, the same
// .wasm the browser bundle ships is rules-correct.
//
// It mirrors games/_shared/test/chess-wasm.test.mjs one-for-one (same marshalling
// helpers, same assertion harness), swapping chess's UCI for column drops and
// reusing the engine's OWN wire shapes (connect_four.state / connect_four.error).
//
// Run:  node games/_shared/test/connect-four-wasm.test.mjs

import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const WASM_PATH = join(
  here,
  '..',
  '..',
  'connect-four',
  'wasm',
  'hugmesh_connect_four.wasm',
);

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
  // Call an op that takes a string and returns a packed JSON string.
  function callStr(fn, s) {
    const [ptr, len] = strIn(s);
    const out = readPacked(fn(ptr, len));
    ex.dealloc(ptr, len);
    return out;
  }
  // Drop into a column (decimal string) → parsed JSON outcome.
  const drop = (col) => JSON.parse(callStr(ex.c4_apply_drop, String(col)));

  console.log(`Loaded ${WASM_PATH} (${bytes.length} bytes), zero host imports.`);

  // ── 1. start position: c4_new + 7 legal columns + ongoing/red ──────────────
  assert(ex.c4_new() === 1, 'c4_new() returns 1 (empty start position created)');

  const startLegal = JSON.parse(readPacked(ex.c4_legal()));
  assert(Array.isArray(startLegal), 'c4_legal() is a JSON array');
  assert(
    startLegal.length === 7 &&
      startLegal.join(',') === '0,1,2,3,4,5,6',
    `c4_legal() at start is [0..6] (got ${JSON.stringify(startLegal)})`,
  );

  const startState = JSON.parse(readPacked(ex.c4_state()));
  assert(
    startState.type === 'connect_four.state',
    `c4_state() emits the engine's connect_four.state shape (got "${startState.type}")`,
  );
  assert(startState.toMove === 'red', 'c4_state() at start: red to move');
  assert(startState.status === 'ongoing', 'c4_state() at start: ongoing');
  assert(
    startState.board === '......./......./......./......./......./.......',
    `c4_state() at start: empty board (got "${startState.board}")`,
  );

  const startStatus = JSON.parse(readPacked(ex.c4_status()));
  assert(startStatus.status === 'ongoing', 'c4_status() at start is "ongoing"');

  // ── 2. unparseable column returns the error shape ───────────────────────────
  const badCol = drop('xyz');
  assert(
    badCol.type === 'connect_four.error' && badCol.error === 'bad-column',
    `unparseable column returns error "bad-column" (got "${badCol.error}")`,
  );
  const oob = drop(9); // out of range 0..6
  assert(
    oob.type === 'connect_four.error' && oob.error === 'out-of-range',
    `out-of-range column returns error "out-of-range" (got "${oob.error}")`,
  );

  // ── 3a. drive a VERTICAL four-in-a-row WIN for Red, drop by drop ─────────────
  // R0 Y1 R0 Y1 R0 Y1 R0#  — Red stacks col 0, Yellow harmlessly stacks col 1.
  {
    ex.c4_new();
    const seq = [0, 1, 0, 1, 0, 1];
    for (let i = 0; i < seq.length; i++) {
      const o = drop(seq[i]);
      assert(
        o.type === 'connect_four.state' && o.status === 'ongoing',
        `vertical setup move ${i + 1} col ${seq[i]} accepted & ongoing`,
      );
    }
    const win = drop(0); // Red's 4th disc in col 0 → vertical win
    assert(
      win.type === 'connect_four.state',
      'vertical winning drop returns a connect_four.state payload',
    );
    assert(
      win.status === 'win:red',
      `vertical win status is "win:red" (got "${win.status}")`,
    );
    assert(
      win.lastPlayer === 'red' && win.lastCol === 0 && win.lastRow === 3,
      `vertical win landed Red at col0 row3 (got player=${win.lastPlayer} col=${win.lastCol} row=${win.lastRow})`,
    );
    assert(
      JSON.stringify(win.winningLine) === '[[0,0],[0,1],[0,2],[0,3]]',
      `vertical winningLine is the col-0 stack (got ${JSON.stringify(win.winningLine)})`,
    );
    // No more legal moves once won; status frozen terminal.
    const afterWin = JSON.parse(readPacked(ex.c4_legal()));
    assert(afterWin.length === 0, 'no legal columns once the game is won');
    const post = drop(2);
    assert(
      post.type === 'connect_four.error' && post.error === 'game-over',
      `post-win drop rejected with "game-over" (got "${post.error}")`,
    );
  }

  // ── 3b. drive a DIAGONAL "/" four-in-a-row WIN for Red ──────────────────────
  // Mirrors the engine's own diagonal_up_right_win test sequence:
  //   [0,1,1,2,3,2,2,3,6,3] then Red into col 3 completes (0,0)(1,1)(2,2)(3,3).
  {
    ex.c4_new();
    const setup = [0, 1, 1, 2, 3, 2, 2, 3, 6, 3];
    for (let i = 0; i < setup.length; i++) {
      const o = drop(setup[i]);
      assert(
        o.type === 'connect_four.state' && o.status === 'ongoing',
        `diagonal setup move ${i + 1} col ${setup[i]} accepted & ongoing`,
      );
    }
    const win = drop(3);
    assert(
      win.status === 'win:red' && win.lastPlayer === 'red',
      `diagonal "/" win is "win:red" by red (got status="${win.status}" player="${win.lastPlayer}")`,
    );
    assert(
      JSON.stringify(win.winningLine) === '[[0,0],[1,1],[2,2],[3,3]]',
      `diagonal winningLine is the up-right diagonal (got ${JSON.stringify(win.winningLine)})`,
    );
  }

  // ── 4. a full-column drop is rejected with "column-full" ────────────────────
  {
    ex.c4_new();
    // Stack col 0 to the brim (6 discs) with no win elsewhere — col 0 alternates
    // R/Y/R/Y/R/Y so no vertical four, and nothing else is touched.
    for (let r = 0; r < 6; r++) {
      const o = drop(0);
      assert(
        o.type === 'connect_four.state',
        `fill col 0 disc ${r + 1} accepted (row ${o.lastRow})`,
      );
    }
    const full = drop(0); // 7th into a 6-cell column
    assert(
      full.type === 'connect_four.error' && full.error === 'column-full',
      `drop into a full column rejected with "column-full" (got "${full.error}")`,
    );
    const legalNow = JSON.parse(readPacked(ex.c4_legal()));
    assert(
      !legalNow.includes(0) && legalNow.length === 6,
      `full column 0 drops out of c4_legal() (got ${JSON.stringify(legalNow)})`,
    );
  }

  // ── 5. a full board with NO four-in-a-row is a DRAW ─────────────────────────
  // The engine's own verified draw order (its full_board_draw test): fills all
  // 42 cells with the striped layout that has no run of four in any direction.
  {
    ex.c4_new();
    const DRAW_ORDER = [
      0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 2, 2, 4, 3, 3, 3, 3, 3,
      3, 4, 4, 4, 4, 4, 5, 5, 5, 5, 5, 5, 6, 6, 6, 6, 6, 6,
    ];
    let last = null;
    for (let i = 0; i < DRAW_ORDER.length; i++) {
      const o = drop(DRAW_ORDER[i]);
      assert(
        o.type === 'connect_four.state',
        `draw fill move ${i + 1} col ${DRAW_ORDER[i]} accepted`,
      );
      if (i + 1 < DRAW_ORDER.length) {
        assert(
          o.status === 'ongoing',
          `draw fill move ${i + 1} still ongoing (no premature win)`,
        );
      }
      last = o;
    }
    assert(
      last.status === 'draw',
      `a full board with no four-in-a-row is a draw (got "${last.status}")`,
    );
    const drawStatus = JSON.parse(readPacked(ex.c4_status()));
    assert(drawStatus.status === 'draw', 'c4_status() after a full board is "draw"');
    const drawLegal = JSON.parse(readPacked(ex.c4_legal()));
    assert(drawLegal.length === 0, 'no legal columns once the board is a draw');
  }

  // ── report ──────────────────────────────────────────────────────────────────
  console.log(`\n${passed} assertions passed, ${failures.length} failed.`);
  if (failures.length > 0) {
    console.error('FAILURES:\n  - ' + failures.join('\n  - '));
    process.exit(1);
  }
  console.log('OK — browser-loadable Connect Four WASM is rules-correct.');
}

main().catch((e) => {
  console.error('test crashed:', e);
  process.exit(1);
});
