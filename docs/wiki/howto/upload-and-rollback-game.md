# How to hot-upload and roll back a game bundle

Hot-upload lets you ship a new game, or a new version of one, without a host redeploy. Rollback is a database edit. The admin console has no rollback control.

## Prerequisites

- `ROLE_ADMIN` in Casdoor. A `ROLE_PUBLISHER` may also upload, but only for a slug in their `publisher_game_scopes`, and a new slug on a brand-scoped deployment is stamped with the uploader's brand.
- A HugMesh Export zip from the game developer (see [Godot developer guide](../guides/godot-developer.md), Step 5b) or from your own Godot editor session.

The BFF reads three things from the zip:

- `manifest.json` — schema v2, with `game`, `levels`, `federation` and `access` blocks.
- `register.sql` — the SQL the BFF runs inside the install transaction.
- `bundle/` — `index.html`, `index.js`, `index.wasm`, `index.pck`, plus any per-chapter assets.

The export plugin also writes `federation/`. The BFF ignores it.

## 1. Upload

Visit `/admin/games`. Drag the zip onto the *Upload a game bundle* card, then click *Install bundle*.

The install:

1. Unpacks the zip into a staging directory.
2. Validates it: zip size ≤ 200 MB, ≤ 500 entries, no zip-slip paths (`..`, leading `/`, `\`), `manifest.json` present, `bundle/` not empty, `schema_version` 2, and a slug that matches `^[a-z0-9][a-z0-9-]{0,63}$`.
3. SHA-256 hashes the `bundle/` tree. The first 16 hex characters name the version directory.
4. Checks that the caller may upload this slug (publisher scope, brand).
5. Moves the staging directory to `<gamesDir>/<slug>/<sha16>`. The default storage is the filesystem (`app.uploads.storage`); `s3` is the other choice. If that directory already exists, the BFF reuses it.
6. Inside one R2DBC transaction:
   - Inserts a `game_uploads` row.
   - Runs `register.sql`. `RegisterSqlPolicy` vets it first: only `INSERT` into `games`, `levels`, `chapters`, `federation_modules`, `game_access` and `game_event_support`, plus `UPDATE games SET features`. Anything else is a 400 and the transaction rolls back. The statements run under a low-privilege Postgres role (`SET LOCAL ROLE`). The `games.domain` column is off limits.
   - Sets `games.current_version_dir` to the new locator.
   - Stamps the uploader's brand on the `games` row, only if the game is new.
   - Marks every earlier `game_uploads` row for the slug `superseded_at = NOW()`.
7. Returns `{slug, contentHash, versionDir, uploadedAt}`.

If `register.sql` throws, the transaction rolls back and the prior `games.current_version_dir` is unchanged. The active bundle keeps serving. See scenario 4 in [05 — API reference](../architecture/05-api-reference.md#recurring-non-happy-paths). The code does not remove the already-moved version directory. See *When upload fails mid-flight* below.

## 2. Verify

The host shell reads the federation manifest once per page load, so reload the page to see a new game. A replaced version takes effect at once for *new* launches. In-flight launches keep reading their pinned version directory for the rest of their 15-minute TTL. See [decision 005](../decisions/005-launch-uid-versioning.md).

```bash
curl http://localhost:8080/api/admin/games/<slug>/uploads \
  -H "Authorization: Bearer $ADMIN_JWT" | jq
```

The latest row with `supersededAt = null` is the active version. Its `versionDir` is a locator, such as `file:/var/hugmesh/games/<slug>/<sha16>`.

## 3. Roll back

Two options.

### Option A — upload the old zip again

If you still have the old zip, upload it again at `/admin/games`. An identical bundle hashes to the same version directory, so the BFF reuses it if it is still on disk and otherwise recreates it. The upload adds a new `game_uploads` row, re-points `games.current_version_dir` and supersedes the rest.

The *Upload history* table on `/admin/games` is read-only (type a slug, click *Load*). It shows `uploaded_at`, the hash, the uploader and whether a row is active. It has no *Set as current* button.

### Option B — direct SQL

Run it in `psql` (`docker compose exec postgres psql -U threewizards -d threewizards`). `version_dir` and `current_version_dir` hold the full locator, not a bare hash prefix. Copy the locator from the row you want back.

```sql
-- Find the version to restore.
SELECT id, version_dir, uploaded_at, superseded_at
FROM game_uploads WHERE slug = '<slug>' ORDER BY uploaded_at DESC;

BEGIN;
-- Pin the game to that locator.
UPDATE games SET current_version_dir = '<old-locator>' WHERE slug = '<slug>';

-- Mark the row that was current as superseded.
UPDATE game_uploads SET superseded_at = NOW()
WHERE slug = '<slug>' AND superseded_at IS NULL;

-- Clear superseded on the row you are pinning to.
UPDATE game_uploads SET superseded_at = NULL WHERE id = <old-id>;
COMMIT;
```

## ⚠ Don't manually delete version directories

`GameUploadPruner` runs every hour. The first run is 10 minutes after the BFF starts. For each slug it sorts `game_uploads` rows by `uploaded_at` and deletes the storage of every row past the newest `app.uploads.versions-to-keep` (default 3, env `APP_UPLOADS_VERSIONS_TO_KEEP`). It keeps the database rows. **It does not check which row is current.** Several rows can name one directory, because a re-upload of the same zip reuses it, and the pruner deletes a directory when any excess row names it.

So after a rollback to a version older than the newest three, the next sweep can delete the directory you just pinned. Asset serving then 404s for new and in-flight launches. Before you roll back that far, raise `APP_UPLOADS_VERSIONS_TO_KEEP` and restart the BFF. Check afterwards that the pinned `version_dir` still exists on disk.

To free disk space, lower the setting and restart. The first sweep runs 10 minutes later.

## When upload fails mid-flight

Symptoms and recovery: see [maintainer runbook 5](../guides/maintainer-runbook.md).

The install is atomic in Postgres. It is **not** atomic against the on-disk move, which happens first. Recovery:

- `GET /api/admin/games/{slug}/uploads`. The latest row with `supersededAt=NULL` is the active version.
- If the directory exists but no row names it, delete the directory. A later upload recreates it.
- If a row exists but its directory is missing, restore from snapshot or pin to a previous version.

## See also

- [Decision 005 — Launch-UID + version pinning](../decisions/005-launch-uid-versioning.md)
- [Administration manual](../guides/administration.md)
- [Godot developer guide](../guides/godot-developer.md)
