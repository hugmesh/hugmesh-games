# 07 — Extending the portal

> Mirror of [`chapters/07-extending.tex`](../../publishing/architecture/chapters/07-extending.tex).

Quick index of common extension tasks. Each one has a step-by-step recipe under [`../howto/`](../howto/); this page is the canonical *contract* those recipes follow.

| Task | Recipe |
|---|---|
| Add a new game | [howto/add-game.md](../howto/add-game.md) |
| Add a federated route | [howto/add-federated-route.md](../howto/add-federated-route.md) |
| Add an admin view | [howto/add-admin-view.md](../howto/add-admin-view.md) |
| Add a role and gate a game on it | [howto/add-role-and-grant.md](../howto/add-role-and-grant.md) |
| Add a new SSE channel | [howto/add-sse-channel.md](../howto/add-sse-channel.md) |
| Add a database column | [howto/add-db-column.md](../howto/add-db-column.md) |

## Adding a new game — two paths

There are two paths.

### Path A — upload at runtime

Every supported game ships an editor plugin (`addons/snapdragon_export/`) under [`games/three-wizards-in-a-trenchcoat/`](../../../games/three-wizards-in-a-trenchcoat/) that produces a deploy-ready zip with a v2 `manifest.json`, a `register.sql` insert script, and the `bundle/` contents:

1. In the Godot editor, *Project → Tools → Snapdragon → Export…*
2. Fill in slug, display name, description, scene type. The plugin defaults the federation `remote_entry_url` to `/_remotes/games-<slug>/remoteEntry.json`; if you don't plan to ship a per-game frontend remote, change it to `/_remotes/wasm-fragment/remoteEntry.json` so the host loads the bundle through the shared fragment.
3. Click *Export*. The plugin writes a zip to your chosen path.
4. Hit `POST /api/admin/games/upload` with the zip — the admin UI's *Upload game bundle* card does this for you. The install transaction validates, hashes, atomically moves the bundle, and runs `register.sql` which inserts into `games`, `federation_modules`, and `game_access` all in one Postgres transaction.

The host shell discovers the new game on the next federation manifest fetch (currently every 60 s; see `FederationService`). No host redeploy is required.

### Path B — commit the game

For a game you want to ship in the repository:

1. Drop the Godot project under `games/<game-dir>/project.godot`, with its own `export_presets.cfg` and `addons/snapdragon_export/`.
2. Add the directory to `GAME_PROJECTS` and define `PRESETS_<dir>` in the root [`Makefile`](../../../Makefile).
3. Add a Liquibase changeset that inserts the `games` + `federation_modules` + `game_access` rows. See [`017-snapdragon-catalog.xml`](../../../backend/src/main/resources/db/changelog/017-snapdragon-catalog.xml) for the canonical pattern.
4. Optionally add a `games-<slug>` service to [`docker-compose.yml`](../../../docker-compose.yml) if the game ships its own Module Federation remote (separate Angular project under `frontend/projects/games-<slug>/`).
5. Run `make build` and `docker compose up -d`.

## Adding a new federated route

1. Build the route's component as a standalone Angular project under `frontend/projects/<remote-name>/`, with its own `federation.config.js` extending [`frontend/federation-base.js`](../../../frontend/federation-base.js).
2. Expose the entry component in `frontend/projects/<remote-name>/federation.config.js`'s `exposes` map.
3. Add a row to `federation_modules` (Liquibase changeset recommended). Set `kind` to `remote-shell` for a full route component.
4. In `frontend/src/app/app.routes.ts`, plug `loadRemote('<remote-name>', './<ExposedSymbol>', '<ExposedSymbol>')` into a `loadComponent` entry.
5. Add an nginx `location /_remotes/<remote-name>/` block in `frontend/nginx.conf` pointing at the per-remote compose service.
6. Add the per-remote service to `docker-compose.yml`.

## Adding a new admin view

The admin remote ([`frontend/projects/admin/`](../../../frontend/projects/admin/)) exposes any top-level standalone component listed in its `exposes` map:

1. Add a standalone component under `frontend/projects/admin/src/app/`.
2. Add it to the `exposes` map in `frontend/projects/admin/federation.config.js`.
3. Wire a route in the host's `frontend/src/app/app.routes.ts` pointing at the exposed name.

DTOs cannot be shared across the federation boundary at runtime (TypeScript types erase). Redeclare interfaces inside the remote — see the existing pattern in `upload-remote.component.ts`.

## Adding a new role

Casdoor is the source of truth for the role catalog. The portal enforces visibility through two layers:

1. Create the role in Casdoor (`ROLE_PLAY_<GAME>` for game access, `ROLE_ADMIN` for admin). `make seed-roles` is the idempotent post-deploy entry point that creates per-game `ROLE_PLAY_*` roles via the Casdoor admin API.
2. Insert a `game_access` row with `(scope='role', value='ROLE_PLAY_<GAME>')` via the admin UI's *Per-game access* card or by direct SQL.

The admin dashboard's *Grant role* dropdown reads the live Casdoor catalog through `GET /api/admin/roles`; new roles appear as soon as Casdoor's catalog has them.

## Adding a new SSE channel

1. Add a `Sinks.Many` field to [`GameStateService`](../../../backend/src/main/java/games/snapdragon/bff/service/GameStateService.java) with `autoCancel=false` (see [decision 003](../decisions/003-autocancel-false.md)).
2. Add a `ServerSentEvent`-builder method that wraps the sink as a typed `Flux` with an `event:` field.
3. Merge it into the `Flux.merge(...)` call inside [`GameStreamController`](../../../backend/src/main/java/games/snapdragon/bff/controller/GameStreamController.java) alongside the existing channels and the keepalive.
4. Register a per-type listener on the frontend by calling `eventSource.addEventListener('<event-name>', cb)`.

## Adding a new database column

1. Write a Liquibase changeset under [`backend/src/main/resources/db/changelog/`](../../../backend/src/main/resources/db/changelog/) with the next sequential number. Postgres-only — Cassandra schemas live in `infra/cassandra/cassandra-init.cql`.
2. Include the changeset in [`db.changelog-master.xml`](../../../backend/src/main/resources/db/changelog/db.changelog-master.xml).
3. Add the field to the matching R2DBC entity. The R2DBC mapping is reflective; `R2dbcConvertersConfig` only needs new entries when adding a new entity, not a new column.
4. For GraalVM native-image builds, run `make build-backend-native` and verify the reflection hints still resolve. If a new entity, regenerate the hints with the `native-image-agent` per the [README.md](../../../README.md) *GraalVM native-image build* section.

> **Never edit a previously-merged changeset.** When something is missed (see PR #24 → 028 sweep), append a new changeset with `<preConditions onFail="MARK_RAN">` so re-runs are idempotent.

---

Previous: [06 — Build and deploy](06-build-and-deploy.md) · Back to [README](../README.md).
