"""
Snapdragon Games — Moderator deck.

A 12-slide deck for ROLE_MOD holders: what the role lets you do, where
the per-game scope check lives, and the day-to-day playbook for keeping
a session healthy.
"""
from pathlib import Path
import argparse, sys
sys.path.insert(0, str(Path(__file__).parent))
from _snapdragon_deck import make_deck   # noqa: E402


SECTIONS = [
    {
        "title": "Who this is for",
        "body": [
            "Holders of ROLE_MOD in Casdoor + at least one row in mod_game_scopes.",
            "You can: observe live sessions, kick disruptive players, inspect session logs.",
            "You cannot: grant roles, upload games, restart services, moderate outside scope.",
            "Scope rows come from an admin via /admin/role-grants.",
        ],
    },
    {
        "title": "The two-layer check",
        "body": [
            "Casdoor owns: \"is a moderator at all\" (ROLE_MOD claim).",
            "Postgres owns: \"for which games\" (mod_game_scopes table).",
            "BFF rejects mod actions whose target game isn't in scope — HTTP 403.",
            "An admin grants the role broadly; per-game scope stays tight.",
            "Both layers are enforced server-side; the UI is just a hint.",
        ],
        "image": "diagram:multiplayer-session-controller",
    },
    {
        "title": "What you can see (/mod console)",
        "body": [
            "Live sessions — every active session for a game in your scope.",
            "Player view — per-player view-channel SSE (camera + position ~10 Hz).",
            "Session log — /api/session-logs/scoped/{casdoorId} for your games.",
            "Federated console: frontend/projects/mod, mounted via loadRemoteModule.",
            "Non-mods see a 403 banner. /admin remains gated to ROLE_ADMIN.",
        ],
        "image": "screenshot:mod-console",
    },
    {
        "title": "What you can do",
        "body": [
            "Kick: POST /api/sessions/{id}/kick { casdoorId } — scope-checked.",
            "Force-close: DELETE /api/sessions/{id} — creator OR mod-with-scope.",
            "Inspect log: GET /api/session-logs/scoped/{casdoorId}.",
            "All three endpoints validate mod_game_scopes for the target game.",
            "Audit trail lives in admin_actions and session_members.",
        ],
        "dark": True,
    },
    {
        "title": "What you cannot do",
        "body": [
            "Grant or revoke roles — admin only.",
            "Upload or roll back game bundles — admin or publisher within scope.",
            "Toggle WASM-console debug mode — admin only (decision 012).",
            "Moderate a game outside your scope — 403 by design.",
            "If you need a wider scope, ask an admin to grant the row.",
        ],
    },
    {
        "title": "Day-1 playbook",
        "body": [
            "Step 1: Log in, confirm /mod link appears in the host header.",
            "Step 2: Open /mod, scan the active-sessions list.",
            "Step 3: Click View on a session, watch the player-view SSE.",
            "Step 4: If someone misbehaves, click Kick + confirm.",
            "Step 5: Use Session log tab to decide whether to escalate to ban.",
            "Bans are admin-only via /admin/users — escalate, don't try.",
        ],
    },
    {
        "title": "The session log endpoint",
        "body": [
            "GET /api/session-logs/scoped/{casdoorId} — your scope.",
            "GET /api/session-logs/admin/{casdoorId} — admin only, returns 403 to mods.",
            "GET /api/session-logs/me — your own history.",
            "Backed by session_members (append-only on join, left_at on leave).",
            "Filterable by game; scope filter is server-side, not UI-side.",
        ],
        "image": "diagram:session-logs-controller",
    },
    {
        "title": "The view channel",
        "body": [
            "Per-player SSE channel: /api/admin/sessions/{id}/stream merges movement + world + view.",
            "Mods get a scoped variant: /api/mod/sessions/{id}/stream (same merge).",
            "Camera samples ~10 Hz from JavaScriptBridge.eval inside the WASM iframe.",
            "Some games may opt out of the view channel — empty stream is normal.",
            "See decision 013 for the per-player view egress design.",
        ],
        "dark": True,
    },
    {
        "title": "Common fires",
        "body": [
            "/mod 403 banner → Casdoor has ROLE_MOD, no mod_game_scopes row. Ask admin.",
            "Kick 403 → session game outside your scope. Same fix.",
            "Player view empty → that game opts out of the view channel.",
            "Kicked player still in roster → log writer is async; refresh in a few seconds.",
            "Live-sessions empty → nobody is playing right now. Reflects left_at IS NULL.",
        ],
    },
    {
        "title": "Escalation path",
        "body": [
            "Tier 1 (you): kick + log review. Cap incidents at 15 min if you can.",
            "Tier 2: another mod with broader scope. Use the admin-cc list.",
            "Tier 3 (admin): permanent bans, role grants, scope changes.",
            "Always record what you did in the incident channel — kicks are auditable but the why is in your message.",
        ],
        "dark": True,
    },
    {
        "title": "Where to look",
        "body": [
            "Decision 011 — Mod / publisher RBAC: the design behind the two-layer check.",
            "Decision 013 — Per-player view egress channel.",
            "Architecture chapter 5 — every mod-scoped endpoint with marble.",
            "How-to: kick or ban a player.",
            "How-to: grant the mod or publisher role for a game (for admins).",
        ],
    },
]


CLOSING = {
    "title": "Mod softly. Server is the source of truth.",
    "body": [
        "Every action you take is scope-checked server-side; trust 403s, they mean what they say.",
        "Kicks recover sessions; bans are admin-only — escalate when patterns repeat.",
        "Use the session log liberally; pattern-matching on participation history is more reliable than memory.",
    ],
    "links": [
        "Repo: github.com/Snapdragon-Systems/snapdragon.games",
        "Admin deck: docs/publishing/slides/build/administration-manual.pptx",
        "Architecture PDF: docs/publishing/architecture/build/architecture.pdf",
    ],
}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    make_deck(
        title="Snapdragon Games — Moderator guide",
        subtitle="Observe, kick, log. Scope is per-game.",
        kicker="Moderator guide",
        audience="Mods (ROLE_MOD with mod_game_scopes rows).",
        sections=SECTIONS,
        closing=CLOSING,
        output=args.output,
    )
    print(f"✓ wrote {args.output}")


if __name__ == "__main__":
    main()
