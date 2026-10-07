# Decision 025 — BFF-side per-instance domain guard

**Status**: Accepted 2026-06-12. Lands in `feat/domain-foundation` as the first consumer of the `DOMAIN_<x>` authority that branch introduced.

## Context

The `domain-foundation` work (same branch) taught the games BFF to derive a tenant the same way hugmesh-gateway does:

- [`DomainRegistry`](../../../backend/src/main/java/games/hugmesh/bff/security/DomainRegistry.java) maps a cryptographically-validated token **issuer** → brand/domain (one Casdoor per brand ⇒ distinct `iss`).
- [`CasdoorAuthorities`](../../../backend/src/main/java/games/hugmesh/bff/config/CasdoorAuthorities.java) stamps exactly one issuer-derived `DOMAIN_<x>` authority on every token, with a reserved-prefix guard so a `permissions:["DOMAIN_other"]` entry can't forge a tenant.
- [`Roles`](../../../backend/src/main/java/games/hugmesh/bff/security/Roles.java) gained the gateway-aligned `isSuperadmin` / fail-closed `domainOf` helpers.

That branch deliberately stopped at the seam — its own Javadoc says *"The games BFF currently has no per-domain request-scoped data, so no `@PreAuthorize`/query DOMAIN predicate is wired."* So the authority was **stamped but never read**. Nothing enforced it.

Meanwhile the gateway already does enforce it: `GatewayRouteAccessFilter` (in hugmesh-gateway, `net.hugmesh.gateway.filter`) hard-denies (403) a non-superadmin principal whose `DOMAIN_<x>` ≠ the request's target brand (derived from the `Host` header), *before* the route-access grant layer.

The games README documents a standing defence-in-depth posture: the gateway is the primary enforcement point, but **the BFF re-runs the gateway's checks** (revocation, rate-limit) so that *"service-to-service traffic on the compose network that bypasses the gateway edge is still gated at the application layer."* The domain check was the one gateway control the BFF had the inputs for (`DOMAIN_<x>` is now on the token) but did not re-run. A request reaching the BFF directly on the compose network — skipping the gateway — could present a foreign-brand Casdoor token and sail past the (absent) edge domain check.

## Decision

Add [`DomainGuardFilter`](../../../backend/src/main/java/games/hugmesh/bff/config/DomainGuardFilter.java) — a reactive `WebFilter` that re-runs the gateway's domain hard-deny at the BFF.

### Activation

Gated on a single new property, `app.tenancy.domain` (env `APP_TENANCY_DOMAIN`), naming the brand **this BFF instance** serves:

- **Unset (default, single-Casdoor games realm today)** → `filter()` returns `chain.filter(exchange)` immediately. A complete no-op; the request path is byte-for-byte unchanged. The guard is inert until a deployment opts into per-brand isolation.
- **Set (e.g. `brandx`)** → the decision table below runs.

### Decision table (only when `app.tenancy.domain` is set)

| Principal | Outcome | Why |
| --- | --- | --- |
| No authenticated JWT (anonymous / public endpoint) | **pass** | No token to scope; public catalog reads stay public. Mirrors the gateway, which only domain-checks authenticated `Jwt` principals. |
| `SUPERADMIN` | **pass** | The ONLY cross-domain bypass — the infra control plane reaches every brand. Checked *before* the match so it needn't carry the instance's `DOMAIN_`. |
| `DOMAIN_<instance>` matches | **pass** | In-brand. |
| Foreign brand, legacy `DOMAIN_default` at a branded instance, or fail-closed `null` (0 or >1 `DOMAIN_` authorities — the injection signature) | **403** + `X-Domain-Denied: <instance>` | Tenant violation / unprovable tenant. |

The match contract (`DomainGuardFilter.domainMatches`) is copied verbatim from the gateway: a `null` principal domain fails closed, `default`/`default` is the single-tenant exemption, otherwise exact equality.

### Ordering

`@Order(SECURITY_CHAIN_ORDER + 40)`, where `SECURITY_CHAIN_ORDER = -100` is Spring Security's `WebFilterChainProxy` order, so the filter runs at `-60`, after the security context is populated. It sits between `RateLimitFilter` (`-70`) and [`BanGateFilter`](../../../backend/src/main/java/games/hugmesh/bff/config/BanGateFilter.java) (`-50`), so a foreign-brand token is rejected as a tenant violation *before* any per-user ban lookup (a cheaper, more fundamental deny that never touches the `AdminActionService` cache). Both read the JWT from the reactive `ReactiveSecurityContextHolder` Spring Security's authentication filter populates.

## Why a BFF copy of a gateway check (and not just trust the gateway)

The platform's primary isolation is **per-brand instances**: brand-X's gateway + BFF are their own deployment, and the gateway hard-denies at the edge. The BFF copy is strictly defence-in-depth for the one path the gateway can't cover — a caller that reaches the BFF *without* traversing the gateway (compose-network service-to-service traffic, a misrouted ingress, a debug port-forward). This is the identical rationale the BFF already applies to revocation and rate-limit re-checks.

It is **not** a substitute for the gateway: the gateway derives the target brand from the validated `Host` suffix (so it can scope by where the request landed), whereas the BFF instance knows its own brand from config. The two are complementary — Host-derived at the edge, instance-derived at the service.

## What this does NOT do (deferred)

- **No per-row catalog partitioning.** (Done since: [decision 026](026-games-catalog-domain-partitioning.md) added `games.domain`.) At the time of this decision `games` / `chapters` / `federation_modules` were not domain-columned. In the per-brand-instance model each brand runs its own BFF against its own data, so the catalog is already physically partitioned; a `domain` column + query predicate is only needed if a single BFF ever serves multiple brands from one database. When that lands it should mirror the gateway's `GatewayRouteAccessService.domainApplies` (a `null`/`default` row is global; a branded row matches only its brand; SUPERADMIN sees all).
- **No public-endpoint brand derivation.** Anonymous public reads are not brand-scoped (they pass through). A brand-partitioned *public* catalog would require the gateway to forward the Host-derived brand to the BFF (e.g. an `X-Domain` header); it did not do so by default. Since then the gateway can stamp `X-Domain` behind `app.domain-header.forward` (default `false`), but the BFF does not read the header.

## Alternatives considered

- **`@PreAuthorize` per controller.** Rejected — it would need a custom SpEL expression on every endpoint and silently miss any new controller. A single early `WebFilter` is fail-safe by default: a new endpoint is covered the moment it's added.
- **Trust the gateway alone.** Rejected — contradicts the documented compose-network-bypass posture the BFF already honours for revocation + rate-limit.

## Verification

[`DomainGuardFilterTest`](../../../backend/src/test/java/games/hugmesh/bff/config/DomainGuardFilterTest.java) — 16 specs along the project taxonomy: single-tenant no-op (`Disabled`), one-principal match/mismatch/legacy-default/anonymous (`UserSituational`), superadmin cross-domain bypass (`MultiUserSituational`), the ALLOW+DENY isolation contract at one instance (`CrossUserIsolation`), fail-closed on 0/>1 `DOMAIN_` authorities (`FailClosed`), and stateless no-cross-talk routing under 16-thread load (`Concurrent`).

A full multi-brand **e2e** is gated on a second Casdoor issuer in the integration-test harness (the current `make e2e` stack runs a single Casdoor). Until then the filter logic — where every security decision lives — is pinned by the unit suite, and the single-tenant default is exercised by the entire existing e2e suite running with `app.tenancy.domain` unset.
