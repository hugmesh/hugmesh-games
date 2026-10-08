# 009 — Three additive scopes for `game_access`

**Status:** Adopted.

## Decision

`game_access` rows decide who sees a game. Rows add up. There are three scopes:

| Scope | `scope_value` | Meaning |
|---|---|---|
| `all` | `NULL` | Every authenticated player can see the game. |
| `role` | `ROLE_PLAY_<GAME>` (or any Casdoor role name) | Players with that role see the game. |
| `user` | `<casdoor-user-id>` | Named individual sees the game. |

Multiple rows are *unioned*. A game with `(all, NULL)` plus `(user, alice)` is visible to everyone, because the `all` row wins. A game with only `(role, ROLE_PLAY_TWIAT)` plus `(user, alice)` is visible to role-holders OR alice.

The constraint `uq_game_access_triple (game_id, scope, scope_value)` makes `role` and `user` grants idempotent: `GameAccessController` catches the violation and returns the existing row. An `all` row has `scope_value = NULL`, and Postgres treats NULLs as distinct in a unique constraint. So the constraint does not stop a second `all` row. Readers do not notice, because the visibility query uses `SELECT DISTINCT game_id`. This follows from the DDL and Postgres NULL semantics. It has not been run against a live database.

A game with no rows matches nobody, so every principal is blind to it.

## Why

The portal needs:

1. **Public games** — visible to anyone logged in.
2. **Beta or paid games** — visible to a specific role.
3. **One-off grants** — let a specific user see a game without joining a role group.

A single `visibility` enum column would allow one mode per game. A JSON blob would push the policy into application code. A relational table with three scopes and union semantics keeps the policy declarative, queryable and extensible.

## How to apply

- **Public by default for newly uploaded games.** The HugMesh Export plugin's `register.sql` inserts an `all` row with `ON CONFLICT (game_id, scope, scope_value) DO NOTHING` (`manifest_builder.gd`). Because `scope_value` is NULL, that clause never fires, so running `register.sql` again adds another `all` row. Admins narrow access afterwards in the admin UI by revoking the `all` row.
- **Admin UI** — the *Per-game access* card (`upload-remote.component.ts`). It POSTs `{scope, value}` to `/api/admin/games/{slug}/access`. `DELETE` on the same path, with `scope` and `value` query parameters, revokes a row. The roles dropdown reads live from Casdoor through `/api/admin/roles`. An admin may change only games in their own brand (`requireGameDomain`).
- **Read path** — `ProgressionService` takes game ids from `GameAccessRepository.findVisibleGameIdsForPrincipal`, filters them by the caller's brand domain, and reads `character_quests` for unlock state. It drives `/api/games/available/{characterId}`. `POST /api/games/{slug}/launch` repeats the visibility check and returns 403 on failure.

## Trade-offs accepted

- **Union semantics surprise operators.** They expect `(role, X)` to *exclude* non-role-holders. It only *also* includes role-holders. The [administration manual](../guides/administration.md) says so. To exclude non-role-holders, *don't add the `(all, NULL)` row*. Start with a role grant only.
- **No deny-list.** To block a user, ban them (see [howto/kick-or-ban.md](../howto/kick-or-ban.md)) or leave them out of every scope row. This cap was deliberate. A deny-list can come if a use case appears.

## Reference

- [04 — Data model](../architecture/04-data-model.md#federation-access-and-uploads)
- [05 — API reference](../architecture/05-api-reference.md#gameaccesscontroller--apiadmingamesslugaccess)
- [howto/add-role-and-grant.md](../howto/add-role-and-grant.md)
