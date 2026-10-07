# Publisher guide

> A typeset edition lives in [`docs/publishing/userguides/publisher/guide.tex`](../../publishing/userguides/publisher/guide.tex). The two are edited separately and can differ.

What `ROLE_PUBLISHER` lets you do for the games in your scope: upload bundles, build games in the makers, and grant or revoke the mod role. Rolling back a version and editing access policy are admin tasks. The boundary against admin and the per-game scope check is the same two-layer pattern as moderators.

## Who this is for

Anyone who holds the Casdoor role `publisher` (the BFF sees it as `ROLE_PUBLISHER`) AND at least one row in [`publisher_game_scopes`](../architecture/04-data-model.md#multiplayer-session-schema). You are responsible for one or more games — uploading new bundles and choosing their moderators — but NOT for stack-level operations like restarting services, and NOT for access policy or version rollback.

If a game isn't in your scope, an `ROLE_ADMIN` holder grants you the scope row with `POST /api/admin/role-grants/grant-publisher`. That is an API call; there is no admin page for it. See [grant-mod-or-publisher](../howto/grant-mod-or-publisher.md). The check is enforced at the BFF before any state mutation.

## What you can see

`/publisher` is the federated publisher console (`frontend/projects/publisher/`). Same Module Federation pattern as `/admin` and `/mod`. Non-publishers see a 403 banner.

| Section | What it shows |
|---|---|
| **Games you publish** | Your active `publisher_game_scopes` rows, from `GET /api/publisher/scopes`. Click a slug to select it. |
| **Upload a bundle** | A file picker for a HugMesh Export zip and an *Upload* button. |
| **Build a new game** | *Launch RPG Maker* and *Launch Visual Novel Maker*. |
| **Moderators for** *game* | The active mods of the selected game, a *Revoke* button on each, and a field to grant the mod scope to a Casdoor user id. |

There are no catalog, uploads, access-policy or live-sessions tabs.

`/admin` and `/mod` consoles remain inaccessible — different roles, different scopes.

## What you can do

- **Upload a new bundle** for a game in your scope. `POST /api/admin/games/upload` (multipart field `bundle`) with the HugMesh Export zip. The route accepts `ADMIN` and `PUBLISHER`; for a publisher the BFF checks `publisher_game_scopes` against the slug in the manifest. The BFF validates manifest schema v2, hashes the bundle, atomically moves to `<gamesDir>/<slug>/<sha-prefix>`, and runs `register.sql` in one Postgres transaction. Earlier versions stay on disk.
- **Read upload history.** `GET /api/admin/games/{slug}/uploads` (newest first).
- **Grant or revoke the mod scope for a game you own.** `POST /api/publisher/grant-mod` and `POST /api/publisher/revoke-mod` with `{targetCasdoorId, gameSlug}`; `GET /api/publisher/games/{gameSlug}/mods` lists the current mods. This adds the per-game scope only; the Casdoor `mod` role must still be assigned in Casdoor.
- **Author a new game from scratch** without leaving the portal. Two launch buttons on the publisher console open dedicated federated maker remotes in a new tab:
    - **Launch RPG Maker** → `/rpg-maker` (Mudlet-shaped editor: areas / rooms / N/S/E/W/NE/NW/SE/SW/UP/DOWN/TELEPORT exits / items / NPCs / scripts). Backed by the [`rpg-builder/`](../../../rpg-builder/) Spring service.
    - **Launch Visual Novel Maker** → `/vn-maker` (godot-2d-visual-novel-shaped editor: acts / characters / backgrounds / scenes / dialogue lines / branching choices). Backed by the [`vn-builder/`](../../../vn-builder/) Spring service.

  Both makers compile the saved project to a HugMesh-bridge-aware Godot 4 WASM bundle (one bundle per Area/Act → registered as a Chapter), POST it to `/api/admin/games/upload` with **your** bearer token, and the BFF runs the same `publisher_game_scopes` check it would on a hand-uploaded bundle. The maker shows a live build-progress bar with terminal `DONE` / `FAILED` state. See [decision 018](../decisions/018-rpg-and-vn-makers.md).

## What you can NOT do

- Grant publisher scope (admin only: `/api/admin/role-grants/grant-publisher`). You receive scope from admins; you can't widen it for yourself or others. You can grant the mod scope, but only for games you own.
- Pin or roll back a version. No endpoint or button exists; an admin does it in SQL (see [upload-and-rollback-game](../howto/upload-and-rollback-game.md), option B).
- Edit per-game access policy. `/api/admin/games/{slug}/access` is `ADMIN`-only. Ask an admin.
- Upload for a game outside your scope. 403.
- Kick or ban players (mod / admin).
- Toggle WASM-console debug mode (admin).
- Edit a published commit's bundles directly on disk — go through the upload pipeline so the audit trail is correct.

## Scope is per-game, like mods

`ROLE_PUBLISHER` alone is not enough; the upload route and the mod-grant endpoints also check `publisher_game_scopes` for the targeted game slug. Same Casdoor + Postgres split as moderators. See [decision 011](../decisions/011-mod-publisher-rbac.md).

## Day-1 playbook

1. Log in. Confirm the `/publisher` link appears in the host header.
2. Open `/publisher`. *Games you publish* lists every game in your scope. Click one to select it.
3. To ship a new build: *Upload a bundle* → choose a HugMesh Export zip → *Upload*. The form posts to `POST /api/admin/games/upload`. Wait for the success message (10–30 s for a typical bundle).
4. Verify by reloading the portal, visiting `/`, and clicking the game's card. New launches mount the new version; the browser fetches the federation manifest when the page loads, so a page that was already open must be reloaded.
5. If something is wrong: you cannot roll back yourself. Ask an admin to pin the previous version. Old versions are kept on disk by [`GameUploadPruner`](administration.md#scheduled-jobs) until the retention cap (default 3 newest).
6. To gate a new game: ask an admin to add a `scope=role` row (the Casdoor role name, such as `play-three-wizards`) on `/admin/games` → *Per-game access*. Players without that role do not see the game.

## Common fires

| Symptom | Likely cause | Fix |
|---|---|---|
| `/publisher` shows a 403 banner | Casdoor session has `ROLE_PUBLISHER` but no `publisher_game_scopes` row | Ask an admin to grant scope at `/admin/role-grants` |
| Upload returns 400 "manifest.schema_version must be 2" | Bundle exported with a stale HugMesh Export plugin | Update the plugin in Godot, re-export |
| Upload returns 409 | Same SHA already on disk under that slug | The bundle is byte-identical to a prior version; nothing to do |
| Grant/revoke mod fails | The game is not in your scope, or `targetCasdoorId` is wrong | Check *Games you publish* and the Casdoor user id |

## Where to look next

- [Decision 011 — Mod / publisher RBAC](../decisions/011-mod-publisher-rbac.md).
- [Decision 009 — Three additive scopes for `game_access`](../decisions/009-game-access-scopes.md).
- [How-to: hot-upload and roll back a game bundle](../howto/upload-and-rollback-game.md).
- [How-to: grant the mod or publisher role for a game](../howto/grant-mod-or-publisher.md).
- [Architecture chapter 5 — API reference](../architecture/05-api-reference.md): every publisher-scoped endpoint.
