# 07 — Extending the portal

> Mirror of [`chapters/07-extending.tex`](../../publishing/architecture/chapters/07-extending.tex).

A quick index of common extension tasks. Each has a step-by-step recipe under [`../howto/`](../howto/). This page holds the *contract* those recipes follow.

| Task | Recipe |
|---|---|
| Add a new game | [howto/add-game.md](../howto/add-game.md) |
| Add a federated route | [howto/add-federated-route.md](../howto/add-federated-route.md) |
| Add an admin view | [howto/add-admin-view.md](../howto/add-admin-view.md) |
| Add a role and gate a game on it | [howto/add-role-and-grant.md](../howto/add-role-and-grant.md) |
| Add a new SSE channel | [howto/add-sse-channel.md](../howto/add-sse-channel.md) |
| Add a database column | [howto/add-db-column.md](../howto/add-db-column.md) |

## Adding a new game — three paths

Paths A and B add a **Godot** game. Path C adds a **web-native (Rust→wasm)** game, the dominant pattern now (14 of the 20 catalog entries).

### Path A — upload at runtime

Every Godot game project carries an editor plugin (`addons/hugmesh_export/`). It produces a deploy-ready zip with a v2 `manifest.json`, a `register.sql` insert script and the `bundle/` contents. The canonical copy lives in [`extensions/hugmesh-bridge/addons/hugmesh_export/`](../../../extensions/hugmesh-bridge/addons/hugmesh_export/). `make sync-bridge-addon` (a prerequisite of `make build-rust`) copies it into every project in `GAME_PROJECTS`, so never edit the per-game copies.

1. In the Godot editor, *Project → Tools → HugMesh → Export to HugMesh…*
2. Fill in slug, display name, description and scene type. The dialog has no field for the federation URL. `manifest_builder.gd` writes a `remote-game` row with `remote_entry_url=/_remotes/games-<slug>/remoteEntry.json` and `exposed_module=./GameRoot`, which assumes a per-game frontend remote. A game without one needs a `wasm-game` row instead (Path C, step 3). The plugin does not write that row.
3. Click *Export*. The plugin writes a zip to your chosen path.
4. Send the zip to `POST /api/admin/games/upload`; the admin UI's *Upload game bundle* card does this for you. The install validates the bundle, hashes it and moves it into storage. Then `register.sql` runs in one Postgres transaction under the least-privilege `hugmesh_register_sql` role. It may insert into `games`, `chapters`, `federation_modules`, `game_access` and `game_event_support`, the tables `RegisterSqlPolicy` allows.

The host shell reads the manifest once per page load (`FederationService` caches it), so a player sees the new game after a reload. A `wasm-game` row needs no host redeploy.

### Path B — commit the game

For a game you want to ship in the repository:

1. Drop the Godot project under `games/<game-dir>/project.godot`, with its own `export_presets.cfg` and `addons/hugmesh_export/`.
2. Add the directory to `GAME_PROJECTS` in the root [`Makefile`](../../../Makefile). Add a `case` arm with its export preset names to both the `build-godot` and `patch-godot` targets. (The `PRESETS_<dir>` variables beside `GAME_PROJECTS` exist, but nothing reads them.) Add the slug to `GAME_SLUGS` if `make seed-roles` should create its `play-<slug>` role.
3. Add a Liquibase changeset that inserts the `games` + `federation_modules` + `game_access` rows. See [`017-snapdragon-catalog.xml`](../../../backend/src/main/resources/db/changelog/017-snapdragon-catalog.xml) for the canonical pattern.
4. Optionally add a `games-<slug>` service to [`docker-compose.yml`](../../../docker-compose.yml) if the game ships its own Module Federation remote (separate Angular project under `frontend/projects/games-<slug>/`).
5. Run `make build` and `docker compose up -d`.

### Path C — add a web-native (Rust→wasm) game

The web-native family (chess + 13 more) uses a different recipe: **no Godot and no per-game Angular remote**. The engine is pure Rust compiled to `wasm32-unknown-unknown`. The frontend is a vanilla-JS canvas that implements the [`games/_shared/`](../../../games/_shared/README.md) `GAME` interface (`{init, render, onLocalInput, onRemoteEvent}`). The game runs **host-authoritative**: the host browser runs the engine, the BFF only relays `control` and `world` over the per-session SSE channels, and the guest re-applies and re-validates. There are four steps, as in the chess and Wave-1 PRs:

1. **The engine and the bundle.** Add the pure-Rust engine under `extensions/hugmesh-<game>/` and a thin C-ABI/JSON shim crate, `extensions/hugmesh-<game>-wasm/`. The shim has zero host imports, so it loads through `WebAssembly.instantiateStreaming` in the browser, through Chicory in-JVM, and under Node. Copy `hugmesh-chess-wasm`. Build the shim with `make build-games-wasm`; add the crate to `GAMES_WASM_CRATES` in the Makefile first. The target only builds and installs nothing. Add the `games/<slug>/` canvas project. Then copy a self-contained bundle into `backend/src/main/resources/assets/levels/<slug>/`: `index.html`, `<slug>.js`, the CSS, the icon, a **copy** of `games/_shared/*.js` under `_shared/`, and the `.wasm` under `wasm/`. Keep the deployed copy byte-identical to the source.
2. **Catalog rows.** Add a Liquibase changeset that inserts the `games` and `chapters` rows. Copy `044-chess-game.xml` or the Wave-1 changesets.
3. **Federation row.** Add a `federation_modules` row with **`kind='wasm-game'`**: `remote_entry_url=/_remotes/wasm-fragment/<slug>/index.html`, `exposed_module=NULL`, `game_slug=<slug>`. This row moves the host off the legacy `<app-game-canvas>`: `home.component` reads it from `GET /api/federation/manifest` (`isWebNative`, `FederationService.webNativeByGameSlug`). No frontend change is needed. `main.ts` leaves `wasm-game` rows out of `initFederation` on purpose. Copy [`051-web-game-federation.xml`](../../../backend/src/main/resources/db/changelog/051-web-game-federation.xml).
4. **Relay event types.** Add `input_event_types` and `game_event_support` rows for the control and world relay channels the game uses. Copy `050` and `052`.

> **⚠ wasm-fragment bakes at image build.** The `wasm-fragment` nginx `COPY`s `backend/src/main/resources/assets/levels` at *image build* time. Rebuild its image whenever a bundle changes: `docker compose build wasm-fragment`. The launch path is `/_remotes/wasm-fragment/<slug>/`, a generic host-nginx proxy present in both `nginx.conf` and `nginx-tls.conf`. `/api/assets/levels/<slug>/` is the BFF fallback.

Test each engine with a Node WASM functional test (`games/_shared/test/<slug>-wasm.test.mjs`) and a DOM-light frontend smoke test under `games/<slug>/test/`. [`e2e/games-arch`](../../../e2e/games-arch/README.md) exercises the end-to-end launch. The "Web-native games" section of [CLAUDE.md](../../../CLAUDE.md) has the full pattern.

> **🔭 Roadmap — future ports.** [`docs/game-ports-backlog.md`](../../game-ports-backlog.md) lists future game ports (kdegames / FPS / RTS). Each row is a future epic, not yet built. The server-authoritative FPS/RTS sim engines exist (🚧 [`docs/realtime-server-authoritative-design.md`](../../realtime-server-authoritative-design.md)), but no realtime title is catalog-launchable yet.

## Adding a new federated route

1. Build the route's component as a standalone Angular project under `frontend/projects/<remote-name>/`, with its own `federation.config.mjs` that calls `hugmeshRemote` from [`frontend/federation-base.mjs`](../../../frontend/federation-base.mjs).
2. Expose the entry component in that `federation.config.mjs`'s `exposes` map.
3. Add a row to `federation_modules` (Liquibase changeset recommended). Set `kind` to `remote-shell` for a full route component.
4. In `frontend/src/app/app.routes.ts`, add `loadComponent: () => loadRemote('<remote-name>', './<ExposedModule>', '<ExportedClass>')`, where the first string is the key in `exposes` and the last is the class the file exports.
5. Add an nginx `location /_remotes/<remote-name>/` block in `frontend/nginx.conf` pointing at the per-remote compose service.
6. Add the per-remote service to `docker-compose.yml`.

## Adding a new admin view

The admin remote ([`frontend/projects/admin/`](../../../frontend/projects/admin/)) exposes each standalone component listed in its `exposes` map:

1. Add a standalone component under `frontend/projects/admin/src/app/`.
2. Add it to the `exposes` map in `frontend/projects/admin/federation.config.mjs`.
3. Wire a route in the host's `frontend/src/app/app.routes.ts` pointing at the exposed name.

DTOs cannot cross the federation boundary at runtime, because TypeScript types erase. Redeclare the interfaces inside the remote, as `upload-remote.component.ts` does.

## Adding a new role

Casdoor is the source of truth for the role catalog. The portal enforces visibility in two steps:

1. Create the role in Casdoor. For per-game access, `make seed-roles` (it needs `CASDOOR_ADMIN_PASS` in the environment) creates a role named `play-<slug>` through the Casdoor admin API, one for each slug in the Makefile's `GAME_SLUGS` (five Godot slugs today). Admin access uses the `admin` role, which the BFF turns into `ROLE_ADMIN`.
2. Insert a `game_access` row with `(scope='role', value='play-<slug>')`, through the admin UI's *Per-game access* card or by direct SQL. The value is the Casdoor role name in lower case, because `Roles.fromJwt` lower-cases the JWT `roles` claim before matching. A `ROLE_PLAY_<GAME>` value never matches.

The admin dashboard's *Grant role* dropdown reads the live Casdoor catalog through `GET /api/admin/roles`, so a new role appears as soon as Casdoor lists it.

## Adding a new SSE channel

1. Add a `Sinks.Many` field to [`GameStateService`](../../../backend/src/main/java/games/hugmesh/bff/service/GameStateService.java) with `autoCancel=false` (see [decision 003](../decisions/003-autocancel-false.md)).
2. Add a stream accessor on `GameStateService` that returns the sink as a `Flux` (like `movementStream()`), and in `GameStreamController` map it with `sse(sseId, "<event-name>", payload)`, which sets the `event:` field.
3. Merge it into the `Flux.merge(...)` call in [`GameStreamController`](../../../backend/src/main/java/games/hugmesh/bff/controller/GameStreamController.java), beside the existing channels and the keepalive. A channel that must stay inside one session also needs a sink in `SessionSinks`; see [02](02-runtime.md#per-session-sse-sink-isolation).
4. Register a per-type listener on the frontend by calling `eventSource.addEventListener('<event-name>', cb)`.

## Adding a new database column

1. Write a Liquibase changeset under [`backend/src/main/resources/db/changelog/`](../../../backend/src/main/resources/db/changelog/) with the next sequential number. Liquibase covers Postgres only; Cassandra schemas live in `docker/cassandra-init.cql`.
2. Include the changeset in [`db.changelog-master.xml`](../../../backend/src/main/resources/db/changelog/db.changelog-master.xml).
3. Add the field to the matching R2DBC entity. The R2DBC mapping is reflective, so a new column needs no converter or hint.
4. For GraalVM native-image builds, run `make build-backend-native` and check that the image still starts. If the change adds a DTO record that crosses the JSON boundary, add it to `JSON_BOUNDARY_DTOS` in [`HugMeshRuntimeHints`](../../../backend/src/main/java/games/hugmesh/bff/aot/HugMeshRuntimeHints.java). The README's *GraalVM native-image build* section covers the rest.

> **Never edit a changeset that has already merged.** When something is missed (see the PR #24 → 028 sweep), append a new changeset with `<preConditions onFail="MARK_RAN">`, so re-runs stay idempotent.

---

Previous: [06 — Build and deploy](06-build-and-deploy.md) · Back to [README](../README.md).
