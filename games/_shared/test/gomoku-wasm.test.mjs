// Functional test for the browser-loadable gomoku engine WASM module.
//
// Loads games/gomoku/wasm/snapdragon_gomoku.wasm with WebAssembly.instantiate and
// EMPTY imports (proving it runs in a bare browser via instantiateStreaming with
// no host glue), then drives gomoku move-by-move through the C-ABI /
// JSON-over-linear-memory shim. If this passes, the same .wasm the browser bundle
// ships is rules-correct.
//
//   • place five in a row (horizontal) for a Black win;
//   • an occupied-cell placement is rejected;
//   • win detection fires at EXACTLY five (not at four).
//
// Run:  node games/_shared/test/gomoku-wasm.test.mjs

import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const WASM_PATH = join(here, '..', '..', 'gomoku', 'wasm', 'snapdragon_gomoku.wasm');

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

  console.log(`Loaded ${WASM_PATH} (${bytes.length} bytes), zero host imports.`);

  // ── 1. start position: gomoku_new + a full empty board of legal moves ───────
  assert(ex.gomoku_new() === 1, 'gomoku_new() returns 1 (empty board created)');

  const startMoves = JSON.parse(readPacked(ex.gomoku_legal()));
  assert(Array.isArray(startMoves), 'gomoku_legal() is a JSON array');
  assert(
    startMoves.length === 225,
    `gomoku_legal() at start has 225 moves (got ${startMoves.length})`,
  );
  assert(startMoves.includes('h8'), 'start moves include the centre point "h8"');
  assert(startMoves.includes('a1'), 'start moves include the corner "a1"');

  const startState = JSON.parse(readPacked(ex.gomoku_state()));
  assert(startState.type === 'gomoku.board', 'gomoku_state() type is "gomoku.board"');
  assert(startState.turn === 'black', 'gomoku_state() turn at start is "black"');
  assert(startState.status === 'ongoing', 'gomoku_state() status at start is "ongoing"');
  assert(
    typeof startState.board === 'string' && startState.board.length === 225,
    `gomoku_state() board is a 225-char string (got len ${startState.board && startState.board.length})`,
  );
  assert(
    /^\.+$/.test(startState.board),
    'gomoku_state() board at start is all empty (".")',
  );
  assert(startState.moves === 0, `gomoku_state() moves at start is 0 (got ${startState.moves})`);

  const startStatus = JSON.parse(readPacked(ex.gomoku_status()));
  assert(startStatus.status === 'ongoing', 'gomoku_status() at start is "ongoing"');

  // ── 2. drive Black to a horizontal five on row 8: a8 b8 c8 d8 e8 ────────────
  // White answers harmlessly on row 1 (a1 b1 c1 d1), so it is always Black's
  // turn for the next row-8 stone.
  // a8 = col a (0), row 8 (index row 7) → 7*15 + 0 = 105.
  const sequence = [
    ['a8', 'black', false],
    ['a1', 'white', false],
    ['b8', 'black', false],
    ['b1', 'white', false],
    ['c8', 'black', false],
    ['c1', 'white', false],
    ['d8', 'black', false], // exactly FOUR for Black after this — NOT a win
    ['d1', 'white', false],
  ];
  let fourCheck = null;
  for (const [coord, who, _win] of sequence) {
    const o = JSON.parse(callStr(ex.gomoku_apply, coord));
    assert(
      o.type === 'gomoku.state',
      `move "${coord}" accepted (gomoku.state payload)`,
    );
    assert(o.player === who, `move "${coord}" attributed to ${who} (got "${o.player}")`);
    assert(o.lastMove === coord, `move "${coord}" echoes lastMove (got "${o.lastMove}")`);
    fourCheck = o;
  }
  // After d8 (Black's 4th on row 8) + d1 reply, the game is still ongoing — FOUR
  // in a row is NOT a win.
  assert(
    fourCheck.status === 'ongoing' && fourCheck.win === false,
    `four in a row is NOT a win — status still ongoing (got status="${fourCheck.status}", win=${fourCheck.win})`,
  );

  // ── 3. occupied-cell placement is rejected ──────────────────────────────────
  // It is Black's turn now; b8 is already a Black stone.
  const occupied = JSON.parse(callStr(ex.gomoku_apply, 'b8'));
  assert(
    occupied.type === 'gomoku.error',
    'occupied-cell placement returns {"type":"gomoku.error"}',
  );
  assert(
    occupied.error === 'occupied',
    `occupied-cell error reason is "occupied" (got "${occupied.error}")`,
  );
  // The rejection did not consume the turn — still Black, still ongoing.
  const afterReject = JSON.parse(readPacked(ex.gomoku_state()));
  assert(afterReject.turn === 'black', 'turn unchanged after an occupied rejection (still black)');
  assert(afterReject.status === 'ongoing', 'status unchanged after an occupied rejection (ongoing)');

  // a bad / unparseable coordinate is rejected too.
  const badCoord = JSON.parse(callStr(ex.gomoku_apply, 'zz9'));
  assert(
    badCoord.type === 'gomoku.error' && badCoord.error === 'bad-coord',
    `unparseable coordinate returns error "bad-coord" (got "${badCoord.error}")`,
  );

  // ── 4. the FIFTH stone (e8) completes five-in-a-row → win detected ──────────
  const winning = JSON.parse(callStr(ex.gomoku_apply, 'e8'));
  assert(winning.type === 'gomoku.state', 'winning placement returns a gomoku.state payload');
  assert(winning.win === true, 'fifth-in-a-row placement reports "win":true');
  assert(
    winning.status === 'win:black',
    `winning status is "win:black" (got "${winning.status}")`,
  );
  assert(
    Array.isArray(winning.winLine) && winning.winLine.length === 5,
    `win line carries exactly five points (got ${JSON.stringify(winning.winLine)})`,
  );
  assert(
    JSON.stringify(winning.winLine) === JSON.stringify(['a8', 'b8', 'c8', 'd8', 'e8']),
    `win line is the five row-8 points a8..e8 (got ${JSON.stringify(winning.winLine)})`,
  );

  // ── 5. status after the win, and moves are rejected once the game is over ───
  const finalStatus = JSON.parse(readPacked(ex.gomoku_status()));
  assert(
    finalStatus.status === 'win:black',
    `gomoku_status() after the win is "win:black" (got "${finalStatus.status}")`,
  );
  const afterWin = JSON.parse(callStr(ex.gomoku_apply, 'h8'));
  assert(
    afterWin.type === 'gomoku.error' && afterWin.error === 'game-over',
    `post-win placement rejected with "game-over" (got "${afterWin.error}")`,
  );
  // legal moves are empty once terminal.
  const noMoves = JSON.parse(readPacked(ex.gomoku_legal()));
  assert(Array.isArray(noMoves) && noMoves.length === 0, 'gomoku_legal() is empty once the game is over');

  // ── 6. gomoku_from_moves replay round-trip (the FEN-equivalent restore) ─────
  const ok = ex.gomoku_from_moves(...strIn('h8 h9 i8 i9'));
  assert(ok === 1, 'gomoku_from_moves() with a legal sequence returns 1');
  const restored = JSON.parse(readPacked(ex.gomoku_state()));
  assert(restored.moves === 4, `restored state has 4 stones placed (got ${restored.moves})`);
  assert(restored.turn === 'black', 'restored state is Black to move after 4 stones');
  assert(restored.status === 'ongoing', 'restored state is ongoing');
  // a sequence with an illegal (repeated) move fails and leaves the game UNCHANGED.
  const badReplay = ex.gomoku_from_moves(...strIn('h8 h8'));
  assert(badReplay === 0, 'gomoku_from_moves() with a repeated point returns 0');
  const stillRestored = JSON.parse(readPacked(ex.gomoku_state()));
  assert(
    stillRestored.moves === 4,
    `failed replay left the prior game UNCHANGED (still 4 stones, got ${stillRestored.moves})`,
  );

  // ── report ──────────────────────────────────────────────────────────────────
  console.log(`\n${passed} assertions passed, ${failures.length} failed.`);
  if (failures.length > 0) {
    console.error('FAILURES:\n  - ' + failures.join('\n  - '));
    process.exit(1);
  }
  console.log('OK — browser-loadable gomoku WASM is rules-correct.');
}

main().catch((e) => {
  console.error('test crashed:', e);
  process.exit(1);
});
