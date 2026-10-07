# How to add a role and gate a game on it

Two layers: Casdoor owns the role catalog, the BFF owns visibility policy.

## 1. Create the role in Casdoor

Roles live in Casdoor. The portal recognises:

- `admin` (the BFF turns it into `ROLE_ADMIN`) — admin dashboard access.
- `mod`, `publisher` — the per-game moderator and publisher roles.
- `play-<slug>` (for example `play-three-wizards`) — per-game play access.

Role names are lowercase in Casdoor and in `game_access.scope_value`. The BFF compares them with the lowercased `roles` claim of the JWT.

Create the role in the Casdoor admin UI (port-forward to reach it; Casdoor is not publicly exposed — see [decision 008](../decisions/008-casdoor-internal.md)) or with the seed target:

```bash
make seed-roles
```

`make seed-roles` creates a `play-<slug>` role for every slug in the Makefile's `GAME_SLUGS` list, through the Casdoor admin API. It needs `curl`, `jq` and `CASDOOR_ADMIN_PASS` (the admin password from the Casdoor logs), and reads `CASDOOR_ENDPOINT` (default `http://localhost:8000`). Add the new game's slug to `GAME_SLUGS` first, then run it. Re-running is harmless: errors on existing roles are ignored.

## 2. Insert the `game_access` row

In the admin UI: `/admin/games` → *Per-game access* card → enter the game slug → choose scope `role` → pick the role from the dropdown → *Grant*.

With the API:

```bash
curl -sS -X POST -H "Authorization: Bearer $ADMIN_JWT" \
  -H 'Content-Type: application/json' \
  -d '{"scope":"role","value":"play-three-wizards"}' \
  /api/admin/games/three-wizards/access
```

With SQL:

```sql
INSERT INTO game_access (game_id, scope, scope_value, granted_by)
SELECT id, 'role', 'play-three-wizards', 'manual-sql'
  FROM games WHERE slug = 'three-wizards'
ON CONFLICT ON CONSTRAINT uq_game_access_triple DO NOTHING;
```

The unique constraint absorbs duplicates. Adding the row twice changes nothing.

## 3. Decide on coexistence with `(all, NULL)`

The three scopes are unioned:

| Configuration | Visibility |
|---|---|
| `(all, NULL)` only | Everyone authenticated. |
| `(role, play-x)` only | **Only role-holders.** |
| `(all, NULL)` + `(role, play-x)` | **Everyone** (the `all` row wins). |
| `(role, play-x)` + `(user, alice)` | Role-holders OR alice. |

To gate a game, **delete the `(all, NULL)` row** (*Revoke* with scope `all` in the UI, or `DELETE /api/admin/games/{slug}/access?scope=all`) and keep only the role and user grants. Otherwise the role grant adds nothing to a game that is already public.

## 4. Assign the role to a user

In Casdoor's admin UI: User → edit → Roles → add the new role. The user's next JWT carries the new claim.

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
