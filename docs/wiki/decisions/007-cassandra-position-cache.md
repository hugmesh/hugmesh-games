# 007 — Cassandra position cache, keyed by `(character, chapter)`

**Status:** Adopted.

## Decision

Player position is tracked **per-(character, chapter) pair**, with three storage tiers: in-memory → Cassandra → Postgres. Cassandra is a write-through cache partitioned `(character_id, chapter_id)` with a 5-minute TTL. Postgres holds the durable copy and is written asynchronously.

## Why

Symptom that drove the change: "movement across screens on the same player doesn't match." Two players on different pods on the same server sometimes saw stale positions for each other. Three contributing problems:

1. **Single-row-per-character position** — the legacy schema had one position per character. Traversing cave → field → cave clobbered the cave coordinates with the field coordinates, then read them back as nonsense on re-entry.
2. **No cross-pod cache** — pod A wrote a movement event; pod B's SSE subscriber read its stale in-memory copy until the next Postgres round-trip. Visible as a 1–3 s lag depending on tick.
3. **`autoCancel=true` on a related sink** — separate fix; see [decision 003](003-autocancel-false.md).

## How to apply

- **Postgres** — Liquibase 027 (`027-per-level-positions.xml`) promoted `player_positions` from a single-row-per-character layout to one row per `(character_id, level_id)`. Liquibase 028 then renamed `level_id → chapter_id`.
- **Cassandra** — `character_position_cache` partitioned `(character_id, chapter_id)`, TTL 5 minutes. Bootstrapped by [`docker/cassandra-init.cql`](../../../docker/cassandra-init.cql).
- **`GameStateService`** — owns the in-memory map (`characterStates`, this pod's view). On every position change it calls `persistPositionAsync` (Postgres, durable) and `PositionCacheService.upsert` (Cassandra, cross-pod). The Cassandra write also refreshes a `chapter_membership` row (60 s TTL) that tells other pods who is in a chapter. A cold read goes Cassandra → Postgres → defaults (`loadState`). When a ClickHouse recovery service is wired and the call carries a game slug and session id, ClickHouse is consulted first (`hydrate`).
- **`PositionCacheService`** — the Cassandra tier only: `upsert`, `findOne`, and the `chapter_membership` helpers. Errors are logged at debug level and swallowed, so a Cassandra outage never blocks movement.
- **Optional tier.** `GameStateService` takes `ObjectProvider<PositionCacheService>`, so the Cassandra tier is optional. `CassandraBackedServicesAutoConfiguration` registers the service when a Cassandra template exists. Tests can skip Cassandra; production gets all three tiers.
- **Cassandra is recoverable from Postgres.** If the keyspace is wiped, reads fall through to Postgres. The cache refills on the next position write for each character; a read alone does not repopulate it.

## Trade-offs accepted

- **5-minute TTL.** A character idling on a chapter for >5 min relies on Postgres for the next read. The Postgres path is single-digit-millisecond; the trade-off is acceptable.
- **Three-tier writes per position update.** ~3 ms per position write vs ~0.5 ms before. `ControlController` applies no 10 Hz throttle; the write rate follows the client's control events. Only the view samples are throttled to ~10 Hz, in the host's `ApiBridgeService`.
- **One more thing to reason about during incidents.** The [data architect overview](../guides/data-architect-overview.md) states the "who's authoritative" answer: Postgres is the source of truth; Cassandra is recoverable from it; in-memory is recoverable from Cassandra.

## Reference

- [04 — Data model](../architecture/04-data-model.md#players-sessions-and-progression)
- [`backend/src/main/java/games/hugmesh/bff/service/PositionCacheService.java`](../../../backend/src/main/java/games/hugmesh/bff/service/PositionCacheService.java)
- [`backend/src/main/java/games/hugmesh/bff/service/GameStateService.java`](../../../backend/src/main/java/games/hugmesh/bff/service/GameStateService.java)
