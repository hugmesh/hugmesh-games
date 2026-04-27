# 007 — Cassandra position cache, keyed by `(character, chapter)`

**Status:** Adopted.

## Decision

Player position is tracked **per-(character, chapter) pair**, with a three-tier write-through cache: in-memory → Cassandra → Postgres. The Cassandra cache is partitioned `(character_id, chapter_id)` with a 5-minute TTL; Postgres holds the durable copy.

## Why

Symptom that drove the change: "movement across screens on the same player doesn't match." Two players on different pods on the same server sometimes saw stale positions for each other. Three contributing problems:

1. **Single-row-per-character position** — the legacy schema had one position per character. Traversing cave → field → cave clobbered the cave coordinates with the field coordinates, then read them back as nonsense on re-entry.
2. **No cross-pod cache** — pod A wrote a movement event; pod B's SSE subscriber read its stale in-memory copy until the next Postgres round-trip. Visible as a 1–3 s lag depending on tick.
3. **`autoCancel=true` on a related sink** — separate fix; see [decision 003](003-autocancel-false.md).

## How to apply

- **Postgres** — Liquibase 027 promoted `player_positions` from a single-row-per-character layout to one row per `(character_id, chapter_id)`. Liquibase 028 then renamed `level_id → chapter_id`.
- **Cassandra** — `character_position_cache` partitioned `(character_id, chapter_id)`, TTL 5 minutes. Bootstrapped by [`docker/cassandra-init.cql`](../../../docker/cassandra-init.cql).
- **`PositionCacheService`** — write-through cache. Every position update writes to in-memory map (this pod's view), Cassandra (cross-pod), and Postgres (durable). Reads prefer in-memory → Cassandra → Postgres.
- **`GameStateService`** — uses `ObjectProvider<PositionCacheService>` so the Cassandra tier is *optional*. Tests can skip Cassandra; production gets all three.
- **Cassandra is recoverable from Postgres + replay.** If the Cassandra keyspace is wiped, the cache rehydrates from Postgres on the next position read (with a one-round-trip warm-up cost).

## Trade-offs accepted

- **5-minute TTL.** A character idling on a chapter for >5 min relies on Postgres for the next read. The Postgres path is single-digit-millisecond; the trade-off is acceptable.
- **Three-tier writes per position update.** ~3 ms per position write vs ~0.5 ms before. Movement update rate is throttled to 10 Hz at the controller, so this is well within budget.
- **One more thing to reason about during incidents.** The runbook documents the "who's authoritative" answer: Postgres is the source of truth; Cassandra is recoverable from it; in-memory is recoverable from Cassandra.

## Reference

- [04 — Data model](../architecture/04-data-model.md#players-sessions-and-progression)
- [`backend/src/main/java/games/snapdragon/bff/service/PositionCacheService.java`](../../../backend/src/main/java/games/snapdragon/bff/service/PositionCacheService.java)
- [`backend/src/main/java/games/snapdragon/bff/service/GameStateService.java`](../../../backend/src/main/java/games/snapdragon/bff/service/GameStateService.java)
