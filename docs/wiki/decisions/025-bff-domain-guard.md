# Decision 025 — BFF-side per-instance domain guard

**Status**: Accepted 2026-06-12. Landed in `feat/domain-foundation` as the first consumer of the `DOMAIN_<x>` authority that branch introduced.

## Context

The `domain-foundation` work (same branch) taught the games BFF to derive a tenant the way hugmesh-gateway does:

- [`DomainRegistry`](../../../backend/src/main/java/games/hugmesh/bff/security/DomainRegistry.java) maps a cryptographically validated token **issuer** to a brand/domain (one Casdoor per brand, so each brand has a distinct `iss`).
- [`CasdoorAuthorities`](../../../backend/src/main/java/games/hugmesh/bff/config/CasdoorAuthorities.java) stamps exactly one issuer-derived `DOMAIN_<x>` authority on every token. A reserved-prefix guard stops a `permissions:["DOMAIN_other"]` entry from forging a tenant.
- [`Roles`](../../../backend/src/main/java/games/hugmesh/bff/security/Roles.java) gained `isSuperadmin` and a fail-closed `domainOf`, both aligned with the gateway.

That branch stopped at the seam. Its own Javadoc says *"The games BFF currently has no per-domain request-scoped data, so no `@PreAuthorize`/query DOMAIN predicate is wired."* The authority was **stamped but never read**. Nothing enforced it.

The gateway does enforce it: `GatewayRouteAccessFilter` (in hugmesh-gateway, `net.hugmesh.gateway.filter`) denies (403) a non-superadmin principal whose `DOMAIN_<x>` differs from the request's target brand (derived from the `Host` header), *before* the route-access grant layer.

The games README documents a standing defence-in-depth posture: the gateway is the primary enforcement point, but **the BFF re-runs the gateway's checks** (revocation, rate-limit), so that *"service-to-service traffic on the compose network that bypasses the gateway edge is still gated at the application layer."* The domain check was the one gateway control the BFF had the inputs for (`DOMAIN_<x>` is now on the token) but did not re-run. A request that reached the BFF directly on the compose network, skipping the gateway, could present a foreign-brand Casdoor token and pass the absent edge check.

## Decision

Add [`DomainGuardFilter`](../../../backend/src/main/java/games/hugmesh/bff/config/DomainGuardFilter.java), a reactive `WebFilter` that re-runs the gateway's domain deny at the BFF.

### Activation

One new property, `app.tenancy.domain` (env `APP_TENANCY_DOMAIN`), names the brand **this BFF instance** serves and switches the guard on:

- **Unset (the default; the games realm has one Casdoor today)** → `filter()` returns `chain.filter(exchange)` at once. It is a complete no-op, and the request path is byte-for-byte unchanged. The guard stays inert until a deployment opts into per-brand isolation.
- **Set (e.g. `brandx`)** → the decision table below runs.

### Decision table (only when `app.tenancy.domain` is set)

| Principal | Outcome | Why |
| --- | --- | --- |
| No authenticated JWT (anonymous / public endpoint) | **pass** | No token to scope; public catalog reads stay public. The gateway likewise checks only authenticated `Jwt` principals. |
| `SUPERADMIN` | **pass** | The ONLY cross-domain bypass: the infra control plane reaches every brand. Checked *before* the match, so it need not carry the instance's `DOMAIN_`. |
| `DOMAIN_<instance>` matches | **pass** | In-brand. |
| Foreign brand, legacy `DOMAIN_default` at a branded instance, or fail-closed `null` (0 or >1 `DOMAIN_` authorities — the injection signature) | **403** + `X-Domain-Denied: <instance>` | Tenant violation / unprovable tenant. |

The match contract (`DomainGuardFilter.domainMatches`) is copied from the gateway: a `null` principal domain fails closed, `default`/`default` is the single-tenant exemption, anything else needs exact equality.

### Ordering

`@Order(SECURITY_CHAIN_ORDER + 40)`, where `SECURITY_CHAIN_ORDER = -100` is Spring Security's `WebFilterChainProxy` order, so the filter runs at `-60`, after the security context is populated. It sits between `RateLimitFilter` (`-70`) and [`BanGateFilter`](../../../backend/src/main/java/games/hugmesh/bff/config/BanGateFilter.java) (`-50`), so a foreign-brand token is rejected as a tenant violation *before* any per-user ban lookup. That deny is cheaper and more basic, and it never touches the `AdminActionService` cache. Both filters read the JWT from the `ReactiveSecurityContextHolder` that Spring Security's authentication filter fills.

## Why a BFF copy of a gateway check (and not just trust the gateway)

The platform's primary isolation is **per-brand instances**: brand-X's gateway and BFF are their own deployment, and the gateway denies at the edge. The BFF copy is defence-in-depth for the one path the gateway cannot cover: a caller that reaches the BFF *without* passing the gateway (compose-network service-to-service traffic, a misrouted ingress, a debug port-forward). The BFF already re-checks revocation and rate limits for the same reason.

It does **not** replace the gateway. The gateway derives the target brand from the validated `Host` suffix, so it scopes by where the request landed. The BFF instance knows its own brand from config. The two complement each other: Host-derived at the edge, instance-derived at the service.

## What this does NOT do (deferred)

- **No per-row catalog partitioning.** (Done since: [decision 026](026-games-catalog-domain-partitioning.md) added `games.domain`.) When this decision was made, `games`, `chapters` and `federation_modules` had no domain column. In the per-brand-instance model each brand runs its own BFF on its own data, so the catalog is already physically partitioned. A `domain` column and query predicate matter only if one BFF serves several brands from one database. That work should mirror the gateway's `GatewayRouteAccessService.domainApplies` (a `null`/`default` row is global; a branded row matches only its brand; SUPERADMIN sees all).
- **No public-endpoint brand derivation.** Anonymous public reads are not brand-scoped; they pass through. A brand-partitioned *public* catalog needs the gateway to forward the Host-derived brand to the BFF (e.g. an `X-Domain` header). It did not then. The gateway can now stamp `X-Domain` behind `app.domain-header.forward` (default `false`), but the BFF does not read the header.

## Alternatives considered

- **`@PreAuthorize` per controller.** Rejected. It needs a custom SpEL expression on every endpoint and silently misses any new controller. One early `WebFilter` fails safe: a new endpoint is covered the moment it exists.
- **Trust the gateway alone.** Rejected. It contradicts the compose-network-bypass posture the BFF already keeps for revocation and rate limits.

## Verification

[`DomainGuardFilterTest`](../../../backend/src/test/java/games/hugmesh/bff/config/DomainGuardFilterTest.java) — 16 specs along the project taxonomy: single-tenant no-op (`Disabled`), one-principal match, mismatch, legacy-default and anonymous (`UserSituational`), superadmin cross-domain bypass (`MultiUserSituational`), the ALLOW+DENY isolation contract at one instance (`CrossUserIsolation`), fail-closed on 0 or more than 1 `DOMAIN_` authority (`FailClosed`), and stateless routing without cross-talk under 16-thread load (`Concurrent`).

A full multi-brand **e2e** waits on a second Casdoor issuer in the integration-test harness (the `make e2e` stack runs one Casdoor). Until then the unit suite pins the filter logic, where every security decision lives. The whole existing e2e suite runs with `app.tenancy.domain` unset and so exercises the single-tenant default.
