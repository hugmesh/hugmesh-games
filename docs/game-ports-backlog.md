# Game-ports backlog — kdegames, FPS, RTS → the HugMesh platform

Equivalent ports of well-known open-source games onto the HugMesh platform
(Godot 4 WASM client + the event-streaming multiplayer architecture). This is a
planning backlog. When it was written, each row was a future epic and nothing
was built. The rows that shipped did not use a Godot client. They are
web-native: a Rust engine compiled to `wasm32`, a canvas frontend, and the
`games/_shared/` shell. Only chess and Connect Four also have Godot versions
(`games/chess-godot/`, `games/connect-four-godot/`).

> **Since then, 13 of the rows below have shipped** (Wave 1 and Wave 2 in part,
> Wave 3 completely). See [Already shipped](#already-shipped-13-titles-since-this-backlog-was-written)
> for the corrected status, cross-checked against the [README's catalog table](../README.md#two-game-technologies)
> and the `games/` / `extensions/` directories. The tables below now list only
> the titles that are still not built.

## Already shipped (13 titles, since this backlog was written)

These rows have merged to `main` as part of the 14-title "Web-native
Rust→wasm32" family (see the [README](../README.md#two-game-technologies)) and
are gone from the "not yet built" tables below. `chess` shipped too, but it was
the template the rest of this doc is built on, not a backlog row.

| HugMesh port | Wave | Upstream reference | Ships as |
|---|---|---|---|
| Reversi/Othello | Wave 1 | KReversi | [`extensions/hugmesh-reversi`](../extensions/hugmesh-reversi), [`games/reversi`](../games/reversi) |
| Connect Four | Wave 1 | KFourInLine | [`extensions/hugmesh-connect-four`](../extensions/hugmesh-connect-four), [`games/connect-four`](../games/connect-four) |
| Gomoku (five-in-a-row) | Wave 1 | Bovo | [`extensions/hugmesh-gomoku`](../extensions/hugmesh-gomoku), [`games/gomoku`](../games/gomoku) |
| Go | Wave 1 | Kigo | [`extensions/hugmesh-go`](../extensions/hugmesh-go), [`games/go`](../games/go) |
| Battleship | Wave 1 | Knavalbattle | [`extensions/hugmesh-battleship`](../extensions/hugmesh-battleship), [`games/battleship`](../games/battleship) |
| Dots and Boxes | Wave 1 | KSquares | [`extensions/hugmesh-dots-and-boxes`](../extensions/hugmesh-dots-and-boxes), [`games/dots-and-boxes`](../games/dots-and-boxes) |
| Tetris | Wave 2 | KBlocks | [`extensions/hugmesh-tetris`](../extensions/hugmesh-tetris), [`games/tetris`](../games/tetris) |
| Minesweeper | Wave 2 | KMines | [`extensions/hugmesh-minesweeper`](../extensions/hugmesh-minesweeper), [`games/minesweeper`](../games/minesweeper) |
| Sudoku | Wave 2 | KSudoku | [`extensions/hugmesh-sudoku`](../extensions/hugmesh-sudoku), [`games/sudoku`](../games/sudoku) |
| Match-3 | Wave 2 | KDiamond | [`extensions/hugmesh-match-three`](../extensions/hugmesh-match-three), [`games/match-three`](../games/match-three) |
| Tron light-cycles | Wave 3 | KSnakeDuel (KTron) | [`extensions/hugmesh-tron`](../extensions/hugmesh-tron), [`games/tron`](../games/tron) |
| Bomberman | Wave 3 | Bomber, Granatier | [`extensions/hugmesh-bomberman`](../extensions/hugmesh-bomberman), [`games/bomberman`](../games/bomberman) |
| Asteroids duel | Wave 3 | KSpaceDuel | [`extensions/hugmesh-asteroids-duel`](../extensions/hugmesh-asteroids-duel), [`games/asteroids-duel`](../games/asteroids-duel) |

**Wave 3 (real-time arcade duels) is now fully shipped.** All three rows are
done, so the Wave 3 table is gone. Waves 1 and 2 are partly shipped, and the
tables below list only their remaining rows.

## How a port maps to the platform (the reusable pattern)

The chess work set the template ([multiplayer-chess-design.md](multiplayer-chess-design.md)):
**a first-party rules/sim engine (Rust → WASM) runs in the browser, and the
server only streams events.** That generalizes by genre. The RTS and FPS rows
predate [the server-authoritative design](realtime-server-authoritative-design.md).
That design chose a server-run tick for both genres and keeps lockstep only as
a scale fallback for RTS. Read those two rows as the original assumption, not
the current plan:

| Genre | Netcode model | Server role | Fit |
|---|---|---|---|
| Abstract / turn-based 2-player (chess, reversi, go…) | host-authoritative; guest re-validates with same WASM | relay `input`/`world` | **excellent** — reuse chess template verbatim |
| Single-player puzzles (tetris, minesweeper…) | all local WASM | none (or just score/leaderboard) | **excellent** |
| Real-time arcade duels (tron, bomberman…) | host-authoritative or lockstep, low entity count | relay input | **good** |
| **RTS** | **deterministic lockstep** — every client runs the sim, exchanges only commands | relay the command stream | **good** — lockstep IS "server only streams" |
| **FPS** | needs server reconciliation / lag-comp for fairness | authoritative tick or host | **weakest** — casual host-authoritative works; competitive needs more than a relay |

Licensing rule for every port: you may reference **code** if it is GPL/MIT-compatible,
but most upstreams ship under GPL and their **art and data may not be reusable**.
Budget for **libre assets** (or original Godot art) per game.

---

## A. KDE Plasma games (`kdegames` meta-package) equivalents

### A1. Two-player abstract — reuse the chess template directly (Wave 1)
Each = a small Rust rules crate (sibling to `hugmesh-chess`) + a board frontend + the chess relay. The shipped rows use a canvas frontend under `games/<slug>/`. A Godot board is the optional second step (see `games/_godot-template/`).

**6 of the original 10 rows have shipped**: Reversi, Connect Four, Gomoku, Go,
Battleship, and Dots and Boxes. See
[Already shipped](#already-shipped-13-titles-since-this-backlog-was-written).
The rows still not built:

| HugMesh port | kdegames / upstream | Notes |
|---|---|---|
| Backgammon | (not in kdegames; GNU Backgammon engine) | dice = shared RNG seed over the stream |
| Skat / card games | LSkat, KPat(engine) | hidden hands → host deals; relay plays |
| Risk-like conquest | KsirK | turn-based strategy; larger |
| Galactic conquest | Konquest | turn-based; medium |

### A2. Single-player puzzles & arcade (Wave 2) — Godot WASM, no/optional MP

**4 of the original 15 rows have shipped**: Tetris, Minesweeper, Sudoku, and
Match-3. See
[Already shipped](#already-shipped-13-titles-since-this-backlog-was-written).
The rows still not built:

| HugMesh port | kdegames | Clone of |
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

**All 3 rows have shipped**: Tron, Bomberman, and Asteroids duel. Wave 3 is
complete. See [Already shipped](#already-shipped-13-titles-since-this-backlog-was-written).

---

## B. Open-source FPS + clones (Wave 4)

Approach: a **Godot-native arena FPS ("HugMesh Arena")** is the realistic
platform equivalent, since the Godot/WASM toolchain already ships. Emscripten
ports of the C/C++ engines are a heavier alternative to evaluate.

| Upstream (clone of) | License | Port approach |
|---|---|---|
| Xonotic (Quake-arena, Nexuiz successor) | GPL + libre data | model for HugMesh Arena movement/weapons; data is reusable (libre) |
| OpenArena (Quake III Arena clone) | GPL + libre data | maps/weapon set reference |
| AssaultCube / Cube | zlib-ish | lightweight — closest to a WASM port candidate |
| Cube 2: Sauerbraten / Tesseract / Red Eclipse | zlib + CC data | in-engine map editing reference |
| Warsow / Warfork (Qfusion) | GPL | fast-movement reference |
| Alien Arena | GPL | — |
| Freedoom + GZDoom/Chocolate-Doom | GPL engine + Freedoom (BSD-ish) data | a **Doom** port: Freedoom gives 100% libre assets; engine via Emscripten is proven |

**Netcode note:** FPS is the weakest fit for "server only streams." Ship a
casual **host-authoritative** mode first, which matches the platform. Competitive
play would need an authoritative server tick and lag compensation, a deliberate
break from the relay-only model.

---

## C. RTS + clones (Wave 5)

**Lockstep is the platform's friend here.** Every client runs the deterministic
sim in WASM and the server relays only the per-tick command stream, so RTS
lockstep IS "our servers only do streams." Determinism (fixed-point math, sync
checksums) is the hard requirement.

| Upstream (clone of) | License / data | Port approach |
|---|---|---|
| 0 A.D. (Age-of-Empires-like) | GPL + CC-BY-SA data | gold standard; huge — model for "HugMesh Command", reuse art under CC |
| OpenRA (C&C, Red Alert, Dune 2000) | GPL (needs original or libre assets) | faction/command model reference; assets are the blocker |
| Warzone 2100 | GPL + **fully libre data** | best reusable-assets candidate |
| MegaGlest / Glest | GPL + CC data | 3D RTS reference |
| Spring engine (Zero-K, Beyond All Reason) | GPL | large-scale TA-style; engine port is heavy |
| Widelands (Settlers-like) | GPL + libre | economy/build RTS variant |
| OpenDungeons (Dungeon Keeper-like) | GPL | dungeon-management variant |
| Wargus / Stratagus (Warcraft II engine) | GPL | classic RTS engine reference |

**Approach:** a Godot-native deterministic-lockstep RTS prototype ("HugMesh
Command") that proves the lockstep-over-stream model with a small unit set, then
scales. Reuse Warzone 2100 / 0 A.D. libre assets where the licenses allow.

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
   shipped**, with no remaining rows.
4. **Wave 4 — FPS**: Godot-native "HugMesh Arena" (Xonotic/OpenArena-modeled,
   libre data) plus a spike of an Emscripten Freedoom/AssaultCube WASM port. Not
   yet started as a catalog title. `extensions/hugmesh-fps-core` (the
   server-authoritative sim engine, not a title) is 🚧 in-flight per the
   [README roadmap](../README.md#in-progress--server-authoritative-realtime-engines-catalog-launch-deferred).
5. **Wave 5 — RTS**: Godot-native deterministic-lockstep "HugMesh Command"
   (Warzone 2100 / 0 A.D.-modeled, libre data) that proves lockstep-over-stream.
   Not yet started as a catalog title. `extensions/hugmesh-rts-core` (the
   engine, not a title) is likewise 🚧 in-flight per the README roadmap.

Cross-cutting per port: pick libre assets; write a rules/sim engine where it
applies (Rust→WASM, chess pattern); add a frontend, a federation seed (a
`wasm-game` row) and a Liquibase catalog row; and follow the e2e test taxonomy
(single-player state coverage; for multiplayer, the two-player/relay/isolation
suite).
