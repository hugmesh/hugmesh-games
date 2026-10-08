# 008 — Casdoor never exposed; BFF reverse-proxies the login flow

**Status:** Adopted.

## Decision

Casdoor is reachable **only from inside the compose network**. The host BFF exposes a thin proxy at `/api/auth/*` that handles the login on the player's behalf. Casdoor itself never gets a published port.

## Why

Two reasons.

1. **Admin surface area.** Casdoor's admin UI is part of the same package as the user-facing auth endpoint. Exposing the auth endpoint exposes the admin UI; gating the admin UI per-route in Casdoor is doable but error-prone. Putting Casdoor entirely behind the BFF makes the public surface explicit: `POST /api/auth/token` is the only `/api/auth/*` route the BFF serves, and nothing else of Casdoor's is reachable.
2. **JWKS endpoint disambiguation.** Casdoor has two JWKS endpoints: `/.well-known/jwks` (anonymous) and `/api/get-jwks` (admin-auth-required). The Spring resource-server uses the public one. With Casdoor private, there's no risk of a misconfigured proxy accidentally fronting the admin variant.

## How to apply

- **Compose:** the `casdoor` service in `docker-compose.yml` has no `ports:` block. Only the network-internal port 8000 is reachable.
- **BFF:** `POST /api/auth/token` (`CasdoorAuthController`) takes `{username, password}` and forwards an OAuth2 password grant to Casdoor's `/api/login/oauth/access_token`. It injects `client_id` and `client_secret` server-side. It is not a code exchange, and there is no `/api/auth/me`.
- **JWT verification:** `spring.security.oauth2.resourceserver.jwt.jwk-set-uri` points at `http://casdoor:8000/.well-known/jwks` — the anonymous variant on the compose network.
- **Role catalog (`/api/admin/roles`):** also a server-side proxy (`RoleProxyController`, admin only). It gets a `client_credentials` token with the server-held client secret and calls Casdoor's `/api/get-roles`, so the browser never sees Casdoor's admin credentials.

## Trade-offs accepted

- **One more service to keep healthy.** If Casdoor is down, login is down. Mitigated by Casdoor's ~5-second cold start and by the fact that already-issued JWTs continue working until expiry — only new logins / refreshes break.
- **Casdoor admin UI requires `docker compose exec` or port-forwarding to access.** That's a feature.

## Reference

- [02 — Runtime architecture](../architecture/02-runtime.md#authentication-and-access)
- [Administration manual](../guides/administration.md)
