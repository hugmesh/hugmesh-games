# Decision 021 — Streaming audit + telemetry pipeline (Kafka → ClickHouse)

**Status**: Accepted 2026-05-12. Foundation lands in `feature/kafka-clickhouse-pipeline`; cross-pod consumer integration deferred to a follow-up phase.

## Context

Three follow-ups from the post-docs plan converge on the same infrastructure:

- **F4 — Cassandra → Kafka → analytics warehouse.** Replace the nightly `CassandraSyncService` batch with a streaming pipeline so per-game analytics aren't 24 h stale.
- **F2 — Federated game telemetry.** Per-remote KPIs surfaced without BFF round-trips.
- **Cross-pod state sync.** Pod A's player state recoverable on Pod B without scraping SSE traffic.

Doing each in isolation produces three different storage surfaces. Doing them together on one pipeline keeps the platform's observability story coherent.

## Decision

**Kafka** (single broker in dev, managed in prod) carries every state-transition event + per-channel emission + lifecycle event. **ClickHouse** consumes via its built-in `Kafka` engine table → `MaterializedView` → `MergeTree` chain — the BFF doesn't ship a separate consumer service.

### Topics

| Topic | Producer | Purpose |
|---|---|---|
| `state_audit` | `GameAuditPublisher` | Session lifecycle (created / joined / left / closed). Replaces the analytics half of `CassandraSyncService`. |
| `game_events` | `GameEventPublisher` | Every SSE-channel emission (movement / world / character / scene / input / audio / view / wasm-console / world-native). Tee from `GameStateService`'s `Sinks.Many.tryEmitNext` sites (`world-native` tees from `emitInput`, added in the PR #117 cross-pod web-native relay fix). |
| `game_kpi` | (F2, follow-up) | Per-game KPIs emitted by federated game remotes. Schema ready; producer lands in Phase 7. |
| `session.<sessionId>.events` | per-session writes | One topic per multiplayer session; cross-pod consumers subscribe only to the sessions they serve. Created by `SessionTopicManager` on session-create, deleted on session-close. |

### ClickHouse tables

All under the `snapdragon` database (kept as-is pending a coordinated ClickHouse migration — see the PR description; see `docker/clickhouse-init/01-snapdragon-schema.sql`):

- `state_audit` (MergeTree) — 90-day TTL, partitioned by month, ordered by `(game_slug, occurred_at, event_id)`.
- `game_events` (MergeTree) — 30-day TTL, ordered by `(game_slug, channel, occurred_at, event_id)`.
- `game_kpi` (MergeTree) — 90-day TTL, ordered by `(game_slug, kpi_name, occurred_at)`.
- `player_state_current` (**ReplacingMergeTree**) — keyed by `(game_slug, session_id, character_id, channel)`; the `updated_at` column is the dedup signal. Cross-pod state recovery queries this view.

Each `_stream` Kafka-engine table feeds a `_mv` MaterializedView that writes into the durable MergeTree.

### Publisher wiring (BFF)

- `KafkaProducerConfig` — gated by `app.kafka.enabled` (default `false`). Builds an idempotent + acks=all + lz4-compressed producer.
- `GameAuditPublisher` — emit lifecycle events. Hooked into `GameSessionService.create / leave / close`.
- `GameEventPublisher` — emit SSE-channel events. Hooked into `GameStateService.applyControl` (movement first; remaining channels follow the same pattern in a follow-up).
- `SessionTopicManager` — `createForSession(sessionId)` on session-create, `deleteForSession(sessionId)` on session-close. AdminClient bean inside the same file.

Every publisher is injected as `Optional<...>` so the in-process gameplay path keeps working when Kafka is disabled. SSE remains the gameplay-driving path; Kafka is purely additive.

## Consequences

### Positive

- One streaming surface replaces the nightly batch. Analytics queries see fresh data.
- Cross-pod state recovery via ClickHouse `player_state_current` ReplacingMergeTree — same query shape regardless of which pod a player reconnects to.
- F2 telemetry inherits the existing pipeline; the Phase-7 PR adds one producer + one feature flag.
- Per-session topics mean a cross-pod consumer only reads the events it serves — no filtering a global firehose.

### Negative

- Two extra containers in dev (`kafka` + `zookeeper`) + one (`clickhouse`) when `app.kafka.enabled=true`. ~2 GB additional RAM in the dev stack. Gated behind the env flag so vanilla compose-up stays light.
- Topic-count growth — every session creates a topic. 100 concurrent sessions per game × 1 000 games = 100 000 topics. Cleanup on session-close mitigates orphans; broker capacity planning for prod is a separate item.
- Two analytics-relevant retentions to track: Cassandra `rate_limit_events` at 90 s, ClickHouse tables at 30 / 90 days.

### Neutral

- `CassandraSyncService` stays running as a backstop until streaming-pipeline stability is established under production load.

## Implementation

| File | Purpose |
|---|---|
| `docker-compose.yml` | `zookeeper` + `kafka` + `clickhouse` services + `kafka_data` + `clickhouse_data` volumes |
| `docker/clickhouse-init/01-snapdragon-schema.sql` | All ClickHouse tables + materialized views |
| `backend/pom.xml` | `spring-kafka` dependency |
| `backend/src/main/java/games/hugmesh/bff/audit/KafkaProducerConfig.java` | Producer factory + KafkaTemplate beans |
| `backend/src/main/java/games/hugmesh/bff/audit/GameAuditPublisher.java` | Lifecycle event publisher |
| `backend/src/main/java/games/hugmesh/bff/audit/GameEventPublisher.java` | Channel event publisher |
| `backend/src/main/java/games/hugmesh/bff/audit/SessionTopicManager.java` | Per-session topic manager + AdminClient |
| `backend/src/main/java/games/hugmesh/bff/service/GameSessionService.java` | Calls topic manager + audit publisher on lifecycle |
| `backend/src/main/java/games/hugmesh/bff/service/GameStateService.java` | Calls event publisher on `applyControl` movement emit |
| `backend/src/main/resources/application.yml` | `app.kafka.*` config block |

## Follow-ups

- ~~Tee the remaining `GameStateService.tryEmitNext` sites (world / character / scene / input / audio / view / wasm-console) into the event publisher.~~ — done in the Phase-7 PR. All 8 channels tee to the `game_events` topic.
- ~~F2 — federated game telemetry producer.~~ — done in the Phase-7 PR. `GameKpiPublisher` + `POST /api/telemetry/game/{slug}` ingest. Iframes call directly; existing `WebConfig` CORS allowlist covers the federated origins.
- ~~Cross-pod subscriber — read from the per-session topic into another pod's `SessionSinks`.~~ — done in the Phase-8a PR. `KafkaSessionSubscriber` consumes `game_events`, filters self-published via `pod_id`, dispatches per-channel to handlers registered in `GameStateService.@PostConstruct`. All 8 channels covered (movement / world / character / view → per-session + global; scene / input / audio / wasm-console → global only).
- ~~Cross-pod parity for the web-native `sendWorld()` relay.~~ — done in games PR #117. A 9th channel, `world-native`, carries the raw web-native world payload (distinct from the typed Godot `WorldState` `world` channel) so a peer pod's `KafkaSessionSubscriber` re-emits it onto that pod's `worldNative` sink; `GameStreamController` merges `worldNative` into the same client-facing `world` SSE event name, so the guest bridge needs no change. See [`InputEventController`](../../../backend/src/main/java/games/hugmesh/bff/controller/InputEventController.java) and [`GameStateService.emitInput`](../../../backend/src/main/java/games/hugmesh/bff/service/GameStateService.java).
- ~~BFF read-path against `player_state_current` for session-reconnect recovery.~~ — done in the Phase-8b PR. `PlayerStateRecoveryService` queries `snapdragon.player_state_current FINAL` via the ClickHouse HTTP interface (WebClient). New `hydrate(characterId, chapterId, gameSlug, sessionId)` overload on `GameStateService` consults the recovery service before the legacy Cassandra → Postgres → defaults chain. `Mono.empty()` is the expected miss path; SSE / gameplay never blocks on this.
- Retire `CassandraSyncService` once the streaming pipeline has demonstrated production stability.
