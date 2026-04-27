# How to add a new game

Two paths. Pick the one that matches whether you're shipping the game in-repo or hot-uploading at runtime.

## Path A — hot-upload at runtime

For a game that will be uploaded by an admin from the portal UI without a host redeploy.

### 1. In the Godot editor

*Project → Tools → Snapdragon → Export…*

Fill in:

- **Slug** — lowercase, kebab-case. Stable forever.
- **Display name** — shown on the home page card.
- **Description** — long form for the card.
- **Scene type** — drives `SCENE_TYPE_TO_GAME_SLUG` in the host shell (sets which renderer mounts).
- **Federation `remote_entry_url`** — defaults to `/_remotes/games-<slug>/remoteEntry.json`. If you don't plan to ship a per-game frontend remote, change it to `/_remotes/wasm-fragment/remoteEntry.json` so the host loads the bundle through the shared fragment.

Click **Export**. The plugin writes a deploy-ready zip to your chosen path containing `manifest.json` (schema v2), `register.sql`, and `bundle/`.

### 2. Upload via the admin UI

Visit `/admin/games` on the live stack as a user with `ROLE_ADMIN`. Drag the zip into the *Upload game bundle* card.

The BFF's install transaction:

1. Validates manifest schema v2, slug regex, zip-slip-safe paths, entry count (≤500), total bytes (≤200 MB).
2. SHA-256 hashes the bundle to derive the `version_dir`.
3. Atomic-moves staging → `<gamesDir>/<slug>/<sha-prefix>`.
4. Runs `register.sql` inside one R2DBC transaction — inserts into `games`, `chapters`, `federation_modules`, `game_access` (`scope='all'`).
5. UPDATEs `games.current_version_dir`.
6. Marks prior `game_uploads` rows `superseded_at = NOW()`.

### 3. Verify

The host shell discovers the new game on the next federation manifest fetch (within 60 s). No host redeploy required.

```bash
curl /api/federation/manifest | jq '.remotes[] | select(.slug == "<slug>")'
curl /api/games/available/{characterId} | jq '.[] | select(.slug == "<slug>")'
```

## Path B — commit the game in-repo

For a game you want to ship in the repository (visible without an admin upload).

### 1. Drop the project under `games/`

```bash
mkdir games/<game-dir>
# Copy snapdragon_bridge.gdextension and addons/snapdragon_export/
# from games/three-wizards-in-a-trenchcoat/
```

`<game-dir>` can be more descriptive than the database slug — the Makefile maps one to the other.

### 2. Wire the Makefile

In the root [`Makefile`](../../../Makefile):

- Add the directory to `GAME_PROJECTS`.
- Define `PRESETS_<dir>` with the preset names (matching `[preset.N]` blocks in `export_presets.cfg`).

### 3. Add a Liquibase changeset

Use [`017-snapdragon-catalog.xml`](../../../backend/src/main/resources/db/changelog/017-snapdragon-catalog.xml) as the template. Insert into:

- `games` — slug, display name, description, sort order.
- `chapters` — one row per export preset.
- `federation_modules` — the per-game remote (or point at `wasm-fragment` if no per-game frontend).
- `game_access` — at least one row, typically `(scope='all', value=NULL)` for public.

Append to [`db.changelog-master.xml`](../../../backend/src/main/resources/db/changelog/db.changelog-master.xml).

### 4. Optionally add a federated game remote

If the game has its own HUD / overlay component (not chrome-less), follow [howto/add-federated-route.md](add-federated-route.md) for the remote, then wire it into the host's home-component switch.

### 5. Build and bring up

```bash
make build
docker compose up -d --force-recreate api wasm-fragment frontend
```

## See also

- [Godot developer guide](../guides/godot-developer.md) — full step-by-step including HostBridge wiring and slug contract.
- [`docs/GODOT_DEVELOPER_GUIDE.md`](../../GODOT_DEVELOPER_GUIDE.md) — the canonical contract reference.
- [07 — Extending the portal](../architecture/07-extending.md).
