# How to create or join a multiplayer session

Two paths: the home page, or the API directly. Either way the player drives one slot, and the BFF scopes its SSE sinks to that session.

## Prerequisites

- A signed-in user. `/api/sessions/**` needs authentication only; no `player` or `admin` role is required.
- A character to drive your slot. The home page defaults `characterId` to your first character. A player with several characters gets a *Play as* picker.

## Path A — From the home page UI

1. Click any unlocked game card on `/`. The chapter modal opens.
2. The modal shows a **Play together** section above the chapter list.
3. Either:
   - **Join** an existing session — click *Join* on a row. The page claims the lowest free slot from 1 up. Slot 0 belongs to the creator.
   - **Start new** — type a session name (1 to 120 characters), set *Max players* (1 to 16), pick who can see it (public, friends only or private), and click *Start session*. You become the creator at slot 0.
4. The modal shows: *✓ You're in this session as slot N. Pick a chapter below to launch.*
5. Pick a chapter from the list below. The launch threads `?session_id=…&slot_index=N&character_id=…` onto the iframe URL; HostBridge flips into multiplayer mode automatically.

To launch solo without a session, just click a chapter without using the picker — single-player path is unchanged.

## Path B — From the API directly

Useful for scripts, e2e tests, or other UIs:

```bash
JWT=...   # bearer token

# 1) Create. maxPlayers defaults to 3 and is clamped to 1..16; visibility is
#    "public" (default), "friends" or "private". The creator always gets slot 0.
curl -sS -X POST http://localhost:8080/api/sessions \
  -H "Authorization: Bearer $JWT" \
  -H 'Content-Type: application/json' \
  -d '{"gameSlug":"three-wizards","name":"Tower run","maxPlayers":3,"characterId":1}'
# → { "id":"<43-char token>", "members":[{"casdoorId":"alice","slotIndex":0,"creator":true}], ... }

# 2) Or list and join an existing one
curl -sS -H "Authorization: Bearer $JWT" \
  'http://localhost:8080/api/sessions?gameSlug=three-wizards'
# A guest asking for slotIndex 0 gets 409: slot 0 is the creator's.
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

- **Join** — inserts a `session_members` row, or updates the caller's active row if they rejoin (idempotent per caller, partial unique index `uq_session_members_active_casdoor`). A closed session returns 410. A full session returns 409.
- **Leave** — `DELETE /api/sessions/{id}/me` stamps `left_at`. If the leaver was the creator, the session closes at once with reason `host-disconnected`. If the leaver was the last active member, it closes with `last-member-left`.
- **Slot collisions** — the partial unique index `uq_session_members_active_slot` returns a 409 if two players claim the same slot at the same time.
- **Presence** — `GET /api/sessions/{id}` doubles as a heartbeat. `SessionMemberReaper` frees the seats of members whose heartbeat goes stale.

## See also

- [Decision 010 — Per-session SSE sink isolation](../decisions/010-per-session-sse-sinks.md)
- [05 — API reference § MultiplayerSessionController](../architecture/05-api-reference.md)
- The integration test that pins the isolation contract: [`SessionIsolationTest`](../../../backend/src/test/java/games/hugmesh/bff/service/SessionIsolationTest.java).
