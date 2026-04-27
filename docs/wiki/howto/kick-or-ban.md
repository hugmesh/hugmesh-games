# How to kick or ban a player

Three actions, one ledger. All three write to `admin_actions` and broadcast on the affected user's per-user SSE channel; the host shell catches the event, closes any open game window via `BroadcastChannel`, and (for ban) redirects to `/banned`.

## Prerequisites

- `ROLE_ADMIN` in Casdoor.
- The user's Casdoor ID (visible on the admin dashboard's *Active sessions* card).

## Kick

Closes the player's current launch and forces their game window to close. Doesn't prevent re-login.

### From the admin UI

`/admin` → *Active sessions* → click *Kick* on the row.

### Via API

```bash
curl -sS -X POST -H "Authorization: Bearer $ADMIN_JWT" \
  -H 'Content-Type: application/json' \
  -d '{"casdoorUserId":"alice@threewizards","action":"kick","message":"connection issue"}' \
  http://localhost:8080/api/admin/actions
```

The optional `message` field is appended to the SSE event the player's tab receives, so the UI can render an admin-supplied reason.

What happens server-side:

1. `AdminActionService` writes a row to `admin_actions` (`action='kick'`, `target_casdoor_id`, `actor_casdoor_id`, `message`, `occurred_at`).
2. The matching launch row is `DELETE`d (sets `terminated_at = NOW()` on `game_launches`).
3. The per-user SSE channel emits a `kick` event.

Player-side:

1. `AdminActionsListenerService` (frontend) receives the SSE event.
2. Posts a `BroadcastChannel('snapdragon')` message. Any open game window in another tab receives it and calls `window.close()`.
3. The current tab shows a modal with the admin's message.

## Ban

Like kick, but persists in `admin_actions` with `action='ban'` and gates all future `/api/*` requests via `BanGateFilter`.

```bash
curl -sS -X POST -H "Authorization: Bearer $ADMIN_JWT" \
  -H 'Content-Type: application/json' \
  -d '{"casdoorUserId":"alice@threewizards","action":"ban","message":"violated TOS section 3"}' \
  http://localhost:8080/api/admin/actions
```

Server-side, additionally:

- `BanGateFilter` (Spring `WebFilter` at `HIGHEST_PRECEDENCE+50`) refuses every `/api/*` request from a user with an unresolved ban — except a small allowlist (`/api/auth/*`, `/api/me/ban-status`, `/api/admin/actions/*`).

Player-side, on next login:

- The host's `authGuard` calls `/api/me/ban-status`. If banned, redirects to `/banned`.
- The `/banned` page renders the admin's message and the `occurred_at` timestamp.

## Unban

```bash
curl -sS -X POST -H "Authorization: Bearer $ADMIN_JWT" \
  -H 'Content-Type: application/json' \
  -d '{"casdoorUserId":"alice@threewizards","action":"unban","message":"appeal accepted"}' \
  http://localhost:8080/api/admin/actions
```

Adds an `action='unban'` row. `BanGateFilter` resolves a user as banned only if their most recent action is `ban`; an `unban` row supersedes it. The audit trail is preserved.

## Verify

```bash
# History for one user:
curl -H "Authorization: Bearer $ADMIN_JWT" \
  "http://localhost:8080/api/admin/actions?targetCasdoorId=alice@threewizards" | jq

# Live stream of admin actions (admins only):
curl -N -H "Authorization: Bearer $ADMIN_JWT" \
  http://localhost:8080/api/admin/actions/stream
```

## See also

- [Administration manual](../guides/administration.md)
- [04 — Data model](../architecture/04-data-model.md#players-sessions-and-progression)
- [05 — API reference](../architecture/05-api-reference.md#adminactioncontroller--apiadminactions)
