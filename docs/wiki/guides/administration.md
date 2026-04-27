# Administration manual

> Mirror of [`docs/publishing/userguides/administration/guide.tex`](../../publishing/userguides/administration/guide.tex).

Day-2 operations for `ROLE_ADMIN` holders: deploy, upload, role-grant, revoke, roll back, troubleshoot.

## Who this is for

Anyone with `ROLE_ADMIN` in Casdoor and shell access to the compose host. You will: deploy, configure, inspect, revoke, roll back. You will not: write Java, GDScript, or Angular here — those have their own guides.

## The compose stack

| Service | What it is |
|---|---|
| `frontend` | Host nginx + Angular host bundle. |
| `login`, `admin` | Federated route remotes (per-remote nginx). |
| `games-three-wizards`, `games-dodecahedron` | Federated game remotes. |
| `wasm-fragment` | Asset-tier nginx that serves Godot bundles with COOP / COEP / CORP headers. |
| `api` (or `api-native` on the GraalVM overlay) | Spring Boot BFF. |
| `postgres`, `cassandra`, `casdoor` | Persistence and identity. |

## Bringing the stack up

```bash
docker compose up -d            # cold start (90-120 s to all-healthy)
docker compose -f compose.yml -f compose.native.yml up -d   # native overlay
docker compose logs -f api      # follow BFF logs
docker compose down -v          # full reset (DROPS volumes)
```

### ⚠ Docker 29.x needs buildx

`docker compose build` hangs silently if `docker-buildx` isn't installed. Fix: user-local install of the v0.18 binary into `~/.docker/cli-plugins/docker-buildx` (no sudo).

### ⚠ First-time Casdoor seeds

Casdoor randomises seed credentials per fresh database. See [build & deploy → Casdoor seed credentials](../architecture/06-build-and-deploy.md#-casdoor-seed-credentials) for the `.env` recipe.

## The admin UI

Visit `/admin` (after logging in as a `ROLE_ADMIN` user). Sub-pages:

- **Dashboard** — per-user stats, active sessions, recent uploads.
- **`/admin/games`** — upload a game zip, view rollback history, set version pin.
- **Per-game access** — `scope=all|role|user`; rows are additive (union). See [decision 009](../decisions/009-game-access-scopes.md).
- **Roles dropdown** — live from Casdoor via `/api/admin/roles`.
- **Active launches** — each row has a *kick* button that `DELETE`s `/api/admin/launches/{uid}`.

## Hot-uploading a game

Step-by-step with screenshots: [howto/upload-and-rollback-game.md](../howto/upload-and-rollback-game.md).

Summary:

1. Get a Snapdragon Export zip from the game developer (or build it yourself with the Godot editor plugin).
2. `/admin/games` → *Upload game bundle* → choose zip → Upload.
3. The install transaction validates manifest schema v2, hashes the bundle, atomically moves to `<gamesDir>/<slug>/<sha-prefix>`, and runs `register.sql` in one Postgres transaction.
4. The host's federation manifest discovers the new game on the next refresh (within 60 s).

## Rollback and access policy

Every upload supersedes the previous one but keeps the prior bundle on disk. `GET /api/admin/games/{slug}/uploads` lists the rollback history; pin to a previous row via the admin UI's *Set as current* button. Old versions are pruned by `GameUploadPruner` (default: keep 3 newest, configurable via `app.uploads.versions-to-keep`).

Per-game visibility lives in the `game_access` table. Three additive scopes: `all`, `role` (e.g. `ROLE_PLAY_THREE_WIZARDS`), `user` (specific Casdoor ID). Add via the admin UI; idempotent on the `uq_game_access_triple` constraint.

## Kicking and banning players

The admin dashboard's *Active sessions* list has *kick* and *ban* buttons; both write to `admin_actions` and broadcast on the per-user SSE channel that the player's host shell subscribes to. Banned players are immediately forced out of any open game window via `BroadcastChannel` and redirected to `/banned` on next login.

Step-by-step: [howto/kick-or-ban.md](../howto/kick-or-ban.md).

## Scheduled jobs

| Job | Cadence | What it does |
|---|---|---|
| `GameLaunchSweeper` | every 5 min | Closes stale launches once their 15-minute TTL expires. |
| `GameUploadPruner` | every hour | Caps disk usage by retaining only `versions-to-keep` most-recent dirs per slug. |
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
| `make gen-api` corrupts `frontend/openapi.json` | BFF unreachable, host nginx returned SPA HTML | Wipe the file (it's git-ignored), bring BFF up, retry |

For deeper triage, see the [maintainer runbook](maintainer-runbook.md).
