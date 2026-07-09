# Game-ports backlog — kdegames, FPS, RTS → the Snapdragon platform

Equivalent ports of well-known open-source games onto the Snapdragon platform
(Godot 4 WASM client + the event-streaming multiplayer architecture). Generated
as a planning backlog; each row was a future epic, not yet built, at the time
this doc was written.

> **Since then, 13 of the rows below have shipped** (Wave 1 partially, Wave 2
> partially, Wave 3 completely) — see [Already shipped](#already-shipped-13-titles-since-this-backlog-was-written)
> for the corrected status, cross-checked against the [README's catalog table](../README.md#two-game-technologies)
> and the `games/` / `extensions/` directories. The tables below now list only
> the titles that are genuinely still not built.

## Already shipped (13 titles, since this backlog was written)

These rows have merged to `main` as part of the 14-title "Web-native
Rust→wasm32" family (see the [README](../README.md#two-game-technologies)) and
are removed from the "not yet built" tables further down. `chess` itself
shipped too, but it was the pre-existing template the rest of this doc is
built on, not a backlog row.

| Snapdragon port | Wave | Upstream reference | Ships as |
|---|---|---|---|
| Reversi/Othello | Wave 1 | KReversi | [`extensions/snapdragon-reversi`](../extensions/snapdragon-reversi), [`games/reversi`](../games/reversi) |
| Connect Four | Wave 1 | KFourInLine | [`extensions/snapdragon-connect-four`](../extensions/snapdragon-connect-four), [`games/connect-four`](../games/connect-four) |
| Gomoku (five-in-a-row) | Wave 1 | Bovo | [`extensions/snapdragon-gomoku`](../extensions/snapdragon-gomoku), [`games/gomoku`](../games/gomoku) |
| Go | Wave 1 | Kigo | [`extensions/snapdragon-go`](../extensions/snapdragon-go), [`games/go`](../games/go) |
| Battleship | Wave 1 | Knavalbattle | [`extensions/snapdragon-battleship`](../extensions/snapdragon-battleship), [`games/battleship`](../games/battleship) |
| Dots and Boxes | Wave 1 | KSquares | [`extensions/snapdragon-dots-and-boxes`](../extensions/snapdragon-dots-and-boxes), [`games/dots-and-boxes`](../games/dots-and-boxes) |
| Tetris | Wave 2 | KBlocks | [`extensions/snapdragon-tetris`](../extensions/snapdragon-tetris), [`games/tetris`](../games/tetris) |
| Minesweeper | Wave 2 | KMines | [`extensions/snapdragon-minesweeper`](../extensions/snapdragon-minesweeper), [`games/minesweeper`](../games/minesweeper) |
| Sudoku | Wave 2 | KSudoku | [`extensions/snapdragon-sudoku`](../extensions/snapdragon-sudoku), [`games/sudoku`](../games/sudoku) |
| Match-3 | Wave 2 | KDiamond | [`extensions/snapdragon-match-three`](../extensions/snapdragon-match-three), [`games/match-three`](../games/match-three) |
| Tron light-cycles | Wave 3 | KSnakeDuel (KTron) | [`extensions/snapdragon-tron`](../extensions/snapdragon-tron), [`games/tron`](../games/tron) |
| Bomberman | Wave 3 | Bomber, Granatier | [`extensions/snapdragon-bomberman`](../extensions/snapdragon-bomberman), [`games/bomberman`](../games/bomberman) |
| Asteroids duel | Wave 3 | KSpaceDuel | [`extensions/snapdragon-asteroids-duel`](../extensions/snapdragon-asteroids-duel), [`games/asteroids-duel`](../games/asteroids-duel) |

**Wave 3 (real-time arcade duels) is now fully shipped** — all three of its
rows are done, so the Wave 3 table below has been removed. Wave 1 and Wave 2
are partially shipped; the tables below list only their remaining rows.

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

**6 of the original 10 rows have shipped** — Reversi, Connect Four, Gomoku, Go,
Battleship, and Dots and Boxes are done; see
[Already shipped](#already-shipped-13-titles-since-this-backlog-was-written).
The remaining, genuinely-not-yet-built rows:

| Snapdragon port | kdegames / upstream | Notes |
|---|---|---|
| Backgammon | (not in kdegames; GNU Backgammon engine) | dice = shared RNG seed over the stream |
| Skat / card games | LSkat, KPat(engine) | hidden hands → host deals; relay plays |
| Risk-like conquest | KsirK | turn-based strategy; larger |
| Galactic conquest | Konquest | turn-based; medium |

### A2. Single-player puzzles & arcade (Wave 2) — Godot WASM, no/optional MP

**4 of the original 15 rows have shipped** — Tetris, Minesweeper, Sudoku, and
Match-3 are done; see
[Already shipped](#already-shipped-13-titles-since-this-backlog-was-written).
The remaining, genuinely-not-yet-built rows:

| Snapdragon port | kdegames | Clone of |
|---|---|---|
| Solitaire suite | KPat | Klondike/FreeCell/Spider |
| Mahjong solitaire | KMahjongg, Kshisen | Shanghai/Shisen-Sho |
| Pac-Man | Kapman | Pac-Man |
| Lode Runner | KGoldrunner | Lode Runner |
| Breakout/Arkanoid | KBounce, KBreakOut | Breakout |
| Picross/nonogram | Picmi | Picross |
| Pipe/network puzzle | KNetWalk | NetWalk |
| Robots | Killbots | Chase/Robots |
| Jigsaw | Palapeli | jigsaw |
| Black Box | KBlackBox | Black Box |
| JumpingCube | KJumpingCube | Tactics |

### A3. Real-time arcade duels (Wave 3) — 2-player real-time over the stream

**All 3 rows have shipped** — Tron, Bomberman, and Asteroids duel are done;
Wave 3 is complete. See [Already shipped](#already-shipped-13-titles-since-this-backlog-was-written).

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

1. **Wave 1 — abstract 2-player** on the chess template. ✅ **Shipped:**
   Reversi, Connect Four, Gomoku, Battleship, Dots-and-Boxes, Go. Remaining:
   Backgammon, Skat/card games, Risk-like conquest (KsirK), Galactic conquest
   (Konquest).
2. **Wave 2 — single-player puzzles** (Godot WASM). ✅ **Shipped:** Tetris,
   Minesweeper, Sudoku, Match-3. Remaining: Solitaire, Mahjong solitaire,
   Pac-Man, Lode Runner, Breakout, Picross, Pipe/network puzzle, Robots,
   Jigsaw, Black Box, JumpingCube.
3. **Wave 3 — real-time duels**: Tron, Bomberman, Asteroids-duel. ✅ **Fully
   shipped** — no remaining rows.
4. **Wave 4 — FPS**: Godot-native "Snapdragon Arena" (Xonotic/OpenArena-modeled,
   libre data) + spike an Emscripten Freedoom/AssaultCube WASM port. Not yet
   started as a catalog title — note that `extensions/snapdragon-fps-core`
   (the server-authoritative sim engine, not a title) is 🚧 in-flight per the
   [README roadmap](../README.md#in-progress--server-authoritative-realtime-engines-catalog-launch-deferred).
5. **Wave 5 — RTS**: Godot-native deterministic-lockstep "Snapdragon Command"
   (Warzone 2100 / 0 A.D.-modeled, libre data) proving lockstep-over-stream.
   Not yet started as a catalog title — `extensions/snapdragon-rts-core` (the
   engine, not a title) is likewise 🚧 in-flight per the README roadmap.

Cross-cutting per port: pick libre assets, a rules/sim engine where applicable
(Rust→WASM, chess pattern), Godot remote + federation seed + Liquibase catalog
row, and the e2e test taxonomy (single-player state coverage; for MP, the
two-player/relay/isolation suite).
