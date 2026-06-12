//! `snapdragon-gomoku` — a pure, dependency-free Gomoku (five-in-a-row) engine.
//!
//! Freestyle rules on a 15×15 board: players alternate placing a stone on an
//! empty point; the first to get FIVE OR MORE of their own stones in a straight
//! line (horizontal, vertical, or either diagonal) wins. A full board with no
//! winner is a draw. There are no captures, no overline restriction, and no
//! opening constraints (no swap / renju "forbidden moves") — pure freestyle.
//!
//! Point indexing: `0 = (col 0, row 0)` (top-left), `col = idx % 15`,
//! `row = idx / 15`. Points are addressed as `(col, row)` or by raw index.
//!
//! This is the ONE engine: it compiles to `wasm32` for the in-browser game (host
//! authoritative, guest re-validates) and natively for `cargo test`. The server
//! runs none of it — it only streams events. Mirrors the `snapdragon-chess`
//! sibling crate in structure: a `GameStatus` enum with a compact `wire()`
//! encoding and a `MoveOutcome::to_json()` payload for the session `world`
//! channel that the server relays verbatim and the guest re-validates.

#![allow(clippy::needless_range_loop)]

/// Board edge length. Freestyle gomoku is played on 15×15.
pub const SIZE: usize = 15;
/// Total number of points on the board (225).
pub const POINTS: usize = SIZE * SIZE;
/// Stones in a row required to win.
pub const WIN_LEN: usize = 5;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Player {
    /// Plays first.
    Black,
    /// Plays second.
    White,
}
impl Player {
    pub fn opposite(self) -> Player {
        if self == Player::Black { Player::White } else { Player::Black }
    }
}

fn player_wire(p: Player) -> &'static str {
    if p == Player::Black { "black" } else { "white" }
}

/// What occupies a single point.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Cell {
    Empty,
    Stone(Player),
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum GameStatus {
    Ongoing,
    Win { player: Player },
    Draw,
}
impl GameStatus {
    pub fn is_terminal(self) -> bool {
        !matches!(self, GameStatus::Ongoing)
    }
    /// Compact wire encoding for the `world`-channel payload.
    pub fn wire(self) -> String {
        match self {
            GameStatus::Ongoing => "ongoing".into(),
            GameStatus::Win { player } => format!("win:{}", player_wire(player)),
            GameStatus::Draw => "draw".into(),
        }
    }
}

#[derive(Debug, PartialEq, Eq)]
pub enum MoveError {
    /// The supplied point is off the board.
    OffBoard,
    /// The target point already holds a stone.
    Occupied,
    /// The game has already ended (win or draw).
    GameOver,
    /// The wire/coordinate string could not be parsed.
    BadCoord,
}

/// The structured result of a successful `place`. Mirrors chess's `MoveOutcome`:
/// it carries the move, the resulting status, and a `to_json()` `world`-channel
/// payload the host emits and the guest re-validates.
#[derive(Debug, Clone, PartialEq)]
pub struct MoveOutcome {
    /// Who placed the stone.
    pub player: Player,
    /// Raw board index of the placed stone (`row * SIZE + col`).
    pub point: u16,
    /// `true` if this stone completed five-or-more in a row.
    pub is_win: bool,
    /// The line of points that won, if any (length ≥ `WIN_LEN`), ordered.
    pub win_line: Option<Vec<u16>>,
    /// Status of the game AFTER this move.
    pub status: GameStatus,
}

impl MoveOutcome {
    /// The JSON payload the host emits on the session `world` channel after a
    /// move; the guest re-validates it against its own engine copy. The server
    /// only relays this string — it never parses it.
    pub fn to_json(&self) -> String {
        let last = coord_str(self.point);
        let line = match &self.win_line {
            Some(l) => {
                let parts: Vec<String> = l.iter().map(|&p| format!("\"{}\"", coord_str(p))).collect();
                format!("[{}]", parts.join(","))
            }
            None => "[]".to_string(),
        };
        format!(
            "{{\"type\":\"gomoku.state\",\"player\":\"{}\",\"lastMove\":\"{}\",\"status\":\"{}\",\"win\":{},\"winLine\":{}}}",
            player_wire(self.player), last, self.status.wire(), self.is_win, line
        )
    }
}

// ── point helpers ────────────────────────────────────────────────────────────
fn col(idx: u16) -> i32 { (idx as usize % SIZE) as i32 }
fn row(idx: u16) -> i32 { (idx as usize / SIZE) as i32 }
fn pidx(c: i32, r: i32) -> u16 { (r as usize * SIZE + c as usize) as u16 }
fn on_board(c: i32, r: i32) -> bool {
    (0..SIZE as i32).contains(&c) && (0..SIZE as i32).contains(&r)
}

/// Parse an algebraic point like `"h8"` / `"a1"` / `"o15"`. Columns are letters
/// `a`..`o` (15 files); rows are `1`..`15` counting from the top (row `1` is
/// index row 0). Returns `None` on anything off-board or malformed.
pub fn parse_coord(s: &str) -> Option<u16> {
    let b = s.as_bytes();
    if b.len() < 2 { return None; }
    let c = b[0].to_ascii_lowercase().wrapping_sub(b'a') as i32;
    let r_str = std::str::from_utf8(&b[1..]).ok()?;
    let r1: i32 = r_str.parse().ok()?;
    let r = r1 - 1;
    if on_board(c, r) { Some(pidx(c, r)) } else { None }
}

/// Render a raw index as algebraic, e.g. `8 -> "i1"`, the inverse of [`parse_coord`].
pub fn coord_str(idx: u16) -> String {
    let c = (b'a' + (idx as usize % SIZE) as u8) as char;
    let r = idx as usize / SIZE + 1;
    format!("{c}{r}")
}

// ── the game ─────────────────────────────────────────────────────────────────
#[derive(Clone)]
pub struct Game {
    board: [Cell; POINTS],
    turn: Player,
    moves_made: u16,
    status: GameStatus,
}

impl Default for Game {
    fn default() -> Self { Game::new() }
}

impl Game {
    /// A fresh 15×15 board; Black to move.
    pub fn new() -> Game {
        Game {
            board: [Cell::Empty; POINTS],
            turn: Player::Black,
            moves_made: 0,
            status: GameStatus::Ongoing,
        }
    }

    pub fn side_to_move(&self) -> Player { self.turn }
    pub fn board(&self) -> [Cell; POINTS] { self.board }
    pub fn status(&self) -> GameStatus { self.status }

    /// Look up a single point by raw index.
    pub fn at(&self, idx: u16) -> Cell {
        self.board.get(idx as usize).copied().unwrap_or(Cell::Empty)
    }
    /// Look up a single point by `(col, row)`; off-board ⇒ `None`.
    pub fn get(&self, c: i32, r: i32) -> Option<Cell> {
        if on_board(c, r) { Some(self.board[pidx(c, r) as usize]) } else { None }
    }

    /// Every legal move: all empty points (none once the game is terminal).
    pub fn legal_moves(&self) -> Vec<u16> {
        if self.status.is_terminal() { return Vec::new(); }
        (0..POINTS as u16).filter(|&i| self.board[i as usize] == Cell::Empty).collect()
    }

    /// `true` iff placing on `idx` is currently legal (in-bounds, empty, live game).
    pub fn is_legal(&self, idx: u16) -> bool {
        !self.status.is_terminal()
            && (idx as usize) < POINTS
            && self.board[idx as usize] == Cell::Empty
    }

    /// Place a stone for the side to move on raw index `idx`.
    /// Enforces turn ownership implicitly (the engine tracks whose turn it is),
    /// in-bounds, emptiness, and game-not-over; returns the structured outcome.
    pub fn place(&mut self, idx: u16) -> Result<MoveOutcome, MoveError> {
        if self.status.is_terminal() { return Err(MoveError::GameOver); }
        if (idx as usize) >= POINTS { return Err(MoveError::OffBoard); }
        if self.board[idx as usize] != Cell::Empty { return Err(MoveError::Occupied); }

        let player = self.turn;
        self.board[idx as usize] = Cell::Stone(player);
        self.moves_made += 1;

        let win_line = self.winning_line_through(idx, player);
        let is_win = win_line.is_some();
        if is_win {
            self.status = GameStatus::Win { player };
        } else if self.moves_made as usize == POINTS {
            self.status = GameStatus::Draw;
        } else {
            self.turn = player.opposite();
        }

        Ok(MoveOutcome { player, point: idx, is_win, win_line, status: self.status })
    }

    /// Place by `(col, row)` — convenience wrapper over [`place`].
    pub fn place_at(&mut self, c: i32, r: i32) -> Result<MoveOutcome, MoveError> {
        if !on_board(c, r) { return Err(MoveError::OffBoard); }
        self.place(pidx(c, r))
    }

    /// Place by algebraic coordinate (e.g. `"h8"`).
    pub fn place_coord(&mut self, coord: &str) -> Result<MoveOutcome, MoveError> {
        let idx = parse_coord(coord).ok_or(MoveError::BadCoord)?;
        self.place(idx)
    }

    /// If the stone just placed at `idx` for `player` completes a run of
    /// `WIN_LEN` or more along any of the four axes, return that ordered line.
    fn winning_line_through(&self, idx: u16, player: Player) -> Option<Vec<u16>> {
        let (c, r) = (col(idx), row(idx));
        // The four axes: horizontal, vertical, and the two diagonals.
        const DIRS: [(i32, i32); 4] = [(1, 0), (0, 1), (1, 1), (1, -1)];
        for (dc, dr) in DIRS {
            // Walk to the far negative end of this axis from the placed stone.
            let mut sc = c;
            let mut sr = r;
            while self.is_player(sc - dc, sr - dr, player) {
                sc -= dc;
                sr -= dr;
            }
            // Collect the contiguous run forward from that start.
            let mut line = Vec::new();
            let (mut cc, mut cr) = (sc, sr);
            while self.is_player(cc, cr, player) {
                line.push(pidx(cc, cr));
                cc += dc;
                cr += dr;
            }
            if line.len() >= WIN_LEN {
                return Some(line);
            }
        }
        None
    }

    fn is_player(&self, c: i32, r: i32, player: Player) -> bool {
        on_board(c, r) && self.board[pidx(c, r) as usize] == Cell::Stone(player)
    }

    /// A simple textual board dump (`.`/`X`/`O`), rows top-to-bottom. Handy in
    /// test failure messages and as a debug aid; not part of the wire protocol.
    pub fn ascii(&self) -> String {
        let mut s = String::with_capacity(POINTS + SIZE);
        for r in 0..SIZE {
            for c in 0..SIZE {
                s.push(match self.board[r * SIZE + c] {
                    Cell::Empty => '.',
                    Cell::Stone(Player::Black) => 'X',
                    Cell::Stone(Player::White) => 'O',
                });
            }
            s.push('\n');
        }
        s
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Drive a sequence of algebraic moves, asserting each is accepted.
    fn play(moves: &[&str]) -> Game {
        let mut g = Game::new();
        for (i, m) in moves.iter().enumerate() {
            g.place_coord(m).unwrap_or_else(|e| {
                panic!("move {} '{}' rejected: {:?}\n{}", i + 1, m, e, g.ascii())
            });
        }
        g
    }

    // ── basics ──
    #[test]
    fn fresh_board_is_empty_and_black_to_move() {
        let g = Game::new();
        assert_eq!(g.side_to_move(), Player::Black);
        assert_eq!(g.status(), GameStatus::Ongoing);
        assert_eq!(g.legal_moves().len(), POINTS);
        assert!(g.board().iter().all(|&c| c == Cell::Empty));
    }

    #[test]
    fn legal_moves_shrink_by_one_each_placement() {
        let mut g = Game::new();
        assert_eq!(g.legal_moves().len(), 225);
        g.place_coord("h8").unwrap();
        assert_eq!(g.legal_moves().len(), 224);
        g.place_coord("a1").unwrap();
        assert_eq!(g.legal_moves().len(), 223);
    }

    #[test]
    fn turn_alternates_black_white() {
        let mut g = Game::new();
        assert_eq!(g.place_coord("h8").unwrap().player, Player::Black);
        assert_eq!(g.side_to_move(), Player::White);
        assert_eq!(g.place_coord("h9").unwrap().player, Player::White);
        assert_eq!(g.side_to_move(), Player::Black);
    }

    #[test]
    fn coord_roundtrip() {
        for idx in 0..POINTS as u16 {
            assert_eq!(parse_coord(&coord_str(idx)), Some(idx));
        }
        assert_eq!(parse_coord("a1"), Some(0));
        assert_eq!(coord_str(0), "a1");
        assert_eq!(parse_coord("o15"), Some(224));
        assert_eq!(coord_str(224), "o15");
        assert_eq!(parse_coord("p1"), None); // column past 'o'
        assert_eq!(parse_coord("a16"), None); // row past 15
        assert_eq!(parse_coord("zz"), None);
    }

    // ── rejections: off-board, occupied, turn-ownership-via-engine, game-over ──
    #[test]
    fn occupied_point_is_rejected() {
        let mut g = Game::new();
        g.place_coord("h8").unwrap();
        // White tries the same point.
        assert_eq!(g.place_coord("h8"), Err(MoveError::Occupied));
        // The side to move did not change after the rejection.
        assert_eq!(g.side_to_move(), Player::White);
    }

    #[test]
    fn off_board_index_is_rejected() {
        let mut g = Game::new();
        assert_eq!(g.place(POINTS as u16), Err(MoveError::OffBoard));
        assert_eq!(g.place(9999), Err(MoveError::OffBoard));
        assert_eq!(g.place_at(15, 0), Err(MoveError::OffBoard));
        assert_eq!(g.place_at(-1, 0), Err(MoveError::OffBoard));
        assert_eq!(g.place_coord("a16"), Err(MoveError::BadCoord));
    }

    #[test]
    fn turn_ownership_is_enforced_by_the_engine() {
        // The engine owns whose turn it is — a caller can't place "for" the
        // other side; every placement is attributed to the side to move, and a
        // duplicate target is the only way an out-of-turn intent surfaces.
        let mut g = Game::new();
        let o1 = g.place_coord("h8").unwrap();
        assert_eq!(o1.player, Player::Black);
        let o2 = g.place_coord("h9").unwrap();
        assert_eq!(o2.player, Player::White); // attributed to White, not Black again
    }

    #[test]
    fn moves_after_game_over_are_rejected() {
        // Black builds a horizontal five; the game is then over.
        let g_ref = play(&[
            "a1", "a2", // B a1, W a2
            "b1", "b2", // B b1, W b2
            "c1", "c2", // B c1, W c2
            "d1", "d2", // B d1, W d2
            "e1", // B e1 -> a1..e1 is five
        ]);
        assert_eq!(g_ref.status(), GameStatus::Win { player: Player::Black });
        let mut g = g_ref;
        assert_eq!(g.place_coord("f1"), Err(MoveError::GameOver));
        assert!(g.legal_moves().is_empty());
    }

    // ── win directions ──
    #[test]
    fn horizontal_five_wins() {
        // Black: a8 b8 c8 d8 e8 ; White answers harmlessly on row 1.
        let g = play(&[
            "a8", "a1",
            "b8", "b1",
            "c8", "c1",
            "d8", "d1",
            "e8", // five horizontally on row 8
        ]);
        assert_eq!(g.status(), GameStatus::Win { player: Player::Black });
    }

    #[test]
    fn vertical_five_wins() {
        // Black stacks h1..h5; White plays elsewhere.
        let g = play(&[
            "h1", "a1",
            "h2", "a2",
            "h3", "a3",
            "h4", "a4",
            "h5", // five vertically on column h
        ]);
        assert_eq!(g.status(), GameStatus::Win { player: Player::Black });
    }

    #[test]
    fn diagonal_down_right_five_wins() {
        // Black on the ↘ diagonal: a1 b2 c3 d4 e5.
        let g = play(&[
            "a1", "a15",
            "b2", "b15",
            "c3", "c15",
            "d4", "d15",
            "e5", // five on the down-right diagonal
        ]);
        assert_eq!(g.status(), GameStatus::Win { player: Player::Black });
    }

    #[test]
    fn diagonal_up_right_five_wins() {
        // Black on the ↗ diagonal: a5 b4 c3 d2 e1.
        let g = play(&[
            "a5", "a15",
            "b4", "b15",
            "c3", "c15",
            "d2", "d15",
            "e1", // five on the up-right (anti-)diagonal
        ]);
        assert_eq!(g.status(), GameStatus::Win { player: Player::Black });
        // The reported win line should be the five anti-diagonal points.
    }

    #[test]
    fn white_can_win_too() {
        // White completes a horizontal five on row 2 while Black builds nothing.
        let g = play(&[
            "h8", "a2",
            "j8", "b2",
            "l8", "c2",
            "h10", "d2",
            "h12", "e2", // White: a2 b2 c2 d2 e2 -> five
        ]);
        assert_eq!(g.status(), GameStatus::Win { player: Player::White });
    }

    // ── the "four is not five" boundary ──
    #[test]
    fn four_in_a_row_is_not_a_win() {
        // Black gets a1 b1 c1 d1 (four), interleaved with White; still Ongoing.
        let g = play(&[
            "a1", "a3",
            "b1", "b3",
            "c1", "c3",
            "d1", // exactly four in a row, not five
        ]);
        assert_eq!(g.status(), GameStatus::Ongoing);
        // Black completing the fifth (e1) on its next turn wins: replay with one
        // more White reply (d3) so it is Black's turn, then Black plays e1.
        let mut g2 = play(&["a1", "a3", "b1", "b3", "c1", "c3", "d1", "d3"]);
        assert_eq!(g2.status(), GameStatus::Ongoing);
        assert_eq!(g2.place_coord("e1").unwrap().status, GameStatus::Win { player: Player::Black });
    }

    #[test]
    fn five_with_a_gap_is_not_a_win() {
        // a1 b1 c1 _ e1 (gap at d1) is NOT five in a row.
        let g = play(&[
            "a1", "a3",
            "b1", "b3",
            "c1", "c3",
            "e1", // skip d1
        ]);
        assert_eq!(g.status(), GameStatus::Ongoing);
    }

    // ── overline: freestyle counts six-or-more as a win ──
    #[test]
    fn overline_six_is_a_win_in_freestyle() {
        // Build five (win) then confirm a six-line is reported as a win too by
        // constructing one where the completing stone bridges into six.
        // B: b1 c1 d1 e1 f1 placed so the last stone yields a run of >=5.
        let g = play(&[
            "b1", "b3",
            "c1", "c3",
            "d1", "d3",
            "e1", "e3",
            "f1", // b1..f1 = five -> win
        ]);
        assert_eq!(g.status(), GameStatus::Win { player: Player::Black });
        // And a longer line is still a Win (constructed separately so the
        // game isn't already over): place the 5th stone in the MIDDLE of two
        // existing pairs to make six at once.
        let mut g2 = Game::new();
        // Black stones at a1,b1,c1, e1,f1 (gap at d1); White elsewhere.
        for (b, w) in [("a1", "a3"), ("b1", "b3"), ("c1", "c3"), ("e1", "e3"), ("f1", "f3")] {
            g2.place_coord(b).unwrap();
            g2.place_coord(w).unwrap();
        }
        assert_eq!(g2.status(), GameStatus::Ongoing);
        // Black fills d1 -> a1 b1 c1 d1 e1 f1 = SIX in a row.
        let o = g2.place_coord("d1").unwrap();
        assert_eq!(o.status, GameStatus::Win { player: Player::Black });
        assert_eq!(o.win_line.as_ref().unwrap().len(), 6, "overline reports full run");
    }

    // ── draw: a full board with no five ──
    #[test]
    fn full_board_with_no_five_is_a_draw() {
        // Construct a colouring of the whole 15×15 board that contains no five
        // in any direction, then replay it move-by-move through the engine.
        //
        // Pattern: 2-wide stripes sheared by a half-step each row, i.e. Black iff
        // `((2*row + col) / 2)` is even. Painting two cells of a colour then two
        // of the other along each diagonal-sheared band caps EVERY run — H, V,
        // and both diagonals — at length 4, so no five ever forms. The shear
        // also balances the colours to 113 Black / 112 White on the odd 15×15
        // board, exactly the split strict Black-first alternation produces (Black
        // lays the final, 225th, stone). Because the FINAL colouring has no five,
        // no intermediate placement order can complete one either.
        fn want_black(r: usize, c: usize) -> bool {
            ((2 * r + c) / 2) % 2 == 0
        }

        // Build the two move lists so Black and White stone *counts* let the
        // engine alternate. We place all of one colour's intended points and the
        // other's interleaved; to keep strict alternation we feed (black, white)
        // pairs drawn from the target colouring, falling back to any remaining
        // empty point for the side that has run out of its own target cells.
        let mut black_pts: Vec<u16> = Vec::new();
        let mut white_pts: Vec<u16> = Vec::new();
        for r in 0..SIZE {
            for c in 0..SIZE {
                let idx = (r * SIZE + c) as u16;
                if want_black(r, c) { black_pts.push(idx); } else { white_pts.push(idx); }
            }
        }
        // Verify the target colouring itself has no five-in-a-row before we even
        // play it (sanity on the pattern).
        let has_five = |is_black: &dyn Fn(usize, usize) -> bool| {
            const DIRS: [(i32, i32); 4] = [(1, 0), (0, 1), (1, 1), (1, -1)];
            for r in 0..SIZE as i32 {
                for c in 0..SIZE as i32 {
                    for (dc, dr) in DIRS {
                        let base = is_black(r as usize, c as usize);
                        let mut n = 1;
                        let (mut cc, mut cr) = (c + dc, r + dr);
                        while on_board(cc, cr) && is_black(cr as usize, cc as usize) == base {
                            n += 1; cc += dc; cr += dr;
                        }
                        if n >= 5 { return true; }
                    }
                }
            }
            false
        };
        assert!(!has_five(&want_black), "test pattern itself must avoid any five");

        // Replay: strict Black/White alternation. Black plays its own target
        // cells; White plays its own. Counts differ by at most one (Black has
        // the extra on an odd board), so Black just plays its last cell last.
        let mut g = Game::new();
        let mut bi = 0;
        let mut wi = 0;
        for step in 0..POINTS {
            let black_turn = step % 2 == 0;
            let idx = if black_turn { let v = black_pts[bi]; bi += 1; v }
                      else { let v = white_pts[wi]; wi += 1; v };
            let o = g.place(idx).unwrap_or_else(|e| panic!("step {step} idx {idx} ({}) -> {e:?}\n{}", coord_str(idx), g.ascii()));
            // No move except possibly the last may end the game.
            if step + 1 < POINTS {
                assert_eq!(o.status, GameStatus::Ongoing, "premature end at step {step}:\n{}", g.ascii());
            }
        }
        assert_eq!(g.status(), GameStatus::Draw, "full board, no five => draw:\n{}", g.ascii());
        assert!(g.legal_moves().is_empty());
        assert_eq!(g.place_coord("a1"), Err(MoveError::GameOver));
    }

    // ── a short scripted full game ──
    #[test]
    fn scripted_game_black_wins_on_a_diagonal() {
        // A compact, realistic-ish skirmish ending in a Black diagonal five.
        // Black walks the ↘ diagonal g7,h8,i9,j10,k11; White contests on a
        // parallel line but never reaches five.
        let g = play(&[
            "g7", "g8",   // 1. B g7   W g8
            "h8", "h9",   // 2. B h8   W h9
            "i9", "i10",  // 3. B i9   W i10
            "j10", "j11", // 4. B j10  W j11
            "k11",        // 5. B k11 -> g7 h8 i9 j10 k11 diagonal five
        ]);
        assert_eq!(g.status(), GameStatus::Win { player: Player::Black });
        assert_eq!(g.side_to_move(), Player::Black, "winner is the side that just moved");
    }

    // ── outcome / win-line reporting ──
    #[test]
    fn outcome_reports_exact_winning_line() {
        let mut g = play(&["a8", "a1", "b8", "b1", "c8", "c1", "d8", "d1"]);
        let o = g.place_coord("e8").unwrap();
        assert!(o.is_win);
        let line = o.win_line.expect("winning line present");
        assert_eq!(line.len(), 5);
        let coords: Vec<String> = line.iter().map(|&p| coord_str(p)).collect();
        assert_eq!(coords, vec!["a8", "b8", "c8", "d8", "e8"]);
        assert_eq!(o.player, Player::Black);
    }

    // ── wire / world-channel payload (mirrors chess) ──
    #[test]
    fn status_wire_encoding() {
        assert_eq!(GameStatus::Ongoing.wire(), "ongoing");
        assert_eq!(GameStatus::Win { player: Player::Black }.wire(), "win:black");
        assert_eq!(GameStatus::Win { player: Player::White }.wire(), "win:white");
        assert_eq!(GameStatus::Draw.wire(), "draw");
    }

    #[test]
    fn world_payload_json_ongoing() {
        let mut g = Game::new();
        let j = g.place_coord("h8").unwrap().to_json();
        assert!(j.contains("\"type\":\"gomoku.state\""), "{j}");
        assert!(j.contains("\"player\":\"black\""), "{j}");
        assert!(j.contains("\"lastMove\":\"h8\""), "{j}");
        assert!(j.contains("\"status\":\"ongoing\""), "{j}");
        assert!(j.contains("\"win\":false"), "{j}");
        assert!(j.contains("\"winLine\":[]"), "{j}");
    }

    #[test]
    fn world_payload_json_on_win_carries_line() {
        let mut g = play(&["a8", "a1", "b8", "b1", "c8", "c1", "d8", "d1"]);
        let j = g.place_coord("e8").unwrap().to_json();
        assert!(j.contains("\"status\":\"win:black\""), "{j}");
        assert!(j.contains("\"win\":true"), "{j}");
        assert!(j.contains("\"lastMove\":\"e8\""), "{j}");
        assert!(j.contains("\"winLine\":[\"a8\",\"b8\",\"c8\",\"d8\",\"e8\"]"), "{j}");
    }
}
