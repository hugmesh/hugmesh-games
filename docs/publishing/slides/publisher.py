"""
HugMesh Games — Publisher deck.

A 12-slide deck for ROLE_PUBLISHER holders: how to upload, roll back,
pin versions, edit access policy for games in your scope.
"""
from pathlib import Path
import argparse, sys
sys.path.insert(0, str(Path(__file__).parent))
from _hugmesh_deck import make_deck   # noqa: E402


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
        "image": "screenshot:publisher-console",
    },
    {
        "title": "Upload pipeline",
        "body": [
            "POST /api/games/{slug}/uploads — multipart with the HugMesh Export zip.",
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
            "Upload 400 manifest schema mismatch → stale HugMesh Export plugin. Re-export.",
            "Upload 409 → byte-identical bundle already on disk. Nothing to do.",
            "Pin 404 → uploadId pruned. Pin to next-most-recent surviving row.",
            "Access edit succeeds but player still 403s → JWT stale; player needs to re-login.",
            "Live-sessions empty → nobody is playing right now.",
        ],
    },
    {
        "title": "Publishing with the RPG maker",
        "body": [
            "Click 'Launch RPG Maker' on the publisher console — opens /rpg-maker in a new tab.",
            "Mudlet-shaped data model: project → areas → rooms + 6+1 direction exits + items/NPCs/scripts.",
            "Each area becomes a Chapter in the BFF; multi-area projects ship as multi-chapter games.",
            "Build: QUEUED → RENDERING → EXPORTING → UPLOADING → DONE.",
            "Worker renders a Godot 4 source tree (hugmesh_bridge.gdextension baked in) → godot CLI → zip → POST /api/admin/games/upload with YOUR bearer.",
            "publisher_game_scopes check at upload time enforces who can ship which slug.",
            "'Download Godot source' streams the same tree if you want to tweak in the editor.",
            "Pinning: e2e/features/makers/rpg-maker.feature.",
        ],
        "image": "screenshot:rpg-maker-area",
    },
    {
        "title": "Publishing with the visual novel maker",
        "body": [
            "/vn-maker — godot-2d-visual-novel-shaped. Project → acts → scenes → ordered lines + characters + branching choices.",
            "Same build pipeline as the RPG maker; each act becomes a Chapter exported to bundle/<act-slug>/index.html.",
            "Inline validation: character colours must look like #rgb / #rrggbb.",
            "Every choice needs a target scene — submitting one without a target shows 'target scene is required'.",
            "Exactly one entry-point scene per act; others are reachable via choices.",
            "Pinning: e2e/features/makers/vn-maker.feature.",
        ],
        "dark": True,
        "image": "screenshot:vn-maker-scene",
    },
    {
        "title": "Session visibility on your games",
        "body": [
            "Session visibility is per-session, not per-game policy — it's the player's choice.",
            "public   — appears in every player's lobby for that game (subject to blocklist).",
            "friends  — appears only in lobbies of the creator's mutual friends.",
            "private  — appears only in the creator's own lobby; invite-only.",
            "Filter lives in MultiplayerSessionController.listOpen; user_relationships is the graph.",
            "Interaction with game_access: a player not in game_access can't see ANY session — visibility runs AFTER game-list.",
            "Pinning: e2e/features/lobby/session-visibility.feature.",
        ],
    },
    {
        "title": "Dev fixture: publisher-flatland",
        "body": [
            "Casdoor seed user tw-pub-flat-001 with auto-granted scope on flatland-rpg + flatland-vn.",
            "Each builder service auto-seeds a Flatland-themed project tree (Lineland, Town Square, A Square, Lord Triangle, the Sphere).",
            "Liquibase 038-flatland-publisher-scopes.xml + builder-side 004-seed-flatland-fixture.xml.",
            "Logging in as publisher-flatland and opening either maker reveals the pre-seeded project ready to build.",
            "Pinning: e2e/features/makers/dev-fixtures.feature.",
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
        "Repo: github.com/hugmesh-net/hugmesh-games",
        "Admin deck: docs/publishing/slides/build/administration-manual.pptx",
        "Architecture PDF: docs/publishing/architecture/build/architecture.pdf",
    ],
}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    make_deck(
        title="HugMesh Games — Publisher guide",
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
