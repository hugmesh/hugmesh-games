# 009 — Three additive scopes for `game_access`

**Status:** Adopted.

## Decision

Per-game visibility is enforced by additive `game_access` rows. Three scopes:

| Scope | `scope_value` | Meaning |
|---|---|---|
| `all` | `NULL` | Every authenticated player can see the game. |
| `role` | `ROLE_PLAY_<GAME>` (or any Casdoor role name) | Players with that role see the game. |
| `user` | `<casdoor-user-id>` | Named individual sees the game. |

Multiple rows are *unioned*. A game with `(all, NULL)` plus `(user, alice)` is visible to everyone (the `all` row wins); a game with only `(role, ROLE_PLAY_TWIAT)` plus `(user, alice)` is visible to role-holders OR alice.

The constraint `uq_game_access_triple (game_id, scope, scope_value)` makes `role` and `user` grants idempotent: `GameAccessController` catches the violation and returns the existing row. An `all` row has `scope_value = NULL`, and Postgres treats NULLs as distinct in a unique constraint, so the constraint does not stop a second `all` row. Duplicates are harmless to readers because the visibility query uses `SELECT DISTINCT game_id`. This follows from the DDL and Postgres NULL semantics; it has not been run against a live database.

A game with no rows at all matches nobody, so it is hidden from every principal.

## Why

The portal needs:

1. **Public games** — visible to anyone logged in.
2. **Beta or paid games** — visible to a specific role.
3. **One-off grants** — let a specific user see a game without making them join a role group.

A single-column `visibility` enum would force one mode per game; a JSON blob would push the policy logic into application code. A relational table with three scopes plus union semantics keeps the policy declarative, queryable, and extensible.

## How to apply

- **Public-by-default for newly uploaded games.** The HugMesh Export plugin's `register.sql` inserts an `all` row with `ON CONFLICT (game_id, scope, scope_value) DO NOTHING` (`manifest_builder.gd`). Because `scope_value` is NULL, that clause never fires, so re-running `register.sql` adds another `all` row. Admins narrow access from the admin UI afterwards by revoking the `all` row.
- **Admin UI** — *Per-game access* card (`upload-remote.component.ts`). It POSTs `{scope, value}` to `/api/admin/games/{slug}/access`; `DELETE` on the same path with `scope` and `value` query parameters revokes a row. The roles dropdown reads live from Casdoor via `/api/admin/roles`. An admin may only change games in their own brand (`requireGameDomain`).
- **Read path** — `ProgressionService` takes the game ids from `GameAccessRepository.findVisibleGameIdsForPrincipal`, filters them by the caller's brand domain, and reads `character_quests` for unlock state. It drives `/api/games/available/{characterId}`. `POST /api/games/{slug}/launch` repeats the visibility check and returns 403 if it fails.

## Trade-offs accepted

- **Union semantics surprise some operators** — they expect `(role, X)` to *exclude* non-role-holders, not just *also* include role-holders. Documented in the [administration manual](../guides/administration.md). To exclude non-role-holders, *don't add the `(all, NULL)` row* — start with a role grant only.
- **No deny-list.** A "blocked" user has to be either banned (see [howto/kick-or-ban.md](../howto/kick-or-ban.md)) or absent from every scope row. This was a deliberate scope cap; a deny-list is a future addition if a use case emerges.

## Reference

- [04 — Data model](../architecture/04-data-model.md#federation-access-and-uploads)
- [05 — API reference](../architecture/05-api-reference.md#gameaccesscontroller--apiadmingamesslugaccess)
- [howto/add-role-and-grant.md](../howto/add-role-and-grant.md)
