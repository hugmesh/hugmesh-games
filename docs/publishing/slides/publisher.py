"""
Snapdragon Games — Publisher deck.

A 12-slide deck for ROLE_PUBLISHER holders: how to upload, roll back,
pin versions, edit access policy for games in your scope.
"""
from pathlib import Path
import argparse, sys
sys.path.insert(0, str(Path(__file__).parent))
from _snapdragon_deck import make_deck   # noqa: E402


SECTIONS = [
    {
        "title": "Who this is for",
        "body": [
            "Holders of ROLE_PUBLISHER + at least one row in publisher_game_scopes.",
            "You can: upload, pin, roll back, edit access for games in scope.",
            "You cannot: grant roles, kick players, restart services.",
            "Scope rows come from an admin via /admin/role-grants.",
        ],
    },
    {
        "title": "The two-layer check",
        "body": [
            "Same pattern as moderators (decision 011).",
            "Casdoor: ROLE_PUBLISHER claim.",
            "Postgres: publisher_game_scopes (one row per (publisher, game)).",
            "BFF rejects publisher actions outside scope — 403.",
            "An admin grants the role broadly; per-game scope stays tight.",
        ],
    },
    {
        "title": "What you can see (/publisher console)",
        "body": [
            "Catalog — every game in your scope with current version + policy summary.",
            "Uploads — per-game upload history (most recent first).",
            "Access policy — game_access table view per game.",
            "Live sessions — read-only for your games (kick is mod-only).",
            "Federated console: frontend/projects/publisher.",
        ],
    },
    {
        "title": "Upload pipeline",
        "body": [
            "POST /api/games/{slug}/uploads — multipart with the Snapdragon Export zip.",
            "BFF validates manifest schema v2 + hashes the bundle.",
            "Atomically moves to <gamesDir>/<slug>/<sha-prefix>.",
            "Runs register.sql in one Postgres transaction.",
            "Federation manifest refreshes within 60 s; players see the new version live.",
        ],
        "image": "diagram:game-upload-controller",
        "dark": True,
    },
    {
        "title": "Pinning and rollback",
        "body": [
            "PUT /api/games/{slug}/uploads/{uploadId}/pin — flip the active-version pointer.",
            "Rollback is a single PUT; old versions stay on disk under <gamesDir>/<slug>/.",
            "GameUploadPruner caps disk usage (default: keep 3 newest per slug).",
            "Configurable via app.uploads.versions-to-keep — raise before rolling back to a long-old version.",
            "Decision 005 covers the launch-UID + version-pin design.",
        ],
    },
    {
        "title": "Per-game access policy",
        "body": [
            "POST /api/games/{slug}/access { scope, value } — three additive scopes.",
            "scope=all — anyone can play.",
            "scope=role — anyone with that role (e.g. ROLE_PLAY_THREE_WIZARDS).",
            "scope=user — specific Casdoor ID.",
            "Idempotent on uq_game_access_triple. Decision 009 covers the union semantics.",
        ],
        "dark": True,
    },
    {
        "title": "What you cannot do",
        "body": [
            "Grant roles or scope rows — admin only.",
            "Touch a game outside your scope — 403.",
            "Kick or ban — mod / admin.",
            "Toggle WASM-console debug mode — admin (decision 012).",
            "Edit a published bundle directly on disk — go through the pipeline so audit trails are correct.",
        ],
    },
    {
        "title": "Day-1 playbook",
        "body": [
            "Step 1: Log in, confirm /publisher link appears.",
            "Step 2: Open /publisher, find the game in your catalog.",
            "Step 3: Uploads tab → Upload new bundle → choose zip → wait for toast.",
            "Step 4: Verify by visiting / and clicking the card; the chapter modal mounts the new version.",
            "Step 5: If broken, Pin a previous row from the same Uploads tab.",
            "Step 6: Access policy tab to gate the game on a role or set of users.",
        ],
    },
    {
        "title": "Common fires",
        "body": [
            "/publisher 403 banner → no publisher_game_scopes row. Ask admin.",
            "Upload 400 manifest schema mismatch → stale Snapdragon Export plugin. Re-export.",
            "Upload 409 → byte-identical bundle already on disk. Nothing to do.",
            "Pin 404 → uploadId pruned. Pin to next-most-recent surviving row.",
            "Access edit succeeds but player still 403s → JWT stale; player needs to re-login.",
            "Live-sessions empty → nobody is playing right now.",
        ],
    },
    {
        "title": "Working with admins",
        "body": [
            "Need a wider scope? /admin/role-grants is admin-only — file a request.",
            "Need a role on a player so they can play? Admin grants ROLE_PLAY_<SLUG>.",
            "Discovered a security issue in a bundle? Coordinate with admin to revoke + roll back.",
            "Pruner about to delete a version you want to keep? Admin raises versions-to-keep.",
        ],
        "dark": True,
    },
    {
        "title": "Where to look",
        "body": [
            "Decision 011 — Mod / publisher RBAC.",
            "Decision 009 — game_access additive scopes.",
            "Decision 005 — Launch-UID + version pinning.",
            "How-to: hot-upload and roll back a game bundle.",
            "Architecture chapter 5 — API reference.",
        ],
    },
]


CLOSING = {
    "title": "Publish through the pipeline. Trust 403.",
    "body": [
        "The upload + pin pipeline is your audit trail; never edit bundles on disk.",
        "Per-game access is additive — start tight (specific role) and widen only when you understand the impact.",
        "Pruner is your friend; raise versions-to-keep before you need to roll back to something old.",
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
        title="Snapdragon Games — Publisher guide",
        subtitle="Upload, pin, gate access. Scope is per-game.",
        kicker="Publisher guide",
        audience="Publishers (ROLE_PUBLISHER with publisher_game_scopes rows).",
        sections=SECTIONS,
        closing=CLOSING,
        output=args.output,
    )
    print(f"✓ wrote {args.output}")


if __name__ == "__main__":
    main()
