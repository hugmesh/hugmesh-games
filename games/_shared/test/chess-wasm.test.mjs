// Functional test for the browser-loadable chess engine WASM module.
//
// Loads games/chess/wasm/snapdragon_chess.wasm with WebAssembly.instantiate and
// EMPTY imports (proving it runs in a bare browser via instantiateStreaming with
// no host glue), then drives a full known game move-by-move through the C-ABI /
// JSON-over-linear-memory shim. If this passes, the same .wasm the browser bundle
// ships is rules-correct.
//
// Run:  node games/_shared/test/chess-wasm.test.mjs

import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const WASM_PATH = join(here, '..', '..', 'chess', 'wasm', 'snapdragon_chess.wasm');

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
    // BigInt: high 32 bits = ptr, low 32 bits = len.
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

  console.log(`Loaded ${WASM_PATH} (${bytes.length} bytes), zero host imports.`);

  // ── 1. start position: chess_new + 20 legal moves ──────────────────────────
  assert(ex.chess_new() === 1, 'chess_new() returns 1 (start position created)');

  const startMoves = JSON.parse(readPacked(ex.chess_legal_moves()));
  assert(Array.isArray(startMoves), 'chess_legal_moves() is a JSON array');
  assert(
    startMoves.length === 20,
    `chess_legal_moves() at start has 20 moves (got ${startMoves.length})`,
  );
  assert(startMoves.includes('e2e4'), 'start moves include "e2e4"');

  const startFen = JSON.parse(readPacked(ex.chess_fen()));
  assert(
    startFen.fen === 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1',
    `chess_fen() at start is the standard start FEN (got "${startFen.fen}")`,
  );

  const startStatus = JSON.parse(readPacked(ex.chess_status()));
  assert(startStatus.status === 'ongoing', 'chess_status() at start is "ongoing"');

  // ── 2. illegal move returns the error shape ─────────────────────────────────
  const illegal = JSON.parse(callStr(ex.chess_apply_uci, 'e7e5')); // black piece on white's turn
  assert(illegal.type === 'chess.error', 'illegal move returns {"type":"chess.error"}');
  assert(
    typeof illegal.error === 'string' && illegal.error.length > 0,
    `illegal move carries an error reason (got "${illegal.error}")`,
  );

  const badUci = JSON.parse(callStr(ex.chess_apply_uci, 'zz')); // unparseable
  assert(
    badUci.type === 'chess.error' && badUci.error === 'bad-uci',
    `unparseable UCI returns error "bad-uci" (got "${badUci.error}")`,
  );

  // ── 3. drive the full Scholar's Mate, move by move ──────────────────────────
  // 1.e4 e5 2.Qh5 Nc6 3.Bc4 Nf6?? 4.Qxf7#
  const scholars = ['e2e4', 'e7e5', 'd1h5', 'b8c6', 'f1c4', 'g8f6', 'h5f7'];
  let lastOutcome = null;
  for (let i = 0; i < scholars.length; i++) {
    const o = JSON.parse(callStr(ex.chess_apply_uci, scholars[i]));
    assert(
      o.type === 'chess.state',
      `move ${i + 1} "${scholars[i]}" accepted (chess.state payload)`,
    );
    assert(
      o.lastMove === scholars[i],
      `move ${i + 1} echoes lastMove "${scholars[i]}" (got "${o.lastMove}")`,
    );
    lastOutcome = o;
  }

  // The final move payload (from the engine's own MoveOutcome::to_json) must
  // report mate, and its embedded status wire must encode checkmate for white.
  assert(lastOutcome.mate === true, 'final move payload has "mate":true');
  assert(lastOutcome.san === 'Qxf7#', `final SAN is "Qxf7#" (got "${lastOutcome.san}")`);
  assert(
    lastOutcome.status === 'checkmate:white',
    `final outcome status is "checkmate:white" (got "${lastOutcome.status}")`,
  );

  // ── 4. chess_status after the game encodes checkmate for white ──────────────
  const finalStatus = JSON.parse(readPacked(ex.chess_status()));
  assert(
    finalStatus.status === 'checkmate:white',
    `chess_status() after mate is "checkmate:white" (got "${finalStatus.status}")`,
  );

  // ── 5. moves are rejected once the game is over ─────────────────────────────
  const afterMate = JSON.parse(callStr(ex.chess_apply_uci, 'a7a6'));
  assert(
    afterMate.type === 'chess.error' && afterMate.error === 'game-over',
    `post-mate move rejected with "game-over" (got "${afterMate.error}")`,
  );

  // ── 6. chess_from_fen + chess_resign round-trip ─────────────────────────────
  const ok = ex.chess_from_fen(...strIn('rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1'));
  assert(ok === 1, 'chess_from_fen() with a valid FEN returns 1');
  const badFen = ex.chess_from_fen(...strIn('not a fen'));
  assert(badFen === 0, 'chess_from_fen() with a bad FEN returns 0');

  const resigned = JSON.parse(callStr(ex.chess_resign, 'white'));
  assert(
    resigned.status === 'resigned:black',
    `chess_resign("white") yields "resigned:black" (winner is opponent; got "${resigned.status}")`,
  );

  // ── report ──────────────────────────────────────────────────────────────────
  console.log(`\n${passed} assertions passed, ${failures.length} failed.`);
  if (failures.length > 0) {
    console.error('FAILURES:\n  - ' + failures.join('\n  - '));
    process.exit(1);
  }
  console.log('OK — browser-loadable chess WASM is rules-correct.');
}

main().catch((e) => {
  console.error('test crashed:', e);
  process.exit(1);
});
