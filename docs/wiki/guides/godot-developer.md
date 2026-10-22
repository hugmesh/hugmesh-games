# Godot developer guide

> Mirror of [`docs/publishing/userguides/godot-developer/guide.tex`](../../publishing/userguides/godot-developer/guide.tex).

From `mkdir games/my-game/` to a row on the home page: a step-by-step Godot 4 walkthrough.

## What you'll build

A new Godot 4.6 project under [`games/<game-slug>/`](../../../games/) that opens standalone in the editor. It uses the reusable Rust GDExtension at [`extensions/hugmesh-bridge/`](../../../extensions/hugmesh-bridge/) for HostBridge signals. The HugMesh Export plugin produces a deploy-ready zip with `manifest.json` (schema v2) + `register.sql` + `bundle/`. You ship it either through `make build-godot` (committed in-repo) or through `/admin/games` (hot upload).

## Prerequisites

- Godot 4.6 in `PATH` as `godot`.
- Rust stable (`cargo --version`).
- Optional: Emscripten + rustup `wasm32-unknown-emscripten` for the Rust GDExtension WASM target. The dockerised builder at [`tools/build-rust-wasm/Dockerfile`](../../../tools/build-rust-wasm/Dockerfile) handles this without polluting the host.

Run once: `make build-rust` so every `games/<dir>/bin/` gets a fresh `libhugmesh_bridge.so` and `hugmesh_bridge.wasm`.

## Step 1 — Create the project

1. `mkdir games/my-game-slug/`
2. Open Godot → New Project → set path → Create.
3. Copy `hugmesh_bridge.gdextension` and `addons/hugmesh_export/` from [`games/three-wizards-in-a-trenchcoat/`](../../../games/three-wizards-in-a-trenchcoat/).
4. Drop a starter scene at `scenes/main.tscn`.

## Step 2 — Pick your slugs

**GAME slug** appears in `games.slug`, `federation_modules.slug`, and (sometimes) the directory name under [`games/`](../../../games/).
**CHAPTER slug(s)** — one per export preset; appear in `chapters.slug`, the export path, and the Makefile.

Lowercase, hyphen-free, kebab-case (`forest-glade`, not `ForestGlade`). Once committed, slugs do not change without a coordinated DB migration. Pick well.

## Step 3 — Wire HostBridge

The reusable GDExtension exposes a `HostBridge` node with typed signals:

| Signal | Payload |
|---|---|
| `movement(x, y, z, action, pressed)` | Player movement / cast / interact |
| `world_state(state)` | Spawn / despawn / chapter transition |
| `character_state(state)` | HP / MP / XP / flags |
| `scene_state(state)` | Scene mount / unmount |
| `time(state)` | Game-clock tick |
| `gamepad_received(payload)` | Generalised input — gamepad |
| `mouse_received(payload)` | Generalised input — mouse |
| `keyboard_received(payload)` | Generalised input — keyboard |
| `touch_received(payload)` | Generalised input — touch |
| `audio_received(payload)` | Audio cue ingress |
| `audio_egress(payload)` | Audio cue outgoing |
| `console_egress(level, message)` | Forward WASM console line to BFF |

Add a HostBridge node to the scene tree, then connect signals from your gameplay scripts. See [`games/three-wizards-in-a-trenchcoat/scripts/scene_loader.gd`](../../../games/three-wizards-in-a-trenchcoat/) for a reference subscriber.

## Step 4 — Author export presets

Project → Export → Add new preset → Web → name it after the chapter slug (e.g. `ForestGlade`).

```ini
export_path  = ../../backend/src/main/resources/assets/levels/forest-glade/index.html
custom_features = level_forest-glade
variant/extensions_support = true
# Do NOT exclude *.gdextension — the Rust binary needs to ship with the bundle.
```

Save the `.cfg`, do not export from the editor. The Makefile drives headless export.

## Step 5a — Commit and ship

1. Add `games/<dir>/` to `GAME_PROJECTS` in the root [`Makefile`](../../../Makefile).
2. Define `PRESETS_<dir>` with the preset names.
3. Write a Liquibase changeset for `games`, `chapters`, `federation_modules`, `game_access` (use [`053-chess-godot-federation.xml`](../../../backend/src/main/resources/db/changelog/053-chess-godot-federation.xml) as the template).
4. Commit. CI runs `make build` and your game lands.

## Step 5b — Upload through admin

In Godot: *Project → Tools → HugMesh → Export…*. Fill in slug, display name, description, scene type. The plugin writes a deploy-ready zip with the v2 `manifest.json` + `register.sql` + `bundle/`.

Visit `/admin/games` on the live stack and drag the zip into the upload card. The BFF validates manifest schema v2, hashes the bundle, atomically moves to `<gamesDir>/<slug>/<sha-prefix>`, and runs `register.sql` in one R2DBC transaction.

See [howto/upload-and-rollback-game.md](../howto/upload-and-rollback-game.md).

## Local test loop

1. `docker compose up -d postgres cassandra casdoor api wasm-fragment`.
2. In Godot, press Play. `scripts/api_client.gd` auto-targets `http://localhost:8080`.
3. `scripts/scene_loader.gd` polls `/api/chapters/{id}/scene` and spawns elements.
4. Edit a scene; press Play again. No build step required for local iteration.
5. Ready to ship: `make build-godot && docker compose restart api wasm-fragment`.

## Common mistakes

- **Slugs differ across the four contract points** → iframe 404s or wrong scene loads. The four places: `games.slug` DB column, `export_presets.cfg` export_path directory, Makefile `PRESETS_<dir>` entry, `chapters.asset_url` DB column.
- **`exclude_filter` in export_presets contains `*.gdextension`** → bundle ships without the Rust binary and `HostBridge` is undefined at runtime. Symptom: `ERROR: Cannot get class 'HostBridge'`.
- **A file the runtime reads as raw bytes has no `.import` sidecar** (a `.vrm` parsed by godot-vrm, a JSON catalogue) → `export_filter="all_resources"` leaves it out and the export still exits 0. Name it in the preset's `include_filter` AND list it in `games/<slug>/export_required.txt`: the game's `tests/run_tests.gd` then checks the filters cover it with the engine's own matching, and `make build-godot` runs `scripts/verify-godot-pck.mjs` on the produced `index.pck` and fails if it is missing or drifted. character-forge lost all 11 `.vrm` this way (hugmesh-character-forge-bff-api#3).
- **`--script res://tests/run_tests.gd` on a fresh checkout parses with "Identifier 'X' not declared" for every `class_name`** → the global class cache (`.godot/global_script_class_cache.cfg`) is only written by an import pass. `make test-godot` runs `godot --headless --path games/<slug> --import` first; do the same before an ad-hoc run. A green run on a warm checkout says nothing about a cold one.
- **Calling `add_child` from `_ready` on a node already in the tree** → "parent is busy". Use `call_deferred`.
- **Forgetting to run `make build-rust` after pulling** → stale `games/*/bin/` binaries.
- **`variant/extensions_support` left at `false`** → bundle exports but `HostBridge` is missing. Set to `true`.

## When movement doesn't happen in the WASM iframe

Symptoms + causes, in order of likelihood:

1. **Rust GDExtension not loaded** — log line `ERROR: Cannot get class 'HostBridge'`. The `bin/hugmesh_bridge.wasm` is missing from the bundle. Check [`extensions/hugmesh-bridge/.cargo/config.toml`](../../../extensions/hugmesh-bridge/.cargo/config.toml) has `-Cpanic=abort` for `wasm32-unknown-emscripten`, and the export presets have `variant/extensions_support=true` + no `*.gdextension` exclude filter. Then `make build-rust && make build-godot`.
2. **Browser cached old wasm** — `wasm-fragment/nginx.conf` adds `Cache-Control: no-cache` on `.wasm`/`.pck`. Hard-refresh (Ctrl+Shift+R) once after a rebuild.
3. **`/api/chapters/{id}/scene` 404** — the BFF renamed the path. PR #24 added `@RequestMapping({"/api/chapters", "/api/levels"})` for back-compat so already-exported bundles work. If you re-export, the URL flips to chapters.
4. **403 from `/api/controls/{id}`** — the JWT subject isn't the controller-role holder for the session. Player needs to claim controller via `POST /api/sessions/control`.
5. **`shimInstalled: false` in iframe** — the `hugmesh-shim.js` script tag's path was absolute (`/hugmesh-shim.js`); it now uses the relative `../hugmesh-shim.js` so it resolves under both the host proxy AND a direct wasm-fragment access.

See [CLAUDE.md](../../../CLAUDE.md) for the full failure-mode catalog.
