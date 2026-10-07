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

The legacy admin role is a single global authority — fine for a one-game portal, awkward when one publisher should manage `three-wizards` while a different team owns `dodecahedron`. Splitting into two layers (capability in Casdoor, scope in Postgres) lets:

- Multiple publishers coexist without seeing each other's games.
- Publishers grant moderators for their own games without admin involvement.
- Admins retain full authority over both layers.
- The audit trail (granted_by + granted_at + revoked_by + revoked_at) survives revocations.

## How to apply

- Casdoor seed (`init_data.json.tpl`) ships **per-game seed users**: `mod-three-wizards`, `mod-dodecahedron`, `publisher-three-wizards`, `publisher-dodecahedron` — each member of the matching role plus a seed scope row. Plus a generic `mod` and `publisher` for ad-hoc tests, and three additional player users `player1` / `player2` / `player3` for multiplayer testing.
- BFF endpoints:
  - `POST /api/mod/sessions/{id}/{stop,kick}` — per-game mod actions; `ModerationService.requireScope(casdoorId, gameSlug)` is the gate.
  - `POST /api/publisher/{grant,revoke}-mod` — per-game mod role grants; `PublisherService.requireOwns` is the gate.
  - `POST /api/admin/role-grants/grant-{publisher,mod}` — admin variant, bypasses the per-game ownership check. It is still brand-gated: the caller's brand must own the game (`RoleAdminController.requireGameDomain`).
- Frontend: separate federated remotes at `/mod` (`frontend/projects/mod/`) and `/publisher` (`frontend/projects/publisher/`); role-conditional nav links via `AuthService.isMod()` + `isPublisher()`.

## Trade-offs accepted

- **Two layers to update on grant.** Adding a publisher requires (a) the Casdoor `publisher` role membership AND (b) a `publisher_game_scopes` row. Documented in [howto/grant-mod-or-publisher.md](../howto/grant-mod-or-publisher.md); admins flip both via `/api/admin/role-grants` + Casdoor admin UI.
- **No deny-list.** A revoked scope is soft-deleted (`revoked_at`); the user simply has no active scope. There's no way to mark a user "may never moderate game X again" beyond the audit trail.
- **Per-game upload scoping is BFF-side only.** `GameUploadController` accepts `ROLE_PUBLISHER` but the per-game match against `publisher_game_scopes` happens after manifest parse. A publisher uploading a bundle for a game they don't own gets a 403 mid-install. Acceptable trade-off; the alternative (knowing the slug before the multipart wrapper) would require a wrapper protocol change. The same hook now also runs a brand check: the uploader's brand must own an existing slug, and only `SUPERADMIN` bypasses it (`GameUploadController.requireScope`).

## Reference

- [05 — API + service reference § Moderation, Publisher, RoleAdmin](../architecture/05-api-reference.md)
- [howto/grant-mod-or-publisher.md](../howto/grant-mod-or-publisher.md)
- Liquibase 032 ([`032-mod-game-scopes.xml`](../../../backend/src/main/resources/db/changelog/032-mod-game-scopes.xml)).
