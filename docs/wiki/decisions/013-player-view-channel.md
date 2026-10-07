# 013 — Per-player view egress channel for admin observation

**Status:** Adopted (Phase 1 + Phase 3 of the multiplayer overhaul).

## Decision

A new `view` SSE channel on the BFF carries per-player camera and orientation samples (`PlayerViewEvent`: position, yaw/pitch, FOV, slot, character, session). Every player's Godot bundle calls the Rust GDExtension's new `report_view(slot, x, y, z, yaw, pitch, fov)` at ~10 Hz. The Rust `BridgeGlue` posts each sample to the parent window as `SD_VIEW_EGRESS`, and the host shell's `ApiBridgeService` POSTs `/api/view`. The BFF authorizes against `session_members` and fans out on the per-session view sink.

Admins subscribe to `/api/admin/sessions/{id}/stream`, which merges the per-session movement / world / character / view channels into one connection. They see what each player is looking at in near-real-time.

## Why

The user's directive was explicit: "give an event stream that would allow the admin to see each players view via the stream on the admin view where the session can be selected."

We weighed two shapes:

1. **Stream video frames** of each player's iframe. Precise, but extremely expensive (encoded video × N players × hundreds of admins), and it needs a media capture / WebRTC pipeline that doesn't exist.
2. **Stream camera state** (position + orientation + FOV). The admin renders a frustum hint or a map pin. It is cheap, reuses the SSE infrastructure, and answers the real question: "where is each player looking right now?"

We picked option 2.

## How to apply

- Rust HostBridge: new `view_egress(payload)` signal and `report_view` method (Phase 3).
- Game scripts: a camera controller is meant to call `bridge.report_view(...)` at ~10 Hz. No shipped game script calls it yet; `e2e/tests/admin-sessions-view.spec.ts` posts samples directly. (The `multiplayer_lobby.gd` this record once named is gone; the lobby is Rust now, see 016.)
- BFF: `POST /api/view` (`ViewEgressController`) authorizes through `GameSessionService.authorizeControl` and emits `PlayerViewEvent` on the per-session view sink.
- Cassandra: `session_view_state` (per-(session, slot) rows, 60 s TTL) exists in `docker/cassandra-init.cql` to give a joining admin the latest sample at once. Nothing writes or reads it yet: `GameStateService.emitView` feeds only the per-session sink and the Kafka publisher. The sink is multicast with no replay, so a joining admin waits for the next sample.
- Admin UI: `SessionsRemoteComponent` at `/admin/sessions` lists open sessions. Selecting one opens an `EventSource` on `/api/admin/sessions/{id}/stream` and renders a per-slot view table.

## Trade-offs accepted

- **No video.** Admins see camera state, not pixels. A media capture pipeline could be layered on later; nothing here forecloses it.
- **10 Hz per slot per session.** That is ~30 events/second for a three-wizards game, well within the SSE budget. At N=16 players × many sessions, batching may pay off, but the per-session sinks already scope the fan-out.
- **Authorization on every view sample.** Each POST hits `session_members` for the active-owner check. Caching membership in Cassandra is one optimisation if this gets hot.

## Reference

- [PlayerViewEvent](../../../backend/src/main/java/games/hugmesh/bff/model/PlayerViewEvent.java)
- [ViewEgressController](../architecture/05-api-reference.md)
- [AdminSessionStreamController](../architecture/05-api-reference.md)
- Phase 3 commit `5713f2b` (Rust HostBridge + GDScript wiring).
- [Decision 014 — All Godot networking through the bridge](014-bridge-routed-networking.md).
