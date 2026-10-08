# How to add a new game

Two paths. Pick the one that matches whether you're shipping the game in-repo or hot-uploading at runtime.

## Path A — hot-upload at runtime

For a game that will be uploaded by an admin from the portal UI without a host redeploy.

### 1. In the Godot editor

*Project → Tools → HugMesh → Export to HugMesh…*

The `hugmesh_export` addon lives in `extensions/hugmesh-bridge/addons/hugmesh_export/`. `make sync-bridge-addon` copies it into every project in `GAME_PROJECTS`. A project outside that list needs the folder copied in by hand.

Fill in:

- **Export preset** — a Web preset of the project.
- **Game slug** — lowercase, dash-separated. Stable forever.
- **Display name** — shown on the home page card.
- **Description** — long form for the card.
- **scene_type tag** — defaults to the slug. The host maps `cave`, `field` and `dodecahedron` to federated remotes through `SCENE_TYPE_TO_GAME_SLUG` in `home.component.ts`. Any other scene type launches from the chapter's `asset_url` in an iframe, unless the game has a `wasm-game` federation row.
- **Chrome-less** — tick it for a pure visual demo with no HUD or controls overlay.
- **Output zip path** — defaults to `user://hugmesh-bundle.zip`.

Click **Export bundle**. The plugin writes the zip with `manifest.json` (schema v2), `register.sql`, `bundle/` and a `federation/` stub. The BFF ignores the stub.

The dialog has no field for the federation `remote_entry_url`. It always writes `/_remotes/games-<slug>/remoteEntry.json`, kind `remote-game`.

> Check the generated `register.sql` before you upload it. The plugin writes `INSERT INTO levels … '{}'::jsonb`. Changeset 028 renamed `levels` to `chapters`, and `chapters.scene_config` is `TEXT`, so a `jsonb` cast aborts the upload. The hand-written [`games/character-forge/register/register.sql`](../../../games/character-forge/register/register.sql) uses `chapters` and a plain `'{}'`. This page has not run the plugin's output against a live stack.

### 2. Upload via the admin UI

Visit `/admin/games` on the live stack as a user with `ROLE_ADMIN`. Drag the zip onto the *Upload a game bundle* card and click *Install bundle*.

The BFF's install transaction:

1. Validates manifest schema v2, the slug regex, zip-slip-safe paths, entry count (≤500) and zip size (≤200 MB).
2. SHA-256 hashes the bundle. The first 16 hex characters name the `version_dir`.
3. Atomic-moves staging → `<gamesDir>/<slug>/<sha16>`.
4. Runs `register.sql` inside one R2DBC transaction. An allowlist limits it to inserts into `games`, `levels`, `chapters`, `federation_modules`, `game_access` and `game_event_support`, plus `UPDATE games SET features`.
5. UPDATEs `games.current_version_dir`.
6. Marks prior `game_uploads` rows `superseded_at = NOW()`.

Full detail: [upload and roll back a game](upload-and-rollback-game.md).

### 3. Verify

The host shell reads the federation manifest once per page load. Reload the page; no host redeploy is needed.

```bash
curl http://localhost:8080/api/federation/manifest | jq '.remotes[] | select(.slug == "<slug>")'
curl -H "Authorization: Bearer $JWT" http://localhost:8080/api/games/available/<characterId> \
  | jq '.[] | select(.slug == "<slug>")'
```

## Path B — commit the game in-repo

For a game you want to ship in the repository (visible without an admin upload).

### 1. Drop the project under `games/`

```bash
mkdir games/<game-dir>
# Copy hugmesh_bridge.gdextension, project.godot, export_presets.cfg and the
# scenes and scripts from a sibling such as games/three-wizards-in-a-trenchcoat/.
```

`addons/` is not in git. `make sync-bridge-addon` copies `hugmesh_bridge` and `hugmesh_export` into every project listed in `GAME_PROJECTS`.

`<game-dir>` can be more descriptive than the database slug. The export preset name maps one to the other.

### 2. Wire the Makefile

In the root [`Makefile`](../../../Makefile):

- Add the directory to `GAME_PROJECTS`.
- Add a `<dir>) presets="<Preset names>";;` arm to the `case` in **both** `build-godot` and `patch-godot`. Those recipes hard-code their preset lists.
- Optionally define `PRESETS_<dir>`, but nothing reads these variables today.
- Add the slug to `GAME_SLUGS` if `make seed-roles` should create its Casdoor roles.

The names must match the `[preset.N]` blocks in `export_presets.cfg`. `make build-godot` writes each preset's bundle to `backend/src/main/resources/assets/levels/<preset-name-lowercased>/`, so the lowercased preset name is the slug in the chapter's `asset_url`.

### 3. Add a Liquibase changeset

Use [`017-snapdragon-catalog.xml`](../../../backend/src/main/resources/db/changelog/017-snapdragon-catalog.xml) as the template. For a `kind='wasm-game'` title launched straight off the wasm-fragment (no per-game Angular remote), copy one of the four-row seeds instead: [`053-chess-godot-federation.xml`](../../../backend/src/main/resources/db/changelog/053-chess-godot-federation.xml) (Godot + Rust core) or [`056-shadows-of-the-net-federation.xml`](../../../backend/src/main/resources/db/changelog/056-shadows-of-the-net-federation.xml) (pure GDScript, plus a `game_event_support` opt-in for a host-authoritative `world/state` relay). Insert into:

- `games` — slug, display name, description, sort order.
- `chapters` — one row per export preset.
- `federation_modules` — the per-game remote (or point at `wasm-fragment` if no per-game frontend).
- `game_access` — at least one row, typically `(scope='all', value=NULL)` for public.
- `input_event_types` / `game_event_support` — only if the game relays a new `(kind, type)` over `/api/input` (see changesets 050/052/056).

Append an `<include … relativeToChangelogFile="false"/>` to [`db.changelog-master.xml`](../../../backend/src/main/resources/db/changelog/db.changelog-master.xml).

### 4. Optionally add a federated game remote

If the game has its own HUD / overlay component (not chrome-less), follow [howto/add-federated-route.md](add-federated-route.md) for the remote. Then add the scene type to `SCENE_TYPE_TO_GAME_SLUG` and the game slug to `FEDERATED_GAME_SLUGS` in [`home.component.ts`](../../../frontend/src/app/home/home.component.ts).

### 5. Build and bring up

```bash
make build
docker compose up -d --force-recreate api wasm-fragment frontend
```

## See also

- [Godot developer guide](../guides/godot-developer.md) — full step-by-step including HostBridge wiring and slug contract.
- [`docs/GODOT_DEVELOPER_GUIDE.md`](../../GODOT_DEVELOPER_GUIDE.md) — the canonical contract reference.
- [07 — Extending the portal](../architecture/07-extending.md).
