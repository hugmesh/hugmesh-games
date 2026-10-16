# How to grant the mod or publisher role for a game

Two layers. Casdoor owns role membership. Postgres owns per-game scope. The BFF accepts the actions only when both are set.

## Prerequisites

- For granting `mod`: you're an admin OR a publisher who owns the target game.
- For granting `publisher`: you're an admin only.

## Step 1 — Casdoor role membership

Casdoor publishes no host port ([decision 008](../decisions/008-casdoor-internal.md); the `casdoor` service has no `ports:` block), so `docker compose port casdoor 8000` prints nothing. To open its admin UI, publish the port with a temporary override file:

```yaml
# docker-compose.casdoor-ui.yml (keep it out of git)
services:
  casdoor:
    ports: ["127.0.0.1:8000:8000"]
```

```bash
docker compose -f docker-compose.yml -f docker-compose.casdoor-ui.yml up -d casdoor
# open http://localhost:8000 and sign in as the Casdoor admin
```

Navigate to *Users → <user> → Roles → Edit → Add* the matching role:

- `mod` for moderation
- `publisher` for publishing

The user's next JWT will carry the new role in the `roles` claim. [`CasdoorAuthorities`](../../../backend/src/main/java/games/hugmesh/bff/config/CasdoorAuthorities.java) maps it to the `ROLE_MOD` / `ROLE_PUBLISHER` Spring authorities.

## Step 2 — per-game scope row

A role without a scope row does nothing. There are two ways to add the row.

### Option A — admin role-grants endpoint

`targetCasdoorId` is the user's Casdoor ID. Rows you grant at runtime are keyed on the JWT `sub`. Changeset 032 seeds its rows by username (`mod-three-wizards`, and so on). Changeset 038 seeds `publisher-flatland` by Casdoor `id` (`tw-pub-flat-001`). `PublisherService` checks both identifiers.

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

A publisher who owns a game can grant mod for it without an admin:

```bash
PUB_JWT=...   # a publisher who has publisher_game_scopes for three-wizards

curl -sS -X POST -H "Authorization: Bearer $PUB_JWT" \
  -H 'Content-Type: application/json' \
  -d '{"targetCasdoorId":"alice","gameSlug":"three-wizards"}' \
  http://localhost:8080/api/publisher/grant-mod
```

The *Manage moderators* card at `/publisher` calls this endpoint.

## Revoking

Soft-delete via the matching `revoke-*` endpoint. The audit row stays. Admins have `revoke-mod` and `revoke-publisher` under `/api/admin/role-grants/`. A publisher revokes mod for their own game with `POST /api/publisher/revoke-mod`, and lists a game's mods with `GET /api/publisher/games/{gameSlug}/mods`.

```bash
curl -sS -X POST -H "Authorization: Bearer $ADMIN_JWT" \
  -H 'Content-Type: application/json' \
  -d '{"targetCasdoorId":"alice","gameSlug":"three-wizards"}' \
  http://localhost:8080/api/admin/role-grants/revoke-mod
```

## Seeded users (out of the box)

The Casdoor template ships seed users, so you can test multiplayer and RBAC without manual grants:

| User | Casdoor roles | Postgres scope rows |
|---|---|---|
| `mod-three-wizards` | `mod` | `mod_game_scopes` for `three-wizards` |
| `mod-dodecahedron` | `mod` | `mod_game_scopes` for `dodecahedron` |
| `publisher-three-wizards` | `publisher` | `publisher_game_scopes` for `three-wizards` |
| `publisher-dodecahedron` | `publisher` | `publisher_game_scopes` for `dodecahedron` |
| `publisher-flatland` | `publisher` | `publisher_game_scopes` for `flatland-rpg` and `flatland-vn` |
| `mod`, `publisher` | `mod`, `publisher` | none, so they hold the role but no game |

`casdoor/entrypoint.sh` prints the seed passwords on first boot, as it does for `admin` and `player`. Under `make e2e` they all share the per-run `E2E_PASSWORD`.

## See also

- [Decision 011 — Mod / publisher with per-game scoping](../decisions/011-mod-publisher-rbac.md)
- [Decision 009 — Three additive scopes for game_access](../decisions/009-game-access-scopes.md)
- [Administration manual](../guides/administration.md)
