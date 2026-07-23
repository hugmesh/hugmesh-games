// Functional test for the browser-loadable Reversi/Othello engine WASM module.
//
// Loads games/reversi/wasm/hugmesh_reversi_wasm.wasm with WebAssembly.instantiate
// and EMPTY imports (proving it runs in a bare browser via instantiateStreaming with
// no host glue), then drives the opening + a short game through the C-ABI /
// JSON-over-linear-memory shim. If this passes, the same .wasm the browser bundle
// ships is rules-correct.
//
// Run:  node games/_shared/test/reversi-wasm.test.mjs

import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const WASM_PATH = join(here, '..', '..', 'reversi', 'wasm', 'hugmesh_reversi_wasm.wasm');

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

  console.log(`Loaded ${WASM_PATH} (${bytes.length} bytes), zero host imports.`);

  // ── 1. opening: reversi_new + the four standard Black openings ──────────────
  assert(ex.reversi_new() === 1, 'reversi_new() returns 1 (opening created)');

  const openMoves = JSON.parse(readPacked(ex.reversi_legal()));
  assert(Array.isArray(openMoves), 'reversi_legal() is a JSON array');
  assert(
    openMoves.length === 4,
    `reversi_legal() at the opening has the 4 Black openings (got ${openMoves.length})`,
  );
  // The four standard Black openings around the centre.
  const sortedOpen = [...openMoves].sort();
  assert(
    JSON.stringify(sortedOpen) === JSON.stringify(['c4', 'd3', 'e6', 'f5']),
    `opening legal moves are exactly c4,d3,e6,f5 (got ${JSON.stringify(sortedOpen)})`,
  );

  // The opening snapshot: 2 black / 2 white discs, Black to move.
  const openState = JSON.parse(readPacked(ex.reversi_state()));
  assert(openState.type === 'reversi.snapshot', 'reversi_state() emits a reversi.snapshot');
  assert(openState.side === 'black', `opening side to move is black (got "${openState.side}")`);
  assert(openState.black === 2 && openState.white === 2, `opening is 2 black / 2 white (got ${openState.black}/${openState.white})`);
  assert(openState.board.length === 64, `opening board string is 64 cells (got ${openState.board.length})`);

  const openStatus = JSON.parse(readPacked(ex.reversi_status()));
  assert(openStatus.status === 'ongoing', 'reversi_status() at the opening is "ongoing"');

  // ── 2. illegal placements are rejected ──────────────────────────────────────
  // d4 is occupied by a seeded White disc.
  const occupied = JSON.parse(callStr(ex.reversi_apply, 'd4'));
  assert(
    occupied.type === 'reversi.error' && occupied.error === 'occupied',
    `placement on an occupied square rejected with "occupied" (got "${occupied.error}")`,
  );
  // a1 is empty but brackets nothing — not a Reversi move.
  const noFlip = JSON.parse(callStr(ex.reversi_apply, 'a1'));
  assert(
    noFlip.type === 'reversi.error' && noFlip.error === 'no-flips',
    `non-flipping placement rejected with "no-flips" (got "${noFlip.error}")`,
  );
  // z9 doesn't parse to a square.
  const badSq = JSON.parse(callStr(ex.reversi_apply, 'z9'));
  assert(
    badSq.type === 'reversi.error' && badSq.error === 'bad-square',
    `unparseable square rejected with "bad-square" (got "${badSq.error}")`,
  );
  // A pass is illegal while a move exists.
  const cannotPass = JSON.parse(readPacked(ex.reversi_pass()));
  assert(
    cannotPass.type === 'reversi.error' && cannotPass.error === 'cannot-pass',
    `pass with a legal move available rejected with "cannot-pass" (got "${cannotPass.error}")`,
  );
  // The board is unchanged after all the rejections (still the opening).
  const stillOpen = JSON.parse(readPacked(ex.reversi_state()));
  assert(
    stillOpen.black === 2 && stillOpen.white === 2 && stillOpen.side === 'black',
    'board is unchanged after the rejected placements/pass',
  );

  // ── 3. play Black d3: flips the single White disc on d4, updates score ──────
  const d3 = JSON.parse(callStr(ex.reversi_apply, 'd3'));
  assert(d3.type === 'reversi.state', 'reversi_apply("d3") accepted (reversi.state payload)');
  assert(d3.lastMove === 'd3', `d3 echoes lastMove "d3" (got "${d3.lastMove}")`);
  assert(d3.mover === 'black', `d3 mover is black (got "${d3.mover}")`);
  assert(
    JSON.stringify(d3.flipped) === JSON.stringify(['d4']),
    `d3 flips exactly d4 (got ${JSON.stringify(d3.flipped)})`,
  );
  // 2 seeded + placed + 1 flipped = 4 black; 2 seeded - 1 flipped = 1 white.
  assert(d3.black === 4, `after d3 black count is 4 (got ${d3.black})`);
  assert(d3.white === 1, `after d3 white count is 1 (got ${d3.white})`);
  assert(d3.next === 'white', `after d3 the turn flips to white (got "${d3.next}")`);
  assert(d3.status === 'ongoing', `after d3 the game is ongoing (got "${d3.status}")`);

  // The disc on d4 really flipped: it now reads as Black in the board string.
  // square index for d4 = file d (3) + rank 4 (rankIdx 3) → 3*8 + 3 = 27.
  const afterD3 = JSON.parse(readPacked(ex.reversi_state()));
  assert(afterD3.board[27] === 'B', `d4 (board[27]) is now Black after d3 (got "${afterD3.board[27]}")`);
  assert(afterD3.side === 'white', 'snapshot now reports White to move');

  // ── 4. play a few more plies; assert one disc placed + flips each ply ───────
  // White replies with its lowest-index legal move; assert discs flip.
  const whiteMoves = afterD3.legal;
  assert(Array.isArray(whiteMoves) && whiteMoves.length > 0, `White has legal replies (got ${JSON.stringify(whiteMoves)})`);
  const w1 = JSON.parse(callStr(ex.reversi_apply, whiteMoves[0]));
  assert(w1.type === 'reversi.state', `White reply ${whiteMoves[0]} accepted`);
  assert(w1.mover === 'white', `White reply mover is white (got "${w1.mover}")`);
  assert(w1.flipped.length >= 1, `White reply flips >= 1 disc (got ${w1.flipped.length})`);
  // Exactly one new disc placed: total disc count went up by exactly 1 (flips
  // only re-colour existing discs).
  assert(
    w1.black + w1.white === d3.black + d3.white + 1,
    `exactly one new disc placed on White's reply (was ${d3.black + d3.white}, now ${w1.black + w1.white})`,
  );

  // ── 5. PASS when the side to move has no legal placement ────────────────────
  // Construct a position (via reversi_from_board) where White is to move but has
  // NO legal placement while Black still does. Column a: a1=B, a2=W, rest empty.
  // White owns only a2, boxed by a1 Black below and empty above with no Black to
  // bracket — White is stuck; Black can play a3 (flips a2). Board string is
  // square 0 first (a1, b1, … h1, a2, …); index a1=0, a2=8.
  const cells = new Array(64).fill('.');
  cells[0] = 'B'; // a1
  cells[8] = 'W'; // a2
  const boardStr = cells.join('');
  const [bptr, blen] = strIn(boardStr);
  const [sptr, slen] = strIn('white');
  const loaded = ex.reversi_from_board(bptr, blen, sptr, slen);
  ex.dealloc(bptr, blen);
  ex.dealloc(sptr, slen);
  assert(loaded === 1, 'reversi_from_board() loaded the stuck-White position');

  const whiteStuck = JSON.parse(readPacked(ex.reversi_legal()));
  assert(
    Array.isArray(whiteStuck) && whiteStuck.length === 0,
    `White has NO legal placement in the constructed position (got ${JSON.stringify(whiteStuck)})`,
  );
  // White MUST pass; reversi_pass succeeds here.
  const passOutcome = JSON.parse(readPacked(ex.reversi_pass()));
  assert(passOutcome.type === 'reversi.state', 'reversi_pass() succeeds when White has no move');
  assert(passOutcome.lastMove === 'pass', `pass payload lastMove is "pass" (got "${passOutcome.lastMove}")`);
  assert(passOutcome.mover === 'white', `pass mover is white (got "${passOutcome.mover}")`);
  assert(
    JSON.stringify(passOutcome.flipped) === '[]',
    `pass flips nothing (got ${JSON.stringify(passOutcome.flipped)})`,
  );
  assert(passOutcome.next === 'black', `after White's pass the turn is black (got "${passOutcome.next}")`);

  // Black now plays a3 (index a3 = 16) and flips a2.
  const blackPlay = JSON.parse(callStr(ex.reversi_apply, 'a3'));
  assert(blackPlay.type === 'reversi.state', 'Black plays a3 after the pass');
  assert(
    JSON.stringify(blackPlay.flipped) === JSON.stringify(['a2']),
    `a3 flips a2 (got ${JSON.stringify(blackPlay.flipped)})`,
  );

  // ── 6. terminal position: winner by disc count ──────────────────────────────
  // Board entirely Black except two White discs; no empty squares → game over.
  // Black 62, White 2 → win:black.
  const full = new Array(64).fill('B');
  full[63] = 'W'; // h8
  full[62] = 'W'; // g8
  const fullStr = full.join('');
  const [fptr, flen] = strIn(fullStr);
  const [fsptr, fslen] = strIn('white');
  const loaded2 = ex.reversi_from_board(fptr, flen, fsptr, fslen);
  ex.dealloc(fptr, flen);
  ex.dealloc(fsptr, fslen);
  assert(loaded2 === 1, 'reversi_from_board() loaded the full terminal position');
  const termStatus = JSON.parse(readPacked(ex.reversi_status()));
  assert(
    termStatus.status === 'win:black',
    `terminal full board with 62 Black is "win:black" (got "${termStatus.status}")`,
  );
  // A placement on a finished game is rejected with game-over.
  const afterOver = JSON.parse(callStr(ex.reversi_apply, 'a1'));
  assert(
    afterOver.type === 'reversi.error' && afterOver.error === 'game-over',
    `placement on a finished game rejected with "game-over" (got "${afterOver.error}")`,
  );

  // ── report ──────────────────────────────────────────────────────────────────
  console.log(`\n${passed} assertions passed, ${failures.length} failed.`);
  if (failures.length > 0) {
    console.error('FAILURES:\n  - ' + failures.join('\n  - '));
    process.exit(1);
  }
  console.log('OK — browser-loadable Reversi WASM is rules-correct.');
}

main().catch((e) => {
  console.error('test crashed:', e);
  process.exit(1);
});
