# Server-authoritative real-time (FPS / RTS) — server truth in Cassandra + DB

Design record for the timing-critical genres. The board, puzzle and duel games
(Waves 1–3) are **host-authoritative**: the host browser runs the engine and the
server only streams. **FPS and RTS are SERVER-authoritative.** The event
architecture keeps **server-level truth in Cassandra + Postgres**, and the
frontends are **Godot-created WASM games served by the DB + fragments**.

## Status

This is a design record. Phases P1 and P2 are built; P3 and P4 are not. No FPS
or RTS title is in the catalog.

- **P1, built:** `extensions/hugmesh-fps-core` and `extensions/hugmesh-rts-core`
  are deterministic Rust sims with `cargo test` suites.
- **P2, built, with a Java sim:** the tick loop is `AuthoritativeTickService`.
  Defaults: 30 Hz (`app.realtime.tick-hz`), input delay 2 ticks
  (`app.realtime.input-delay-ticks`), a Cassandra keyframe every 30 ticks
  (`app.realtime.keyframe-interval`). Cassandra holds `realtime_keyframes` and
  `realtime_command_log`, both with a 24-hour TTL (`docker/cassandra-init.cql`).
  Postgres holds `realtime_matches` (session, game, kind, seed, tick rate,
  start and end times, `result_json`), not replays. The only sim the running BFF
  registers is the Java `ReferenceArenaSim` (kind `reference-arena`). The Rust
  cores are not wired in; see [the P4 record](realtime-sim-wiring-p4.md).
- **Not the channels drawn below:** inputs and snapshots do not use the session
  `input` and `world` channels. The surface is `POST /api/realtime/{sessionId}/start`
  (creator only), `POST /api/realtime/{sessionId}/input`,
  `GET /api/realtime/stream?sessionId=` (SSE, event `snapshot`),
  `GET /api/realtime/{sessionId}/log?afterTick=`, and
  `POST /api/admin/realtime/{sessionId}/recover`.
- **Not native:** the text below says the server runs the Rust crate natively.
  The P4 record chose WebAssembly inside the JVM (Chicory) instead.
- **P3 and P4, not built:** no Godot FPS or RTS client, no lag-comp rewind, no
  replay viewer.

## Why two models

| | Waves 1–3 (board / puzzle / duel) | **Waves 4–5 (FPS / RTS)** |
|---|---|---|
| Authority | host browser (WASM engine) | **the server (BFF authoritative tick)** |
| Source of truth | host's engine state | **Cassandra (live) + Postgres (durable)** |
| Server role | relay `input`/`world` events | **run the sim, persist truth, broadcast snapshots** |
| Client | renders host state, re-validates | **sends inputs; predicts + reconciles; renders server snapshots** |
| Why | low stakes, simple, cheap | **timing, fairness, lag-comp, anti-cheat** |

A peer host will cheat if it can, and its latency is never fair. So the server
holds the truth and the client predicts for smoothness.

## Architecture (reuses the existing event + persistence infra)

```
 Godot WASM client (served by wasm-fragment + DB federation)
   │  inputs (timestamped) on the session `input` channel  ▲ authoritative
   ▼                                                       │ snapshots/deltas on `world`
 BFF — authoritative tick loop (fixed Hz) per match
   ├─ sim core (ONE deterministic Rust crate; native server-side, same crate
   │           compiles to wasm for client-side PREDICTION) advances the truth
   ├─ Cassandra  = LIVE truth: per-tick/keyframe state + input/command log,
   │               cross-pod session lease + recovery (decision-017 store,
   │               PositionCacheService / CassandraSyncService), so ANY pod can
   │               serve or recover a match and admin can observe
   ├─ Kafka per-session topic (decision-021 SessionTopicManager) tees state +
   │               feeds KafkaSessionSubscriber / PlayerStateRecoveryService
   └─ Postgres   = DURABLE: match metadata, results, replays (the input log is
                   a deterministic replay), catalog games/chapters/federation
```

- **Inputs in:** clients send timestamped inputs on the existing `input` channel
  or control endpoints. The server queues them per tick behind a small
  input-delay buffer (lag compensation).
- **Truth in Cassandra:** the authoritative per-tick state (keyframes and the
  input/command log) goes to Cassandra, extending the decision-017 cross-pod
  store and the decision-021 per-session Kafka topics. A match survives a pod
  failure (the `PlayerStateRecoveryService` pattern) and any pod can answer.
- **Durable in Postgres:** match records, results, and final replays. The catalog
  rows (games/chapters) and the `federation_modules` remote registration
  (DB-hosted federation) make the game launchable.
- **Broadcast out:** authoritative snapshots and deltas fan out on the existing
  `world`/SSE channels and the per-session Kafka topic. Every client renders
  **server truth**. The client interpolates between snapshots and reconciles its
  prediction against them.
- **Serving:** **wasm-fragment** serves the Godot WASM bundle, and the
  `federation_modules` DB row registers the remote ("served by db and
  fragments"). The client ships no authority, only presentation and prediction.

## RTS specifics

- **Server-sim authoritative with a persisted command log.** The server runs the
  full deterministic simulation. Clients send *commands* (move/attack/build).
  The ordered command log is the Cassandra truth: authoritative, and a perfect
  replay. State is fixed-point/integer, so the server tick is bit-exact and a
  client prediction (same crate, wasm) matches.
- Deterministic **lockstep** is the scale fallback: clients run the sim, the
  server orders and relays commands, and Cassandra stores the command log. Note
  that this moves from "server holds live state" toward "server holds the
  command-log truth".

## FPS specifics

- **Authoritative server tick** (30–60 Hz). The server resolves hitscan and
  projectiles with **lag compensation** (rewind to the shooter's acknowledged
  tick, using the recent state stored in Cassandra). The client does
  **prediction + interpolation + reconciliation**. The server is the only
  authority on hits, damage, and position. The client cannot fabricate state.

## The one-sim principle (mirrors chess)

ONE deterministic Rust sim core per genre, compiled **native** for the server
(authoritative truth) and **wasm32** for the client (prediction). The Wave-3
duel sims (Tron/Bomberman/Asteroids) already prove deterministic tick sims.
The RTS/FPS cores extend that, run server-side and persisted.

## Phased plan

- **P1 — sim cores (buildable + testable now):** `hugmesh-rts-core` +
  `hugmesh-fps-core`. Deterministic, fixed-timestep, seeded Rust sims with a
  command/input API, an authoritative snapshot/keyframe, replay-from-log, and a
  thorough `cargo test` suite (including *determinism*: same seed+inputs ⇒
  identical state ⇒ server and client agree). This is the "server truth" engine.
- **P2 — BFF integration (verifiable via JUnit + boot):** the authoritative tick
  loop, input ingestion, **Cassandra keyframe/command-log persistence** (extend
  GameStateService and the decision-021 pipeline), SSE snapshot broadcast, and
  Postgres match records. Tests: tick determinism, persist→recover on another
  pod, input ordering/lag-buffer, isolation.
- **P3 — Godot WASM frontends (needs Godot/wasm toolchain):** RTS + FPS clients
  with prediction/reconciliation, served by wasm-fragment + `federation_modules`.
- **P4 — hardening:** lag-comp rewind, anti-cheat (server-only authority),
  replay viewer (from the command log), e2e.

## Catalog & serving wiring

Same as the engine waves: a Liquibase changeset adds the game and chapter rows
and, when the bundle exists, the `federation_modules` remote that points at the
wasm-fragment-served WASM. The only difference is server-side: these sessions
run an authoritative tick and persist truth, instead of relaying a host's state.
