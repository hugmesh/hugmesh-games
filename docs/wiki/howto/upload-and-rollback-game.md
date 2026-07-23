# How to hot-upload and roll back a game bundle

The portal's hot-upload pipeline lets an admin ship a new game (or a new version of an existing one) without a host redeploy. Rollback to a prior version is a single click.

## Prerequisites

- `ROLE_ADMIN` in Casdoor.
- A HugMesh Export zip from the game developer (see [Godot developer guide](../guides/godot-developer.md), Step 5b) or from your own Godot editor session.

The zip must contain:

- `manifest.json` — schema v2, with `game`, `chapters`, `federation`, `access` blocks.
- `register.sql` — parameterised SQL the BFF runs in one R2DBC transaction.
- `bundle/` — `index.html`, `index.js`, `index.wasm`, `index.pck`, plus any per-chapter assets.

## 1. Upload

Visit `/admin/games`. Drag the zip into the *Upload game bundle* card.

The install transaction:

1. Stages the zip to a random staging dir.
2. Validates manifest schema v2, slug regex `[a-z0-9-]+`, zip-slip-safe paths, entry count (≤500), total bytes (≤200 MB).
3. SHA-256 hashes the bundle to derive the `version_dir` name.
4. Atomic-moves staging → `<gamesDir>/<slug>/<sha-prefix>`.
5. Inside one R2DBC transaction:
   - Inserts a `game_uploads` row.
   - Runs the bundle's `register.sql`. It typically `INSERT … ON CONFLICT (slug) DO UPDATE`s `games`, `chapters`, `federation_modules`, and `INSERT … ON CONFLICT DO NOTHING`s `game_access (scope='all')`.
   - UPDATEs `games.current_version_dir`.
   - Marks prior `game_uploads` rows for the same slug `superseded_at = NOW()`.
6. Returns `{slug, contentHash, versionDir, uploadedAt}`.

If `register.sql` throws inside the transactional block, Reactor's `onError` unwinds: the R2DBC transaction rolls back **and** the on-disk move is reversed. The prior `games.current_version_dir` is unchanged and the active bundle keeps serving — see scenario 4 in [05 — API reference](../architecture/05-api-reference.md#recurring-non-happy-paths).

## 2. Verify

The host shell discovers the new game on the next federation manifest fetch (within 60 s). For an in-place version replacement, the new `current_version_dir` takes effect immediately for *new* launches; in-flight launches keep reading from their pinned `version_dir` for the rest of their 15-minute TTL — see [decision 005](../decisions/005-launch-uid-versioning.md).

```bash
curl /api/admin/games/<slug>/uploads -H "Authorization: Bearer $ADMIN_JWT" | jq
```

Latest row with `supersededAt = null` is the active version.

## 3. Roll back

Two options.

### Option A — admin UI

`/admin/games` → expand the rollback history under the affected game → *Set as current* on a prior row.

### Option B — direct SQL

```sql
-- Pin to a specific version_dir.
UPDATE games SET current_version_dir = '<old-sha-prefix>' WHERE slug = '<slug>';

-- And mark the row that was current as superseded:
UPDATE game_uploads
SET superseded_at = NOW()
WHERE slug = '<slug>' AND version_dir = '<new-sha-prefix>';

-- And clear superseded on the row you're pinning to:
UPDATE game_uploads
SET superseded_at = NULL
WHERE slug = '<slug>' AND version_dir = '<old-sha-prefix>';
```

## ⚠ Don't manually delete version directories

`GameUploadPruner` (every hour) keeps the latest `app.uploads.versions-to-keep` (default 3) and removes older `version_dir` trees. **It never deletes the row marked `supersededAt=NULL`.** If you delete a directory while it's still pinned, asset serving 404s for in-flight launches.

If you need to free disk space immediately, lower `app.uploads.versions-to-keep` and restart the BFF; the pruner runs at startup.

## When upload fails mid-flight

Symptoms + recovery: see [maintainer runbook 5](../guides/maintainer-runbook.md).

The install transaction is atomic in Postgres but **NOT** atomic vs the on-disk move. Recovery:

- `GET /api/admin/games/{slug}/uploads`. Latest row `supersededAt=NULL` is the active version.
- If on-disk dir exists but no row, manually delete the dir; the next upload will recreate.
- If row exists but dir is missing, restore from snapshot or pin to a previous version.

## See also

- [Decision 005 — Launch-UID + version pinning](../decisions/005-launch-uid-versioning.md)
- [Administration manual](../guides/administration.md)
- [Godot developer guide](../guides/godot-developer.md)
