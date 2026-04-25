# Phase 2 plan — Module Federation, Godot plugin, hot upload, native AOT

> **Branch:** `feature/module-federation-and-native` (forked from `feature/game-foundation` after PR #9 merged the Snapdragon rebrand)
>
> **Audience:** future-me / a fresh agent picking this up after a context reset. Skim this top-to-bottom — every section ends with a "left off at" line that can be flipped to "done" as work lands.

---

## Why this batch

The current portal mounts each Godot WASM bundle inside an Angular `<iframe>`, ships a single Spring Boot fat-JAR, hand-edits per-game export presets, and has no in-product way to upload a new title. We're moving to:

1. **Iframe-free game canvases** — host Godot WASM directly in Angular via Module Federation remotes.
2. **Godot editor plugin** — one-click "Export to Snapdragon" that produces a deploy-ready zip.
3. **Hot upload API + admin UI** — drag a zip, get a new game (or in-place replacement) without disturbing live sessions.
4. **GraalVM native-image build** of the BFF — sub-second cold start, smaller container.

These reinforce each other: (1) gives us a clean per-game module boundary, (2) produces the artefact (3) accepts, and (4) makes the upload server responsive enough that a swap is cheap.

---

## Order of operations (dependency-aware)

```
┌─ A. Module Federation refactor ──────────────────────────────────────────┐
│  Establishes the per-game-module boundary that everything else targets.  │
│  Without this, "uploading a new game" still means rebuilding the whole   │
│  monorepo. Do this FIRST.                                                │
└──────────────────────────────────────────────────────────────────────────┘
            │
            ▼
┌─ B. Godot editor plugin ────────────────────────┐    ┌─ D. GraalVM native-image ────────┐
│  Produces the manifest+bundle zip the upload    │    │  Independent of A/B/C — can run  │
│  endpoint expects. Depends on A only because    │    │  in parallel. Pin reflection /   │
│  the manifest schema lives in the host shell.   │    │  resource hints early so they    │
└─────────────────────────────────────────────────┘    │  don't block C's polish phase.   │
            │                                          └──────────────────────────────────┘
            ▼
┌─ C. Hot upload API + admin UI ──────────────────────────────────────────┐
│  Consumes B's zips, registers them as per A's module boundary, swaps    │
│  asset_url atomically without disturbing live SSE streams. Depends on   │
│  A (module path convention) and B (zip schema).                         │
└─────────────────────────────────────────────────────────────────────────┘
```

A and D are roughly equal size; B is small; C is the largest. A blocks B+C; D blocks nothing.

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
- `frontend/nginx-tls.conf` + `frontend/nginx.conf` — add COOP/COEP headers (currently in `angular.json` `serve` only)
- Backend: `/api/assets/levels/{slug}/` is unchanged but `index.html` is no longer used by the host (the remote loads `index.js` directly)

### Left off at
**Not started.**

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
**Done in this branch.** Plugin scaffolds Project → Tools → Snapdragon menu, gathers
metadata via the dialog, shells out to `OS.get_executable_path()` with
`--export-release` to drive the chosen Web preset, and packs `manifest.json` +
`bundle/` into a zip via `ZIPPacker`. Manifest schema is `schema_version: 1` —
matching parser will land in C. The `make test-godot-plugin` target is **deferred**
(headless plugin smoke needs a fixture preset; not blocking C).

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
**Not started.**

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

**Open** (next session, before declaring D done):
1. Run `make build-backend-native` end-to-end (5–10 min, 6–8 GB RAM). Likely surfaces
   missing hints around: Casdoor JWKS verification, Springdoc OpenAPI `@Operation`
   reflection, Liquibase singleton scope. Add to `SnapdragonRuntimeHints` as they
   appear.
2. Add an `api-native` service to `docker-compose.yml` so `make e2e` can be
   parametrised to hit the native image. Verify the e2e suite passes against it.
3. Document the build prerequisites + tradeoffs in the README testing section
   (currently only the JVM build is documented).

---

## Cross-cutting hygiene

- Bump unit test count alongside each refactor; never let test count regress.
- Run `make e2e` after each major step; the spec already covers the critical flow.
- Don't forget `frontend/karma.conf.js` if test infrastructure changes (federation might need its own test config).
- Update `docs/GODOT_DEVELOPER_GUIDE.md` once B lands so the docs reflect the plugin workflow instead of "edit `export_presets.cfg`".
- Remove this plan file (`docs/PLAN-module-federation-and-native.md`) only AFTER all four refactors are merged. Until then it's load-bearing for context recovery.
