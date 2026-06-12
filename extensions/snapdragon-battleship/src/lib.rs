//! `snapdragon-battleship` — a pure, dependency-free Battleship rules engine.
//!
//! Two players, two hidden 10×10 boards. A coordinate is `(col, row)` with both in
//! `0..10`; algebraic form is a column letter `A..J` followed by a 1-based row
//! number `1..10` (e.g. `"A1"` is `(0, 0)`, `"J10"` is `(9, 9)`). The standard
//! fleet — Carrier(5), Battleship(4), Cruiser(3), Submarine(3), Destroyer(2) — is
//! placed once per player in PHASE 1 (placement): each ship occupies a straight
//! run of cells, horizontal or vertical (never diagonal), fully on-board, never
//! overlapping another ship. Once BOTH fleets are placed the game advances to
//! PHASE 2 (firing): the two players alternate firing at a single opponent cell,
//! resolving to Miss / Hit / Sunk(ship) / Win (the shot that sinks the last
//! enemy ship).
//!
//! Host-authoritative hidden information: the HOST holds BOTH boards (it is the
//! single source of truth). A `fire` reveals only the SHOT RESULT to the opponent
//! — never the unhit cells of a board. The world payload a host broadcasts after
//! a shot therefore carries the coordinate + result, not the enemy fleet layout.
//!
//! This is the ONE engine: it compiles to `wasm32` for the in-browser game (host
//! authoritative, guest re-validates the results it is shown) and natively for
//! `cargo test`. The server runs none of it — it only streams events. See
//! `docs/multiplayer-chess-design.md` for the platform authority model.

#![allow(clippy::needless_range_loop)]

/// Board edge length: a 10×10 grid.
pub const SIZE: usize = 10;
/// Total cells on one player's board.
pub const CELLS: usize = SIZE * SIZE;

// ── players ──────────────────────────────────────────────────────────────────

/// The two players. `One` fires first in the firing phase.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Player {
    One,
    Two,
}
impl Player {
    /// The opponent — whose board this player fires at.
    pub fn opposite(self) -> Player {
        match self {
            Player::One => Player::Two,
            Player::Two => Player::One,
        }
    }
    /// Compact wire token for the `world`-channel payload.
    pub fn wire(self) -> &'static str {
        match self {
            Player::One => "p1",
            Player::Two => "p2",
        }
    }
}

// ── ships ────────────────────────────────────────────────────────────────────

/// The five ships of the standard fleet. Each player places exactly one of each.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Ship {
    Carrier,
    Battleship,
    Cruiser,
    Submarine,
    Destroyer,
}
impl Ship {
    /// Every ship type, in fleet order. Iterate this to drive placement.
    pub const ALL: [Ship; 5] = [
        Ship::Carrier,
        Ship::Battleship,
        Ship::Cruiser,
        Ship::Submarine,
        Ship::Destroyer,
    ];

    /// The cell-length of this ship's hull.
    pub fn len(self) -> usize {
        match self {
            Ship::Carrier => 5,
            Ship::Battleship => 4,
            Ship::Cruiser => 3,
            Ship::Submarine => 3,
            Ship::Destroyer => 2,
        }
    }

    /// Stable index `0..5` into the per-player fleet arrays.
    fn idx(self) -> usize {
        match self {
            Ship::Carrier => 0,
            Ship::Battleship => 1,
            Ship::Cruiser => 2,
            Ship::Submarine => 3,
            Ship::Destroyer => 4,
        }
    }

    /// Compact wire token for the `world`-channel payload.
    pub fn wire(self) -> &'static str {
        match self {
            Ship::Carrier => "carrier",
            Ship::Battleship => "battleship",
            Ship::Cruiser => "cruiser",
            Ship::Submarine => "submarine",
            Ship::Destroyer => "destroyer",
        }
    }
}

/// How a ship's hull runs across the board from its origin cell.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Orientation {
    /// Hull extends along increasing columns (eastward) from the origin.
    Horizontal,
    /// Hull extends along increasing rows (southward) from the origin.
    Vertical,
}
impl Orientation {
    pub fn wire(self) -> &'static str {
        match self {
            Orientation::Horizontal => "h",
            Orientation::Vertical => "v",
        }
    }
}

// ── status / results / errors ────────────────────────────────────────────────

/// Top-level phase / terminal classification of the game.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum GameStatus {
    /// Phase 1: one or both players are still placing their fleet.
    Placing,
    /// Phase 2: both fleets placed; players alternate firing.
    Firing,
    /// Terminal: `player`'s shots sank the entire opposing fleet.
    Win { player: Player },
}
impl GameStatus {
    pub fn is_terminal(self) -> bool {
        matches!(self, GameStatus::Win { .. })
    }
    /// Compact wire encoding for the `world`-channel payload.
    pub fn wire(self) -> String {
        match self {
            GameStatus::Placing => "placing".into(),
            GameStatus::Firing => "firing".into(),
            GameStatus::Win { player } => format!("win:{}", player.wire()),
        }
    }
}

/// The result of a legal `fire` — the ONLY information revealed to the opponent.
/// A board's un-fired cells stay hidden; only the targeted cell's fate is shown.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ShotResult {
    /// The shot struck open water.
    Miss,
    /// The shot struck a ship hull that still has un-hit cells.
    Hit,
    /// The shot struck the last un-hit cell of a ship, sinking it (others remain).
    Sunk(Ship),
    /// The shot sank the last ship of the opposing fleet — game over.
    Win(Ship),
}
impl ShotResult {
    /// Compact wire encoding for the `world`-channel payload.
    pub fn wire(self) -> String {
        match self {
            ShotResult::Miss => "miss".into(),
            ShotResult::Hit => "hit".into(),
            ShotResult::Sunk(s) => format!("sunk:{}", s.wire()),
            ShotResult::Win(s) => format!("win:{}", s.wire()),
        }
    }
}

/// Why a `place_ship` was rejected.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PlaceError {
    /// Not in the placement phase any more (a fire phase has begun).
    NotPlacing,
    /// Origin coordinate is off the board.
    OffBoard,
    /// The hull, laid from the origin, would run off the board edge.
    OutOfBounds,
    /// The hull would overlap a ship this player already placed.
    Overlap,
    /// This player has already placed this ship type.
    AlreadyPlaced,
}

/// Why a `fire` was rejected.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum FireError {
    /// Still in the placement phase — no firing yet.
    NotFiring,
    /// The game already ended with a winner.
    GameOver,
    /// Target coordinate is off the board.
    OffBoard,
    /// This cell on the opponent's board was already fired at.
    AlreadyFired,
}

// ── coordinates ──────────────────────────────────────────────────────────────

/// A board cell, `(col, row)` with both in `0..SIZE`. Construct via `Coord::new`
/// or parse algebraic form ("A1".."J10") with `Coord::parse`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Coord {
    pub col: usize,
    pub row: usize,
}
impl Coord {
    /// A coordinate from a column/row pair (unchecked — `on_board` validates).
    pub fn new(col: usize, row: usize) -> Coord {
        Coord { col, row }
    }
    /// True when this coordinate lies on the 10×10 board.
    pub fn on_board(self) -> bool {
        self.col < SIZE && self.row < SIZE
    }
    /// Flat index `0..CELLS` (row-major) for board arrays.
    fn index(self) -> usize {
        self.row * SIZE + self.col
    }
    /// Parse algebraic coordinates: a column letter `A..J` (case-insensitive)
    /// followed by a 1-based row number `1..10`. Returns `None` if malformed or
    /// off-board.
    pub fn parse(s: &str) -> Option<Coord> {
        let bytes = s.as_bytes();
        if bytes.len() < 2 {
            return None;
        }
        let c = bytes[0];
        let col = match c {
            b'A'..=b'J' => (c - b'A') as usize,
            b'a'..=b'j' => (c - b'a') as usize,
            _ => return None,
        };
        let row_str = &s[1..];
        let row_1: usize = row_str.parse().ok()?;
        if !(1..=SIZE).contains(&row_1) {
            return None;
        }
        Some(Coord {
            col,
            row: row_1 - 1,
        })
    }
    /// Render as algebraic coordinates, e.g. `(0, 0) -> "A1"`.
    pub fn to_algebraic(self) -> String {
        let c = (b'A' + self.col as u8) as char;
        format!("{c}{}", self.row + 1)
    }
}

// ── one player's fleet + incoming-fire board ─────────────────────────────────

/// A single placed ship: its set of hull cells and which of them have been hit.
#[derive(Debug, Clone)]
struct PlacedShip {
    kind: Ship,
    cells: Vec<Coord>,
    /// Parallel to `cells`: `true` once that cell has taken a hit.
    hits: Vec<bool>,
}
impl PlacedShip {
    fn is_sunk(&self) -> bool {
        self.hits.iter().all(|&h| h)
    }
}

/// One player's board: the fleet they placed, plus a record of which of THEIR
/// cells the opponent has fired at (so a repeated shot is rejected).
#[derive(Clone)]
struct Board {
    /// `ships[Ship::idx()]` is `Some` once that ship type is placed.
    ships: [Option<PlacedShip>; 5],
    /// Which cells the opponent has fired at, regardless of hit/miss.
    fired_at: [bool; CELLS],
}
impl Board {
    fn new() -> Board {
        Board {
            ships: [None, None, None, None, None],
            fired_at: [false; CELLS],
        }
    }

    /// Count of ship types placed so far.
    fn placed_count(&self) -> usize {
        self.ships.iter().filter(|s| s.is_some()).count()
    }

    /// True once all five ships are placed.
    fn fully_placed(&self) -> bool {
        self.placed_count() == Ship::ALL.len()
    }

    /// True once every placed ship is sunk (and the fleet is complete).
    fn all_sunk(&self) -> bool {
        self.fully_placed() && self.ships.iter().all(|s| s.as_ref().is_some_and(|p| p.is_sunk()))
    }

    /// True if `coord` is occupied by any placed ship hull.
    fn occupied(&self, coord: Coord) -> bool {
        self.ships
            .iter()
            .flatten()
            .any(|s| s.cells.contains(&coord))
    }

    /// Place `kind` along `cells`, after the caller has validated geometry.
    fn place(&mut self, kind: Ship, cells: Vec<Coord>) {
        let n = cells.len();
        self.ships[kind.idx()] = Some(PlacedShip {
            kind,
            cells,
            hits: vec![false; n],
        });
    }
}

// ── the game ─────────────────────────────────────────────────────────────────

/// A complete Battleship game. The host owns one of these; it holds BOTH boards
/// and is the single source of truth. Construct with `Game::new`, place both
/// fleets, call `ready` for each player (or rely on the auto-advance when the
/// tenth ship is placed), then alternate `fire`.
#[derive(Clone)]
pub struct Game {
    boards: [Board; 2],
    /// Set once a player has declared their fleet final. Both true ⇒ firing phase.
    ready: [bool; 2],
    /// Whose turn it is to fire (only meaningful in the firing phase).
    turn: Player,
    /// The winner, once the game is over.
    winner: Option<Player>,
}

impl Default for Game {
    fn default() -> Self {
        Game::new()
    }
}

impl Game {
    /// A fresh game: empty boards, placement phase, Player One to fire first once
    /// firing begins.
    pub fn new() -> Game {
        Game {
            boards: [Board::new(), Board::new()],
            ready: [false, false],
            turn: Player::One,
            winner: None,
        }
    }

    fn board(&self, p: Player) -> &Board {
        match p {
            Player::One => &self.boards[0],
            Player::Two => &self.boards[1],
        }
    }
    fn board_mut(&mut self, p: Player) -> &mut Board {
        match p {
            Player::One => &mut self.boards[0],
            Player::Two => &mut self.boards[1],
        }
    }
    fn is_ready(&self, p: Player) -> bool {
        match p {
            Player::One => self.ready[0],
            Player::Two => self.ready[1],
        }
    }
    fn set_ready(&mut self, p: Player) {
        match p {
            Player::One => self.ready[0] = true,
            Player::Two => self.ready[1] = true,
        }
    }

    /// True once both players have fully placed AND declared ready — the game is
    /// in the firing phase.
    fn firing_phase(&self) -> bool {
        self.ready[0] && self.ready[1] && self.boards[0].fully_placed() && self.boards[1].fully_placed()
    }

    /// The current status: `Placing`, `Firing`, or terminal `Win`.
    pub fn status(&self) -> GameStatus {
        if let Some(w) = self.winner {
            return GameStatus::Win { player: w };
        }
        if self.firing_phase() {
            GameStatus::Firing
        } else {
            GameStatus::Placing
        }
    }

    /// Whose turn it is to fire. Only meaningful once `status()` is `Firing`.
    pub fn turn(&self) -> Player {
        self.turn
    }

    /// How many ship types `player` has placed so far (`0..=5`).
    pub fn placed_count(&self, player: Player) -> usize {
        self.board(player).placed_count()
    }

    /// True once `player` has placed all five ships.
    pub fn fleet_complete(&self, player: Player) -> bool {
        self.board(player).fully_placed()
    }

    /// True if `ship` is sunk on `player`'s board.
    pub fn is_sunk(&self, player: Player, ship: Ship) -> bool {
        self.board(player)
            .ships[ship.idx()]
            .as_ref()
            .is_some_and(|s| s.is_sunk())
    }

    /// The hull cells a ship would occupy if laid from `origin` with `orientation`.
    /// Returns the run of `ship.len()` coordinates (may include off-board cells —
    /// `place_ship` is what rejects those).
    fn hull_cells(ship: Ship, origin: Coord, orientation: Orientation) -> Vec<Coord> {
        (0..ship.len())
            .map(|i| match orientation {
                Orientation::Horizontal => Coord::new(origin.col + i, origin.row),
                Orientation::Vertical => Coord::new(origin.col, origin.row + i),
            })
            .collect()
    }

    /// PHASE 1 — place one `ship` for `player` with its bow at `origin`, running
    /// `orientation`. Validates: still placing, on-board origin, hull fully
    /// on-board, no overlap with this player's other ships, and that this ship
    /// type isn't already placed. A successful placement is idempotent-safe to
    /// re-attempt only AFTER a different ship — re-placing the same type before
    /// firing is rejected as `AlreadyPlaced`.
    pub fn place_ship(
        &mut self,
        player: Player,
        ship: Ship,
        origin: Coord,
        orientation: Orientation,
    ) -> Result<(), PlaceError> {
        if self.firing_phase() || self.winner.is_some() {
            return Err(PlaceError::NotPlacing);
        }
        // A player who has declared ready may not keep placing.
        if self.is_ready(player) {
            return Err(PlaceError::NotPlacing);
        }
        if !origin.on_board() {
            return Err(PlaceError::OffBoard);
        }
        if self.board(player).ships[ship.idx()].is_some() {
            return Err(PlaceError::AlreadyPlaced);
        }
        let cells = Self::hull_cells(ship, origin, orientation);
        if cells.iter().any(|c| !c.on_board()) {
            return Err(PlaceError::OutOfBounds);
        }
        if cells.iter().any(|&c| self.board(player).occupied(c)) {
            return Err(PlaceError::Overlap);
        }
        self.board_mut(player).place(ship, cells);
        Ok(())
    }

    /// Declare `player`'s fleet final. Requires all five ships placed; otherwise
    /// returns `false` and the player stays in placement. When BOTH players are
    /// ready the game transitions to the firing phase (observable via `status`).
    /// Returns `true` if the player is now ready.
    pub fn ready(&mut self, player: Player) -> bool {
        if !self.board(player).fully_placed() {
            return false;
        }
        self.set_ready(player);
        true
    }

    /// PHASE 2 — `turn`'s player fires at `coord` on the OPPONENT's board.
    /// Resolves to Miss / Hit / Sunk(ship) / Win(ship). Enforces phase (firing
    /// only), turn ownership (only the side to move may fire — there is no player
    /// argument, the engine fires for `turn`), on-board target, and that the cell
    /// was not already fired at. A Miss or Hit hands the turn to the opponent; a
    /// Sunk that isn't the last ship also hands over; a Win ends the game.
    pub fn fire(&mut self, coord: Coord) -> Result<ShotResult, FireError> {
        if self.winner.is_some() {
            return Err(FireError::GameOver);
        }
        if !self.firing_phase() {
            return Err(FireError::NotFiring);
        }
        if !coord.on_board() {
            return Err(FireError::OffBoard);
        }
        let shooter = self.turn;
        let target = shooter.opposite();
        let idx = coord.index();
        if self.board(target).fired_at[idx] {
            return Err(FireError::AlreadyFired);
        }
        // Record the shot on the target's board.
        self.board_mut(target).fired_at[idx] = true;

        // Resolve against the target's fleet.
        let result = self.resolve_hit(target, coord);

        match result {
            ShotResult::Win(_) => {
                self.winner = Some(shooter);
                // turn is left as-is; the game is over.
            }
            _ => {
                // Miss, Hit, or non-final Sunk: hand the turn to the opponent.
                self.turn = target;
            }
        }
        Ok(result)
    }

    /// Apply a confirmed shot to `target`'s fleet and classify it. Assumes the
    /// cell was on-board and not previously fired at.
    fn resolve_hit(&mut self, target: Player, coord: Coord) -> ShotResult {
        let board = self.board_mut(target);
        // Find the ship (if any) whose hull contains this cell.
        for slot in board.ships.iter_mut() {
            if let Some(ship) = slot {
                if let Some(pos) = ship.cells.iter().position(|&c| c == coord) {
                    ship.hits[pos] = true;
                    if ship.is_sunk() {
                        let kind = ship.kind;
                        // Was this the last ship of the fleet?
                        if board.all_sunk() {
                            return ShotResult::Win(kind);
                        }
                        return ShotResult::Sunk(kind);
                    }
                    return ShotResult::Hit;
                }
            }
        }
        ShotResult::Miss
    }

    /// Convenience: fire at an algebraic coordinate like `"B7"`.
    pub fn fire_str(&mut self, s: &str) -> Result<ShotResult, FireError> {
        let coord = Coord::parse(s).ok_or(FireError::OffBoard)?;
        self.fire(coord)
    }

    /// Convenience: place a ship at an algebraic origin like `"A1"`.
    pub fn place_ship_str(
        &mut self,
        player: Player,
        ship: Ship,
        origin: &str,
        orientation: Orientation,
    ) -> Result<(), PlaceError> {
        let coord = Coord::parse(origin).ok_or(PlaceError::OffBoard)?;
        self.place_ship(player, ship, coord, orientation)
    }

    // ── wire / world payloads ────────────────────────────────────────────────

    /// The JSON payload the host emits on the session `world` channel after a
    /// shot. It carries the shooter, the target coordinate, and the REVEALED
    /// result only — never the opponent's hidden layout. The guest re-validates
    /// it against its own engine copy; the server only relays the string.
    pub fn shot_json(&self, shooter: Player, coord: Coord, result: ShotResult) -> String {
        format!(
            "{{\"type\":\"battleship.shot\",\"shooter\":\"{}\",\"target\":\"{}\",\"coord\":\"{}\",\"result\":\"{}\",\"next\":\"{}\",\"status\":\"{}\"}}",
            shooter.wire(),
            shooter.opposite().wire(),
            coord.to_algebraic(),
            result.wire(),
            self.turn.wire(),
            self.status().wire(),
        )
    }

    /// A re-sync snapshot of the public game state: phase, ready flags, per-player
    /// placement progress, whose turn it is, and how many ships each fleet has
    /// lost. Deliberately omits unhit hull positions — the boards stay hidden.
    pub fn snapshot_json(&self) -> String {
        let sunk = |p: Player| -> usize {
            self.board(p)
                .ships
                .iter()
                .flatten()
                .filter(|s| s.is_sunk())
                .count()
        };
        format!(
            "{{\"type\":\"battleship.snapshot\",\"status\":\"{}\",\"turn\":\"{}\",\"p1Placed\":{},\"p2Placed\":{},\"p1Ready\":{},\"p2Ready\":{},\"p1Sunk\":{},\"p2Sunk\":{}}}",
            self.status().wire(),
            self.turn.wire(),
            self.placed_count(Player::One),
            self.placed_count(Player::Two),
            self.ready[0],
            self.ready[1],
            sunk(Player::One),
            sunk(Player::Two),
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    // A standard, valid, non-overlapping fleet laid out in distinct rows so any
    // ship is trivial to place. Returns the (ship, origin, orientation) plan.
    fn standard_fleet() -> Vec<(Ship, Coord, Orientation)> {
        vec![
            (Ship::Carrier, Coord::new(0, 0), Orientation::Horizontal),   // A1..E1
            (Ship::Battleship, Coord::new(0, 1), Orientation::Horizontal), // A2..D2
            (Ship::Cruiser, Coord::new(0, 2), Orientation::Horizontal),   // A3..C3
            (Ship::Submarine, Coord::new(0, 3), Orientation::Horizontal), // A4..C4
            (Ship::Destroyer, Coord::new(0, 4), Orientation::Horizontal), // A5..B5
        ]
    }

    fn place_all(g: &mut Game, p: Player) {
        for (ship, origin, o) in standard_fleet() {
            g.place_ship(p, ship, origin, o).expect("valid placement");
        }
    }

    /// A UNIQUE open-water cell for the `n`-th harmless miss. The standard fleet
    /// occupies rows 0..5; rows 8 and 9 are guaranteed water — 20 distinct cells,
    /// indexed by `n` so no two misses ever repeat a cell.
    fn water_cell(n: usize) -> Coord {
        let row = 8 + (n / SIZE); // 8 then 9
        let col = n % SIZE;
        Coord::new(col, row)
    }

    /// Bring both players to the firing phase with the standard fleet.
    fn ready_game() -> Game {
        let mut g = Game::new();
        place_all(&mut g, Player::One);
        place_all(&mut g, Player::Two);
        assert!(g.ready(Player::One));
        assert!(g.ready(Player::Two));
        assert_eq!(g.status(), GameStatus::Firing);
        g
    }

    // ── coordinate parsing ────────────────────────────────────────────────────
    #[test]
    fn coord_parse_and_render_roundtrip() {
        assert_eq!(Coord::parse("A1"), Some(Coord::new(0, 0)));
        assert_eq!(Coord::parse("J10"), Some(Coord::new(9, 9)));
        assert_eq!(Coord::parse("E5"), Some(Coord::new(4, 4)));
        assert_eq!(Coord::parse("a1"), Some(Coord::new(0, 0))); // case-insensitive
        assert_eq!(Coord::new(0, 0).to_algebraic(), "A1");
        assert_eq!(Coord::new(9, 9).to_algebraic(), "J10");
        assert_eq!(Coord::new(4, 4).to_algebraic(), "E5");
    }

    #[test]
    fn coord_parse_rejects_malformed_and_off_board() {
        assert_eq!(Coord::parse(""), None);
        assert_eq!(Coord::parse("A"), None);
        assert_eq!(Coord::parse("K1"), None); // column past J
        assert_eq!(Coord::parse("A0"), None); // row below 1
        assert_eq!(Coord::parse("A11"), None); // row past 10
        assert_eq!(Coord::parse("1A"), None);
        assert_eq!(Coord::parse("AA"), None);
        assert!(!Coord::new(10, 0).on_board());
        assert!(!Coord::new(0, 10).on_board());
        assert!(Coord::new(9, 9).on_board());
    }

    // ── fleet geometry ────────────────────────────────────────────────────────
    #[test]
    fn ship_lengths_match_spec() {
        assert_eq!(Ship::Carrier.len(), 5);
        assert_eq!(Ship::Battleship.len(), 4);
        assert_eq!(Ship::Cruiser.len(), 3);
        assert_eq!(Ship::Submarine.len(), 3);
        assert_eq!(Ship::Destroyer.len(), 2);
        assert_eq!(Ship::ALL.len(), 5);
    }

    // ── valid placement + phase transitions ───────────────────────────────────
    #[test]
    fn fresh_game_is_placing() {
        let g = Game::new();
        assert_eq!(g.status(), GameStatus::Placing);
        assert_eq!(g.placed_count(Player::One), 0);
        assert_eq!(g.placed_count(Player::Two), 0);
        assert!(!g.fleet_complete(Player::One));
    }

    #[test]
    fn valid_horizontal_and_vertical_placements_accepted() {
        let mut g = Game::new();
        assert_eq!(
            g.place_ship(Player::One, Ship::Carrier, Coord::new(0, 0), Orientation::Horizontal),
            Ok(())
        );
        assert_eq!(g.placed_count(Player::One), 1);
        assert_eq!(
            g.place_ship(Player::One, Ship::Destroyer, Coord::new(9, 8), Orientation::Vertical),
            Ok(())
        );
        assert_eq!(g.placed_count(Player::One), 2);
    }

    #[test]
    fn placement_at_extreme_edges_is_on_board() {
        let mut g = Game::new();
        // Carrier (len 5) ending exactly on the right edge: F1..J1 -> origin (5,0).
        assert_eq!(
            g.place_ship(Player::One, Ship::Carrier, Coord::new(5, 0), Orientation::Horizontal),
            Ok(())
        );
        // Destroyer (len 2) ending exactly on the bottom edge: J9..J10 -> origin (9,8).
        assert_eq!(
            g.place_ship(Player::One, Ship::Destroyer, Coord::new(9, 8), Orientation::Vertical),
            Ok(())
        );
    }

    #[test]
    fn all_placed_transitions_to_firing_only_after_both_ready() {
        let mut g = Game::new();
        place_all(&mut g, Player::One);
        assert!(g.fleet_complete(Player::One));
        // P1 ready but P2 still placing: still placement phase.
        assert!(g.ready(Player::One));
        assert_eq!(g.status(), GameStatus::Placing);
        place_all(&mut g, Player::Two);
        assert!(g.ready(Player::Two));
        // Both ready + both fleets complete: firing phase.
        assert_eq!(g.status(), GameStatus::Firing);
        assert_eq!(g.turn(), Player::One); // P1 fires first
    }

    #[test]
    fn ready_rejected_until_fleet_complete() {
        let mut g = Game::new();
        // No ships placed: ready() returns false and does not advance.
        assert!(!g.ready(Player::One));
        // Place four of five.
        for (ship, origin, o) in standard_fleet().into_iter().take(4) {
            g.place_ship(Player::One, ship, origin, o).unwrap();
        }
        assert!(!g.ready(Player::One));
        // The fifth completes the fleet.
        let (ship, origin, o) = standard_fleet()[4];
        g.place_ship(Player::One, ship, origin, o).unwrap();
        assert!(g.ready(Player::One));
    }

    // ── invalid placements ────────────────────────────────────────────────────
    #[test]
    fn placement_off_board_origin_rejected() {
        let mut g = Game::new();
        assert_eq!(
            g.place_ship(Player::One, Ship::Carrier, Coord::new(10, 0), Orientation::Horizontal),
            Err(PlaceError::OffBoard)
        );
        assert_eq!(
            g.place_ship(Player::One, Ship::Carrier, Coord::new(0, 10), Orientation::Vertical),
            Err(PlaceError::OffBoard)
        );
    }

    #[test]
    fn placement_running_off_edge_rejected() {
        let mut g = Game::new();
        // Carrier (len 5) horizontal from G1 (col 6): G..K would need col 10 -> off-board.
        assert_eq!(
            g.place_ship(Player::One, Ship::Carrier, Coord::new(6, 0), Orientation::Horizontal),
            Err(PlaceError::OutOfBounds)
        );
        // Battleship (len 4) vertical from row 8 (index 7): rows 8,9,10,11 -> off-board.
        assert_eq!(
            g.place_ship(Player::One, Ship::Battleship, Coord::new(0, 7), Orientation::Vertical),
            Err(PlaceError::OutOfBounds)
        );
    }

    #[test]
    fn placement_overlap_rejected() {
        let mut g = Game::new();
        // Carrier on A1..E1.
        g.place_ship(Player::One, Ship::Carrier, Coord::new(0, 0), Orientation::Horizontal)
            .unwrap();
        // Battleship vertical from C1 (col 2) crosses C1 which the carrier occupies.
        assert_eq!(
            g.place_ship(Player::One, Ship::Battleship, Coord::new(2, 0), Orientation::Vertical),
            Err(PlaceError::Overlap)
        );
        // A non-overlapping placement of the same battleship still works.
        assert_eq!(
            g.place_ship(Player::One, Ship::Battleship, Coord::new(0, 1), Orientation::Horizontal),
            Ok(())
        );
    }

    #[test]
    fn placement_each_ship_only_once() {
        let mut g = Game::new();
        g.place_ship(Player::One, Ship::Cruiser, Coord::new(0, 0), Orientation::Horizontal)
            .unwrap();
        // Re-placing the same ship type is rejected even at a fresh, valid spot.
        assert_eq!(
            g.place_ship(Player::One, Ship::Cruiser, Coord::new(0, 5), Orientation::Horizontal),
            Err(PlaceError::AlreadyPlaced)
        );
    }

    #[test]
    fn overlap_is_per_player_not_cross_player() {
        // P1 and P2 may occupy the same coordinates — boards are independent.
        let mut g = Game::new();
        g.place_ship(Player::One, Ship::Carrier, Coord::new(0, 0), Orientation::Horizontal)
            .unwrap();
        assert_eq!(
            g.place_ship(Player::Two, Ship::Carrier, Coord::new(0, 0), Orientation::Horizontal),
            Ok(())
        );
    }

    #[test]
    fn cannot_place_after_declaring_ready() {
        let mut g = Game::new();
        place_all(&mut g, Player::One);
        assert!(g.ready(Player::One));
        // P1 has declared ready; further placement is rejected even though P2 is
        // still in the placement phase overall.
        // (All ship types already placed too, so AlreadyPlaced is also possible —
        // assert it is NotPlacing by using a ship type — all are placed, so use
        // the ready-guard path: re-attempt any ship returns NotPlacing.)
        let err = g
            .place_ship(Player::One, Ship::Destroyer, Coord::new(5, 5), Orientation::Horizontal)
            .unwrap_err();
        assert_eq!(err, PlaceError::NotPlacing);
    }

    // ── firing: phase + turn enforcement ─────────────────────────────────────
    #[test]
    fn cannot_fire_during_placement() {
        let mut g = Game::new();
        place_all(&mut g, Player::One);
        // P2 not placed/ready yet -> still placement phase.
        assert_eq!(g.fire(Coord::new(0, 0)), Err(FireError::NotFiring));
    }

    #[test]
    fn firing_alternates_turns() {
        let mut g = ready_game();
        assert_eq!(g.turn(), Player::One);
        // P1 fires a miss (deep water, no ship of P2 there).
        let r = g.fire(Coord::new(9, 9)).unwrap();
        assert_eq!(r, ShotResult::Miss);
        assert_eq!(g.turn(), Player::Two);
        // P2 fires a miss.
        let r = g.fire(Coord::new(9, 9)).unwrap();
        assert_eq!(r, ShotResult::Miss);
        assert_eq!(g.turn(), Player::One);
    }

    #[test]
    fn miss_hit_detection() {
        let mut g = ready_game();
        // P2's carrier is on A1..E1. P1 firing A1 is a Hit; J10 is a Miss.
        assert_eq!(g.fire(Coord::new(0, 0)).unwrap(), ShotResult::Hit);
        // Now P2's turn; have P2 miss to hand back.
        assert_eq!(g.fire(Coord::new(9, 9)).unwrap(), ShotResult::Miss);
        // P1 again: B1 is another carrier cell -> Hit (carrier still afloat).
        assert_eq!(g.fire(Coord::new(1, 0)).unwrap(), ShotResult::Hit);
    }

    #[test]
    fn cannot_fire_same_cell_twice() {
        let mut g = ready_game();
        assert_eq!(g.fire(Coord::new(9, 9)).unwrap(), ShotResult::Miss);
        // Back to P1 after P2 fires elsewhere.
        g.fire(Coord::new(8, 8)).unwrap();
        // P1 fires the same J10 again: already fired on P2's board.
        assert_eq!(g.fire(Coord::new(9, 9)), Err(FireError::AlreadyFired));
    }

    #[test]
    fn fire_off_board_rejected() {
        let mut g = ready_game();
        assert_eq!(g.fire(Coord::new(10, 0)), Err(FireError::OffBoard));
        assert_eq!(g.fire_str("Z9"), Err(FireError::OffBoard));
    }

    // ── sinking ───────────────────────────────────────────────────────────────
    #[test]
    fn sinking_a_ship_reports_sunk() {
        let mut g = ready_game();
        // P2's destroyer is on A5..B5 = (0,4),(1,4). Sink it across two P1 turns.
        // Turn 1: P1 hits A5.
        assert_eq!(g.fire(Coord::new(0, 4)).unwrap(), ShotResult::Hit);
        // P2 misses to hand back.
        g.fire(Coord::new(9, 9)).unwrap();
        // Turn 2: P1 hits B5 -> destroyer fully hit -> Sunk(Destroyer).
        assert_eq!(g.fire(Coord::new(1, 4)).unwrap(), ShotResult::Sunk(Ship::Destroyer));
        assert!(g.is_sunk(Player::Two, Ship::Destroyer));
        assert!(!g.is_sunk(Player::Two, Ship::Carrier));
        // Game is not over — four ships remain.
        assert_eq!(g.status(), GameStatus::Firing);
    }

    // ── winning (whole fleet destroyed) ──────────────────────────────────────
    #[test]
    fn destroying_whole_fleet_wins() {
        let mut g = ready_game();
        // P1 will sink P2's entire fleet. P2 misses on every one of its turns so
        // P1 keeps getting the move back. All of P2's ships are on rows 1..5,
        // columns starting at A; collect every hull cell.
        let mut targets: Vec<Coord> = Vec::new();
        for (ship, origin, o) in standard_fleet() {
            for i in 0..ship.len() {
                let c = match o {
                    Orientation::Horizontal => Coord::new(origin.col + i, origin.row),
                    Orientation::Vertical => Coord::new(origin.col, origin.row + i),
                };
                targets.push(c);
            }
        }
        let total = targets.len();
        assert_eq!(total, 5 + 4 + 3 + 3 + 2); // 17 hull cells

        let mut last_result = ShotResult::Miss;
        for (i, c) in targets.iter().enumerate() {
            assert_eq!(g.turn(), Player::One, "P1 should hold the turn at shot {i}");
            let r = g.fire(*c).unwrap();
            last_result = r;
            if i + 1 < total {
                // Not the final cell: P1 hit or sank but the game continues, so it
                // becomes P2's turn. P2 deliberately misses to hand back.
                assert_ne!(r, ShotResult::Miss, "every target cell is a ship cell");
                if let ShotResult::Win(_) = r {
                    panic!("won too early at shot {i}");
                }
                // P2's turn: fire a UNIQUE empty cell on P1's board. Rows 8 & 9
                // (indices) are all water for the standard fleet -> 20 cells, more
                // than enough distinct misses.
                let p2miss = g.fire(water_cell(i)).unwrap();
                assert_eq!(p2miss, ShotResult::Miss);
            }
        }
        // The final shot sank the last ship -> Win.
        assert!(matches!(last_result, ShotResult::Win(_)));
        assert_eq!(g.status(), GameStatus::Win { player: Player::One });
        assert!(g.status().is_terminal());
    }

    #[test]
    fn no_fire_after_game_over() {
        let mut g = ready_game();
        // Quickly destroy P2's fleet by firing all hull cells (P2 misses between).
        let mut targets: Vec<Coord> = Vec::new();
        for (ship, origin, o) in standard_fleet() {
            for i in 0..ship.len() {
                let c = match o {
                    Orientation::Horizontal => Coord::new(origin.col + i, origin.row),
                    Orientation::Vertical => Coord::new(origin.col, origin.row + i),
                };
                targets.push(c);
            }
        }
        let total = targets.len();
        for (i, c) in targets.iter().enumerate() {
            g.fire(*c).unwrap();
            if i + 1 < total {
                g.fire(water_cell(i)).unwrap();
            }
        }
        assert!(g.status().is_terminal());
        // Any further fire is rejected as GameOver.
        assert_eq!(g.fire(Coord::new(0, 9)), Err(FireError::GameOver));
        // Placement is also rejected post-game.
        assert_eq!(
            g.place_ship(Player::One, Ship::Carrier, Coord::new(0, 0), Orientation::Horizontal),
            Err(PlaceError::NotPlacing)
        );
    }

    // ── wire encodings ────────────────────────────────────────────────────────
    #[test]
    fn status_wire_encoding() {
        assert_eq!(GameStatus::Placing.wire(), "placing");
        assert_eq!(GameStatus::Firing.wire(), "firing");
        assert_eq!(GameStatus::Win { player: Player::One }.wire(), "win:p1");
        assert_eq!(GameStatus::Win { player: Player::Two }.wire(), "win:p2");
    }

    #[test]
    fn shot_result_wire_encoding() {
        assert_eq!(ShotResult::Miss.wire(), "miss");
        assert_eq!(ShotResult::Hit.wire(), "hit");
        assert_eq!(ShotResult::Sunk(Ship::Cruiser).wire(), "sunk:cruiser");
        assert_eq!(ShotResult::Win(Ship::Destroyer).wire(), "win:destroyer");
    }

    #[test]
    fn shot_json_reveals_only_result_not_layout() {
        let mut g = ready_game();
        let coord = Coord::new(0, 0);
        let r = g.fire(coord).unwrap(); // P2 carrier A1 -> Hit
        let j = g.shot_json(Player::One, coord, r);
        assert!(j.contains("\"type\":\"battleship.shot\""), "{j}");
        assert!(j.contains("\"shooter\":\"p1\""), "{j}");
        assert!(j.contains("\"target\":\"p2\""), "{j}");
        assert!(j.contains("\"coord\":\"A1\""), "{j}");
        assert!(j.contains("\"result\":\"hit\""), "{j}");
        assert!(j.contains("\"next\":\"p2\""), "{j}");
        assert!(j.contains("\"status\":\"firing\""), "{j}");
        // The payload never carries any other coordinate of the hidden fleet.
        assert!(!j.contains("B1"), "{j} should not leak unhit hull cells");
    }

    #[test]
    fn snapshot_json_tracks_public_state() {
        let mut g = Game::new();
        place_all(&mut g, Player::One);
        let j = g.snapshot_json();
        assert!(j.contains("\"type\":\"battleship.snapshot\""), "{j}");
        assert!(j.contains("\"status\":\"placing\""), "{j}");
        assert!(j.contains("\"p1Placed\":5"), "{j}");
        assert!(j.contains("\"p2Placed\":0"), "{j}");
        assert!(j.contains("\"p1Ready\":false"), "{j}");
        assert!(j.contains("\"p1Sunk\":0"), "{j}");
    }

    // ── algebraic convenience helpers ────────────────────────────────────────
    #[test]
    fn algebraic_place_and_fire_helpers() {
        let mut g = Game::new();
        g.place_ship_str(Player::One, Ship::Carrier, "A1", Orientation::Horizontal)
            .unwrap();
        // Reuse the standard plan for the rest.
        for (ship, origin, o) in standard_fleet().into_iter().skip(1) {
            g.place_ship(Player::One, ship, origin, o).unwrap();
        }
        place_all(&mut g, Player::Two);
        g.ready(Player::One);
        g.ready(Player::Two);
        // P1 fires A1 algebraically: P2's carrier -> Hit.
        assert_eq!(g.fire_str("A1").unwrap(), ShotResult::Hit);
    }

    // ── full scripted game to a win ──────────────────────────────────────────
    #[test]
    fn scripted_game_runs_to_win() {
        let mut g = ready_game();
        // Walk P1 through every P2 hull cell, P2 misses between. Mirrors a real
        // game's full fire pipeline end-to-end.
        let mut p2_cells: Vec<Coord> = Vec::new();
        for (ship, origin, o) in standard_fleet() {
            p2_cells.extend(Game::hull_cells(ship, origin, o));
        }
        let n = p2_cells.len();
        for (i, c) in p2_cells.iter().enumerate() {
            let r = g.fire(*c).unwrap();
            if i + 1 == n {
                assert!(matches!(r, ShotResult::Win(_)));
            } else {
                assert!(matches!(r, ShotResult::Hit | ShotResult::Sunk(_)));
                g.fire(water_cell(i)).unwrap(); // P2 misses a unique water cell
            }
        }
        assert_eq!(g.status(), GameStatus::Win { player: Player::One });
    }
}
