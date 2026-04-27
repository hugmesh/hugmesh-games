# Snapdragon Games — contributor notes

A pragmatic onboarding doc for anyone (human or AI) jumping into this repo. Keep it tight; for prose-y reference material see the [in-repo wiki](docs/wiki/) (architecture chapters, audience guides, how-to recipes, decisions), [`README.md`](README.md), the architecture PDF (`docs/publishing/architecture/`), or the user guides under `docs/publishing/userguides/`.

## What this repo is

A federated browser portal that hosts multiple Godot 4 games behind one Angular shell. Three tiers:

| Tier | Tech | Where |
| --- | --- | --- |
| Browser | Angular host + native-federation remotes | `frontend/` (host), `frontend/projects/{login,admin,api-docs,games-three-wizards,games-dodecahedron}` |
| Per-remote nginx | Each remote shipped as its own image, proxied at `/_remotes/<slug>/` by the host nginx | `*/Dockerfile`, `frontend/nginx.conf` |
| BFF | Spring Boot 3.4 WebFlux + R2DBC + Liquibase + Casdoor + Postgres + Cassandra | `backend/` |

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

1. **Rust GDExtension not loaded** — log line `ERROR: Cannot get class 'HostBridge'`. The `bin/snapdragon_bridge.wasm` is missing from the bundle. Check `extensions/snapdragon-bridge/.cargo/config.toml` has `-Cpanic=abort` for `wasm32-unknown-emscripten`, and the export presets have `variant/extensions_support=true` + no `*.gdextension` exclude filter. Then `make build-rust && make build-godot`.
2. **Browser cached old wasm** — `wasm-fragment/nginx.conf` adds `Cache-Control: no-cache` on `.wasm`/`.pck`. Hard-refresh (Ctrl+Shift+R) once after a rebuild.
3. **`/api/levels/{id}/scene` 404** — backend renamed the path to `/api/chapters/...`. PR #24 added `@RequestMapping({"/api/chapters", "/api/levels"})` for back-compat so already-exported bundles work. If you re-export, the URL flips to chapters.
4. **403 from `/api/controls/{id}`** — the JWT subject isn't the controller-role holder for the session. Player needs to claim controller via `POST /api/sessions/control`. Check `SessionService.isController(casdoorId)`.
5. **shimInstalled: false in iframe** — the `snapdragon-shim.js` script tag's path was absolute (`/snapdragon-shim.js`); it now uses the relative `../snapdragon-shim.js` so it resolves under both the host proxy AND a direct wasm-fragment access.

### Liquibase 028 patterns

PR #20's bulk sed-rename caught entity FIELDS but not always the underlying tables/columns. The fix protocol when a fresh `column X.Y does not exist` shows up:

1. `docker compose exec postgres psql -U threewizards -d threewizards -tAc "SELECT table_name, column_name FROM information_schema.columns WHERE column_name LIKE '%level%' OR table_name LIKE '%level%'"`
2. For each real (non-system-catalog) hit, append a `<changeSet>` to `028-rename-chapter-instant.xml` with a `<columnExists>` or `<tableExists>` precondition.
3. If the entity already uses the new name (e.g. `chapterId`), the migration is the only fix needed. If the entity also has the old name, sed-rename it.

## Repository tour

| Path | Purpose |
| --- | --- |
| `backend/` | Spring Boot WebFlux BFF. R2DBC + Liquibase. Generates Javadoc into `docs/publishing/javadoc-output/`. |
| `backend/src/main/resources/db/changelog/` | Liquibase changesets, numbered `001-*` through latest. Master file is `db.changelog-master.xml`. |
| `frontend/` | Angular host shell. `frontend/projects/<remote>` is each federated remote. |
| `games/<slug>/` | Godot 4 project. Each has its own `bin/` for the Rust GDExtension binaries (gitignored, generated). |
| `extensions/snapdragon-bridge/` | Rust GDExtension. `tools/build-rust-wasm/Dockerfile` is the dockerised build env. |
| `docker/cassandra-init.cql` | Cassandra keyspace + tables. Run by the `cassandra-init` compose service on first boot. |
| `wasm-fragment/` | nginx that serves the Godot bundles + injects `snapdragon-shim.js` into every `*.html`. |
| `docs/publishing/` | LaTeX architecture PDF, six user guides, six PowerPoints, themed Javadoc, OpenAPI explorer. `make docs-bundle` zips it all into `docs/snapdragon-design-docs-<TS>.zip`. |
| `tools/build-rust-wasm/` | Dockerfile for rust+emsdk builder image. `make build-rust` uses it. |
| `compose.staging.yml` / `compose.prod.yml` | Compose overlays for hostname targeting + bind-mounted upload storage. |
| `Makefile` (root) | Top-level orchestrator. Delegates docs targets to `docs/publishing/Makefile`. |
| `docs/PLAN-module-federation-and-native.md` | Original 8-section plan that produced the federated topology. Pre-rename language ("levels"). |

## Where to look for…

- **API contract** — `docs/publishing/openapi/openapi.fallback.json` is the static fallback. Live spec at `/v3/api-docs` when the BFF is up. The `api-docs` federated remote at `/admin/docs` renders it interactively.
- **Marble diagrams** — `docs/publishing/architecture/marbles/` has one `.tex` per controller and per service. They're rendered to PNG by `bash docs/publishing/diagrams-rendered/render-all.sh`.
- **A scenario or runbook** — six user guides under `docs/publishing/userguides/<audience>/`. Built into PDFs by `make docs-userguides`.
- **What's currently in flight** — open PRs on https://github.com/Snapdragon-Systems/snapdragon.games/pulls.

## Workflow conventions

- **Branches**: `feature/<short-slug>` for new work, `fix/<short-slug>` for bug-fixes, `docs/<slug>` for doc-only changes, `chore/<slug>` for tooling.
- **PR base**: always `main`. Never push directly to `main`.
- **Commits**: imperative subject under ~70 chars. Body explains the *why*. Co-author trailer is optional but standard for AI-pair work.
- **Schema migrations**: append to Liquibase, never edit a previously-merged changeset. Number sequentially. Gate with `<preConditions>` so re-runs are no-ops on partial state.
- **Tests**: `./mvnw test` for the BFF, `npx ng test` for the Angular host, `cargo test` for the Rust extension. The `GameUploadIntegrationTest` needs Docker socket access (testcontainers); failure of just that test in a sandboxed env is environmental, not a regression.

## Known gaps / TODO

- Full Content-Security-Policy needs an inventory of the federation runtime's eval / inline-script needs first.
- Per-form a11y sweep (login form labels, error messaging, validation announcements).
- WCAG colour-contrast audit on the Snapdragon palette.
- Cassandra-backed cross-pod rate-limit storage (currently in-process per-pod via `RateLimitFilter`).
- Iframe-side keyboard input still uses Godot's `_unhandled_input` — focus management means a freshly-mounted iframe needs a click before keys land. The host's `GameControlsComponent` covers the host-focused case via `@HostListener('window:keydown')` + `/api/controls/{id}`.
