# Godot Developer Guide

This guide is for anyone working inside `godot-project/` and needs their work to **compile into the shapes the rest of the stack expects**: slugs, export paths, asset registrations, and scene DTOs. Follow it and the API, Angular, and DB will pick up your level / textures / plugins without any other code changes.

---

## The contract in one page

Every level must have a **slug** that appears in exactly the same lowercase, hyphen-free form in **four** places:

| Place | Example |
|---|---|
| `levels.slug` DB column | `cave` |
| `godot-project/export_presets.cfg` → `export_path` directory | `.../assets/levels/cave/index.html` |
| `Makefile` `GODOT_LEVELS` entry (preset name, capitalised) | `Cave` |
| `levels.asset_url` DB column | `/api/assets/levels/cave/index.html` |

If any of those four drift, the level 404s in the iframe or loads the wrong bundle. **Never use spaces or uppercase in the slug itself** — the Makefile lowercases the preset name with `tr '[:upper:]' '[:lower:]'`, so `Cave` (preset) → `cave` (directory).

Supporting contracts:

- Scene DTOs returned from `GET /api/levels/{id}/scene` must have `elementType` values that `scene_loader.gd` knows (`world_env`, `sky_env`, `ambient_light`, `sun_light`, `torch`, `fill_light`, `fairy`, `stalagmite`, `stalactite`, `tree`, `cottage`). Add a new value → add a handler in `scene_loader._spawn()`.
- Level-transition `postMessage` payload must literally be `{type: "TW_LEVEL_TRANSITION", slug, levelId}` — the Angular host matches on the literal string.
- Assets referenced from a scene element's `properties.textureUrl` must be either a classpath file under `assets/levels/{slug}/` or registered in `level_assets` with a `filePath` that `AssetController` can resolve.

---

## Running the Godot editor against the live stack

The project runs in two modes and `scripts/api_client.gd` auto-selects between them:

```
Editor / native run  → fetch('http://localhost:8080/api/…')
Web export (iframe)  → fetch(same-origin '/api/…')
```

To play the full game from inside the editor:

1. Start the backend stack once:
   ```
   docker compose up -d            # or: make dev-backend
   ```
2. In Godot, press **Play**. The scene_loader polls `/api/levels/{id}/scene` and populates fairies/lights/geometry exactly as the web build does.
3. User-added assets registered under `level_assets` are reachable by label via `ApiClient.resolve_asset(level_id, "oak-bark", cb)`.

If the editor can't reach the API, `_base_url()` in `api_client.gd` logs an HTTP error to the output panel. No silent fallbacks.

---

## Adding a new level

A level is **one compiled WASM bundle**. Each level lives in its own subdirectory under `backend/src/main/resources/assets/levels/<slug>/` and has its own row in `levels`.

### 1. Pick a slug

Lowercase, hyphen-free, stable. Examples: `cave`, `field`, `forest-glade`, `tower`. Do **not** use `CaveOfDoom` or `cave_of_doom` — keep it kebab-case and lowercase. The slug will be written into `export_presets.cfg`, `Makefile`, and the DB, so make it short and final.

### 2. Create the scene file

Put the main `.tscn` in `godot-project/scenes/<slug>.tscn`. The convention is that the scene's root node runs `scene_loader.gd` which reads `?level_id=N` from the iframe URL, calls `/api/levels/{N}/scene`, and spawns the elements returned in the DTO. You don't need to hardcode scene contents.

### 3. Add an export preset

Open `godot-project/export_presets.cfg` and append a new `[preset.N]` block. Copy the `[preset.0]` (Cave) block verbatim, then edit these three fields:

```ini
[preset.N]

name="ForestGlade"                              ; CamelCase, no spaces. Used by Makefile.
platform="Web"
runnable=true
custom_features="level_forest-glade"            ; matches lowercase slug
exclude_filter="*.gdextension,scenes/cave.tscn,scenes/field.tscn,...others"
export_path="../../backend/src/main/resources/assets/levels/forest-glade/index.html"
```

**`exclude_filter` matters.** List every other level's `.tscn` so each bundle only ships its own scene + shared dependencies. Keep `*.gdextension` in the filter — GDExtension is handled via the separate `build-rust` step.

Keep the `[preset.N.options]` block identical to the Cave one — `html/canvas_resize_policy=2`, `extensions_support=true`, etc.

### 4. Register the slug in the Makefile

Open `Makefile` at the project root and add your preset name to `GODOT_LEVELS`:

```makefile
GODOT_LEVELS = Cave Field ForestGlade
```

The `build-godot` target iterates over this list, lowercases each entry to derive the output directory, and calls `godot --headless --export-release "<preset>"` for each one.

### 5. Seed the DB

Either add a Liquibase changeset (preferred for committed content) or run `make seed-db` after an SQL insert like:

```sql
INSERT INTO levels (name, slug, scene_type, sort_order, asset_url, game_id, scene_config, created_at)
VALUES ('The Forest Glade', 'forest-glade', 'forest', 3,
        '/api/assets/levels/forest-glade/index.html', 1, '{}', NOW())
ON CONFLICT (slug) DO UPDATE SET asset_url = EXCLUDED.asset_url;
```

Then add scene_elements:

```sql
INSERT INTO scene_elements (level_id, element_type, name, position_x, position_y, position_z, properties, sort_order)
VALUES
  ((SELECT id FROM levels WHERE slug='forest-glade'),
   'sky_env', 'GladeSky', 0, 0, 0,
   '{"skyTopR":0.2,"skyTopG":0.5,"skyTopB":0.85,"ambientEnergy":1.2}', 1);
```

And optionally prerequisites if the level should be gated:

```sql
INSERT INTO level_prerequisites (level_id, required_quest_id)
SELECT (SELECT id FROM levels WHERE slug='forest-glade'),
       (SELECT id FROM quests WHERE name='The Cottage Secret');
```

### 6. Build

```
make build-godot        # exports every preset → classpath
make seed-db            # refreshes DB if you used scripts/seed-games.sql
docker compose build api && docker compose up -d api
```

The new level appears on the home page with its lock state driven by its prerequisites.

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
2. Angular's `game-canvas.component.ts` catches it, hits `GET /api/levels/3` to read the fresh `asset_url`, and swaps the iframe `src`.
3. The new level's Godot bundle loads cold; its `scene_loader.gd` fetches `/api/levels/3/scene` and spawns.

**Important:** the current WASM is fully destroyed. Anything in memory is lost. Persist to the DB via `PUT /api/characters/{id}` (stats, HP) or `POST /api/controls/{id}` (movement) before calling `request_level_transition`.

---

## What breaks if you skip a step

| Symptom | Cause |
|---|---|
| Iframe 404 on `index.html` | `levels.asset_url` slug differs from the classpath directory |
| Blank iframe, no error | Export preset name in `GODOT_LEVELS` doesn't match `export_presets.cfg` |
| Wrong scene loads | `scene_elements.level_id` FK is stale — check it matches the new `levels.id` |
| `resolve_asset` returns 404 | `label` isn't in `level_assets` or `level_id` wrong |
| `postMessage` transition doesn't fire | payload shape wrong — must be literally `TW_LEVEL_TRANSITION` and include `slug` + `levelId` |
| Exported bundle includes other levels' scenes | `exclude_filter` in your preset is incomplete |
| `ERROR: Parent node is busy setting up children, add_child() failed` | Calling `add_child` (or `get_tree().root.add_child`) directly from `_ready()` or `_process()` while siblings are still `_ready`-ing. Use `node.add_child.call_deferred(child)`. |
| Loading dots animate forever, prefetch logged success | `JavaScriptBridge.eval(...) != true` Variant compare is unreliable. Use `bool(JavaScriptBridge.eval("!!window.foo"))` to coerce through a real JS boolean. |
| Iframe shows old WASM after `make build-godot` | Browser cached the iframe HTML. The Angular `game-canvas` already cache-busts with `&t=...`; if the timestamp isn't in the URL, it's the wrong code path. |

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
make build-godot        # every preset exports cleanly
make build-backend      # Liquibase + R2DBC bindings match the entity shape
make build-frontend     # TypeScript models + templates typecheck
make gen-api            # optional, refreshes Angular TS client from /v3/api-docs
```

If any step fails, don't force-merge — the mismatch will show up as a runtime 404 or a silent empty scene later.
