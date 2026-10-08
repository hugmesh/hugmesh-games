# 005 — Launch-UID + version-pinning for hot uploads

**Status:** Adopted.

## Decision

Every game launch mints a 256-bit URL-safe `launch_uid` (`Crypto.mintUid()`, base64url of a SHA3-256 digest). The uid records the current `games.current_version_dir` at mint time. The launch row holds that `version_dir` for its 15-minute TTL. An in-flight launch keeps reading from the install pinned at mint time, even if a hot upload replaces the bundle mid-session.

## Why

The hot-upload pipeline works like this: an admin uploads a zip, the BFF atomically moves it to `<gamesDir>/<slug>/<sha-prefix>`, then updates `games.current_version_dir`. The prefix is the first 16 characters of the content hash. S3 storage uploads the files instead of moving them. None of this may pull the rug from under a player who is mid-game. Without pinning, a chapter transition mid-session would resolve `current_version_dir` against the *new* upload. Assets, scene DTOs and even WASM heap layouts could shift between two requests in one playthrough.

Filesystem symlinks alone cannot hold the contract. For any uploaded game, the URL resolves to a file path inside the BFF (a DB lookup against `game_launches`). So the pin lives in the row: "your session reads from the version that was current when you launched."

## How to apply

- **`POST /api/games/{slug}/launch`** copies `games.current_version_dir` into `game_launches.version_dir`. The response body is `{uid, expiresAt, gameSlug}` and does not carry `versionDir`; the pin stays server-side. The game iframe URL carries `?launch_uid=<uid>`. The remote loads assets from `/api/assets/levels/<slug>/<uid>/<file>`.
- **`AssetController.getLevelAssetByUid`** looks up a launch row that is unexpired, unterminated and matches the `game_slug` in the URL. Any mismatch is a 404. It prefers the row's `version_dir` over `current_version_dir`. It falls back to current/classpath only when the row's `version_dir` is null (legacy launch or classpath-only game). The older path without a uid (`/api/assets/levels/<slug>/<file>`) still serves the current version. Set `app.assets.require-launch-uid=true` to make that path return 403.
- **`GameUploadService`** sets `superseded_at = NOW()` on prior `game_uploads` rows after a successful upload. Stale launches keep their `version_dir`, and the files stay on disk until `GameUploadPruner` runs.
- **`GameUploadPruner`** runs hourly (first run after 10 minutes). It keeps the newest `app.uploads.versions-to-keep` uploads per slug (default 3, ordered by `uploaded_at`) and deletes the storage artefact of the rest. The `game_uploads` rows stay. It does not check for live launches.
- **`GameLaunchSweeper`** runs every 5 minutes and stamps `terminated_at` on expired `game_launches` rows, so the table does not grow without bound. The asset query already rejects expired rows by `expires_at`. The sweeper does not enforce the TTL.

## Trade-offs accepted

- **Disk pressure scales with concurrent old launches × upload frequency.** The pruner enforces a hard cap. Tune it with `app.uploads.versions-to-keep`.
- **A player whose session outlasts 15 minutes, a hot upload and a chapter transition sees the new version.** The TTL is a deliberate cap on how long we honor the pin.
- **Two-store atomicity gap.** The install transaction is atomic in Postgres but NOT atomic against the on-disk move. The recovery procedure is in the [maintainer runbook](../guides/maintainer-runbook.md), Runbook 5.

## Reference

- [04 — Data model](../architecture/04-data-model.md#federation-access-and-uploads)
- Plan section H in [`docs/PLAN-module-federation-and-native.md`](../../PLAN-module-federation-and-native.md).
