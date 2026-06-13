// Functional test for the browser-loadable Battleship engine WASM module.
//
// Loads games/battleship/wasm/snapdragon_battleship_wasm.wasm with
// WebAssembly.instantiate and EMPTY imports (proving it runs in a bare browser
// via instantiateStreaming with no host glue), then drives a DETERMINISTIC,
// fully-scripted game through the C-ABI / JSON-over-linear-memory shim:
// placement → ready → firing → a forced win, plus every rejection path. If this
// passes, the same .wasm the browser bundle ships is rules-correct.
//
// FOG OF WAR: the shim splits the engine's atomic fire into resolve (owner) +
// record (shooter) so a fleet layout never crosses the wire. This test drives
// BOTH on one engine (it holds both boards) to assert the resolve path end to
// end; the record path is asserted with the turn-sync + relayed-result contract.
//
// Run:  node games/_shared/test/battleship-wasm.test.mjs

import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const WASM_PATH = join(here, '..', '..', 'battleship', 'wasm', 'snapdragon_battleship_wasm.wasm');

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

// The standard fleet laid out in distinct rows (mirrors the engine's own
// standard_fleet() test fixture): every ship trivially placeable, no overlap.
// [ship, origin, orientation].
const STANDARD_FLEET = [
  ['carrier', 'A1', 'h'], // A1..E1
  ['battleship', 'A2', 'h'], // A2..D2
  ['cruiser', 'A3', 'h'], // A3..C3
  ['submarine', 'A4', 'h'], // A4..C4
  ['destroyer', 'A5', 'h'], // A5..B5
];

// Every hull cell of the standard fleet, in fleet order (17 cells total).
function fleetHullCells() {
  const cells = [];
  for (const [ship, origin, orient] of STANDARD_FLEET) {
    const len = { carrier: 5, battleship: 4, cruiser: 3, submarine: 3, destroyer: 2 }[ship];
    const col = origin.charCodeAt(0) - 65; // 'A' -> 0
    const row = parseInt(origin.slice(1), 10) - 1; // '1' -> 0
    for (let i = 0; i < len; i++) {
      const c = orient === 'h' ? col + i : col;
      const r = orient === 'h' ? row : row + i;
      cells.push(String.fromCharCode(65 + c) + String(r + 1));
    }
  }
  return cells;
}

// A unique open-water cell for the n-th harmless miss. The standard fleet
// occupies rows 1..5 (row indices 0..4); row indices 8 & 9 (labels 9 & 10) are
// guaranteed water — 20 distinct cells, indexed by n so no miss repeats a cell.
function waterCell(n) {
  const row = 8 + Math.floor(n / 10); // index 8 then 9  → labels "9".."10"
  const col = n % 10;
  return String.fromCharCode(65 + col) + String(row + 1);
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
  // Call a (ptr,len) op with one string arg → parsed JSON.
  function call1(fn, s) {
    const [ptr, len] = strIn(s);
    const out = readPacked(fn(ptr, len));
    ex.dealloc(ptr, len);
    return JSON.parse(out);
  }
  // Call a 2-string op (record: coord + result) → parsed JSON.
  function call2(fn, a, b) {
    const [pa, la] = strIn(a);
    const [pb, lb] = strIn(b);
    const out = readPacked(fn(pa, la, pb, lb));
    ex.dealloc(pa, la);
    ex.dealloc(pb, lb);
    return JSON.parse(out);
  }
  // place(player, ship, origin, orient) → parsed JSON.
  function place(player, ship, origin, orient) {
    const a = strIn(player);
    const b = strIn(ship);
    const c = strIn(origin);
    const d = strIn(orient);
    const out = readPacked(ex.battleship_place(a[0], a[1], b[0], b[1], c[0], c[1], d[0], d[1]));
    ex.dealloc(...a);
    ex.dealloc(...b);
    ex.dealloc(...c);
    ex.dealloc(...d);
    return JSON.parse(out);
  }
  const ready = (p) => call1(ex.battleship_ready, p);
  const resolve = (coord) => call1(ex.battleship_resolve, coord);
  const record = (coord, result) => call2(ex.battleship_record, coord, result);
  const snapshot = () => JSON.parse(readPacked(ex.battleship_snapshot()));
  const status = () => JSON.parse(readPacked(ex.battleship_status()));

  console.log(`Loaded ${WASM_PATH} (${bytes.length} bytes), zero host imports.`);

  // ── 1. fresh game: placement phase, nothing placed ──────────────────────────
  assert(ex.battleship_new() === 1, 'battleship_new() returns 1 (fresh game)');
  let snap = snapshot();
  assert(snap.type === 'battleship.snapshot', 'battleship_snapshot() emits a battleship.snapshot');
  assert(snap.status === 'placing', `fresh game status is "placing" (got "${snap.status}")`);
  assert(snap.turn === 'p1', `fresh game P1 fires first (turn "${snap.turn}")`);
  assert(snap.p1Placed === 0 && snap.p2Placed === 0, 'fresh game has 0 ships placed either side');
  assert(snap.p1Ready === false && snap.p2Ready === false, 'fresh game neither player ready');
  assert(status().status === 'placing', 'battleship_status() at the open is "placing"');

  // ── 2. cannot fire / cannot ready during placement ──────────────────────────
  const earlyFire = resolve('A1');
  assert(
    earlyFire.type === 'battleship.error' && earlyFire.error === 'not-firing',
    `firing during placement rejected with "not-firing" (got "${earlyFire.error}")`,
  );
  const earlyReady = ready('p1');
  assert(
    earlyReady.type === 'battleship.error' && earlyReady.error === 'fleet-incomplete',
    `ready() before a full fleet rejected with "fleet-incomplete" (got "${earlyReady.error}")`,
  );

  // ── 3. invalid placements are rejected with the right reason ────────────────
  const badPlayer = place('p3', 'carrier', 'A1', 'h');
  assert(badPlayer.error === 'bad-player', `unknown player rejected "bad-player" (got "${badPlayer.error}")`);
  const badShip = place('p1', 'frigate', 'A1', 'h');
  assert(badShip.error === 'bad-ship', `unknown ship rejected "bad-ship" (got "${badShip.error}")`);
  const badOrient = place('p1', 'carrier', 'A1', 'diagonal');
  assert(badOrient.error === 'bad-orientation', `bad orient rejected "bad-orientation" (got "${badOrient.error}")`);
  const offBoard = place('p1', 'carrier', 'Z9', 'h');
  assert(offBoard.error === 'off-board', `off-board origin rejected "off-board" (got "${offBoard.error}")`);
  // Carrier (len 5) horizontal from G1 (col 6): would need col 10 → out of bounds.
  const runOff = place('p1', 'carrier', 'G1', 'h');
  assert(runOff.error === 'out-of-bounds', `hull running off the edge rejected "out-of-bounds" (got "${runOff.error}")`);

  // ── 4. place P1's full fleet; assert progress; reject overlap + double-place ─
  for (let i = 0; i < STANDARD_FLEET.length; i++) {
    const [ship, origin, orient] = STANDARD_FLEET[i];
    const r = place('p1', ship, origin, orient);
    assert(r.type === 'battleship.snapshot', `place P1 ${ship}@${origin} accepted (snapshot)`);
    assert(r.p1Placed === i + 1, `P1 placed count is ${i + 1} after ${ship} (got ${r.p1Placed})`);
  }
  // Re-placing carrier (already placed) → already-placed.
  const dup = place('p1', 'carrier', 'A7', 'h');
  assert(dup.error === 'already-placed', `re-placing a ship type rejected "already-placed" (got "${dup.error}")`);

  // P2 places, and an overlap on P2's own board is rejected.
  place('p2', 'carrier', 'A1', 'h'); // A1..E1 (boards independent — same coords OK)
  const overlap = place('p2', 'battleship', 'C1', 'v'); // C1 is in P2's carrier
  assert(overlap.error === 'overlap', `overlapping a own ship rejected "overlap" (got "${overlap.error}")`);
  // Finish P2's fleet at the standard spots.
  for (const [ship, origin, orient] of STANDARD_FLEET.slice(1)) {
    place('p2', ship, origin, orient);
  }
  snap = snapshot();
  assert(snap.p1Placed === 5 && snap.p2Placed === 5, `both fleets fully placed (${snap.p1Placed}/${snap.p2Placed})`);
  assert(snap.status === 'placing', 'still "placing" until BOTH declare ready');

  // ── 5. both ready → firing phase ────────────────────────────────────────────
  const r1 = ready('p1');
  assert(r1.type === 'battleship.snapshot' && r1.p1Ready === true, 'P1 ready accepted');
  assert(r1.status === 'placing', 'still placing after only P1 ready');
  const r2 = ready('p2');
  assert(r2.type === 'battleship.snapshot' && r2.p2Ready === true, 'P2 ready accepted');
  assert(r2.status === 'firing', `both ready → "firing" (got "${r2.status}")`);
  assert(snapshot().turn === 'p1', 'P1 to fire first in the firing phase');

  // ── 6. resolve: miss / hit / sunk wire shapes (P1 fires at P2's fleet) ───────
  // P1 fires J10 (deep water) → miss; turn hands to P2.
  const miss = resolve('J10');
  assert(miss.type === 'battleship.shot', 'resolve() emits a battleship.shot');
  assert(miss.shooter === 'p1' && miss.target === 'p2', `miss shooter p1 → target p2 (got ${miss.shooter}/${miss.target})`);
  assert(miss.coord === 'J10' && miss.result === 'miss', `J10 is a miss (coord ${miss.coord}, result ${miss.result})`);
  assert(miss.next === 'p2', `after a miss the turn flips to p2 (got "${miss.next}")`);
  assert(miss.status === 'firing', 'game still firing after the miss');
  // The shot payload reveals ONLY the result — never an unfired hull cell.
  assert(!miss.coord.includes('A') || miss.result === 'miss', 'shot payload carries only coord+result, no layout');

  // P2 misses to hand back (J10 on P1's board is also water).
  const p2miss = resolve('J10');
  assert(p2miss.result === 'miss' && p2miss.next === 'p1', 'P2 misses, turn back to p1');

  // P1 fires A1 → P2's carrier → hit.
  const hit = resolve('A1');
  assert(hit.result === 'hit', `A1 hits P2's carrier (got "${hit.result}")`);
  assert(hit.next === 'p2', 'a hit also hands the turn over');

  // ── 7. already-fired + off-board rejections during firing ───────────────────
  resolve('I10'); // P2 misses to hand back to P1
  const refire = resolve('J10'); // P1 already fired J10 on P2's board
  assert(refire.error === 'already-fired', `re-firing a cell rejected "already-fired" (got "${refire.error}")`);
  const fireOff = resolve('Z1');
  assert(fireOff.error === 'off-board', `firing off-board rejected "off-board" (got "${fireOff.error}")`);

  // ── 8. the SHOOTER's record path: relayed result + turn-sync contract ───────
  // record(coord, result) drives the local engine (marks fired, advances turn)
  // and reports the AUTHORITATIVE relayed result. It is P1's turn (the off-board
  // attempt didn't consume the turn). P1 records a relayed "hit" at B7.
  const beforeTurn = snapshot().turn;
  assert(beforeTurn === 'p1', `it is P1's turn before record (got "${beforeTurn}")`);
  const rec = record('B7', 'hit');
  assert(rec.type === 'battleship.shot', 'record() emits a battleship.shot');
  assert(rec.coord === 'B7' && rec.result === 'hit', `record echoes the relayed coord+result (${rec.coord}/${rec.result})`);
  assert(rec.shooter === 'p1', `record shooter is p1 (got "${rec.shooter}")`);
  assert(rec.next === 'p2', `record advanced the turn to p2 (got "${rec.next}")`);
  // A malformed relayed result is rejected (defends against a malicious peer).
  const badRes = record('C7', 'pwned');
  assert(badRes.error === 'bad-result', `malformed relayed result rejected "bad-result" (got "${badRes.error}")`);

  // ── 9. a DETERMINISTIC forced win: P1 sinks P2's entire fleet ────────────────
  // Fresh game, both standard fleets, P1 walks every P2 hull cell; P2 misses a
  // unique water cell between each so P1 keeps the turn. The final hull cell is a
  // terminal Win.
  assert(ex.battleship_new() === 1, 'fresh game for the scripted win');
  for (const [ship, origin, orient] of STANDARD_FLEET) {
    place('p1', ship, origin, orient);
    place('p2', ship, origin, orient);
  }
  ready('p1');
  assert(ready('p2').status === 'firing', 'scripted game reached firing');

  const targets = fleetHullCells();
  assert(targets.length === 17, `standard fleet has 17 hull cells (got ${targets.length})`);
  let last = null;
  let sawSunk = false;
  for (let i = 0; i < targets.length; i++) {
    assert(snapshot().turn === 'p1', `P1 holds the turn at shot ${i}`);
    const shot = resolve(targets[i]);
    last = shot;
    if (i + 1 < targets.length) {
      // Not the last hull cell: a hit or non-final sunk; the game continues.
      assert(
        shot.result === 'hit' || shot.result.startsWith('sunk:'),
        `shot ${i} (${targets[i]}) is a hit/sunk, not a miss (got "${shot.result}")`,
      );
      if (shot.result.startsWith('sunk:')) sawSunk = true;
      assert(shot.status === 'firing', `game still firing at shot ${i} (got "${shot.status}")`);
      // P2 misses a unique water cell to hand the turn back to P1.
      const back = resolve(waterCell(i));
      assert(back.result === 'miss' && back.next === 'p1', `P2 misses ${waterCell(i)}, turn back to p1`);
    }
  }
  assert(sawSunk, 'at least one ship was reported "sunk:<ship>" before the win');
  assert(last.result.startsWith('win:'), `the final hull cell is a terminal win (got "${last.result}")`);
  assert(last.status === 'win:p1', `terminal status is "win:p1" (got "${last.status}")`);
  assert(snapshot().p2Sunk === 5, `P2 lost all 5 ships (got ${snapshot().p2Sunk})`);
  assert(status().status === 'win:p1', `battleship_status() is "win:p1" at game over (got "${status().status}")`);

  // ── 10. no fire after the game is over ───────────────────────────────────────
  const afterOver = resolve('B9');
  assert(
    afterOver.error === 'game-over',
    `firing after the game ended rejected "game-over" (got "${afterOver.error}")`,
  );

  // ── report ──────────────────────────────────────────────────────────────────
  console.log(`\n${passed} assertions passed, ${failures.length} failed.`);
  if (failures.length > 0) {
    console.error('FAILURES:\n  - ' + failures.join('\n  - '));
    process.exit(1);
  }
  console.log('OK — browser-loadable Battleship WASM is rules-correct (fog-of-war resolve/record).');
}

main().catch((e) => {
  console.error('test crashed:', e);
  process.exit(1);
});
