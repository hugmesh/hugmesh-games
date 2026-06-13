# Decision 026 — Per-brand games-catalog domain partitioning

**Status**: Accepted 2026-06-12. Lands in `feat/domain-foundation`, building on [decision 025](025-bff-domain-guard.md).

## Context

[Decision 025](025-bff-domain-guard.md) added the `DomainGuardFilter` — a request-level, per-INSTANCE brand guard (reject a token whose `DOMAIN_<x>` ≠ this BFF's configured brand). That covers the per-brand-instance topology, where each brand runs its own BFF against its own database. It explicitly deferred **per-row catalog partitioning**, noting it is "only needed if a single BFF ever serves multiple brands from one database… should mirror the gateway's `GatewayRouteAccessService.domainApplies`."

This decision implements that deferred piece: the games catalog itself learns which brand owns each game, and the management surfaces enforce caller-brand == game-brand. Two scenarios motivate it even before a shared-DB deployment exists:

- **Defence-in-depth** for the eventual single-BFF-multi-brand mode (the gateway scopes by Host at the edge; the catalog should also refuse a cross-brand mutation at the data layer).
- **Provenance**: every uploaded game records the brand that shipped it, so a multi-brand admin console can filter/attribute by brand without guessing.

## Decision

### 1. `games.domain` column (migration 043)

`games` gains `domain VARCHAR(64) NOT NULL DEFAULT 'default'` (+ `ix_games_domain`). The 012-6 umbrella table — *not* the original 001 table, which was long ago renamed to `chapters`. Back-compat by construction: every existing row backfills to `'default'`, and `'default'` (like `null`/blank) is treated as UNSCOPED/global by the predicate below. Idempotent `<preConditions onFail="MARK_RAN">`.

Only `games` carries the column. `chapters` derive their brand through the `game_id` FK; `game_access` through `game_id`; `federation_modules` through `game_slug`. `games.domain` is the single source of truth.

### 2. `Roles.domainApplies(rowDomain, callerDomain, isSuperadmin)` — the predicate

Copied (logic) from snapdragon-gateway `GatewayRouteAccessService.domainApplies`, with the gateway's SUPERADMIN short-circuit folded in:

```
isSuperadmin                                   -> true   (cross-domain infra plane)
rowDomain null / blank / "default" (ci, trim)  -> true   (UNSCOPED, back-compat)
else: rowDomain.equalsIgnoreCase(callerDomain) (both trimmed); null caller -> false (fail-closed)
```

Case-INSENSITIVE here (a row domain may be hand-seeded), deliberately distinct from the case-SENSITIVE principal-vs-instance match in `DomainGuardFilter.domainMatches` (both inputs there are registry-canonical) — the same asymmetry the gateway keeps.

### 3. `DomainScopeService.requireGameDomain(slug, callerDomain, isSuperadmin)` — the gate

A `@Service` mirroring `PublisherService.requireOwns` / `ModerationService.requireScope`: looks up the game by slug, completes empty on allow, errors `403` on a cross-brand mismatch. An unknown slug completes empty (no domain to enforce — the downstream `404` surfaces). SUPERADMIN short-circuits before the lookup. **No config flag**: the data (`'default'` rows + `'default'` callers) makes it inert in single-tenant, and it still fires correctly in a multi-issuer-shared-BFF deployment where `app.tenancy.domain` is unset but games carry real brands. Controllers resolve `callerDomain`/`isSuperadmin` from the JWT (`Roles.domainOf(jwt, registry)` / `Roles.isSuperadmin(jwt)`) and pass primitives in — services never read the security context.

### 4. Upload stamping (server-controlled, untrusted-SQL-proof)

`GameUploadService.install` threads the uploader's brand; `writeAndRegister` stamps it via `UPDATE games SET domain = :domain WHERE slug = :slug` **inside the install transaction, AFTER `register.sql` runs**. Because the stamp is server-controlled and keyed by slug, a hand-crafted `register.sql` cannot claim a foreign brand — whatever the uploaded SQL sets, the trusted stamp overwrites it in the same transaction (rolls back together on any failure). The brand is the uploader's issuer-derived domain (`Roles.domainOf(jwt, registry)`), never a user-settable claim. `GameUploadController` **fails closed (403)** if the resolved domain is `null` (an untrusted issuer in multi-brand mode — already 401'd upstream by the decoder, denied here defensively).

**First-writer-wins.** The stamp fires **only when the game is newly created** — `writeAndRegister` reads whether the `games` row existed *before* `register.sql` ran (a consistent in-transaction `SELECT`), and a re-upload PRESERVES the existing domain. This closes a gap the adversarial review surfaced: without it, a brand-X re-upload of an existing `'default'` (global) title would silently stamp it brand-X-private, removing it from every other brand (the `requireGameDomain` guard passes a `'default'` game because it is unscoped). A brand's ownership is therefore fixed at creation; *moving* a game's domain is reserved for a future superadmin operation, never a side effect of upload.

`GameUploadController` also composes a **re-upload brand guard** into the existing post-stage `requireScope` hook: `requireOwns` (publishers; admins bypass) **then** `requireGameDomain` (a brand-X admin cannot overwrite an existing brand-Y title; a brand-new slug passes and is stamped with the uploader's brand; SUPERADMIN bypasses).

### 5. Enforcement surface (this increment)

`GameAccessController.list` / `grant` / `revoke` (the canonical per-game admin management surface) are gated by `requireGameDomain` — a brand-X admin cannot read, grant, or revoke access policy on a brand-Y game. Plus the upload write/re-upload guard above.

## Extended to the remaining management surfaces (follow-on, landed)

The deferred admin-bypass branches are now gated too — same controller-level pattern,
services unchanged:

- **`PublisherController`** (`grantMod` / `revokeMod` / `modsForGame`) — `requireGameDomain` on the request/path `gameSlug`.
- **`ModerationController`** (`stopSession` / `kickPlayer`) — `DomainScopeService.requireSessionDomain(sessionId, …)` resolves `sessionId → game_slug → game.domain` (the target is a session id, not a slug).
- **`GameLaunchController`** — `launch` gates on the path slug (before the per-user `game_access` visibility check); `adminRevoke` gained an `@AuthenticationPrincipal Jwt` param and gates on the loaded launch row's `gameSlug`. `terminate()` is deliberately **left ungated** — it is already self-scoped by casdoor id (a player can only ever terminate their own launch, including cross-brand for a `'default'` game).

Two reactive details: (1) the gated service calls are wrapped in `Mono.defer`/`Flux.defer` so a *denied* request assembles no side work (the gate is a clean short-circuit, not just an unsubscribed branch); (2) `requireSessionDomain` mirrors `requireGameDomain` exactly (superadmin no-lookup bypass, unknown id → empty pass-through, null caller fail-closed). Adversarially reviewed; the two confirmed findings (regression-insurance for `terminate()`'s ungated self-scope + `modsForGame`'s `Flux.defer` ALLOW path) are covered by `GameLaunchControllerDomainScopeTest.Terminate` and `PublisherControllerDomainScopeTest.modsForGameAllowsOwnBrandAndEmitsRows`.

## What this does NOT do (deferred follow-on)

- **Public/anonymous catalog reads** (`GameController.list/get`, `ChapterController.*`, `FederationController.manifest`) stay global — there is no JWT to scope by, and a brand-partitioned *public* catalog needs the gateway to forward a Host-derived brand (e.g. `X-Domain`), which it does not today. Same boundary [decision 025](025-bff-domain-guard.md) drew.

## Authenticated read-list filtering (follow-on, landed)

The deferred result-set filters now narrow the *authenticated* catalog/launch reads by brand — a **filter**, never a hard-deny (it only narrows what `game_access`/visibility already allowed):

- **`GameController.available` + `CharacterController` dashboard** → `ProgressionService.availableGamesWithLevels(…, callerDomain, isSuperadmin)` filters the catalog by `Roles.domainApplies(game.getDomain(), …)` on top of the existing `game_access` intersection. A branded player sees only their brand's games (+ globals); anonymous resolves to `default` (globals only); SUPERADMIN sees all; single-tenant inert.
- **`GameLaunchController.activeLaunches`** (admin dashboard) → resolves each launch's game domain by slug and keeps only the caller-brand (+ global) launches; SUPERADMIN short-circuits without a lookup.

The filter lives in the service / collection pipeline — the only place the `Game.domain` is in hand (`GameWithChaptersDto` deliberately does not carry it). Covered by `ProgressionServiceTest.brandFilterHidesForeignBrandGamesButKeepsGlobals` + `GameLaunchControllerDomainScopeTest.ActiveLaunches`. Only the **public/anonymous** catalog reads above remain global (gated on a gateway `X-Domain`).

## Verification

- `DomainScopeServiceTest` — full taxonomy (single-tenant no-op / user / superadmin bypass / cross-user ALLOW+DENY / fail-closed / 16-thread concurrent).
- `GameAccessControllerDomainScopeTest` — controller-level proof that a brand-X admin is 403'd on a brand-Y game's list/grant/revoke (and never saves/deletes), superadmin bypasses, single-tenant is inert.
- `RolesDomainScopingTest.DomainApplies` — the predicate's UNSCOPED set + case-insensitive scoped match + null fail-closed.
- `GameUploadIntegrationTest` (Docker) — the no-domain path stamps `'default'`, and an explicit-brand install stamps that brand **independent of `register.sql`** (the fixture never sets domain).
- The entire existing suite runs with no tenancy configured, exercising the single-tenant no-op end to end.
