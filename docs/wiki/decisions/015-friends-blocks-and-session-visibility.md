# 015 — Friends, blocks, session visibility, and per-user session log

**Status:** Adopted (Phase 8 of the multiplayer overhaul).

## Decision

Three coupled additions land together:

1. A **session visibility tier** — `public` / `friends` / `private` — on
   `game_sessions`. The default is `public`. The lobby filter joins on
   the friend graph for the `friends` tier and hides `private` sessions
   from everyone but their creator.
2. A **single-table user-relationship graph** in `user_relationships`.
   It holds both directional friend edges (`friend_request` → `friend`)
   and the blocklist (`blocked`). Mutual friendship is two `friend`
   rows. A block is one `blocked` row in one direction (the target
   never knows).
3. A **per-user session participation log**, read straight from the
   existing `session_members` join with `game_sessions`. It has three
   call sites: `/me` (any role), `/admin/{casdoorId}` (admin), and
   `/scoped/{casdoorId}` (mod / publisher, filtered to the caller's
   game scope).

## Why

Players asked to run sessions only with people they know, to silence
those who ruin the lobby, and to see which sessions they had joined.

Two design questions came up:

- **One graph table or two?** Friends and blocks share enough plumbing
  (single-row CRUD, indexed by `(user, other)`) that splitting them
  added surface for skew. One table with a `relationship` enum keeps
  reads cheap. The unique constraint on
  `(user_casdoor_id, other_casdoor_id)` means a block silently
  overrides any earlier friend edge in that direction.
- **Block target gating.** Per the directive, players can block
  *players* and *moderators* but not *publishers* or *admins*. Only
  the publisher half is built: the service rejects publisher targets
  via a `publisher_game_scopes` lookup
  (`UserRelationshipService.block`, 403). Nothing refuses an admin
  target. The admin role lives only in Casdoor (no local table), and
  `UserRelationshipController.block` has no role check, so an admin
  can be blocked today.

## How to apply

- **Liquibase 034** adds `game_sessions.visibility`, the
  `user_relationships` table and its indexes, and an
  `(casdoor_id, joined_at desc)` index on `session_members`. The
  per-user history query is then a single backwards scan.
- **`UserRelationshipService`** owns the graph mutations. `block()`
  removes any existing friend edges first, so a block always wins.
- **`GameSessionService.listOpen(gameSlug, callerCasdoorId)`** loads
  the caller's friend set and blocklist once, then filters the lobby
  source `Flux<GameSession>`. Anonymous (no JWT) callers see only
  public sessions. The blocklist merges both directions
  (`blocklistFor`), so a blocked user also stops seeing the blocker's
  sessions.
- **`SessionLogController`** is the read seam. It is `@PreAuthorize`'d
  at the method level, so non-admin callers can't reach
  `/admin/{casdoorId}`. The `/scoped/{casdoorId}` query intersects the
  caller's mod + publisher scopes with the requested user's history.
- **Frontend** ships an in-host `FriendsComponent` at `/friends` and a
  reusable `SessionLogComponent` (inputs `scope` + `casdoorId`). The
  player-facing `/sessions/log` page uses it, and it can be embedded in
  the admin / mod / publisher remotes when those views are layered on.
  Promoting either to a federated remote bundle is mechanical.
- **Toast UX.** A `NotificationService` and an `errorInterceptor` map
  HTTP status codes to action-oriented player text; players never see
  "status 500" or a raw stack trace. Components subscribe with empty
  `error: () => {}` blocks and the interceptor shows the toast.

## Trade-offs accepted

- **Friends UI is in-host, not federated.** The data plumbing landed
  in one PR; the federated bundle (matching `mod`/`publisher`/`admin`)
  is a follow-up. The in-host component renders the same flow and the
  API surface is fixed, so the later lift is cheap.
- **No notification on incoming requests or friend acceptance.** The
  player must refresh `/friends` to see new state. Real-time push over
  the existing per-user SSE channel is on the backlog.
- **The admin check on `block()` was meant to sit at the controller,
  not the service**, because Casdoor's role membership isn't mirrored
  into Postgres and a service-level check would cost a Casdoor
  round-trip per block. That controller check was never written (see
  Block target gating above).
- **Visibility filtering is server-side.** The lobby `GET /api/sessions`
  returns only what the caller can see. A crafted request cannot
  enumerate hidden sessions; the filter is the authority, not the
  renderer.

## Reference

- [Liquibase 034](../../../backend/src/main/resources/db/changelog/034-friendships-and-session-visibility.xml)
- [UserRelationshipService](../../../backend/src/main/java/games/hugmesh/bff/service/UserRelationshipService.java)
- [SessionLogController](../../../backend/src/main/java/games/hugmesh/bff/controller/SessionLogController.java)
- [errorInterceptor](../../../frontend/src/app/services/error-interceptor.ts)
- [FriendsComponent](../../../frontend/src/app/friends/friends.component.ts)
- [SessionLogComponent](../../../frontend/src/app/session-log/session-log.component.ts)
- [Decision 010 — Per-session SSE sink isolation](010-per-session-sse-sinks.md)
- [Decision 013 — Per-player view egress](013-player-view-channel.md)
