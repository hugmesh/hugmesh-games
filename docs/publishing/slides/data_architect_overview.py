"""
HugMesh Games — Enterprise data architect summary & overview.

A 14-slide deck written for a data architect arriving at a steering
committee or design review. The lens is data: where it lives, why,
how it flows, and what regulatory / lifecycle / consistency posture
the system takes.
"""
from pathlib import Path
import argparse, sys
sys.path.insert(0, str(Path(__file__).parent))
from _hugmesh_deck import make_deck   # noqa: E402


SECTIONS = [
    {
        "title": "Executive summary",
        "body": [
            "Multi-game browser portal. One Spring Boot BFF brokers reads/writes for many concurrent games and players.",
            "Two stores, by responsibility:",
            (1, "PostgreSQL — canonical relational state. Catalog, players, federation, access, uploads."),
            (1, "Cassandra — high-write log + cache. Movement events, audit log, short-lived sessions."),
            "Casdoor (OAuth2/OIDC) is the identity provider. JWT subject is the trust boundary.",
            "All persistence is inside one compose stack today; swap-points are documented for cloud relocation.",
        ],
    },
    {
        "title": "Domains",
        "body": [
            "Catalog — games, levels, scene_elements, level_assets, level_prerequisites.",
            "Federation & access — federation_modules, game_access, game_launches, game_uploads.",
            "Players — characters, spell_loadout, character_quests, character_items, controls.",
            "Identity — Casdoor's tenant in Postgres.",
            "Audit & cache — Cassandra keyspace; movement + audit log.",
        ],
        "image": "diagram:data-model",
    },
    {
        "title": "Data lifecycle: hot, warm, cold",
        "body": [
            "Hot   (≤ 60 s)  — SSE merge stream, in-process Sinks.Many. Lost on restart by design; clients re-subscribe.",
            "Warm  (minutes) — Cassandra movement events; useful for cross-pod replay; aged out by TTL.",
            "Cold  (durable) — Postgres rows for catalog, characters, quests, uploads, audit summary.",
            "Hot is recoverable from Cassandra. Cassandra is recoverable from Postgres + replay. Postgres is the source of truth.",
        ],
    },
    {
        "title": "Schema posture",
        "body": [
            "Liquibase drives Postgres schema. Forward-only — never edit a committed changeset.",
            "24 changelogs at the head of feature/module-federation-and-native; new ones get the next integer prefix.",
            "Every R2DBC entity has matching reflection hints (R2dbcConvertersConfig) so native-image works.",
            "Cassandra schemas live in infra/cassandra/cassandra-init.cql, bootstrapped by an init container.",
            "Schema evolution: add columns/tables freely; renames/drops require a two-step expand-then-contract.",
        ],
    },
    {
        "title": "Identity & access posture",
        "body": [
            "Casdoor is single source of truth for the role catalog. The portal does NOT mirror it.",
            "Per-game visibility lives in game_access (scope=all|role|user). Three scopes are additive (union).",
            "ROLE_ADMIN is checked centrally for every endpoint under /api/admin/.",
            "JWT subjects are the only trusted identity at the BFF; X-Casdoor-Id legacy headers were removed.",
            "Per-game ROLE_PLAY_<game> roles are created idempotently by scripts/seed-roles.sh after a deploy.",
        ],
    },
    {
        "title": "Cross-cutting concerns",
        "body": [
            "Auditability — every state transition emitted to Cassandra audit log.",
            "Reproducibility — game_launches.version_dir snapshots current_version_dir.",
            "Reversibility — game_uploads.superseded_at allows version-pinning.",
            "Consistency — register.sql in the upload R2DBC tx; failure rolls back.",
            "Observability — scheduled jobs emit one-line summaries.",
        ],
        "image": "diagram:game-upload-controller",
    },
    {
        "title": "Failure mode 1 — unauthenticated request",
        "body": [
            "OAuth2 resource-server filter rejects pre-controller.",
            "401; no controller code touched; nothing written.",
            "BFF log distinguishes 'no token' from 'token rejected by JWKS'.",
        ],
        "image": "diagram:scenario-error-401",
    },
    {
        "title": "Failure mode 2 — SSE subscriber disconnect",
        "body": [
            "Client TCP closes mid-stream; Reactor sees onCancel.",
            "Reactor deregisters subscription #1; upstream Sinks stay open.",
            "Every Sinks.many() sets autoCancel=false — reconnect resumes.",
            "If 'first reconnect then silence': autoCancel=false was dropped.",
        ],
        "image": "diagram:scenario-sse-disconnect",
    },
    {
        "title": "Failure mode 3 — admin force-revoke (kick)",
        "body": [
            "DELETE /api/admin/launches/{uid} inside a tx.",
            "game_launches.terminated_at = NOW() — atomic.",
            "In-flight bytes finish; next level-transition gets 410 Gone.",
            "Host shell shows NavigationError banner.",
        ],
        "image": "diagram:scenario-admin-kick",
        "dark": True,
    },
    {
        "title": "Failure mode 4 — hot upload rollback",
        "body": [
            "register.sql throws inside transactional block.",
            "R2DBC tx rolls back AND the on-disk move is reversed.",
            "games.current_version_dir unchanged — prior bundle still active.",
            "Surfaces as 500 (SQL) or 409 (slug-aware constraint reason).",
        ],
        "image": "diagram:scenario-upload-rollback",
    },
    {
        "title": "Per-game scope tables",
        "body": [
            "Mod/publisher auth is split: Casdoor owns the role, Postgres owns the per-game scope.",
            "mod_game_scopes / publisher_game_scopes — one row per (casdoorId, gameSlug) pair.",
            "BFF reads BOTH layers for every mod/publisher mutation; one alone is insufficient.",
            "Cloud-relocation property: identity provider can move (Auth0/Cognito/Entra), per-game scope stays in your relational store.",
            "Mutation endpoints: /api/admin/role-grants/* (admin) + /api/publisher/grant-mod (publisher within scope) — only these write rows.",
            "Pinning: e2e/features/security/mod-grant-gates.feature.",
        ],
    },
    {
        "title": "Maker side databases",
        "body": [
            "RPG and VN makers each ship as their own Spring Boot side service with its own Postgres instance.",
            "postgres-rpg-builder — Mudlet-shaped (areas/rooms/exits/items/NPCs).",
            "postgres-vn-builder — godot-2d-vn-shaped (acts/characters/backgrounds/scenes/lines/choices).",
            "Schemas exclusively Liquibase-managed; no JPA ddl-auto, no psql-init seed.",
            "Build worker walks the project tree → Godot 4 source → godot --export-release → POSTs to BFF /api/admin/games/upload WITH ORIGINATING USER'S BEARER.",
            "Each area (RPG) / act (VN) becomes a Chapter row in the BFF. Three Postgres instances live in the same compose today but can move independently. See decision 018.",
        ],
        "dark": True,
    },
    {
        "title": "Identity widening (Liquibase 036)",
        "body": [
            "Crypto.mintUid() replaced UUID.randomUUID() everywhere for UI-facing UIDs + URL tokens.",
            "Output: 43-char URL-safe base64 of SHA3-256(64 random bytes from BC SP800-90A HMAC-SHA256 DRBG).",
            "Liquibase 036 widens session-id columns from varchar(36) to varchar(64) — fits 43-char SHA3 plus headroom.",
            "Pre-existing UUID-shape rows (36 chars) keep fitting; migration widens never narrows. No data rewrite.",
            "Cross-system joins still work — the migration widens every join target in the same changeset.",
            "Log shippers / analytics warehouses pulling session id need a 64-char buffer (relevant only for length-aware external schemas).",
        ],
    },
    {
        "title": "Rate-limit telemetry",
        "body": [
            "rate_limit_events Cassandra table — append-only log of cross-pod rate-limit decisions, 90 s TTL.",
            "Window-bounded COUNT(*) against event_id >= minTimeuuid(now − WINDOW) gives the cluster-wide budget.",
            "Aggregates back to per-user / per-zone counters without bloating durable state.",
            "Disable via app.rate-limit.distributed.enabled=false; falls back to local-only when Cassandra is unreachable.",
            "Paired with nginx zones at the edge (decision 019) — JVM never sees rejected floods.",
        ],
    },
    {
        "title": "Read patterns",
        "body": [
            "Catalog reads (games, levels) — heavy cache, ttl ~60 s, served from Postgres directly. Index on slug.",
            "Real-time reads — SSE merge stream; subscribers are connected players, not external systems.",
            "Federation manifest — fetched by every host page load, cached client-side 60 s. Index on (kind, slug).",
            "Admin reads — paginated lists; stat aggregates run as small ad-hoc queries (not materialised views today).",
            "Audit log replay — Cassandra range query on (character_id, occurred_at).",
        ],
    },
    {
        "title": "Write patterns",
        "body": [
            "Movement events — high frequency, write-through to Cassandra; SSE merge reads from in-process Sinks.",
            "Character updates — Postgres single-row UPDATE per movement persistence batch (typically every N events).",
            "Game uploads — atomic R2DBC transaction; multi-table insert via the bundle's register.sql.",
            "Schema changes — single-writer (Liquibase at boot); never concurrent migrations.",
            "Cross-tier syncs — nightly CassandraSyncService reconciles audit-log totals to Postgres counters.",
        ],
    },
    {
        "title": "Capacity / scaling axes",
        "body": [
            "Player concurrency — bounded today by the SSE multicast Sinks (~thousands per BFF process).",
            "Catalog growth — linear; games × levels × scene_elements. Indexes on slug are the hot path.",
            "Audit log growth — linear with player activity; Cassandra TTL trims the tail; Postgres holds totals only.",
            "Upload storage — `app.uploads.games-dir` capped via `app.uploads.versions-to-keep` (default 3).",
            "Frontend — federation lets remotes scale independently; each is its own nginx container.",
        ],
    },
    {
        "title": "Regulatory posture",
        "body": [
            "PII surface  — Casdoor user email + display name. Stored in Casdoor's Postgres; not duplicated anywhere.",
            "Auth tokens  — issued by Casdoor, verified at the BFF; never logged.",
            "Game state   — pseudonymous; keyed by Casdoor user id, not by PII.",
            "Audit log    — stores casdoor_user_id; combine with Casdoor's tenant DB for traceback.",
            "Right-to-erasure: delete the Casdoor user + cascade to characters + null casdoor_user_id in audit rows.",
        ],
        "dark": True,
    },
    {
        "title": "Disaster posture",
        "body": [
            "RPO target: 24 hours (nightly Postgres + uploads dir snapshot).",
            "RTO target: ~30 minutes (compose up against a restored VM).",
            "Failure domains: single-host today; multi-host requires moving Postgres + Cassandra to managed services.",
            "Cassandra is intentionally a CACHE — the audit log can be reconstructed from Postgres + log replay.",
            "Casdoor is co-resident with Postgres (separate database, same instance); restoring one restores the other.",
        ],
    },
    {
        "title": "Cloud relocation swap-points",
        "body": [
            "Postgres → managed RDS / Cloud SQL: change spring.r2dbc.url + spring.datasource.url. No code change.",
            "Postgres read replica → Aurora reader / Cloud SQL replica: set app.r2dbc.replica.url. R2dbcReadReplicaConfig aliases primary when empty.",
            "Cassandra → managed AstraDB / Scylla Cloud: spring.cassandra.contact-points. No code change.",
            "Casdoor → managed identity (Auth0 / Cognito / Entra): change /.well-known/jwks URL + Casdoor-specific role mapping.",
            "Object storage for uploads: app.uploads.storage=s3 + the four APP_UPLOADS_S3_* knobs. UploadStorage strategy works with AWS, R2, B2, MinIO.",
            "Streaming audit / telemetry: app.kafka.enabled=true + Kafka + ClickHouse bootstrap URLs. Gated producers + consumer; no code change.",
            "BFF perimeter: hugmesh-gateway routes /api/* + carries primary rate-limit + revocation list. BFF mirrors as defence-in-depth.",
            "Federation manifest URL — already DB-backed, no changes needed across hosts.",
        ],
    },
    {
        "title": "Shipped — was \"future\" in earlier decks",
        "body": [
            "Streaming audit + telemetry pipeline — Kafka topics state_audit / game_events / game_kpi / session.<id>.events; ClickHouse via Kafka-engine + MergeTree. Decision 021.",
            "Postgres read replica routing — R2dbcReadReplicaConfig provides @Qualifier(\"replica\") beans; compose-level overlay at docker-compose.replica.yml. Decision 022.",
            "Object-storage-backed uploads — UploadStorage strategy with filesystem (default) + s3; circuit-breaker decorated; pre-signed URL reads. Decision 020.",
            "Cross-pod rate-limit storage — DistributedRateLimitService on Cassandra rate_limit_events (90 s TTL). Defence-in-depth behind the gateway.",
            "Federated game telemetry — POST /api/telemetry/game/{slug} feeds GameKpiPublisher → game_kpi → ClickHouse kpi_rolling_avg_24h.",
            "Per-role accounts schema — accounts.players / .producers / .mods, populated on first-touch or producer-contacts approval. Decision: docs/decisions/020-per-role-schema-split.md.",
        ],
    },
    {
        "title": "Still future",
        "body": [
            "AWS S3 + Cloudflare R2 + Backblaze B2 acceptance matrix (the strategy works against all three; the matrix documents pass/fail per edge case like CORS / pre-signed URL semantics).",
            "CassandraSyncService retirement once the streaming pipeline has soaked in production (audit / heartbeat are double-written today).",
            "Per-route CSP tightening + strict CSP (drop both 'unsafe-*' tokens) — three-step plan in decision 023.",
        ],
    },
    {
        "title": "Where to read more",
        "body": [
            "docs/publishing/architecture/build/architecture.pdf — chapter 4 is the schema deep-dive.",
            "docs/publishing/javadoc-output/index.html — every controller and service, themed.",
            "backend/src/main/resources/db/changelog/ — the Liquibase changelog tree, in numerical order.",
            "infra/cassandra/cassandra-init.cql — the Cassandra schema bootstrap.",
            "docs/PLAN-module-federation-and-native.md — the eight-section plan that produced the current shape.",
        ],
    },
]


CLOSING = {
    "title": "Two stores. One source of truth. Forward-only schema.",
    "body": [
        "The data model exists to serve gameplay; reporting and analytics are explicitly downstream.",
        "Reversibility is built into uploads, launches, and access policy. None of those is destructive.",
        "Cloud relocation requires no code change today — only configuration. That is the strongest design property of the BFF.",
    ],
    "links": [
        "Repo: github.com/hugmesh-net/hugmesh-games",
        "Architecture PDF: docs/publishing/architecture/build/architecture.pdf",
        "Schema reference: backend/src/main/resources/db/changelog/",
    ],
}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    make_deck(
        title="HugMesh Games — Data architect overview",
        subtitle="Where data lives, how it flows, and how it stays consistent",
        kicker="Enterprise data architect overview",
        audience="Data architects, technology committee members, integration leads.",
        sections=SECTIONS,
        closing=CLOSING,
        output=args.output,
    )
    print(f"✓ wrote {args.output}")


if __name__ == "__main__":
    main()
