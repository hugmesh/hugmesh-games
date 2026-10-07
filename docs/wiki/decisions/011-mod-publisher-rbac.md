# 011 — Mod / publisher roles with per-game Postgres scoping

**Status:** Adopted (Phase 2 of the multiplayer overhaul).

## Decision

Two new roles join `admin` and `player` in Casdoor:

| Role | Capability | Scope |
|---|---|---|
| `mod` | Stop sessions and kick players. | Per-game via `mod_game_scopes`. |
| `publisher` | Upload game bundles + grant the `mod` role. | Per-game via `publisher_game_scopes`. |

Casdoor owns role membership ("alice has the `mod` role"). Postgres owns per-game scope ("alice can mod `three-wizards` but not `dodecahedron`"). The BFF rejects every mod / publisher action whose target game isn't in the caller's active scope.

## Why

The admin role is one global authority. That works for a one-game portal. It fails when one publisher should manage `three-wizards` and another team owns `dodecahedron`. Two layers fix this: capability in Casdoor, scope in Postgres. They let us:

- Run several publishers side by side, each blind to the others' games.
- Let publishers grant moderators for their own games without an admin.
- Keep full admin authority over both layers.
- Keep the audit trail (granted_by + granted_at + revoked_by + revoked_at) after a revocation.

## How to apply

- The Casdoor seed (`init_data.json.tpl`) ships **per-game seed users**: `mod-three-wizards`, `mod-dodecahedron`, `publisher-three-wizards`, `publisher-dodecahedron`. Each belongs to the matching role and has a seed scope row. It also ships a generic `mod` and `publisher` for ad-hoc tests, and three more player users, `player1` / `player2` / `player3`, for multiplayer testing.
- BFF endpoints:
  - `POST /api/mod/sessions/{id}/{stop,kick}` — per-game mod actions; `ModerationService.requireScope(casdoorId, gameSlug)` is the gate.
  - `POST /api/publisher/{grant,revoke}-mod` — per-game mod role grants; `PublisherService.requireOwns` is the gate.
  - `POST /api/admin/role-grants/grant-{publisher,mod}` — admin variant, bypasses the per-game ownership check. It is still brand-gated: the caller's brand must own the game (`RoleAdminController.requireGameDomain`).
- Frontend: separate federated remotes at `/mod` (`frontend/projects/mod/`) and `/publisher` (`frontend/projects/publisher/`). Nav links appear by role through `AuthService.isMod()` and `isPublisher()`.

## Trade-offs accepted

- **Two layers to update on grant.** Adding a publisher needs (a) the Casdoor `publisher` role AND (b) a `publisher_game_scopes` row. [howto/grant-mod-or-publisher.md](../howto/grant-mod-or-publisher.md) covers it; admins set both through `/api/admin/role-grants` and the Casdoor admin UI.
- **No deny-list.** A revoked scope is soft-deleted (`revoked_at`), so the user simply has no active scope. Beyond the audit trail, you cannot mark a user "may never moderate game X again".
- **Per-game upload scoping is BFF-side only.** `GameUploadController` accepts `ROLE_PUBLISHER`, but the match against `publisher_game_scopes` runs after the manifest is parsed. A publisher who uploads a bundle for a game they don't own gets a 403 mid-install. We accept that; knowing the slug before the multipart wrapper would need a wrapper protocol change. The same hook now also runs a brand check: the uploader's brand must own an existing slug, and only `SUPERADMIN` bypasses it (`GameUploadController.requireScope`).

## Reference

- [05 — API + service reference § Moderation, Publisher, RoleAdmin](../architecture/05-api-reference.md)
- [howto/grant-mod-or-publisher.md](../howto/grant-mod-or-publisher.md)
- Liquibase 032 ([`032-mod-game-scopes.xml`](../../../backend/src/main/resources/db/changelog/032-mod-game-scopes.xml)).
