# How to kick or ban a player

Three actions, one ledger. Each call to the admin-actions API writes a row to `admin_actions` and pushes it to the target's per-user SSE stream. For a kick or a ban, the host shell removes every game iframe in the tab and sends the player to `/banned`. An unban only clears the local ban marker.

## Prerequisites

- `ROLE_ADMIN` in Casdoor.
- The user's Casdoor ID, written `<casdoorId>` below. It must be the user's JWT `sub`. The player's stream and the ban gate match rows against `jwt.getSubject()` (`AdminActionController`, `BanGateFilter`), so a row that names a username takes effect for nobody. The admin console shows the `sub` in the *Per-user usage* table, column *Casdoor ID*. Casdoor re-mints the `sub` of a seeded user on a fresh boot, so look it up each time.

## Kick

A kick closes the player's game window. It does not prevent a new login.

### From the admin UI

`/admin` → *Per-user usage* → click *Kick* on an online user's row.

This button is not the API below. It calls `DELETE /api/admin/sessions/{casdoorId}`, which drops the player's presence and stamps `disconnected_at` on their session row. It writes no `admin_actions` row and sends no SSE event. The admin console has no ban or unban control. Use the API.

### Via API

```bash
curl -sS -X POST -H "Authorization: Bearer $ADMIN_JWT" \
  -H 'Content-Type: application/json' \
  -d '{"message":"connection issue"}' \
  http://localhost:8080/api/admin/actions/kick/<casdoorId>
```

The body is optional. `message` is stored with the row and reaches the player's tab, so the UI can show a reason.

Server side:

1. `AdminActionService` appends a row to `admin_actions` (`action='kick'`, `target_casdoor_id`, `actor_casdoor_id`, `message`, `created_at`).
2. The service emits the row on its in-process sink.
3. `SessionService.disconnect` drops the player's presence and stamps `disconnected_at` on their session row.

A kick does not touch `game_launches`. To revoke one launch, use `DELETE /api/admin/launches/{uid}`.

Player side:

1. `AdminActionsListenerService` (frontend) receives the `admin-action` event from `/api/me/admin-actions/stream`.
2. It posts the event on the `hugmesh-admin-actions` `BroadcastChannel`, so every tab on the same origin reacts.
3. Each tab removes all `iframe` elements, stores the action in `sessionStorage` (`tw_ban_marker`) and goes to `/banned`.

## Ban

A ban is a row with `action='ban'`. It does what a kick does. It also blocks the player's API access until an unban row supersedes it.

```bash
curl -sS -X POST -H "Authorization: Bearer $ADMIN_JWT" \
  -H 'Content-Type: application/json' \
  -d '{"message":"violated TOS section 3","expiresAt":"2026-12-31T00:00:00Z"}' \
  http://localhost:8080/api/admin/actions/ban/<casdoorId>
```

`expiresAt` is optional. Leave it out and the ban lasts until an unban.

Server side, additionally:

- `BanGateFilter` (a Spring `WebFilter`, order `-100 + 50`, after the security chain has bound the JWT) answers `403` to every `/api/*` request from a banned user. It lets through `/api/auth/`, `/api/me/ban-status` and `/api/me/admin-actions/stream`.

Player side, on the next navigation:

- The host's `authGuard` calls `/api/me/ban-status`. If `banned` is true, it redirects to `/banned`.
- The `/banned` page shows the admin's message and the expiry time.

## Unban

```bash
curl -sS -X POST -H "Authorization: Bearer $ADMIN_JWT" \
  -H 'Content-Type: application/json' \
  -d '{"message":"appeal accepted"}' \
  http://localhost:8080/api/admin/actions/unban/<casdoorId>
```

This appends an `action='unban'` row. A user counts as banned only while their latest row is a `ban` that has not expired, so the unban supersedes it. The audit trail stays.

## Verify

```bash
# History for one user, newest first:
curl -H "Authorization: Bearer $ADMIN_JWT" \
  "http://localhost:8080/api/admin/actions/<casdoorId>" | jq

# The player's own stream (any signed-in user; it carries only that user's rows):
curl -N -H "Authorization: Bearer $JWT" \
  http://localhost:8080/api/me/admin-actions/stream
```

There is no admin-wide live stream of actions.

## See also

- [Administration manual](../guides/administration.md)
- [04 — Data model](../architecture/04-data-model.md#players-sessions-and-progression)
- [05 — API reference](../architecture/05-api-reference.md#adminactioncontroller--apiadminactions)
