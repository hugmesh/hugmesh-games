// Functional test for the browser-loadable Go (Weiqi/Baduk) engine WASM module.
//
// Loads games/go/wasm/hugmesh_go.wasm with WebAssembly.instantiate and EMPTY
// imports (proving it runs in a bare browser via instantiateStreaming with no
// host glue), then drives known positions through the C-ABI / JSON-over-linear-
// memory shim. If this passes, the same .wasm the browser bundle ships is
// rules-correct.
//
// It mirrors games/_shared/test/connect-four-wasm.test.mjs / gomoku-wasm.test.mjs
// one-for-one (same marshalling helpers, same assertion harness), swapping the
// drops/placements for Go's stone placement / pass / capture / suicide / ko,
// reusing the engine's OWN wire shapes (go.state / go.snapshot / go.error).
//
// Run:  node games/_shared/test/go-wasm.test.mjs

import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const WASM_PATH = join(here, '..', '..', 'go', 'wasm', 'hugmesh_go.wasm');

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
  // Call an op that takes a string and returns a packed JSON string.
  function callStr(fn, s) {
    const [ptr, len] = strIn(s);
    const out = readPacked(fn(ptr, len));
    ex.dealloc(ptr, len);
    return out;
  }
  // Play a coordinate (or "pass") → parsed JSON outcome.
  const play = (coord) => JSON.parse(callStr(ex.go_apply, String(coord)));
  const state = () => JSON.parse(readPacked(ex.go_state()));
  const status = () => JSON.parse(readPacked(ex.go_status()));
  const legal = () => JSON.parse(readPacked(ex.go_legal()));
  // Rebuild from a board string + size + side (side 0 = Black, 1 = White).
  function fromBoard(boardStr, size, side) {
    const [ptr, len] = strIn(boardStr);
    const r = ex.go_from_board(ptr, len, size, side);
    ex.dealloc(ptr, len);
    return r;
  }

  console.log(`Loaded ${WASM_PATH} (${bytes.length} bytes), zero host imports.`);

  // ── 1. start position: go_new + 81 legal points + ongoing/black ─────────────
  assert(ex.go_new() === 1, 'go_new() returns 1 (empty 9x9 start position created)');

  const startLegal = legal();
  assert(Array.isArray(startLegal), 'go_legal() is a JSON array');
  assert(
    startLegal.length === 81,
    `go_legal() at start has all 81 points (got ${startLegal.length})`,
  );

  const startState = state();
  assert(
    startState.type === 'go.snapshot',
    `go_state() emits the engine's go.snapshot shape (got "${startState.type}")`,
  );
  assert(startState.size === 9, `go_state() size is 9 (got ${startState.size})`);
  assert(startState.side === 'black', 'go_state() at start: black to move');
  assert(startState.status === 'ongoing', 'go_state() at start: ongoing');
  assert(
    typeof startState.board === 'string' && startState.board.length === 81 && /^\.+$/.test(startState.board),
    `go_state() at start: empty 81-char board (len ${startState.board && startState.board.length})`,
  );
  assert(status().status === 'ongoing', 'go_status() at start is "ongoing"');

  // ── 2. an occupied / out-of-range / unparseable move returns the error shape
  {
    ex.go_new();
    play('c3'); // Black c3
    const occ = play('c3'); // White can't play c3
    assert(
      occ.type === 'go.error' && occ.error === 'occupied',
      `playing an occupied point returns error "occupied" (got "${occ.error}")`,
    );
    const oob = play('z9'); // off the 9x9 board (col z out of range)
    assert(
      oob.type === 'go.error' && oob.error === 'bad-point',
      `out-of-range coordinate returns error "bad-point" (got "${oob.error}")`,
    );
  }

  // ── 3. CAPTURE: surround a single enemy stone and assert it is removed ───────
  // White stone b2 surrounded on 3 sides by Black (a2,b1,b3); Black plays the
  // 4th liberty c2 → b2 has zero liberties → removed, blackCaptures becomes 1.
  // Engine board layout for from_board: point 0 first, BOTTOM row up.
  {
    const size = 9;
    const cells = new Array(size * size).fill('.');
    const put = (col, row1, ch) => {
      // col letter→index, row 1-based; idx = (row1-1)*size + col
      cells[(row1 - 1) * size + col] = ch;
    };
    // a=0,b=1,c=2 ; rows 1..3
    put(1, 2, 'W'); // b2 White (target)
    put(0, 2, 'B'); // a2 Black
    put(1, 1, 'B'); // b1 Black
    put(1, 3, 'B'); // b3 Black
    assert(fromBoard(cells.join(''), size, 0) === 1, 'go_from_board() rebuilt the capture position (Black to move)');

    // Pre-capture: b2 White is on the board.
    const pre = state();
    const idxB2 = (2 - 1) * size + 1; // row2 col b
    assert(pre.board[idxB2] === 'W', 'before the capture, White stone sits at b2');

    const cap = play('c2'); // Black fills b2's last liberty
    assert(cap.type === 'go.state', 'capturing play returns a go.state payload');
    assert(cap.lastMove === 'c2', `capture lastMove is c2 (got "${cap.lastMove}")`);
    assert(cap.mover === 'black', `capture mover is black (got "${cap.mover}")`);
    assert(
      Array.isArray(cap.captured) && cap.captured.length === 1 && cap.captured[0] === 'b2',
      `single enemy stone b2 captured (got ${JSON.stringify(cap.captured)})`,
    );
    assert(cap.blackCaptures === 1, `blackCaptures incremented to 1 (got ${cap.blackCaptures})`);
    assert(cap.whiteCaptures === 0, `whiteCaptures still 0 (got ${cap.whiteCaptures})`);
    assert(cap.next === 'white', `turn handed to white (got "${cap.next}")`);

    // The captured point is now empty on the board snapshot.
    const post = state();
    assert(post.board[idxB2] === '.', 'after the capture, b2 is empty on the board');
  }

  // ── 4. SUICIDE is rejected ──────────────────────────────────────────────────
  // Black ring around b2 (a2,c2,b1,b3); White to move plays into the fully
  // enclosed single point b2 — zero liberties, no capture → suicide.
  {
    const size = 9;
    const cells = new Array(size * size).fill('.');
    const put = (col, row1, ch) => { cells[(row1 - 1) * size + col] = ch; };
    put(0, 2, 'B'); // a2
    put(2, 2, 'B'); // c2
    put(1, 1, 'B'); // b1
    put(1, 3, 'B'); // b3
    assert(fromBoard(cells.join(''), size, 1) === 1, 'go_from_board() rebuilt the suicide position (White to move)');
    const sui = play('b2');
    assert(
      sui.type === 'go.error' && sui.error === 'suicide',
      `suicide into a fully-enclosed point is rejected with "suicide" (got "${sui.error}")`,
    );
  }

  // ── 5. KO is rejected (immediate recapture forbidden) ───────────────────────
  // The textbook ko: c2 White in atari (only liberty d2), walled so Black's d2
  // capture leaves d2 in a one-stone/one-liberty shape. White may NOT recapture
  // at c2 immediately.
  {
    const size = 9;
    const cells = new Array(size * size).fill('.');
    const put = (col, row1, ch) => { cells[(row1 - 1) * size + col] = ch; };
    // a=0,b=1,c=2,d=3,e=4
    put(2, 2, 'W'); // c2 White (Black will capture)
    put(1, 2, 'B'); // b2 Black
    put(2, 1, 'B'); // c1 Black
    put(2, 3, 'B'); // c3 Black
    put(3, 1, 'W'); // d1 White
    put(3, 3, 'W'); // d3 White
    put(4, 2, 'W'); // e2 White
    assert(fromBoard(cells.join(''), size, 0) === 1, 'go_from_board() rebuilt the ko position (Black to move)');

    const koCap = play('d2'); // Black captures c2
    assert(
      koCap.type === 'go.state' && Array.isArray(koCap.captured) && koCap.captured.length === 1 && koCap.captured[0] === 'c2',
      `Black d2 captures the single White stone c2 (got ${JSON.stringify(koCap.captured)})`,
    );
    // White's immediate recapture at c2 is forbidden by the simple-ko rule.
    const koRecap = play('c2');
    assert(
      koRecap.type === 'go.error' && koRecap.error === 'ko',
      `immediate ko recapture at c2 is rejected with "ko" (got "${koRecap.error}")`,
    );
    // c2 is NOT in the legal list while the ko ban stands.
    assert(!legal().includes('c2'), 'the ko point c2 is excluded from go_legal() while the ban stands');
  }

  // ── 6. PASS keeps the game ongoing; TWO passes end it with a score ──────────
  // Build the split 5x5 board (Black file b, White file d) so the area score is
  // deterministic: Black 10, White 10 → a tie (jigo) at komi 0.
  {
    const size = 5;
    const cells = new Array(size * size).fill('.');
    const put = (col, row1, ch) => { cells[(row1 - 1) * size + col] = ch; };
    for (let r = 1; r <= 5; r++) {
      put(1, r, 'B'); // file b
      put(3, r, 'W'); // file d
    }
    assert(fromBoard(cells.join(''), size, 0) === 1, 'go_from_board() rebuilt the 5x5 split board (Black to move)');

    const pass1 = play('pass'); // Black passes
    assert(pass1.type === 'go.state', 'first pass returns a go.state payload');
    assert(pass1.lastMove === 'pass', `first pass lastMove is "pass" (got "${pass1.lastMove}")`);
    assert(pass1.status === 'ongoing', `after one pass the game is still ongoing (got "${pass1.status}")`);
    assert(pass1.next === 'white', `after Black's pass it is White's turn (got "${pass1.next}")`);

    // Drive the second pass through the dedicated go_pass export to exercise it.
    const pass2 = JSON.parse(readPacked(ex.go_pass())); // White passes → game ends
    assert(pass2.type === 'go.state', 'go_pass() second pass returns a go.state payload');
    assert(
      pass2.status === 'ended:draw:10:10',
      `two passes end the game with the area score (got "${pass2.status}")`,
    );

    // The terminal status is reflected by go_status() and freezes legal moves.
    assert(status().status === 'ended:draw:10:10', 'go_status() after two passes reports the ended:draw:10:10 score');
    assert(legal().length === 0, 'no legal points once the game has ended');
    const postEnd = play('a1');
    assert(
      postEnd.type === 'go.error' && postEnd.error === 'game-over',
      `a play after the game ends is rejected with "game-over" (got "${postEnd.error}")`,
    );
  }

  // ── 7. a komi tilts the otherwise-tied score to White ───────────────────────
  // (Sanity that the ended score wire carries the side/scores; komi default is
  // 0 so the split board is a draw — we assert the winner field via wire format.)
  {
    // Black-owns-more board: file c Black, file e White on a 5x5 → Black 15,
    // White 5, Black wins.
    const size = 5;
    const cells = new Array(size * size).fill('.');
    const put = (col, row1, ch) => { cells[(row1 - 1) * size + col] = ch; };
    for (let r = 1; r <= 5; r++) {
      put(2, r, 'B'); // file c
      put(4, r, 'W'); // file e
    }
    assert(fromBoard(cells.join(''), size, 0) === 1, 'go_from_board() rebuilt the Black-wins 5x5 board');
    play('pass'); // Black
    const end = JSON.parse(readPacked(ex.go_pass())); // White → end
    assert(
      end.status === 'ended:black:15:5',
      `Black owning more area wins (got "${end.status}")`,
    );
  }

  // ── report ──────────────────────────────────────────────────────────────────
  console.log(`\n${passed} assertions passed, ${failures.length} failed.`);
  if (failures.length > 0) {
    console.error('FAILURES:\n  - ' + failures.join('\n  - '));
    process.exit(1);
  }
  console.log('OK — browser-loadable Go WASM is rules-correct (capture / suicide / ko / pass / two-pass score).');
}

main().catch((e) => {
  console.error('test crashed:', e);
  process.exit(1);
});
