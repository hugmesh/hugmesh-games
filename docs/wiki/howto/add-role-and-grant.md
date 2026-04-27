# How to add a role and gate a game on it

Two layers: Casdoor owns the role catalog, the BFF owns visibility policy.

## 1. Create the role in Casdoor

Roles live in Casdoor. The portal recognises:

- `ROLE_ADMIN` — admin dashboard access.
- `ROLE_PLAY_<GAME>` — per-game play access.

Create via the Casdoor admin UI (port-forward, since Casdoor isn't publicly exposed — see [decision 008](../decisions/008-casdoor-internal.md)) or via the seed target:

```bash
make seed-roles
```

`make seed-roles` is the idempotent post-deploy entry point that creates the per-game `ROLE_PLAY_*` roles via the Casdoor admin API. Run it after deploying a new game.

## 2. Insert the `game_access` row

Via the admin UI: `/admin/games` → *Per-game access* card → choose scope `role` → pick the role from the dropdown → Add.

Or via SQL:

```sql
INSERT INTO game_access (game_slug, scope, scope_value)
VALUES ('three-wizards', 'role', 'ROLE_PLAY_THREE_WIZARDS')
ON CONFLICT ON CONSTRAINT uq_game_access_triple DO NOTHING;
```

The unique constraint absorbs duplicates — flipping the row on twice is a no-op.

## 3. Decide on coexistence with `(all, NULL)`

Three scopes are unioned:

| Configuration | Visibility |
|---|---|
| `(all, NULL)` only | Everyone authenticated. |
| `(role, ROLE_PLAY_X)` only | **Only role-holders.** |
| `(all, NULL)` + `(role, ROLE_PLAY_X)` | **Everyone** (the `all` row wins). |
| `(role, ROLE_PLAY_X)` + `(user, alice)` | Role-holders OR alice. |

To gate a game, **delete the `(all, NULL)` row** and keep only the role / user grants. Otherwise the role grant is just an unused additive on top of public visibility.

## 4. Assign the role to a user

In Casdoor's admin UI: User → edit → Roles → add the new role. The user's next JWT will carry the new claim.

## 5. Verify

```bash
# As the user:
curl -H "Authorization: Bearer $JWT" /api/games/available/{characterId} | jq

# As an admin, see what scopes are active for a game:
curl -H "Authorization: Bearer $ADMIN_JWT" /api/admin/games/three-wizards/access
```

## See also

- [Decision 009 — Three additive scopes](../decisions/009-game-access-scopes.md)
- [Decision 008 — Casdoor internal-only](../decisions/008-casdoor-internal.md)
- [Administration manual](../guides/administration.md)
