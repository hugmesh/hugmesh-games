# Decision 026 — Per-brand games-catalog domain partitioning

**Status**: Accepted 2026-06-12. Landed in `feat/domain-foundation`, building on [decision 025](025-bff-domain-guard.md).

## Context

[Decision 025](025-bff-domain-guard.md) added the `DomainGuardFilter`, a request-level, per-INSTANCE brand guard: it rejects a token whose `DOMAIN_<x>` differs from this BFF's configured brand. That covers the per-brand-instance topology, where each brand runs its own BFF on its own database. It deferred **per-row catalog partitioning**, which it called "only needed if a single BFF ever serves multiple brands from one database… should mirror the gateway's `GatewayRouteAccessService.domainApplies`."

This decision builds that piece. The games catalog records which brand owns each game, and the management surfaces enforce caller brand == game brand. Two reasons apply even before a shared-DB deployment exists:

- **Defence-in-depth** for the later single-BFF, multi-brand mode. The gateway scopes by Host at the edge; the catalog should also refuse a cross-brand mutation at the data layer.
- **Provenance.** Every uploaded game records the brand that shipped it, so a multi-brand admin console can filter and attribute by brand without guessing.

## Decision

### 1. `games.domain` column (migration 043)

`games` gains `domain VARCHAR(64) NOT NULL DEFAULT 'default'` (+ `ix_games_domain`). This is the 012-6 umbrella table, *not* the original 001 table, which was renamed to `chapters` long ago. It is backward compatible by construction: every existing row backfills to `'default'`, and the predicate below treats `'default'` (like `null` or blank) as UNSCOPED/global. The changeset is idempotent (`<preConditions onFail="MARK_RAN">`).

Only `games` carries the column. `chapters` take their brand through the `game_id` FK, `game_access` through `game_id`, and `federation_modules` through `game_slug`. `games.domain` is the single source of truth.

### 2. `Roles.domainApplies(rowDomain, callerDomain, isSuperadmin)` — the predicate

The logic is copied from hugmesh-gateway `GatewayRouteAccessService.domainApplies`, with the gateway's SUPERADMIN short-circuit folded in:

```
isSuperadmin                                   -> true   (cross-domain infra plane)
rowDomain null / blank / "default" (ci, trim)  -> true   (UNSCOPED, back-compat)
else: rowDomain.equalsIgnoreCase(callerDomain) (both trimmed); null caller -> false (fail-closed)
```

The match is case-INSENSITIVE here because a row domain may be hand-seeded. That differs on purpose from the case-SENSITIVE principal-vs-instance match in `DomainGuardFilter.domainMatches`, where both inputs are registry-canonical. The gateway keeps the same asymmetry.

### 3. `DomainScopeService.requireGameDomain(slug, callerDomain, isSuperadmin)` — the gate

A `@Service` modelled on `PublisherService.requireOwns` / `ModerationService.requireScope`. It looks up the game by slug, completes empty on allow, and errors `403` on a cross-brand mismatch. An unknown slug completes empty: there is no domain to enforce, and the downstream `404` surfaces. SUPERADMIN short-circuits before the lookup. **No config flag.** The data (`'default'` rows and `'default'` callers) makes it inert in a single-tenant setup. It still fires correctly in a multi-issuer, shared-BFF deployment where `app.tenancy.domain` is unset but games carry real brands. Controllers resolve `callerDomain` / `isSuperadmin` from the JWT (`Roles.domainOf(jwt, registry)` / `Roles.isSuperadmin(jwt)`) and pass primitives in. Services never read the security context.

### 4. Upload stamping (server-controlled, untrusted-SQL-proof)

`GameUploadService.install` passes the uploader's brand down. `writeAndRegister` stamps it with `UPDATE games SET domain = :domain WHERE slug = :slug` **inside the install transaction, AFTER `register.sql` runs**. The stamp is server-controlled and keyed by slug, so a hand-crafted `register.sql` cannot claim a foreign brand: whatever the uploaded SQL sets, the trusted stamp overwrites it in the same transaction, and both roll back together on any failure. The brand is the uploader's issuer-derived domain (`Roles.domainOf(jwt, registry)`), never a claim the user can set. `GameUploadController` **fails closed (403)** if the resolved domain is `null`: that is an untrusted issuer in multi-brand mode, which the decoder already 401s upstream and which is denied here as a second line.

**First-writer-wins.** The stamp fires **only when the game is newly created**. `writeAndRegister` checks whether the `games` row existed *before* `register.sql` ran (a consistent in-transaction `SELECT`), and a re-upload PRESERVES the existing domain. The adversarial review found the gap this closes: without it, a brand-X re-upload of an existing `'default'` (global) title would silently stamp it brand-X-private and remove it from every other brand (`requireGameDomain` passes a `'default'` game because it is unscoped). A brand's ownership is fixed at creation. *Moving* a game's domain is reserved for a future superadmin operation and is never a side effect of upload.

`GameUploadController` also adds a **re-upload brand guard** to the post-stage `requireScope` hook: `requireOwns` first (publishers; admins bypass), **then** `requireGameDomain`. A brand-X admin cannot overwrite an existing brand-Y title. A brand-new slug passes and is stamped with the uploader's brand. SUPERADMIN bypasses.

### 5. Enforcement surface (this increment)

`GameAccessController.list` / `grant` / `revoke` (the canonical per-game admin surface) are gated by `requireGameDomain`: a brand-X admin cannot read, grant or revoke access policy on a brand-Y game. The upload and re-upload guard above is the other gate.

## Extended to the remaining management surfaces (follow-on, landed)

The deferred admin-bypass branches are gated too. The pattern is the same, at the
controller level, with services unchanged:

- **`PublisherController`** (`grantMod` / `revokeMod` / `modsForGame`) — `requireGameDomain` on the request/path `gameSlug`.
- **`ModerationController`** (`stopSession` / `kickPlayer`) — `DomainScopeService.requireSessionDomain(sessionId, …)` resolves `sessionId → game_slug → game.domain` (the target is a session id, not a slug).
- **`GameLaunchController`** — `launch` gates on the path slug, before the per-user `game_access` visibility check. `adminRevoke` gained an `@AuthenticationPrincipal Jwt` param and gates on the loaded launch row's `gameSlug`. `terminate()` is **left ungated** on purpose: it is already self-scoped by casdoor id, so a player can only terminate their own launch, including a cross-brand launch of a `'default'` game.

Three more controllers have been gated since, with the same helpers:

- **`RoleAdminController`** (`/api/admin/role-grants`: `grant-publisher`, `revoke-publisher`, `grant-mod`, `revoke-mod`) — `requireGameDomain` on the request's `gameSlug`.
- **`RealtimeController`** (`/api/realtime`: `start`, `input`, `stream`, `log`) and **`AdminRealtimeController`** (`/api/admin/realtime/{sessionId}/recover`) — `requireSessionDomain`.

Two reactive details: (1) The gated service calls are wrapped in `Mono.defer` / `Flux.defer`, so a *denied* request assembles no side work. The gate is a clean short-circuit, not just an unsubscribed branch. (2) `requireSessionDomain` mirrors `requireGameDomain` exactly: superadmin bypasses without a lookup, an unknown id passes through empty, a null caller fails closed. The change was adversarially reviewed. The two confirmed findings (regression insurance for `terminate()`'s ungated self-scope, and `modsForGame`'s `Flux.defer` ALLOW path) are covered by `GameLaunchControllerDomainScopeTest.Terminate` and `PublisherControllerDomainScopeTest.modsForGameAllowsOwnBrandAndEmitsRows`.

## What this does NOT do (deferred follow-on)

- **Public/anonymous catalog reads** (`GameController.list/get`, `ChapterController.*`, `FederationController.manifest`) stay global. There is no JWT to scope by, and a brand-partitioned *public* catalog needs the gateway to forward a Host-derived brand (e.g. `X-Domain`). The gateway can now stamp that header, but only when `app.domain-header.forward` is on (default `false`), and the BFF does not read it. Same boundary [decision 025](025-bff-domain-guard.md) drew.

## Authenticated read-list filtering (follow-on, landed)

Result-set filters now narrow the *authenticated* catalog and launch reads by brand. This is a **filter**, never a hard deny; it only narrows what `game_access` and visibility already allowed:

- **`GameController.available` + `CharacterController` dashboard** → `ProgressionService.availableGamesWithLevels(…, callerDomain, isSuperadmin)` filters the catalog by `Roles.domainApplies(game.getDomain(), …)` on top of the existing `game_access` intersection. A branded player sees only their brand's games plus globals. Anonymous resolves to `default` (globals only). SUPERADMIN sees all. Single-tenant setups are unaffected.
- **`GameLaunchController.activeLaunches`** (admin dashboard) → resolves each launch's game domain by slug and keeps only the caller-brand and global launches. SUPERADMIN short-circuits without a lookup.

The filter lives in the service and collection pipeline, the only place `Game.domain` is in hand (`GameWithChaptersDto` does not carry it, on purpose). Covered by `ProgressionServiceTest.brandFilterHidesForeignBrandGamesButKeepsGlobals` + `GameLaunchControllerDomainScopeTest.ActiveLaunches`. Only the **public/anonymous** catalog reads above remain global (they need the BFF to read a gateway `X-Domain`).

## Verification

- `DomainScopeServiceTest` — full taxonomy (single-tenant no-op / user / superadmin bypass / cross-user ALLOW+DENY / fail-closed / 16-thread concurrent).
- `GameAccessControllerDomainScopeTest` — controller-level proof that a brand-X admin is 403'd on a brand-Y game's list/grant/revoke (and never saves/deletes), superadmin bypasses, single-tenant is inert.
- `RolesDomainScopingTest.DomainApplies` — the predicate's UNSCOPED set + case-insensitive scoped match + null fail-closed.
- `GameUploadIntegrationTest` (Docker) — the no-domain path stamps `'default'`, and an explicit-brand install stamps that brand **independent of `register.sql`** (the fixture never sets domain).
- The entire existing suite runs with no tenancy configured, exercising the single-tenant no-op end to end.
