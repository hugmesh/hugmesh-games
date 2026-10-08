# 010 — Per-session SSE sink isolation

**Status:** Adopted (Phase 1 of the multiplayer overhaul).

## Decision

`GameStateService` keeps a `ConcurrentHashMap<String, SessionSinks>` of per-session sink bundles next to the legacy global sinks. When a control event arrives with a `sessionId`, the resulting `PlayerMovement`, `WorldState` and `CharacterDto` fan out on **both**:

- the **legacy global sink**, which a subscriber to `/api/game/stream` without a `sessionId` reads, and
- the **session-scoped sink** keyed by `sessionId`, so a subscriber to `/api/game/stream?sessionId=A` sees only session A's events.

Two channels differ. `PlayerViewEvent` goes to the session sink only (plus the Kafka publisher); it has no global sink. Session-control events and input events go to both.

`/api/admin/stream` is not the global firehose. It sends per-pod stats on a 5-second timer and does not read these sinks.

The service allocates a session's sinks lazily, on first emit or first stream access.

## Why

The directive was explicit: "ensure to add tests as well of all sse streams back to the client to ensure different sessions don't update each other."

Without per-session keying, every subscriber attaches to the same `Sinks.Many`. A player in session A would see movement, world and view events from session B. That cannot work for any deployment with more than one session.

## How to apply

- All seven sinks in `SessionSinks` (movement, world, character, view, session-control, input, world-native) use `Sinks.many().multicast().onBackpressureBuffer(N, /* autoCancel */ false)`, the rule from [decision 003](003-autocancel-false.md).
- `GameStateService.applyControl(event, characterId, sessionId)` is the multiplayer-aware overload. The 2-arg form remains for the legacy single-player path and delegates with `null`. A `null` `sessionId` skips the per-session emit.
- `GameStateService.movementStream(sessionId)`, `worldStream(sessionId)`, `charStatStream(sessionId)` and `viewStream(sessionId)` return the per-session `Flux`. `inputStream`, `sessionControlStream` and `worldNativeStream` have per-session forms too. The no-arg variants return the legacy global streams. There is no no-arg `viewStream`.
- `GameStreamController` accepts `?sessionId=` and switches to the per-session streams when it is present. The caller must then be an active session member, an admin or a superadmin (401 without a JWT, 403 for a non-member). The no-arg path stays open to unauthenticated spectators. Its time, scene, audio and WASM-console channels stay global.
- `AdminSessionStreamController` at `/api/admin/sessions/{id}/stream` merges the movement, world, character, view and session-control channels, plus a keepalive, into one `text/event-stream` for admin observation.

## Trade-offs accepted

- **Sinks live forever.** The `sessionSinks` map is never pruned at runtime. Postgres bounds the number of sessions, so the memory cost is small (7 sinks × tens of sessions). A `SessionSweeper` job could prune expired sessions if memory grows in production. No such class exists yet.
- **Two emits per control event.** Global plus per-session. The cost is negligible. Emitting per-session only would break the global stream's contract.
- **`onBackpressureBuffer` capacities are fixed at construction.** A session with very high churn could fill the buffer faster than subscribers drain it. The global sinks carry the same risk and the same mitigation: the buffer is sized for tens of subscribers and ~64 events of memory.

## How we proved isolation

[`SessionIsolationTest`](../../../backend/src/test/java/games/hugmesh/bff/service/SessionIsolationTest.java) emits on session A's sinks and asserts that session B's subscriber sees nothing. Eight specs cover movement (both directions), world snapshots, view events, input events, input with no session, bidirectional isolation, and the legacy single-player path's non-leakage.

## Reference

- [02 — Runtime architecture § Per-session SSE sinks](../architecture/02-runtime.md)
- [05 — API + service reference § GameStateService](../architecture/05-api-reference.md)
- [`SessionSinks`](../../../backend/src/main/java/games/hugmesh/bff/service/SessionSinks.java)
