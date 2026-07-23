# How to create or join a multiplayer session

Two paths from the home page; either way ends with the player driving one slot in a multi-player game with the BFF's per-session SSE sinks scoped to that session.

## Prerequisites

- Casdoor `player` role (or `admin`).
- For the slot you want to drive: an active character (the BFF auto-binds `characterId` to your default character).

## Path A — From the home page UI

1. Click any unlocked game card on `/`. The chapter modal opens.
2. The modal shows a **Multiplayer sessions** section above the chapter list.
3. Either:
   - **Join** an existing session — click *Join* on a row. The lowest free slot is auto-claimed.
   - **Start new** — type a name in the input, optionally bump max players, click *Start new session*. You become the creator at slot 0.
4. The modal shows a green confirmation banner: *✓ Joined session — slot N*.
5. Pick a chapter from the list below. The launch threads `?session_id=…&slot_index=N&character_id=…` onto the iframe URL; HostBridge flips into multiplayer mode automatically.

To launch solo without a session, just click a chapter without using the picker — single-player path is unchanged.

## Path B — From the API directly

Useful for scripts, e2e tests, or other UIs:

```bash
JWT=...   # bearer token

# 1) Create
curl -sS -X POST http://localhost:8080/api/sessions \
  -H "Authorization: Bearer $JWT" \
  -H 'Content-Type: application/json' \
  -d '{"gameSlug":"three-wizards","name":"Tower run","maxPlayers":3,"characterId":1,"slotIndex":0}'
# → { "id":"...uuid...", "members":[{"casdoorId":"alice","slotIndex":0,"creator":true}], ... }

# 2) Or list and join an existing one
curl -sS -H "Authorization: Bearer $JWT" \
  'http://localhost:8080/api/sessions?gameSlug=three-wizards'
curl -sS -X POST -H "Authorization: Bearer $JWT" -H 'Content-Type: application/json' \
  -d '{"characterId":2,"slotIndex":1}' \
  http://localhost:8080/api/sessions/<sessionId>/join

# 3) Mint a launch + open the iframe
curl -sS -X POST -H "Authorization: Bearer $JWT" \
  http://localhost:8080/api/games/three-wizards/launch
# Open in browser:
#   /_remotes/wasm-fragment/cave/index.html
#     ?session_id=<sessionId>&slot_index=1&character_id=2&launch_uid=<uid>
```

## Verify the SSE per-session isolation

Subscribe to one session's stream and emit a control on a different session — you should see *nothing* on the wrong session's stream.

```bash
# Terminal A — subscribe to session A
curl -N -H "Authorization: Bearer $JWT" \
  'http://localhost:8080/api/game/stream?sessionId=<SESSION_A>'

# Terminal B — emit a control bound to session B
curl -X POST \
  -H "Authorization: Bearer $JWT" \
  -H "X-Session-Id: <SESSION_B>" \
  -H 'Content-Type: application/json' \
  -d '{"action":"MOVE_FORWARD","pressed":true,"timestamp":0}' \
  http://localhost:8080/api/controls/<characterId-in-B>

# Terminal A's stream stays silent — that's the contract.
```

## What the BFF does on join / leave

- **Join** — `INSERT INTO session_members` (or update if rejoining); idempotent on `(session_id, casdoor_id)` via the partial unique index.
- **Leave** — stamps `left_at = NOW()`. If the leaver was the last active member, the session itself auto-closes (`closed_at = NOW()`).
- **Slot collisions** — the partial unique index `uq_session_members_active_slot` returns a 409 if two players try to claim the same slot at the same time.

## See also

- [Decision 010 — Per-session SSE sink isolation](../decisions/010-per-session-sse-sinks.md)
- [05 — API reference § MultiplayerSessionController](../architecture/05-api-reference.md)
- The integration test that pins the isolation contract: [`SessionIsolationTest`](../../../backend/src/test/java/games/hugmesh/bff/service/SessionIsolationTest.java).
