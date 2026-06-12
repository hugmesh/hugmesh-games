# Server-authoritative real-time (FPS / RTS) — server truth in Cassandra + DB

Design record for the timing-critical genres. Where the board/puzzle/duel games
(Waves 1–3) are **host-authoritative** (the host browser runs the engine, the
server only streams), **FPS and RTS are SERVER-authoritative**: the event
architecture keeps **server-level truth in Cassandra + Postgres**, and the
frontends are **Godot-created WASM games served by the DB + fragments**.

## Why two models

| | Waves 1–3 (board / puzzle / duel) | **Waves 4–5 (FPS / RTS)** |
|---|---|---|
| Authority | host browser (WASM engine) | **the server (BFF authoritative tick)** |
| Source of truth | host's engine state | **Cassandra (live) + Postgres (durable)** |
| Server role | relay `input`/`world` events | **run the sim, persist truth, broadcast snapshots** |
| Client | renders host state, re-validates | **sends inputs; predicts + reconciles; renders server snapshots** |
| Why | low stakes, simple, cheap | **timing, fairness, lag-comp, anti-cheat** |

A timing-critical game cannot trust a peer host (cheating, unfair latency), so
the server holds truth and the client predicts for smoothness.

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
  / control endpoints; the server queues them per tick behind a small input-delay
  buffer (lag compensation).
- **Truth in Cassandra:** the authoritative per-tick state (keyframes + the
  input/command log) is written to Cassandra — extending the decision-017
  cross-pod store and decision-021 per-session Kafka topics — so a match survives
  a pod failure (`PlayerStateRecoveryService` pattern) and any pod can answer.
- **Durable in Postgres:** match records, results, and final replays; the catalog
  rows (games/chapters) + `federation_modules` remote registration (DB-hosted
  federation) make the game launchable.
- **Broadcast out:** authoritative snapshots/deltas fan out on the existing
  `world`/SSE channels + the per-session Kafka topic, so every client renders
  **server truth**; the client interpolates between snapshots and reconciles its
  prediction against them.
- **Serving:** the Godot WASM bundle is served by **wasm-fragment**; the
  `federation_modules` DB row registers the remote ("served by db and
  fragments"). The client ships no authority — only presentation + prediction.

## RTS specifics

- **Server-sim authoritative with a persisted command log.** The server runs the
  full deterministic simulation; clients send *commands* (move/attack/build).
  The ordered command log is the Cassandra truth (authoritative AND a perfect
  replay). Fixed-point/integer state so the server tick is bit-exact and a client
  prediction (same crate, wasm) matches.
- Deterministic **lockstep** (clients run the sim, server orders+relays commands,
  Cassandra stores the command log) is the scale fallback — note it diverges from
  "server holds live state" toward "server holds the command-log truth".

## FPS specifics

- **Authoritative server tick** (30–60 Hz). Hitscan/projectiles resolved
  server-side with **lag compensation** (rewind to the shooter's acknowledged
  tick using the Cassandra-stored recent state). Client-side **prediction +
  interpolation + reconciliation**. The server is the only authority on hits,
  damage, and position — the client cannot fabricate state.

## The one-sim principle (mirrors chess)

ONE deterministic Rust sim core per genre: compiled **native** for the server
(authoritative truth) and **wasm32** for the client (prediction). The Wave-3
duel sims (Tron/Bomberman/Asteroids) already prove deterministic tick sims;
the RTS/FPS cores extend that, now run server-side and persisted.

## Phased plan

- **P1 — sim cores (buildable + testable now):** `snapdragon-rts-core` +
  `snapdragon-fps-core` — deterministic, fixed-timestep, seeded Rust sims with a
  command/input API, an authoritative snapshot/keyframe + replay-from-log, and a
  thorough `cargo test` suite (incl. *determinism*: same seed+inputs ⇒ identical
  state ⇒ server and client agree). The "server truth" engine.
- **P2 — BFF integration (verifiable via JUnit + boot):** the authoritative tick
  loop + input ingestion + **Cassandra keyframe/command-log persistence**
  (extend GameStateService + the decision-021 pipeline) + SSE snapshot broadcast
  + Postgres match records. Tests: tick determinism, persist→recover on another
  pod, input ordering/lag-buffer, isolation.
- **P3 — Godot WASM frontends (needs Godot/wasm toolchain):** RTS + FPS clients
  with prediction/reconciliation, served by wasm-fragment + `federation_modules`.
- **P4 — hardening:** lag-comp rewind, anti-cheat (server-only authority),
  replay viewer (from the command log), e2e.

## Catalog & serving wiring

Same as the engine waves: a Liquibase changeset adds the game + chapter rows and
(when the bundle exists) the `federation_modules` remote pointing at the
wasm-fragment-served WASM. The difference is purely server-side: these sessions
run an authoritative tick + persist truth, rather than relaying a host's state.
