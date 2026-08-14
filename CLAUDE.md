# HugMesh Game Studio — contributor notes

A pragmatic onboarding doc for anyone (human or AI) jumping into this repo. Keep it tight; for prose-y reference material see the [in-repo wiki](docs/wiki/) (architecture chapters, audience guides, how-to recipes, decisions), [`README.md`](README.md), the architecture PDF (`docs/publishing/architecture/`), or the user guides under `docs/publishing/userguides/`.

## What this repo is

A self-hosted Godot 4 WASM game studio and portal — authors build RPG and visual-novel titles in the browser, players enjoy them in live multiplayer sessions. Three tiers:

| Tier | Tech | Where |
| --- | --- | --- |
| Browser | Angular host + native-federation remotes | `frontend/` (host), `frontend/projects/{login,admin,api-docs,games-three-wizards,games-dodecahedron}` |
| Per-remote nginx | Each remote shipped as its own image, proxied at `/_remotes/<slug>/` by the host nginx | `*/Dockerfile`, `frontend/nginx.conf` |
| BFF | Spring Boot 4.0 WebFlux + R2DBC + Liquibase + Casdoor + Postgres + Cassandra | `backend/` |

Per-game Godot 4 WASM bundles live under `games/<slug>/` and ship into `backend/src/main/resources/assets/levels/<slug>/` via `make build-godot`. The `wasm-fragment` nginx serves them at `/_remotes/wasm-fragment/<slug>/index.html`.

## Naming — read this before grepping

A bulk rename pass landed in PR #20. The user-facing terms are:

- **`chapter`** (formerly `level`) — a playable game stage. The `chapters` table, `chapter_id` columns, `/api/chapters/...` paths, `Chapter*.java` classes.
- **`instant`** (formerly `spell`) — the cast-action verb. `skills.skill_type='instant'`, `CAST_INSTANT_N` action names, `InstantService`.
- **`game`** — the umbrella title. Three Wizards In A Trenchcoat (with chapters Cave + Field), Dodecahedron Demo (one chapter).

What stayed the same on purpose:

- `player_characters.level` — that's the **XP level** of the character, not a game stage. The XP getter is `c.getLevel()`.
- "log level" — `logging.level.*` config keys.
- Filenames under `backend/src/main/resources/assets/levels/<slug>/` — the on-disk path didn't move (yet), only the schema/code.

If you see `findByLevelId` or `level_id` in the source, treat it as a missed-rename bug. The fix pattern is in PR #24 — extend Liquibase 028 with a `<renameColumn>` and update the Spring Data method name to match.

## Local dev quickstart

```bash
# 1. First-time setup — Casdoor randomises seed credentials per fresh DB.
#    Bring the stack up once, copy the printed seed into .env:
docker compose up -d
docker compose logs casdoor 2>&1 | grep -A4 "Casdoor seed credentials"
# Copy Client Secret + admin pass + player pass into a new .env at repo root:
cat > .env <<EOF
CASDOOR_CLIENT_ID=twclient001
CASDOOR_CLIENT_SECRET=<paste from log>
CASDOOR_ADMIN_PASS=<paste from log>
CASDOOR_PLAYER_PASS=<paste from log>
EOF
docker compose up -d --force-recreate api frontend

# 2. Build the Rust GDExtension (native + WASM, dockerised).
make build-rust
# 3. Re-export the Godot bundles after touching any *.gd file.
make build-godot
# 4. Build the docs zip.
make docs-bundle
# 5. Open http://localhost:8080
```

## Things that bite

### "Couldn't reach Login — the federated module didn't load"

Means the BFF is dead. Check `docker compose logs api`. The most common causes after a rename / schema change:

- `PropertyReferenceException: No property 'X' found for type 'Y'` — Spring Data parsed a derived query method name (`findByLevelId`) against an entity whose property was renamed (`chapterId`). Fix: rename the repo method to match.
- `column X.Y does not exist` — Liquibase migration missed a table/column. Add a follow-up `<renameColumn>` changeset (gate it with a precondition so re-runs are idempotent).
- `Unterminated dollar quote` / `syntax error at or near "$1"` from a Liquibase `<sql>` block — JDBC parameter binder ate a `?` jsonb operator. Use `jsonb_exists(col, 'key')` instead of `col ? 'key'`.
- BFF restart-loop on Casdoor secret mismatch — pin the `.env` file (above).

### `make gen-api` corrupts `frontend/openapi.json`

If the BFF is unreachable on `:8080`, the host nginx serves Angular's `index.html` for any unknown path with HTTP 200, so a naive `curl /v3/api-docs` happily writes HTML to `openapi.json`. The Makefile now JSON-validates before accepting; if you see "end of stream" parser errors during `npm run gen`, wipe `frontend/openapi.json` (it's git-ignored) and rebuild.

### Movement doesn't happen in the WASM iframe

Symptoms + causes, in order of likelihood:

1. **Rust GDExtension not loaded** — log line `ERROR: Cannot get class 'HostBridge'`. The `bin/hugmesh_bridge.wasm` is missing from the bundle. Check `extensions/hugmesh-bridge/.cargo/config.toml` has `-Cpanic=abort` for `wasm32-unknown-emscripten`, and the export presets have `variant/extensions_support=true` + no `*.gdextension` exclude filter. Then `make build-rust && make build-godot`.
2. **Browser cached old wasm** — `wasm-fragment/nginx.conf` adds `Cache-Control: no-cache` on `.wasm`/`.pck`. Hard-refresh (Ctrl+Shift+R) once after a rebuild.
3. **`/api/levels/{id}/scene` 404** — backend renamed the path to `/api/chapters/...`. PR #24 added `@RequestMapping({"/api/chapters", "/api/levels"})` for back-compat so already-exported bundles work. If you re-export, the URL flips to chapters.
4. **Empty world (no scene elements spawn)** — the BFF's `/api/chapters/{id}/scene` returned a 500. The HTML prefetch in `index.html` previously parsed error JSON as the scene config, silently yielding zero elements. Fixed by (a) Liquibase 028-12 which renamed `scene_elements.level_id → chapter_id` (the column was missed in the original 028 sweep) and (b) an `if (!r.ok) throw new Error(...)` guard in the prefetch so future BFF errors fall to `.catch()` with a console warning instead of becoming a phantom empty config.
5. **403 from `/api/controls/{id}`** — the JWT subject isn't the controller-role holder for the session. `HomeComponent.ngOnInit` calls `session.connect()` → `session.claimControl()` on page load; if the BFF restarted (clearing in-memory `activeSessions`/`currentController`) while the Angular SPA was live, the claim is stale. **Auto-recovery**: `game_bridge.gd` now posts `SD_SESSION_EXPIRED` on a 403; `ApiBridgeService.handleSessionExpired()` re-runs the connect/claim chain automatically. Immediate fix for a persistent 403: hard-refresh (`Ctrl+Shift+R`).
6. **shimInstalled: false in iframe** — the `hugmesh-shim.js` script tag's path was absolute (`/hugmesh-shim.js`); it now uses the relative `../hugmesh-shim.js` so it resolves under both the host proxy AND a direct wasm-fragment access.

### `make build-godot` spam: gdext + Gentoo Godot incompatibility

If `/usr/bin/godot` is a Gentoo build (the `.gentoo.<sha>` suffix in the version string), every `make build-godot` invocation logs noisy errors during project init:

```
ERROR: Attempt to get non-existent interface function: 'get_godot_version'.
   at: get_interface_function (core/extension/gdextension.cpp:730)
thread '<unnamed>' panicked at gdextension_interface.rs: failed to load `get_godot_version`
ERROR: GDExtension initialization function 'gdext_rust_init' returned an error.
ERROR: Error loading extension: 'res://hugmesh_bridge.gdextension'.
```

**Cause:** The Gentoo patches strip the deprecated v1 GDExtension functions (`get_godot_version`, `mem_alloc`, `classdb_construct_object`, …) and only keep the `*2` successors. gdext (any commit through latest master at the time of writing) still calls `get_proc_address("get_godot_version")` unconditionally and panics if the pointer is null.

**Non-fatal:** `Cargo.toml` sets `panic=unwind` so the panic is caught — `gdext_rust_init` returns an error → Godot logs "Error loading extension" → Godot continues the export anyway. All three bundles (`cave/`, `field/`, `dodecahedron/`) still get freshly written; check the `index.html` mtime to confirm.

**Practical impact:**
- Editor (`libhugmesh_bridge.so`) is **broken** on Gentoo: F5/local-play in the Godot editor can't load the bridge. Use the WASM bundle in the browser for any bridge-dependent testing.
- WASM export and browser play both **work** — the `hugmesh_bridge.wasm` is built in Docker and runs under the standard `web_release.zip` template, which still has the v1 functions.
- For full editor bridge support, use a stock (non-Gentoo) Godot 4.6.x binary: download from godotengine.org and set `GODOT=path/to/godot4` before `make build-rust`.

### Web-native games (HTML5 + Rust-engine wasm32) — adding one

Beyond the Godot games (`three-wizards`, `dodecahedron`, `chess-godot`, `connect-four-godot`, and `shadows-of-the-net` — the pure-GDScript XR chat chamber, changelog 056, no Rust bridge), the 14 abstract/puzzle/duel games are **web-native**, NOT Godot: a pure-Rust engine in `extensions/hugmesh-<game>/` compiled to `wasm32-unknown-unknown` via a thin C-ABI/JSON-shim wrapper crate `extensions/hugmesh-<game>-wasm/` (zero host imports — loads in the browser via `WebAssembly.instantiateStreaming` and in-JVM via Chicory; mirror `hugmesh-fps-core-wasm` / `hugmesh-chess-wasm`), driving a canvas frontend `games/<slug>/` that implements the shared `games/_shared/` GAME interface (`{init, render, onLocalInput, onRemoteEvent}` — see `games/_shared/README.md`). Host-authoritative: the host browser runs the wasm engine, the BFF relays `control`/`world` over the session SSE channels, the guest re-applies + re-validates. Verify each with a node WASM functional test (`games/_shared/test/<slug>-wasm.test.mjs`) + a DOM-light frontend smoke (`games/<slug>/test/`).

To make one **appear + launch**, four things (the chess/Wave-1 PR pattern):
1. The `games/<slug>/` project + the wasm + the served bundle copied self-contained into `backend/src/main/resources/assets/levels/<slug>/` (index.html + `<slug>.js` + css + icon + a **copy** of `games/_shared/*.js` under `_shared/` + the `.wasm` under `wasm/`). Keep the deployed copy byte-identical to source.
2. `games` + `chapters` catalog rows (a Liquibase changeset; `asset_url=/api/assets/levels/<slug>/index.html`).
3. A `federation_modules` row with **`kind='wasm-game'`** (`remote_entry_url=/_remotes/wasm-fragment/<slug>/index.html`, `exposed_module=NULL`, `game_slug=<slug>`). This is what flips the host off the legacy canvas — `home.component` reads it off `/api/federation/manifest` (`isWebNative`/`FederationService.webNativeByGameSlug`); no per-game Angular remote, no frontend change. `main.ts` deliberately excludes `wasm-game` from `initFederation`.
4. If the engine relays a new `(kind, type)` (e.g. host `world/state`), add the `input_event_types` + `game_event_support` rows (see changeset 050).

**Gotcha:** the `wasm-fragment` nginx **bakes `assets/levels` at image-build** (`COPY ... /usr/share/nginx/html`), so its image MUST be rebuilt when any bundle changes (`docker compose build wasm-fragment`) — the launch path is `/_remotes/wasm-fragment/<slug>/` (generic host-nginx proxy, present in both `nginx.conf` + `nginx-tls.conf`), with `/api/assets/levels/<slug>/` as the BFF fallback. Known follow-up: the simple engines lack a `from_state` op (chess has `from_fen`), so a dropped/reordered SSE relay degrades the guest to a visible "out of sync" banner rather than auto-resyncing its wasm engine.

### Cassandra-backed optional services: NEVER `@ConditionalOnBean` on a scanned `@Service`

`@ConditionalOnBean(ReactiveCassandraTemplate.class)` on a component-scanned class evaluates BEFORE Spring Boot's auto-configuration registers the template, so the condition is deterministically false and the service silently never exists — exactly this killed `PositionCacheService`, `CassandraSyncService`, `DistributedRateLimitService`, `RevocationListService`, and `RealtimeTruthStore` in every real deployment (unit suites passed because they hand-inject instances; the empty `pod_heartbeats` table was the tell). All five are now registered by `games.hugmesh.autoconfigure.CassandraBackedServicesAutoConfiguration` (a true `@AutoConfiguration` listed in `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`, in a package deliberately OUTSIDE the `games.hugmesh.bff` scan root) — add any new Cassandra-conditional bean THERE, not as an annotated `@Service`. The auto-config also contributes the `ReactiveCqlTemplate` bean Boot doesn't provide. Live tell that it works: `pod_heartbeats` gets a row within ~20s of boot.

### Liquibase 028 patterns

PR #20's bulk sed-rename caught entity FIELDS but not always the underlying tables/columns. The fix protocol when a fresh `column X.Y does not exist` shows up:

1. `docker compose exec postgres psql -U threewizards -d threewizards -tAc "SELECT table_name, column_name FROM information_schema.columns WHERE column_name LIKE '%level%' OR table_name LIKE '%level%'"`
2. For each real (non-system-catalog) hit, append a `<changeSet>` to `028-rename-chapter-instant.xml` with a `<columnExists>` or `<tableExists>` precondition.
3. If the entity already uses the new name (e.g. `chapterId`), the migration is the only fix needed. If the entity also has the old name, sed-rename it.

Known missed renames fixed by 028-9 through 028-12: `chapter_prerequisites` table + `chapter_prerequisites.chapter_id` (028-9/10/11); `scene_elements.chapter_id` (028-12 — its absence caused `GET /api/chapters/{id}/scene` to 500 and the world to render empty).

## Repository tour

| Path | Purpose |
| --- | --- |
| `backend/` | Spring Boot WebFlux BFF. R2DBC + Liquibase. Generates Javadoc into `docs/publishing/javadoc-output/`. |
| `backend/src/main/resources/db/changelog/` | Liquibase changesets, numbered `001-*` through latest. Master file is `db.changelog-master.xml`. |
| `frontend/` | Angular host shell. `frontend/projects/<remote>` is each federated remote. |
| `games/<slug>/` | Godot 4 project. Each has its own `bin/` for the Rust GDExtension binaries (gitignored, generated). |
| `extensions/hugmesh-bridge/` | Rust GDExtension. `tools/build-rust-wasm/Dockerfile` is the dockerised build env. |
| `docker/cassandra-init.cql` | Cassandra keyspace + tables. Run by the `cassandra-init` compose service on first boot. |
| `wasm-fragment/` | nginx that serves the Godot bundles + injects `hugmesh-shim.js` into every `*.html`. |
| `docs/publishing/` | LaTeX architecture PDF, six user guides, six PowerPoints, themed Javadoc, OpenAPI explorer. `make docs-bundle` zips it all into `docs/hugmesh-design-docs-<TS>.zip`. |
| `tools/build-rust-wasm/` | Dockerfile for rust+emsdk builder image. `make build-rust` uses it. |
| `compose.staging.yml` / `compose.prod.yml` | Compose overlays for hostname targeting + bind-mounted upload storage. |
| `Makefile` (root) | Top-level orchestrator. Delegates docs targets to `docs/publishing/Makefile`. |
| `docs/PLAN-module-federation-and-native.md` | Original 8-section plan that produced the federated topology. Pre-rename language ("levels"). |

## Where to look for…

- **API contract** — `docs/publishing/openapi/openapi.fallback.json` is the static fallback. Live spec at `/v3/api-docs` when the BFF is up. The `api-docs` federated remote at `/admin/docs` renders it interactively.
- **Marble diagrams** — `docs/publishing/architecture/marbles/` has one `.tex` per controller and per service. They're rendered to PNG by `bash docs/publishing/diagrams-rendered/render-all.sh`.
- **A scenario or runbook** — six user guides under `docs/publishing/userguides/<audience>/`. Built into PDFs by `make docs-userguides`.
- **What's currently in flight** — open PRs on https://github.com/hugmesh/hugmesh-games/pulls.

## Multiplayer + roles (post-PR)

A multi-phase feature batch landed on `feature/multiplayer-sessions` that's worth knowing before grepping:

- **Per-session SSE sinks.** `GameStateService` keeps a `ConcurrentHashMap<String, SessionSinks>` lazy-allocated by `sessionId`. `applyControl(event, characterId, sessionId)` is the multiplayer-aware overload; the 2-arg form remains the legacy single-player path. `GameStreamController` accepts `?sessionId=` to scope subscribers; `/api/admin/sessions/{id}/stream` merges movement + world + character + view for admin observation. **The isolation contract is locked in by [`SessionIsolationTest`](backend/src/test/java/games/hugmesh/bff/service/SessionIsolationTest.java)** — emit on session A's sinks, assert session B's per-session subscriber sees nothing.
- **Mod / publisher roles.** Casdoor owns role membership (`mod`, `publisher`); Postgres owns per-game scope (`mod_game_scopes`, `publisher_game_scopes`). The BFF rejects any mod/publisher action whose target game isn't in the caller's active scope. Admins manage both layers via `/api/admin/role-grants/*`.
- **Frontend remotes per role.** `frontend/projects/{admin,mod,publisher}/` are independently-built nginx-shipped Angular remotes federated under `/admin`, `/mod`, `/publisher`. The host shell shows role-conditional nav links via `AuthService.isMod() / isPublisher()`.
- **Per-player view channel.** Rust HostBridge gained `register_player_slot` + `view_egress`; the GDScript `multiplayer_lobby.gd` spawns one wizard `CharacterBody3D` per registered slot. Camera samples (~10 Hz) post to `/api/view`, which fans out on the per-session `view` SSE channel admins watch via `SessionsShell`.
- **Diagnostics is admin-only and off by default.** `POST /api/diagnostics/wasm-console` requires `ROLE_ADMIN` AND `wasm_console_debug_enabled=true` (admin-toggled at `PUT /api/admin/debug-mode/wasm-console`). Frontend `WasmConsoleForwarderService` mirrors the gate so non-admins never even attempt the POST.

Casdoor seed users for testing: `player`, `player1`, `player2`, `player3`, `mod`, `publisher`, plus per-game `mod-three-wizards`, `mod-dodecahedron`, `publisher-three-wizards`, `publisher-dodecahedron`. Passwords printed by `casdoor/entrypoint.sh` on first boot.

## Friends, blocks, visibility (Phase 8)

Decision: see [`docs/wiki/decisions/015-friends-blocks-and-session-visibility.md`](docs/wiki/decisions/015-friends-blocks-and-session-visibility.md).

- `game_sessions.visibility` is one of `public` / `friends` / `private`. The `MultiplayerSessionController` filters the lobby list against the caller's friend graph + blocklist; no leaks to the network.
- `user_relationships` is a single graph table. Friends are mutual (two `friend` rows); blocks are one-directional (`blocked` row, target invisible to the block). Publishers are not blockable at the data layer; admins are not blockable at the controller.
- `/api/session-logs/{me|admin/...|scoped/...}` reads the per-user history off `session_members`. `@PreAuthorize` gates the admin/scoped paths.
- **R2DBC `save()` quirk**: entities whose id is application-assigned (e.g. `GameSession`'s UUID) MUST implement `Persistable<T>` with a `@Transient boolean isNew` flag, otherwise `save()` issues an UPDATE that affects zero rows and throws `TransientDataAccessResourceException: Row with Id ... does not exist`.
- Frontend toast UX is via `NotificationService` + `errorInterceptor` — components subscribe with empty `error: () => {}` blocks and let the interceptor surface the friendly message.
- The level→chapter rename is now complete in source: `chapter_id` URL param, `/api/chapters/...` endpoint, `chapter_membership` Cassandra table, `TW_CHAPTER_TRANSITION` postMessage. Only the `assets/levels/<slug>/` filesystem path stays per the original holdout. After pulling, run `make build-rust && make build-godot` to re-export the bundles with the new URL contract — already-exported bundles will silently 404 their scene fetch.
- The `hugmesh_export` Godot addon now lives canonically in `extensions/hugmesh-bridge/addons/hugmesh_export/` alongside `hugmesh_bridge/`; `make sync-bridge-addon` copies both into each `games/<slug>/addons/` and the per-game copies are gitignored.

## Integration-testing profile + multi-user e2e (Phase 9)

- `make e2e` brings up the full stack with the `integration-test` Spring profile (TLS, deterministic Casdoor seed, verbose multiplayer logging) and runs the Playwright suite under `e2e/tests/`.
- Every Casdoor seed user (`player` / `player1..3` / `admin` / `mod` / `mod-three-wizards` / `mod-dodecahedron` / `publisher` / `publisher-three-wizards` / `publisher-dodecahedron`) gets a deterministic password, default `e2e-pass`. Override individually via the matching `E2E_*_PASSWORD` env or change the shared default with `E2E_PASSWORD=…`.
- `e2e/tests/helpers/roles.ts` is the catalogue Playwright iterates; `helpers/sse-bridge.ts` installs an iframe-side `window.message` capture so a spec can assert on the `SD_SSE_EVENT` deliveries the BridgeGlue would normally drain.
- **Race-free capture**: `installSseCapture(context)` in `helpers/sse-bridge.ts` uses `BrowserContext.addInitScript` so the listener attaches in every iframe BEFORE its own scripts. Without this, BridgeGlue drains the queue before the test's listener attaches.
- **WASM-boot timing**: gate every `sendControl` in a multi-user spec on `waitForResponse(r => r.url().includes('/api/game/stream?sessionId=…'))` — the Angular wrapper opens its session-scoped EventSource only AFTER WASM compile finishes (15-30s on a cold image), and a `sendControl` before then races the subscription.
- **Multi-user lobby flow**: drive sessions through the lobby modal (`createSessionViaLobby` / `joinSessionViaLobby` in the spec) — direct API calls to `/api/sessions` leave the home component's `activeSessionId` blind, so the iframe URL has no `?session_id=` and the wrapper subscribes to the unscoped global stream. See [`docs/wiki/howto/run-multi-user-e2e.md`](docs/wiki/howto/run-multi-user-e2e.md) for the full pattern.
- New specs:
    - `per-role-assets.spec.ts` — every seed role logs in, opens the first chapter, asserts the WASM bundle bytes (index.html / index.wasm / index.pck) come down with HTTP 200.
    - `multiplayer-same-session.spec.ts` — two players in one session both load the bundle and player2's iframe receives an SSE event after player1 emits a control. Strict positive assertion.
    - `cross-session-isolation.spec.ts` — two sessions for the same game; player1 moves in session A, player2 in session B sees no event carrying P1's character. The e2e flip-side of `SessionIsolationTest`. Strict negative assertion.

### Gherkin / BDD layer (`make e2e-bdd`)

Parallel to the canonical `tests/*.spec.ts` suite. Lives under [`e2e/features/`](e2e/features/) — `.feature` files in Gherkin syntax (one scenario per role, plus topic-shaped scenarios for friends/blocks, lobby visibility, multiplayer SSE isolation, session log) backed by step definitions in `e2e/features/steps/`. The `AfterStep` hook in [`e2e/features/support/fixtures.ts`](e2e/features/support/fixtures.ts) attaches a PNG to the report at every step transition, so a reviewer can see the UI state at every line of the scenario without re-running.

- Run: `make e2e-bdd` brings up the same TLS integration-test stack and runs only the `bdd` Playwright project.
- Two Playwright projects in [`e2e/playwright.config.ts`](e2e/playwright.config.ts): `chromium` (existing 21 specs, the canonical CI gate) and `bdd` (generated specs from `.feature` files via `playwright-bdd`'s `bddgen`). The Dockerfile pre-runs `bddgen` so the BDD project is ready to discover at default-CMD time.
- The BDD step files re-use the same plain-Playwright helpers in `tests/helpers/roles.ts` (`login`, `createSession`, `joinSession`, `sendControl`, `installSseCapture`) so behaviour stays in lockstep with the canonical specs — the BDD layer is presentation, not a parallel implementation.
- Per-scenario state lives in [`e2e/features/steps/_registry.ts`](e2e/features/steps/_registry.ts) (`pageRegistry`, `ctxRegistry`, `sessionByName`, `responseState`). The `Before` hook calls `resetRegistry()` so each scenario starts clean.

### Doc screenshots: live-stack vs e2e harvest

Two paths feed `docs/publishing/screenshots/build/<name>.png` (which the user-guide PDFs and slide decks reference via `\includegraphics{<name>.png}` and the `screenshot:<name>` image keys):

- `make docs-screenshots` — the original Selenium flow ([`capture.py`](docs/publishing/screenshots/capture.py)). Drives a headless Firefox against a running compose stack at `HUGMESH_BASE_URL`. Convenient when you've got a dev stack already up; falls back to placeholder PNGs when unreachable. Limitation: the dev stack only seeds admin + player passwords from `.env`, so mod / publisher logins fail and those captures land as placeholders.
- `make docs-screenshots-from-e2e` — deterministic alternative. Runs the full `make e2e-bdd` flow (TLS integration-test stack, every Casdoor seed user with `E2E_PASSWORD=e2e-pass`), then runs [`harvest-from-e2e.py`](docs/publishing/screenshots/harvest-from-e2e.py) which copies the per-step BDD PNGs into the doc build dir under their reference names. Heavier (10-15 min cold) but lands real screenshots for every role including the per-game mod / publisher variants. `make docs-screenshots-harvest` skips the BDD run and re-harvests an existing `e2e/results/artifacts/` directory.

## LAN access + production exposure

Two knobs control where the stack listens.

**Dev / LAN — [`lan.properties`](lan.properties) + `make lan-*`.** A workstation defaults to loopback-only (`127.0.0.1:8080`) so the BFF + Postgres aren't reachable by every device on the local Wi-Fi. `lan.properties` is a docker-compose env-file (literal `FRONTEND_BIND=0.0.0.0:` / `FRONTEND_PORT=…` / `POSTGRES_BIND=…` — no boolean indirection). Two equivalent ways to take it: `make lan-up` (recommended) or `docker compose --env-file lan.properties up -d` (plain) — both interpolate the same `${FRONTEND_BIND}` / `${POSTGRES_BIND}` keys into the port mappings. `make lan-info` prints reachable URLs. `make lan-up-tls` wraps the frontend in HTTPS via [`docker-compose.tls.yml`](docker-compose.tls.yml) (self-signed cert from `make gen-certs`; the cert's SAN list now includes the host's detected LAN IPs so `https://<lan-ip>:8080/` is name-valid, not just `localhost`). A plain `docker compose up -d` with no env-file falls back to the loopback defaults baked into `docker-compose.yml`. *Lesson:* an earlier design used a `LAN_OPEN=true|false` boolean translated to a bind prefix inside a Makefile shell function — that broke silently when users ran plain `docker compose up -d` because the translation never fired. Storing the prefix directly fixes both invocation paths.

**Production — [`compose.prod.yml`](compose.prod.yml) + nginx rate-limit zones.** The prod overlay's frontend defaults to `PROD_FRONTEND_BIND=127.0.0.1:` (assumes an upstream LB / TLS terminator at `hugmesh.games`). Set `PROD_FRONTEND_BIND=0.0.0.0:` for a single-VM deploy that wants this nginx to face the internet directly. Either way the host nginx (`frontend/nginx.conf` + `frontend/nginx-tls.conf`) carries `limit_req_zone` declarations sized for ~10 000 concurrent users:

| zone | rate/sec | burst | applied to |
|---|---|---|---|
| browse | 100 | 200 (delay) | `/` (SPA shell + static) |
| api | 50 | 150 (delay) | `/api/*` (catch-all) |
| sse | 30 | 100 (nodelay) | `/api/game/stream` |
| auth | 5 | 20 (nodelay) | `/api/auth/*` |
| admin | 5 | 20 (nodelay) | `/api/admin/*` |
| maker_api | 10 | 40 (delay) | `/api/rpg/*` + `/api/vn/*` |

Plus `limit_conn conn_per_ip 40` to cap a single source's parallel sockets. Tripped requests return 429 (not the nginx-default 503) so clients with backoff do the right thing. The BFF's existing per-pod `RateLimitFilter` (+ Cassandra cross-pod aggregator from decision 017) still runs at the controller level — these zones are the edge layer for cheap rejections before traffic touches the Java VM. See [`docs/wiki/decisions/019-production-rate-limits.md`](docs/wiki/decisions/019-production-rate-limits.md).

## ⚠ nginx-tls.conf drift bites the e2e suite

`make e2e` and `make e2e-bdd` mount [`frontend/nginx-tls.conf`](frontend/nginx-tls.conf) into the host frontend container — a TLS-only variant of the regular [`frontend/nginx.conf`](frontend/nginx.conf). Both files must list every `/_remotes/<slug>/` proxy block; if the TLS variant is missing one, the host nginx falls through to `try_files` and the host shell renders **"Couldn't reach <X> — federated module didn't load"** for any user that navigates to that route.

The plain-HTTP variant has historically been kept up to date but the TLS overlay drifted (publisher / mod / api-docs were missing for months and only surfaced when the maker BDD scenarios first navigated through `/publisher`). When adding a new federated remote, **edit both files in lockstep** — copy the new `location /_remotes/<slug>/` block into nginx-tls.conf in the same place. Same applies to any `/api/<sub-service>/` proxy blocks (e.g. `/api/rpg/`, `/api/vn/`) — these are TLS-side too.

## Twin maker side services (RPG + VN)

Decision: see [`docs/wiki/decisions/018-rpg-and-vn-makers.md`](docs/wiki/decisions/018-rpg-and-vn-makers.md).

Two reactive Spring Boot side services live alongside the BFF:

- [`rpg-builder/`](rpg-builder/) — Mudlet-shaped editor data model (areas / rooms / 6+1-direction exits / items / NPCs / scripts). Owns its own `postgres-rpg-builder` DB with Liquibase-managed schema. REST surface at `/api/rpg/*`.
- [`vn-builder/`](vn-builder/) — godot-2d-visual-novel-shaped data model (acts / characters / backgrounds / scenes / lines / branching choices). Owns its own `postgres-vn-builder` DB. REST surface at `/api/vn/*`.

Twin federated frontend remotes drive them: [`frontend/projects/games-rpg-maker/`](frontend/projects/games-rpg-maker/) (mounted at `/rpg-maker`) and [`frontend/projects/games-vn-maker/`](frontend/projects/games-vn-maker/) (mounted at `/vn-maker`). The publisher console exposes "Launch RPG Maker" / "Launch Visual Novel Maker" buttons that open the makers in a new tab.

Build pipeline (identical shape in both services): `QUEUED → RENDERING → EXPORTING → UPLOADING → DONE | FAILED`. The worker walks the project tree, renders a Godot 4 source tree under `<workdir>/<job-id>/godot-src/` (with `hugmesh_bridge.gdextension` + the compiled `hugmesh_bridge.wasm` baked into `bin/` so the produced WASM is multiplayer-aware out of the box), runs the Godot Web export (`GodotExporter`), zips the result, and POSTs to the BFF `/api/admin/games/upload` endpoint **with the originating user's bearer token** so the BFF's existing `publisher_game_scopes` check enforces who can ship which slug.

**Real exports (the `EXPORTING` step).** `GodotExporter.exportWeb` runs `godot --headless --export-release "Web"` for real and fails the build loudly on a non-zero exit / timeout / empty bundle — it is NOT a stub anymore. Gated behind `app.build.godot-enabled` (default `false` → dev stub bundles so the upload/register flow still runs without the multi-GB Godot image). To run real builds: `make godot-export-tools` (downloads Godot 4.6 + Web templates into `tools/godot-export/`), then `make build-with-godot` (the [`docker-compose.godot.yml`](docker-compose.godot.yml) overlay flips `app.build.godot-enabled=true`, points `app.build.godot-bin` at the host Godot, and bind-mounts the bridge wasm via `app.build.bridge-dir`). See [`tools/godot-export/README.md`](tools/godot-export/README.md). The whole-project export runs ONCE and is copied into each Area/Act bundle dir (the chapter to boot is selected at runtime). RPG room-to-room navigation works via the generated `scripts/room_index.gd` (room UUID → scene path). Pipeline coverage: `GodotExporterTest`, `Rpg`/`VnGodotProjectRendererTest`, `GodotCompileWorkerTest` (`./mvnw test` in each builder; no Godot needed — the process call is faked).

Each Area (RPG) or Act (VN) becomes a Chapter in the BFF, exported as its own `bundle/<area-slug>/index.html`. The host portal's chapter chooser already handles multi-chapter games.

**Seed user for the upload-only path:** `uploader` (id `tw-uploader-001`, the lone member of the `uploader` role — upload + replace bundles, no role management). Joins the existing roster (`admin`/`player`/`player1-3`/`mod`/`mod-*`/`publisher`/`publisher-*`/`publisher-flatland`). The maker build e2e currently runs as `admin` to bypass `publisher_game_scopes`; driving a real publisher (e.g. `publisher-flatland`) through enqueue-build → worker → BFF scope check, plus a non-owner-publisher denial *through the pipeline* (not just a direct `/upload` POST), remains a follow-on. The maker launch step now asserts `index.wasm`/`index.pck` are served (and non-empty when `E2E_GODOT_ENABLED=true`), closing the "200 on a zero-byte stub" false-confidence gap.

Schemas in both side DBs are managed exclusively by Liquibase changelogs under `rpg-builder/src/main/resources/db/changelog/` and `vn-builder/src/main/resources/db/changelog/`. No psql-init seed file; no JPA `ddl-auto`.

Federation manifest seed: backend changelog [`037-rpg-and-vn-maker-federation.xml`](backend/src/main/resources/db/changelog/037-rpg-and-vn-maker-federation.xml) adds the `games-rpg-maker` + `games-vn-maker` remotes so the host shell discovers them at bootstrap without a redeploy.

E2E BDD coverage: [`e2e/features/makers/rpg-maker.feature`](e2e/features/makers/rpg-maker.feature) and [`e2e/features/makers/vn-maker.feature`](e2e/features/makers/vn-maker.feature) cover the launch-from-publisher flow, every inline form-validation error message, and the full create-→-build progress loop. Per-step PNG screenshots via the existing AfterStep hook in [`e2e/features/support/fixtures.ts`](e2e/features/support/fixtures.ts).

**Biome themes + real 3D worlds (decision [027](docs/wiki/decisions/027-biome-themes-and-real-godot-e2e.md)).** RPG areas/rooms carry a `theme` (desert/forest/dungeon/cave/town/castle/plain); the renderer bakes real textured 3D rooms (CC0 textures under `rpg-builder/src/main/resources/biome-assets/`, `.import` sidecars), portal-based movement-through-levels, a generated `remote_avatars.gd` for multiplayer visibility, and FF-style town buildings/shops (`rpg_buildings`/`rpg_shop_stock`, game-local economy). Seed `six-realms` (rpg-builder 007) demos all six biomes. Prove it live with `make e2e-godot` (real Godot exports + `E2E_GODOT_ENABLED=true`, runs the `@godot` biome BDD suite with per-biome screenshots + webm). Worker dedups the ~41MB engine into `bundle/_engine/` (per-area `index.html` patched to `../_engine`) to stay under the 200MB upload cap.

**Dev fixture: `publisher-flatland`.** A new Casdoor seed user (id `tw-pub-flat-001`) is auto-granted publisher scope on `flatland-rpg` + `flatland-vn` via [`038-flatland-publisher-scopes.xml`](backend/src/main/resources/db/changelog/038-flatland-publisher-scopes.xml). Each builder service auto-seeds a corresponding Flatland project tree owned by that user via Liquibase 004 in its own DB ([rpg-builder](rpg-builder/src/main/resources/db/changelog/004-seed-flatland-fixture.xml) / [vn-builder](vn-builder/src/main/resources/db/changelog/004-seed-flatland-fixture.xml)). Logging in as `publisher-flatland` and opening either maker reveals the pre-seeded project ready to build — running through the maker pipeline pushes the Flatland games into the BFF catalog through the same `/api/admin/games/upload` path a hand-uploaded bundle takes. The seeded data uses Flatland-themed names (Lineland, Town Square, A Square, Lord Triangle, the Sphere) so the produced bundles double as a reference for what the renderer outputs.

**Godot-source download per project.** Both makers expose a "Download Godot source" button next to "Build & upload" that hits `GET /api/{rpg|vn}/projects/{id}/godot-zip` on the side service. The endpoint runs the project renderer to produce the same Godot 4 source tree the build worker would feed to `godot --export-release`, zips it, and streams it back so the publisher can open the project in the Godot editor directly to tweak art/scenes the maker UI doesn't yet surface. Re-uploading after editing is just the normal publisher-console flow.

### Character model: canonical in `rpg-builder`, synced into the others

Decision: see [`docs/wiki/decisions/028-parametric-humanoid-avatars.md`](docs/wiki/decisions/028-parametric-humanoid-avatars.md).

The canonical package is [`rpg-builder/src/main/java/games/hugmesh/character/`](rpg-builder/src/main/java/games/hugmesh/character/) — `CharacterSpec` (validated params + humanoid bounds) → `HumanoidRig` (the *only* geometry table) → `GdRigBaker` / `AvatarSvgRenderer` / `GlbExporter` / `CharacterDocument`. No Spring, no third-party deps, so it copies verbatim.

`make sync-character-model` copies it into `backend/` and `vn-builder/` (the `make sync-bridge-addon` idiom — three independent Maven projects, each Dockerised with its own directory as build context, so a shared module means repo-wide build surgery).

Both consumers now carry the copy, and each carries a **byte-identical `CharacterModelSyncTest`** (deliberately the same file in both — per-service guard variants could themselves drift) that fails that service's test run on ANY divergence: changed bytes, a missing file, an added file **at any depth** (a fork hiding in `character/sub/` was exactly the blindspot an adversarial pass found in the first flat-listing version), or a missing canonical tree (fail, never skip). `build-backend` runs the sync as a prerequisite (the `sync-bridge-addon` → `build-rust` idiom). **Never hand-edit a copy** — and note the converse cost: editing a CANONICAL file means re-running `make sync-character-model` or both consumers' suites go red.

**Growing a whitelist moves five things together**, and every one is tested:

1. `CharacterSpec` — the token set / `Bound` / `ColorSlot`.
2. A new Liquibase changeset under `rpg-builder/src/main/resources/db/changelog/` (append-only; drop and re-add the column CHECK with the new member).
3. `HumanoidRig` — the geometry arm that actually builds it.
4. The mirrored `const` in [`frontend/projects/games-rpg-maker/src/app/rpg-maker-remote.component.ts`](frontend/projects/games-rpg-maker/src/app/rpg-maker-remote.component.ts).
5. The maker control (`<select>` / slider) that exposes it.

`avatar_factory.gd` is deliberately **not** one of them — since 028 it walks the rig `GdRigBaker` baked in Java, so GDScript never re-derives a dimension and 2D/3D parity is structural rather than a review obligation. Same reason the maker's live preview POSTs `/api/rpg/avatar-preview.svg` instead of drawing on a canvas: preview and final art come from one renderer.

⚠ On the wire, `headColor` is the **skin** tone — a historic column name from the seven-field maker that predates the rig. Don't "fix" it.

## Postgres read replica routing (decision 022)

### Activation in compose (optional)

A single-host streaming-replica overlay ships at [`docker-compose.replica.yml`](docker-compose.replica.yml). The default `docker compose up -d` flow runs a single Postgres node (zero change). Activate the replica with:

```bash
# Fresh deploy — the bootstrap script handles role creation +
# pg_basebackup + a write-and-read smoke test:
./scripts/setup-pg-replica.sh

# Subsequent boots — just pass both files:
docker compose -f docker-compose.yml -f docker-compose.replica.yml up -d
```

Both containers use `postgres:16-alpine` (same image as the default), so the existing `pgdata` volume keeps working untouched. The replica gets its own `pgdata_replica` volume, populated by `pg_basebackup` on first boot via [`docker/postgres-replica-entrypoint.sh`](docker/postgres-replica-entrypoint.sh). The overlay also sets `APP_R2DBC_REPLICA_URL` on the `api` service so the BFF's `R2dbcReadReplicaConfig` conditional fires and the `@Qualifier("replica")` beans load.

Reverting to single-node: bring everything down and drop the overlay. The primary's `wal_level=replica` setting can stay (it's harmless without a replica); to fully revert, restart the primary without the overlay's command-line flags.

### BFF-side wiring

The replica wiring is in place in `backend/src/main/java/games/hugmesh/bff/config/R2dbcReadReplicaConfig.java`: when `app.r2dbc.replica.url` is non-empty, three `@Qualifier("replica")` beans become available — `ConnectionFactory`, `R2dbcEntityTemplate`, `DatabaseClient`. When the URL is empty (dev / single-DB), the config skips and Spring Boot auto-config provides the normal primary beans alone.

**Per-service migration pattern.** Read-only endpoints that hit the catalog tables (games / chapters / federation_modules / per-game access rules) can opt into the replica by injecting the qualified template directly, bypassing the Spring Data repo for that specific call:

```java
@RestController
@RequestMapping("/api/games")
public class GameController {

    private final GameRepository games;                  // write path → primary CF
    private final R2dbcEntityTemplate replica;           // read path → replica CF
                                                          //   (aliases primary when no replica URL)

    public GameController(GameRepository games,
                          @Qualifier("replica") R2dbcEntityTemplate replica) {
        this.games   = games;
        this.replica = replica;
    }

    @GetMapping
    public Flux<Game> list() {
        // Was: games.findAllByOrderBySortOrderAsc();
        return replica.select(Game.class)
                      .matching(Query.empty().sort(Sort.by("sort_order")))
                      .all();
    }
}
```

Write operations (POST / PUT / DELETE) stay on the `games` repository so they hit the primary. The `@Qualifier("replica")` injection is the SIGNAL — anyone reading the controller sees instantly which path is replica-routable.

**When to migrate which service.** Start with the highest-volume catalog reads, in this rough priority order:
1. `GameController.list()` + `GameController.get(id)`
2. `ChapterController` reads
3. `FederationController.manifest()` — called on every page load
4. `GameAccessController` read paths

Skip anything that mutates within the same transaction as the read. Skip anything that needs read-your-own-writes consistency.

**Why per-service, not global.** Spring Data R2DBC repositories use the framework-provided primary `r2dbcEntityTemplate` bean implicitly; swapping the bean a repo uses requires `@EnableR2dbcRepositories(entityOperationsRef = "…")` per package, which fragments the repository scan. The per-call-site qualifier above keeps the migration linear: one controller method at a time, no global config flip.

## Security primitives

- **All UI-facing UIDs and URL tokens come through one call**: `Crypto.mintUid()` in `backend/src/main/java/games/hugmesh/bff/util/Crypto.java`. Output: 43-character URL-safe base64 of `SHA3-256(64 random bytes from BouncyCastle SP800-90A HMAC-SHA256 DRBG)`. No more `UUID.randomUUID()` or raw `SecureRandom` in this codebase. If you need bytes, ask `Crypto.randomBytes(n)`. If you need a hash, ask `Crypto.sha3_256(...)`. The DRBG state is process-singleton so callers don't have to thread it.
- **Liquibase 036** widens session id columns from `varchar(36)` to `varchar(64)` to fit the new SHA3 shape (43 chars). Pre-existing UUID-shape rows continue to fit.
- **JWT role extraction** is centralised in `backend/src/main/java/games/hugmesh/bff/security/Roles.java`. Use `Roles.has(jwt, "ADMIN")` / `Roles.fromAuthentication(auth)` / `Roles.isAdmin(jwt)` rather than copying the claim-walk. Controller-level access checks should use `@PreAuthorize("hasRole('ADMIN')")`; method-internal checks fall through to `Roles`.
- **Session-scoped control authorization**: when two players use the same `character_id` in different sessions, the unscoped `findActiveOwnerOfCharacter(character_id)` races on Postgres' first-row pick and one side 403s. The fix is `findActiveOwnerInSession(sessionId, characterId)` + the 3-arg `authorizeControl(casdoorId, characterId, sessionIdOpt)` overload — `ControlController` threads `X-Session-Id` through. Pin in `GameSessionServiceTest`.

## Multi-brand tenancy (hugmesh.net merger)

The games BFF is being folded into the hugmesh.net multi-brand architecture (per-brand gateway + BFF + Casdoor instances). Decision: [`docs/wiki/decisions/025-bff-domain-guard.md`](docs/wiki/decisions/025-bff-domain-guard.md). The tenant seam is copied verbatim (logic) from hugmesh-gateway so the games realm derives scope identically:

- **`DomainRegistry`** ([`security/DomainRegistry.java`](backend/src/main/java/games/hugmesh/bff/security/DomainRegistry.java)) maps a validated token `iss` → brand. Single-issuer / no config ⇒ every token is `default`. `app.tenancy.issuers` (CSV of `issuer=domain`) opts into multi-brand; an unlisted issuer fails closed.
- **`CasdoorAuthorities`** ([`config/CasdoorAuthorities.java`](backend/src/main/java/games/hugmesh/bff/config/CasdoorAuthorities.java)) is the ONE converter both the single-issuer (`SecurityConfig`) and optional multi-issuer (`TenancyResourceServerConfig`) paths use, so they emit byte-identical authorities. It stamps exactly one issuer-derived `DOMAIN_<x>` and DROPS any `permissions` entry starting with the reserved `ROLE_`/`DOMAIN_` prefixes (authority-injection defence). **Never** key tenant off a user-settable claim (`owner`/`org`/`aud`) — only the validated `iss`.
- **`Roles`** ([`security/Roles.java`](backend/src/main/java/games/hugmesh/bff/security/Roles.java)) gained gateway-aligned `isSuperadmin` / `isImageBuilder` / fail-closed `domainOf(...)`. `domainOf(authorities)` returns `null` (deny) on ZERO **or** >1 `DOMAIN_` authorities — >1 is the injection signature; never pick the first.
- **`DomainGuardFilter`** ([`config/DomainGuardFilter.java`](backend/src/main/java/games/hugmesh/bff/config/DomainGuardFilter.java)) is the first thing that *reads* the `DOMAIN_<x>`. When `app.tenancy.domain` names this instance's brand, it 403s (`X-Domain-Denied`) any authenticated non-superadmin token whose domain ≠ that brand — defence-in-depth for compose-network traffic that bypasses the gateway's edge check. **Unset ⇒ complete no-op** (the single-Casdoor default), so single-tenant behaviour is byte-for-byte unchanged.

**Per-game catalog scoping (decision [026](docs/wiki/decisions/026-games-catalog-domain-partitioning.md)).** The first brand-scoped *request data* has landed: `games.domain` (migration 043, `VARCHAR(64) NOT NULL DEFAULT 'default'`) names the brand that owns each game. The row predicate is `Roles.domainApplies(rowDomain, callerDomain, isSuperadmin)` (mirrors the gateway's `GatewayRouteAccessService.domainApplies`: `null`/blank/`'default'` row is global, branded row matches only its brand case-insensitively, SUPERADMIN sees all, null caller fails closed). `DomainScopeService.requireGameDomain(slug, callerDomain, isSuperadmin)` is the 403 gate (mirrors `requireScope`/`requireOwns`); `requireSessionDomain(sessionId, …)` is the session-targeting variant (resolves `sessionId → game_slug → game.domain`). Controllers resolve `callerDomain` via `Roles.domainOf(jwt, registry)` and pass primitives in; the gated service call is wrapped in `Mono.defer`/`Flux.defer` so a denied request short-circuits with no side work. Wired into **`GameAccessController`** (list/grant/revoke), the **upload path**, **`PublisherController`** (grant/revoke/mods-for-game), **`ModerationController`** (stop/kick), and **`GameLaunchController`** (launch/admin-revoke; `terminate()` stays ungated — self-scoped by casdoor id). **Upload stamping is first-writer-wins**: `GameUploadService` stamps the uploader's brand onto a game only when it's newly created (server-controlled `UPDATE` inside the install txn, after register.sql); a re-upload preserves the existing domain so a brand can't capture a `'default'` title. `chapters`/`game_access`/`federation_modules` derive their brand THROUGH the parent game — only `games` carries the column. Deferred (decision 026): public-read brand-filtering (needs a gateway `X-Domain`) + domain-filtering authenticated read-lists (`activeLaunches`/`available`).

When you extend this, keep the same shape (mirror `domainApplies`; new `domain` column via a Liquibase changeset with `defaultValue="default"`). Per-brand-INSTANCE deployments don't strictly need per-row scoping (their DB is already partitioned) — it's defence-in-depth for a single BFF serving multiple brands. Tests follow the established taxonomy (single-tenant no-op / user / multi-user / cross-user ALLOW+DENY isolation / fail-closed / concurrent) — see `DomainScopeServiceTest`, `GameAccessControllerDomainScopeTest`, `PublisherControllerDomainScopeTest`, `ModerationControllerDomainScopeTest`, `GameLaunchControllerDomainScopeTest`, `GameUploadControllerTest`, `DomainGuardFilterTest`, `RolesDomainScopingTest`, `CasdoorAuthoritiesTest`, `DomainRegistryTest`. (Java-25 note: ByteBuddy can't mock concrete classes — `ModerationServiceTest` etc. use no-op subclasses, not `mock(ConcreteClass)`.)

## Workflow conventions

- **Branches**: `feature/<short-slug>` for new work, `fix/<short-slug>` for bug-fixes, `docs/<slug>` for doc-only changes, `chore/<slug>` for tooling.
- **PR base**: always `main`. Never push directly to `main`.
- **Commits**: imperative subject under ~70 chars. Body explains the *why*. Co-author trailer is optional but standard for AI-pair work.
- **Schema migrations**: append to Liquibase, never edit a previously-merged changeset. Number sequentially. Gate with `<preConditions>` so re-runs are no-ops on partial state.
- **Tests**: `./mvnw test` for the BFF, `npx ng test` for the Angular host, `cargo test` for the Rust extension. The `GameUploadIntegrationTest` needs Docker socket access (testcontainers); failure of just that test in a sandboxed env is environmental, not a regression.

## Known gaps / TODO

- ~~Full Content-Security-Policy needs an inventory of the federation runtime's eval / inline-script needs first.~~ — first cut done. `frontend/nginx.conf` + `frontend/nginx-tls.conf` both ship the policy in lockstep: `default-src 'self'` + `connect-src 'self'` + `frame-ancestors 'self'` + `object-src 'none'` + `base-uri 'self'`. The two retained `'unsafe-*'` tokens (`script-src 'unsafe-eval' 'unsafe-inline'` + `style-src 'unsafe-inline'`) are load-bearing for es-module-shims shimMode, the `esms-options` inline block, and Angular's runtime style injection. Path to strict (drop all `'unsafe-*'`) documented in [decision 023](docs/wiki/decisions/023-content-security-policy.md). Drive-by: closed the nginx-tls.conf header drift gap (it was missing CORP / HSTS / X-Frame-Options / Referrer-Policy / Permissions-Policy).
- ~~Per-form a11y sweep (login form labels, error messaging, validation announcements).~~ — done. The login form's username/password inputs have visually-hidden `<label>` elements, `[attr.aria-invalid]` reflects per-field touched-invalid state, and the error message is a `role="alert" aria-live="assertive"` live region so failures announce without re-tabbing. Producer-registration error message is also a live region. RPG / VN maker dynamic field-error / success-banner markup announces via `role="alert"` + `role="status"` (commit `e47cb94`); maker inputs use wrapping `<label>` elements so each `<input>` is implicitly associated. Admin upload-shell file and slug inputs get `aria-label`s, success/error result panes get `role="status"` / `role="alert"`, and the AdminShell 403/500 banner is now a live region (commit `3851a7d`).
- ~~WCAG colour-contrast audit on the HugMesh palette~~ — done. Decision [024](docs/wiki/decisions/024-wcag-palette-audit.md) centralises the palette as `:root` custom properties in [`frontend/src/styles.scss`](frontend/src/styles.scss); the two failing tokens (`#7070a0` → `#a8acc4`, `#7c4dff` as text → `#b388ff`) were swept across the host shell + every federated remote. Backgrounds, borders, and focus rings keep `#7c4dff` (WCAG 1.4.11 non-text contrast at 3:1 still clears).
- ~~Cassandra-backed cross-pod rate-limit storage~~ — done. `RateLimitFilter` runs the local in-process bucket as the fast pre-check, then augments cluster-wide via `DistributedRateLimitService` (append-row log on the Cassandra `rate_limit_events` table with a 90 s TTL; window-bounded `COUNT(*)` against `event_id >= minTimeuuid(now − WINDOW)`). Disable with `app.rate-limit.distributed.enabled=false` (default `true`); falls back to local-only when Cassandra is unreachable.
- ~~Iframe-side keyboard input still uses Godot's `_unhandled_input` — focus management means a freshly-mounted iframe needs a click before keys land.~~ — fixed. `GameCanvasComponent.onFrameLoad()` now calls `this.gameFrame.nativeElement.focus({ preventScroll: true })` via `queueMicrotask` on the iframe's `(load)` event. The microtask defer lets the WASM frame's own load handlers complete first; the eventual `.focus()` lands cleanly so WASD works on first key-press without a prior click. The host's `GameControlsComponent` still has the `@HostListener('window:keydown')` fallback for the rare case the auto-grab fails (iframe detached mid-mount).
- ~~Object-storage-backed game uploads~~ — done. `UploadStorage` strategy (`backend/src/main/java/games/hugmesh/bff/upload/`) with `filesystem` (default) and `s3` implementations. Switch via `app.uploads.storage=s3`; `compose.yml` ships a `minio` container for dev. Locator scheme: `file:<absolute-path>` for filesystem, `s3://<bucket>/<slug>/<sha-prefix>` for S3. Existing `game_uploads.version_dir` rows that store raw paths (pre-prefix scheme) keep resolving via the filesystem strategy. See [decision 020](docs/wiki/decisions/020-object-storage-uploads.md).
- ~~Streaming audit + telemetry pipeline (Kafka → ClickHouse)~~ — done. `state_audit`, `game_events`, `game_kpi`, `session.<id>.events` Kafka topics produced by `GameAuditPublisher` / `GameEventPublisher` / `GameKpiPublisher`. ClickHouse consumes via Kafka-engine tables + materialized views (durable MergeTree storage + `player_state_current` ReplacingMergeTree for cross-pod state recovery). All 9 SSE channels (movement/world/character/scene/input/audio/view/wasm-console/world-native — the last added by games PR #117 for cross-pod web-native relay parity) tee to Kafka without changing the in-process gameplay path. Per-session topics created by `SessionTopicManager` on session-create. Gated by `app.kafka.enabled` (default `false`). Cross-pod subscriber (`KafkaSessionSubscriber`) + `PlayerStateRecoveryService` close the F4 fan-out loop. See [decision 021](docs/wiki/decisions/021-kafka-clickhouse-streaming-pipeline.md). Remaining follow-up: retire `CassandraSyncService` once streaming pipeline soaks in production.
- ~~Federated game telemetry~~ — done. `POST /api/telemetry/game/{slug}` accepts batched KPI samples from federated game remotes; iframes call directly (existing `WebConfig` CORS covers federated origins). `GameKpiPublisher` ships to the `game_kpi` Kafka topic; ClickHouse aggregates via `kpi_rolling_avg_24h` for admin dashboards.
- ~~Postgres read replica routing~~ — done. BFF side: `R2dbcReadReplicaConfig` provides `@Qualifier("replica")` `ConnectionFactory` / `R2dbcEntityTemplate` / `DatabaseClient` beans; when `app.r2dbc.replica.url` is empty they alias the primary. Compose side: opt-in overlay at [`docker-compose.replica.yml`](docker-compose.replica.yml) brings up a primary + streaming replica on the existing `postgres:16-alpine` image (no Bitnami swap — keeps the existing `pgdata` volume layout). Activation + smoke test via [`scripts/setup-pg-replica.sh`](scripts/setup-pg-replica.sh). Per-service migration pattern in [decision 022](docs/wiki/decisions/022-postgres-read-replica.md).
