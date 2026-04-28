# How to grant the mod or publisher role for a game

Two layers. Casdoor owns role membership; Postgres owns per-game scope. Both must be set for the BFF to accept the actions.

## Prerequisites

- For granting `mod`: you're an admin OR a publisher who owns the target game.
- For granting `publisher`: you're an admin only.

## Step 1 — Casdoor role membership

Open the Casdoor admin UI (port-forward, since Casdoor is internal — see [decision 008](../decisions/008-casdoor-internal.md)):

```bash
docker compose port casdoor 8000   # find the published port
# open http://localhost:<port> and log in as the casdoor admin
```

Navigate to *Users → <user> → Roles → Edit → Add* the matching role:

- `mod` for moderation
- `publisher` for publishing

The user's next JWT will carry the new role in the `roles` claim, which the BFF's `casdoorAuthoritiesConverter` maps to `ROLE_MOD` / `ROLE_PUBLISHER` Spring authorities.

## Step 2 — per-game scope row

The role membership alone does nothing without a scope row. Two ways to grant.

### Option A — admin role-grants endpoint

```bash
ADMIN_JWT=...

# Grant mod scope to alice for three-wizards:
curl -sS -X POST -H "Authorization: Bearer $ADMIN_JWT" \
  -H 'Content-Type: application/json' \
  -d '{"targetCasdoorId":"alice","gameSlug":"three-wizards"}' \
  http://localhost:8080/api/admin/role-grants/grant-mod

# Grant publisher scope (admin-only):
curl -sS -X POST -H "Authorization: Bearer $ADMIN_JWT" \
  -H 'Content-Type: application/json' \
  -d '{"targetCasdoorId":"bob","gameSlug":"three-wizards"}' \
  http://localhost:8080/api/admin/role-grants/grant-publisher

# List active grants:
curl -sS -H "Authorization: Bearer $ADMIN_JWT" \
  http://localhost:8080/api/admin/role-grants/mods
curl -sS -H "Authorization: Bearer $ADMIN_JWT" \
  http://localhost:8080/api/admin/role-grants/publishers
```

### Option B — publisher grants mod for their own game

A publisher who already owns a game can grant mod for it without admin involvement:

```bash
PUB_JWT=...   # a publisher who has publisher_game_scopes for three-wizards

curl -sS -X POST -H "Authorization: Bearer $PUB_JWT" \
  -H 'Content-Type: application/json' \
  -d '{"targetCasdoorId":"alice","gameSlug":"three-wizards"}' \
  http://localhost:8080/api/publisher/grant-mod
```

This is what the publisher's UI at `/publisher` invokes from the *Manage moderators* card.

## Revoking

Soft-delete via the matching `revoke-*` endpoint. The audit row stays:

```bash
curl -sS -X POST -H "Authorization: Bearer $ADMIN_JWT" \
  -H 'Content-Type: application/json' \
  -d '{"targetCasdoorId":"alice","gameSlug":"three-wizards"}' \
  http://localhost:8080/api/admin/role-grants/revoke-mod
```

## Seeded users (out of the box)

The Casdoor template ships with per-game seed users so you can test multiplayer + RBAC without manual grants:

| User | Casdoor roles | Postgres scope rows |
|---|---|---|
| `mod-three-wizards` | `mod` | `mod_game_scopes` for `three-wizards` |
| `mod-dodecahedron` | `mod` | `mod_game_scopes` for `dodecahedron` |
| `publisher-three-wizards` | `publisher` | `publisher_game_scopes` for `three-wizards` |
| `publisher-dodecahedron` | `publisher` | `publisher_game_scopes` for `dodecahedron` |

Their seed passwords are printed by `casdoor/entrypoint.sh` on first boot — same mechanism as the existing `admin` / `player` seeds.

## See also

- [Decision 011 — Mod / publisher with per-game scoping](../decisions/011-mod-publisher-rbac.md)
- [Decision 009 — Three additive scopes for game_access](../decisions/009-game-access-scopes.md)
- [Administration manual](../guides/administration.md)
