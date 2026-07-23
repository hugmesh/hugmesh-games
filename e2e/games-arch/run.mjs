// e2e/games-arch/run.mjs
//
// Multi-user ARCHITECTURE e2e harness for the 14 web-native games.
//
// This proves the games REALLY work in the deployed architecture WITH USERS at
// the API/SSE layer — the server's actual job in host-authoritative games. No
// browser: the host-authoritative model puts gameplay in the host's wasm engine
// and makes the BFF a DUMB RELAY over per-session SSE. We exercise that relay
// exactly as games/_shared/sse-host-client.js does (direct transport).
//
// Per RELAY game (chess, connect-four, reversi, gomoku, dots-and-boxes, go,
// battleship, tron, bomberman, asteroids-duel):
//   1. mint 2 tokens (player1, player2);
//   2. player1 POST /api/sessions {gameSlug} -> sessionId;
//   3. player2 POST /api/sessions/{id}/join;
//   4. player2 opens the real SSE stream (/api/game/stream?sessionId=);
//   5. player1 sends the game's representative move via POST /api/input/{cid}
//      (and, for games that broadcast, the host world/state echo);
//   6. ASSERT player2 RECEIVES the relayed event on the `input` channel within
//      a timeout, with a CONTENT check (the right (kind,type) + payload).
//   ISOLATION: a 2nd session for a DIFFERENT game; emit a move there; ASSERT
//   that event is ABSENT on session-1's stream.
//
// Per SOLO game (tetris, minesweeper, sudoku, match-three):
//   POST /api/games/{slug}/launch mints a uid; the bundle serves; no relay.
//
// Plus GLOBAL facts: a plain player sees all 14 via /api/games/available/{cid};
// the federation manifest lists 14 wasm-game rows; each bundle serves via the
// wasm-fragment (index.html + its REAL wasm filename = 200).
//
// Exit non-zero iff any REQUIRED assertion fails. Per-game results are printed
// AND emitted as a machine-readable JSON block for the parent workflow.

import {
  API_BASE,
  WASM_FRAGMENT_BASE,
  mintToken,
  apiJson,
  rawGet,
  openSseStream,
  waitForApiHealth,
  sleep,
} from './lib.mjs';
import {
  RELAY_GAMES,
  SOLO_GAMES,
  ALL_GAME_SLUGS,
  EXPECTED_WASM_GAME_SLUGS,
  CHARACTER_IDS,
} from './scenarios.mjs';

const RELAY_TIMEOUT_MS = Number(process.env.RELAY_TIMEOUT_MS || 10000);
const ISOLATION_QUIET_MS = Number(process.env.ISOLATION_QUIET_MS || 3500);

// Credentials. Passwords are random per fresh Casdoor DB, so they MUST be
// passed in (the make target greps them out of the casdoor logs). Defaults
// are only useful when the env was pre-seeded deterministically.
const CREDS = {
  player1: {
    user: 'player1',
    pass: process.env.PLAYER1_PASS || process.env.CASDOOR_PLAYER1_PASS || '',
  },
  player2: {
    user: 'player2',
    pass: process.env.PLAYER2_PASS || process.env.CASDOOR_PLAYER2_PASS || '',
  },
  player: {
    user: 'player',
    pass: process.env.PLAYER_PASS || process.env.CASDOOR_PLAYER_PASS || '',
  },
};

const results = { relay: {}, solo: {}, global: {} };
let failures = 0;
const log = (...a) => console.log(...a);
function fail(label, detail) {
  failures++;
  log(`  FAIL ${label}${detail ? ' — ' + detail : ''}`);
}
function pass(label, detail) {
  log(`  ok   ${label}${detail ? ' — ' + detail : ''}`);
}

// ── session helpers ──────────────────────────────────────────────────────────

async function createSession(token, gameSlug, characterId, slotIndex) {
  const r = await apiJson('POST', '/api/sessions', {
    token,
    body: { gameSlug, name: `arch-e2e ${gameSlug} ${Date.now()}`, maxPlayers: 3, visibility: 'public', characterId, slotIndex },
  });
  if (!r.ok || !r.json || !r.json.id) {
    throw new Error(`create session ${gameSlug} failed: HTTP ${r.status} ${r.text.slice(0, 200)}`);
  }
  return r.json.id;
}

async function joinSession(token, sessionId, characterId, slotIndex) {
  const r = await apiJson('POST', `/api/sessions/${sessionId}/join`, {
    token,
    body: { characterId, slotIndex },
  });
  if (!r.ok) {
    throw new Error(`join session ${sessionId} failed: HTTP ${r.status} ${r.text.slice(0, 200)}`);
  }
  return r.json;
}

/** POST one relay event the way sendInput/sendWorld do. Returns {status, ok, text}. */
async function sendRelay(token, sessionId, gameSlug, characterId, ev) {
  return apiJson('POST', `/api/input/${characterId}`, {
    token,
    sessionId,
    body: { gameSlug, sessionId, kind: ev.kind, type: ev.type, payload: ev.payload },
  });
}

/** Match an input-channel SSE event carrying the given (kind,type) + game. */
function matchEvent(gameSlug, kind, type) {
  return (e) =>
    e.channel === 'input' &&
    e.data &&
    typeof e.data === 'object' &&
    e.data.kind === kind &&
    e.data.type === type &&
    e.data.gameSlug === gameSlug;
}

// ── token mint ───────────────────────────────────────────────────────────────

async function mintAll() {
  const tokens = {};
  for (const k of ['player1', 'player2', 'player']) {
    const { user, pass } = CREDS[k];
    if (!pass) throw new Error(`missing password for ${user} (set ${k.toUpperCase()}_PASS)`);
    tokens[k] = await mintToken(user, pass);
  }
  return tokens;
}

// ── RELAY game scenario ─────────────────────────────────────────────────────
//
// Returns { received: bool, worldRelayed: bool|null, sessionId, notes }.

async function runRelayGame(game, tokens) {
  const res = { slug: game.slug, received: false, worldReceived: null, moveStatus: null, worldStatus: null, sessionId: null, notes: [] };
  let stream;
  try {
    const sid = await createSession(tokens.player1, game.slug, CHARACTER_IDS.player1, 0);
    res.sessionId = sid;
    await joinSession(tokens.player2, sid, CHARACTER_IDS.player2, 1);

    // player2 opens the session-scoped SSE stream and reads it for real.
    stream = await openSseStream(sid, { token: tokens.player2 });
    // Give the subscriber a beat to attach before the first emit.
    await sleep(300);

    // player1 sends the guest's representative move.
    const moveRes = await sendRelay(tokens.player1, sid, game.slug, CHARACTER_IDS.player1, game.move);
    res.moveStatus = moveRes.status;
    if (moveRes.status === 400) {
      res.notes.push(
        `move (${game.move.kind}/${game.move.type}) REJECTED 400 by InputEventController Gate 1 — (kind,type) absent from input_event_types: ${moveRes.text.slice(0, 160)}`,
      );
    } else if (!moveRes.ok && moveRes.status !== 202) {
      res.notes.push(`move POST returned HTTP ${moveRes.status}: ${moveRes.text.slice(0, 160)}`);
    }

    // Assert player2 RECEIVES the move on the input channel (content-checked).
    const got = await stream.waitFor(matchEvent(game.slug, game.move.kind, game.move.type), RELAY_TIMEOUT_MS);
    res.received = !!got;

    // If the game broadcasts a host world echo, exercise that path too.
    if (game.world) {
      const worldRes = await sendRelay(tokens.player1, sid, game.slug, CHARACTER_IDS.player1, game.world);
      res.worldStatus = worldRes.status;
      if (worldRes.status === 400) {
        res.notes.push(`world echo (${game.world.kind}/${game.world.type}) REJECTED 400: ${worldRes.text.slice(0, 120)}`);
      }
      const gotW = await stream.waitFor(matchEvent(game.slug, game.world.kind, game.world.type), RELAY_TIMEOUT_MS);
      res.worldReceived = !!gotW;
    }
  } catch (e) {
    res.notes.push(`exception: ${e.message}`);
  } finally {
    if (stream) await stream.close();
  }
  return res;
}

// ── ISOLATION scenario ──────────────────────────────────────────────────────
//
// Open SSE on session A (game A). Create session B for a DIFFERENT relay game,
// emit B's move. Assert session A's stream NEVER carries B's event. This is the
// live flip-side of SessionIsolationTest.

async function runIsolation(tokens) {
  const gameA = RELAY_GAMES.find((g) => g.slug === 'chess');
  const gameB = RELAY_GAMES.find((g) => g.slug === 'reversi');
  const out = { ok: false, notes: [] };
  let streamA;
  try {
    const sidA = await createSession(tokens.player1, gameA.slug, CHARACTER_IDS.player1, 0);
    await joinSession(tokens.player2, sidA, CHARACTER_IDS.player2, 1);
    streamA = await openSseStream(sidA, { token: tokens.player2 });
    await sleep(300);

    // Sanity: A's own move DOES arrive on A (positive control for the negative test).
    await sendRelay(tokens.player1, sidA, gameA.slug, CHARACTER_IDS.player1, gameA.move);
    const ownArrived = await streamA.waitFor(matchEvent(gameA.slug, gameA.move.kind, gameA.move.type), RELAY_TIMEOUT_MS);
    if (!ownArrived) out.notes.push('session A did not receive its OWN move (isolation control failed)');

    // Now emit on a SEPARATE session B for a different game.
    const sidB = await createSession(tokens.player1, gameB.slug, CHARACTER_IDS.player1, 0);
    await joinSession(tokens.player2, sidB, CHARACTER_IDS.player2, 1);
    await sendRelay(tokens.player1, sidB, gameB.slug, CHARACTER_IDS.player1, gameB.move);

    // Wait the quiet window, then assert B's event NEVER appeared on A.
    await sleep(ISOLATION_QUIET_MS);
    const leaked = streamA.has(
      (e) => e.channel === 'input' && e.data && typeof e.data === 'object' && e.data.gameSlug === gameB.slug,
    );
    out.ok = !!ownArrived && !leaked;
    if (leaked) out.notes.push(`LEAK: session B (${gameB.slug}) event appeared on session A's stream`);
  } catch (e) {
    out.notes.push(`exception: ${e.message}`);
  } finally {
    if (streamA) await streamA.close();
  }
  return out;
}

// ── SOLO game scenario ──────────────────────────────────────────────────────

async function runSoloGame(slug, token) {
  const res = { slug, launched: false, uid: null, served: false, wasmFile: null, wasmServed: false, notes: [] };
  try {
    const r = await apiJson('POST', `/api/games/${slug}/launch`, { token });
    if (r.ok && r.json && r.json.uid) {
      res.launched = true;
      res.uid = r.json.uid;
    } else {
      res.notes.push(`launch HTTP ${r.status}: ${r.text.slice(0, 160)}`);
    }
    const serve = await serveBundle(slug, token);
    res.served = serve.indexServed;
    res.wasmFile = serve.wasmFile;
    res.wasmServed = serve.wasmServed;
    if (serve.notes.length) res.notes.push(...serve.notes);
  } catch (e) {
    res.notes.push(`exception: ${e.message}`);
  }
  return res;
}

// ── bundle serve check (used by solo + the global serve assertion) ───────────
//
// Hits the wasm-fragment directly: GET /<slug>/index.html, parse out the REAL
// wasm filename (wasm/hugmesh_<...>.wasm), then GET that file. Both must 200.

async function serveBundle(slug) {
  const out = { indexServed: false, wasmFile: null, wasmServed: false, notes: [] };
  const idxUrl = `${WASM_FRAGMENT_BASE}/${slug}/index.html`;
  const idx = await rawGet(idxUrl);
  out.indexServed = idx.ok && idx.bytes > 0 && /<html/i.test(idx.text);
  if (!out.indexServed) {
    out.notes.push(`index.html HTTP ${idx.status} (${idx.bytes}b) ct=${idx.contentType}`);
    return out;
  }
  const m = idx.text.match(/wasm\/([A-Za-z0-9_.\-]+\.wasm)/);
  if (!m) {
    out.notes.push('could not find wasm reference in index.html');
    return out;
  }
  out.wasmFile = m[1];
  const wUrl = `${WASM_FRAGMENT_BASE}/${slug}/wasm/${out.wasmFile}`;
  const w = await rawGet(wUrl);
  out.wasmServed = w.ok && w.bytes > 0;
  if (!out.wasmServed) out.notes.push(`wasm ${out.wasmFile} HTTP ${w.status} (${w.bytes}b)`);
  return out;
}

// ── GLOBAL facts ─────────────────────────────────────────────────────────────

async function runGlobal(tokens) {
  const g = { visibility: null, manifest: null, serve: {} };

  // 1. A plain player sees all 14 via /api/games/available/{characterId}.
  const av = await apiJson('GET', `/api/games/available/${CHARACTER_IDS.player1}`, { token: tokens.player });
  if (av.ok && Array.isArray(av.json)) {
    const slugs = av.json.map((x) => x.slug);
    const missing = ALL_GAME_SLUGS.filter((s) => !slugs.includes(s));
    g.visibility = { ok: missing.length === 0, count: slugs.length, missing };
  } else {
    g.visibility = { ok: false, error: `HTTP ${av.status}: ${av.text.slice(0, 160)}` };
  }

  // 2. Manifest lists 14 wasm-game rows.
  const man = await apiJson('GET', '/api/federation/manifest', { token: tokens.player });
  if (man.ok && man.json && Array.isArray(man.json.remotes)) {
    const wasm = man.json.remotes.filter((r) => r.kind === 'wasm-game');
    const slugs = wasm.map((r) => r.gameSlug || r.game_slug).filter(Boolean).sort();
    g.manifest = {
      ok: wasm.length === 14 && JSON.stringify(slugs) === JSON.stringify(EXPECTED_WASM_GAME_SLUGS),
      count: wasm.length,
      slugs,
    };
  } else {
    g.manifest = { ok: false, error: `HTTP ${man.status}: ${man.text.slice(0, 160)}` };
  }

  // 3. Each of the 14 bundles serves via wasm-fragment (index + real wasm).
  for (const slug of ALL_GAME_SLUGS) {
    g.serve[slug] = await serveBundle(slug);
  }
  return g;
}

// ── main ─────────────────────────────────────────────────────────────────────

async function main() {
  log(`\n=== HugMesh games multi-user architecture e2e ===`);
  log(`API_BASE=${API_BASE}  WASM_FRAGMENT_BASE=${WASM_FRAGMENT_BASE}`);

  log(`\n[0] Waiting for API health…`);
  await waitForApiHealth();
  log(`    API is UP.`);

  log(`\n[0] Minting tokens (player1, player2, player)…`);
  const tokens = await mintAll();
  log(`    minted 3 tokens.`);

  // RELAY games.
  log(`\n[1] RELAY games — 2 users, real SSE delivery + content check`);
  for (const game of RELAY_GAMES) {
    log(`\n  • ${game.slug}`);
    const r = await runRelayGame(game, tokens);
    results.relay[game.slug] = r;
    if (r.received) pass(`${game.slug} relay-received (input ${game.move.kind}/${game.move.type})`);
    else fail(`${game.slug} relay-received`, `move HTTP ${r.moveStatus}; ${r.notes.join('; ')}`);
    if (game.world) {
      if (r.worldReceived) pass(`${game.slug} world-echo-received (world/state)`);
      else fail(`${game.slug} world-echo-received`, `world HTTP ${r.worldStatus}; ${r.notes.join('; ')}`);
    }
  }

  // ISOLATION.
  log(`\n[2] ISOLATION — session B's events must be ABSENT on session A's stream`);
  const iso = await runIsolation(tokens);
  results.global.isolation = iso;
  if (iso.ok) pass('cross-session isolation (chess vs reversi)');
  else fail('cross-session isolation', iso.notes.join('; '));

  // SOLO games.
  log(`\n[3] SOLO games — launch mints a uid + bundle serves (no relay)`);
  for (const slug of SOLO_GAMES) {
    const r = await runSoloGame(slug, tokens.player);
    results.solo[slug] = r;
    if (r.launched) pass(`${slug} solo-launch (uid ${r.uid?.slice(0, 8)}…)`);
    else fail(`${slug} solo-launch`, r.notes.join('; '));
    if (r.served && r.wasmServed) pass(`${slug} bundle serves (${r.wasmFile})`);
    else fail(`${slug} bundle serves`, r.notes.join('; '));
  }

  // GLOBAL.
  log(`\n[4] GLOBAL — visibility (14), manifest (14 wasm-game), bundle serve (14)`);
  const g = await runGlobal(tokens);
  Object.assign(results.global, g);
  if (g.visibility.ok) pass(`plain player sees all 14 via /api/games/available (${g.visibility.count})`);
  else fail('visibility of 14 games', JSON.stringify(g.visibility));
  if (g.manifest.ok) pass(`manifest lists 14 wasm-game rows`);
  else fail('manifest 14 wasm-game rows', JSON.stringify(g.manifest));
  let serveFails = [];
  for (const slug of ALL_GAME_SLUGS) {
    const s = g.serve[slug];
    if (!(s.indexServed && s.wasmServed)) serveFails.push(`${slug}(${s.notes.join(',')})`);
  }
  if (serveFails.length === 0) pass(`all 14 bundles serve via wasm-fragment (index + real wasm)`);
  else fail('14 bundles serve', serveFails.join('; '));

  // Machine-readable block for the parent workflow.
  log(`\n=== RESULTS_JSON_BEGIN ===`);
  log(JSON.stringify({ failures, results }, null, 2));
  log(`=== RESULTS_JSON_END ===`);

  log(`\n=== SUMMARY === ${failures === 0 ? 'ALL GREEN' : failures + ' FAILURE(S)'}`);
  process.exit(failures === 0 ? 0 : 1);
}

main().catch((e) => {
  console.error('\nFATAL:', e && e.stack ? e.stack : e);
  process.exit(2);
});
