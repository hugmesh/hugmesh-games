# Phase 2 plan — Module Federation, Godot plugin, hot upload, native AOT

> **Branch:** `feature/module-federation-and-native` (forked from `feature/game-foundation` after PR #9 merged the Snapdragon rebrand)
>
> **Audience:** future-me / a fresh agent picking this up after a context reset. Skim this top-to-bottom — every section ends with a "left off at" line that can be flipped to "done" as work lands.

---

## Why this batch

The current portal mounts each Godot WASM bundle inside an Angular `<iframe>`, ships a single Spring Boot fat-JAR, hand-edits per-game export presets, and has no in-product way to upload a new title. We're moving to:

1. **Three-tier federated topology** — host shell → per-game remote modules (each its own nginx container, holding HUD + SSE) → shared parametrised WASM fragment (separate nginx, takes `?slug=` query string).
2. **Godot editor plugin** — one-click "Export to Snapdragon" that produces a deploy-ready zip + `register.sql`.
3. **Hot upload API + admin UI** — drag a zip, get a new game (or in-place replacement) without disturbing live sessions.
4. **GraalVM native-image build** of the BFF — sub-second cold start, smaller container.
5. **JWT roles + per-game access policy** — Casdoor stores role assignments + permissions; Postgres stores which game is visible to whom (all / role / individual user). Admin console flips visibility.
6. **DB-backed federation discovery + Admin/Login as their own remotes** — the host shell fetches `GET /api/federation/manifest` from PostgreSQL on bootstrap; admin console + login UI ship as their own remote modules so they can be rotated independently.

These reinforce each other: (1) gives the per-game module boundary, (2) produces the artefact (3) accepts, (4) makes the upload server responsive enough that a swap is cheap, (5) keeps the role catalog in Casdoor (single source of truth) while letting admins gate visibility from the portal, (6) means new remotes appear without redeploying the host.

---

## Target topology

Three nginx tiers, one per concern:

```
                                                  ┌──── Casdoor (auth, role catalog) ────┐
                                                  │  • role definitions                  │
                                                  │  • role assignments per user         │
                                                  │  • permission tab                    │
                                                  │  • JWT signing                       │
                                                  └──────────────────────────────────────┘
                                                                  ▲
                                                                  │ JWT roles claim
                                                                  │
  Browser ─────► [nginx#1: HOST shell] ──────────────────────────►│
   loads          • Angular host shell                            │ /api/federation/manifest
   /              • module-federation runtime                     │   (DB-backed)
                  • route guards                                  │
                       │                                          │
                       │ initFederation(remotes from manifest)    │
                       ▼                                          │
              ┌──────────────────────────────┬────────────┬───────┴──────┐
              │                              │            │              │
              ▼                              ▼            ▼              ▼
      [nginx#2: LOGIN remote]       [nginx#2: ADMIN]  [nginx#2:        [nginx#2:
       • LoginComponent              • AdminShell      games-twiat]     games-dodecahedron]
       • Casdoor RFC6749 flow        • role/access     • HUD + SSE      • chrome-less
                                       management      • level UI       • no HUD
                                                            │
                                                            │ <iframe / fetch ?slug=...>
                                                            ▼
                                          [nginx#3: WASM FRAGMENT]
                                          • parametrised by ?slug=&level=
                                          • serves the Godot bundle (wasm/pck/js)
                                          • cross-origin-isolated (COOP/COEP)
                                          • the only place Godot's HTML shell runs
```

Why nginx-per-remote: each remote can scale, cache, and be replaced independently. A `make e2e` run does not need to rebuild the host to verify a single game's changes. Uploads (via `/api/admin/games/upload`) drop a fresh bundle into the wasm-fragment volume + register a new `federation_modules` row + flip a current-version pointer atomically.

Why a SINGLE shared wasm-fragment instead of one per game: WASM bundles are large (37 MB+) and almost identical at the asset-resolution layer. Sharing one nginx that serves any bundle by `?slug=` keeps the per-remote container tiny (HUD + SSE only) while the WASM tier carries no Angular runtime.

---

## Order of operations (dependency-aware)

```
F1. Federation manifest in DB (BFF) ──┐    ┌── E. JWT roles + game_access (BFF) ──┐
    Replaces static manifest.json.    │    │   Casdoor owns role catalog;         │
    Required before any UI split.     │    │   BFF maps roles claim → authority   │
└───────────────┬──────────────────────┘    │   + filters games by access policy. │
                │                           └─────────────┬───────────────────────┘
                ▼                                         ▼
A. Frontend split into host + remotes  ◄────────  Auth gate works before
   (login / admin / portal /                       remotes are split (CharacterController
   games-* / wasm-fragment)                        keeps a single-arg legacy fallback).

         │                                           ┌─ D. GraalVM native-image ────────┐
         ├───── B. Godot plugin v2 (DONE) ───┐       │  Independent. Add reflection     │
         │      manifest.json + register.sql │       │  hints as build failures surface.│
         │      auto-emit on export.         │       └──────────────────────────────────┘
         ▼
C. Hot upload API + admin UI
   • Validates manifest schema_version
   • Runs register.sql in transaction
   • Versioned dirs + atomic swap
   • Live sessions pin to old version dir
```

E + F1 are foundational and **shipped in this branch** (see "Left off at" markers below). A is the largest remaining piece; B is done; C and D depend on A's split decisions but can scaffold in parallel.

---

## A. Module Federation refactor — kill the iframe

### What changes
Today: `app-game-canvas` mounts an `<iframe src="/api/assets/levels/{slug}/index.html">`. Each WASM bundle ships its own Godot HTML shell + JS loader; cross-frame `postMessage` is the only Angular↔Godot channel.

Target: each game ships as an **Angular remote module** (built by Webpack 5 / native federation). The host shell loads the remote at runtime, mounts a component that owns its own `<canvas>` element, and the Godot WASM is initialised against that canvas (no iframe). Communication is direct DI / Subject / Signal — no `postMessage`.

### Approach

1. **Pick a federation flavour.** Options:
   - `@angular-architects/module-federation` (Webpack 5 ModuleFederationPlugin wrapper — battle-tested, but Angular 19 moved to ESBuild for `application` builder).
   - `@angular-architects/native-federation` (newer, ESBuild-native, no webpack — recommended for Angular 19+).
   - Choose **native-federation** unless we hit a blocker; it matches the existing `application` builder.

2. **Convert the host shell.**
   - Add `federation.config.js` declaring no exposes (host only).
   - Replace `application` builder with `@angular-architects/native-federation:build` in `angular.json`.
   - Add `federation.manifest.json` listing remote URLs (will be populated by the upload API at runtime — for build-time defaults we just point at known games).
   - Bootstrap via `initFederation('/assets/federation.manifest.json')` in `main.ts` before `bootstrapApplication`.

3. **Convert the Godot canvas.**
   - The Godot HTML shell is a script tag that calls `Engine` from `index.js`. We can call this directly from Angular: load `index.js` at runtime, instantiate `new Engine(...)` against an Angular-owned `<canvas>` node, point the `executable` at `/api/assets/levels/{slug}/index` (no `.html`).
   - Each game module exposes a single `GameRootComponent` that does this. The component takes the slug as an input, owns the canvas lifecycle, cleans up on `ngOnDestroy` (`Engine.destroy()` / canvas removal).

4. **Per-game module boundary.**
   - Move TWiaT-specific code (game_bridge eval, control overlay, HUD) into a `@snapdragon/games-three-wizards` remote.
   - Dodecahedron demo becomes `@snapdragon/games-dodecahedron` — minimal, no bridge.
   - Host shell only renders the card grid + the dynamically-loaded `GameRootComponent`.

5. **Kill the iframe.** Delete `app-game-canvas`, `nginx-tls.conf` X-Frame-Options relaxation, the `_twMovementQueue` JavaScriptBridge eval path. Move SSE consumption into a service injected into both host and remote.

### Watch for

- **Cross-Origin-Embedder-Policy / Cross-Origin-Opener-Policy** — Godot WASM with threads needs `coop=same-origin, coep=require-corp`. The frontend already sets these in dev (`angular.json` serve headers). Ensure prod nginx sends them too — set explicitly in `nginx-tls.conf`.
- **WASM SharedArrayBuffer** — same COOP/COEP requirement. Without these the WebGL canvas falls back to single-threaded mode (functional but slow).
- **Canvas focus** — the iframe used to absorb keyboard. With a direct canvas, the host's keyboard listeners may swallow input. Solution: focus the canvas on click, route key events through the existing `ControlService`, drop the JavaScriptBridge round-trip entirely.
- **Memory leak** — `Engine.destroy()` doesn't always release the WASM module. On level transition, recreate the module by reloading the script — verify with Chrome heap snapshot.
- **Module Federation + standalone components** — Angular 19 standalone is fine but federation manifests still expose NgModules in many examples; double-check the `exposes` block uses `Routes` or component imports.

### Files this touches

- `frontend/angular.json` — builder swap
- `frontend/federation.config.js` — new
- `frontend/federation.manifest.json` — new
- `frontend/src/main.ts` — `initFederation` bootstrap
- `frontend/src/app/app.routes.ts` — lazy-load remote routes
- `frontend/src/app/home/home.component.{ts,html}` — replace iframe component with `GameRootComponent` host
- `frontend/src/app/game-canvas/` — delete
- `frontend/projects/games-three-wizards/` — new remote
- `frontend/projects/games-dodecahedron/` — new remote
- `frontend/nginx-tls.conf` + `frontend/nginx.conf` — add COOP/COEP headers (currently in `angular.json` `serve` only) ✓ **done**
- Backend: `/api/assets/levels/{slug}/` is unchanged but `index.html` is no longer used by the host (the remote loads `index.js` directly)

### Left off at
**Bootstrap + topology stubs in place; per-remote Angular projects still TODO.**
Done in this branch:
- COOP `same-origin` + COEP `require-corp` headers in both
  `frontend/nginx.conf` (HTTP) and `frontend/nginx-tls.conf` (HTTPS / e2e). This
  unblocks SharedArrayBuffer + threaded WASM once Godot bundles mount directly
  inside Angular.
- `main.ts` now preloads the federation manifest from
  `GET /api/federation/manifest` BEFORE `bootstrapApplication`, parking the
  result on `window.__SNAPDRAGON_FEDERATION_MANIFEST__`. `FederationService`
  reads from there if present, else lazy-fetches.
- `docker-compose.yml` carries five nginx-stub services that match the
  seeded `federation_modules` rows: `login`, `admin`, `games-three-wizards`,
  `games-dodecahedron`, `wasm-fragment`. Each serves a stub
  `/remoteEntry.json` (`{exposes:[], stub:true}`) and a placeholder index.
  `wasm-fragment` advertises COOP/COEP and a `/fragment` doc endpoint.
- Stubs sit on the compose network; only `frontend` is host-published, so
  the topology matches the eventual production shape.

**Next session — concrete first slice for the actual remote split:**
1. `npm i @angular-architects/native-federation` and run its host installer (or
   replicate manually: `federation.config.js`, swap `application` builder for
   `@angular-architects/native-federation:build` in `angular.json`).
2. Create a single demo remote (`games-dodecahedron`) under `frontend/projects/`.
   It exposes one `DodecahedronGameComponent` that takes a slug input and mounts
   the existing Godot bundle directly against an Angular-managed `<canvas>` (load
   `/api/assets/levels/dodecahedron/index.js` at runtime,
   `new Engine({canvas, executable})`). Replace the `games-dodecahedron`
   compose stub with a build of the new project.
3. Wire it into `home.component` only when `level.sceneType === 'dodecahedron'` so
   the iframe path stays alive for the TWiaT levels until the second remote lands.
4. Run `make e2e` — extend the spec to assert the dodecahedron canvas is in the
   DOM (not the iframe) and `hud-layer` is still absent.

---

## B. Godot editor plugin — "Export to Snapdragon"

### What changes
Today: each new game requires hand-editing `godot-project/export_presets.cfg`, adding a level to `Makefile.GODOT_LEVELS`, writing a Liquibase migration, and rebuilding the API.

Target: a Godot editor plugin (in `godot-project/addons/snapdragon_export/`) that adds a "Snapdragon → Export…" menu item. Click it → choose preset → output is a deploy-ready zip the upload endpoint accepts.

### Approach

1. **Scaffold the plugin.**
   - `addons/snapdragon_export/plugin.cfg` — Godot 4.6 plugin manifest
   - `addons/snapdragon_export/plugin.gd` — `EditorPlugin` subclass; registers a tool menu entry
   - `addons/snapdragon_export/export_dialog.gd` — modal: pick preset, enter slug + display name + description, choose chrome-less yes/no, hit Export

2. **Bundle layout the plugin produces.**
   ```
   <slug>-snapdragon-bundle.zip
   ├── manifest.json              # Game/level/scene metadata (schema below)
   ├── bundle/
   │   ├── index.html             # Godot's exported HTML (kept for fallback)
   │   ├── index.js
   │   ├── index.wasm
   │   ├── index.pck
   │   ├── index.png
   │   ├── index.audio.worklet.js
   │   └── ...
   └── README.md                  # Optional human-readable description
   ```

3. **`manifest.json` schema** (versioned — `schema_version: 1` so the upload endpoint can reject unknown versions cleanly):
   ```json
   {
     "schema_version": 1,
     "game": {
       "name": "Dodecahedron Demo",
       "slug": "dodecahedron",
       "description": "...",
       "sort_order": 2
     },
     "levels": [{
       "name": "Spinning Dodecahedron",
       "slug": "dodecahedron",
       "scene_type": "dodecahedron",
       "sort_order": 1,
       "asset_url": "/api/assets/levels/dodecahedron/index",
       "scene_config": {},
       "chromeless": true
     }],
     "godot_version": "4.6.1",
     "exported_at": "2026-04-25T12:00:00Z"
   }
   ```

4. **Plugin internals.**
   - Run the chosen preset's export to a temp dir (`EditorExportPlatform.export_project(...)`)
   - Build `manifest.json` from the dialog inputs
   - Use `ZIPPacker` to stream-write the zip without buffering everything in RAM (Godot 4 has `FileAccess` + `ZIPPacker`)
   - Optionally: POST the zip to a configured endpoint right from the editor (skip this for v1; users can drag-drop in the admin UI)

5. **CI smoke for the plugin** — add `make test-godot-plugin` that headlessly opens the project, runs the export against a fixture preset, asserts the zip contains `manifest.json` and the WASM bundle.

### Watch for

- **`EditorExportPlatform` API** is still considered Godot 4.x experimental; check the deprecation notes in 4.7 if we ever upgrade.
- **Asset paths** in the manifest must be ABSOLUTE web paths (`/api/assets/levels/{slug}/index`), not relative — the host shell loads them by URL.
- **Gating "chromeless"** — the dialog must default to `false` for safety; chrome-less should be opt-in per game.
- **Plugin enabled state is per-project** — `project.godot` needs an `[editor_plugins]` block with the plugin path so it survives a fresh clone.

### Files this touches

- `godot-project/addons/snapdragon_export/plugin.cfg` — new
- `godot-project/addons/snapdragon_export/plugin.gd` — new
- `godot-project/addons/snapdragon_export/export_dialog.gd` — new
- `godot-project/addons/snapdragon_export/manifest_builder.gd` — new
- `godot-project/project.godot` — enable plugin under `[editor_plugins]`
- `Makefile` — add `make test-godot-plugin` (optional; could be deferred)

### Left off at
**Done in this branch (extended for v2).** Plugin scaffolds Project → Tools →
Snapdragon menu, gathers metadata via the dialog, shells out to
`OS.get_executable_path()` with `--export-release` to drive the chosen Web
preset, and packs `manifest.json` + `bundle/` + **`register.sql`** into a zip
via `ZIPPacker`.

`manifest.json` is now `schema_version: 2`. New keys vs v1:
- `federation` block — slug, kind (`remote-game`), `remote_entry_url`
  (defaults to `http://games-<slug>:8080/remoteEntry.json` so docker-compose
  can pick it up by service name), `exposed_module: './GameRoot'`, version,
  metadata flags (`hud`, `sse`, `chromeless`).
- `access.default_scope: 'all'` — registers the game open by default; admins
  narrow this from the admin console afterwards.

`manifest_builder.to_register_sql()` emits the parameterised SQL the BFF's
upcoming GameUploadController will run inside the install transaction:
- `INSERT INTO games … ON CONFLICT (slug) DO UPDATE`
- `INSERT INTO levels … FROM games WHERE slug = …`
- `INSERT INTO federation_modules … ON CONFLICT (slug) DO UPDATE` (so a
  re-upload refreshes `remote_entry_url` + `version` without losing access
  policy rows referencing the row's id)
- `INSERT INTO game_access (scope='all') ON CONFLICT DO NOTHING`

Single-quote escaping is at the GDScript layer; the BFF still validates
`schema_version`, slug regex, and zip-slip-safe paths before executing.

---

## C. Hot upload API + admin UI — drag a zip, ship a game

### What changes
Today: adding a game = code changes + Liquibase migration + Docker rebuild.

Target: an admin uploads a zip from the browser → BFF unpacks, validates, registers, hot-swaps. Live sessions keep playing the old version until they reconnect.

### Approach

1. **Storage layout (key insight: versioning).** Each upload writes to a NEW directory keyed by content hash, never overwriting in place:
   ```
   /var/snapdragon/games/<slug>/
     ├── current -> v3                  (atomic symlink swap)
     ├── v1/                            (old — still served to active sessions)
     ├── v2/
     └── v3/
         ├── manifest.json
         └── bundle/
   ```
   Move the games dir OUT of the API jar's classpath into a host-mounted volume (`/var/snapdragon/games`). Update `AssetController` to read from the filesystem instead of `ClassPathResource`.

2. **Upload pipeline (atomic, session-safe):**
   ```
   POST /api/admin/games/upload (multipart: zip file)
     │
     ├─ stage:    write to /var/snapdragon/staging/<random>/
     ├─ validate: schema_version, manifest.json well-formed, expected files
     │            present, slug regex /^[a-z0-9-]+$/
     ├─ hash:     compute SHA-256 of bundle/ tree → version dir name
     ├─ install:  rename(staging, /var/snapdragon/games/<slug>/<version>)
     ├─ register: txn — INSERT/UPDATE games + levels + level_assets, set
     │            current_version_dir on the level row
     ├─ flip:     rename(current, .current.tmp) → rename(<version>, current)
     │            so active sessions keep reading their pinned version dir
     │            (asset_url resolution captures the dir at session start)
     └─ cache:    publish 'snapdragon.catalog.invalidated' on Cassandra so
                  every BFF pod refreshes its catalog cache
   ```

3. **Session-safety contract.** When a player starts a game, the SSE session captures the `version_dir` path (e.g. `/var/snapdragon/games/cave/v3`) and uses THAT path until the session ends. New sessions started after the swap pick up `current` (now pointing at v4). No active stream sees a half-replaced bundle.

4. **DB schema changes.**
   ```sql
   ALTER TABLE levels ADD COLUMN current_version_dir TEXT;
   CREATE TABLE game_uploads (
     id BIGSERIAL PRIMARY KEY,
     slug TEXT NOT NULL,
     version_dir TEXT NOT NULL,
     uploaded_by TEXT NOT NULL,
     uploaded_at TIMESTAMPTZ NOT NULL,
     manifest_json JSONB NOT NULL,
     superseded_at TIMESTAMPTZ
   );
   CREATE INDEX idx_uploads_slug_active
     ON game_uploads (slug, uploaded_at DESC) WHERE superseded_at IS NULL;
   ```

5. **AssetController refactor.** Switch from `ClassPathResource` to `Files.newInputStream(...)` against the resolved version dir. Keep the same `/api/assets/levels/{slug}/{file}` URL — clients don't care.

6. **Admin UI.**
   - New `frontend/projects/admin-games/` (a federated remote? or just a host-shell route under `/admin/games`)
   - `<input type="file" accept=".zip">` with drag-drop zone
   - On upload: progress bar, validation result (manifest summary), confirm button, then `POST`
   - List view: every game's current version + upload history; "rollback to vN" button
   - Replace existing game: same upload form, slug match → upload registers as a new version, current pointer flips

7. **Auth.** Require `ROLE_ADMIN` for upload + rollback endpoints (`SecurityConfig` already gates `/api/admin/**`). The admin UI route should also check `auth.isAdmin()`.

### Watch for

- **Filesystem permissions** — the API container runs as root by default in eclipse-temurin; if we drop privileges later, ensure `/var/snapdragon/games` is writable.
- **Disk pressure** — keep last N versions per slug, prune older ones (cron job in `CassandraSyncService`).
- **Zip slip** — when extracting, reject any entry whose normalized path escapes the staging dir. Use `java.nio.file.Path.normalize()` and `startsWith()` checks; never `new File(parent, entryName)` blindly.
- **Streaming the upload** — `multipart/form-data` with WebFlux uses `FilePart`; pipe it through `transferTo(Path)` instead of buffering in memory.
- **Cassandra cache invalidation** — make sure SSE sessions read the version dir from session state, not from the cache; cache only metadata.
- **Atomicity on the symlink flip** — `Files.move(REPLACE_EXISTING, ATOMIC_MOVE)` is atomic on POSIX; Windows would need different handling but the BFF is Linux-only in compose.

### Files this touches

- `backend/src/main/java/com/threewizards/controller/GameUploadController.java` — new
- `backend/src/main/java/com/threewizards/service/GameUploadService.java` — new (staging, validation, install, flip)
- `backend/src/main/java/com/threewizards/service/AssetController.java` — refactor to filesystem-resolved version dir
- `backend/src/main/java/com/threewizards/service/SessionService.java` — capture `version_dir` on session start
- `backend/src/main/resources/db/changelog/018-game-uploads.xml` — new (`game_uploads` table + `levels.current_version_dir`)
- `docker-compose.yml` — mount `/var/snapdragon/games` volume on api
- `frontend/src/app/admin/game-upload.component.{ts,html,scss}` — new admin UI
- `frontend/src/app/admin/admin.component.html` — link to game-upload route

### Left off at
**Backend done; admin UI live; integration test pending.** Done in this branch:
- Migration 021 (`game_uploads` table + `games.current_version_dir` column).
- `GameUploadService` accepts a multipart bundle zip, validates manifest
  schema_version=2 + slug regex + zip-slip-safe paths, bounds entry count
  (500) + total bytes (200 MB), SHA-256s the bundle/ tree → version dir
  name, atomic-move stages → `<gamesDir>/<slug>/<sha-prefix>`, runs
  `register.sql` inside ONE R2DBC transaction (statement-by-statement
  split that respects single-quoted strings), UPDATEs
  `games.current_version_dir`, marks prior uploads superseded.
- `POST /api/admin/games/upload` (ROLE_ADMIN) is the REST surface;
  `GET /api/admin/games/{slug}/uploads` returns the rollback history.
- `AssetController` two-tier resolver: filesystem under
  `current_version_dir` first, classpath fallback. Lets newly uploaded
  games serve without rebuilding the API image.
- `docker-compose.yml` mounts a `games_uploads` named volume on the
  api service.
- Admin UI at `/admin/games` (route + nav link gated on isAdmin) with
  drag-drop bundle upload, rollback history, per-game access controls
  (all/role/user) using the existing Casdoor role-list proxy.

**Open follow-ups:**
1. Integration test that POSTs a fixture zip through the upload
   endpoint, verifies the rows landed in `games_uploads` + `games`
   + `federation_modules` + `game_access`, and that the asset
   resolver serves from the new version dir.
2. Active-version pinning for in-flight launches: currently the
   AssetController reads `current_version_dir` per request, so a swap
   mid-launch could swap the WASM under the player's feet. Capture
   `version_dir` on the `game_launches` row at launch time and serve
   from THAT (PLAN section A's session-safety contract).
3. Disk-pressure cap: keep last N uploads per slug, prune older
   `version_dir` trees in a scheduled task.

---

## D. GraalVM native-image build for the BFF

### What changes
Today: API container is `eclipse-temurin:21-jre`, JVM cold start ~3 s, RSS ~400 MB.

Target: native binary built by Spring Boot's `native` profile, cold start <100 ms, RSS ~100 MB, container ~150 MB.

### Approach

1. **Add `native` profile to `pom.xml`.**
   ```xml
   <profiles>
     <profile>
       <id>native</id>
       <build>
         <plugins>
           <plugin>
             <groupId>org.graalvm.buildtools</groupId>
             <artifactId>native-maven-plugin</artifactId>
             <executions>
               <execution>
                 <id>build-native</id>
                 <goals><goal>compile-no-fork</goal></goals>
                 <phase>package</phase>
               </execution>
             </executions>
           </plugin>
         </plugins>
       </build>
     </profile>
   </profiles>
   ```
   And add the `org.springframework.boot:spring-boot-starter-parent` declares `native-maven-plugin` already in 3.4 — verify versions.

2. **Reflection / resource hints.** Spring Boot's AOT plugin generates most hints automatically, but reactive types and Jackson serializers often need explicit hints.
   - `@RegisterReflectionForBinding` on every DTO that crosses the JSON boundary (`CharacterDto`, `GameWithLevelsDto`, `AvailableLevelDto`, `SessionDto`, `SceneDto`, all SSE event records).
   - `@ImportRuntimeHints` for any Liquibase migration class loading or dynamic class lookups.
   - Resource hints for the changelog files (`classpath:db/changelog/**/*.xml`) — Spring Boot's hints generally cover this but verify.

3. **R2DBC + Cassandra reactive driver.** Both have known native-image quirks:
   - R2DBC PostgreSQL driver: works in native, but ensure the connection factory beans don't lazy-load classes by name.
   - Cassandra reactive driver (Datastax Java Driver 4.x): has `META-INF/native-image` config since 4.18. Pin to a recent enough version.

4. **Liquibase native compatibility.** Liquibase 4.27+ has reasonable native support but you may need `liquibase.scope.SingletonScopeManager` hints. If we hit blockers, run migrations OUT-OF-PROCESS (a separate init container) instead of in the BFF — cleaner anyway.

5. **WebFlux + Netty + JVM TLS.** Should work natively; ensure `JAVA_TOOL_OPTIONS` doesn't pass JVM-only flags.

6. **Multi-stage Dockerfile.**
   ```dockerfile
   FROM ghcr.io/graalvm/native-image-community:21 AS build
   WORKDIR /app
   COPY .mvn .mvn
   COPY mvnw pom.xml ./
   RUN ./mvnw dependency:go-offline -q
   COPY src src
   RUN ./mvnw -q -Pnative native:compile -DskipTests

   FROM debian:bookworm-slim
   WORKDIR /app
   COPY --from=build /app/target/snapdragon-gaming-bff app.bin
   ENTRYPOINT ["/app/app.bin"]
   ```

7. **CI smoke.** Native-image builds are slow (~5–10 min). Don't run on every PR — gate behind `[native]` in commit message OR a separate workflow that runs on `main`.

8. **Keep the JVM build as a fallback.** Add `make build-backend-native` as a parallel target; the existing `make build-backend` continues to produce the JAR. This way native is opt-in until it's proven.

### Watch for

- **Reflection on records**: Java 21 records work fine but JSON deserialization of nested generic records (e.g. `Mono<Map<String,Object>>`) can need explicit hints. Spring's AOT plugin usually handles this; if not, add `RuntimeHints` callbacks.
- **Class loaders**: native-image is single-classloader. Any code that constructs a `URLClassLoader` (rare, but Liquibase has done this) breaks.
- **Build memory**: native-image needs ~6–8 GB of RAM during compilation. CI runners may need bumping.
- **Springdoc OpenAPI** (we're on 2.7.0): has known native-image issues around `@Operation` reflection. Test that `/v3/api-docs` still resolves under native.
- **Casdoor JWKS fetch at startup**: works fine, but make sure `WebClient` instantiation isn't lazy in a way native loses.

### Files this touches

- `backend/pom.xml` — add `native` profile
- `backend/src/main/java/com/threewizards/aot/SnapdragonRuntimeHints.java` — new (custom hints registrar)
- `backend/src/main/java/com/threewizards/model/*.java` — add `@RegisterReflectionForBinding` on DTOs that cross JSON
- `backend/Dockerfile.native` — new (parallel to existing)
- `Makefile` — add `build-backend-native` target
- `docker-compose.yml` — optionally an `api-native` profile that uses the native image
- README + this plan: document tradeoffs (slow build, no class reload in dev)

### Left off at
**Scaffolded, not yet built end-to-end.** In this branch:
- `pom.xml` has the `native` profile (spring-boot-maven-plugin `process-aot` +
  `org.graalvm.buildtools:native-maven-plugin`, plus a tiny `--initialize-at-run-time`
  list for jansi to suppress the AnsiConsole startup-init failure).
- `SnapdragonRuntimeHints` registers reflection for the 13 JSON-boundary record DTOs
  (`MemberCategory.INVOKE_DECLARED_CONSTRUCTORS|METHODS` + `DECLARED_FIELDS`) plus
  the `db/changelog/*.xml` resource pattern. Wired via `@ImportRuntimeHints` on
  `ThreeWizardsApplication`.
- `backend/Dockerfile.native` — multi-stage `ghcr.io/graalvm/native-image-community:21`
  → `debian:bookworm-slim` runtime.
- `Makefile`: `make build-backend-native`.

**Open** (run when host has 6–8 GB free for the AOT compiler):
1. Run `make build-backend-native` end-to-end (5–10 min). Likely surfaces
   missing hints around: Casdoor JWKS verification, Springdoc OpenAPI `@Operation`
   reflection, Liquibase singleton scope. Add to `SnapdragonRuntimeHints` as they
   appear.
2. ✓ **Done in this branch:** `docker-compose.native.yml` overlay swaps the
   JVM api service for the native binary (`docker compose -f docker-compose.yml
   -f docker-compose.native.yml up`). `make e2e-native` runs the full
   Playwright suite against the native image — same pipeline as `make e2e`
   but with the overlay added. The build itself isn't run on PR CI; gate
   behind a `[native]` commit-message marker or a separate workflow.
3. Document the build prerequisites + tradeoffs in the README testing section
   (currently only the JVM build is documented).

---

## E. JWT roles + per-game access policy

### What changes
Today: `SecurityConfig.casdoorAuthoritiesConverter` reads only `isAdmin`. There's no per-game visibility control — every player sees every game.

Target: Casdoor owns the role catalog (admin assigns via the Casdoor web UI). The JWT carries a `roles` array claim; the BFF maps each entry to a `ROLE_<UPPER>` authority. Postgres holds `game_access` rows describing who can see each game (all / role / individual user). `ProgressionService` intersects the catalog against the principal's authorities.

### Boundary
Casdoor is the single source of truth for roles + assignments. Postgres `scope_value` strings are *just* role names — no FK, no Postgres-side roles table. If the role is renamed in Casdoor, the corresponding `game_access` row goes stale until an admin updates it. That's the desired tradeoff: simpler join, fewer cross-system migrations.

### Files this touches
- `backend/src/main/java/com/threewizards/config/SecurityConfig.java` — `casdoorAuthoritiesConverter` reads `roles` (array) → `ROLE_<UPPER>` and `permissions` (array) → raw authority. Legacy `isAdmin` boolean still produces `ROLE_ADMIN` for backward-compat.
- `casdoor/init_data.json.tpl` — seeds three Casdoor roles (`admin`, `player`, `uploader`) and one permission (`upload-games` bound to admin + uploader). Admins can add more from the Casdoor admin UI without touching this file.
- `backend/src/main/resources/db/changelog/019-game-access.xml` — `game_access` table: `(game_id, scope, scope_value, granted_by, granted_at)` with a unique-on-triple constraint. Backfill row inserts `scope='all'` for every existing game so deploy is a no-op for current players.
- `backend/src/main/java/com/threewizards/model/GameAccess.java` — entity.
- `backend/src/main/java/com/threewizards/repository/GameAccessRepository.java` — adds `findVisibleGameIdsForPrincipal(roleNames[], userId)` returning the union of `scope='all'`, `scope='role' AND scope_value = ANY(:roles)`, and `scope='user' AND scope_value = :userId`.
- `backend/src/main/java/com/threewizards/controller/GameAccessController.java` — `@PreAuthorize("hasRole('ADMIN')")` on GET / POST / DELETE under `/api/admin/games/{slug}/access`. Body: `{"scope":"all"}` | `{"scope":"role","value":"<roleName>"}` | `{"scope":"user","value":"<casdoorUserId>"}`.
- `backend/src/main/java/com/threewizards/service/ProgressionService.java` — three-arg overload `availableGamesWithLevels(characterId, roleNames, userId)`. Single-arg overload kept for `CharacterController` dashboard until that endpoint is also gated.
- `backend/src/main/java/com/threewizards/controller/GameController.java` — `/api/games/available/{characterId}` now plumbs the JWT through.

### Left off at
**Done in this branch.** `make test-backend` green after the refactor; the
existing 1 unit test (`CharacterDtoTest`) still passes. Open follow-ups:
1. Plumb the JWT through `CharacterController` dashboard so role-gated games
   don't leak into the player's own progress view.
2. Add an admin endpoint that lists Casdoor role names (so the admin UI's
   "grant role" dropdown isn't a free-text input). Easiest path: proxy
   Casdoor's `/api/get-roles?owner=threewizards` from the BFF.
3. Add a unit test for `GameAccessController` (validation rules + idempotent
   grant) once the e2e admin UI is in place.

---

## F. DB-backed federation discovery + Admin/Login as remotes

### What changes
Today: there is no federation manifest — the SPA is monolithic.

Target:
- `federation_modules` table holds one row per remote module (host, login, admin, per-game, wasm-fragment).
- `GET /api/federation/manifest` returns the active rows; the Angular host bootstrap calls it before `bootstrapApplication` and feeds the result into `initFederation(...)`.
- An uploaded game's `register.sql` (from the Godot plugin's v2 manifest) inserts its own `federation_modules` row, so a fresh upload becomes a discoverable remote on the next manifest fetch — no host redeploy.

### Files this touches
- `backend/src/main/resources/db/changelog/018-federation-modules.xml` — table + indexes + initial seed for `host`, `login`, `admin`, `games-three-wizards`, `games-dodecahedron`, `wasm-fragment`.
- `backend/src/main/java/com/threewizards/model/FederationModule.java` + `repository/FederationModuleRepository.java` — entity + reactive repo.
- `backend/src/main/java/com/threewizards/model/FederationManifestDto.java` — wire shape (record).
- `backend/src/main/java/com/threewizards/controller/FederationController.java` — `GET /api/federation/manifest`. Public on purpose: the host shell needs the `login` remote URL before any user authenticates.

### Left off at
**Backend done in this branch.** The host SPA does not yet consume the
manifest — that lands when section A's frontend split happens. Open before
declaring F done:
1. Frontend bootstrap calls `/api/federation/manifest` and feeds it into
   `initFederation(...)`.
2. Verification: hit `curl https://localhost:8080/api/federation/manifest`
   from the e2e flow (admin only? probably keep public — the host can't load
   without it). Update e2e spec to assert the response shape.
3. `docker-compose.yml` adds the placeholder service stubs (`login`, `admin`,
   `games-three-wizards`, `games-dodecahedron`, `wasm-fragment`) so the
   seed URLs in 018-2 actually resolve once those services have content.
   Until then the seed URLs are aspirational placeholders; manifest-fetched
   remotes will fail at `initFederation` time without a backing service.

---

## G. Per-game project layout + canonical Rust bridge

### What changed
The monorepo's single `godot-project/` was hosting all three scenes (cave +
field + dodecahedron) under one project, and `game/rust/` held three Rust
classes — two of which (`CaveScene`, `PlayerController`) were placeholders
duplicating GDScript work. The Rust crate was named `three_wizards`.

Target reached this branch:
- `extensions/snapdragon-bridge/` — reusable Rust bridge crate. Just one
  class (`HostBridge`) that emits typed signals (`movement_received`,
  `world_received`, `character_received`, `time_received`,
  `scene_received`, `scene_ready`, `input_egress`) carrying stringly-typed
  JSON payloads. A `streaming` flag (set by the GDScript glue once it
  detects `window._twSceneEvents`) switches between Streaming and
  Standalone modes; Standalone fakes a `scene_ready` from `_ready` and
  echoes `send_input` back through `movement_received` so the Godot
  editor's F5 path drives the player without any of the BFF / Casdoor /
  SSE infrastructure running.
- `games/three-wizards-in-a-trenchcoat/` — flagship JRPG project with
  `cave.tscn` + `field.tscn` + 6 GDScript files + a `Cave/Field` export
  preset pair.
- `games/dodecahedron-demo/` — chrome-less demo project with
  `dodecahedron.tscn` + `dodecahedron_spinner.gd` + a single `Dodecahedron`
  export preset.
- Each game project carries its own copy of the bridge `.gdextension`
  manifest, the `addons/snapdragon_export/` plugin, and a `bin/` dir the
  Makefile populates with the freshly-built bridge binary at every
  `make build-rust`. That keeps each project openable standalone in the
  Godot editor, so a level designer can clone, open, hit F5, and drive
  the player without spinning up the rest of the stack.

### Files this touches
- `extensions/snapdragon-bridge/{Cargo.toml,Cargo.lock,src/{lib,host_bridge}.rs,bin/.gitkeep,snapdragon_bridge.gdextension}`
- `games/three-wizards-in-a-trenchcoat/{project.godot,export_presets.cfg,scenes/,scripts/,addons/,bin/,snapdragon_bridge.gdextension}`
- `games/dodecahedron-demo/{project.godot,export_presets.cfg,scenes/,scripts/,addons/,bin/,snapdragon_bridge.gdextension}`
- `Makefile` — per-project iteration via shell `case` (bash variable names
  can't have dashes, so the original `$(PRESETS_$(GAME))` lookup didn't
  resolve); `make build-rust` now copies the binary into every game's
  `bin/` so each project opens standalone in the editor.
- `.vscode/{launch.json,settings.json}` and `.gitignore` swept onto the
  new paths.

### Left off at
**Done in this branch + IDE local-play unblocked.** Updates:
- TWiaT's `game_bridge.gd` now has an explicit local-play branch:
  on non-web builds it skips SSE entirely, fires a stub
  `scene_updated` so `scene_loader.gd`'s bootstrap runs, and routes
  keyboard input straight back through `movement_updated` /
  `state_updated`. The player controller consumes the same signals
  it always did, so editor F5 drives the wizard from the keyboard
  without spinning up the BFF / Casdoor / Postgres / Cassandra.
- `extensions/snapdragon-bridge/example/bridge_glue.gd` is a
  reference template for new games that want to use the Rust
  `HostBridge` directly instead of the legacy GDScript bridge. Drop
  a copy into `scripts/bridge_glue.gd`, attach to a `HostBridge` node,
  connect game scripts to its signals.

**Open follow-ups:**
1. Migrate the existing TWiaT scripts (`player_controller`,
   `scene_loader`, `camera_controller`, `fairy`) onto the
   `HostBridge` Rust node so the legacy `game_bridge.gd` can retire.
   Each scene update can move independently.
2. Web target for the Rust bridge: the WASM cargo target falls through
   with a warning if emscripten isn't installed; per-export-preset
   `extensions_support` stays false. Light-weight; fix when emscripten
   tooling is set up.

---

## H. Cryptographic launch UIDs + per-game roles + JWT through dashboard

### What changed
- `POST /api/games/{slug}/launch` (auth required) mints a 256-bit URL-safe
  uid, persists a row in `game_launches` (migration 020) with a 15-minute
  TTL, returns `{uid, expiresAt, gameSlug}`. The federated remote holds
  the uid and uses it as a path component when fetching the bundle:
  `GET /api/assets/levels/{slug}/{uid}/index.{wasm,pck,html,js}`. The
  AssetController validates against active rows and 404s on mismatch /
  expired / wrong-slug. A feature flag
  (`app.assets.require-launch-uid`, default `false`) enforces the gated
  path globally once the federated client is in place.
- `DELETE /api/games/launches/{uid}` lets the player terminate their own
  launch (owner-checked via `casdoorId == jwt.sub`).
- Per-game Casdoor roles seeded: `play-three-wizards`, `play-dodecahedron`
  in `casdoor/init_data.json.tpl` (assigned to admin + player by default).
  `make seed-roles` provides an idempotent post-deploy way to add roles
  for newly uploaded games (curls Casdoor's `add-role` admin API with a
  client_credentials token).
- `GameController.available()` and `CharacterController.dashboard()` both
  plumb the JWT through and call the three-arg
  `ProgressionService.availableGamesWithLevels(charId, roles, userId)` so
  per-game `game_access` policy applies on every catalog response.
- `RoleProxyController` (`GET /api/admin/roles`, ROLE_ADMIN only) proxies
  Casdoor's role catalog so the upcoming admin UI's "grant role" dropdown
  has a known set to choose from. Casdoor stays the source of truth — no
  caching on the BFF side.
- Frontend: `FederationService` fetches `/api/federation/manifest` once
  per page load (cached via `shareReplay(1)`), exposes `remoteForGame(slug)`
  and `firstByKind(kind)` resolvers. 4 unit tests cover happy-path,
  cache, lookup-miss, and HTTP-error fallback. Bootstrap consumption
  lands when section A's federation runtime is wired in.

### Files this touches
- `backend/src/main/resources/db/changelog/020-game-launches.xml`
- `backend/src/main/java/com/threewizards/model/GameLaunch.java`
- `backend/src/main/java/com/threewizards/repository/GameLaunchRepository.java`
- `backend/src/main/java/com/threewizards/controller/{GameLaunchController,RoleProxyController}.java`
- `backend/src/main/java/com/threewizards/controller/{AssetController,CharacterController,GameController}.java` — UID gate + JWT plumb-through
- `backend/src/main/java/com/threewizards/service/ProgressionService.java` — three-arg overload (already present from previous slice)
- `backend/src/test/java/com/threewizards/controller/GameAccessControllerTest.java` — 4 validation-rule tests
- `casdoor/init_data.json.tpl` — `play-three-wizards`, `play-dodecahedron` roles
- `Makefile` — `make seed-roles` (curl + jq based)
- `frontend/src/app/services/federation.service.{ts,spec.ts}` — manifest service + 4 unit tests

### Left off at
**Done in this branch.** Open follow-ups:
1. Flip `app.assets.require-launch-uid=true` once the federated remote
   actually mints + uses a uid. Today the iframe-based SPA still hits the
   legacy non-uid path, so flipping early would 403 every game card.
2. Admin endpoint that revokes someone else's launch
   (`/api/admin/launches/{uid}` with ROLE_ADMIN). Stub: today only
   self-termination works.
3. Cron / scheduled cleanup of `game_launches` rows past their
   `expires_at` to keep the table bounded. Cassandra TTL would do the same
   if we move launch state there later.

---

## Cross-cutting hygiene

- Bump unit test count alongside each refactor; never let test count regress.
- Run `make e2e` after each major step; the spec already covers the critical flow.
- Don't forget `frontend/karma.conf.js` if test infrastructure changes (federation might need its own test config).
- Update `docs/GODOT_DEVELOPER_GUIDE.md` once B lands so the docs reflect the plugin workflow instead of "edit `export_presets.cfg`".
- Remove this plan file (`docs/PLAN-module-federation-and-native.md`) only AFTER all six refactors are merged. Until then it's load-bearing for context recovery.
