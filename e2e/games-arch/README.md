# Multi-user games architecture e2e (`games-arch`)

A **committed, re-runnable** harness that proves the 14 web-native games REALLY
work in the deployed architecture **with users** — the host-authoritative SSE
relay, sessions, multiplayer delivery, and cross-session isolation — at the
**API/SSE layer** (no browser). In host-authoritative games this is the server's
actual job: gameplay runs in the host browser's wasm engine and the BFF is a
**dumb relay** over per-session Server-Sent Events. So the meaningful "does
multiplayer work?" proof lives at the relay, not in a headless Chrome.

This is the live multi-user complement to the agent-free build proof (all 14
wasm rebuild byte-identically + 29/29 node suites) — it does NOT re-do that.

## What it asserts

**Per RELAY game** (chess, connect-four, reversi, gomoku, dots-and-boxes, go,
battleship, tron, bomberman, asteroids-duel):

1. mint 2 tokens (player1, player2) via Casdoor ROPC;
2. player1 `POST /api/sessions {gameSlug}` → `sessionId`;
3. player2 `POST /api/sessions/{id}/join`;
4. player2 opens the **real** SSE stream `GET /api/game/stream?sessionId=…` and
   reads it (a manual SSE line-reader over the fetch body — **a harness that
   only sends is worthless**);
5. player1 sends the game's **representative move** via `POST /api/input/{cid}`
   (the exact `(kind,type,payload)` the game's frontend emits — see
   `scenarios.mjs`), plus the host `world/state` echo for games that broadcast;
6. **assert** player2 receives the relayed event on the `input` channel within a
   timeout, with a content check.

**Isolation**: a 2nd session for a different game; assert its events are
**absent** on session 1's stream (the live flip-side of `SessionIsolationTest`).

**Per SOLO game** (tetris, minesweeper, sudoku, match-three): `POST
/api/games/{slug}/launch` mints a uid + the bundle serves; no relay.

**Global**: a plain `player` sees all 14 via `/api/games/available/{cid}`; the
federation manifest lists 14 `wasm-game` rows; each bundle serves via the
wasm-fragment (`index.html` + its **real** wasm filename = 200).

## Relay contract (verified from source)

- read: `EventSource`/`GET /api/game/stream?sessionId={id}`, per-channel events.
  Both the guest's `kind:'control'` move AND the host's `kind:'world',
  type:'state'` echo ride the **`input`** SSE channel — `GameStateService.emitInput`
  fans every `/api/input` emit to the session-scoped input sink regardless of kind.
- send: `POST /api/input/{characterId}` body `{gameSlug, sessionId, kind, type,
  payload}` (`sendInput`/`sendWorld`).
- `InputEventController` Gate 0 = session-member ownership of `characterId`;
  Gate 1 = `(kind,type)` must exist in `input_event_types` (unknown → **400**).

## Run it

The api has no host port, so the harness runs from a container on the
`sdgames_default` network.

```bash
# One-shot (brings the stack up, runs, tears down):
make games-arch-e2e-up

# Against an already-up stack (docker compose -p sdgames up -d --build):
make games-arch-e2e
# or directly:
PROJECT=sdgames bash e2e/games-arch/run-in-docker.sh
```

`run-in-docker.sh` greps the per-fresh-DB Casdoor seed creds (client secret +
player passwords) out of `docker compose -p sdgames logs casdoor` and injects
them as env. Override with `CASDOOR_CLIENT_SECRET` / `PLAYER1_PASS` /
`PLAYER2_PASS` / `PLAYER_PASS`.

## Files

| file | role |
|---|---|
| `scenarios.mjs` | the scenario table — relay games + their real moves, solo games, expected slugs |
| `lib.mjs` | zero-dep transport: ROPC mint, JSON fetch, the manual SSE line-reader, health wait |
| `run.mjs` | the runner — relay / isolation / solo / global, prints per-game pass/fail + a `RESULTS_JSON` block |
| `run-in-docker.sh` | cred-resolving driver that runs `run.mjs` in node:20 on `sdgames_default` |
