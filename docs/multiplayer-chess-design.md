# Multiplayer chess — a first-party Rust engine over the HugMesh event bus

Design record for adding **multiplayer chess** to hugmesh.games. It collects
the full request. The keystone decision: **reimplement the chess engine in Rust
as a project component** (no GNU Chess at runtime) and drive it through the
**existing event/session architecture**.

## Status

This record is partly built. What is in the repo:

- **P1, built in part:** `extensions/hugmesh-chess` has the full rules and 18
  unit tests in `src/lib.rs`: Fool's Mate, Scholar's Mate and Légal's Mate,
  en passant, promotion with underpromotion, castling (including through
  check), stalemate, insufficient material, the fifty-move rule, threefold
  repetition, resignation, and perft counts. **Not built:** the gnuchess
  differential oracle (no test calls `gnuchess`; the name appears only in
  comments) and the rest of the famous-games matrix below (Opera Game,
  Immortal Game, Byrne–Fischer, Kasparov–Topalov, and the curated endgames).
- **P2, built:** the relay is `GameStateService.reemitToSession`. Changelog
  `050-chess-relay-event-types.xml` registers the event types, and
  `SessionIsolationTest` covers isolation.
- **P3, built as web-native, not as Godot:** `games/chess/` is a canvas
  frontend driven by the `hugmesh-chess-wasm` shim through `games/_shared/`. It
  is registered by `044-chess-game.xml` and a `wasm-game` row, `games-chess`,
  in `051-web-game-federation.xml`. There is no `frontend/projects/games-chess`.
  The Godot version is a separate title, `games/chess-godot/`
  (`053-chess-godot-federation.xml`).
- **P4, built in minimum form:** the browser e2e in
  `e2e/tests/web-games-multiplayer.spec.ts` plays one move, 1.e4. The famous
  games are not played through the UI.

## Requirements (consolidated)

1. A new **multiplayer chess game**, built like Three Wizards (Godot 4, 2D), in
   the games repo.
2. Modeled on [`The-Wizard-Coder/Godot-4---Chess-2D`](https://github.com/The-Wizard-Coder/Godot-4---Chess-2D)
   (scenes/ + scripts/ + a `pieces.png` sprite sheet; PvP and a basic AI).
3. Engine: first "use GNU Chess", then **decision: reimplement as a first-party
   Rust crate** `hugmesh-chess`, beside the existing Rust Godot extension. Chess
   logic becomes part of the project: no GPL runtime dependency, compiles native
   and to WASM, one engine for client and server.
4. Run it **over the existing event architecture** (SSE channels, control/input
   verbs, multiplayer sessions), not a bespoke transport or a CLI.
5. **e2e tests covering every reachable game state between two players, using a
   couple handfuls of well-known games.**
6. **Host-in-browser authority:** whoever *creates* the game runs the whole
   engine in their browser session and is authoritative. **The server just
   relays events** between the two players.

## Decision: the engine runs entirely as WASM in the browser

Per "this all ends up running as wasm on the browser" and "our servers only do
streams", the chess engine is **100% client-side WASM**. The server never runs
chess logic (see Authority model). Two acceptable ways to produce that WASM,
**both in the browser, never on the server**:

- **(preferred) a pure-Rust `hugmesh-chess` crate** → `wasm32-unknown-unknown`
  via the existing bridge toolchain. Small, deterministic, no C toolchain, easy
  to share with `cargo test`.
- **(allowed) wrap GNU Chess:** compile gnuchess (C) to WASM (Emscripten) plus
  a thin Rust/JS wrapper. Fine IF it saves work and **runs in the browser**. The
  cost is a heavier wasm, a C/emscripten step, and the GPL. Default to
  pure-Rust; revisit only if reusing gnuchess's rule logic is clearly faster.

Either way `gnuchess` (installed at `/usr/bin/gnuchess`) stays a **differential
test oracle**: `cargo test` (native) replays each move through the engine AND
gnuchess force-mode and asserts they agree on legality and terminal result. The
native build exists ONLY for tests and the oracle. Production is the browser wasm.

## The crate: `extensions/hugmesh-chess/` (sibling to `hugmesh-bridge`)

Pure rules core. The BFF, the WASM client, and the tests all use this public API:

```rust
pub struct Game { /* board, side, castling rights, ep square, halfmove clock, history */ }
impl Game {
    pub fn new() -> Game;                       // standard start
    pub fn from_fen(fen: &str) -> Result<Game, String>;
    pub fn fen(&self) -> String;
    pub fn side_to_move(&self) -> Color;
    pub fn board(&self) -> [Option<Piece>; 64];
    pub fn legal_moves(&self) -> Vec<Move>;     // fully legal (pins/checks filtered)
    pub fn apply_uci(&mut self, uci: &str) -> Result<MoveOutcome, MoveError>; // "e2e4","e7e8q","e1g1"
    pub fn status(&self) -> GameStatus;
    pub fn resign(&mut self, who: Color) -> MoveOutcome;
}
pub enum GameStatus {
    Ongoing, Check,
    Checkmate { winner: Color },
    Stalemate,
    DrawFiftyMove, DrawThreefold, DrawInsufficientMaterial,
    Resigned { winner: Color },
}
// Move/MoveOutcome carry: from, to, piece, capture, is_castle, is_en_passant,
// promotion, gives_check, is_mate, san (algebraic), and the resulting fen.
```

Rules coverage (the whole point): per-piece move generation including sliding
pieces; **castling** (rights, empty path, not through/into/out of check);
**en passant**; **promotion** including underpromotion; a legality filter (no
moving into check, pins); **checkmate**; **stalemate**; **draws** (fifty-move,
threefold repetition, insufficient material). An optional negamax+material AI
could serve a later vs-computer mode. PvP does not need it.

Targets: production is **`wasm32-unknown-unknown`** for the browser client
(the `hugmesh-chess-wasm` shim, built by `make build-games-wasm` through
`tools/build-games-wasm/`). A native `rlib` build exists only for
`cargo test` and the gnuchess differential oracle. The server links nothing.

## Authority model — host-in-browser, server relays events (req 4 & 6)

Whoever **creates** the game is the **host**. Their browser runs the whole
`hugmesh-chess` WASM engine and is authoritative for board state and rule
enforcement. **The server is a pure event relay.** It never runs the engine and
never interprets a move; it only fans events out to the session's two members,
which is exactly what `GameStateService.reemitToSession(sessionId, channel,
json)` already does. This reuses the multiplayer plumbing as-is
(`MultiplayerSessionController` to create/join, per-session SSE sinks, the
`SessionIsolationTest` contract, and the `HostBridge` `input_egress` /
`*_received` signals) and keeps the BFF stateless about chess.

Flow:
- **Create** → the creator's session is the **host**; the engine boots in their
  browser (WASM). **Join** → the other member is the **guest** (thin client).
- **Guest move:** the guest board emits `input_egress {uci}` → the server
  **relays** it on the session's `input` channel → the host applies it in its
  WASM engine. Turn ownership and legality are enforced **at the host**.
- **Authoritative update:** the host emits the new board and status (`{fen, san,
  status, lastMove}`) on the session `world` channel → the server **relays** →
  both boards render via `world_received`. The host renders its own move
  locally and echoes it for the guest.
- **Guest re-validates.** The guest embeds the same WASM engine and checks each
  authoritative update against its own copy, independently. A desync or an
  illegal host update is caught locally, so this is **not** blind trust: both
  sides run identical rules. On a mismatch the guest shows "host out of sync"
  and can abort.
- **Server responsibilities (events only):** create/join/leave, relay
  `input`/`world`, and optionally append the move list to the session log for
  reconnect/replay. No rules, no engine, no per-move validation.

Trust note: this is a friendly host-authoritative model (you host a game in
your browser; your opponent joins). The guest's local re-validation is the
integrity check. There is no server-side referee: the section "Server runs no
chess" below drops it, even as an option.

## The Godot game: `games/chess/` (req 1, 2)

Modeled on the reference repo (a `Board` scene, 8×8 grid, piece sprites from a
sheet, click/drag, legal-move highlight, turn indicator, captured tray,
check/mate banners). **Driven over events**, not local rules: local input →
`input_egress` (the UCI move) → render the authoritative board from
`world_received`. The **WASM `hugmesh-chess`** gives instant local
legal-move highlighting and an optimistic preview. The **host's** browser is
authoritative; the guest re-validates locally.
As shipped, `games/chess/` is a web-native canvas bundle, not a Godot scene. It
has no Angular remote of its own. A `wasm-game` row in `federation_modules` (with
a game + chapter row from a Liquibase changeset) points the host at the
`wasm-fragment` bundle. The Godot board described here is `games/chess-godot/`.

## e2e test matrix — every state × a handful of famous games (req 5)

Games chosen so every reachable state is hit at least once:

| Game | States exercised |
|---|---|
| Fool's Mate (`1.f3 e5 2.g4 Qh4#`) | fastest checkmate |
| Scholar's Mate | mate via `Qxf7#`, early captures |
| Morphy "Opera Game" | captures, sacrifice, **castling**, checkmate |
| Anderssen "Immortal Game" | heavy sacrifices, checkmate |
| Byrne–Fischer "Game of the Century" | castling both sides, long tactical, mate |
| Kasparov–Topalov 1999 | many **checks**, king walk |
| curated **en-passant** game | en-passant capture (and that it's only legal immediately) |
| curated **promotion** endgame | promotion + **underpromotion** |
| curated **stalemate** study | stalemate draw |
| curated **threefold-repetition** line | draw by repetition |
| curated **fifty-move** study | fifty-move draw |
| any decisive game cut short | **resignation** terminal state |

Plus negatives: illegal-move rejection, **wrong-turn** rejection (turn
ownership), and **cross-session isolation** (a move in session A never appears
in B).

Three test layers:
1. **`cargo test`** — replay each game's UCI list through `hugmesh-chess`,
   assert every move is legal and the terminal `GameStatus` is exact, and run a
   **differential check against `gnuchess`** force-mode as oracle.
2. **JUnit relay/isolation** — the server is a dumb stream relay, so its tests
   assert that it fans `input`/`world` events to both session members faithfully
   and **never leaks across sessions** (`SessionIsolationTest` shape). Chess
   correctness lives in layer 1 (the WASM engine), not the server.
3. **Playwright BDD** (later) — two browser contexts drive the board UI through
   a couple of the famous games, with per-step screenshots from the existing hook.

## Server runs no chess — streams only

Per "our servers only do streams for this kind of game", the BFF runs **no**
chess engine, not even an optional referee. Its whole role is the existing
per-session SSE relay (`reemitToSession`) plus create/join/leave and an optional
move-log append for reconnect. Integrity comes from **both browsers running the
identical WASM engine** (host authoritative, guest re-validates), not from the
server. That ends the earlier sidecar/FFM/Java-referee question: there is
nothing to bind, because the server never touches a move's meaning.

## Phased plan

- **P1 — `hugmesh-chess` crate:** full rules, the famous-games `cargo test`
  matrix, and the gnuchess differential oracle. Pure logic, no BFF/Godot deps.
- **P2 — relay wiring (streams only):** the `chess` payloads ride the existing
  `input`/`world` session channels end to end (create/join/relay), plus the
  JUnit relay/isolation tests. **No server engine.**
- **P3 — Godot `games/chess`:** board scene, WASM engine, frontend remote,
  federation/Liquibase seed, and the build-pipeline wiring.
- **P4 — Playwright BDD e2e:** two players play a couple of famous games.

P1 is the foundation everything else depends on, and it is fully testable in
isolation. Implementation starts there.
