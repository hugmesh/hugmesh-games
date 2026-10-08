# 005 — Launch-UID + version-pinning for hot uploads

**Status:** Adopted.

## Decision

Every game launch mints a 256-bit URL-safe `launch_uid` (`Crypto.mintUid()`, base64url of a SHA3-256 digest) that captures the current `games.current_version_dir` at mint time. The launch row holds that `version_dir` for its 15-minute TTL. An in-flight launch keeps reading from the install pinned at mint time, even if a hot upload supersedes the bundle mid-session.

## Why

The hot-upload pipeline (admin uploads a zip → BFF atomically moves it to `<gamesDir>/<slug>/<sha-prefix>` (the first 16 characters of the content hash; S3 storage uploads the files instead of moving them) → updates `games.current_version_dir`) must not pull the rug out from under a player who's mid-game. Without pinning, a chapter transition mid-session would resolve `current_version_dir` against the *new* upload — assets, scene DTOs, even WASM heap layouts could shift between two requests in the same playthrough.

We can't rely on filesystem symlinks alone because the resolution from URL to file path goes through the BFF (DB lookup against `game_launches` → resolves filesystem path) for any uploaded game. Pinning at the row level keeps the contract: "your session reads from the version that was current when you launched."

## How to apply

- **`POST /api/games/{slug}/launch`** snapshots `games.current_version_dir` into `game_launches.version_dir` at mint time. The response body is `{uid, expiresAt, gameSlug}`; it does not carry `versionDir`. The pin stays server-side. The game iframe URL carries `?launch_uid=<uid>`, and the remote loads assets from `/api/assets/levels/<slug>/<uid>/<file>`.
- **`AssetController.getLevelAssetByUid`** looks up an unexpired, unterminated launch row whose `game_slug` matches the URL; any mismatch is a 404. It prefers the row's `version_dir` over `current_version_dir`. It falls back to current/classpath only when the row's `version_dir` is null (legacy launch or classpath-only game). The older path without a uid (`/api/assets/levels/<slug>/<file>`) still serves the current version; setting `app.assets.require-launch-uid=true` makes it return 403.
- **`GameUploadService`** marks prior `game_uploads` rows `superseded_at = NOW()` on a successful new upload. The `version_dir` columns on stale launches stay populated; their files stay on disk until `GameUploadPruner` runs.
- **`GameUploadPruner`** runs hourly (first run after 10 minutes). It keeps the newest `app.uploads.versions-to-keep` uploads per slug (default 3, ordered by `uploaded_at`) and deletes the storage artefact of the rest. The `game_uploads` rows stay. It does not check for live launches.
- **`GameLaunchSweeper`** runs every 5 minutes and stamps `terminated_at` on expired `game_launches` rows, so the table does not grow without bound. The asset query already rejects expired rows by `expires_at`; the sweeper is not what enforces the TTL.

## Trade-offs accepted

- **Disk pressure scales with concurrent old launches × upload frequency.** Pruner enforces a hard cap; tunable via `app.uploads.versions-to-keep`.
- **A player whose session lasts >15 min through a hot upload + a level transition will see the new version.** Not the old one. The TTL is a deliberate cap on how long we honor the pin.
- **Two-store atomicity gap.** The install transaction is atomic in Postgres but NOT atomic vs the on-disk move. Recovery procedure is in the [maintainer runbook](../guides/maintainer-runbook.md), Runbook 5.

## Reference

- [04 — Data model](../architecture/04-data-model.md#federation-access-and-uploads)
- Plan section H in [`docs/PLAN-module-federation-and-native.md`](../../PLAN-module-federation-and-native.md).
