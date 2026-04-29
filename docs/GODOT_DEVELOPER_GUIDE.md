# Godot Developer Guide

This guide is for anyone working inside `games/<slug>/` and needs their work to **compile into the shapes the rest of the stack expects**: slugs, export paths, asset registrations, scene DTOs, and federation manifests. Follow it and the API, Angular host, and DB will pick up your level / textures / plugins / new game without any other code changes.

> **Layout note.** The Godot tree was split into per-game projects in section G of the federation plan. Every game is a self-contained Godot project under `games/<game-slug>/`, opens standalone in the Godot editor, and produces one or more Web export bundles. The legacy single `godot-project/` directory is gone.

```
games/
├── three-wizards-in-a-trenchcoat/   # slug: three-wizards (parent game)
│   ├── project.godot
│   ├── export_presets.cfg            # Cave + Field presets
│   ├── scenes/cave.tscn, scenes/field.tscn
│   ├── scripts/                       # api_client.gd, scene_loader.gd, …
│   ├── addons/snapdragon_export/      # editor plugin → upload zip
│   ├── snapdragon_bridge.gdextension  # GDExtension binding
│   └── bin/libsnapdragon_bridge.so    # populated by `make build-rust`
└── dodecahedron-demo/               # slug: dodecahedron
    └── …
```

A Rust GDExtension (`extensions/snapdragon-bridge/`) supplies the bridge ABI. `make build-rust` builds it once and copies `libsnapdragon_bridge.so` into every game's `bin/` so each project can be opened directly.

---

## The contract in one page

Every level must have a **slug** that appears in exactly the same lowercase, hyphen-free form in **four** places:

| Place | Example |
|---|---|
| `levels.slug` DB column | `cave` |
| `games/<game-slug>/export_presets.cfg` → `export_path` directory | `.../assets/levels/cave/index.html` |
| `Makefile` `PRESETS_<game-slug>` entry (preset name, capitalised) | `Cave` |
| `levels.asset_url` DB column | `/api/assets/levels/cave/index.html` (legacy) **or** `/_remotes/wasm-fragment/cave/index.html` (federated) |

If any of those four drift, the level 404s in the iframe or loads the wrong bundle. **Never use spaces or uppercase in the slug itself** — the Makefile lowercases the preset name with `tr '[:upper:]' '[:lower:]'`, so `Cave` (preset) → `cave` (directory).

A **game slug** (parent of one or more level slugs) appears in three more places:

| Place | Example |
|---|---|
| `games.slug` DB column | `three-wizards` |
| `federation_modules.slug` (the Module Federation remote) | `three-wizards` |
| `Makefile` `GAME_PROJECTS` row (filesystem dir under `games/`) | `three-wizards-in-a-trenchcoat` |

The directory under `games/` is allowed to be longer / more descriptive than the database slug — the Makefile maps one to the other via the `PRESETS_<dir>` variable.

Supporting contracts:

- Scene DTOs returned from `GET /api/levels/{id}/scene` must have `elementType` values that `scene_loader.gd` knows (`world_env`, `sky_env`, `ambient_light`, `sun_light`, `torch`, `fill_light`, `fairy`, `stalagmite`, `stalactite`, `tree`, `cottage`). Add a new value → add a handler in `scene_loader._spawn()`.
- Level-transition `postMessage` payload must literally be `{type: "TW_LEVEL_TRANSITION", slug, levelId}` — the Angular host's `HomeComponent.onWindowMessage` matches on the literal string. (This used to live in `game-canvas.component.ts`; section H of the federation plan lifted it to the host so it works regardless of which remote rendered the iframe.)
- Assets referenced from a scene element's `properties.textureUrl` must be either a classpath file under `assets/levels/{slug}/` or registered in `level_assets` with a `filePath` that `AssetController` can resolve.

---

## Two delivery paths: classpath assets vs. federated remotes

There are two ways a Godot bundle reaches the player:

1. **Classpath assets (legacy / dev-loop).** `make build-godot` writes the export into `backend/src/main/resources/assets/levels/<slug>/`. `AssetController` serves the bundle at `/api/assets/levels/<slug>/index.html`. This path is still used for development and as a fallback.
2. **Federated remote via `wasm-fragment`.** The `wasm-fragment` Docker service (`wasm-fragment/`) is an nginx that bakes the same exports and serves them at `/_remotes/wasm-fragment/<slug>/index.html` with the COOP/COEP/CORP headers WASM games need. The host shell's `RemoteLoaderService` picks this path for federated games (`SCENE_TYPE_TO_GAME_SLUG` in `home.component.ts`).

You don't have to pick — both paths read the same exports. The federated path adds a `launch_uid` query string for operational visibility (sweeper / revoke) and lets the Angular host swap the active game without reloading the page.

---

## Running the Godot editor against the live stack

Each project under `games/<slug>/` opens directly in Godot 4.5+. `scripts/api_client.gd` auto-selects between native run and web export:

```
Editor / native run  → fetch('http://localhost:8080/api/…')
Web export (iframe)  → fetch(same-origin '/api/…')
```

To play the full game from inside the editor:

1. Start the backend stack once:
   ```
   docker compose up -d            # or: make dev-backend
   ```
2. Ensure the GDExtension is built and copied:
   ```
   make build-rust
   ```
3. Open `games/<slug>/project.godot` in Godot and press **Play**. The scene_loader polls `/api/levels/{id}/scene` and populates fairies/lights/geometry exactly as the web build does.
4. User-added assets registered under `level_assets` are reachable by label via `ApiClient.resolve_asset(level_id, "oak-bark", cb)`.

If the editor can't reach the API, the `BridgeApiClient` (running in standalone mode because no parent module is attached) logs an HTTP error to the output panel. No silent fallbacks.

---

## All API networking flows through the bridge

**Game scripts never call `HTTPRequest` directly.** Use the
`snapdragon_bridge` addon's `BridgeApiClient`:

```gdscript
const _BridgeApiClient = preload("res://addons/snapdragon_bridge/bridge_api_client.gd")

var _api: Node = null

func _ready() -> void:
    _api = _BridgeApiClient.new()
    add_child(_api)
    _api.get("/api/chapters/1/scene", func(status, body):
        if status == 200: _apply_scene(body))
```

What the bridge does for you:

- In **web export** mode, the request is forwarded through `HostBridge`
  to the host shell which performs the actual HTTP call. The host's
  `HttpClient` interceptor adds the `Authorization` bearer token
  automatically, and the bridge attaches `X-Session-Id` so the BFF's
  per-session SSE sinks scope the gameplay correctly. Iframe code never
  needs to know about JWTs or session ids.
- In **editor / native (F5)** mode, `BridgeApiClient` falls back to a
  direct `HTTPRequest` against `http://localhost:8080`. The contract is
  the same; only the transport layer changes.

The legacy three-wizards `api_client.gd` is now a thin wrapper around
`BridgeApiClient` that preserves the named methods
(`fetch_scene` / `fetch_level_assets` / `resolve_asset`); existing
callers keep working without code changes. New games should use
`BridgeApiClient` directly.

See [decision 014](publishing/architecture/build/architecture.pdf) for
the full design rationale, or the addon's
[`README.md`](../extensions/snapdragon-bridge/addons/snapdragon_bridge/README.md)
for usage notes.

## Multiplayer scripting via the snapdragon_bridge addon

The addon ships every reusable multiplayer + bridge primitive any
Snapdragon-hosted game needs:

| File | Purpose |
|---|---|
| `plugin.cfg` + `plugin.gd` | Editor plugin manifest. Enable in `project.godot`'s `[editor_plugins]`. |
| `bridge_glue.gd` | `JavaScriptBridge` ↔ `HostBridge` plumbing. Forwards `input_egress` / `view_egress` / `api_request_egress`; pumps `SD_API_RESPONSE` back into `dispatch_api_response`. |
| `bridge_api_client.gd` | Callable-based wrapper around the bridge's API surface. The preferred surface for any Godot → BFF call. |
| `multiplayer_lobby.gd` | Slot orchestration: subscribes to `slot_registered` / `slot_released` / `movement_received`, fetches `/api/sessions/{id}` *via the bridge*, spawns one programmatic `CharacterBody3D` per registered slot. Subclass + override `spawn_avatar()` to swap in your own player scene. |
| `multiplayer_lobby_menu.tscn` + `multiplayer_lobby_menu.gd` | Drop-in pre-game lobby UI: list open sessions for `game_slug`, *Join* per row, create-new form. Emits `session_joined(session_id, slot_index)`. |

Source of truth lives in
`extensions/snapdragon-bridge/addons/snapdragon_bridge/`. `make build-rust`
syncs the canonical copy into every game project's
`addons/snapdragon_bridge/` (this dir is gitignored per-game; never
hand-edit). Any game that enables the plugin in `project.godot` picks
up future updates automatically on the next sync.

---

## Adding a new level to an existing game

A level is **one compiled WASM bundle** that lives inside an existing game project. Each level has its own preset and its own row in `levels`.

### 1. Pick a level slug

Lowercase, hyphen-free, stable. Examples: `cave`, `field`, `forest-glade`, `tower`. Do **not** use `CaveOfDoom` or `cave_of_doom`. The slug will be written into `export_presets.cfg`, `Makefile`, and the DB, so make it short and final.

### 2. Create the scene file

Put the main `.tscn` in `games/<game-slug>/scenes/<level-slug>.tscn`. The convention is that the scene's root node runs `scene_loader.gd` which reads `?level_id=N` from the iframe URL, calls `/api/levels/{N}/scene`, and spawns the elements returned in the DTO. You don't need to hardcode scene contents.

### 3. Add an export preset

Open `games/<game-slug>/export_presets.cfg` and append a new `[preset.N]` block. Copy an existing preset block verbatim, then edit:

```ini
[preset.N]

name="ForestGlade"                              ; CamelCase, no spaces. Used by Makefile.
platform="Web"
runnable=true
custom_features="level_forest-glade"            ; matches lowercase slug
exclude_filter="*.gdextension"                  ; GDExtension is shipped via build-rust
export_path="../../backend/src/main/resources/assets/levels/forest-glade/index.html"
```

The `../../` prefix is relative to the game project directory and resolves to the repo's `backend/src/main/resources/assets/levels/`. Keep the `[preset.N.options]` block identical to a working preset — `html/canvas_resize_policy=2`, `extensions_support=true` (or `false` if you don't need GDExtension at runtime), etc.

> **Note on `exclude_filter`.** With per-game projects you no longer need to enumerate other levels' `.tscn` files — Godot's tree-shaking ships only what each preset references, and only one game's scenes live in the project. The single `*.gdextension` exclusion stays.

### 4. Register the preset in the Makefile

Open `Makefile` at the project root and append the preset name to `PRESETS_<game-slug>`:

```makefile
GAME_PROJECTS = three-wizards-in-a-trenchcoat dodecahedron-demo
PRESETS_three-wizards-in-a-trenchcoat = Cave Field ForestGlade
PRESETS_dodecahedron-demo             = Dodecahedron
```

`build-godot` iterates `GAME_PROJECTS`, looks up the per-game preset list, lowercases each entry to derive the output directory, and calls `godot --headless --export-release "<preset>"` from inside that game's directory.

### 5. Seed the DB

Either add a Liquibase changeset (preferred for committed content) or run `make seed-db` after an SQL insert like:

```sql
INSERT INTO levels (name, slug, scene_type, sort_order, asset_url, game_id, scene_config, created_at)
VALUES ('The Forest Glade', 'forest-glade', 'forest', 3,
        '/api/assets/levels/forest-glade/index.html',
        (SELECT id FROM games WHERE slug='three-wizards'),
        '{}', NOW())
ON CONFLICT (slug) DO UPDATE SET asset_url = EXCLUDED.asset_url;
```

Then add `scene_elements` rows and (optionally) `level_prerequisites` to gate the level behind a quest. See `db/changelog/004-seed-scene-elements.xml` for the pattern.

### 6. Build

```
make build-rust         # populates games/*/bin/libsnapdragon_bridge.so
make build-godot        # exports every preset in every game → classpath
make seed-db            # refreshes DB if you changed seed SQL
docker compose build api wasm-fragment && docker compose up -d api wasm-fragment
```

Rebuild `wasm-fragment` whenever you change `assets/levels/` so the federated path picks up the new bundle. The new level appears on the home page with its lock state driven by its prerequisites.

---

## Adding a brand-new game

A *game* is one Godot project under `games/<game-slug-dir>/` that produces one or more level bundles, plus one row in `games`, one row in `federation_modules`, and (usually) a `games-<slug>` Docker compose service if you want a separately deployable Module Federation remote rather than a host-rendered fragment.

The fast path: use the **Snapdragon Export** editor plugin already wired into both stock games (`addons/snapdragon_export/`).

### Option A — let the upload pipeline register everything

1. Create a new project under `games/<dir>/` (copy an existing one as a template).
2. In Godot, open **Project → Tools → Snapdragon → Export…**, fill in slug / display name / description / scene type, and let the plugin produce a deploy-ready zip with `manifest.json` (schema v2) + `register.sql` + `bundle/`.
3. Hit `/api/admin/games/upload` (the BFF's `GameUploadController`) with the zip. The install transaction:
   - Validates the manifest, computes a content hash, supersedes the previous upload row.
   - Moves the bundle into `app.uploads.games-dir/<slug>/<version-dir>/` and updates `games.current_version_dir`.
   - Runs `register.sql` which inserts/updates rows in `games`, `federation_modules`, and `game_access` atomically.

Once the request returns, the game is live: the host shell's federation manifest discovers it on next fetch and the home page picks it up.

### Option B — wire it manually

If you're committing a game to the repo (rather than uploading at runtime), do all four:

1. Add the project dir to `GAME_PROJECTS` and define `PRESETS_<dir>` in the Makefile.
2. Add a Liquibase changeset that inserts the `games` row, the `federation_modules` row, and `level_prerequisites`/`game_access` if needed. See `db/changelog/017-snapdragon-catalog.xml` and `018-federation-modules.xml` for the patterns.
3. (Optional) Add a `games-<slug>` service to `docker-compose.yml` if the game ships its own remote (separate Angular project under `frontend/projects/games-<slug>/`). Stock games use this; one-off uploads can ride the shared `wasm-fragment` remote instead.
4. Run the section-6 build chain.

The four-place slug rule still applies — every place that names the game must use the same `<game-slug>` value in `games.slug`.

---

## Adding textures, plugins, or other assets

Assets that aren't part of the Godot scene graph (custom textures applied at runtime, GDExtension plugins loaded dynamically, audio files, etc.) go in the `level_assets` registry. The registry gives every asset a **label** so GDScript can look it up without hardcoding filenames.

### 1. Drop the file into the level's asset directory

```
backend/src/main/resources/assets/levels/<slug>/<filename>
```

Example: `backend/src/main/resources/assets/levels/forest-glade/moss-bark.png`.

### 2. Register it

```
curl -X POST http://localhost:8080/api/levels/3/assets \
  -H 'Content-Type: application/json' \
  -d '{
    "label": "moss-bark",
    "assetType": "texture",
    "filePath": "/api/assets/levels/forest-glade/moss-bark.png",
    "contentType": "image/png",
    "sizeBytes": 248310
  }'
```

Labels must be unique within a level. Typical `assetType` values: `texture`, `mesh`, `audio`, `plugin`, `shader`.

Prefer committing a Liquibase changeset for assets you want shipped to every install — see [013-seed-level-assets.xml](../backend/src/main/resources/db/changelog/013-seed-level-assets.xml) for the pattern.

### 3. Use from GDScript

```gdscript
var api := ApiClient.new()
add_child(api)
api.resolve_asset(level_id, "moss-bark", func(url):
    var http := HTTPRequest.new()
    add_child(http)
    http.request_completed.connect(func(_r, _c, _h, body):
        var img := Image.new()
        img.load_png_from_buffer(body)
        my_material.albedo_texture = ImageTexture.create_from_image(img)
    )
    http.request(url)
)
```

Or list everything registered on the level and resolve by type:

```gdscript
api.fetch_level_assets(level_id, func(assets):
    for a in assets:
        if a["assetType"] == "texture":
            ...
)
```

---

## Mid-game level transitions

When something in-game (a portal, a cutscene, a quest completion) should unload the current WASM and mount the next level's bundle, call:

```gdscript
$GameBridge.request_level_transition("forest-glade", 3)
```

What happens:

1. Godot posts `window.parent.postMessage({type:"TW_LEVEL_TRANSITION", slug:"forest-glade", levelId:3}, "*")`.
2. Angular's `HomeComponent.onWindowMessage` (host-level — moved out of `game-canvas.component.ts` in section H) catches it, hits `GET /api/levels/3` to read the fresh `asset_url`, mints a new `launch_uid` via `POST /api/games/{slug}/launch` if the target is federated, and either swaps the iframe `src` (host fragment) or threads the new inputs into the active remote component.
3. The new level's Godot bundle loads cold; its `scene_loader.gd` fetches `/api/levels/3/scene` and spawns.

**Important:** the current WASM is fully destroyed. Anything in memory is lost. Persist to the DB via `PUT /api/characters/{id}` (stats, HP) or `POST /api/controls/{id}` (movement) before calling `request_level_transition`.

---

## What breaks if you skip a step

| Symptom | Cause |
|---|---|
| Iframe 404 on `index.html` | `levels.asset_url` slug differs from the classpath directory, or `wasm-fragment` not rebuilt after `make build-godot` |
| Blank iframe, no error | Preset name in `PRESETS_<game>` doesn't match `export_presets.cfg`, or `GAME_PROJECTS` row pointing at a nonexistent directory |
| Wrong scene loads | `scene_elements.level_id` FK is stale — check it matches the new `levels.id` |
| `resolve_asset` returns 404 | `label` isn't in `level_assets` or `level_id` wrong |
| `postMessage` transition doesn't fire | Payload shape wrong — must be literally `TW_LEVEL_TRANSITION` and include `slug` + `levelId` |
| `ERR_BLOCKED_BY_RESPONSE` on federated WASM iframe | `wasm-fragment/nginx.conf` missing `Cross-Origin-Resource-Policy: cross-origin`, or host nginx not stripping duplicate COOP/COEP from the upstream |
| Federation manifest shows the game but iframe stays blank | `federation_modules.remote_entry_url` points at a host that isn't reachable from the browser, or the game ships its own remote but `games-<slug>` compose service isn't up |
| `ERROR: Parent node is busy setting up children, add_child() failed` | Calling `add_child` (or `get_tree().root.add_child`) directly from `_ready()` or `_process()` while siblings are still `_ready`-ing. Use `node.add_child.call_deferred(child)` |
| Loading dots animate forever, prefetch logged success | `JavaScriptBridge.eval(...) != true` Variant compare is unreliable. Use `bool(JavaScriptBridge.eval("!!window.foo"))` to coerce through a real JS boolean |
| Iframe shows old WASM after `make build-godot` | Browser cached the iframe HTML. The Angular remote already cache-busts with `&t=...`; if the timestamp isn't in the URL, it's the wrong code path |
| GDExtension symbol fails to load in editor | `make build-rust` was skipped or the per-game `bin/libsnapdragon_bridge.so` is stale — the Makefile copies the freshly-built `.so` into every `games/*/bin/` |

### Why so many `call_deferred` calls

Godot 4 enforces a "tree is busy" guard while `_ready()` is propagating. Any
script that adds children or attaches nodes to `get_tree().root` from inside
`_ready` / `_process` / `_input` runs the risk of hitting it. The convention
in this project is:

- `_ready()` builds detached subtrees with `Node.new()` + sync `add_child` on
  those new nodes (safe — the new node isn't in the tree yet).
- The final attachment to a node that **is** in the tree (the parent or
  `get_tree().root`) is always `call_deferred`'ed.
- `_apply()` and any spawn handler called from polling code (`_process`)
  are similarly deferred. See `scene_loader._process` for the pattern.

If you're writing a new spawn handler, copy that pattern. Sync `add_child` on
fresh nodes you just created, deferred `add_child` for anything attaching to
the live tree.

---

## Compile-time checklist (run before pushing)

```
make build-rust         # GDExtension compiles, .so copied into every games/*/bin/
make build-godot        # every preset in every game exports cleanly
make build-backend      # Liquibase + R2DBC bindings match the entity shape
make build-frontend     # TypeScript models + templates typecheck
make gen-api            # optional, refreshes Angular TS client from /v3/api-docs
```

If any step fails, don't force-merge — the mismatch will show up as a runtime 404 or a silent empty scene later.
