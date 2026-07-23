# Module federation, native AOT, hot upload, JWT roles, federated routes & games

> **PR helper.** This file is checked in alongside the branch so a PR can be opened without recomputing the summary. To open the PR:
>
> ```
> gh pr create --base main --head feature/module-federation-and-native \
>   --title "Module federation, native AOT, hot upload, JWT roles, federated routes & games" \
>   --body-file docs/PR-module-federation-and-native.md
> ```
>
> Or paste the body below into the GitHub web UI.

---

## Summary

Phase-2 of the portal: Angular shell becomes a native-federation host, every game runs as its own module, the BFF compiles to a GraalVM native image, hot uploads land atomically into Postgres, and per-game access policy is enforced through Casdoor JWTs. 91 commits since `main`, all eight PLAN sections (A–H) at a deliberate end state.

Top-line outcomes:

- **Module Federation host** loading four real remotes (`login`, `admin`, `games-three-wizards`, `games-dodecahedron`) plus a `wasm-fragment` asset-tier nginx — every BFF route that used to be in-tree (`/login`, `/admin`, `/admin/games`) and every Godot game (cave, field, dodecahedron) is now served from a separately deployable bundle.
- **Hot upload pipeline** (`POST /api/admin/games/upload`) — drag a HugMesh-export zip in, BFF validates manifest_json schema v2 + slug + zip-slip-safe paths, SHA-256s the bundle, atomically moves it to a versioned dir, runs the bundle's `register.sql` inside a single R2DBC transaction that lands `games`/`federation_modules`/`game_access` rows, and supersedes prior uploads. Backed by a `GameUploadPruner` scheduled task (`app.uploads.versions-to-keep=3` default).
- **GraalVM native image** of the BFF — `Dockerfile.native` + native compose overlay + `make e2e-native` target; sub-second cold start, smaller container.
- **JWT roles + per-game access policy** — `game_access` table (`scope: all|role|user`) gates `/api/games` listing; `RoleProxyController` proxies Casdoor's role catalog so the admin UI's "grant role" dropdown stays live; admin dashboard is JWT-gated end to end.
- **DB-backed federation discovery** — `federation_modules` table + `FederationController.GET /api/federation/manifest`; the host fetches it BEFORE `bootstrapApplication` so a new remote can come online without redeploying the host.
- **Cryptographic launch UIDs** — `POST /api/games/{slug}/launch` mints a 256-bit URL-safe `launch_uid` per session (with `version_dir` snapshot), threaded through federated game-card clicks for traceability + admin force-revoke + scheduled `GameLaunchSweeper`.
- **Per-game Godot project layout** — `games/three-wizards-in-a-trenchcoat/` + `games/dodecahedron-demo/` replace the single `godot-project/`; the Rust GDExtension lives in a reusable `extensions/hugmesh-bridge/` and is copied into every game's `bin/`.
- **WASM Rust target enabled** — `extensions/hugmesh-bridge` builds for `wasm32-unknown-emscripten` (3.3 MB `hugmesh_bridge.wasm`) via emscripten 5.0.6; godot-rust's `experimental-wasm` feature is forwarded by the crate's default `wasm-opt-in` feature so a plain `cargo build --target wasm32-unknown-emscripten` works.
- **Coverage**: 73 frontend Karma specs (federation surface, NavigationError banner, loadRemote helper, auth guard, federated remote unit specs for login/admin/upload/three-wizards/dodecahedron) + 11 `GameAccessControllerTest` specs + a Postgres-Testcontainer–backed `GameUploadIntegrationTest` (1/1) that exercises the upload install transaction end to end.

## Plan section status

| Section | Status | Notes |
|---|---|---|
| A. Module Federation | **Done** | All routes + all games federated. `wasm-fragment` direct-serves Godot bundles; `<app-game-canvas>` stays as a fallback for sceneTypes outside `SCENE_TYPE_TO_GAME_SLUG`. |
| B. Godot editor plugin | **Done** | `addons/hugmesh_export/` produces a v2 manifest + register.sql + bundle zip in both stock games. |
| C. Hot upload API + admin UI | **Done** | Pipeline + UI + active-version pinning + disk-pressure cap + Postgres-backed integration test (C.1). |
| D. GraalVM native-image | **Done** | Build profile + Dockerfile + native compose overlay + `make e2e-native` + README docs. |
| E. JWT roles + access policy | **Done** | `game_access` table + `RoleProxyController` + JWT-gated dashboard + 11 `GameAccessControllerTest` specs. |
| F. DB-backed federation discovery | **Done** | `federation_modules` + `FederationController` + frontend manifest preload. |
| G. Per-game project layout | **Done** | `extensions/hugmesh-bridge/` + per-game projects + HostBridge migration; WASM cargo target builds with emscripten 5.0.6 (G.2 closed). |
| H. Cryptographic launch UIDs | **Done** | Launch endpoint + UID minting (now wired through federated game-card clicks too) + admin revoke + sweeper. The `app.assets.require-launch-uid=true` flag stays defense-in-depth on the BFF asset path; federated WASM bundles are public under `wasm-fragment` direct-serve by design. |

## What landed by component

### Frontend host (`frontend/`)

- `main.ts` calls `initFederation(...)` BEFORE `bootstrapApplication` (split into `bootstrap.ts` because `@angular/*` static imports can't resolve before the import map exists).
- `loadRemoteModule` is stashed on `globalThis.__HUGMESH_LOAD_REMOTE_MODULE__` — keeps `@angular-architects/native-federation` out of karma's static graph and out of route-handler bundles.
- `app.routes.ts` exports a typed `loadRemote<T>(remoteName, exposedModule, exportName)` helper used for `/login`, `/admin`, and `/admin/games`.
- `app.component.ts` shows a NavigationError banner with retry when a federated route fails to load (route → human label via longest-prefix-first `FEDERATED_ROUTE_LABELS`).
- `HomeComponent` is the federation fork point: `SCENE_TYPE_TO_GAME_SLUG` maps cave/field → `three-wizards`, dodecahedron → `dodecahedron`; federated games receive a fresh `launch_uid` per click; `TW_LEVEL_TRANSITION` `postMessage` listener lifted to host level so it fires regardless of which renderer mounts.

### Frontend remotes (`frontend/projects/`)

- `login/` — `LoginRemoteComponent` with full host branding port; inline `/api/auth/token` POST + sessionStorage write to `tw_auth` (mirrors `AuthService.StoredAuth` shape).
- `admin/` — Exposes `./AdminShell` + `./UploadShell` from one remote so `/admin` and `/admin/games` share a build. DTOs (`AdminStats`, `PerUserStats`, `PlayerSession`, `InstalledUpload`, `GameUploadHistory`, `RoleSummary`, `AccessGrantRequest`) redeclared inside the remote (TS types erased at runtime, can't be shared via federation).
- `games-three-wizards/` — `<app-three-wizards-remote>` Web Component (createCustomElement). `@Input levelSlug + levelId + launchUid` → `/_remotes/wasm-fragment/${slug}/index.html?level_id=…&launch_uid=…&t=…`.
- `games-dodecahedron/` — Same pattern, single chrome-less scene.
- `federation-base.js` — shared `hugmeshHost` / `hugmeshRemote` / `STD_SKIP` / `STD_SHARED` helpers used by all 5 federation configs.

### `wasm-fragment/` (new)

Asset-tier nginx that bakes `backend/src/main/resources/assets/levels/` and serves at `/_remotes/wasm-fragment/<slug>/index.html` with COOP `same-origin` + COEP `require-corp` + CORP `cross-origin` (the CORP header was required to avoid `ERR_BLOCKED_BY_RESPONSE` when the host iframes the remote).

### Backend BFF (`backend/`)

New controllers/services:

- `FederationController.GET /api/federation/manifest` — Reads `federation_modules` rows.
- `GameLaunchController` — `POST /api/games/{slug}/launch`, `DELETE /api/games/launches/{uid}` (self), `DELETE /api/admin/launches/{uid}` (admin force-revoke).
- `GameUploadController.POST /api/admin/games/upload` + `GET /api/admin/games/{slug}/uploads` — Hot upload + history.
- `GameAccessController` — `POST/GET/DELETE /api/admin/games/{slug}/access` over the `game_access` table.
- `RoleProxyController.GET /api/admin/roles` — Server-side proxy of Casdoor's `/api/get-roles?owner=threewizards`.
- `GameUploadService` — Pipeline: validate → SHA-256 → atomic move → R2DBC tx (insert `game_uploads`, run `register.sql`, UPDATE `games.current_version_dir`, supersede prior).
- `GameUploadPruner` — Scheduled task; keeps `app.uploads.versions-to-keep=3` newest version dirs per slug, prunes the rest off disk + via the supersede flag.
- `GameLaunchSweeper` — Scheduled task; expires/closes stale launches.
- `GameTimeService` + `GameStateService` now `@DependsOn("liquibase")` so `@PostConstruct` can't race Liquibase against `@SpringBootTest` parallel bean init.
- Liquibase: `018-federation-modules`, `019-game-access`, `020-game-launches`, `021-game-uploads`, `022-federation-relative-urls`, `023-launch-version-pin`, `024-game-uploads-manifest-text` (jsonb→text + `setval(games_id_seq)`).

### Per-game Godot projects (`games/`)

- Replaces `godot-project/`. `games/three-wizards-in-a-trenchcoat/` (cave + field) and `games/dodecahedron-demo/` (chrome-less single-scene demo); each has its own `project.godot`, `export_presets.cfg`, `scenes/`, `scripts/`, `addons/hugmesh_export/`.
- Rust GDExtension extracted to `extensions/hugmesh-bridge/`. `make build-rust` builds once + copies `libhugmesh_bridge.so` into every `games/*/bin/` so each project opens standalone in Godot.
- Makefile uses `GAME_PROJECTS` + `PRESETS_<dir>` to drive per-game per-preset web export.
- Bridge migration: `game_bridge.gd` dispatches to `HostBridge` directly; legacy GDScript signal fan-out + fallback paths removed; `player_controller.gd` + `scene_loader.gd` subscribe to `HostBridge` directly.

### WASM Rust target

`extensions/hugmesh-bridge/Cargo.toml` adds a default `wasm-opt-in` feature that forwards `godot/experimental-wasm`. Build chain documented in README §8 (Emscripten + WASM Rust target):

```
emsdk install latest && emsdk activate latest      # → ~/Tools/emsdk/
rustup target add wasm32-unknown-emscripten       # rustup user-local; system Gentoo Rust didn't ship this target
cargo build --release --target wasm32-unknown-emscripten
# → 3.3 MB target/wasm32-unknown-emscripten/release/hugmesh_bridge.wasm
```

## Notable bugs uncovered or fixed along the way

- **`net::ERR_BLOCKED_BY_RESPONSE` on wasm-fragment iframe** — Missing `Cross-Origin-Resource-Policy: cross-origin`, plus duplicate COOP/COEP headers from the upstream nginx. Fixed in `wasm-fragment/nginx.conf` + `frontend/nginx.conf`'s `proxy_hide_header` + `add_header` re-emit.
- **NG0904/NG0203 inside dodecahedron remote** — Standalone `bootstrapApplication(...)` in the remote's `main.ts` ran inside the host shell during native-federation remote init. Removed, made `main.ts` re-export only.
- **Karma "Module parse failed" on `@angular-architects/native-federation`** — Static import in `RemoteLoaderService` broke webpack parser. Fixed by stashing `loadRemoteModule` on `globalThis` from `main.ts` (which IS bundled).
- **`docker compose build` hung silently** — Docker 29.x removed the legacy builder; missing `docker-buildx`, plus 8 stuck `docker-credential-secretservice` processes from interrupted prior runs. Fix: `pkill docker-credential-secretservice` + downloaded buildx v0.18 binary to `~/.docker/cli-plugins/docker-buildx` (no sudo).
- **Testcontainers "client version 1.32 too old"** — docker-java in Testcontainers BOM 1.20.4 speaks API 1.32; Docker 29 requires 1.44. Fix: pinned `org.testcontainers` to 1.21.3 + `-Dapi.version=1.44` system property.
- **`BadSqlGrammarException 'relation does not exist'` in integration test** — R2DBC pool connections opened during `@PostConstruct` before Liquibase committed, locked into pre-migration MVCC snapshot. Fixed via `@DependsOn("liquibase")` on `GameTimeService` + `GameStateService` + `spring.r2dbc.pool.initial-size=0` in the test.
- **`'column manifest_json is of type jsonb but expression is of type character varying'`** — Spring Data R2DBC sends Java `String` as `varchar`. `manifest_json` is opaque storage (no `->`/`@>`/GIN), so migration `024-1` ALTERs to `text` at zero functional cost.
- **`DuplicateKeyException` on `games_pkey`** — Migration `017-1` inserted explicit `id=2` without bumping `games_id_seq`. Fixed by `024-2` `setval(pg_get_serial_sequence('games','id'), MAX(id))`.

## Test plan

- [ ] `make build-frontend-all` — host + 4 remotes build cleanly.
- [ ] `make test-frontend` — 73 specs pass.
- [ ] `make test-backend` — including `GameAccessControllerTest` (11 specs) + `GameUploadIntegrationTest` (1/1 against Postgres Testcontainer).
- [ ] `make e2e` — federation routes load (login → /, /admin, /admin/games), all three games iframe-render through `/_remotes/wasm-fragment/<slug>/`, hot upload of a fixture zip lands a new game on the home page without restarting the BFF.
- [ ] `make e2e-native` — same, against the GraalVM native image.
- [ ] `make build-rust` — produces the native `.so` + (when emscripten is on PATH) the `wasm32-unknown-emscripten` `.wasm`.
- [ ] Admin force-revoke from the dashboard kills an active session within one sweep cycle; the sweeper closes idle launches after 15 min.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
