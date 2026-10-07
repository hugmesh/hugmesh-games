# 013 — Per-player view egress channel for admin observation

**Status:** Adopted (Phase 1 + Phase 3 of the multiplayer overhaul).

## Decision

A new `view` SSE channel on the BFF carries per-player camera + orientation samples (`PlayerViewEvent`: position, yaw/pitch, FOV, slot, character, session). Every player's Godot bundle calls the Rust GDExtension's new `report_view(slot, x, y, z, yaw, pitch, fov)` at ~10 Hz; the Rust `BridgeGlue` posts each sample to the parent window as `SD_VIEW_EGRESS`, and the host shell's `ApiBridgeService` POSTs `/api/view`. The BFF authorizes against `session_members` and fans out on the per-session view sink.

Admins subscribe to `/api/admin/sessions/{id}/stream` (which merges the per-session movement / world / character / view channels into one connection) to see what each player is looking at in near-real-time.

## Why

The user's directive was explicit: "give an event stream that would allow the admin to see each players view via the stream on the admin view where the session can be selected."

Two implementation shapes were considered:

1. **Stream video frames** of each player's iframe back to the admin. This is precise but extremely expensive (encoded video × N players × hundreds of admins) and requires a media capture / WebRTC pipeline that doesn't exist yet.
2. **Stream camera state** (position + orientation + FOV). The admin renders a frustum hint or a small map pin. Cheap, reuses the existing SSE infrastructure, and matches the granularity an admin actually needs ("where is each player looking right now?").

We picked option 2.

## How to apply

- Rust HostBridge: new `view_egress(payload)` signal + `report_view` method (Phase 3).
- Game scripts: a camera controller is meant to call `bridge.report_view(...)` at ~10 Hz. No shipped game script calls it yet; `e2e/tests/admin-sessions-view.spec.ts` posts samples directly. (The `multiplayer_lobby.gd` script this record once named is gone; the lobby is now Rust, see decision 016.)
- BFF: `POST /api/view` (`ViewEgressController`) authz via `GameSessionService.authorizeControl`; emits `PlayerViewEvent` on the per-session view sink.
- Cassandra: `session_view_state` (per-(session, slot) rows, 60 s TTL) exists in `docker/cassandra-init.cql` to give a joining admin the latest sample at once. Nothing writes or reads it yet: `GameStateService.emitView` feeds only the per-session sink and the Kafka publisher. The sink is multicast with no replay, so a joining admin waits for the next sample.
- Admin UI: `SessionsRemoteComponent` at `/admin/sessions` lists open sessions; selecting one opens an `EventSource` against `/api/admin/sessions/{id}/stream` and renders a per-slot view table.

## Trade-offs accepted

- **No video.** Admins see camera state, not pixels. A future enhancement could layer a media capture pipeline on top; nothing in this design forecloses that.
- **10 Hz per slot per session.** ~30 events/second for a three-wizards game; well within the SSE budget. If we ever scale to N=16 players × M=many sessions, batching may become useful — but the per-session sinks already keep the fan-out scoped.
- **Authorization on every view sample.** Each POST hits `session_members` for the active-owner check. Cassandra-cached membership is one optimisation if this becomes hot.

## Reference

- [PlayerViewEvent](../../../backend/src/main/java/games/hugmesh/bff/model/PlayerViewEvent.java)
- [ViewEgressController](../architecture/05-api-reference.md)
- [AdminSessionStreamController](../architecture/05-api-reference.md)
- Phase 3 commit `5713f2b` (Rust HostBridge + GDScript wiring).
- [Decision 014 — All Godot networking through the bridge](014-bridge-routed-networking.md).
