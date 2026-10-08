# 007 — Cassandra position cache, keyed by `(character, chapter)`

**Status:** Adopted.

## Decision

Track player position **per (character, chapter) pair**, across three storage tiers: in-memory, Cassandra, Postgres. Cassandra is a write-through cache partitioned `(character_id, chapter_id)` with a 5-minute TTL. Postgres holds the durable copy and is written asynchronously.

## Why

The symptom: "movement across screens on the same player doesn't match." Two players on different pods of the same server sometimes saw stale positions for each other. Three problems contributed:

1. **One position row per character.** The legacy schema stored a single position per character. Walking cave → field → cave overwrote the cave coordinates with the field coordinates, and re-entry read back nonsense.
2. **No cross-pod cache.** Pod A wrote a movement event. Pod B's SSE subscriber read its stale in-memory copy until the next Postgres round-trip. That showed up as a 1–3 s lag, depending on tick.
3. **`autoCancel=true` on a related sink.** A separate fix; see [decision 003](003-autocancel-false.md).

## How to apply

- **Postgres** — Liquibase 027 (`027-per-level-positions.xml`) changed `player_positions` from one row per character to one row per `(character_id, level_id)`. Liquibase 028 then renamed `level_id` to `chapter_id`.
- **Cassandra** — `character_position_cache`, partitioned `(character_id, chapter_id)`, TTL 5 minutes. [`docker/cassandra-init.cql`](../../../docker/cassandra-init.cql) creates it.
- **`GameStateService`** — owns the in-memory map (`characterStates`, this pod's view). On every position change it calls `persistPositionAsync` (Postgres, durable) and `PositionCacheService.upsert` (Cassandra, cross-pod). The Cassandra write also refreshes a `chapter_membership` row (60 s TTL) that tells other pods who is in a chapter. A cold read goes Cassandra, then Postgres, then defaults (`loadState`). If a ClickHouse recovery service is wired and the call carries a game slug and session id, `hydrate` asks ClickHouse first.
- **`PositionCacheService`** — the Cassandra tier only: `upsert`, `findOne` and the `chapter_membership` helpers. It logs errors at debug level and swallows them, so a Cassandra outage never blocks movement.
- **Optional tier.** `GameStateService` takes `ObjectProvider<PositionCacheService>`, so the Cassandra tier may be absent. `CassandraBackedServicesAutoConfiguration` registers the service when a Cassandra template exists. Tests can skip Cassandra; production gets all three tiers.
- **Cassandra is recoverable from Postgres.** If the keyspace is wiped, reads fall through to Postgres. Each character's cache entry refills on its next position write. A read alone does not refill it.

## Trade-offs accepted

- **5-minute TTL.** A character idle in a chapter for more than 5 minutes depends on Postgres for the next read. The Postgres path is single-digit-millisecond, so the trade is acceptable.
- **Three-tier writes per position update.** About 3 ms per position write, against about 0.5 ms before. `ControlController` applies no 10 Hz throttle; the write rate follows the client's control events. Only the view samples are throttled to ~10 Hz, in the host's `ApiBridgeService`.
- **One more thing to reason about during incidents.** The [data architect overview](../guides/data-architect-overview.md) answers "who is authoritative": Postgres is the source of truth, Cassandra is recoverable from it, and in-memory state is recoverable from Cassandra.

## Reference

- [04 — Data model](../architecture/04-data-model.md#players-sessions-and-progression)
- [`backend/src/main/java/games/hugmesh/bff/service/PositionCacheService.java`](../../../backend/src/main/java/games/hugmesh/bff/service/PositionCacheService.java)
- [`backend/src/main/java/games/hugmesh/bff/service/GameStateService.java`](../../../backend/src/main/java/games/hugmesh/bff/service/GameStateService.java)
