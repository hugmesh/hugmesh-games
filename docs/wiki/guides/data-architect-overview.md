# Data architect overview

> A typeset edition lives in [`docs/publishing/userguides/data-architect-overview/guide.tex`](../../publishing/userguides/data-architect-overview/guide.tex). The two are edited separately and can differ.

Where data lives, how it flows, and how it stays consistent. The enterprise summary.

## Executive summary

A multi-game browser portal. One Spring Boot BFF brokers reads and writes for many concurrent games and players. **Two stores, divided by job:**

- **PostgreSQL** holds the canonical relational state (catalog, players, federation, access, uploads).
- **Cassandra** is a high-write log + cache (movement events, audit log, short-lived sessions, position cache).

Casdoor is the OAuth2 / OIDC identity provider. The JWT subject is the trust boundary.

All persistence runs inside one compose stack today. Cloud-relocation swap-points are documented for a later move without code changes.

## Domains and clusters

| Cluster | Tables |
|---|---|
| Catalog | `games`, `chapters`, `scene_elements`, `chapter_assets`, `chapter_prerequisites` |
| Federation & access | `federation_modules`, `game_access`, `game_launches`, `game_uploads` |
| Players | `player_characters`, `character_skills`, `character_skill_slots`, `character_equipment`, `character_quests`, `character_items`, `player_positions`, `player_sessions` |
| Multiplayer & RBAC | `game_sessions`, `session_members`, `mod_game_scopes`, `publisher_game_scopes`, `system_settings` |
| Generalised input | `input_event_kinds`, `input_event_types`, `game_event_support`, `input_events` |
| Identity | Casdoor's tenant in Postgres (separate database within the same instance) |
| Audit & cache | Cassandra keyspace — movement events, position cache, state-transition audit log |
| Moderation | `admin_actions` (kick / ban / unban audit ledger) |

## Data lifecycle

| Tier | Latency window | Where | Loss model |
|---|---|---|---|
| **Hot** | ≤ 60 s | In-process `Sinks.Many` SSE merge | Lost on restart by design; clients re-subscribe. |
| **Warm** | minutes | Cassandra movement events + `character_position_cache` | Aged out by TTL (5 min on the position cache). |
| **Cold** | durable | Postgres rows for catalog, characters, quests, uploads, audit summary | Source of truth. |

Hot is recoverable from Cassandra. Cassandra is recoverable from Postgres + replay. Postgres is the source of truth.

## Cross-cutting concerns

- **Auditability** — every state transition emitted by `GameStateService` is written to the Cassandra audit log. Admin moderation also writes to `admin_actions` in Postgres.
- **Reproducibility** — `game_launches.version_dir` snapshots `games.current_version_dir` at mint, so an in-flight launch keeps reading from its own install. See [decision 005](../decisions/005-launch-uid-versioning.md).
- **Reversibility** — `game_uploads.superseded_at` lets an administrator pin a prior version. No admin UI does this; it is a SQL update (see [upload-and-rollback-game](../howto/upload-and-rollback-game.md), option B).
- **Consistency** — `register.sql` runs inside the upload R2DBC transaction, and a failure rolls everything back. The transaction is *not* atomic with the on-disk move; recovery is in the [maintainer runbook](maintainer-runbook.md), Runbook 5.
- **Observability** — scheduled jobs (`GameLaunchSweeper`, `GameUploadPruner`, `CassandraSyncService`) emit one line per run for log scraping. The admin dashboard SSE streams `pod_heartbeats` rows from Cassandra.

## Identity, regulatory, DR

- **PII surface:** Casdoor user email + display name, stored in Casdoor's Postgres and **not duplicated**.
- **Auth tokens:** issued by Casdoor, verified at the BFF, **never logged**.
- **Audit log:** stores `casdoor_user_id`; combine with Casdoor's tenant DB for traceback.
- **RPO** 24 hours (nightly Postgres + uploads-dir snapshot).
- **RTO** ≈30 minutes (compose up against a restored VM).
- **Right-to-erasure:** delete the Casdoor user, cascade to `player_characters`, NULL out `casdoor_user_id` in audit rows.

## Cloud relocation swap-points

| Service | Swap | Code change |
|---|---|---|
| Postgres → managed RDS / Cloud SQL | `spring.r2dbc.url` + `spring.datasource.url` | None |
| Postgres read replica | `app.r2dbc.replica.url` → managed replica (Aurora reader, Cloud SQL replica) | None — `R2dbcReadReplicaConfig` aliases primary when empty |
| Cassandra → managed Astra / Scylla | `spring.cassandra.contact-points` | None |
| Casdoor → managed identity (Auth0 / Cognito / Entra) | `/.well-known/jwks` URL + role-mapping | Casdoor-specific role mapping |
| Object-storage uploads | `app.uploads.storage=s3` + `APP_UPLOADS_S3_*` knobs (AWS, Cloudflare R2, Backblaze B2, MinIO) | None — `UploadStorage` strategy |
| Federation manifest URL | Already DB-backed | None |
| Streaming audit / telemetry | `app.kafka.enabled=true` + Kafka + ClickHouse bootstrap URLs | None — gated producers + consumer |
| BFF perimeter | `hugmesh-gateway` in front; routes `/api/*` to BFF | None — gateway carries primary rate-limit + revocation; BFF mirrors as defence-in-depth |

## Shipped — was "future" in earlier versions of this guide

These items are built. The data model needed no rewrite for any of them; each was additive.

- **Streaming audit + telemetry pipeline.** Kafka topics `state_audit`, `game_events`, `game_kpi`, `session.<id>.events` are produced by `GameAuditPublisher` / `GameEventPublisher` / `GameKpiPublisher`. ClickHouse consumes via Kafka-engine + materialised views. Gated by `app.kafka.enabled` (default `false`). A cross-pod consumer + `PlayerStateRecoveryService` close the F4 fan-out loop. See [decision 021](../decisions/021-kafka-clickhouse-streaming-pipeline.md).
- **Postgres read replica routing.** `R2dbcReadReplicaConfig` provides `@Qualifier("replica")` beans; the per-service migration pattern is in [decision 022](../decisions/022-postgres-read-replica.md). The compose overlay is `docker-compose.replica.yml`.
- **Object-storage-backed uploads.** The `UploadStorage` strategy offers `filesystem` (default) and `s3` (compose ships MinIO; prod points at AWS / R2 / B2). `app.uploads.s3.presigned-reads=true` makes `AssetController` answer with a 302 to a presigned URL, which keeps the WASM hot path off the BFF. A Resilience4j circuit breaker (`s3` instance) wraps the strategy. See [decision 020](../decisions/020-object-storage-uploads.md).
- **Cross-pod rate-limit storage.** `DistributedRateLimitService` runs the cluster-wide augment on Cassandra `rate_limit_events` (90 s TTL); disable it with `app.rate-limit.distributed.enabled=false`. The `hugmesh-gateway` is the primary enforcement point; the BFF's `RateLimitFilter` is defence-in-depth.
- **Per-role accounts schema.** `accounts.players` / `accounts.producers` / `accounts.mods` (Liquibase 041). Players are populated lazily; producers and mods are populated when a `producer_contacts` application is APPROVED.

## Still future

- An AWS S3 + Cloudflare R2 + Backblaze B2 acceptance matrix. The `s3` strategy works against all three; the matrix would record pass/fail per provider for edge cases like CORS and pre-signed URL semantics.
- Retirement of `CassandraSyncService` once the streaming pipeline has proved itself in production. The audit and heartbeat data are double-written today.
- Per-route CSP tightening (`/login` doesn't need `worker-src blob:`, `/admin` doesn't need `frame-src 'self'`), and strict CSP via the three-step plan in [decision 023](../decisions/023-content-security-policy.md).
