# Moderator guide

> A typeset edition lives in [`docs/publishing/userguides/moderator/guide.tex`](../../publishing/userguides/moderator/guide.tex). The two are edited separately and can differ.

What `ROLE_MOD` actually lets you do, where the per-game scope check lives, and the day-to-day playbook for keeping a session healthy.

## Who this is for

Anyone who holds the Casdoor role `mod` (the BFF sees it as `ROLE_MOD`) AND has at least one row in [`mod_game_scopes`](../architecture/04-data-model.md#multiplayer-session-schema). You are NOT an admin — you cannot grant roles, upload games, or restart services. You CAN list open sessions, kick disruptive players, stop sessions in any game in your scope, and read a player's session-participation log for the games you moderate.

If you need to moderate a game that isn't in your scope, an `ROLE_ADMIN` holder grants you the scope row with `POST /api/admin/role-grants/grant-mod` (a publisher of that game can use `POST /api/publisher/grant-mod`). There is no admin page for this; both are API calls. See [grant-mod-or-publisher](../howto/grant-mod-or-publisher.md). The check is enforced at the BFF before any state mutation; the frontend hides UI you can't act on, but the server is the source of truth.

## What you can see

`/mod` is the federated mod console. The shell is `frontend/projects/mod/` — same Module Federation pattern as `/admin` and `/publisher`. After login the host loads it via `loadRemoteModule`; non-mods see a 403 banner instead of the dashboard.

| Section | What it shows |
|---|---|
| **Games you can moderate** | Your active `mod_game_scopes` rows, from `GET /api/mod/scopes`. |
| **Open sessions** | One row per open session: name, game, creator, players, members. Each member has a *Kick* button; each row has *Stop session*. |

The console has no player-view stream and no session-log tab. The per-player view stream is admin-only (`/admin/sessions`). To look up a player's history, call `GET /api/session-logs/scoped/{casdoorId}` (see below).

The "everything" admin dashboard at `/admin` is gated to `ROLE_ADMIN` only — your token won't unlock it.

## What you can do

- **Kick a player from a session you moderate.** `POST /api/mod/sessions/{id}/kick` with `{targetCasdoorId, reason}`. The BFF validates that the target session's `game_slug` is in your `mod_game_scopes`, stamps `left_at` on the member row, closes the session if no one is left, and writes an `admin_actions` row. Kicked players see a toast and return to `/`. (See [kick-or-ban](../howto/kick-or-ban.md) for the admin-level kick.)
- **Stop a session.** `POST /api/mod/sessions/{id}/stop` with an optional `{reason}`. Same scope check; audited in `admin_actions`. (`DELETE /api/sessions/{id}` is for the session creator only.)
- **Inspect session logs.** `GET /api/session-logs/scoped/{casdoorId}` returns participation history filtered to games in your scope. The unscoped admin variant (`/api/session-logs/admin/{casdoorId}`) returns 403.

## What you can NOT do

- Grant or revoke roles (admin only).
- Upload game bundles (admin, or a publisher within scope).
- Toggle WASM-console debug mode (admin only — see [decision 012](../decisions/012-debug-mode-gate.md)).
- Moderate a game outside your scope. The 403 is by design; ask an admin to widen the scope row.

## Scope is per-game, not per-action

Holding `ROLE_MOD` alone is not enough — every mod-only endpoint also checks `mod_game_scopes` for the game the action targets. The two layers are intentional: Casdoor owns "is a moderator at all", Postgres owns "for which games". An admin can hand out the role broadly; per-game scope stays tight.

## Day-1 playbook

1. Log in. Confirm the `/mod` link appears in the host header.
2. Open `/mod`. The dashboard lists every active session in your scope. If empty, no one is playing right now in the games you moderate.
3. Find the session in the *Open sessions* table. Each member has a *Kick* button; the row has *Stop session*.
4. If a player misbehaves: *Kick* beside their `casdoorId`. Confirm the prompt. The kick is server-side and is recorded in `admin_actions`.
5. After the incident: call `GET /api/session-logs/scoped/{casdoorId}` (there is no console tab for it). It returns up to `limit` rows (default 100) of that player's participation in the games you moderate. Use it to decide whether to escalate to a ban. A ban is admin-only: `POST /api/admin/actions/ban/{casdoorId}` (there is no admin page for it).

## Common fires

| Symptom | Likely cause | Fix |
|---|---|---|
| `/mod` shows "You need the mod or admin role to use this console." | The token lacks the `mod` role | Ask an admin to assign the Casdoor role, then sign in again |
| `/mod` says you hold the `mod` role but no per-game scope is assigned | No `mod_game_scopes` row | Ask an admin (`POST /api/admin/role-grants/grant-mod`) or a publisher of the game (`POST /api/publisher/grant-mod`) |
| `Kick` returns 403 | Session game is outside your scope | Same — ask for scope, OR escalate to an admin who already has it |
| Mod console is missing the kicked player | The session log writer is asynchronous; refresh after a few seconds | Hit reload — `session_members.left_at` is what populates the log |

## Where to look next

- [Decision 011 — Mod / publisher RBAC](../decisions/011-mod-publisher-rbac.md): the design behind the two-layer check.
- [Architecture chapter 5 — API reference](../architecture/05-api-reference.md): every mod-scoped endpoint with marble + payload.
- [How-to: kick or ban a player](../howto/kick-or-ban.md): step-by-step UI walkthrough.
- [How-to: grant the mod or publisher role for a game](../howto/grant-mod-or-publisher.md): for when an admin needs to widen your scope.
