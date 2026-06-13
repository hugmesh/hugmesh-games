# Game-ports backlog — kdegames, FPS, RTS → the Snapdragon platform

Equivalent ports of well-known open-source games onto the Snapdragon platform
(Godot 4 WASM client + the event-streaming multiplayer architecture). Generated
as a planning backlog; each row is a future epic, not yet built.

## How a port maps to the platform (the reusable pattern)

The chess work established the template ([multiplayer-chess-design.md](multiplayer-chess-design.md)):
**a first-party rules/sim engine (Rust → WASM) runs in the browser; the server
only streams events.** That generalizes by genre:

| Genre | Netcode model | Server role | Fit |
|---|---|---|---|
| Abstract / turn-based 2-player (chess, reversi, go…) | host-authoritative; guest re-validates with same WASM | relay `input`/`world` | **excellent** — reuse chess template verbatim |
| Single-player puzzles (tetris, minesweeper…) | all local WASM | none (or just score/leaderboard) | **excellent** |
| Real-time arcade duels (tron, bomberman…) | host-authoritative or lockstep, low entity count | relay input | **good** |
| **RTS** | **deterministic lockstep** — every client runs the sim, exchanges only commands | relay the command stream | **good** — lockstep IS "server only streams" |
| **FPS** | needs server reconciliation / lag-comp for fairness | authoritative tick or host | **weakest** — casual host-authoritative works; competitive needs more than a relay |

Licensing rule for every port: **code** can be referenced if GPL/MIT-compatible,
but most upstreams ship under GPL and their **art/data may not be reusable** —
budget for **libre assets** (or original Godot art) per game.

---

## A. KDE Plasma games (`kdegames` meta-package) equivalents

### A1. Two-player abstract — reuse the chess template directly (Wave 1)
Each = a small Rust rules crate (sibling to `snapdragon-chess`) + a Godot board + the chess relay.

| Snapdragon port | kdegames / upstream | Notes |
|---|---|---|
| Reversi/Othello | KReversi | trivial rules engine; perfect chess-template clone |
| Connect Four | KFourInLine | tiny engine; great first non-chess port |
| Gomoku (five-in-a-row) | Bovo | tiny engine |
| Go | Kigo (+ GNU Go as AI/oracle, like gnuchess) | ko/scoring rules non-trivial; medium |
| Battleship | Knavalbattle | hidden-state → host holds boards, reveals hits only (fits host-authoritative) |
| Dots and Boxes | KSquares | tiny engine |
| Backgammon | (not in kdegames; GNU Backgammon engine) | dice = shared RNG seed over the stream |
| Skat / card games | LSkat, KPat(engine) | hidden hands → host deals; relay plays |
| Risk-like conquest | KsirK | turn-based strategy; larger |
| Galactic conquest | Konquest | turn-based; medium |

### A2. Single-player puzzles & arcade (Wave 2) — Godot WASM, no/optional MP
| Snapdragon port | kdegames | Clone of |
|---|---|---|
| Tetris | KBlocks | Tetris |
| Minesweeper | KMines | Minesweeper |
| Sudoku | KSudoku | Sudoku |
| Solitaire suite | KPat | Klondike/FreeCell/Spider |
| Mahjong solitaire | KMahjongg, Kshisen | Shanghai/Shisen-Sho |
| Pac-Man | Kapman | Pac-Man |
| Lode Runner | KGoldrunner | Lode Runner |
| Breakout/Arkanoid | KBounce, KBreakOut | Breakout |
| Match-3 | KDiamond | Bejeweled |
| Picross/nonogram | Picmi | Picross |
| Pipe/network puzzle | KNetWalk | NetWalk |
| Robots | Killbots | Chase/Robots |
| Jigsaw | Palapeli | jigsaw |
| Black Box | KBlackBox | Black Box |
| JumpingCube | KJumpingCube | Tactics |

### A3. Real-time arcade duels (Wave 3) — 2-player real-time over the stream
| Snapdragon port | kdegames | Clone of |
|---|---|---|
| Tron light-cycles | KSnakeDuel (KTron) | Tron/Snake duel |
| Bomberman | Bomber, Granatier | Bomberman |
| Asteroids duel | KSpaceDuel | Asteroids/space duel |

---

## B. Open-source FPS + clones (Wave 4)

Approach: a **Godot-native arena FPS ("Snapdragon Arena")** as the platform
equivalent is the realistic path (the Godot/WASM toolchain already ships);
Emscripten ports of the C/C++ engines are a heavier alternative to evaluate.

| Upstream (clone of) | License | Port approach |
|---|---|---|
| Xonotic (Quake-arena, Nexuiz successor) | GPL + libre data | model for Snapdragon Arena movement/weapons; data is reusable (libre) |
| OpenArena (Quake III Arena clone) | GPL + libre data | maps/weapon set reference |
| AssaultCube / Cube | zlib-ish | lightweight — closest to a WASM port candidate |
| Cube 2: Sauerbraten / Tesseract / Red Eclipse | zlib + CC data | in-engine map editing reference |
| Warsow / Warfork (Qfusion) | GPL | fast-movement reference |
| Alien Arena | GPL | — |
| Freedoom + GZDoom/Chocolate-Doom | GPL engine + Freedoom (BSD-ish) data | a **Doom** port: Freedoom gives 100% libre assets; engine via Emscripten is proven |

**Netcode note:** FPS is the weakest fit for "server only streams." Ship a
casual **host-authoritative** mode first (matches the platform); flag that
competitive play would need an authoritative server tick + lag compensation,
which is a deliberate divergence from the relay-only model.

---

## C. RTS + clones (Wave 5)

**Lockstep is the platform's friend here:** every client runs the deterministic
sim in WASM and the server relays only the per-tick command stream — i.e. RTS
lockstep IS "our servers only do streams." Determinism (fixed-point math, sync
checksums) is the hard requirement.

| Upstream (clone of) | License / data | Port approach |
|---|---|---|
| 0 A.D. (Age-of-Empires-like) | GPL + CC-BY-SA data | gold standard; huge — model for "Snapdragon Command", reuse art under CC |
| OpenRA (C&C, Red Alert, Dune 2000) | GPL (needs original or libre assets) | faction/command model reference; assets are the blocker |
| Warzone 2100 | GPL + **fully libre data** | best reusable-assets candidate |
| MegaGlest / Glest | GPL + CC data | 3D RTS reference |
| Spring engine (Zero-K, Beyond All Reason) | GPL | large-scale TA-style; engine port is heavy |
| Widelands (Settlers-like) | GPL + libre | economy/build RTS variant |
| OpenDungeons (Dungeon Keeper-like) | GPL | dungeon-management variant |
| Wargus / Stratagus (Warcraft II engine) | GPL | classic RTS engine reference |

**Approach:** a Godot-native deterministic-lockstep RTS prototype ("Snapdragon
Command") proving the lockstep-over-stream model with a small unit set, then
scale; reuse Warzone 2100 / 0 A.D. libre assets where license-compatible.

---

## Recommended waves (the todos)

1. **Wave 1 — abstract 2-player** on the chess template: Reversi, Connect Four,
   Gomoku, Battleship, Dots-and-Boxes, Go. (Each: a Rust rules crate + Godot
   board + the chess relay. Reversi/Connect-Four first — smallest engines.)
2. **Wave 2 — single-player puzzles** (Godot WASM): Tetris, Minesweeper, Sudoku,
   Solitaire, Pac-Man, Breakout, Match-3.
3. **Wave 3 — real-time duels**: Tron, Bomberman, Asteroids-duel.
4. **Wave 4 — FPS**: Godot-native "Snapdragon Arena" (Xonotic/OpenArena-modeled,
   libre data) + spike an Emscripten Freedoom/AssaultCube WASM port.
5. **Wave 5 — RTS**: Godot-native deterministic-lockstep "Snapdragon Command"
   (Warzone 2100 / 0 A.D.-modeled, libre data) proving lockstep-over-stream.

Cross-cutting per port: pick libre assets, a rules/sim engine where applicable
(Rust→WASM, chess pattern), Godot remote + federation seed + Liquibase catalog
row, and the e2e test taxonomy (single-player state coverage; for MP, the
two-player/relay/isolation suite).
