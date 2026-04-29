"""
Snapdragon Games — Administration manual.

A 14-slide deck for whoever runs the live stack: how to deploy, what the
admin UI exposes, how to grant role access, how to roll back a bad
upload, how to read the dashboards, and what each scheduled job does.

Edit the SECTIONS list below and re-run:
    python3 docs/publishing/slides/administration_manual.py \\
      --output docs/publishing/slides/build/administration-manual.pptx
"""
from pathlib import Path
import argparse, sys
sys.path.insert(0, str(Path(__file__).parent))
from _snapdragon_deck import make_deck   # noqa: E402


SECTIONS = [
    {
        "title": "Who this is for",
        "body": [
            "Anyone with ROLE_ADMIN in Casdoor and shell access to the compose host.",
            "You will: deploy, configure, inspect, revoke, and roll back.",
            "You will not: write Java, GDScript, or Angular here. Those have their own decks.",
            "Read this end-to-end the first time. After that it is a runbook.",
        ],
    },
    {
        "title": "The compose stack",
        "body": [
            "frontend  — host nginx + Angular host bundle",
            "login, admin — federated route remotes",
            "games-three-wizards, games-dodecahedron — federated games",
            "wasm-fragment — asset-tier nginx for Godot bundles",
            "api — Spring Boot BFF (or api-native overlay)",
            "postgres, cassandra, casdoor — persistence + identity",
        ],
        "image": "diagram:topology",
    },
    {
        "title": "Bringing the stack up",
        "body": [
            "Cold start:   docker compose up -d   (90–120 s to all-healthy on a workstation)",
            "Native image: docker compose -f compose.yml -f compose.native.yml up -d  (sub-second BFF cold start)",
            "Logs:         docker compose logs -f api                  (or any service)",
            "Stop one:     docker compose stop <service>",
            "Full reset:   docker compose down -v   (DROPS Postgres + Cassandra volumes)",
        ],
        "dark": True,
    },
    {
        "title": "Required user-tooling on the host",
        "body": [
            "Docker 29.x: requires docker-buildx. User-local install at ~/.docker/cli-plugins/docker-buildx (no sudo).",
            "Without buildx, `docker compose build` hangs silently — the legacy in-daemon builder is gone.",
            "Java 21, Node 20, Rust stable, Godot 4.6 — only needed if you plan to rebuild from source on the host.",
            "Emscripten + rustup wasm32-unknown-emscripten — only needed for the Rust GDExtension WASM target.",
        ],
    },
    {
        "title": "The admin UI",
        "body": [
            "Visit /admin (after logging in as a ROLE_ADMIN user).",
            "Dashboard — per-user stats, active sessions, recent uploads.",
            "/admin/games — upload a game zip, view rollback history, set version pin.",
            "Per-game access — scope=all / role / user; rows are additive (union).",
            "Active launches — each row has a `kick` button that DELETEs /api/admin/launches/{uid}.",
        ],
        "image": "screenshot:admin-01-dashboard",
    },
    {
        "title": "Step-by-step: upload a game",
        "body": [
            "Navigate to /admin/games.",
            "Drop a Snapdragon Export zip into the upload card (or file-pick).",
            "BFF: validate manifest_json schema_v=2 + zip-slip-safe paths.",
            "BFF: SHA-256 the bundle/, atomic move to <gamesDir>/<slug>/<sha-prefix>.",
            "BFF: ONE R2DBC tx — insert game_uploads, run register.sql, UPDATE games.current_version_dir, supersede prior.",
            "Card refreshes; the new bundle appears in the rollback table.",
        ],
        "image": "screenshot:admin-02-uploads",
    },
    {
        "title": "Step-by-step: explore the API live",
        "body": [
            "Navigate to /admin/docs.",
            "Left sidebar: every operation grouped by tag (driven by the merged OpenAPI spec).",
            "Reference tab — Javadoc-style read-only.",
            "Try-it-out — auth-token override + binary upload + JSON body editor.",
            "SSE endpoints stream into an in-window terminal panel.",
        ],
        "image": "screenshot:admin-03-docs",
    },
    {
        "title": "Hot-uploading a game",
        "body": [
            "1. Snapdragon Export zip from the game developer.",
            "2. /admin/games → Upload bundle → Upload.",
            "3. Install tx validates schema v2, hashes, moves to versioned dir, runs register.sql atomically.",
            "4. Federation manifest discovers the new game on next refresh (within 60 s).",
            "5. New title appears on the home page automatically.",
        ],
        "image": "diagram:game-upload-controller",
    },
    {
        "title": "Rollback",
        "body": [
            "Every upload supersedes the previous one but keeps the prior bundle on disk.",
            "GET /api/admin/games/{slug}/uploads — newest first; supersededAt = NULL marks the current.",
            "To roll back: pin the version via the admin UI's `Set as current` button on the row you want.",
            "Old versions are pruned by GameUploadPruner (default: keep 3 newest, configurable via app.uploads.versions-to-keep).",
            "Liquibase changesets that came with a now-superseded upload stay applied — DB is forward-only by design.",
        ],
    },
    {
        "title": "Per-game access policy",
        "body": [
            "game_access rows have (scope, scope_value):",
            (1, "(all, NULL)              — visible to every authenticated user."),
            (1, "(role, ROLE_PLAY_<GAME>) — visible to anyone with that Casdoor role."),
            (1, "(user, <casdoor-user-id>) — visible to that specific user."),
            "Multiple rows are additive; absence of any row hides the game from non-admins.",
            "Add via /admin POST /api/admin/games/{slug}/access; remove via DELETE with ?scope=&value=.",
            "Idempotent — duplicates absorbed by uq_game_access_triple.",
        ],
        "dark": True,
    },
    {
        "title": "Role management",
        "body": [
            "Casdoor is the source of truth for the role catalog.",
            "scripts/seed-roles.sh — idempotent per-game ROLE_PLAY_<game> creation. Run after deploying a new game.",
            "Grant a role from the admin UI's Roles tab (which calls Casdoor through /api/admin/roles).",
            "ROLE_ADMIN is required for every endpoint under /api/admin/.",
        ],
    },
    {
        "title": "Scheduled jobs (running inside the BFF)",
        "body": [
            "GameLaunchSweeper   — closes stale launch_uid rows once their 15-minute TTL expires.",
            "GameUploadPruner    — keeps only app.uploads.versions-to-keep newest version dirs per slug; prunes the rest off disk and via the supersede flag.",
            "CassandraSyncService — nightly reconcile of Cassandra audit-log totals back into Postgres counters.",
            "Each job logs a one-line summary on every run; check `docker compose logs api | grep Sweeper` if a row looks stuck.",
        ],
    },
    {
        "title": "Active session control",
        "body": [
            "GET /api/sessions/active — current connected sessions per character.",
            "DELETE /api/admin/launches/{uid} — admin force-revoke. Used by the dashboard's `Kick` button.",
            "DELETE /api/games/launches/{uid} — owner self-terminate (matched by JWT subject).",
            "Mismatch on the second one returns 403; the dashboard never shows kick buttons for sessions that aren't yours unless you're admin.",
        ],
    },
    {
        "title": "Multiplayer sessions",
        "body": [
            "GET /api/sessions[?gameSlug=] — list open sessions for the game-menu picker.",
            "POST /api/sessions — creator names + auto-joins slot 0.",
            "POST /api/sessions/{id}/join — slot collisions resolve as 409 via partial unique index.",
            "Live admin observation: /admin/sessions (SessionsShell) lists open sessions with per-player camera-state stream.",
            "Per-session SSE sinks isolate sessions from each other (see SessionIsolationTest).",
        ],
    },
    {
        "title": "Mod and publisher roles",
        "body": [
            "/admin/role-grants/* — admin grants publisher / mod scope per game.",
            "/api/publisher/grant-mod — publishers can grant mod role on their own games.",
            "/api/mod/sessions/{id}/{stop,kick} — moderators act per-game-scope.",
            "Per-game seed users: mod-three-wizards / mod-dodecahedron / publisher-three-wizards / publisher-dodecahedron.",
            "Each role has its own federated remote at /admin /mod /publisher.",
        ],
        "dark": True,
    },
    {
        "title": "Debug-mode toggle for diagnostics",
        "body": [
            "POST /api/diagnostics/wasm-console is now ROLE_ADMIN + flag-gated.",
            "Default OFF — players' iframe console captures never round-trip to the BFF.",
            "PUT /api/admin/debug-mode/wasm-console — admin enables briefly while triaging an iframe issue.",
            "GET /api/admin/debug-mode — read current state.",
            "system_settings table holds the persisted flag; AtomicBoolean mirrors hot-path reads.",
        ],
    },
    {
        "title": "Backup and restore",
        "body": [
            "Postgres        — pg_dump from inside the postgres container; volumes/postgres-data is the canonical source.",
            "Cassandra       — nodetool snapshot; cassandra is a cache, but the audit log is durable.",
            "Casdoor         — postgres-backed; included in the pg_dump above.",
            "Game uploads    — `app.uploads.games-dir` (default /var/snapdragon/games) is on the host and not bind-mounted unless you ask. Snapshot it on the same schedule as Postgres.",
        ],
        "dark": True,
    },
    {
        "title": "Troubleshooting",
        "body": [
            "`docker compose build` hangs    →    install docker-buildx (Docker 29.x).",
            "BFF restarts every minute       →    Liquibase failed; check `docker compose logs api | head -100` for the changeset.",
            "Iframe shows blank with no error →    CORP missing on wasm-fragment (already fixed; verify nginx.conf if you've edited it).",
            "JWT requests 500 with `Missing required keys member` →   wired to the wrong jwks endpoint; should be /.well-known/jwks.",
            "Active session list is empty but games are running →   GameLaunchSweeper closed them; check TTL.",
        ],
    },
    {
        "title": "Reading list",
        "body": [
            "README.md `Troubleshooting — recurring gotchas` section.",
            "docs/publishing/architecture/build/architecture.pdf — chapter 6 covers build / deploy in detail.",
            "docs/publishing/javadoc-output/index.html — branded Javadoc for every controller.",
            "docs/PLAN-module-federation-and-native.md — the why behind every choice on this slide.",
        ],
    },
]


CLOSING = {
    "title": "You are the operator. The runtime obeys you.",
    "body": [
        "Take a snapshot before anything risky.",
        "Roll back through the version-pin UI, not by deleting rows.",
        "When something breaks, read the BFF logs first — most paths emit a single descriptive line on failure.",
    ],
    "links": [
        "Repo: github.com/Snapdragon-Systems/snapdragon.games",
        "Architecture PDF: make docs-pdf",
        "Javadoc: make docs-javadocs",
    ],
}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    make_deck(
        title="Snapdragon Games — Administration",
        subtitle="Day-2 operations for ROLE_ADMIN holders",
        kicker="Administration manual",
        audience="Operators, on-call, ROLE_ADMIN holders.",
        sections=SECTIONS,
        closing=CLOSING,
        output=args.output,
    )
    print(f"✓ wrote {args.output}")


if __name__ == "__main__":
    main()
