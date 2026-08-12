"""
HugMesh Games — Godot developer step-by-step.

A 14-slide walkthrough of starting a new game in Godot, wiring it into
the per-game HugMesh project layout, and either shipping it via the
Make build chain or uploading it through the admin UI.

Edit the SECTIONS list below and re-run:
    python3 docs/publishing/slides/godot_developer.py \\
      --output docs/publishing/slides/build/godot-developer.pptx
"""
from pathlib import Path
import argparse, sys
sys.path.insert(0, str(Path(__file__).parent))
from _hugmesh_deck import make_deck   # noqa: E402


SECTIONS = [
    {
        "title": "What you'll build",
        "body": [
            "A new Godot 4.6 project under games/<game-slug>/ that opens standalone in the editor.",
            "It uses the reusable Rust GDExtension (extensions/hugmesh-bridge/) for HostBridge signals.",
            "The HugMesh Export plugin produces a deploy-ready zip with manifest.json + register.sql + bundle/.",
            "You ship it either through `make build-godot` (committed in-repo) or through /admin/games (hot upload).",
        ],
    },
    {
        "title": "Prerequisites",
        "body": [
            "Godot 4.6 in PATH as `godot` (verify: `godot --version`).",
            "Rust stable (`cargo --version`).",
            "Optional: emscripten + rustup wasm32-unknown-emscripten for the Rust GDExtension WASM target.",
            "Run once: `make build-rust` so games/<slug>/bin/libhugmesh_bridge.so is populated for every game.",
        ],
        "code": "make build-rust   →   one .so, copied into every games/<dir>/bin/",
    },
    {
        "title": "Step 1: Create the project",
        "body": [
            "1. mkdir games/my-game-slug/",
            "2. Open Godot → New Project → set path to that directory → Create.",
            "3. Copy hugmesh_bridge.gdextension and addons/hugmesh_export/ from games/three-wizards-in-a-trenchcoat/ to your new project.",
            "4. Open the project. Verify Project → Settings shows the GDExtension loaded.",
            "5. Drop a starter scene at scenes/main.tscn.",
        ],
    },
    {
        "title": "Step 2: Pick your slugs",
        "body": [
            "GAME slug    — appears in games.slug, federation_modules.slug, and (sometimes) the directory under games/.",
            "LEVEL slug(s) — one per export preset; appears in levels.slug, the export path, and the Makefile.",
            "Lowercase, hyphen-free, kebab-case. Examples: `forest-glade`, `lighthouse`, `sky-bridge`.",
            "Once committed to source, slugs do not change without a coordinated DB migration. Pick well.",
        ],
        "dark": True,
    },
    {
        "title": "Step 3: Wire HostBridge into your scene",
        "body": [
            "The reusable GDExtension exposes a HostBridge node with typed signals:",
            (1, "movement(x:float, y:float, z:float, action:String, pressed:bool)"),
            (1, "world_state(state:Dictionary)"),
            (1, "character_state(state:Dictionary)"),
            (1, "scene_state(state:Dictionary)"),
            (1, "time(state:Dictionary)"),
            "Add a HostBridge node to the scene tree, connect signals from your gameplay scripts.",
            "See games/three-wizards-in-a-trenchcoat/scripts/scene_loader.gd for a reference subscriber.",
        ],
    },
    {
        "title": "Step 4: Author one or more export presets",
        "body": [
            "Project → Export → Add new preset → Web → name it after the level slug, capitalised (e.g. `ForestGlade`).",
            "Export Path: ../../backend/src/main/resources/assets/levels/<level-slug>/index.html",
            "Custom Features: level_<level-slug>",
            "Exclude Filter: *.gdextension   (the GDExtension is shipped via build-rust, not inside the bundle)",
            "Save the .cfg, do NOT export from the editor. The Makefile drives headless export.",
        ],
        "code": "export_path=\"../../backend/src/main/resources/assets/levels/forest-glade/index.html\"",
    },
    {
        "title": "Step 5a (option A): Commit and ship",
        "body": [
            "Add games/<dir>/ to the root Makefile's GAME_PROJECTS list.",
            "Define PRESETS_<dir> with the space-separated preset names.",
            "Add a Liquibase changeset for games + levels + federation_modules + game_access. Use 017-snapdragon-catalog.xml as the template.",
            "Commit. CI will run make build, which exports your game alongside the existing ones.",
            "Run `make seed-db` to land the new rows; the home page picks the new game up after compose restart.",
        ],
        "code": "GAME_PROJECTS = three-wizards-in-a-trenchcoat dodecahedron-demo my-game-dir\nPRESETS_my-game-dir = ForestGlade",
        "dark": True,
    },
    {
        "title": "Step 5b (option B): Upload through admin",
        "body": [
            "Godot: Project → Tools → HugMesh → Export…",
            "Fill in slug, name, description, scene type.",
            "Export. Plugin writes a zip to your chosen path.",
            "Visit /admin/games, drag zip into Upload card.",
            "BFF validates schema v2, hashes, atomically moves, runs register.sql.",
            "Federation manifest discovers on next 60 s refresh.",
        ],
        "image": "diagram:game-upload-controller",
    },
    {
        "title": "What the admin sees on upload",
        "body": [
            "/admin/games shows the upload card + rollback history.",
            "Drag-and-drop or file-pick the zip you just exported.",
            "On success: new row at the top with supersededAt=NULL.",
            "On failure: the install tx + on-disk move both roll back.",
        ],
        "image": "screenshot:admin-02-uploads",
    },
    {
        "title": "manifest.json (schema v2)",
        "body": [
            "schema_version: 2",
            "game: { slug, name, description }",
            "levels: [ { slug, name, scene_type, sort_order, asset_url, scene_config, chromeless } ]",
            "federation: { slug, display_name, kind, remote_entry_url, exposed_module, version, metadata }",
            "The plugin generates the whole shape; you only fill in the editor dialog.",
        ],
        "code": "addons/hugmesh_export/manifest_builder.gd::build(inputs) → Dictionary",
    },
    {
        "title": "register.sql contract",
        "body": [
            "Runs inside the upload transaction; statements split on `;` (respecting single-quoted strings).",
            "Three INSERTs for a typical upload: games, federation_modules, game_access.",
            "Use ON CONFLICT (slug) DO UPDATE for upserts so re-uploading the same slug refreshes name + description.",
            "Never reference rows you didn't insert in the same statement — execution order inside the tx is sequential.",
            "Failure rolls everything back, including the on-disk move.",
        ],
        "dark": True,
    },
    {
        "title": "Local test loop",
        "body": [
            "Run the backend stack: docker compose up -d postgres cassandra casdoor api wasm-fragment.",
            "In Godot, press Play. scripts/api_client.gd auto-targets http://localhost:8080.",
            "scene_loader.gd polls /api/levels/{id}/scene and spawns the elements you authored.",
            "Edit a scene, press Play again. No build step required for local iteration.",
            "When ready: make build-godot && docker compose restart api wasm-fragment.",
        ],
    },
    {
        "title": "Mid-game level transitions",
        "body": [
            "Call $GameBridge.request_level_transition(slug, level_id) from GDScript.",
            "Godot posts {type:\"TW_LEVEL_TRANSITION\", slug, levelId} via window.parent.postMessage.",
            "HomeComponent (host) catches it, mints a new launch_uid for federated games, swaps the iframe.",
            "The current WASM is fully destroyed — persist character state via PUT /api/characters/{id} BEFORE calling.",
        ],
        "code": "$GameBridge.request_level_transition(\"forest-glade\", 3)",
    },
    {
        "title": "Multiplayer-aware bridge",
        "body": [
            "HostBridge gained two methods for multiplayer:",
            (1, "register_player_slot(slot, character_id) — called from multiplayer_lobby.gd as each player joins."),
            (1, "view_egress(camera_pos, look_dir) — ~10 Hz POST to /api/view fanned out to the admin observation stream."),
            "Every POST /api/controls/{character_id} MUST carry an X-Session-Id header.",
            "Without it, two players sharing a character record across sessions race Postgres' first-row pick and one gets 403.",
            "On 403, post SD_SESSION_EXPIRED — ApiBridgeService re-runs session.connect → claimControl automatically.",
            "game_bridge.gd already adds X-Session-Id; if you fork it, keep that line.",
        ],
        "dark": True,
    },
    {
        "title": "Two-player local test",
        "body": [
            "Browser-side multiplayer contract is pinned by e2e/features/multiplayer/rpg-two-players.feature.",
            "Two browser profiles (normal + incognito) — cookies must be isolated, otherwise the session tracker races.",
            "Log in as player1 + player2 (E2E_PASSWORD=e2e-pass on the integration-test profile).",
            "Profile A creates a public lobby; profile B joins on the new row. Both Launch.",
            "WASM compile is 15–90 s cold — gate input on ?sessionId= EventSource open before pressing keys.",
            "Window controls (fullscreen / open-in-new-tab / open-in-new-window with pause / resume) — e2e/features/multiplayer/window-controls.feature.",
            "Open-in-new-window posts SD_GAME_PAUSE to the parent iframe — mirror that if you wire a custom toolbar.",
        ],
    },
    {
        "title": "Common mistakes",
        "body": [
            "Slugs differ across the four contract points → iframe 404s or wrong scene loads.",
            "exclude_filter in the preset misses *.gdextension → bundle balloons by 8 MB.",
            "Calling add_child from _ready on a node already in the tree → \"parent is busy\". Use call_deferred.",
            "JavaScriptBridge.eval(...) compared to true with == → unreliable. Wrap in bool(JavaScriptBridge.eval(\"!!x\")).",
            "Forgetting to run make build-rust after pulling → games/*/bin/.so is stale.",
        ],
    },
    {
        "title": "Reading list",
        "body": [
            "docs/GODOT_DEVELOPER_GUIDE.md — the canonical contract.",
            "addons/hugmesh_export/manifest_builder.gd — manifest schema reference.",
            "games/three-wizards-in-a-trenchcoat/ — production-grade reference project.",
            "docs/publishing/architecture/build/architecture.pdf — chapter 7 on extending the portal.",
        ],
    },
]


CLOSING = {
    "title": "Ship a level. Ship a game. Either way wins.",
    "body": [
        "The fastest loop is `make build-godot` for a tweak; the slowest is the upload-pipeline test for a brand-new game.",
        "Lean on the HugMesh Export plugin — it builds the zip you want.",
        "Every contract here is enforced by tests; if you stay inside the lines, the home page will pick your game up.",
    ],
    "links": [
        "Repo: github.com/hugmesh/hugmesh-games",
        "Godot guide: docs/GODOT_DEVELOPER_GUIDE.md",
        "Architecture PDF: make docs-pdf",
    ],
}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    make_deck(
        title="Building a game on HugMesh",
        subtitle="From `mkdir games/my-game/` to a row on the home page",
        kicker="Godot developer walkthrough",
        audience="Game developers shipping a Godot 4 title onto the portal.",
        sections=SECTIONS,
        closing=CLOSING,
        output=args.output,
    )
    print(f"✓ wrote {args.output}")


if __name__ == "__main__":
    main()
