# Decision 021 — Streaming audit + telemetry pipeline (Kafka → ClickHouse)

**Status**: Accepted 2026-05-12. Foundation landed in `feature/kafka-clickhouse-pipeline`. Producers, the cross-pod subscriber and the ClickHouse recovery service have since landed. Two parts are not live: nothing uses the per-session topics, and nothing calls the ClickHouse recovery path (see Follow-ups).

## Context

Three follow-ups from the post-docs plan need the same infrastructure:

- **F4 — Cassandra → Kafka → analytics warehouse.** Replace the nightly `CassandraSyncService` batch with a stream, so per-game analytics are not 24 h stale.
- **F2 — Federated game telemetry.** Show per-remote KPIs without BFF round-trips.
- **Cross-pod state sync.** Make Pod A's player state recoverable on Pod B without scraping SSE traffic.

Built separately, they would give three storage surfaces. One pipeline keeps observability in one place.

## Decision

**Kafka** (one broker in dev, managed in prod) carries every state-transition event, per-channel emission and lifecycle event. **ClickHouse** consumes it through its built-in `Kafka` engine table → `MaterializedView` → `MergeTree` chain, so the BFF ships no separate consumer service.

### Topics

| Topic | Producer | Purpose |
|---|---|---|
| `state_audit` | `GameAuditPublisher` | Session lifecycle (`session.created` / `session.left` / `session.closed` / `session.paused` / `session.resumed`; there is no `joined` event). Replaces the analytics half of `CassandraSyncService`. |
| `game_events` | `GameEventPublisher` | Every SSE-channel emission (movement / world / character / scene / input / audio / view / wasm-console / world-native). Tee from `GameStateService`'s `Sinks.Many.tryEmitNext` sites (`world-native` tees from `emitInput`, added in the PR #117 cross-pod web-native relay fix). |
| `game_kpi` | `GameKpiPublisher` | Per-game KPIs emitted by federated game remotes (F2), through `POST /api/telemetry/game/{slug}`. |
| `session.<sessionId>.events` | none (see below) | One topic per multiplayer session, created by `SessionTopicManager` on session-create and deleted on session-close (1 partition, 6 h retention by default). Nothing writes to it and nothing reads from it: `topicFor` is called only inside `SessionTopicManager`, `GameEventPublisher` writes every channel to `game_events`, and `KafkaSessionSubscriber` reads only `game_events`. |

### ClickHouse tables

All tables live in the `hugmesh` database (see `docker/clickhouse-init/01-hugmesh-schema.sql`). Existing deployments carry the pre-rebrand database name: run `RENAME DATABASE`, or override `APP_CLICKHOUSE_DATABASE`.

- `state_audit` (MergeTree) — 90-day TTL, partitioned by month, ordered by `(game_slug, occurred_at, event_id)`.
- `game_events` (MergeTree) — 30-day TTL, ordered by `(game_slug, channel, occurred_at, event_id)`.
- `game_kpi` (MergeTree) — 90-day TTL, ordered by `(game_slug, kpi_name, occurred_at)`.
- `player_state_current` (**ReplacingMergeTree**) — keyed by `(game_slug, session_id, character_id, channel)`; the `updated_at` column is the dedup signal; 7-day TTL, partitioned by `game_slug`. `PlayerStateRecoveryService` queries this table.

Each `_stream` Kafka-engine table feeds a `_mv` MaterializedView, which writes into the durable MergeTree.

### Publisher wiring (BFF)

- `KafkaProducerConfig` — gated by `app.kafka.enabled` (default `false`). Builds an idempotent, `acks=all`, lz4-compressed producer.
- `GameAuditPublisher` — emits lifecycle events. Hooked into `GameSessionService` (create, leave, close, pause, resume).
- `GameEventPublisher` — emits SSE-channel events. Hooked into `GameStateService`, which publishes all nine channels.
- `SessionTopicManager` — `createForSession(sessionId)` on session-create, `deleteForSession(sessionId)` on session-close. AdminClient bean inside the same file.

Every publisher is injected as `Optional<...>`, so gameplay keeps working in-process when Kafka is off. SSE still drives gameplay. Kafka only adds to it.

## Consequences

### Positive

- One stream replaces the nightly batch. Analytics queries see fresh data.
- Cross-pod state recovery via the ClickHouse `player_state_current` ReplacingMergeTree table is built. It is not wired in yet: see the Phase-8b follow-up.
- F2 telemetry reuses the pipeline; the Phase-7 PR added one producer and one feature flag.
- Per-session topics were meant to let a cross-pod consumer read only the events it serves. That did not happen: the subscriber still reads the global `game_events` topic and filters on `pod_id`.

### Negative

- Three extra containers in dev (`zookeeper`, `kafka`, `clickhouse`), about 2 GB more RAM. `app.kafka.enabled` (default `false`) gates only the BFF side. `docker-compose.yml` gives the three services no profile, so a plain `docker compose up -d` starts them regardless, and it does not pass `APP_KAFKA_ENABLED` to the `api` service. The comment above the `zookeeper` service claims they are opt-in via `--profile analytics`. No such profile exists.
- Topic count grows: every session creates a topic. 100 concurrent sessions per game × 1 000 games = 100 000 topics. Cleanup on session-close limits orphans. Broker capacity planning for prod is a separate item.
- Two sets of retention to track: Cassandra `rate_limit_events` at 90 s, ClickHouse tables at 30 / 90 days.

### Neutral

- `CassandraSyncService` keeps running as a backstop until the stream has proved itself under production load.

## Implementation

| File | Purpose |
|---|---|
| `docker-compose.yml` | `zookeeper` + `kafka` + `clickhouse` services + `kafka_data` + `clickhouse_data` volumes |
| `docker/clickhouse-init/01-hugmesh-schema.sql` | All ClickHouse tables + materialized views |
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
- ~~F2 — federated game telemetry producer.~~ — done in the Phase-7 PR. `GameKpiPublisher` plus the `POST /api/telemetry/game/{slug}` ingest. Iframes call it directly, and the existing `WebConfig` CORS allowlist covers the federated origins.
- ~~Cross-pod subscriber — read events into another pod's `SessionSinks`.~~ — done in the Phase-8a PR. `KafkaSessionSubscriber` consumes `game_events`, filters self-published via `pod_id`, dispatches per-channel to handlers registered in `GameStateService.@PostConstruct`. All 8 channels covered (movement / world / character / view → per-session + global; scene / input / audio / wasm-console → global only). It reads the global topic, not the per-session ones.
- ~~Cross-pod parity for the web-native `sendWorld()` relay.~~ — done in games PR #117. A 9th channel, `world-native`, carries the raw web-native world payload (distinct from the typed Godot `WorldState` `world` channel). A peer pod's `KafkaSessionSubscriber` re-emits it onto that pod's `worldNative` sink. `GameStreamController` merges `worldNative` into the same client-facing `world` SSE event name, so the guest bridge needs no change. See [`InputEventController`](../../../backend/src/main/java/games/hugmesh/bff/controller/InputEventController.java) and [`GameStateService.emitInput`](../../../backend/src/main/java/games/hugmesh/bff/service/GameStateService.java).
- BFF read-path against `player_state_current` for session-reconnect recovery — built in the Phase-8b PR, but not in use. `PlayerStateRecoveryService` queries `hugmesh.player_state_current FINAL` via the ClickHouse HTTP interface (WebClient); it exists only when `app.clickhouse.enabled=true` (default `false`). The new `hydrate(characterId, chapterId, gameSlug, sessionId)` overload on `GameStateService` consults it before the legacy Cassandra → Postgres → defaults chain. Its only production caller, `SessionController`, uses the two-argument `hydrate(characterId, chapterId)`, which passes null for `gameSlug` and `sessionId` and skips recovery. `Mono.empty()` is the expected miss path; SSE / gameplay never blocks on this.
- Retire `CassandraSyncService` once the streaming pipeline has demonstrated production stability.
