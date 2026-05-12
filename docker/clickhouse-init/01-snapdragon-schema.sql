-- Snapdragon analytics warehouse bootstrap (decision-021).
--
-- The architecture: BFF (and any other producer) → Kafka topic →
-- ClickHouse `Kafka` engine table (auto-pulls) → MaterializedView →
-- MergeTree-backed durable storage that reads cheaply. The Kafka
-- engine acts as a streaming consumer; the MV moves rows from the
-- streaming table (which is destructively consumed) into the
-- durable analytics table.
--
-- Two topics today:
--   state_audit  — BFF state-transition audit log. Today
--                  CassandraSyncService runs a nightly batch into
--                  Postgres counters; this pipeline replaces it
--                  with streaming, eventually retiring the batch.
--   game_kpi     — Per-game KPI events emitted by federated game
--                  remotes (F2; lands as a follow-up PR on top of
--                  this infrastructure).
--
-- Schema design notes:
--   • partitioned by month so 90-day retention is a cheap DROP
--     PARTITION rather than a row-by-row scan;
--   • ORDER BY (game_slug, occurred_at) so per-game range reads
--     (the common admin-dashboard query) hit a contiguous block;
--   • timestamps in UTC; the BFF stamps occurred_at at emit time.

CREATE DATABASE IF NOT EXISTS snapdragon;

-- ── state_audit pipeline ──────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS snapdragon.state_audit_stream
(
    event_id        String,
    occurred_at     DateTime64(3, 'UTC'),
    game_slug       LowCardinality(String),
    session_id      String,
    casdoor_id      String,
    event_type      LowCardinality(String),
    payload_json    String
)
ENGINE = Kafka
SETTINGS
    kafka_broker_list   = 'kafka:9092',
    kafka_topic_list    = 'state_audit',
    kafka_group_name    = 'clickhouse-state-audit',
    kafka_format        = 'JSONEachRow',
    kafka_num_consumers = 1,
    kafka_skip_broken_messages = 100;

CREATE TABLE IF NOT EXISTS snapdragon.state_audit
(
    event_id        String,
    occurred_at     DateTime64(3, 'UTC'),
    game_slug       LowCardinality(String),
    session_id      String,
    casdoor_id      String,
    event_type      LowCardinality(String),
    payload_json    String,
    ingested_at     DateTime64(3, 'UTC') DEFAULT now64()
)
ENGINE = MergeTree
PARTITION BY toYYYYMM(occurred_at)
ORDER BY (game_slug, occurred_at, event_id)
TTL toDateTime(occurred_at) + INTERVAL 90 DAY;

CREATE MATERIALIZED VIEW IF NOT EXISTS snapdragon.state_audit_mv
TO snapdragon.state_audit
AS SELECT
    event_id,
    occurred_at,
    game_slug,
    session_id,
    casdoor_id,
    event_type,
    payload_json
FROM snapdragon.state_audit_stream;

-- ── game_kpi pipeline (consumed by F2 in a follow-up PR) ──────────────

CREATE TABLE IF NOT EXISTS snapdragon.game_kpi_stream
(
    event_id        String,
    occurred_at     DateTime64(3, 'UTC'),
    game_slug       LowCardinality(String),
    casdoor_id      String,
    kpi_name        LowCardinality(String),
    kpi_value       Float64,
    payload_json    String
)
ENGINE = Kafka
SETTINGS
    kafka_broker_list   = 'kafka:9092',
    kafka_topic_list    = 'game_kpi',
    kafka_group_name    = 'clickhouse-game-kpi',
    kafka_format        = 'JSONEachRow',
    kafka_num_consumers = 1,
    kafka_skip_broken_messages = 100;

CREATE TABLE IF NOT EXISTS snapdragon.game_kpi
(
    event_id        String,
    occurred_at     DateTime64(3, 'UTC'),
    game_slug       LowCardinality(String),
    casdoor_id      String,
    kpi_name        LowCardinality(String),
    kpi_value       Float64,
    payload_json    String,
    ingested_at     DateTime64(3, 'UTC') DEFAULT now64()
)
ENGINE = MergeTree
PARTITION BY toYYYYMM(occurred_at)
ORDER BY (game_slug, kpi_name, occurred_at)
TTL toDateTime(occurred_at) + INTERVAL 90 DAY;

CREATE MATERIALIZED VIEW IF NOT EXISTS snapdragon.game_kpi_mv
TO snapdragon.game_kpi
AS SELECT
    event_id,
    occurred_at,
    game_slug,
    casdoor_id,
    kpi_name,
    kpi_value,
    payload_json
FROM snapdragon.game_kpi_stream;

-- ── game_events pipeline (channel-discriminated tee from BFF sinks) ──
-- Every SSE-channel emission inside GameStateService.tryEmitNext is
-- also published here so ClickHouse can answer per-channel volume +
-- latency aggregates without scraping SSE traffic. SSE remains the
-- gameplay-driving path; this stream is purely additive.

CREATE TABLE IF NOT EXISTS snapdragon.game_events_stream
(
    event_id        String,
    occurred_at     DateTime64(3, 'UTC'),
    channel         LowCardinality(String),
    game_slug       LowCardinality(String),
    session_id      String,
    casdoor_id      String,
    payload_json    String
)
ENGINE = Kafka
SETTINGS
    kafka_broker_list   = 'kafka:9092',
    kafka_topic_list    = 'game_events',
    kafka_group_name    = 'clickhouse-game-events',
    kafka_format        = 'JSONEachRow',
    kafka_num_consumers = 2,
    kafka_skip_broken_messages = 1000;

CREATE TABLE IF NOT EXISTS snapdragon.game_events
(
    event_id        String,
    occurred_at     DateTime64(3, 'UTC'),
    channel         LowCardinality(String),
    game_slug       LowCardinality(String),
    session_id      String,
    casdoor_id      String,
    payload_json    String,
    ingested_at     DateTime64(3, 'UTC') DEFAULT now64()
)
ENGINE = MergeTree
PARTITION BY toYYYYMM(occurred_at)
ORDER BY (game_slug, channel, occurred_at, event_id)
TTL toDateTime(occurred_at) + INTERVAL 30 DAY;

CREATE MATERIALIZED VIEW IF NOT EXISTS snapdragon.game_events_mv
TO snapdragon.game_events
AS SELECT
    event_id,
    occurred_at,
    channel,
    game_slug,
    session_id,
    casdoor_id,
    payload_json
FROM snapdragon.game_events_stream;

-- ── player_state_current (cross-pod sync via ClickHouse) ─────────────
-- A ReplacingMergeTree keyed by (game_slug, session_id, character_id)
-- collapses the game_events stream into the most-recent state row per
-- player. When a player reconnects via a different pod, that pod
-- queries this view to recover position + scene + character snapshot
-- instead of waiting for an SSE replay from the original pod.
--
-- The materialized view filters game_events for the channels that
-- carry recoverable state (movement, character, scene) and projects
-- the relevant fields out of payload_json. The `updated_at` column
-- gives ReplacingMergeTree the dedup signal — newer wins. FINAL on the
-- read picks up un-merged duplicates between background merges.

CREATE TABLE IF NOT EXISTS snapdragon.player_state_current
(
    game_slug      LowCardinality(String),
    session_id     String,
    character_id   String,
    casdoor_id     String,
    channel        LowCardinality(String),
    updated_at     DateTime64(3, 'UTC'),
    payload_json   String
)
ENGINE = ReplacingMergeTree(updated_at)
PARTITION BY (game_slug)
ORDER BY (game_slug, session_id, character_id, channel)
TTL toDateTime(updated_at) + INTERVAL 7 DAY;

CREATE MATERIALIZED VIEW IF NOT EXISTS snapdragon.player_state_current_mv
TO snapdragon.player_state_current
AS SELECT
    game_slug,
    session_id,
    JSONExtractString(payload_json, 'characterId') AS character_id,
    casdoor_id,
    channel,
    occurred_at AS updated_at,
    payload_json
FROM snapdragon.game_events
WHERE channel IN ('movement', 'character', 'scene')
  AND JSONExtractString(payload_json, 'characterId') <> '';

-- ── convenience views for the admin dashboard ────────────────────────

CREATE OR REPLACE VIEW snapdragon.recent_state_audit AS
SELECT game_slug, event_type, count() AS n
FROM snapdragon.state_audit
WHERE occurred_at >= now() - INTERVAL 24 HOUR
GROUP BY game_slug, event_type
ORDER BY n DESC
LIMIT 100;

CREATE OR REPLACE VIEW snapdragon.kpi_rolling_avg_24h AS
SELECT game_slug, kpi_name, avg(kpi_value) AS avg_24h, count() AS samples
FROM snapdragon.game_kpi
WHERE occurred_at >= now() - INTERVAL 24 HOUR
GROUP BY game_slug, kpi_name
ORDER BY game_slug, kpi_name;

CREATE OR REPLACE VIEW snapdragon.events_per_channel_1h AS
SELECT game_slug, channel, count() AS n
FROM snapdragon.game_events
WHERE occurred_at >= now() - INTERVAL 1 HOUR
GROUP BY game_slug, channel
ORDER BY n DESC;
