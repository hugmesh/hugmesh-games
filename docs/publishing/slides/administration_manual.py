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
            "frontend            — host nginx + Angular host bundle",
            "login, admin        — federated route remotes (per-remote nginx)",
            "games-three-wizards, games-dodecahedron — federated game remotes",
            "wasm-fragment       — asset-tier nginx that serves Godot bundles",
            "api                 — Spring Boot BFF (or `api-native` overlay for the native image)",
            "postgres, cassandra, casdoor — persistence + identity",
        ],
        "code": "docker compose ps   →   12 services, all `healthy` is the target.",
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
            "Dashboard          — per-user stats, active sessions, recent uploads.",
            "/admin/games       — upload a game zip, view rollback history, set version pin.",
            "Per-game access    — scope=all / role / user; rows are additive (union).",
            "Roles dropdown     — live from Casdoor via /api/admin/roles (no client secret in the browser).",
            "Active launches    — each row has a `kick` button that DELETEs /api/admin/launches/{uid}.",
        ],
    },
    {
        "title": "Hot-uploading a game",
        "body": [
            "1. Get a Snapdragon Export zip from the game developer (or build it yourself with the editor plugin).",
            "2. /admin/games → Upload game bundle → choose zip → Upload.",
            "3. The install transaction validates manifest schema v2, hashes the bundle, atomically moves to <gamesDir>/<slug>/<sha-prefix>, and runs register.sql in one Postgres transaction.",
            "4. The host's federation manifest discovers the new game on the next refresh (within 60 s).",
            "5. New title appears on the home page automatically.",
        ],
        "code": "POST /api/admin/games/upload   →   {slug, contentHash, versionDir, uploadedAt}",
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
        "Repo: github.com/Snapdragon-Systems/ThreeWizardsInATrenchcoat",
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
