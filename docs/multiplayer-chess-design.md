# Multiplayer chess — a first-party Rust engine over the Snapdragon event bus

Design record for adding **multiplayer chess** to snapdragon.games. Consolidates
the full request; the keystone decision is to **reimplement the chess engine in
Rust as a project component** (not depend on GNU Chess at runtime) and drive it
through the **existing event/session architecture**.

## Requirements (consolidated)

1. A new **multiplayer chess game**, coded like Three Wizards (Godot 4, 2D), in
   the games repo.
2. Modeled on [`The-Wizard-Coder/Godot-4---Chess-2D`](https://github.com/The-Wizard-Coder/Godot-4---Chess-2D)
   (scenes/ + scripts/ + a `pieces.png` sprite sheet; PvP and a basic AI).
3. Engine: originally "use GNU Chess" → **decision: reimplement as a first-party
   Rust crate** `snapdragon-chess`, alongside the existing Rust Godot extension,
   so chess logic is part of the project (no GPL runtime dep; compiles native +
   WASM; one engine shared by client and server).
4. Run it **over the existing event architecture** — SSE channels + control/input
   verbs + multiplayer sessions — not a bespoke transport or a CLI.
5. **e2e tests covering every reachable game state between two players, using a
   couple handfuls of well-known games.**
6. **Host-in-browser authority:** whoever *creates* the game runs the entire
   engine in their browser session and is authoritative; **the server just
   relays events** between the two players.

## Decision: the engine runs entirely as WASM in the browser

Per "this all ends up running as wasm on the browser" and "our servers only do
streams" — the chess engine is **100% client-side WASM**; the server never runs
chess logic (see Authority model). Two acceptable ways to produce that WASM,
**both in the browser, never on the server**:

- **(preferred) a pure-Rust `snapdragon-chess` crate** → `wasm32-unknown-unknown`
  via the existing bridge toolchain. Small, deterministic, no C toolchain, easy
  to share with `cargo test`.
- **(allowed) wrap GNU Chess** — compile gnuchess (C) to WASM (Emscripten) +
  a thin Rust/JS wrapper. Fine IF it saves work and **runs in the browser**;
  trade-off is a heavier wasm + a C/emscripten step + GPL. Default to pure-Rust;
  revisit only if reusing gnuchess's rule logic is clearly faster.

Either way `gnuchess` (installed at `/usr/bin/gnuchess`) stays a **differential
test oracle**: `cargo test` (native) replays each move through the engine AND
gnuchess force-mode and asserts they agree on legality + terminal result. The
native build exists ONLY for tests/oracle — production is the browser wasm.

## The crate: `extensions/snapdragon-chess/` (sibling to `snapdragon-bridge`)

Pure rules core; public API the BFF, the WASM client, and the tests all use:

```rust
pub struct Game { /* board, side, castling rights, ep square, halfmove clock, history */ }
impl Game {
    pub fn new() -> Game;                       // standard start
    pub fn from_fen(fen: &str) -> Result<Game, FenError>;
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

Rules coverage (the whole point): per-piece move generation incl. sliding
pieces; **castling** (rights + empty path + not through/into/out of check);
**en passant**; **promotion** incl. underpromotion; legality filter (no moving
into check, pins); **checkmate**; **stalemate**; **draws** — fifty-move,
threefold repetition, insufficient material. Optional negamax+material AI for a
later vs-computer mode (not needed for PvP).

Targets: production is **`wasm32-unknown-unknown`** for the Godot/browser client
(reusing `tools/build-rust-wasm/`); a native `rlib` build exists only for
`cargo test` + the gnuchess differential oracle. The server links nothing.

## Authority model — host-in-browser, server relays events (req 4 & 6)

Whoever **creates** the game is the **host**: their browser runs the entire
`snapdragon-chess` WASM engine and is authoritative for board state and rule
enforcement. **The server is a pure event relay** — it never runs the engine,
never interprets a move; it only fans events out to the session's two members,
which is exactly what `GameStateService.reemitToSession(sessionId, channel,
json)` already does. This reuses the multiplayer plumbing as-is
(`MultiplayerSessionController` to create/join, per-session SSE sinks, the
`SessionIsolationTest` contract, and the `HostBridge` `input_egress` /
`*_received` signals) and keeps the BFF stateless about chess.

Flow:
- **Create** → the creator's session is the **host**; the engine boots in their
  browser (WASM). **Join** → the other member is the **guest** (thin client).
- **Guest move:** guest board emits `input_egress {uci}` → server **relays** it
  on the session's `input` channel → the host applies it in its WASM engine
  (turn-ownership + legality are enforced **at the host**).
- **Authoritative update:** the host emits the new board + status (`{fen, san,
  status, lastMove}`) on the session `world` channel → server **relays** → both
  boards render via `world_received`. The host renders its own move locally and
  echoes it for the guest.
- **Guest re-validates.** The guest embeds the same WASM engine and
  independently checks each authoritative update against its own copy — a desync
  or an illegal host update is caught locally (so this is **not** blind trust;
  both sides run identical rules). On mismatch the guest shows "host out of
  sync" and can abort.
- **Server responsibilities (events only):** create/join/leave + relay
  `input`/`world` + optionally append the move list to the session log for
  reconnect/replay. No rules, no engine, no per-move validation.

Trust note: a friendly host-authoritative model (you host a game in your
browser; your opponent joins). The guest's local re-validation is the integrity
check; a server-side referee is an *optional, off-by-default* add-on (§below)
for ranked/untrusted play.

## The Godot game: `games/chess/` (req 1, 2)

Modeled on the reference repo (a `Board` scene, 8×8 grid, piece sprites from a
sheet, click/drag, legal-move highlight, turn indicator, captured tray,
check/mate banners). **Driven over events**, not local rules: local input →
`input_egress` (the UCI move) → render the authoritative board from
`world_received`. The **WASM `snapdragon-chess`** gives instant local
legal-move highlighting + optimistic preview; the **host's** browser is authoritative, the guest re-validates locally.
Ships as a federated remote `frontend/projects/games-chess` + a WASM bundle via
the existing pipeline, registered by a Liquibase changeset (game + chapter +
`federation_modules` row) exactly like three-wizards.

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
ownership), **cross-session isolation** (a move in session A never appears in B).

Three test layers:
1. **`cargo test`** — replay each game's UCI list through `snapdragon-chess`,
   assert every move legal + the exact terminal `GameStatus`; **differential vs
   `gnuchess`** force-mode as oracle.
2. **JUnit relay/isolation** — the server is a dumb stream relay, so its tests
   assert it faithfully fans `input`/`world` events to both session members and
   **never leaks across sessions** (`SessionIsolationTest` shape). Chess
   correctness lives in layer 1 (the WASM engine), not the server.
3. **Playwright BDD** (later) — two browser contexts, drive the board UI through
   a couple of the famous games, per-step screenshots via the existing hook.

## Server runs no chess — streams only

Per "our servers only do streams for this kind of game": the BFF runs **no**
chess engine — not even an optional referee. Its entire role is the existing
per-session SSE relay (`reemitToSession`) plus create/join/leave and an optional
move-log append for reconnect. Integrity comes from **both browsers running the
identical WASM engine** (host authoritative, guest re-validates), not the
server. This removes the earlier sidecar/FFM/Java-referee question entirely:
there is nothing to bind, because the server never touches a move's meaning.

## Phased plan

- **P1 — `snapdragon-chess` crate:** full rules + the famous-games `cargo test`
  matrix + the gnuchess differential oracle. Pure logic, no BFF/Godot deps.
- **P2 — relay wiring (streams only):** the `chess` payloads ride the existing
  `input`/`world` session channels end-to-end (create/join/relay) + the JUnit
  relay/isolation tests. **No server engine.**
- **P3 — Godot `games/chess`:** board scene + WASM engine + frontend remote +
  federation/Liquibase seed + the build-pipeline wiring.
- **P4 — Playwright BDD e2e:** two players through a couple famous games.

P1 is the foundation everything depends on and is fully testable in isolation —
it is where implementation starts.
