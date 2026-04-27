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
| Cassandra → managed Astra / Scylla | `spring.cassandra.contact-points` | None |
| Casdoor → managed identity (Auth0 / Cognito / Entra) | `/.well-known/jwks` URL + role-mapping | Casdoor-specific role mapping |
| Object-storage uploads | `app.uploads.games-dir` → S3-compatible volume mount | None |
| Federation manifest URL | Already DB-backed | None |

## Future shape

Per-game telemetry pipeline (Cassandra audit log → Kafka → analytics warehouse). Read replica for catalog reads to offload heavy `/api/games/*`. Object-storage-backed uploads to remove host-disk pressure and enable CDN delivery for `wasm-fragment`. Cross-pod rate-limit storage in Cassandra (currently in-process per-pod via `RateLimitFilter`). All additive — the data model doesn't need a rewrite to enable any of them.
