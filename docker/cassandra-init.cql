-- Cassandra keyspace + tables for cross-pod state sync.
-- Reactive Spring Data Cassandra binds to this from the api container.
--
-- Why Cassandra alongside Postgres?
--   • Postgres holds authoritative game state (characters, levels, quests).
--   • Cassandra holds high-write ephemeral data: per-pod session leases,
--     active-controller arbitration, the aggregate counters the admin
--     /api/admin/stream serves. Linear-scalable across pods + tunable
--     consistency for the "good-enough" reads admin dashboards need.

CREATE KEYSPACE IF NOT EXISTS threewizards
  WITH REPLICATION = { 'class': 'SimpleStrategy', 'replication_factor': 1 };

USE threewizards;

-- One row per active session across the entire fleet of api pods.
-- TTL'd at 60 s; pods refresh their own rows every 15 s as a heartbeat.
-- A stale row that drops off TTL means that pod is gone.
CREATE TABLE IF NOT EXISTS active_sessions (
    casdoor_id     text,
    session_token  text,
    character_id   bigint,
    pod_id         text,
    is_controller  boolean,
    connected_at   timestamp,
    last_ping      timestamp,
    PRIMARY KEY (casdoor_id)
) WITH default_time_to_live = 60;

-- Cluster-wide controller arbitration. Single partition; LWT writes ensure
-- only one pod thinks it owns the controller for a given character.
CREATE TABLE IF NOT EXISTS controller_lease (
    character_id   bigint,
    casdoor_id     text,
    pod_id         text,
    granted_at     timestamp,
    PRIMARY KEY (character_id)
) WITH default_time_to_live = 30;

-- Counters for the admin live stream — incremented on session events from
-- any pod, read aggregated at admin-stream tick time.
CREATE TABLE IF NOT EXISTS aggregate_counters (
    metric         text,
    pod_id         text,
    value          counter,
    PRIMARY KEY (metric, pod_id)
);

-- Per-pod heartbeat so admins can see how many api pods are online.
CREATE TABLE IF NOT EXISTS pod_heartbeats (
    pod_id         text,
    last_ping      timestamp,
    started_at     timestamp,
    version        text,
    PRIMARY KEY (pod_id)
) WITH default_time_to_live = 30;

-- Per-(character, level) position cache. Postgres remains the source of
-- truth (player_positions) for durability + analytics; this Cassandra
-- table is the cross-pod read cache that lets pod B see a position
-- updated on pod A within the round-trip of the SSE fan-out.
--
-- Composite partition key (character_id, level_id) so a player walking
-- cave → field → cave finds their cave coordinates exactly where they
-- left them (the prior single-row schema overwrote across levels).
--
-- 5-minute TTL so a long-idle character doesn't pin a row that the
-- player abandoned; durable storage in Postgres backs the cache when
-- it expires.
CREATE TABLE IF NOT EXISTS character_position_cache (
    character_id   bigint,
    level_id       bigint,
    pos_x          double,
    pos_y          double,
    pos_z          double,
    rotation_y     double,
    last_action    text,
    last_pressed   boolean,
    pod_id         text,
    updated_at     timestamp,
    tick           bigint,
    PRIMARY KEY ((character_id, level_id))
) WITH default_time_to_live = 300;

-- Per-level membership index: which characters are currently present
-- in a given level. Used by GameStateService.emitWorldState to scope
-- broadcasts so a player in cave doesn't see a player in field. The
-- TTL is shorter than the position cache because membership is more
-- volatile (a character rapidly traversing levels needs prompt
-- invalidation), and a single missed write reverses on the next
-- movement tick.
CREATE TABLE IF NOT EXISTS level_membership (
    level_id       bigint,
    character_id   bigint,
    pod_id         text,
    last_seen      timestamp,
    PRIMARY KEY (level_id, character_id)
) WITH default_time_to_live = 60;
