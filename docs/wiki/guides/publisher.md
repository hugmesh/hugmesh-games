# Publisher guide

> Mirror of [`docs/publishing/userguides/publisher/guide.tex`](../../publishing/userguides/publisher/guide.tex).

What `ROLE_PUBLISHER` lets you do for the games in your scope: upload, roll back, set version pin, edit access policy. The boundary against admin and the per-game scope check is the same two-layer pattern as moderators.

## Who this is for

Anyone who holds `ROLE_PUBLISHER` in Casdoor AND at least one row in [`publisher_game_scopes`](../architecture/04-data-model.md#publisher_game_scopes). You are responsible for the catalog state of one or more games — uploading new bundles, pinning versions, gating access — but NOT for stack-level operations like restarting services or granting roles.

If a game isn't in your scope, an `ROLE_ADMIN` holder grants you the scope row at `/admin/role-grants` (see [grant-mod-or-publisher](../howto/grant-mod-or-publisher.md)). The check is enforced at the BFF before any state mutation.

## What you can see

`/publisher` is the federated publisher console (`frontend/projects/publisher/`). Same Module Federation pattern as `/admin` and `/mod`. Non-publishers see a 403 banner.

| Tab | What it shows |
|---|---|
| **Catalog** | Every game in your scope with current version, access policy summary, and recent upload count. |
| **Uploads** | Per-game upload history (most recent first). Each row links to its rollback target. |
| **Access policy** | Per-game `game_access` table view: rows have `scope=all|role|user`. Add/remove from this UI. |
| **Live sessions** | Read-only for your games — same data the mod console shows but without the kick controls. |

`/admin` and `/mod` consoles remain inaccessible — different roles, different scopes.

## What you can do

- **Upload a new bundle** for a game in your scope. `POST /api/games/{slug}/uploads` with the Snapdragon Export zip. The BFF validates manifest schema v2, hashes the bundle, atomically moves to `<gamesDir>/<slug>/<sha-prefix>`, and runs `register.sql` in one Postgres transaction. The rollback history is preserved.
- **Pin a previous version** as the active one. Useful when a fresh upload regresses. `PUT /api/games/{slug}/uploads/{uploadId}/pin`.
- **Edit per-game access policy.** `POST /api/games/{slug}/access` with `{scope, value}` — three additive scopes: `all`, `role` (e.g. `ROLE_PLAY_THREE_WIZARDS`), `user` (specific Casdoor ID). Idempotent on `uq_game_access_triple`. See [decision 009](../decisions/009-game-access-scopes.md).
- **List active sessions** for monitoring (read-only — kick is mod-only).
- **Author a new game from scratch** without leaving the portal. Two launch buttons on the publisher tab open dedicated federated maker remotes in a new tab:
    - **Launch RPG Maker** → `/rpg-maker` (Mudlet-shaped editor: areas / rooms / N/S/E/W/NE/NW/SE/SW/UP/DOWN/TELEPORT exits / items / NPCs / scripts). Backed by the [`rpg-builder/`](../../../rpg-builder/) Spring service.
    - **Launch Visual Novel Maker** → `/vn-maker` (godot-2d-visual-novel-shaped editor: acts / characters / backgrounds / scenes / dialogue lines / branching choices). Backed by the [`vn-builder/`](../../../vn-builder/) Spring service.

  Both makers compile the saved project to a Snapdragon-bridge-aware Godot 4 WASM bundle (one bundle per Area/Act → registered as a Chapter), POST it to `/api/admin/games/upload` with **your** bearer token, and the BFF runs the same `publisher_game_scopes` check it would on a hand-uploaded bundle. The maker shows a live build-progress bar with terminal `DONE` / `FAILED` state. See [decision 018](../decisions/018-rpg-and-vn-makers.md).

## What you can NOT do

- Grant roles or scope rows (admin only). You receive scope from admins; you can't widen it for yourself or others.
- Upload, roll back, or edit access for a game outside your scope. 403.
- Kick or ban players (mod / admin).
- Toggle WASM-console debug mode (admin).
- Edit a published commit's bundles directly on disk — go through the upload pipeline so the audit trail is correct.

## Scope is per-game, like mods

`ROLE_PUBLISHER` alone is not enough; every publisher-only endpoint also checks `publisher_game_scopes` for the targeted game slug. Same Casdoor + Postgres split as moderators. See [decision 011](../decisions/011-mod-publisher-rbac.md).

## Day-1 playbook

1. Log in. Confirm the `/publisher` link appears in the host header.
2. Open `/publisher`. The catalog lists every game in your scope. The "current version" column should match the bundle on disk under `<gamesDir>/<slug>/`.
3. To ship a new build: open the game's *Uploads* tab → *Upload new bundle* → choose a Snapdragon Export zip. The form posts to `POST /api/games/{slug}/uploads`. Wait for the success toast (10–30 s for a typical bundle).
4. Verify by visiting `/` and clicking the game's card → the chapter modal should mount the new version. The host's federation manifest refreshes within 60 s of the install transaction committing.
5. If something is wrong: open *Uploads*, find the previous row, click *Pin*. The active-version pointer flips back atomically. Old superseded versions are kept on disk by [`GameUploadPruner`](../architecture/06-build-and-deploy.md#scheduled-jobs) until the retention cap (default 3 newest).
6. To gate a new game: *Access policy* tab → choose `scope=role` and a role like `ROLE_PLAY_THREE_WIZARDS` → save. Players without that role hit 403 on the chapter scene fetch.

## Common fires

| Symptom | Likely cause | Fix |
|---|---|---|
| `/publisher` shows a 403 banner | Casdoor session has `ROLE_PUBLISHER` but no `publisher_game_scopes` row | Ask an admin to grant scope at `/admin/role-grants` |
| Upload returns 400 "manifest schema mismatch" | Bundle exported with a stale Snapdragon Export plugin | Update the plugin in Godot, re-export |
| Upload returns 409 | Same SHA already on disk under that slug | The bundle is byte-identical to a prior version; nothing to do |
| Pin returns 404 | The target uploadId was pruned by `GameUploadPruner` | Pin to the next-most-recent surviving version, OR raise `versions-to-keep` and re-upload |
| Access policy edit "wins" but the player still 403s | Caller's JWT is stale | Player needs to re-login so the JWT picks up the new role-bearing claim |
| Live-sessions list is empty | No one is playing right now | This is normal — the table reflects `session_members.left_at IS NULL` |

## Where to look next

- [Decision 011 — Mod / publisher RBAC](../decisions/011-mod-publisher-rbac.md).
- [Decision 009 — Three additive scopes for `game_access`](../decisions/009-game-access-scopes.md).
- [How-to: hot-upload and roll back a game bundle](../howto/upload-and-rollback-game.md).
- [How-to: grant the mod or publisher role for a game](../howto/grant-mod-or-publisher.md).
- [Architecture chapter 5 — API reference](../architecture/05-api-reference.md): every publisher-scoped endpoint.
