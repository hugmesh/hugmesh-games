# Administration manual

> A typeset edition lives in [`docs/publishing/userguides/administration/guide.tex`](../../publishing/userguides/administration/guide.tex). The two are edited separately and can differ.

Day-2 operations for games admins (`ROLE_GAMES_ADMIN`): deploy, upload, grant, revoke, roll back, troubleshoot. An admin holds every key in the building, so check the door before you turn one.

## Who this is for

You are a games admin and have shell access to the compose host. On the compose stack that means the Casdoor role `admin` in the games realm (org `threewizards`); the BFF turns it into `ROLE_GAMES_ADMIN`, the authority every admin endpoint checks. On a mesh, a bare `ROLE_ADMIN` is the platform admin and opens nothing here; the games admin there is `ROLE_GAMES_ADMIN` (see [05 — Who is a games admin](../architecture/05-api-reference.md#who-is-a-games-admin)). You deploy, configure, inspect, revoke and roll back. You do not write Java, GDScript or Angular here; those have their own guides.

## The compose stack

The base `docker-compose.yml` defines 28 services. The ones you will touch:

| Service | What it is |
|---|---|
| `frontend` | Host nginx + Angular host bundle. |
| `login`, `registration`, `producer-registration`, `banned`, `admin`, `mod`, `publisher`, `api-docs` | Federated route remotes (per-remote nginx). |
| `games-three-wizards`, `games-dodecahedron`, `games-rpg-maker`, `games-vn-maker` | Federated game and maker remotes. |
| `wasm-fragment` | Asset-tier nginx that serves Godot bundles with COOP / COEP / CORP headers. |
| `api` | Spring Boot BFF. The native overlay (`docker-compose.native.yml`) replaces it with the GraalVM build and adds a one-shot `liquibase` service that runs the migrations; there is no separate `api-native` service. |
| `rpg-builder`, `vn-builder` (+ `postgres-rpg-builder`, `postgres-vn-builder`) | Maker back-ends and their databases. |
| `postgres`, `cassandra`, `casdoor` | Persistence and identity. |
| `kafka`, `zookeeper`, `clickhouse`, `minio` | Streaming pipeline and object storage. They are in the base file, but the BFF uses them only when `app.kafka.enabled` or `app.uploads.storage=s3` is on. |

## Bringing the stack up

```bash
make db-env                     # once: writes random Postgres passwords into the git-ignored .env
docker compose up -d            # cold start (90-120 s to all-healthy)
docker compose -f docker-compose.yml -f docker-compose.native.yml up -d   # native overlay
docker compose logs -f api      # follow BFF logs
docker compose down -v          # full reset (DROPS volumes)
```

`docker-compose.yml` has no database password literal. Compose refuses every subcommand until `GAMES_DB_PASS`, `RPG_BUILDER_DB_PASS` and `VN_BUILDER_DB_PASS` are set, either in `.env` (`make db-env`) or exported. An existing Postgres volume keeps the password it was created with, so keep `.env` once the volume exists.

### ⚠ Docker 29.x needs buildx

`docker compose build` hangs silently if `docker-buildx` isn't installed. Fix: install the v0.18 binary user-locally into `~/.docker/cli-plugins/docker-buildx` (no sudo).

### ⚠ First-time Casdoor seeds

Casdoor randomises its seed credentials for each fresh database. See [build & deploy → Casdoor seed credentials](../architecture/06-build-and-deploy.md#-casdoor-seed-credentials) for the `.env` recipe.

## The admin UI

Visit `/admin` after logging in as a games admin (the host shows the admin links for the `admin` and `games_admin` roles). Pages:

- **`/admin`** — Dashboard: totals, *Per-user usage*, and the *Active sessions right now* table. Each online row in *Per-user usage* has a *Kick* button. It force-disconnects the player (`DELETE /api/admin/sessions/{casdoorId}`), writes no `admin_actions` row and sends the player no event, so it is not the audited admin kick below.
- **`/admin/games`** — *Upload a game bundle*; *Upload history* (look up a slug; read-only); *Per-game access*.
- **Per-game access** — `scope=all|role|user`; rows are additive (union). The role dropdown is live from Casdoor via `/api/admin/roles`, and the value is the Casdoor role name (for example `play-three-wizards`). See [decision 009](../decisions/009-game-access-scopes.md).
- **`/admin/sessions`** — live multiplayer sessions: members, an event terminal, channel rates, and the per-player view stream.
- **`/admin/session-log`** — open sessions grouped by game.
- **`/admin/docs`** — API docs.

There are no ban, unban or launch-revoke buttons, and no button for the audited admin kick. Those are API calls (see below).

## Hot-uploading a game

Step-by-step: [howto/upload-and-rollback-game.md](../howto/upload-and-rollback-game.md).

Summary:

1. Get a HugMesh Export zip from the game developer, or build it yourself with the Godot editor plugin.
2. On `/admin/games`, drop the zip on the *Upload a game bundle* card and press *Install bundle*.
3. The install transaction validates manifest schema v2, hashes the bundle, atomically moves it to `<gamesDir>/<slug>/<sha-prefix>`, and runs `register.sql` in one Postgres transaction.
4. The host fetches the federation manifest once, when the page loads. Players see the new game after they reload.

## Rollback and access policy

Every upload supersedes the previous one and keeps the prior bundle on disk. `GET /api/admin/games/{slug}/uploads` lists the history. There is no rollback button or endpoint. If you still have the old zip, upload it again: an identical bundle hashes to the same version directory, and the upload makes it current (option A in [howto/upload-and-rollback-game.md](../howto/upload-and-rollback-game.md)). Otherwise update `games.current_version_dir` and the `superseded_at` columns in SQL (option B). `GameUploadPruner` removes old versions (default: keep the 3 newest by upload time; set `app.uploads.versions-to-keep` to change it). It does not check which version is current, so it can delete a version you pinned in SQL; read the howto's warning first.

Per-game visibility lives in the `game_access` table. It has three additive scopes: `all`, `role` (the Casdoor role name, e.g. `play-three-wizards`) and `user` (a specific Casdoor ID). Add rows in the admin UI (*Per-game access* on `/admin/games`) or with `POST /api/admin/games/{slug}/access`. Re-adding an existing `role` or `user` row is a no-op (the `uq_game_access_triple` constraint). An `all` row stores a NULL value, which the constraint does not dedupe, so re-adding `all` inserts a second, equivalent row.

## Kicking and banning players

Kick, ban and unban are games-admin-only API calls: `POST /api/admin/actions/kick/{casdoorId}`, `.../ban/{casdoorId}` and `.../unban/{casdoorId}`. `{casdoorId}` must be the player's JWT `sub`, which the dashboard shows in the *Casdoor ID* column of *Per-user usage*. The optional body is `{message, expiresAt}`; `expiresAt` makes a ban temporary. Each call writes `admin_actions` and broadcasts on the per-user SSE channel (`/api/me/admin-actions/stream`) that the player's host shell subscribes to. Kick and ban also drop the player's server-side session. For a kick or a ban, every tab of the player removes its game iframes (the event is relayed between tabs over `BroadcastChannel`) and goes to `/banned`; after a ban, the auth guard keeps the player there until an unban. To end one launch, call `DELETE /api/games/admin/launches/{uid}`.

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
