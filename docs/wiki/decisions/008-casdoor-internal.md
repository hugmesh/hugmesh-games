# 008 — Casdoor never exposed; BFF reverse-proxies the login flow

**Status:** Adopted.

## Decision

Casdoor is reachable **only from inside the compose network**. The host BFF exposes a thin proxy at `/api/auth/*` that handles the login for the player. Casdoor itself never gets a published port.

## Why

Two reasons.

1. **Admin surface area.** Casdoor's admin UI ships in the same package as the user-facing auth endpoint. Expose the auth endpoint and you expose the admin UI. Gating the admin UI route by route in Casdoor is possible and easy to get wrong. With Casdoor entirely behind the BFF, the public surface is explicit: `POST /api/auth/token` is the only `/api/auth/*` route the BFF serves, and nothing else of Casdoor's is reachable.
2. **Two JWKS endpoints.** Casdoor has `/.well-known/jwks` (anonymous) and `/api/get-jwks` (admin auth required). The Spring resource server uses the public one. With Casdoor private, a misconfigured proxy cannot front the admin variant by accident.

## How to apply

- **Compose:** the `casdoor` service in `docker-compose.yml` has no `ports:` block. Only the network-internal port 8000 is reachable.
- **BFF:** `POST /api/auth/token` (`CasdoorAuthController`) takes `{username, password}` and forwards an OAuth2 password grant to Casdoor's `/api/login/oauth/access_token`. It adds `client_id` and `client_secret` on the server. It is not a code exchange, and there is no `/api/auth/me`.
- **JWT verification:** `spring.security.oauth2.resourceserver.jwt.jwk-set-uri` is `${CASDOOR_ENDPOINT}/.well-known/jwks`, the anonymous variant. Compose sets `CASDOOR_ENDPOINT=http://casdoor:8000`.
- **Role catalog (`/api/admin/roles`):** also a server-side proxy (`RoleProxyController`, admin only). It fetches a `client_credentials` token with the server-held client secret and calls Casdoor's `/api/get-roles`. The browser never sees Casdoor's admin credentials.

## Trade-offs accepted

- **One more service to keep healthy.** If Casdoor is down, login is down. Two things soften this: Casdoor cold-starts in about 5 seconds, and already-issued JWTs keep working until they expire. Only new logins and refreshes break.
- **The Casdoor admin UI needs `docker compose exec` or port-forwarding.** That is a feature.

## Reference

- [02 — Runtime architecture](../architecture/02-runtime.md#authentication-and-access)
- [Administration manual](../guides/administration.md)
