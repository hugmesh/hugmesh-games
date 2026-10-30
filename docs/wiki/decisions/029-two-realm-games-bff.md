# 029. One games BFF, two identity realms

**Status:** Accepted, 2026-10-30.

## Decision

The games service keeps two realms. A games player can sign in with a games-realm
account (Casdoor org `threewizards`) and need not be a mesh user. A mesh user with
`ROLE_GAMES` / `ROLE_GAMES_ADMIN` reaches games through the mesh shell, carrying the
systems Casdoor's token. In the cluster, ONE BFF accepts both.

A role name means different things in each realm, so the BFF reads it per token, from
the token's validated `iss`. It never keys the realm on `owner` or on any other claim.

## What each token gets

| Role in the token | Games realm token | Systems realm token |
|---|---|---|
| `games_admin` | games admin | games admin |
| `admin` | games admin | nothing (it is the PLATFORM admin) |
| `mesh-games-admin` | games admin (the GM sync owns this role) | nothing |
| `superadmin` alone | not a games admin | not a games admin |
| `mod`, `publisher`, `producer` | that capability | nothing (dropped) |
| `games_mod`, `games_publisher`, `games_producer` | nothing | that capability |

Whether a mesh user may play at all (`ROLE_GAMES`) is the fe-gateway's route-access
rule; the BFF has no check of its own, as before.

## Configuration

A BFF on one issuer needs none of this. `casdoor.organization` still says which realm
it is, and `CASDOOR_ISSUER_URI`, when set, makes it reject any other `iss`.

| Env | Meaning | Cluster value |
|---|---|---|
| `APP_TENANCY_ISSUERS` | issuer=domain CSV. Map both to the SAME domain, or uploads get stamped differently and each realm's admins lose the other's games | `<games issuer>=default,<systems issuer>=default` |
| `APP_TENANCY_JWKS_URIS` | issuer=jwksUri CSV, in-cluster URLs. A trusted issuer with no entry stops the BFF at boot | `<games issuer>=http://casdoor-games...:8000/.well-known/jwks,<systems issuer>=http://casdoor...:8000/.well-known/jwks` |
| `APP_SECURITY_GAMES_REALM_ISSUERS` | which issuers are the games realm. Required on two issuers: the BFF refuses to boot without it | `<games issuer>` |
| `APP_TENANCY_SYSTEMS_DIRECTORY_*` | optional. `ISSUER`, `ENDPOINT`, `ORGANIZATION`, `CLIENT_ID`, `CLIENT_SECRET`: the systems Casdoor client for username lookups by mesh callers | see below |

The BFF never runs OIDC discovery. Cluster issuers are public hostnames it cannot reach,
and fetching from a URL read out of an unverified token is not something to build on.

Do not set `spring.security.oauth2.resourceserver.jwt.issuer-uri` from an env var with an
empty default. Boot builds a `JwtIssuerValidator` from any non-null value, so an unset
variable makes every token fail `iss == ""`. That is why the setting is
`casdoor.issuer-uri`.

## Identity

One person signing in through both realms is two players, with separate profiles,
characters, friends, bans and scopes. That follows from there being two realms. Linking
them is tracked as its own piece of work, and must never be done by username.

Rows keep the bare `sub`. Casdoor mints random UUIDs, so subjects from the two realms do
not collide in practice.

Usernames do collide, so they are treated as realm-local everywhere:

- `UserDirectoryService` caches `(realm, name)`, and looks a name up in the caller's
  realm only. A name from the other realm reads back as `alice (id.example)`.
- Without `APP_TENANCY_SYSTEMS_DIRECTORY_*`, a mesh caller's lookup is answered from the
  login cache alone, so a name resolves once that user has signed in here. It never falls
  through to the games Casdoor.
- `publisher_game_scopes` rows seeded by Liquibase name games-realm accounts, so only a
  games-realm token matches them by name (`GamesAdminPolicy.seededUsernameOf`).

## `game_access` with `scope='role'`

Values are canonical role names, matched against the raw `roles` claim in both realms
(`Roles.fromJwt`). They only widen what a caller can SEE in the catalog. A game gated on
`play-<slug>` appears for both kinds of player only if that role is seeded in both
Casdoors. The per-realm reading above does not apply here, so `mod` as a `game_access`
value also matches a mesh platform mod. Use a `play-<slug>` value for gating.
