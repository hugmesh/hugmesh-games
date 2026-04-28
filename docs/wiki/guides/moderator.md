# Moderator guide

> Mirror of [`docs/publishing/userguides/moderator/guide.tex`](../../publishing/userguides/moderator/guide.tex).

What `ROLE_MOD` actually lets you do, where the per-game scope check lives, and the day-to-day playbook for keeping a session healthy.

## Who this is for

Anyone who holds `ROLE_MOD` in Casdoor AND has at least one row in [`mod_game_scopes`](../architecture/04-data-model.md#mod_game_scopes). You are NOT an admin — you cannot grant roles, upload games, or restart services. You CAN observe live sessions, kick disruptive players from any game in your scope, and inspect a player's session-participation log for the games you moderate.

If you need to moderate a game that isn't in your scope, an `ROLE_ADMIN` holder grants you the scope row at `/admin/role-grants` (see [grant-mod-or-publisher](../howto/grant-mod-or-publisher.md)). The check is enforced at the BFF before any state mutation; the frontend hides UI you can't act on, but the server is the source of truth.

## What you can see

`/mod` is the federated mod console. The shell is `frontend/projects/mod/` — same Module Federation pattern as `/admin` and `/publisher`. After login the host loads it via `loadRemoteModule`; non-mods see a 403 banner instead of the dashboard.

| Tab | What it shows |
|---|---|
| **Live sessions** | Every active session for a game in your scope, with player roster + creator + visibility tier. |
| **Player view** | Streams the per-player view-channel SSE (camera + position samples ~10 Hz). Picks the game out of your scope automatically. |
| **Session log** | `/api/session-logs/scoped/{casdoorId}` — when a specific player is misbehaving, look up their participation history across the games you moderate. |

The "everything" admin dashboard at `/admin` is gated to `ROLE_ADMIN` only — your token won't unlock it.

## What you can do

- **Kick a player from a session you moderate.** `POST /api/sessions/{id}/kick` with `{casdoorId}`. The BFF validates that the target session's `game_slug` is in your `mod_game_scopes`. Kicked players see a toast and return to `/`. (See [kick-or-ban](../howto/kick-or-ban.md) for the UI flow.)
- **Force-close a session.** `DELETE /api/sessions/{id}` requires either being the session creator OR holding `ROLE_MOD` for the matching game. Same scope check.
- **Inspect session logs.** `GET /api/session-logs/scoped/{casdoorId}` returns participation history filtered to games in your scope. The unscoped admin variant (`/api/session-logs/admin/{casdoorId}`) returns 403.

## What you can NOT do

- Grant or revoke roles (admin only).
- Upload or roll back game bundles (admin or publisher within scope).
- Toggle WASM-console debug mode (admin only — see [decision 012](../decisions/012-debug-mode-gate.md)).
- Moderate a game outside your scope. The 403 is by design; ask an admin to widen the scope row.

## Scope is per-game, not per-action

Holding `ROLE_MOD` alone is not enough — every mod-only endpoint also checks `mod_game_scopes` for the game the action targets. The two layers are intentional: Casdoor owns "is a moderator at all", Postgres owns "for which games". An admin can hand out the role broadly; per-game scope stays tight.

## Day-1 playbook

1. Log in. Confirm the `/mod` link appears in the host header.
2. Open `/mod`. The dashboard lists every active session in your scope. If empty, no one is playing right now in the games you moderate.
3. Pick a session. Click *View* → the player-view SSE stream attaches. You'll see camera + position deltas in near-real-time. Closing the panel detaches the SSE.
4. If a player misbehaves: copy their `casdoorId` (visible in the roster) → *Kick*. Confirm. The kick is server-side; the player's iframe receives a `session.kicked` event and unmounts.
5. After incident: `Session log` tab → paste the `casdoorId` → look up their last 24 h of session participation. Use this to decide whether to escalate to a permanent ban (admin-only via `/admin/users`).

## Common fires

| Symptom | Likely cause | Fix |
|---|---|---|
| `/mod` shows a 403 banner | Casdoor session has `ROLE_MOD` but no `mod_game_scopes` row | Ask an admin to grant scope at `/admin/role-grants` |
| `Kick` returns 403 | Session game is outside your scope | Same — ask for scope, OR escalate to an admin who already has it |
| Player view is empty | Player's iframe isn't streaming the view channel | Check whether the game uses the [player view channel](../decisions/013-player-view-channel.md); some games opt out |
| Mod console is missing the kicked player | The session log writer is asynchronous; refresh after a few seconds | Hit reload — `session_members.left_at` is what populates the log |

## Where to look next

- [Decision 011 — Mod / publisher RBAC](../decisions/011-mod-publisher-rbac.md): the design behind the two-layer check.
- [Architecture chapter 5 — API reference](../architecture/05-api-reference.md): every mod-scoped endpoint with marble + payload.
- [How-to: kick or ban a player](../howto/kick-or-ban.md): step-by-step UI walkthrough.
- [How-to: grant the mod or publisher role for a game](../howto/grant-mod-or-publisher.md): for when an admin needs to widen your scope.
