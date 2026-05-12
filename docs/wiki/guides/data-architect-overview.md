# Data architect overview

> Mirror of [`docs/publishing/userguides/data-architect-overview/guide.tex`](../../publishing/userguides/data-architect-overview/guide.tex).

Where data lives, how it flows, and how it stays consistent. The enterprise summary.

## Executive summary

A multi-game browser portal. One Spring Boot BFF brokers reads and writes for many concurrent games and players. **Two stores, by responsibility:**

- **PostgreSQL** is the canonical relational state (catalog, players, federation, access, uploads).
- **Cassandra** is a high-write log + cache (movement events, audit log, short-lived sessions, position cache).

Casdoor is the OAuth2 / OIDC identity provider; the JWT subject is the trust boundary.

All persistence is inside one compose stack today; cloud-relocation swap-points are documented for future migration without code changes.

## Domains and clusters

| Cluster | Tables |
|---|---|
| Catalog | `games`, `chapters`, `scene_elements`, `chapter_assets`, `chapter_prerequisites` |
| Federation & access | `federation_modules`, `game_access`, `game_launches`, `game_uploads` |
| Players | `characters`, `skills_loadout`, `character_quests`, `character_items`, `controls`, `player_positions` |
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

- **Auditability** — every state transition emitted by `GameStateService` writes to the Cassandra audit log. Admin moderation also writes to `admin_actions` in Postgres.
- **Reproducibility** — `game_launches.version_dir` snapshots `games.current_version_dir` at mint, so an in-flight launch keeps reading from its install. See [decision 005](../decisions/005-launch-uid-versioning.md).
- **Reversibility** — `game_uploads.superseded_at` lets administrators pin to a prior version through the admin UI.
- **Consistency** — `register.sql` executes inside the upload R2DBC transaction; failure rolls everything back. *Not* atomic vs the on-disk move — recovery procedure in the [maintainer runbook](maintainer-runbook.md), Runbook 5.
- **Observability** — scheduled jobs (`GameLaunchSweeper`, `GameUploadPruner`, `CassandraSyncService`) emit one line per run for log scraping. The admin dashboard SSE streams `pod_heartbeats` rows from Cassandra.

## Identity, regulatory, DR

- **PII surface:** Casdoor user email + display name, stored in Casdoor's Postgres; **not duplicated**.
- **Auth tokens:** issued by Casdoor, verified at the BFF; **never logged**.
- **Audit log:** stores `casdoor_user_id`; combine with Casdoor's tenant DB for traceback.
- **RPO** 24 hours (nightly Postgres + uploads-dir snapshot).
- **RTO** ≈30 minutes (compose up against a restored VM).
- **Right-to-erasure:** delete the Casdoor user, cascade to `characters`, NULL out `casdoor_user_id` in audit rows.

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
| BFF perimeter | `snapdragon-gateway` in front; routes `/api/*` to BFF | None — gateway carries primary rate-limit + revocation; BFF mirrors as defence-in-depth |

## Shipped — was "future" in earlier versions of this guide

These items are now in production-shaped state. The data model didn't need a rewrite to land any of them; each was additive.

- **Streaming audit + telemetry pipeline.** Kafka topics `state_audit`, `game_events`, `game_kpi`, `session.<id>.events` produced by `GameAuditPublisher` / `GameEventPublisher` / `GameKpiPublisher`. ClickHouse consumes via Kafka-engine + materialised views. Gated by `app.kafka.enabled` (default `false`). Cross-pod consumer + `PlayerStateRecoveryService` close the F4 fan-out loop. See [decision 021](../decisions/021-kafka-clickhouse-streaming-pipeline.md).
- **Postgres read replica routing.** `R2dbcReadReplicaConfig` provides `@Qualifier("replica")` beans; per-service migration pattern in [decision 022](../decisions/022-postgres-read-replica.md). Compose-level overlay at `docker-compose.replica.yml`.
- **Object-storage-backed uploads.** `UploadStorage` strategy with `filesystem` (default) + `s3` (compose ships MinIO; prod points at AWS / R2 / B2). `app.uploads.s3.presigned-reads=true` flips `AssetController` into 302-to-presigned-URL mode so the WASM hot path stays off the BFF. Resilience4j circuit breaker (`s3` instance) wraps the strategy. See [decision 020](../decisions/020-object-storage-uploads.md).
- **Cross-pod rate-limit storage.** `DistributedRateLimitService` runs the cluster-wide augment on Cassandra `rate_limit_events` (90 s TTL); disable via `app.rate-limit.distributed.enabled=false`. The `snapdragon-gateway` is the primary enforcement; the BFF's `RateLimitFilter` is defence-in-depth.
- **Per-role accounts schema.** `accounts.players` / `accounts.producers` / `accounts.mods` (Liquibase 041). Lazy-populated for players; producers + mods are populated when a `producer_contacts` application is APPROVED.

## Still future

- AWS S3 + Cloudflare R2 + Backblaze B2 acceptance matrix (the `s3` strategy works against all three; the matrix would document pass/fail per provider for the edge cases like CORS and pre-signed URL semantics).
- `CassandraSyncService` retirement once the streaming pipeline has soaked in production. The audit / heartbeat are double-written today.
- Per-route CSP tightening (`/login` doesn't need `worker-src blob:`, `/admin` doesn't need `frame-src 'self'`); strict CSP via the three-step plan in [decision 023](../decisions/023-content-security-policy.md).
