# Administration manual

> A typeset edition lives in [`docs/publishing/userguides/administration/guide.tex`](../../publishing/userguides/administration/guide.tex). The two are edited separately and can differ.

Day-2 operations for `ROLE_ADMIN` holders: deploy, upload, role-grant, revoke, roll back, troubleshoot.

## Who this is for

Anyone with the Casdoor role `admin` (the BFF sees it as `ROLE_ADMIN`) and shell access to the compose host. You will: deploy, configure, inspect, revoke, roll back. You will not: write Java, GDScript, or Angular here — those have their own guides.

## The compose stack

The base `docker-compose.yml` defines 28 services. The ones you will touch:

| Service | What it is |
|---|---|
| `frontend` | Host nginx + Angular host bundle. |
| `login`, `registration`, `producer-registration`, `banned`, `admin`, `mod`, `publisher`, `api-docs` | Federated route remotes (per-remote nginx). |
| `games-three-wizards`, `games-dodecahedron`, `games-rpg-maker`, `games-vn-maker` | Federated game and maker remotes. |
| `wasm-fragment` | Asset-tier nginx that serves Godot bundles with COOP / COEP / CORP headers. |
| `api` | Spring Boot BFF. The native overlay (`docker-compose.native.yml`) replaces it with the GraalVM build; there is no separate `api-native` service. |
| `rpg-builder`, `vn-builder` (+ `postgres-rpg-builder`, `postgres-vn-builder`) | Maker back-ends and their databases. |
| `postgres`, `cassandra`, `casdoor` | Persistence and identity. |
| `kafka`, `zookeeper`, `clickhouse`, `minio` | Streaming pipeline and object storage. They are in the base file, but the BFF uses them only when `app.kafka.enabled` or `app.uploads.storage=s3` is on. |

## Bringing the stack up

```bash
docker compose up -d            # cold start (90-120 s to all-healthy)
docker compose -f docker-compose.yml -f docker-compose.native.yml up -d   # native overlay
docker compose logs -f api      # follow BFF logs
docker compose down -v          # full reset (DROPS volumes)
```

### ⚠ Docker 29.x needs buildx

`docker compose build` hangs silently if `docker-buildx` isn't installed. Fix: user-local install of the v0.18 binary into `~/.docker/cli-plugins/docker-buildx` (no sudo).

### ⚠ First-time Casdoor seeds

Casdoor randomises seed credentials per fresh database. See [build & deploy → Casdoor seed credentials](../architecture/06-build-and-deploy.md#-casdoor-seed-credentials) for the `.env` recipe.

## The admin UI

Visit `/admin` (after logging in as a user with the `admin` role). Pages:

- **`/admin`** — Dashboard: totals, per-user usage, and the *Active sessions right now* table. It is read-only.
- **`/admin/games`** — *Upload a game bundle*; *Upload history* (look up a slug; read-only); *Per-game access*.
- **Per-game access** — `scope=all|role|user`; rows are additive (union). The role dropdown is live from Casdoor via `/api/admin/roles`, and the value is the Casdoor role name (for example `play-three-wizards`). See [decision 009](../decisions/009-game-access-scopes.md).
- **`/admin/sessions`** — live multiplayer sessions: members, an event terminal, channel rates, and the per-player view stream.
- **`/admin/session-log`** — open sessions grouped by game.
- **`/admin/docs`** — API docs.

There are no kick, ban or launch-revoke buttons. Those are API calls (see below).

## Hot-uploading a game

Step-by-step: [howto/upload-and-rollback-game.md](../howto/upload-and-rollback-game.md).

Summary:

1. Get a HugMesh Export zip from the game developer (or build it yourself with the Godot editor plugin).
2. `/admin/games` → *Upload game bundle* → choose zip → Upload.
3. The install transaction validates manifest schema v2, hashes the bundle, atomically moves to `<gamesDir>/<slug>/<sha-prefix>`, and runs `register.sql` in one Postgres transaction.
4. The host fetches the federation manifest once, when the page loads. Players see the new game after they reload.

## Rollback and access policy

Every upload supersedes the previous one but keeps the prior bundle on disk. `GET /api/admin/games/{slug}/uploads` lists the history. There is no rollback button or endpoint: to pin a previous version, update `games.current_version_dir` and the `superseded_at` columns in SQL (see [howto/upload-and-rollback-game.md](../howto/upload-and-rollback-game.md), option B). Old versions are pruned by `GameUploadPruner` (default: keep 3 newest, configurable via `app.uploads.versions-to-keep`).

Per-game visibility lives in the `game_access` table. Three additive scopes: `all`, `role` (the Casdoor role name, e.g. `play-three-wizards`), `user` (specific Casdoor ID). Add via the admin UI (*Per-game access* on `/admin/games`) or `POST /api/admin/games/{slug}/access`; re-adding an existing row is harmless.

## Kicking and banning players

Kick, ban and unban are admin-only API calls: `POST /api/admin/actions/kick/{casdoorId}`, `.../ban/{casdoorId}` and `.../unban/{casdoorId}`, with an optional body `{message, expiresAt}` (`expiresAt` makes a ban temporary). Each writes `admin_actions` and broadcasts on the per-user SSE channel (`/api/me/admin-actions/stream`) that the player's host shell subscribes to. Kick and ban also drop the player's server-side session. The shell closes open game windows via `BroadcastChannel`; a banned player is redirected to `/banned`. To end one launch, call `DELETE /api/games/admin/launches/{uid}`.

Moderators kick players inside a single session with `POST /api/mod/sessions/{id}/kick`; see the [moderator guide](moderator.md). API detail: [architecture chapter 5](../architecture/05-api-reference.md).

## Scheduled jobs

| Job | Cadence | What it does |
|---|---|---|
| `GameLaunchSweeper` | every 5 min | Closes stale launches once their 15-minute TTL expires. |
| `GameUploadPruner` | every hour | Caps disk usage by retaining only `versions-to-keep` most-recent dirs per slug. |
| `SessionMemberReaper` | every 30 s | Releases the seat of any session member silent for 3 min (`app.sessions.member-timeout-ms`). Disable with `app.sessions.reap-enabled=false`. |
| `CassandraSyncService` (heartbeat) | every 15 s | Writes a `pod_heartbeats` row with TTL 30 s. |
| `CassandraSyncService` (snapshot) | every 24 h | Mirrors Postgres aggregates into `daily_snapshots` for offline analytics. |

## Common fires

| Symptom | Likely cause | Fix |
|---|---|---|
| `docker compose build` hangs | docker-buildx not installed | Install `~/.docker/cli-plugins/docker-buildx` |
| BFF restarts every minute | Liquibase failed | `docker compose logs api`, read the changeset id |
| BFF restart-loop after a fresh DB | Casdoor secret mismatch | Re-pin `.env` from `docker compose logs casdoor` |
| Iframe blank, no error | CORP missing on `wasm-fragment` | Verify `wasm-fragment/nginx.conf` has `Cross-Origin-Resource-Policy: cross-origin` |
| JWT 500 with "Missing required keys member" | Wrong jwks endpoint | Should be `/.well-known/jwks`, not `/api/get-jwks` |
| "Couldn't reach Login" banner | BFF is down | Check `docker compose logs api` |
| `make gen-api` skips generation ("openapi.json is missing or not valid JSON") | BFF unreachable, or host nginx returned SPA HTML (gen-api now rejects non-JSON before it overwrites the file) | Bring the BFF up at `:8080`, rerun `make gen-api` |

For deeper triage, see the [maintainer runbook](maintainer-runbook.md).
