# Godot game template — porting a web-native game to a Godot project that reuses its Rust core

This is the **canonical recipe** for the Godot pivot:

> a web-native game (HTML/canvas + a deterministic Rust core compiled to wasm32)
> → a real **Godot 4 project outline** that **reuses the SAME Rust core** via the
> `snapdragon_bridge` GDExtension → a **Web WASM** export → a **federated app**
> the host launches like any other game.

The reference implementation is [`games/chess-godot/`](../chess-godot). It is a
complete, exportable Godot project that delegates **all chess rules** to the
existing `extensions/snapdragon-chess` Rust crate — there is **no GDScript
reimplementation** of the rules. Study it alongside this README; the two stock
games [`games/three-wizards-in-a-trenchcoat/`](../three-wizards-in-a-trenchcoat)
and [`games/dodecahedron-demo/`](../dodecahedron-demo) show the bridge transport
and the simplest possible (no-core) project respectively.

For the general Godot contract (classpath vs federated remotes, running the
editor against the live stack, asset registration, level transitions) see
[`docs/GODOT_DEVELOPER_GUIDE.md`](../../docs/GODOT_DEVELOPER_GUIDE.md). **This
file is specifically about reusing a Rust core from Godot.**

---

## The principle: Godot owns rendering + input + relay; the Rust core owns the rules

```
  Godot scene (.tscn + .gd)        ← rendering, input, board UI, animations
        │  calls (never reimplements rules)
        ▼
  ChessEngine  (GDExtension node)  ← thin wrapper, in snapdragon_bridge
        │  links as an rlib dependency
        ▼
  snapdragon-chess  (Rust crate)   ← THE rules engine: legal_moves / apply_uci /
                                      status / fen — the single source of truth,
                                      identical native (.so) and in the browser
                                      (compiled into snapdragon_bridge.wasm)

  HostBridge / BridgeGlue / BridgeApiClient  (snapdragon_bridge)
        ← session SSE relay + control egress, same as every Snapdragon Godot game
```

The web-native bundle drives the very same crate through a wasm C-ABI shim
(`extensions/snapdragon-chess-wasm`). The Godot port drives it through a Rust
**GDExtension node** instead. Both consume one validated engine, so a Godot
guest re-validating a relayed move is interchangeable with a web guest — the
`world`-channel JSON (`{"type":"chess.state","fen":..,"status":..}`) is the same.

---

## Recipe (what `chess-godot` did — do this per game)

### 1. Expose the Rust core as a GDExtension node in `snapdragon_bridge`

The cores are **pure, dependency-free `rlib` crates** that already compile for
both native and `wasm32-unknown-emscripten`. Reuse one by linking it into the
bridge and wrapping it in a `#[derive(GodotClass)]` node:

* `extensions/snapdragon-bridge/Cargo.toml` — add the core as a path dep:

  ```toml
  [dependencies]
  snapdragon-chess = { path = "../snapdragon-chess" }
  ```

* `extensions/snapdragon-bridge/src/<game>_engine.rs` — a `Node`-based
  `GodotClass` that holds one core game instance and exposes a small, JSON/string
  `#[func]` API (so it crosses every gdext version cleanly — strings, not typed
  Dictionaries). See `chess_engine.rs`: `new_game`, `load_fen`, `board_fen`,
  `legal_moves` / `legal_moves_from`, `apply_uci`, `status`, `is_terminal`,
  `resign`. **Every method delegates to the core; none implements rules.**

* `extensions/snapdragon-bridge/src/lib.rs` — `mod <game>_engine;` +
  `pub use <game>_engine::<Game>Engine;` (the `#[gdextension]` macro auto-registers
  any `GodotClass` in the crate — no manual registry).

* Unit-test the reuse path with plain `cargo test` (no Godot runtime needed):
  assert the core produces the wire shapes the web bundle relies on. See the
  `chess_engine::tests` module.

`make build-rust` then compiles the core into BOTH `libsnapdragon_bridge.so`
(native, editor F5 + tests) and `snapdragon_bridge.wasm` (the dockerised
emscripten build that ships in the Web export). One core, everywhere.

### 2. Create the Godot project outline

Mirror `games/chess-godot/`:

* `project.godot` — `config/name`, `run/main_scene`, and **enable both addon
  plugins** so the editor opens standalone:
  `enabled=PackedStringArray("res://addons/snapdragon_export/plugin.cfg", "res://addons/snapdragon_bridge/plugin.cfg")`
* `snapdragon_bridge.gdextension` (+ `.uid`) — copy verbatim from any existing
  game; paths are `res://bin/...`.
* `.gitignore` / `.gitattributes` / `.editorconfig` — copy from `dodecahedron-demo`.
  These ignore `bin/*.so|*.wasm` and the synced `addons/` (the root `.gitignore`
  also enforces `games/*/bin/*.so` etc.) — **binaries and synced addons are
  never committed**; `make build-rust` regenerates them.
* `scenes/<game>.tscn` — a `GameBridge` (Node) + sibling `HostBridge` +
  `BridgeGlue` (under GameBridge), plus your game root node. For a board game a
  `Node2D` root + a `CanvasLayer` HUD is enough (chess uses exactly this).
* `scripts/game_bridge.gd` — copy `chess-godot`'s: threads the multiplayer URL
  params into `HostBridge`, requests the SSE subscription, and wires
  `session_control_received` to pause/resume/close. **Transport only.**
* `scripts/<game>_board.gd` — renders, takes input, and calls the engine node
  for every rules decision. Instantiate the engine with
  `ClassDB.instantiate("<Game>Engine")` (guard with `ClassDB.class_exists` and
  `push_error` if missing, so a broken extension is loud, not silently
  rules-less). Relay moves with the addon `BridgeApiClient`; re-sync from the
  authoritative FEN on each `world_received`.

### 3. Add a Web export preset

`export_presets.cfg` with a single `platform="Web"` preset whose **name
lower-cases to the slug** (`make build-godot` derives the output dir that way).
`chess-godot` names it `"Chess-Godot"` → slug `chess-godot`. Set
`variant/extensions_support=true` (the GDExtension must ship), `export_filter="all_resources"`,
and `export_path="../../backend/src/main/resources/assets/levels/<slug>/index.html"`.

### 4. Wire the Makefile

In the root `Makefile` add the project dir to `GAME_PROJECTS`, add
`PRESETS_<dir> = <PresetName>`, the slug to `GAME_SLUGS`, and the project to the
`case` arms in BOTH `build-godot` and `patch-godot`.

### 5. Register + launch (federation)

Add a Liquibase migration (next number; see `053-chess-godot-federation.xml`)
that inserts:
* a `games` row + a `chapters` row (asset_url = `/_remotes/wasm-fragment/<slug>/index.html`),
* a `federation_modules` row with `kind='wasm-game'`, `game_slug=<slug>`,
  `remote_entry_url=/_remotes/wasm-fragment/<slug>/index.html`,
* a default `game_access` `scope='all'` row (else the tile is hidden),
and `<include>` it in `db.changelog-master.xml`. The host launches `wasm-game`
rows as parametrised iframes with **no frontend change**.

**Keep the web-native bundle + its 051 row as the fallback.** Ship the Godot
entry alongside (distinct slug). Cut over later by repointing the original
chapter/federation URL at the Godot bundle — or disable the new row to roll back.

### 6. Build + verify

```sh
make build-rust     # core → .so + (dockerised) snapdragon_bridge.wasm
make build-godot    # headless Web export → backend/.../assets/levels/<slug>/
cargo test -p snapdragon_bridge --lib            # reuse-path tests
```

Confirm the export produced a **real, non-empty** `index.wasm` + `index.pck` +
`index.html` (tens of MB total, not a stub). `make games-arch-e2e-up` exercises
the host-authoritative SSE relay in the deployed stack.

---

## Toolchain note (Godot 4.6.3 binary vs 4.6.1 templates)

Godot looks for export templates under `~/.local/share/godot/export_templates/<version>.stable/`.
If your binary is `4.6.3` but only `4.6.1` templates are installed, the export
fails with *"No export template found … 4.6.3.stable/web_*.zip"*. Fix by
fetching the matching templates (`make godot-export-tools` pins a version) or by
symlinking the installed templates into a matching dir:

```sh
TPL=~/.local/share/godot/export_templates
mkdir -p "$TPL/4.6.3.stable"
ln -sf "$TPL"/4.6.1.stable/web_*.zip "$TPL/4.6.3.stable/"
printf '4.6.3.stable\n' > "$TPL/4.6.3.stable/version.txt"
```

The native bridge build already handles patch-release skew via
`cargo build --features api-custom` (regenerates gdext bindings against your
actual Godot binary — see `make build-rust`).

---

## Wave plan — the remaining 13 games

Prove-the-pattern is done (`chess-godot`). The rest port in waves. Each already
has a deterministic Rust core under `extensions/` driving its web-native bundle;
the port = steps 1–6 above against that core. Turn-based games are
**host-authoritative** (relay moves, guest re-validates from authoritative
state — identical to chess). Real-time games need the realtime tick-loop relay
(see migration 049 realtime-matches + the realtime cores) rather than the
turn-based control path.

Every game already has a dependency-free `snapdragon-<slug>` core crate (plus a
`snapdragon-<slug>-wasm` C-ABI shim that the current web bundle uses). The port
reuses the **core** crate as a bridge `rlib` dep — the `-wasm` shim is left alone
as the web-native fallback.

| Game | Slug | Rust core to reuse | Cadence | Notes for the port |
|------|------|--------------------|---------|--------------------|
| Connect Four | `connect-four` | `extensions/snapdragon-connect-four` | turn-based (host-auth) | trivial 7×6 grid; mirror chess board input |
| Reversi/Othello | `reversi` | `extensions/snapdragon-reversi` | turn-based (host-auth) | flip animation is presentation-only |
| Gomoku | `gomoku` | `extensions/snapdragon-gomoku` | turn-based (host-auth) | large board; same click→`apply_move` shape |
| Dots and Boxes | `dots-and-boxes` | `extensions/snapdragon-dots-and-boxes` | turn-based (host-auth) | edges as click targets, not cells |
| Go | `go` | `extensions/snapdragon-go` | turn-based (host-auth) | scoring/ko handled by the core; UI shows territory |
| Battleship | `battleship` | `extensions/snapdragon-battleship` | turn-based (host-auth) | hidden state per slot; core owns reveal logic |
| Minesweeper | `minesweeper` | `extensions/snapdragon-minesweeper` | turn-based / single | co-op or solo; core owns mine layout + reveal |
| Sudoku | `sudoku` | `extensions/snapdragon-sudoku` | single-player | no relay needed; pure local core + persistence |
| Match Three | `match-three` | `extensions/snapdragon-match-three` | single / score-relay | cascades are core-driven; UI animates the deltas |
| Tetris | `tetris` | `extensions/snapdragon-tetris` | **real-time** | tick-loop; relay piece/lock events, not whole board |
| Tron | `tron` | `extensions/snapdragon-tron` | **real-time** | continuous input; realtime relay; trail in UI |
| Bomberman | `bomberman` | `extensions/snapdragon-bomberman` | **real-time** | grid + timers in core; realtime relay |
| Asteroids Duel | `asteroids-duel` | `extensions/snapdragon-asteroids-duel` | **real-time** | physics in core; realtime relay; 2-player duel |

Suggested wave order:
1. **Wave A (turn-based, simplest):** connect-four, reversi, gomoku, dots-and-boxes —
   nearly copy-paste of `chess-godot` with a different engine node + board shape.
2. **Wave B (turn-based, richer state):** go, battleship, minesweeper, sudoku, match-three.
3. **Wave C (real-time):** tetris, tron, bomberman, asteroids-duel — these need the
   realtime tick-loop relay path; do them last and together so the realtime
   bridge surface is proven once.

> Before each wave, confirm the target core is a dependency-free `rlib` (most are
> `["rlib","cdylib"]` already). If a core is wasm-only today, add `rlib` to its
> `crate-type` — that's the only change needed for native/Godot reuse, exactly as
> `snapdragon-chess` was already set up.
