# Godot Developer Guide

This guide is for anyone working inside `games/<slug>/` whose work must **compile into the shapes the rest of the stack expects**: slugs, export paths, asset registrations, scene DTOs, and federation manifests. Follow it and the API, the Angular host, and the DB will pick up your chapter, textures, plugins, or new game with no other code change.

> **Layout note.** The Godot tree was split into per-game projects in section G of the federation plan. Every game is a self-contained Godot project under `games/<game-slug>/`. It opens standalone in the Godot editor and produces one or more Web export bundles. The legacy single `godot-project/` directory is gone.

> **Terminology note.** This guide uses the platform-wide `level` → `chapter` rename (see the "Naming" section of [`CLAUDE.md`](../CLAUDE.md): `levels` → `chapters`, `level_id` → `chapter_id`, `level_assets` → `chapter_assets`, `level_prerequisites` → `chapter_prerequisites`; changeset [`028-rename-chapter-instant.xml`](../backend/src/main/resources/db/changelog/028-rename-chapter-instant.xml) is the migration). Three things kept the word `level` on purpose, because that is what the code says: the export directory `backend/src/main/resources/assets/levels/<slug>/` (`GODOT_EXPORT_ROOT` in the `Makefile`), the asset route it is served on (`/api/assets/levels/<slug>/…`, `AssetController`), and the `custom_features="level_<slug>"` tag in `export_presets.cfg` (used by cave, field and dodecahedron; the newer projects use `game_<slug>` with underscores, such as `game_chess_godot`). `/api/levels/...` also still resolves as a back-compat alias (`@RequestMapping({"/api/chapters", "/api/levels"})`), but this guide uses the canonical `/api/chapters/...` throughout. Prefer it in new code. The bridge classes (`BridgeApiClient`, `BridgeGlue`, the multiplayer lobby) were also rewritten from GDScript to Rust GodotClasses; see [decision 014](wiki/decisions/014-bridge-routed-networking.md).

```
games/
├── three-wizards-in-a-trenchcoat/   # slug: three-wizards (parent game)
│   ├── project.godot
│   ├── export_presets.cfg            # Cave + Field presets
│   ├── scenes/cave.tscn, scenes/field.tscn
│   ├── scripts/                       # api_client.gd, scene_loader.gd, …
│   ├── addons/hugmesh_export/      # editor plugin → upload zip
│   ├── hugmesh_bridge.gdextension  # GDExtension binding
│   └── bin/libhugmesh_bridge.so    # populated by `make build-rust`
├── dodecahedron-demo/               # slug: dodecahedron
│   └── …
├── chess-godot/                     # slug: chess-godot (Godot port, Rust chess core via the bridge)
│   └── …
├── connect-four-godot/              # slug: connect-four-godot (same recipe as chess-godot)
│   └── …
├── character-forge/                 # slug: character-forge (single-player 3D character maker)
│   └── …
└── shadows-of-the-net/              # slug: shadows-of-the-net ("Shadows of the Net – CHAT")
    ├── project.godot                #   PURE GDScript — deliberately NO hugmesh_bridge
    ├── export_presets.cfg           #   Web (threads OFF) + Linux/Windows PCVR presets
    ├── scenes/room.tscn             #   16-seat noir chamber, media surfaces, stage
    └── scripts/                     #   session_bridge.gd (SD_*) + chat3d_bridge.gd (Chat3D)
```

A Rust GDExtension (`extensions/hugmesh-bridge/`) supplies the bridge ABI. `make build-rust` builds it once and copies `libhugmesh_bridge.so` into every game's `bin/`, so each project opens directly.

---

## The contract in one page

Every chapter must have a **slug** that appears in exactly the same lowercase form in **four** places. Hyphens are fine (`chess-godot`); the upload pipeline accepts `^[a-z0-9][a-z0-9-]{0,63}$`:

| Place | Example |
|---|---|
| `chapters.slug` DB column | `cave` |
| `games/<game-slug>/export_presets.cfg` → `export_path` directory | `.../assets/levels/cave/index.html` |
| `Makefile` preset name, in the `case` arms of `build-godot` and `patch-godot` (capitalised; any hyphens must already be in the name) | `Cave` |
| `chapters.asset_url` DB column | `/api/assets/levels/cave/index.html` (legacy) **or** `/_remotes/wasm-fragment/cave/index.html` (federated) |

If any of those four drift, the chapter 404s in the iframe or loads the wrong bundle. **Never use spaces or uppercase in the slug itself.** The Makefile lowercases the preset name with `tr '[:upper:]' '[:lower:]'`, so `Cave` (preset) → `cave` (directory).

A **game slug** (parent of one or more chapter slugs) appears in three more places:

| Place | Example |
|---|---|
| `games.slug` DB column | `three-wizards` |
| `federation_modules.game_slug` (the Module Federation remote's slug column is `games-three-wizards`) | `three-wizards` |
| `Makefile` `GAME_PROJECTS` row (filesystem dir under `games/`) | `three-wizards-in-a-trenchcoat` |

The directory under `games/` may be longer and more descriptive than the database slug. The Makefile maps one to the other in the `case` arms of `build-godot` and `patch-godot`. (`PRESETS_<dir>` variables exist beside `GAME_PROJECTS`, but no recipe reads them.)

Supporting contracts:

- Scene DTOs returned from `GET /api/chapters/{id}/scene` must have `elementType` values that `scene_loader.gd` knows (`world_env`, `sky_env`, `ambient_light`, `sun_light`, `torch`, `fill_light`, `fairy`, `stalagmite`, `stalactite`, `tree`, `cottage`). To add a value, add a handler in `scene_loader._spawn()`.
- The chapter-transition `postMessage` payload must literally be `{type: "TW_CHAPTER_TRANSITION", slug, chapterId}`. The Angular host's `HomeComponent.onWindowMessage` matches on that exact string. (It used to live in `game-canvas.component.ts`; section H of the federation plan lifted it to the host, so it works whichever remote rendered the iframe.)
- An asset referenced from a scene element's `properties.textureUrl` must be either a classpath file under `assets/levels/{slug}/` or registered in `chapter_assets` with a `filePath` that `AssetController` can resolve.

---

## Two delivery paths: classpath assets vs. federated remotes

A Godot bundle reaches the player in one of two ways:

1. **Classpath assets (legacy / dev-loop).** `make build-godot` writes the export into `backend/src/main/resources/assets/levels/<slug>/`. `AssetController` serves the bundle at `/api/assets/levels/<slug>/index.html`. This path is still used for development and as a fallback.
2. **Federated remote via `wasm-fragment`.** The `wasm-fragment` Docker service (`wasm-fragment/`) is an nginx that bakes the same exports and serves them at `/_remotes/wasm-fragment/<slug>/index.html`, with the COOP/COEP/CORP headers WASM games need. The host shell's `RemoteLoaderService` picks this path for federated games (`SCENE_TYPE_TO_GAME_SLUG` in `home.component.ts`).

You need not choose: both paths read the same exports. The federated path adds a `launch_uid` query string for operational visibility (sweeper / revoke) and lets the Angular host swap the active game without reloading the page.

---

## Running the Godot editor against the live stack

Each project under `games/<slug>/` opens directly in Godot 4.6 (the projects' `config/features` say `4.6`). `scripts/api_client.gd` picks between native run and web export:

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
3. Open `games/<slug>/project.godot` in Godot and press **Play**. The scene_loader polls `/api/chapters/{id}/scene` and populates fairies, lights and geometry exactly as the web build does.
4. User-added assets registered under `chapter_assets` are reachable by label through `ApiClient.resolve_asset(chapter_id, "oak-bark", cb)`.

If the editor cannot reach the API, `BridgeApiClient` (in standalone mode, because no parent module is attached) logs an HTTP error to the output panel. There are no silent fallbacks.

---

## All API networking flows through the bridge

**Game scripts never call `HTTPRequest` directly.** Use `BridgeApiClient` from
the `hugmesh_bridge` addon:

```gdscript
var _api: Node = null

func _ready() -> void:
    _api = ClassDB.instantiate("BridgeApiClient")  # native Rust GodotClass, no preload
    add_child(_api)
    _api.http_get("/api/chapters/1/scene", func(status, body):
        if status == 200: _apply_scene(body))
```

What the bridge does for you:

- In **web export** mode, the request goes through `HostBridge` to the host
  shell, which makes the actual HTTP call. The host's `HttpClient` interceptor
  adds the `Authorization` bearer token, and the bridge attaches `X-Session-Id`
  so the BFF's per-session SSE sinks scope the gameplay correctly. Iframe code
  never needs to know about JWTs or session ids.
- In **editor / native (F5)** mode, `BridgeApiClient` falls back to a direct
  `HTTPRequest` against `http://localhost:8080`. The contract is the same; only
  the transport changes.

The legacy three-wizards `api_client.gd` is now a thin wrapper around
`BridgeApiClient`. It keeps the named methods (`fetch_scene` /
`fetch_chapter_assets` / `resolve_asset`), so existing callers work unchanged.
New games should use `BridgeApiClient` directly.

See [decision 014](wiki/decisions/014-bridge-routed-networking.md) for the
design rationale, or the addon's
[`README.md`](../extensions/hugmesh-bridge/addons/hugmesh_bridge/README.md)
for usage notes.

## Multiplayer scripting via the hugmesh_bridge addon

The addon ships every reusable multiplayer and bridge primitive that a
HugMesh-hosted game needs:

| File / class | Purpose |
|---|---|
| `plugin.cfg` + `plugin.gd` | Editor plugin manifest (GDScript). Enable in `project.godot`'s `[editor_plugins]`. |
| `BridgeGlue` (Rust GodotClass, `bridge_glue.rs`) | `JavaScriptBridge` ↔ `HostBridge` plumbing. Forwards `input_egress` / `view_egress` / `api_request_egress`; pumps `SD_API_RESPONSE` back into `dispatch_api_response`. |
| `BridgeApiClient` (Rust GodotClass, `bridge_api_client.rs`) | Callable-based wrapper around the bridge's API surface (`http_get`/`http_post`/`http_put`/`http_delete`). The preferred surface for any Godot → BFF call. |
| `HugMeshMultiplayerLobby` (Rust GodotClass, `multiplayer_lobby.rs`) | Slot orchestration: subscribes to `slot_registered` / `slot_released` / `movement_received`, fetches `/api/sessions/{id}` *via the bridge*, spawns one programmatic `CharacterBody3D` per registered slot. |
| `multiplayer_lobby_menu.tscn` + `multiplayer_lobby_menu.gd` | The one remaining GDScript UI piece: a drop-in pre-game lobby that lists open sessions for `game_slug`, has a *Join* button per row, and a create-new form. Emits `session_joined(session_id, slot_index)`. |

`BridgeGlue`, `BridgeApiClient`, and `HugMeshMultiplayerLobby` used to be
GDScript files in this addon. They were rewritten as native Rust GodotClasses
(source of truth: `extensions/hugmesh-bridge/src/*.rs`) registered by the
`hugmesh_bridge.gdextension` file; see
[decision 014](wiki/decisions/014-bridge-routed-networking.md) for the
rationale. The remaining GDScript pieces (`plugin.gd`,
`multiplayer_lobby_menu.gd`) still live under
`extensions/hugmesh-bridge/addons/hugmesh_bridge/`. `make build-rust` syncs the
canonical addon copy and the compiled GDExtension binary into every game
project's `addons/hugmesh_bridge/` and `bin/` (both gitignored per game; never
hand-edit them). A game that enables the plugin in `project.godot` picks up
future updates on the next sync.

---

## Adding a new chapter to an existing game

A chapter is **one compiled WASM bundle** inside an existing game project. Each chapter has its own preset and its own row in `chapters`.

### 1. Pick a chapter slug

Lowercase letters, digits and hyphens; stable. Examples: `cave`, `field`, `forest-glade`, `tower`. Do **not** use `CaveOfDoom` or `cave_of_doom`. The slug goes into `export_presets.cfg`, the `Makefile`, and the DB, so keep it short and make it final.

### 2. Create the scene file

Put the main `.tscn` in `games/<game-slug>/scenes/<chapter-slug>.tscn`. By convention the scene's root node runs `scene_loader.gd`, fed by `game_bridge.gd`, which reads `?chapter_id=N` off the iframe URL, calls `/api/chapters/{N}/scene`, and spawns the elements the DTO returns. You need not hardcode scene contents.

### 3. Add an export preset

Open `games/<game-slug>/export_presets.cfg` and append a new `[preset.N]` block. Copy an existing preset block verbatim, then edit it:

```ini
[preset.N]

name="Forest-Glade"                             ; No spaces. The Makefile only lowercases it, so it must become the slug: Forest-Glade -> forest-glade.
platform="Web"
runnable=true
custom_features="level_forest-glade"            ; matches lowercase slug — the `level_` prefix is literal, see the terminology note
exclude_filter=""                               ; three-wizards leaves this empty; dodecahedron-demo uses "*.gdextension" 
export_path="../../backend/src/main/resources/assets/levels/forest-glade/index.html"
```

The `../../` prefix is relative to the game project directory and resolves to the repo's `backend/src/main/resources/assets/levels/`. Keep the `[preset.N.options]` block identical to a working preset: `html/canvas_resize_policy=2`, `extensions_support=true` (or `false` if you do not need GDExtension at runtime), and so on.

> **Note on `exclude_filter`.** With per-game projects you no longer list other chapters' `.tscn` files. Godot's tree-shaking ships only what each preset references, and only one game's scenes live in the project. The stock projects differ: `dodecahedron-demo` excludes `*.gdextension`; `three-wizards-in-a-trenchcoat`, `chess-godot`, `connect-four-godot` and `shadows-of-the-net` leave the filter empty; `character-forge` excludes `tests/*`. Copy the filter from the project you are editing.

### 4. Register the preset in the Makefile

Open `Makefile` at the project root. Add the preset name in **two** places: the `PRESETS_<game-slug>` record, and the matching arm of the `case` block in **both** `build-godot` and `patch-godot`:

```makefile
PRESETS_three-wizards-in-a-trenchcoat = Cave Field Forest-Glade
```

```makefile
    three-wizards-in-a-trenchcoat) presets="Cave Field Forest-Glade";; \
```

`build-godot` iterates `GAME_PROJECTS`, takes each game's preset list from its `case` arm, lowercases each entry to get the output directory, and calls `godot --headless --export-release "<preset>"` from inside that game's directory. No recipe reads the `PRESETS_<dir>` variables, so editing only them exports nothing, and says nothing. A project missing from the `case` arms falls through to an empty list and is skipped the same way.

### 5. Seed the DB

Either add a Liquibase changeset (preferred for committed content), or add an insert like the one below to `scripts/seed-games.sql` and run `make seed-db`:

```sql
INSERT INTO chapters (name, slug, scene_type, sort_order, asset_url, game_id, scene_config, created_at)
VALUES ('The Forest Glade', 'forest-glade', 'forest', 3,
        '/api/assets/levels/forest-glade/index.html',
        (SELECT id FROM games WHERE slug='three-wizards'),
        '{}', NOW())
ON CONFLICT (slug) DO UPDATE SET asset_url = EXCLUDED.asset_url;
```

Then add `scene_elements` rows and, optionally, `chapter_prerequisites` to gate the chapter behind a quest. See `db/changelog/004-seed-scene-elements.xml` for the pattern.

### 6. Build

```
make build-rust         # populates games/*/bin/libhugmesh_bridge.so
make build-godot        # exports every preset in every game → classpath
make seed-db            # refreshes DB if you changed seed SQL
docker compose build api wasm-fragment && docker compose up -d api wasm-fragment
```

Rebuild `wasm-fragment` whenever `assets/levels/` changes, so the federated path picks up the new bundle. The new chapter appears on the home page, and its prerequisites decide whether it is locked.

---

## Adding a brand-new game

A *game* is one Godot project under `games/<game-slug-dir>/` that produces one or more chapter bundles. It also has one row in `games`, one row in `federation_modules`, and usually a `games-<slug>` Docker compose service if you want a separately deployable Module Federation remote instead of a host-rendered fragment.

The fast path is the **HugMesh Export** editor plugin already wired into both stock games (`addons/hugmesh_export/`).

### Option A — let the upload pipeline register everything

1. Create a new project under `games/<dir>/` (copy an existing one as a template).
2. In Godot, open **Project → Tools → HugMesh → Export…**, fill in slug / display name / description / scene type, and let the plugin produce a deploy-ready zip with `manifest.json` (schema v2) + `register.sql` + `bundle/`.
3. Send the zip to `/api/admin/games/upload` (the BFF's `GameUploadController`). The install transaction:
   - Validates the manifest, computes a content hash, and supersedes the previous upload row.
   - Moves the bundle into `app.uploads.games-dir/<slug>/<version-dir>/` and updates `games.current_version_dir`.
   - Runs `register.sql`, which inserts or updates rows in `games`, `federation_modules`, and `game_access` atomically.

When the request returns, the game is live: the host shell's federation manifest finds it on the next fetch and the home page picks it up.

### Option B — wire it manually

If you are committing a game to the repo (not uploading at runtime), do all four:

1. Add the project dir to `GAME_PROJECTS`, define `PRESETS_<dir>`, and add the project to the `case` arms of `build-godot` and `patch-godot` (these decide what gets exported). Add the slug to `GAME_SLUGS` too if per-game roles should be seeded.
2. Add a Liquibase changeset that inserts the `games` row, the `federation_modules` row, and `chapter_prerequisites`/`game_access` if needed. See `db/changelog/044-chess-game.xml` (the `games` and `chapters` rows) and `018-federation-modules.xml` (the `federation_modules` row) for the patterns. For a `kind='wasm-game'` title (an iframe straight off the wasm-fragment, no per-game Angular remote), mirror `053-chess-godot-federation.xml` / `056-shadows-of-the-net-federation.xml` instead.
3. (Optional) Add a `games-<slug>` service to `docker-compose.yml` if the game ships its own remote (a separate Angular project under `frontend/projects/games-<slug>/`). The stock games do this; one-off uploads can ride the shared `wasm-fragment` remote instead.
4. Run the section-6 build chain.

The four-place slug rule still applies: every place that names the game must use the same `<game-slug>` value in `games.slug`.

---

## Adding textures, plugins, or other assets

Assets that are not part of the Godot scene graph (custom textures applied at runtime, GDExtension plugins loaded dynamically, audio files, and so on) go in the `chapter_assets` registry. The registry gives each asset a **label**, so GDScript can look it up without hardcoding filenames.

### 1. Drop the file into the chapter's asset directory

```
backend/src/main/resources/assets/levels/<slug>/<filename>
```

Example: `backend/src/main/resources/assets/levels/forest-glade/moss-bark.png`.

### 2. Register it

```
curl -X POST http://localhost:8080/api/chapters/3/assets \
  -H 'Content-Type: application/json' \
  -d '{
    "label": "moss-bark",
    "assetType": "texture",
    "filePath": "/api/assets/levels/forest-glade/moss-bark.png",
    "contentType": "image/png",
    "sizeBytes": 248310
  }'
```

Labels must be unique within a chapter. Typical `assetType` values: `texture`, `mesh`, `audio`, `plugin`, `shader`.

To ship an asset to every install, commit a Liquibase changeset instead. See [013-seed-level-assets.xml](../backend/src/main/resources/db/changelog/013-seed-level-assets.xml) for the pattern. (The changeset FILE name predates the rename and is immutable; the table it writes is `chapter_assets` today.)

### 3. Use from GDScript

```gdscript
var api := ApiClient.new()
add_child(api)
api.resolve_asset(chapter_id, "moss-bark", func(url):
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

Or list everything registered on the chapter and filter by type:

```gdscript
api.fetch_chapter_assets(chapter_id, func(assets):
    for a in assets:
        if a["assetType"] == "texture":
            ...
)
```

---

## Mid-game chapter transitions

When something in-game (a portal, a cutscene, a quest completion) should unload the current WASM and mount the next chapter's bundle, call:

```gdscript
$GameBridge.request_chapter_transition("forest-glade", 3)
```

What happens:

1. Godot posts `window.parent.postMessage({type:"TW_CHAPTER_TRANSITION", slug:"forest-glade", chapterId:3}, "*")`.
2. Angular's `HomeComponent.onWindowMessage` (host-level; moved out of `game-canvas.component.ts` in section H) catches it and calls `GET /api/chapters/3` to read the fresh `asset_url`. If the target is federated, it mints a new `launch_uid` via `POST /api/games/{slug}/launch`. It then either swaps the iframe `src` (host fragment) or passes the new inputs to the active remote component.
3. The new chapter's Godot bundle loads cold. Its `scene_loader.gd` fetches the chapter's scene config and spawns it.

**Important:** the current WASM is fully destroyed, and anything in memory is lost. Before you call `request_chapter_transition`, persist to the DB with `PUT /api/characters/{id}` (stats, HP) or `POST /api/controls/{id}` (movement).

---

## What breaks if you skip a step

| Symptom | Cause |
|---|---|
| Iframe 404 on `index.html` | `chapters.asset_url` slug differs from the classpath directory, or `wasm-fragment` not rebuilt after `make build-godot` |
| Blank iframe, no error | Preset name in the Makefile's `case` arm doesn't match `export_presets.cfg`, the project is missing from the `case` arms (nothing exported), or the `GAME_PROJECTS` row points at a nonexistent directory |
| Wrong scene loads | `scene_elements.chapter_id` FK is stale — check it matches the new `chapters.id` |
| `resolve_asset` returns 404 | `label` isn't in `chapter_assets` or `chapter_id` wrong |
| `postMessage` transition doesn't fire | Payload shape wrong — must be literally `TW_CHAPTER_TRANSITION` and include `slug` + `chapterId` |
| `ERR_BLOCKED_BY_RESPONSE` on federated WASM iframe | `wasm-fragment/nginx.conf` missing `Cross-Origin-Resource-Policy: cross-origin`, or host nginx not stripping duplicate COOP/COEP from the upstream |
| Federation manifest shows the game but iframe stays blank | `federation_modules.remote_entry_url` points at a host that isn't reachable from the browser, or the game ships its own remote but `games-<slug>` compose service isn't up |
| `ERROR: Parent node is busy setting up children, add_child() failed` | Calling `add_child` (or `get_tree().root.add_child`) directly from `_ready()` or `_process()` while siblings are still `_ready`-ing. Use `node.add_child.call_deferred(child)` |
| Loading dots animate forever, prefetch logged success | `JavaScriptBridge.eval(...) != true` Variant compare is unreliable. Use `bool(JavaScriptBridge.eval("!!window.foo"))` to coerce through a real JS boolean |
| Iframe shows old WASM after `make build-godot` | Browser cached the iframe HTML. The Angular remote already cache-busts with `&t=...`; if the timestamp isn't in the URL, it's the wrong code path |
| GDExtension symbol fails to load in editor | `make build-rust` was skipped or the per-game `bin/libhugmesh_bridge.so` is stale — the Makefile copies the freshly-built `.so` into every `games/*/bin/` |

### Why so many `call_deferred` calls

Godot 4 enforces a "tree is busy" guard while `_ready()` is propagating. A
script that adds children, or attaches nodes to `get_tree().root`, from inside
`_ready` / `_process` / `_input` can hit it. The project's convention:

- `_ready()` builds detached subtrees with `Node.new()` and a plain `add_child`
  on those new nodes (safe: the new node is not in the tree yet).
- The final attachment to a node that **is** in the tree (the parent or
  `get_tree().root`) is always `call_deferred`.
- `_apply()` and any spawn handler called from polling code (`_process`) are
  deferred in the same way. See `scene_loader._process` for the pattern.

When you write a new spawn handler, copy that pattern. Use a plain `add_child`
on fresh nodes you just created, and a deferred `add_child` for anything that
attaches to the live tree.

---

## Compile-time checklist (run before pushing)

```
make build-rust         # GDExtension compiles, .so copied into every games/*/bin/
make build-godot        # every preset in every game exports cleanly
make build-backend      # Liquibase + R2DBC bindings match the entity shape
make build-frontend     # TypeScript models + templates typecheck
make gen-api            # optional, refreshes Angular TS client from /v3/api-docs
```

If any step fails, do not force-merge. The mismatch will show up later as a runtime 404 or a silent empty scene.
