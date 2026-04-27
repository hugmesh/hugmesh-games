# 005 — Launch-UID + version-pinning for hot uploads

**Status:** Adopted.

## Decision

Every game launch mints a 256-bit URL-safe `launch_uid` that captures the current `games.current_version_dir` at mint time. The launch row holds that `version_dir` for its 15-minute TTL. An in-flight launch keeps reading from the install pinned at mint time, even if a hot upload supersedes the bundle mid-session.

## Why

The hot-upload pipeline (admin uploads a zip → BFF atomically moves it to `<gamesDir>/<slug>/<sha-prefix>` → updates `games.current_version_dir`) must not pull the rug out from under a player who's mid-game. Without pinning, a level transition mid-session would resolve `current_version_dir` against the *new* upload — assets, scene DTOs, even WASM heap layouts could shift between two requests in the same playthrough.

We can't rely on filesystem symlinks alone because the resolution from URL to file path goes through the BFF (DB lookup against `game_launches` → resolves filesystem path) for any uploaded game. Pinning at the row level keeps the contract: "your session reads from the version that was current when you launched."

## How to apply

- **`POST /api/games/{slug}/launch`** snapshots `games.current_version_dir` into `game_launches.version_dir` at mint time. The response body carries `versionDir` so the iframe URL contains `?launch_uid=<uid>` and the BFF can resolve it back.
- **`AssetController.getLevelAssetByUid`** prefers the launch row's `version_dir` over `current_version_dir`. Falls back to current/classpath only when the launch row's `version_dir` is null (legacy launch or classpath-only game).
- **`GameUploadService`** marks prior `game_uploads` rows `superseded_at = NOW()` on a successful new upload. The `version_dir` columns on stale launches stay populated; their files stay on disk until `GameUploadPruner` runs.
- **`GameUploadPruner`** keeps the latest `app.uploads.versions-to-keep` directories per slug (default 3); never deletes the row marked `supersededAt=NULL`.
- **`GameLaunchSweeper`** marks expired `game_launches` rows so the asset path stops resolving against them after the TTL.

## Trade-offs accepted

- **Disk pressure scales with concurrent old launches × upload frequency.** Pruner enforces a hard cap; tunable via `app.uploads.versions-to-keep`.
- **A player whose session lasts >15 min through a hot upload + a level transition will see the new version.** Not the old one. The TTL is a deliberate cap on how long we honor the pin.
- **Two-store atomicity gap.** The install transaction is atomic in Postgres but NOT atomic vs the on-disk move. Recovery procedure is in the [maintainer runbook](../guides/maintainer-runbook.md), Runbook 5.

## Reference

- [04 — Data model](../architecture/04-data-model.md#federation-access-and-uploads)
- Plan section H in [`docs/PLAN-module-federation-and-native.md`](../../PLAN-module-federation-and-native.md).
