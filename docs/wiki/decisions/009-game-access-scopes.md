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

The constraint `uq_game_access_triple (game_slug, scope, scope_value)` makes inserts idempotent — flipping a row on twice is a no-op.

## Why

The portal needs:

1. **Public games** — visible to anyone logged in.
2. **Beta or paid games** — visible to a specific role.
3. **One-off grants** — let a specific user see a game without making them join a role group.

A single-column `visibility` enum would force one mode per game; a JSON blob would push the policy logic into application code. A relational table with three scopes plus union semantics keeps the policy declarative, queryable, and extensible.

## How to apply

- **Public-by-default for newly uploaded games.** The Snapdragon Export plugin's `register.sql` includes `INSERT INTO game_access (scope='all') ON CONFLICT DO NOTHING`. Admins narrow this from the admin UI afterwards.
- **Admin UI** — *Per-game access* card. POST to `/api/admin/games/{slug}/access` with `{scope, value}`. The roles dropdown reads live from Casdoor via `/api/admin/roles`.
- **Read path** — `ProgressionService` joins `game_access` × `characters` × `character_quests`. Drives `/api/games/available/{characterId}`.

## Trade-offs accepted

- **Union semantics surprise some operators** — they expect `(role, X)` to *exclude* non-role-holders, not just *also* include role-holders. Documented in the [administration manual](../guides/administration.md). To exclude non-role-holders, *don't add the `(all, NULL)` row* — start with a role grant only.
- **No deny-list.** A "blocked" user has to be either banned (see [howto/kick-or-ban.md](../howto/kick-or-ban.md)) or absent from every scope row. This was a deliberate scope cap; a deny-list is a future addition if a use case emerges.

## Reference

- [04 — Data model](../architecture/04-data-model.md#federation-access-and-uploads)
- [05 — API reference](../architecture/05-api-reference.md#gameaccesscontroller--apiadmingamesslugaccess)
- [howto/add-role-and-grant.md](../howto/add-role-and-grant.md)
