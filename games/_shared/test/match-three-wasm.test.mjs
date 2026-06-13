// Functional test for the browser-loadable Match-3 puzzle engine WASM module.
//
// Loads games/match-three/wasm/snapdragon_match_three_wasm.wasm with
// WebAssembly.instantiate and EMPTY imports (proving it runs in a bare browser via
// instantiateStreaming with no host glue), then drives a DETERMINISTIC, SEEDED
// scenario through the C-ABI / JSON-over-linear-memory shim. If this passes, the
// same .wasm the browser bundle ships is rules-correct.
//
// Match-3 is a SINGLE-PLAYER puzzle: there is NO server, NO relay, NO multiplayer.
// The board is fully determined by its (width, height, colors, seed), so every
// assertion below is reproducible move-for-move from the seed.
//
// Run:  node games/_shared/test/match-three-wasm.test.mjs

import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const WASM_PATH = join(here, '..', '..', 'match-three', 'wasm', 'snapdragon_match_three_wasm.wasm');

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
  // Call an op that takes a string buffer and returns a packed JSON string.
  function callStr(fn, s) {
    const [ptr, len] = strIn(s);
    const out = readPacked(fn(ptr, len));
    ex.dealloc(ptr, len);
    return out;
  }
  // Call an op that takes a string buffer and returns a plain i32 status.
  function callI32(fn, s) {
    const [ptr, len] = strIn(s);
    const r = fn(ptr, len) | 0;
    ex.dealloc(ptr, len);
    return r;
  }

  console.log(`Loaded ${WASM_PATH} (${bytes.length} bytes), zero host imports.`);

  // ── 1. new(8,8,6,42): a fresh seeded board with NO initial matches ──────────
  assert(callI32(ex.match3_new, '8,8,6,42') === 1, 'match3_new("8,8,6,42") returns 1 (board created)');

  const snap = JSON.parse(readPacked(ex.match3_snapshot()));
  assert(snap.type === 'match3.snapshot', 'match3_snapshot() emits a match3.snapshot');
  assert(snap.width === 8 && snap.height === 8, `board is 8x8 (got ${snap.width}x${snap.height})`);
  assert(snap.colors === 6, `board has 6 colours (got ${snap.colors})`);
  assert(snap.score === 0, `opening score is 0 (got ${snap.score})`);
  assert(snap.status === 'playing', `opening status is "playing" (got "${snap.status}")`);
  assert(Array.isArray(snap.board) && snap.board.length === 8, `board has 8 rows (got ${snap.board && snap.board.length})`);
  assert(snap.board.every((r) => Array.isArray(r) && r.length === 8), 'every board row has 8 cells');
  // The opening board (seeded) has NO initial match — the spec guarantee. We
  // assert it indirectly: it has legal swaps to PLAY (the player must create the
  // first match), and the engine reports a positive legal-swap count.
  assert(typeof snap.legalSwaps === 'number' && snap.legalSwaps === 12, `seed 42 opening has 12 legal swaps (got ${snap.legalSwaps})`);

  const openStatus = JSON.parse(readPacked(ex.match3_status()));
  assert(openStatus.status === 'playing', 'match3_status() at the opening is "playing"');
  const openScore = JSON.parse(readPacked(ex.match3_score()));
  assert(openScore.score === 0, 'match3_score() at the opening is 0');

  // ── 2. legal swaps: the seeded enumeration is exact ─────────────────────────
  const legal = JSON.parse(readPacked(ex.match3_legal()));
  assert(Array.isArray(legal) && legal.length === 12, `match3_legal() lists 12 swaps for seed 42 (got ${legal.length})`);
  assert(legal.every((s) => Array.isArray(s) && s.length === 4), 'each legal swap is a 4-tuple [ax,ay,bx,by]');
  // The first legal swap is deterministic for this seed.
  assert(
    JSON.stringify(legal[0]) === JSON.stringify([2, 1, 2, 2]),
    `the first legal swap for seed 42 is [2,1,2,2] (got ${JSON.stringify(legal[0])})`,
  );

  // ── 3. illegal swaps are rejected with specific reasons, board UNTOUCHED ─────
  const notAdj = JSON.parse(callStr(ex.match3_swap, '0,0,5,5'));
  assert(
    notAdj.type === 'match3.error' && notAdj.error === 'not-adjacent',
    `a non-adjacent swap is rejected "not-adjacent" (got "${notAdj.error}")`,
  );
  const oob = JSON.parse(callStr(ex.match3_swap, '0,0,0,99'));
  assert(
    oob.type === 'match3.error' && oob.error === 'out-of-bounds',
    `an off-board swap is rejected "out-of-bounds" (got "${oob.error}")`,
  );
  const bad = JSON.parse(callStr(ex.match3_swap, 'x,y,z'));
  assert(
    bad.type === 'match3.error' && bad.error === 'bad-swap',
    `an unparseable swap is rejected "bad-swap" (got "${bad.error}")`,
  );
  // (0,0)<->(1,0) is adjacent but forms no run for seed 42 → no-match.
  const noMatch = JSON.parse(callStr(ex.match3_swap, '0,0,1,0'));
  assert(
    noMatch.type === 'match3.error' && noMatch.error === 'no-match',
    `an adjacent non-matching swap is rejected "no-match" (got "${noMatch.error}")`,
  );
  // Board is unchanged after all rejections.
  const stillOpen = JSON.parse(readPacked(ex.match3_snapshot()));
  assert(stillOpen.score === 0 && stillOpen.legalSwaps === 12, 'board is unchanged (score 0, 12 swaps) after the rejected swaps');

  // ── 4. apply the first legal swap: clears a triple, scores, stays playing ────
  const outcome = JSON.parse(callStr(ex.match3_swap, '2,1,2,2'));
  assert(outcome.type === 'match3.swap', 'a legal swap emits a match3.swap outcome');
  assert(outcome.cleared === 3, `the swap clears exactly 3 gems (got ${outcome.cleared})`);
  assert(outcome.cascades === 1, `the swap is a single cascade step (got ${outcome.cascades})`);
  assert(JSON.stringify(outcome.steps) === JSON.stringify([3]), `per-step clears are [3] (got ${JSON.stringify(outcome.steps)})`);
  // Score formula is 10 * cleared * step → 10 * 3 * 1 = 30.
  assert(outcome.score === 30, `the swap scores 30 (10*3*1) (got ${outcome.score})`);
  assert(outcome.status === 'playing', `the game is still playing after the swap (got "${outcome.status}")`);

  // The cumulative score advanced by exactly the outcome score.
  const afterScore = JSON.parse(readPacked(ex.match3_score()));
  assert(afterScore.score === 30, `cumulative score is 30 after the swap (got ${afterScore.score})`);
  const afterSnap = JSON.parse(readPacked(ex.match3_snapshot()));
  assert(afterSnap.score === 30, `snapshot reports score 30 (got ${afterSnap.score})`);
  assert(afterSnap.board.length === 8 && afterSnap.board.every((r) => r.length === 8), 'board stays full (8x8, no holes) after the cascade settled');

  // ── 5. determinism: same seed → identical board; different seed → differs ───
  callI32(ex.match3_new, '8,8,6,42');
  const a = JSON.parse(readPacked(ex.match3_snapshot()));
  callI32(ex.match3_new, '8,8,6,42');
  const b = JSON.parse(readPacked(ex.match3_snapshot()));
  assert(JSON.stringify(a.board) === JSON.stringify(b.board), 'same (w,h,colors,seed) reproduces the identical opening board');
  callI32(ex.match3_new, '8,8,6,43');
  const c = JSON.parse(readPacked(ex.match3_snapshot()));
  assert(JSON.stringify(a.board) !== JSON.stringify(c.board), 'a different seed produces a different opening board');

  // ── 6. save / restore round-trip preserves the RNG so cascades replay ───────
  callI32(ex.match3_new, '8,8,6,42');
  const saveStr = JSON.parse(readPacked(ex.match3_save())).save;
  assert(typeof saveStr === 'string' && saveStr.startsWith('m3|8|8|6|'), `match3_save() returns an m3|… save string (got "${saveStr.slice(0, 12)}…")`);
  // Play the first legal swap on the live game; capture the outcome + board.
  const live = JSON.parse(callStr(ex.match3_swap, '2,1,2,2'));
  const liveBoard = JSON.parse(readPacked(ex.match3_snapshot())).board;
  // Restore the PRE-swap save and replay the EXACT same swap — identical result.
  assert(callI32(ex.match3_restore, saveStr) === 1, 'match3_restore() of the saved string succeeds');
  const replay = JSON.parse(callStr(ex.match3_swap, '2,1,2,2'));
  const replayBoard = JSON.parse(readPacked(ex.match3_snapshot())).board;
  assert(JSON.stringify(live) === JSON.stringify(replay), 'replay from the restored save yields the identical cascade outcome');
  assert(JSON.stringify(liveBoard) === JSON.stringify(replayBoard), 'replay from the restored save yields the identical settled board (RNG position preserved)');
  // Malformed saves are rejected (and leave the game unchanged).
  assert(callI32(ex.match3_restore, 'not-a-save') === 0, 'match3_restore() of garbage returns 0');
  assert(callI32(ex.match3_restore, '') === 0, 'match3_restore() of an empty string returns 0');

  // ── 7. terminal: a LOCKED board (no legal swap) is "no_moves" / game-over ────
  // Restore a striped 6x3 board where every adjacent swap fails to line up 3 — the
  // engine's own no-moves fixture. 3 colours, row-major: "012012","120120","201201".
  const dead = `m3|6|3|3|12345|0|${'012012' + '120120' + '201201'}`;
  assert(callI32(ex.match3_restore, dead) === 1, 'match3_restore() loads the locked striped board');
  const deadStatus = JSON.parse(readPacked(ex.match3_status()));
  assert(deadStatus.status === 'no_moves', `the locked board reports status "no_moves" (got "${deadStatus.status}")`);
  const deadSnap = JSON.parse(readPacked(ex.match3_snapshot()));
  assert(deadSnap.status === 'no_moves' && deadSnap.legalSwaps === 0, 'snapshot of the locked board has status no_moves and 0 legal swaps');
  const deadLegal = JSON.parse(readPacked(ex.match3_legal()));
  assert(Array.isArray(deadLegal) && deadLegal.length === 0, `match3_legal() on a dead board is [] (got ${JSON.stringify(deadLegal)})`);
  // Even a well-formed adjacent swap on a dead board is rejected "game-over".
  const overSwap = JSON.parse(callStr(ex.match3_swap, '0,0,1,0'));
  assert(
    overSwap.type === 'match3.error' && overSwap.error === 'game-over',
    `a swap on the dead board is rejected "game-over" (got "${overSwap.error}")`,
  );

  // ── 8. degenerate configs are rejected by match3_new (no panic / trap) ──────
  assert(callI32(ex.match3_new, '8,8,2,0') === 0, 'match3_new() rejects < 3 colours (returns 0, no trap)');
  assert(callI32(ex.match3_new, '2,2,6,0') === 0, 'match3_new() rejects a board too small to hold a 3-run (2x2)');
  assert(callI32(ex.match3_new, 'garbage') === 0, 'match3_new() rejects a malformed config string');

  // ── report ──────────────────────────────────────────────────────────────────
  console.log(`\n${passed} assertions passed, ${failures.length} failed.`);
  if (failures.length > 0) {
    console.error('FAILURES:\n  - ' + failures.join('\n  - '));
    process.exit(1);
  }
  console.log('OK — browser-loadable Match-3 WASM is rules-correct.');
}

main().catch((e) => {
  console.error('test crashed:', e);
  process.exit(1);
});
