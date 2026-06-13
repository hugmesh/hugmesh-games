// Functional test for the browser-loadable Asteroids-Duel engine WASM module.
//
// Loads games/asteroids-duel/wasm/snapdragon_asteroids_duel_wasm.wasm with
// WebAssembly.instantiate and EMPTY imports (proving it runs in a bare browser via
// instantiateStreaming with no host glue), then drives a DETERMINISTIC known
// scenario through the C-ABI / JSON-over-linear-memory shim. If this passes, the
// same .wasm the browser bundle ships is simulation-correct.
//
// CATEGORY: REALTIME DUEL (host-authoritative TICK LOOP). Unlike the turn-based
// shims, the gameplay ops take SCALAR args (ship index + input flags) and the
// host advances a fixed-step clock with asteroids_tick. The engine is
// deterministic (integer fixed-point + a seeded PRNG), so a known seed + known
// input sequence reaches a known terminal state bit-for-bit — that is what the
// scenarios below assert.
//
// Run:  node games/_shared/test/asteroids-duel-wasm.test.mjs

import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const WASM_PATH = join(
  here,
  '..',
  '..',
  'asteroids-duel',
  'wasm',
  'snapdragon_asteroids_duel_wasm.wasm',
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
  const dec = new TextDecoder();

  // ── linear-memory marshalling helper (the documented ABI) ──────────────────
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
  const snapshot = () => JSON.parse(readPacked(ex.asteroids_snapshot()));
  const status = () => JSON.parse(readPacked(ex.asteroids_status())).status;

  console.log(`Loaded ${WASM_PATH} (${bytes.length} bytes), zero host imports.`);

  // ── 0. the exports exist (the documented op set) ────────────────────────────
  for (const fn of [
    'alloc',
    'dealloc',
    'asteroids_new',
    'asteroids_input',
    'asteroids_tick',
    'asteroids_snapshot',
    'asteroids_status',
  ]) {
    assert(typeof ex[fn] === 'function', `export ${fn}() is present`);
  }

  // ── 1. survival opening snapshot (seed 42, 1 ship) ──────────────────────────
  assert(ex.asteroids_new(42, 0, 1) === 1, 'asteroids_new(42, …, 1) returns 1 (survival created)');
  const open = snapshot();
  assert(open.type === 'asteroids.snapshot', 'asteroids_snapshot() emits an asteroids.snapshot');
  assert(open.players === 1, `survival has 1 ship slot (got ${open.players})`);
  assert(open.ships.length === 1, `survival snapshot has exactly 1 ship (got ${open.ships.length})`);
  assert(open.fp === 256, `fixed-point scale is 256 (got ${open.fp})`);
  assert(open.width === 1024 && open.height === 768, `field is 1024x768 (got ${open.width}x${open.height})`);
  assert(open.asteroids.length === 4, `seeded field has 4 large asteroids (got ${open.asteroids.length})`);
  assert(open.asteroids.every((a) => a.size === 'L'), 'every seeded asteroid is Large');
  assert(open.bullets.length === 0, `no bullets at the opening (got ${open.bullets.length})`);
  assert(open.tick === 0, `opening tick is 0 (got ${open.tick})`);
  assert(open.ships[0].alive === true, 'the lone ship is alive at the opening');
  assert(open.ships[0].score === 0, `opening score is 0 (got ${open.ships[0].score})`);
  assert(status() === 'playing', `opening status is "playing" (got "${status()}")`);

  // ── 2. illegal input is rejected: ship 1 doesn't exist in survival ──────────
  assert(
    ex.asteroids_input(1, 1, 0, 0) === 0,
    'asteroids_input(ship=1, …) rejected with 0 in a single-ship survival game',
  );
  assert(
    ex.asteroids_input(0, 1, 0, 0) === 1,
    'asteroids_input(ship=0, …) accepted with 1 (the lone ship exists)',
  );
  // The rejected input must not have leaked into the engine: a fresh game with no
  // thrust stays at rest on the first tick.
  ex.asteroids_new(42, 0, 1);
  const restX = snapshot().ships[0].x;
  ex.asteroids_tick();
  assert(snapshot().ships[0].x === restX, 'a ship with no input does not move (rejected ship-1 input had no effect)');

  // ── 3. thrust accelerates along the heading (+X at heading 0) ───────────────
  ex.asteroids_new(42, 0, 1);
  const h0 = snapshot().ships[0].heading;
  assert(h0 === 0, `survival ship spawns heading +X (heading 0, got ${h0})`);
  ex.asteroids_input(0, 1, 0, 0); // thrust, no turn, no fire
  ex.asteroids_tick();
  const thrusted = snapshot().ships[0];
  assert(thrusted.vx > 0, `thrust at heading 0 builds +X velocity (vx=${thrusted.vx})`);
  assert(thrusted.vy === 0, `no Y velocity when heading is exactly +X (vy=${thrusted.vy})`);

  // ── 4. rotation steps the heading deterministically ─────────────────────────
  ex.asteroids_new(42, 0, 1);
  ex.asteroids_input(0, 0, 1, 0); // turn right
  ex.asteroids_tick();
  assert(snapshot().ships[0].heading === 3, `one right-turn tick steps heading by 3 (got ${snapshot().ships[0].heading})`);

  // ── 5. firing spawns one bullet, then the cooldown blocks a rapid re-fire ────
  ex.asteroids_new(42, 0, 1);
  ex.asteroids_input(0, 0, 0, 1); // fire
  ex.asteroids_tick();
  assert(snapshot().bullets.length === 1, `firing spawns exactly one bullet (got ${snapshot().bullets.length})`);
  assert(snapshot().bullets[0].owner === 'p1', `the bullet is owned by p1 (got "${snapshot().bullets[0].owner}")`);
  ex.asteroids_input(0, 0, 0, 1); // hold fire during the cooldown
  ex.asteroids_tick();
  assert(snapshot().bullets.length === 1, `the fire cooldown blocks a second bullet next tick (got ${snapshot().bullets.length})`);

  // ── 6. a scripted survival run scores by destroying an asteroid ─────────────
  // Seed 42, spin-and-fire policy. Deterministic: the engine is integer-only, so
  // this exact policy lands a large-asteroid hit (score 20) at tick 39. We assert
  // the FIRST score, the size-tier value, and that the status is still "playing".
  ex.asteroids_new(42, 0, 1);
  let firstScore = 0;
  let scoreTick = -1;
  for (let t = 0; t < 60; t++) {
    ex.asteroids_input(0, t % 3 === 0 ? 1 : 0, t % 8 < 2 ? 1 : t % 8 < 5 ? -1 : 0, 1);
    ex.asteroids_tick();
    const s = snapshot();
    if (s.ships[0].score > 0) {
      firstScore = s.ships[0].score;
      scoreTick = t;
      break;
    }
  }
  assert(firstScore === 20, `the spin-and-fire run scores a Large asteroid = 20 points (got ${firstScore})`);
  assert(scoreTick === 39, `the first score lands deterministically at tick 39 (got ${scoreTick})`);
  assert(status() === 'playing', `the game is still playing after the first kill (got "${status()}")`);

  // ── 7. determinism: same seed + same input sequence ⇒ identical snapshot ────
  function scriptedRun() {
    ex.asteroids_new(42, 0, 1);
    for (let t = 0; t < 120; t++) {
      ex.asteroids_input(0, t % 3 === 0 ? 1 : 0, t % 8 < 2 ? 1 : t % 8 < 5 ? -1 : 0, 1);
      ex.asteroids_tick();
    }
    return readPacked(ex.asteroids_snapshot());
  }
  const runA = scriptedRun();
  const runB = scriptedRun();
  assert(runA === runB, 'a replay of the same seed + inputs yields a bit-identical final snapshot');

  // ── 8. duel terminal: an idle two-ship duel ends in a deterministic win ─────
  // Seed 7, both ships idle: drifting asteroids decide it. Deterministically a
  // rock finds player two first, so player one WINS at tick 278. We assert the
  // terminal status, the winner, and that a finished game freezes (tick is a no-op).
  assert(ex.asteroids_new(7, 0, 2) === 1, 'asteroids_new(7, …, 2) returns 1 (duel created)');
  const duelOpen = snapshot();
  assert(duelOpen.players === 2 && duelOpen.ships.length === 2, 'duel snapshot has 2 ships');
  assert(
    duelOpen.ships[0].alive && duelOpen.ships[1].alive,
    'both ships are alive at the duel opening',
  );

  let endTick = -1;
  let endStatus = 'playing';
  for (let t = 0; t < 2000; t++) {
    const st = status();
    if (st !== 'playing') {
      endStatus = st;
      endTick = t;
      break;
    }
    ex.asteroids_tick();
  }
  assert(endStatus === 'win:p1', `the idle duel (seed 7) ends with "win:p1" (got "${endStatus}")`);
  assert(endTick === 278, `the duel terminates deterministically at tick 278 (got ${endTick})`);
  const terminal = snapshot();
  assert(
    terminal.ships[0].alive === true && terminal.ships[1].alive === false,
    'at the win, player one is alive and player two is destroyed',
  );
  assert(terminal.status.startsWith('win:p1'), `the terminal snapshot status is win:p1 (got "${terminal.status}")`);

  // A finished game is frozen: ticking it is a no-op (the engine guards terminal).
  const frozen = readPacked(ex.asteroids_snapshot());
  ex.asteroids_tick();
  ex.asteroids_tick();
  assert(readPacked(ex.asteroids_snapshot()) === frozen, 'ticking a terminal game is a no-op (state frozen)');
  assert(status() === 'win:p1', 'a finished duel keeps reporting its terminal status');

  // ── 9. no-game guards (before any asteroids_new on a fresh instance) ────────
  const { instance: fresh } = await WebAssembly.instantiate(bytes, {});
  assert(fresh.exports.asteroids_tick() === 0, 'asteroids_tick() with no game returns 0');
  assert(fresh.exports.asteroids_input(0, 1, 0, 0) === 0, 'asteroids_input() with no game returns 0');
  const freshPacked = fresh.exports.asteroids_snapshot();
  const fbig = BigInt.asUintN(64, BigInt(freshPacked));
  const fptr = Number(fbig >> 32n);
  const flen = Number(fbig & 0xffffffffn);
  const fjson = dec.decode(new Uint8Array(fresh.exports.memory.buffer, fptr, flen).slice());
  fresh.exports.dealloc(fptr, flen);
  assert(fjson === '{}', `asteroids_snapshot() with no game is the empty object {} (got ${fjson})`);

  // ── report ──────────────────────────────────────────────────────────────────
  console.log(`\n${passed} assertions passed, ${failures.length} failed.`);
  if (failures.length > 0) {
    console.error('FAILURES:\n  - ' + failures.join('\n  - '));
    process.exit(1);
  }
  console.log('OK — browser-loadable Asteroids-Duel WASM is simulation-correct.');
}

main().catch((e) => {
  console.error('test crashed:', e);
  process.exit(1);
});
