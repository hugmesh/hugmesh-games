"""
Snapdragon Games — Intro for entry-level coders.

A 12-slide tour of the hosted architecture written for someone who has
shipped some code in some language and is brand new to this codebase.
The deck assumes nothing about Spring, Angular, Godot, or federation; it
points at where to read more in the architecture PDF and the Javadoc.

Edit the SECTIONS list below and re-run:
    python3 docs/publishing/slides/intro_for_coders.py \\
      --output docs/publishing/slides/build/intro-for-coders.pptx
"""
from pathlib import Path
import argparse, sys

sys.path.insert(0, str(Path(__file__).parent))
from _snapdragon_deck import make_deck   # noqa: E402


SECTIONS = [
    {
        "title": "What is Snapdragon Games?",
        "body": [
            "A browser-native portal that hosts multiple games on one page.",
            "Each game is a Godot 4 export compiled to WebAssembly.",
            "An Angular shell wraps the games with HUD, login, and admin.",
            "A Spring Boot BFF (\"backend for frontend\") streams game state and controls over Server-Sent Events.",
            "Postgres holds catalog/state; Cassandra is a fast cross-pod cache.",
            "Casdoor handles login (OAuth2/OIDC) and the host BFF reverse-proxies it.",
        ],
    },
    {
        "title": "The runtime, in three tiers",
        "body": [
            "Tier 1 — Browser: Angular host shell + the active game iframe.",
            "Tier 2 — Per-remote nginx: host, login, admin, two game remotes, and an asset-tier wasm-fragment.",
            "Tier 3 — BFF + persistence: Spring Boot, Postgres, Cassandra, Casdoor.",
            "Everything in tier 2 is one Docker container per box.",
            "See chapter 2 of architecture.pdf for the full diagram.",
        ],
        "image": "diagram:topology",
    },
    {
        "title": "Module Federation: why one app feels like many",
        "body": [
            "The host shell loads /login, /admin, and per-game remotes at runtime — they are not bundled with the host.",
            "Adding a new route means a new federation_modules row, not a host redeploy.",
            "@angular-architects/native-federation does the runtime import-map dance.",
            "loadRemoteModule is stashed on globalThis to keep the federation runtime out of karma's static graph.",
        ],
        "code": "GET /api/federation/manifest  →  initFederation(...)  →  bootstrapApplication(AppComponent)",
        "dark": True,
    },
    {
        "title": "Where the code lives",
        "body": [
            "frontend/                Angular host (one project)",
            "frontend/projects/       The four federated remotes (login, admin, games-three-wizards, games-dodecahedron)",
            "wasm-fragment/           Asset-tier nginx that serves Godot bundles",
            "backend/                 Spring Boot BFF (WebFlux + R2DBC)",
            "games/<slug>/            One Godot project per game",
            "extensions/snapdragon-bridge/  Reusable Rust GDExtension (native + WASM)",
        ],
    },
    {
        "title": "How a player click reaches a frame",
        "body": [
            "1. HomeComponent reads SCENE_TYPE_TO_GAME_SLUG.",
            "2. Federated games POST /api/games/{slug}/launch to mint a launch_uid.",
            "3. RemoteLoaderService loads the per-game remote.",
            "4. The remote mounts a Web Component that builds the iframe URL.",
            "5. wasm-fragment serves index.html with COOP/COEP/CORP headers.",
            "6. Godot pulls scene JSON from /api/levels/{id}/scene and renders.",
        ],
        "image": "diagram:runtime-flow",
    },
    {
        "title": "Server-Sent Events: one stream, five channels",
        "body": [
            "GET /api/game/stream emits movement, world, character, scene, and time events.",
            "GameStreamController merges five Sinks.Many onto one Flux<ServerSentEvent>.",
            "A 15s keepalive fires immediately so EventSource.onopen lands fast.",
            "Every Sinks.many() in this project sets autoCancel=false.",
            "If you forget, the sink dies on the first reconnect.",
        ],
        "image": "diagram:game-stream-controller",
    },
    {
        "title": "Authentication, in one paragraph",
        "body": [
            "Casdoor lives on the compose network only — no host port.",
            "The host BFF exposes /api/auth/* as a thin Casdoor proxy.",
            "JWTs are verified against the anonymous /.well-known/jwks endpoint.",
            "Per-game visibility is enforced by the game_access table (scope: all / role / user).",
            "ROLE_ADMIN is checked at controller level for everything under /api/admin/.",
        ],
    },
    {
        "title": "How to run it",
        "body": [
            "First-time setup: Java 21, Node 20, Rust stable, Godot 4.6, Docker + buildx.",
            "make build                 — full source build (rust + godot + backend + frontend)",
            "docker compose up -d       — bring the whole stack up",
            "make e2e                   — full Playwright suite against the live stack",
            "Open http://localhost:8080 — login auto-creates a character on first visit.",
        ],
    },
    {
        "title": "First click: the login form",
        "body": [
            "Land on /login. The Casdoor flow is reverse-proxied by the BFF.",
            "Form posts to /api/auth/token. Token lands in sessionStorage.tw_auth.",
            "After login the host's authGuard lets / and /admin/* routes mount.",
        ],
        "image": "screenshot:tour-01-login",
    },
    {
        "title": "Second click: the catalog",
        "body": [
            "/ shows every visible game (filtered by game_access).",
            "Each card is a federated remote loaded on click.",
            "Clicking a level mints a launch_uid + loads the matching games-* remote.",
        ],
        "image": "screenshot:tour-02-home",
    },
    {
        "title": "Third click: a game running",
        "body": [
            "wasm-fragment serves index.html with COOP/COEP/CORP headers.",
            "Godot prefetches /api/levels/{id}/scene before the WASM compiles.",
            "Toolbar (top-right): fullscreen, open-in-new-tab, open-in-new-window.",
        ],
        "image": "screenshot:tour-03-game-cave",
    },
    {
        "title": "Your first change: pick the right tier",
        "body": [
            "Tweaking a controller / DTO / SQL?  →  backend/ + a Liquibase changeset.",
            "Tweaking a route / login UI / admin?  →  the matching frontend/projects/<remote>/.",
            "Tweaking the game?  →  games/<slug>/ in the Godot editor (after make build-rust).",
            "Tweaking the runtime data flow?  →  HomeComponent + RemoteLoaderService.",
            "Always run the matching tests: make test-backend, make test-frontend.",
        ],
    },
    {
        "title": "Testing posture",
        "body": [
            "Backend unit + slice tests under backend/src/test/.",
            "Postgres-backed integration tests via Testcontainers (GameUploadIntegrationTest).",
            "Frontend Karma specs under frontend/src/app/ — currently 73 specs covering federation surface.",
            "End-to-end Playwright in e2e/ — make e2e drives a real browser against the live compose stack.",
            "If a test fails locally and not in CI, double-check buildx is installed (Docker 29.x removed the legacy builder).",
        ],
    },
    {
        "title": "Multiplayer sessions, one paragraph",
        "body": [
            "Players click a game card → modal shows open sessions for that game + a 'start new' form.",
            "Joining picks the lowest free slot; the session id + slot index thread onto the iframe URL.",
            "GameStateService keeps a per-session SessionSinks bundle on top of the legacy global sinks.",
            "Subscribers to /api/game/stream?sessionId=A see only A's events — never B's.",
            "Three-wizards spawns one wizard CharacterBody3D per registered slot via multiplayer_lobby.gd.",
            "SessionIsolationTest pins the cross-session non-leakage in both directions.",
        ],
    },
    {
        "title": "Roles: admin / publisher / mod / player",
        "body": [
            "Casdoor owns role membership; Postgres owns per-game scope (mod_game_scopes / publisher_game_scopes).",
            "publisher: upload bundles + grant the mod role on games they own.",
            "mod: stop sessions + kick players for games an admin or publisher has scoped them to.",
            "admin: everything, including granting publisher and scoped mod across games.",
            "Each role has its own federated remote: /admin, /mod, /publisher.",
            "Per-game seeded users out of the box — see casdoor/init_data.json.tpl.",
        ],
        "dark": True,
    },
    {
        "title": "Friends, blocks, session visibility",
        "body": [
            "Single user_relationships graph table backs both features.",
            "Friends are mutual (two `friend` rows). Blocks are one-directional (one `blocked` row).",
            "Sessions have a visibility tier: public / friends / private.",
            "Lobby filter (MultiplayerSessionController.listOpen) runs against caller's friend graph + blocklist BEFORE returning.",
            "Publishers can't be blocked at the data layer; admins can't be blocked at the controller layer.",
            "Pinning: e2e/features/social/friends-and-blocks.feature + lobby/session-visibility.feature.",
        ],
        "dark": True,
    },
    {
        "title": "RPG + VN makers (no Godot editor needed)",
        "body": [
            "/rpg-maker — Mudlet-shaped editor (areas/rooms/exits/items/NPCs). Federated remote.",
            "/vn-maker  — godot-2d-visual-novel-shaped editor (acts/scenes/lines/choices). Federated remote.",
            "Backed by rpg-builder + vn-builder side services; each has its own Postgres + Liquibase.",
            "Build pipeline: walks the tree → renders Godot 4 source → runs godot --export-release → POST /api/admin/games/upload.",
            "Bundles ship snapdragon_bridge.gdextension baked in — multiplayer-aware out of the box.",
            "publisher-flatland seed user opens pre-seeded Flatland projects, ready to build.",
        ],
    },
    {
        "title": "Common gotchas",
        "body": [
            "Sinks without autoCancel=false die on the first reconnect.",
            "@PostConstruct that touches DB needs @DependsOn(\"liquibase\") in tests.",
            "Federated WASM iframes need CORP cross-origin or you'll see ERR_BLOCKED_BY_RESPONSE.",
            "Route handlers reach the federation runtime via globalThis, not DI.",
            "Migration order matters — never re-number an existing changelog file.",
        ],
        "dark": True,
    },
    {
        "title": "Reading list",
        "body": [
            "README.md — installation, build, deploy, troubleshooting (start here).",
            "docs/PLAN-module-federation-and-native.md — the eight-section plan that produced the current shape.",
            "docs/GODOT_DEVELOPER_GUIDE.md — slug contract, presets, upload flow.",
            "docs/publishing/architecture/build/architecture.pdf — typeset reference (run `make docs-pdf`).",
            "docs/publishing/javadoc-output/index.html — branded Javadoc (run `make docs-javadocs`).",
        ],
    },
]


CLOSING = {
    "title": "You have everything you need.",
    "body": [
        "Ship a small change end-to-end before tackling a big one.",
        "Lean on the architecture PDF when something feels load-bearing.",
        "Ask for help in the team channel — every gotcha here cost someone an afternoon already.",
    ],
    "links": [
        "Repo: github.com/Snapdragon-Systems/snapdragon.games",
        "Issues + roadmap: docs/PLAN-module-federation-and-native.md",
        "Build everything locally: make docs-all",
    ],
}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    make_deck(
        title="Welcome to Snapdragon Games",
        subtitle="A 12-slide tour of the hosted architecture",
        kicker="Intro for coders",
        audience="Engineers new to this codebase. No assumed framework knowledge.",
        sections=SECTIONS,
        closing=CLOSING,
        output=args.output,
    )
    print(f"✓ wrote {args.output}")


if __name__ == "__main__":
    main()
