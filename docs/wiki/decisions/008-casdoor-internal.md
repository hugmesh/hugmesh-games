# 008 — Casdoor never exposed; BFF reverse-proxies the login flow

**Status:** Adopted.

## Decision

Casdoor is reachable **only from inside the compose network**. The host BFF exposes a thin reverse-proxy at `/api/auth/*` that handles the entire login flow on the player's behalf. Casdoor itself never gets a published port.

## Why

Two reasons.

1. **Admin surface area.** Casdoor's admin UI is part of the same package as the user-facing auth endpoint. Exposing the auth endpoint exposes the admin UI; gating the admin UI per-route in Casdoor is doable but error-prone. Putting Casdoor entirely behind the BFF makes the public surface explicit (`/api/auth/token`, `/api/auth/me`) and rejects everything else.
2. **JWKS endpoint disambiguation.** Casdoor has two JWKS endpoints: `/.well-known/jwks` (anonymous) and `/api/get-jwks` (admin-auth-required). The Spring resource-server uses the public one. With Casdoor private, there's no risk of a misconfigured proxy accidentally fronting the admin variant.

## How to apply

- **Compose:** Casdoor service has no `ports:` block. Only the network-internal port is reachable.
- **BFF:** `/api/auth/token` proxies the OAuth2 code-exchange to Casdoor's token endpoint. `/api/auth/me` resolves the bearer token to a Casdoor user.
- **JWT verification:** `spring.security.oauth2.resourceserver.jwt.jwk-set-uri` points at `http://casdoor:8000/.well-known/jwks` — the anonymous variant on the compose network.
- **Role catalog (`/api/admin/roles`):** also a server-side proxy; hits Casdoor's admin API with a server-held client secret so the browser never sees Casdoor's admin credentials.

## Trade-offs accepted

- **One more service to keep healthy.** If Casdoor is down, login is down. Mitigated by Casdoor's ~5-second cold start and by the fact that already-issued JWTs continue working until expiry — only new logins / refreshes break.
- **Casdoor admin UI requires `docker compose exec` or port-forwarding to access.** That's a feature.

## Reference

- [02 — Runtime architecture](../architecture/02-runtime.md#authentication-and-access)
- [Administration manual](../guides/administration.md)
