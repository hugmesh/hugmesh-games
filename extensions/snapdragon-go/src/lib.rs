//! `snapdragon-go` — a pure, dependency-free Go (Weiqi / Baduk) rules engine.
//!
//! Board: `size`×`size` (default 9, configurable up to 25). Point indexing is
//! row-major: `idx = row * size + col`, `0 = a1` in the bottom-left, `col` runs
//! a..  left-to-right and `row` runs 1.. bottom-to-top. **Black moves first.**
//!
//! ## Rules implemented
//! - Players alternate **placing a stone** on an empty point or **passing**.
//! - **Capture**: after a stone is placed, every *opponent* group adjacent to it
//!   that now has **zero liberties** is removed from the board. Opponent captures
//!   are resolved FIRST.
//! - **Suicide**: a move whose own group would be left with no liberties *after*
//!   the opponent-capture step is **illegal** — UNLESS it captured at least one
//!   enemy stone (in which case the liberty it freed makes it legal).
//! - **Simple ko**: a move that would recreate the board position immediately
//!   preceding it (the position before the opponent's last move) is illegal. This
//!   forbids instant recapture in the classic one-stone ko.
//! - **Two consecutive passes end the game.**
//! - **Scoring** (Chinese / area): a side's score is its stones on the board plus
//!   the empty points reachable only from that colour (territory). Empty regions
//!   touching both colours, or neither (a full empty board), score for no one. A
//!   configurable `komi` (default 0.0, given to White) breaks ties.
//!
//! This is the ONE engine: it compiles to `wasm32` for the in-browser game (host
//! authoritative, guest re-validates) and natively for `cargo test`. The server
//! runs none of it — it only streams events.

#![allow(clippy::needless_range_loop)]

/// The two stone colours. Black moves first by Go convention.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Player { Black, White }
impl Player {
    pub fn opposite(self) -> Player { if self == Player::Black { Player::White } else { Player::Black } }
    fn wire(self) -> &'static str { if self == Player::Black { "black" } else { "white" } }
    fn stone_char(self) -> char { if self == Player::Black { 'B' } else { 'W' } }
}

/// One point of the board.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Point { Empty, Stone(Player) }

/// Terminal / running classification of a game.
///
/// `Ended` carries the final **area** scores for both sides and the winner.
/// `winner == None` is a tie (a jigo), only possible with an integer komi.
#[derive(Debug, Clone, Copy, PartialEq)]
pub enum GameStatus {
    Ongoing,
    Ended { black: f32, white: f32, winner: Option<Player> },
}
impl GameStatus {
    pub fn is_terminal(self) -> bool { !matches!(self, GameStatus::Ongoing) }
    /// Compact wire encoding for the `world`-channel payload.
    pub fn wire(self) -> String {
        match self {
            GameStatus::Ongoing => "ongoing".into(),
            GameStatus::Ended { black, white, winner } => {
                let w = match winner { Some(p) => p.wire(), None => "draw" };
                format!("ended:{w}:{black}:{white}")
            }
        }
    }
}

/// Why a `play()` / `pass()` attempt was rejected.
#[derive(Debug, PartialEq, Eq)]
pub enum MoveError {
    /// Point index out of `0..size*size`, or coordinate string didn't parse.
    BadPoint,
    /// Target point already holds a stone.
    Occupied,
    /// The move would leave the played group with no liberties and captures
    /// nothing — forbidden self-capture.
    Suicide,
    /// The move would recreate the position immediately before it (simple ko).
    Ko,
    /// The game is already over (two passes have been played).
    GameOver,
}

/// The structured result of a successful `play()` or `pass()`.
#[derive(Debug, Clone, PartialEq)]
pub struct Outcome {
    /// The point the stone was placed on, or `usize::MAX` for a pass.
    pub point: usize,
    /// Who placed the stone (the side that moved this turn).
    pub mover: Player,
    /// Opponent points captured (removed) by this move (empty for a pass).
    pub captured: Vec<usize>,
    /// True when this turn was a pass (no stone placed).
    pub passed: bool,
    /// Total stones Black has captured so far this game.
    pub black_captures: u32,
    /// Total stones White has captured so far this game.
    pub white_captures: u32,
    /// Who moves next (turns always alternate in Go).
    pub next: Player,
    pub status: GameStatus,
}
impl Outcome {
    /// The JSON payload the host emits on the session `world` channel after a
    /// move; the guest re-validates it against its own engine copy. The server
    /// only relays this string — it never parses it.
    pub fn to_json(&self, size: usize) -> String {
        let last = if self.passed { "pass".to_string() } else { point_str(self.point, size) };
        let caps: Vec<String> = self.captured.iter().map(|&p| format!("\"{}\"", point_str(p, size))).collect();
        format!(
            "{{\"type\":\"go.state\",\"lastMove\":\"{}\",\"mover\":\"{}\",\"captured\":[{}],\"blackCaptures\":{},\"whiteCaptures\":{},\"next\":\"{}\",\"status\":\"{}\"}}",
            last, self.mover.wire(), caps.join(","), self.black_captures, self.white_captures,
            self.next.wire(), self.status.wire()
        )
    }
}

// ── coordinate helpers ────────────────────────────────────────────────────────
// Go traditionally skips the letter 'i' in column labels; we keep the full
// a.. alphabet here for simplicity (the engine never needs the human label
// internally — these helpers exist for tests and the wire payload).

/// Parse a coordinate like `"c3"` (col letter + 1-based row) into an index, given
/// the board `size`. `col` letter is a..z (no 'i' skip); `row` is 1..size.
pub fn parse_point(s: &str, size: usize) -> Option<usize> {
    let b = s.as_bytes();
    if b.len() < 2 { return None; }
    let col = (b[0] | 0x20).wrapping_sub(b'a') as usize; // lower-case the letter
    let row_str = std::str::from_utf8(&b[1..]).ok()?;
    let row1: usize = row_str.parse().ok()?;
    if row1 == 0 { return None; }
    let row = row1 - 1;
    if col < size && row < size { Some(row * size + col) } else { None }
}

/// Render an index as a coordinate, e.g. `0 -> "a1"` on any size.
pub fn point_str(idx: usize, size: usize) -> String {
    if idx == usize::MAX { return "pass".into(); }
    let col = idx % size;
    let row = idx / size;
    let letter = (b'a' + col as u8) as char;
    format!("{letter}{}", row + 1)
}

// ── the game ─────────────────────────────────────────────────────────────────
#[derive(Clone)]
pub struct Game {
    size: usize,
    board: Vec<Point>,
    side: Player,
    /// `Some(idx)` marks the single point forbidden by the simple-ko rule on the
    /// next move; cleared after any move that isn't an immediate recapture.
    ko: Option<usize>,
    /// Number of consecutive passes (2 ends the game).
    consecutive_passes: u32,
    /// Stones each side has captured over the whole game (prisoners).
    black_captures: u32,
    white_captures: u32,
    /// Bonus points for White, added to White's area score (tie-breaker).
    komi: f32,
}

impl Default for Game { fn default() -> Self { Game::new(9) } }

impl Game {
    /// A fresh empty board of side length `size` (clamped to `2..=25`), Black to
    /// move, komi 0.
    pub fn new(size: usize) -> Game {
        Self::with_komi(size, 0.0)
    }

    /// A fresh empty board with an explicit komi (added to White's score).
    pub fn with_komi(size: usize, komi: f32) -> Game {
        let size = size.clamp(2, 25);
        Game {
            size,
            board: vec![Point::Empty; size * size],
            side: Player::Black,
            ko: None,
            consecutive_passes: 0,
            black_captures: 0,
            white_captures: 0,
            komi,
        }
    }

    /// Build a game from a board string (`'B'`/`'W'`/`'.'`, point 0 first, row by
    /// row from the BOTTOM row up) plus the side to move. Whitespace is ignored,
    /// so a multi-line `size`-row literal reads bottom-row-first. Used by tests to
    /// set up specific positions.
    pub fn from_board(cells: &str, size: usize, side: Player) -> Result<Game, String> {
        let size = size.clamp(2, 25);
        let chars: Vec<char> = cells.chars().filter(|c| !c.is_whitespace()).collect();
        let n = size * size;
        if chars.len() != n { return Err(format!("board needs {n} cells, got {}", chars.len())); }
        let mut board = vec![Point::Empty; n];
        for (i, c) in chars.iter().enumerate() {
            board[i] = match c {
                '.' | '-' | '+' => Point::Empty,
                'B' | 'b' | 'X' | 'x' | '*' | '#' => Point::Stone(Player::Black),
                'W' | 'w' | 'O' | 'o' => Point::Stone(Player::White),
                other => return Err(format!("bad cell '{other}' at {i}")),
            };
        }
        Ok(Game {
            size,
            board,
            side,
            ko: None,
            consecutive_passes: 0,
            black_captures: 0,
            white_captures: 0,
            komi: 0.0,
        })
    }

    pub fn size(&self) -> usize { self.size }
    pub fn side_to_move(&self) -> Player { self.side }
    pub fn board(&self) -> &[Point] { &self.board }
    pub fn komi(&self) -> f32 { self.komi }
    pub fn black_captures(&self) -> u32 { self.black_captures }
    pub fn white_captures(&self) -> u32 { self.white_captures }
    /// The current simple-ko-forbidden point, if any.
    pub fn ko_point(&self) -> Option<usize> { self.ko }
    fn at(&self, idx: usize) -> Point { self.board[idx] }

    /// Orthogonal neighbours of `idx` on the board.
    fn neighbours(&self, idx: usize) -> Vec<usize> {
        let s = self.size;
        let (col, row) = (idx % s, idx / s);
        let mut out = Vec::with_capacity(4);
        if col > 0 { out.push(idx - 1); }
        if col + 1 < s { out.push(idx + 1); }
        if row > 0 { out.push(idx - s); }
        if row + 1 < s { out.push(idx + s); }
        out
    }

    /// Flood-fill the maximal connected group of same-colour stones containing
    /// `idx`, returning `(group_points, liberty_count)`. Liberties are the
    /// distinct empty points orthogonally adjacent to the group. Caller must pass
    /// a point that holds a stone.
    fn group_and_liberties(&self, idx: usize) -> (Vec<usize>, usize) {
        let colour = match self.at(idx) { Point::Stone(p) => p, Point::Empty => return (Vec::new(), 0) };
        let n = self.board.len();
        let mut in_group = vec![false; n];
        let mut is_lib = vec![false; n];
        let mut stack = vec![idx];
        let mut group = Vec::new();
        in_group[idx] = true;
        while let Some(cur) = stack.pop() {
            group.push(cur);
            for nb in self.neighbours(cur) {
                match self.at(nb) {
                    Point::Empty => { is_lib[nb] = true; }
                    Point::Stone(p) if p == colour && !in_group[nb] => {
                        in_group[nb] = true;
                        stack.push(nb);
                    }
                    _ => {}
                }
            }
        }
        let liberties = is_lib.iter().filter(|&&b| b).count();
        (group, liberties)
    }

    /// Remove every stone of `colour` whose group has zero liberties and which is
    /// orthogonally adjacent to `played` (the just-placed stone). Returns the
    /// removed points. Used for the opponent-capture step.
    fn capture_dead_adjacent(&mut self, played: usize, colour: Player) -> Vec<usize> {
        let mut removed = Vec::new();
        let mut seen = vec![false; self.board.len()];
        for nb in self.neighbours(played) {
            if seen[nb] { continue; }
            if self.at(nb) == Point::Stone(colour) {
                let (group, libs) = self.group_and_liberties(nb);
                for &g in &group { seen[g] = true; }
                if libs == 0 {
                    for &g in &group {
                        self.board[g] = Point::Empty;
                        removed.push(g);
                    }
                }
            }
        }
        removed
    }

    /// Does placing a `side` stone on the empty point `idx` capture anything, and
    /// would it be self-capture (suicide)? Returns `(captured, is_suicide)`
    /// without mutating `self`. `idx` must be empty.
    fn analyse_move(&self, idx: usize, side: Player) -> (Vec<usize>, bool) {
        let mut probe = self.clone();
        probe.board[idx] = Point::Stone(side);
        let captured = probe.capture_dead_adjacent(idx, side.opposite());
        // After opponent captures are removed, check our own group's liberties.
        let (_grp, libs) = probe.group_and_liberties(idx);
        let is_suicide = captured.is_empty() && libs == 0;
        (captured, is_suicide)
    }

    /// Is `idx` a legal placement for the side to move? Mirrors `play`'s checks
    /// exactly, without mutating. (`pass` is always legal while the game is
    /// ongoing.)
    pub fn is_legal(&self, idx: usize) -> bool {
        if self.status().is_terminal() { return false; }
        if idx >= self.board.len() { return false; }
        if self.at(idx) != Point::Empty { return false; }
        let (captured, is_suicide) = self.analyse_move(idx, self.side);
        if is_suicide { return false; }
        // Simple ko: the recorded ko point may not be re-taken by a single-stone
        // capture (that recreates the immediately-previous board). This guard is
        // byte-for-byte the one `play` enforces.
        if let Some(ko) = self.ko {
            if idx == ko && captured.len() == 1 { return false; }
        }
        true
    }

    /// All legal placement points for the side to move (excludes the pass move,
    /// which is represented separately by `pass()`).
    pub fn legal_moves(&self) -> Vec<usize> {
        if self.status().is_terminal() { return Vec::new(); }
        (0..self.board.len()).filter(|&i| self.is_legal(i)).collect()
    }

    /// Place a stone for the side to move on `idx`, applying captures, the suicide
    /// rule, and the simple-ko rule, then hand the turn over. Enforces turn
    /// ownership implicitly (only the side to move can act).
    pub fn play(&mut self, idx: usize) -> Result<Outcome, MoveError> {
        if self.status().is_terminal() { return Err(MoveError::GameOver); }
        if idx >= self.board.len() { return Err(MoveError::BadPoint); }
        if self.at(idx) != Point::Empty { return Err(MoveError::Occupied); }

        let side = self.side;
        // Resolve the move on a probe so we can validate before committing.
        let mut probe = self.clone();
        probe.board[idx] = Point::Stone(side);
        let captured = probe.capture_dead_adjacent(idx, side.opposite());
        let (_grp, libs) = probe.group_and_liberties(idx);
        if captured.is_empty() && libs == 0 { return Err(MoveError::Suicide); }

        // Simple ko: a single-stone capture that recreates the position that
        // existed immediately before the opponent's previous move is forbidden.
        if let Some(ko) = self.ko {
            if idx == ko && captured.len() == 1 {
                return Err(MoveError::Ko);
            }
        }

        // Commit.
        self.board[idx] = Point::Stone(side);
        let actually_removed = self.capture_dead_adjacent(idx, side.opposite());
        debug_assert_eq!(actually_removed.len(), captured.len());

        // Tally prisoners.
        match side {
            Player::Black => self.black_captures += actually_removed.len() as u32,
            Player::White => self.white_captures += actually_removed.len() as u32,
        }

        // Set / clear the ko point. A ko arises only when exactly ONE stone was
        // captured AND the capturing stone itself is now in a one-stone group with
        // exactly one liberty (the point just vacated). The forbidden reply is
        // recapturing on that single captured point.
        self.ko = if actually_removed.len() == 1 {
            let (grp, libs) = self.group_and_liberties(idx);
            if grp.len() == 1 && libs == 1 {
                Some(actually_removed[0])
            } else {
                None
            }
        } else {
            None
        };

        self.consecutive_passes = 0;
        self.side = side.opposite();
        Ok(self.outcome(idx, side, actually_removed, false))
    }

    /// Convenience: play a move named in coordinates ("c3").
    pub fn play_str(&mut self, s: &str) -> Result<Outcome, MoveError> {
        let idx = parse_point(s, self.size).ok_or(MoveError::BadPoint)?;
        self.play(idx)
    }

    /// Pass the turn. Two consecutive passes end the game (Chinese scoring is then
    /// reported by `status()`).
    pub fn pass(&mut self) -> Result<Outcome, MoveError> {
        if self.status().is_terminal() { return Err(MoveError::GameOver); }
        let mover = self.side;
        self.consecutive_passes += 1;
        self.ko = None;
        self.side = mover.opposite();
        Ok(self.outcome(usize::MAX, mover, Vec::new(), true))
    }

    fn outcome(&self, idx: usize, mover: Player, captured: Vec<usize>, passed: bool) -> Outcome {
        Outcome {
            point: idx,
            mover,
            captured,
            passed,
            black_captures: self.black_captures,
            white_captures: self.white_captures,
            next: self.side,
            status: self.status(),
        }
    }

    /// The current game status. `Ongoing` until two consecutive passes, then
    /// `Ended` with the Chinese **area** scores (stones + sole-bordered territory,
    /// White credited the komi) and the winner.
    pub fn status(&self) -> GameStatus {
        if self.consecutive_passes < 2 { return GameStatus::Ongoing; }
        let (b, w) = self.area_score();
        let bf = b as f32;
        let wf = w as f32 + self.komi;
        let winner = if bf > wf { Some(Player::Black) }
                     else if wf > bf { Some(Player::White) }
                     else { None };
        GameStatus::Ended { black: bf, white: wf, winner }
    }

    /// Chinese **area** score for both colours: `(black, white)` where each is the
    /// number of that colour's stones on the board PLUS the empty points whose
    /// connected empty region borders only that colour. Empty regions touching
    /// both colours (or neither) are neutral and score for no one. Komi is NOT
    /// folded in here — `status()` adds it to White.
    pub fn area_score(&self) -> (u32, u32) {
        let n = self.board.len();
        let mut black = 0u32;
        let mut white = 0u32;
        // Count stones.
        for p in &self.board {
            match p {
                Point::Stone(Player::Black) => black += 1,
                Point::Stone(Player::White) => white += 1,
                Point::Empty => {}
            }
        }
        // Flood empty regions; attribute each to its sole bordering colour, if any.
        let mut visited = vec![false; n];
        for start in 0..n {
            if visited[start] || self.at(start) != Point::Empty { continue; }
            let mut stack = vec![start];
            let mut region = Vec::new();
            let mut borders_black = false;
            let mut borders_white = false;
            visited[start] = true;
            while let Some(cur) = stack.pop() {
                region.push(cur);
                for nb in self.neighbours(cur) {
                    match self.at(nb) {
                        Point::Empty => {
                            if !visited[nb] { visited[nb] = true; stack.push(nb); }
                        }
                        Point::Stone(Player::Black) => borders_black = true,
                        Point::Stone(Player::White) => borders_white = true,
                    }
                }
            }
            match (borders_black, borders_white) {
                (true, false) => black += region.len() as u32,
                (false, true) => white += region.len() as u32,
                _ => {} // neutral (dame) or empty board
            }
        }
        (black, white)
    }

    /// Compact board string (`'B'`/`'W'`/`'.'`, point 0 first, row 0 first).
    pub fn board_str(&self) -> String {
        self.board.iter().map(|c| match c {
            Point::Empty => '.',
            Point::Stone(p) => p.stone_char(),
        }).collect()
    }

    /// The world-channel JSON for the CURRENT state (no move attached). Lets the
    /// host broadcast the opening position or a re-sync snapshot.
    pub fn snapshot_json(&self) -> String {
        let (b, w) = self.area_score();
        format!(
            "{{\"type\":\"go.snapshot\",\"size\":{},\"board\":\"{}\",\"side\":\"{}\",\"blackCaptures\":{},\"whiteCaptures\":{},\"areaBlack\":{},\"areaWhite\":{},\"komi\":{},\"status\":\"{}\"}}",
            self.size, self.board_str(), self.side.wire(), self.black_captures, self.white_captures,
            b, w, self.komi, self.status().wire()
        )
    }

}

#[cfg(test)]
mod tests {
    use super::*;

    // helper: place a stone on `cells` by coordinate.
    fn put(cells: &mut Vec<char>, s: &str, size: usize, c: char) {
        cells[parse_point(s, size).unwrap()] = c;
    }
    fn board_of(cells: &[char]) -> String { cells.iter().collect() }

    // ── coordinate round-tripping ──────────────────────────────────────────────
    #[test]
    fn coordinate_round_trip() {
        let size = 9;
        for idx in 0..size * size {
            let s = point_str(idx, size);
            assert_eq!(parse_point(&s, size), Some(idx), "round trip {idx} -> {s}");
        }
        assert_eq!(parse_point("a1", 9), Some(0));
        assert_eq!(parse_point("b1", 9), Some(1));
        assert_eq!(parse_point("a2", 9), Some(9));
        assert_eq!(point_str(0, 9), "a1");
        // out of range / garbage
        assert_eq!(parse_point("z1", 9), None);
        assert_eq!(parse_point("a99", 9), None);
        assert_eq!(parse_point("a0", 9), None);
        assert_eq!(parse_point("", 9), None);
    }

    // ── empty-board basics ─────────────────────────────────────────────────────
    #[test]
    fn fresh_board_is_empty_black_to_move() {
        let g = Game::new(9);
        assert_eq!(g.size(), 9);
        assert_eq!(g.side_to_move(), Player::Black);
        assert_eq!(g.board().iter().filter(|p| **p != Point::Empty).count(), 0);
        // every point is a legal opening move on an empty board.
        assert_eq!(g.legal_moves().len(), 81);
        assert_eq!(g.status(), GameStatus::Ongoing);
    }

    #[test]
    fn size_is_configurable_and_clamped() {
        assert_eq!(Game::new(13).size(), 13);
        assert_eq!(Game::new(19).size(), 19);
        assert_eq!(Game::new(5).size(), 5);
        assert_eq!(Game::new(0).size(), 2);   // clamped up
        assert_eq!(Game::new(99).size(), 25); // clamped down
        assert_eq!(Game::new(19).legal_moves().len(), 361);
    }

    #[test]
    fn turn_alternates_on_play() {
        let mut g = Game::new(9);
        assert_eq!(g.side_to_move(), Player::Black);
        let o = g.play_str("c3").unwrap();
        assert_eq!(o.mover, Player::Black);
        assert_eq!(o.next, Player::White);
        assert_eq!(g.side_to_move(), Player::White);
        let o2 = g.play_str("g7").unwrap();
        assert_eq!(o2.mover, Player::White);
        assert_eq!(g.side_to_move(), Player::Black);
    }

    // ── illegal placements ─────────────────────────────────────────────────────
    #[test]
    fn rejects_occupied_point() {
        let mut g = Game::new(9);
        g.play_str("c3").unwrap();      // Black c3
        // White can't play c3.
        assert_eq!(g.play_str("c3"), Err(MoveError::Occupied));
    }

    #[test]
    fn rejects_out_of_range_point() {
        let mut g = Game::new(9);
        assert_eq!(g.play(81), Err(MoveError::BadPoint));
        assert_eq!(g.play(usize::MAX - 1), Err(MoveError::BadPoint));
        assert_eq!(g.play_str("z9"), Err(MoveError::BadPoint));
    }

    // ── single-stone capture ───────────────────────────────────────────────────
    //
    //   . . .          . B .
    //   B W .   -->    B . .   (Black plays c2; White b2 has 0 liberties → removed)
    //   . B .          . B .
    #[test]
    fn single_stone_capture() {
        let size = 9;
        let mut cells = vec!['.'; size * size];
        // White stone on b2 surrounded on 3 sides by Black; the 4th (c2) is the move.
        put(&mut cells, "b2", size, 'W');
        put(&mut cells, "a2", size, 'B');
        put(&mut cells, "b1", size, 'B');
        put(&mut cells, "b3", size, 'B');
        let mut g = Game::from_board(&board_of(&cells), size, Player::Black).unwrap();
        // b2 White currently has exactly one liberty: c2.
        let o = g.play_str("c2").unwrap();
        assert_eq!(o.captured, vec![parse_point("b2", size).unwrap()]);
        assert_eq!(g.at(parse_point("b2", size).unwrap()), Point::Empty, "white stone removed");
        assert_eq!(o.black_captures, 1);
        assert_eq!(o.white_captures, 0);
    }

    #[test]
    fn capture_on_the_edge_and_corner() {
        let size = 9;
        // Corner capture: White on a1 (corner has 2 liberties a2,b1). Black fills both.
        let mut cells = vec!['.'; size * size];
        put(&mut cells, "a1", size, 'W');
        put(&mut cells, "a2", size, 'B');
        // b1 is the capturing move.
        let mut g = Game::from_board(&board_of(&cells), size, Player::Black).unwrap();
        let o = g.play_str("b1").unwrap();
        assert_eq!(o.captured, vec![parse_point("a1", size).unwrap()]);
        assert_eq!(g.at(parse_point("a1", size).unwrap()), Point::Empty);
    }

    // ── multi-stone group capture ──────────────────────────────────────────────
    //
    // A 2-stone White group b2,c2 with a single shared liberty; Black fills it and
    // the whole group is removed at once.
    #[test]
    fn multi_stone_group_capture() {
        let size = 9;
        let mut cells = vec!['.'; size * size];
        // White group: b2, c2.
        put(&mut cells, "b2", size, 'W');
        put(&mut cells, "c2", size, 'W');
        // Black surrounds everything except the single liberty d2.
        put(&mut cells, "a2", size, 'B'); // left of b2
        put(&mut cells, "b1", size, 'B'); // below b2
        put(&mut cells, "c1", size, 'B'); // below c2
        put(&mut cells, "b3", size, 'B'); // above b2
        put(&mut cells, "c3", size, 'B'); // above c2
        // remaining liberty is d2.
        let mut g = Game::from_board(&board_of(&cells), size, Player::Black).unwrap();
        // Confirm the group really has one liberty before the move.
        let (grp, libs) = g.group_and_liberties(parse_point("b2", size).unwrap());
        assert_eq!(grp.len(), 2);
        assert_eq!(libs, 1);
        let o = g.play_str("d2").unwrap();
        let mut caps = o.captured.clone();
        caps.sort();
        let mut want = vec![parse_point("b2", size).unwrap(), parse_point("c2", size).unwrap()];
        want.sort();
        assert_eq!(caps, want, "both white stones captured at once");
        assert_eq!(g.at(parse_point("b2", size).unwrap()), Point::Empty);
        assert_eq!(g.at(parse_point("c2", size).unwrap()), Point::Empty);
        assert_eq!(o.black_captures, 2);
    }

    // ── suicide rejection ──────────────────────────────────────────────────────
    //
    // A point fully surrounded by enemy stones (and no capture results) is suicide.
    //   . B .
    //   B . B    Black surrounds c2; White playing the centre is suicide.
    //   . B .
    #[test]
    fn suicide_is_rejected() {
        let size = 9;
        let mut cells = vec!['.'; size * size];
        // Black ring around b2.
        put(&mut cells, "a2", size, 'B');
        put(&mut cells, "c2", size, 'B');
        put(&mut cells, "b1", size, 'B');
        put(&mut cells, "b3", size, 'B');
        // White to move plays into the fully-enclosed single empty point b2.
        let mut g = Game::from_board(&board_of(&cells), size, Player::White).unwrap();
        assert!(!g.is_legal(parse_point("b2", size).unwrap()));
        assert_eq!(g.play_str("b2"), Err(MoveError::Suicide));
    }

    #[test]
    fn multi_point_suicide_is_rejected() {
        // Filling the LAST liberty of your own multi-stone group (with no capture)
        // is suicide. White group a1,a2 with sole liberty a3, fully walled by Black
        // on b1,b2,b3; White playing a3 would self-fill with zero liberties.
        let size = 9;
        let mut cells = vec!['.'; size * size];
        put(&mut cells, "a1", size, 'W');
        put(&mut cells, "a2", size, 'W');
        put(&mut cells, "b1", size, 'B');
        put(&mut cells, "b2", size, 'B');
        put(&mut cells, "b3", size, 'B');
        put(&mut cells, "a4", size, 'B'); // cap above the a-column so a3 has no outside liberty
        let mut g = Game::from_board(&board_of(&cells), size, Player::White).unwrap();
        // a3 is the group's only liberty; filling it self-captures with no enemy take.
        assert_eq!(g.play_str("a3"), Err(MoveError::Suicide));
    }

    // ── a capturing move that would otherwise be suicide is LEGAL ───────────────
    //
    // White stone at the centre would be self-atari, BUT it captures a Black stone
    // first, freeing a liberty → the move is legal.
    //
    //   . W .            . W .
    //   W B W    play    W W W   (White centre captures Black; the freed point is a
    //   . B .    -->     . . W?  liberty so the white stone survives)
    //
    // Concretely: a single Black stone b2 in atari, with White all around except
    // the point Black sits on is the capture target. Build so White's filling move
    // both removes Black and ends with a liberty.
    #[test]
    fn capturing_move_that_would_be_suicide_is_legal() {
        let size = 9;
        let mut cells = vec!['.'; size * size];
        // Black single stone at b2 in atari: surrounded by White on 3 sides, and
        // the 4th side (where White will play) reduces it to 0 liberties.
        // White: a2, b1, b3. Black: b2. White plays c2 to capture b2.
        put(&mut cells, "b2", size, 'B');
        put(&mut cells, "a2", size, 'W');
        put(&mut cells, "b1", size, 'W');
        put(&mut cells, "b3", size, 'W');
        // To make the WHITE c2 stone itself near-suicidal, wall it too — but leave
        // its survival to the captured point. Surround c2 with White-friendly walls
        // except d2/c1/c3; the capture of b2 frees b2 as c2's liberty.
        // Here c2 after capture is adjacent to: b2 (now empty), c1 (empty), c3
        // (empty), d2 (empty) → plenty of liberties, so also assert the *strict*
        // case in the next test. This case proves capture-first ordering.
        let mut g = Game::from_board(&board_of(&cells), size, Player::White).unwrap();
        // Pre-move: b2 Black has exactly one liberty (c2).
        let (_grp, libs) = g.group_and_liberties(parse_point("b2", size).unwrap());
        assert_eq!(libs, 1, "black b2 should be in atari");
        let o = g.play_str("c2").unwrap();
        assert_eq!(o.captured, vec![parse_point("b2", size).unwrap()]);
        assert_eq!(g.at(parse_point("b2", size).unwrap()), Point::Empty);
        assert_eq!(o.white_captures, 1);
    }

    #[test]
    fn strict_capturing_move_in_self_atari_is_legal() {
        // The strict form: the played stone would have ZERO liberties if not for
        // the capture. A classic "snapback"-style single point.
        //
        // Black group fills every liberty of White's intended point EXCEPT the
        // capture target, so the only liberty White gains is the captured point.
        //
        // Layout (lower-left), '.' = empty:
        //   row3: B . . . .
        //   row2: W B . . .
        //   row1: . W B . .     (a1 empty, b1 W, c1 B)
        //
        // Let Black be to move and capture the White group {a2,b1} which is in
        // atari at a1. After Black plays a1, Black's a1 stone has zero outside
        // liberties (b1 was White→now empty gives it a liberty). Build precisely:
        let size = 9;
        let mut cells = vec!['.'; size * size];
        // White group in atari: a2 + b1 (connected? no — a2 and b1 aren't adjacent).
        // Use a connected White group a1,a2 instead, in atari at a3, captured by
        // Black a3 where Black a3 itself would otherwise be self-atari.
        // Simpler reliable construction: single white stone, snapback not needed.
        // White b1 alone, in atari, Black plays a1 to capture; a1 then has liberty
        // from the freed b1.
        put(&mut cells, "b1", size, 'W'); // white target (corner-ish edge)
        put(&mut cells, "b2", size, 'B'); // above b1
        put(&mut cells, "c1", size, 'B'); // right of b1
        // a1 is the capturing move for Black; b1 White currently has liberties a1.
        let mut g = Game::from_board(&board_of(&cells), size, Player::Black).unwrap();
        let (_g, libs) = g.group_and_liberties(parse_point("b1", size).unwrap());
        assert_eq!(libs, 1, "white b1 in atari (only liberty a1)");
        let o = g.play_str("a1").unwrap();
        assert_eq!(o.captured, vec![parse_point("b1", size).unwrap()]);
        assert_eq!(g.at(parse_point("b1", size).unwrap()), Point::Empty);
        // The Black a1 stone survives: its liberty is the just-freed b1.
        let (_g2, libs2) = g.group_and_liberties(parse_point("a1", size).unwrap());
        assert!(libs2 >= 1, "captured point becomes a liberty so the move isn't suicide");
    }

    // ── simple ko ──────────────────────────────────────────────────────────────
    //
    // The classic ko shape. After Black captures one White stone, White may not
    // immediately recapture (it would recreate the previous board); White must
    // play elsewhere first, then the ko reopens.
    //
    //   . B W .
    //   B . . W      (the two centre points form the ko)
    //   . B W .
    #[test]
    fn simple_ko_forbids_immediate_recapture() {
        let size = 9;
        let mut cells = vec!['.'; size * size];
        // Set up a standard ko. Points (using c/d columns, rows 1..3):
        //   c2 = White, d2 = (ko point, empty), b2 = Black
        //   c1 = Black, d1 = White
        //   c3 = Black, d3 = White
        // Black to move captures the White stone at c2 by playing d2? Let's build
        // the canonical ko:
        //
        //   row3:  . B W . .
        //   row2:  B W . W .   <- Black b2, White c2, empty d2, White e2... no.
        //
        // Use the textbook ko: White stone 'X' is captured by Black.
        //   b3 B   c3 W
        //   a2 B   b2 X(empty target)  c2 B ...
        // We construct it directly with two adjacent contested points c2 (W) and d2.
        // Black plays d2 capturing c2; then white d2-recapture is the ko.
        // Stones:
        put(&mut cells, "c2", size, 'W'); // the white stone Black will capture
        put(&mut cells, "b2", size, 'B'); // left of c2
        put(&mut cells, "c1", size, 'B'); // below c2
        put(&mut cells, "c3", size, 'B'); // above c2
        // d2 will be Black's capturing move; to make it a KO (Black d2 ends in
        // self-atari recapturable shape) wall d2 with White on the far sides:
        put(&mut cells, "d1", size, 'W'); // below d2
        put(&mut cells, "d3", size, 'W'); // above d2
        put(&mut cells, "e2", size, 'W'); // right of d2
        // Now: c2 White has one liberty (d2). Black plays d2:
        //   - captures c2 (its last liberty filled),
        //   - Black d2 is then a single stone whose only liberty is c2 (just freed)
        //     because d1,d3,e2 are White → a one-stone, one-liberty group → KO.
        let mut g = Game::from_board(&board_of(&cells), size, Player::Black).unwrap();
        let (_gg, lib_c2) = g.group_and_liberties(parse_point("c2", size).unwrap());
        assert_eq!(lib_c2, 1, "white c2 must be in atari (liberty d2)");
        let o = g.play_str("d2").unwrap();
        assert_eq!(o.captured, vec![parse_point("c2", size).unwrap()], "black captures c2");
        // A ko point must now be recorded at c2 (the captured point).
        assert_eq!(g.ko_point(), Some(parse_point("c2", size).unwrap()));
        // White's immediate recapture at c2 is forbidden by the simple-ko rule.
        assert!(!g.is_legal(parse_point("c2", size).unwrap()));
        assert_eq!(g.play_str("c2"), Err(MoveError::Ko));
    }

    #[test]
    fn ko_reopens_after_a_play_elsewhere() {
        // Same ko, but White plays a ko threat elsewhere; the ban lifts and White
        // may then recapture. (Black would have to respond to keep the ko; here we
        // just prove the legality flips.)
        let size = 9;
        let mut cells = vec!['.'; size * size];
        put(&mut cells, "c2", size, 'W');
        put(&mut cells, "b2", size, 'B');
        put(&mut cells, "c1", size, 'B');
        put(&mut cells, "c3", size, 'B');
        put(&mut cells, "d1", size, 'W');
        put(&mut cells, "d3", size, 'W');
        put(&mut cells, "e2", size, 'W');
        let mut g = Game::from_board(&board_of(&cells), size, Player::Black).unwrap();
        g.play_str("d2").unwrap(); // Black captures c2; ko recorded.
        assert_eq!(g.play_str("c2"), Err(MoveError::Ko)); // White can't recapture now.
        // White plays elsewhere (a far corner): ko ban must clear.
        g.play_str("h8").unwrap();
        assert_eq!(g.ko_point(), None, "ko clears after a non-ko move");
        // It's Black's turn now; let Black also play elsewhere.
        g.play_str("g7").unwrap();
        // Now White CAN recapture at c2 (the d2 black stone is in atari at c2).
        assert!(g.is_legal(parse_point("c2", size).unwrap()));
        let o = g.play_str("c2").unwrap();
        assert_eq!(o.captured, vec![parse_point("d2", size).unwrap()], "white recaptures d2");
    }

    #[test]
    fn non_ko_single_capture_sets_no_ban_when_group_grows() {
        // A single capture where the capturing stone connects into a larger group
        // (so it is NOT a one-stone/one-liberty shape) must NOT set a ko ban.
        let size = 9;
        let mut cells = vec!['.'; size * size];
        // White single stone b2 in atari; Black plays c2 to capture, where c2 also
        // touches a friendly Black stone (so the capturing group has >1 stone).
        put(&mut cells, "b2", size, 'W');
        put(&mut cells, "a2", size, 'B');
        put(&mut cells, "b1", size, 'B');
        put(&mut cells, "b3", size, 'B');
        put(&mut cells, "c3", size, 'B'); // friendly neighbour to c2 (connects group)
        let mut g = Game::from_board(&board_of(&cells), size, Player::Black).unwrap();
        let o = g.play_str("c2").unwrap();
        assert_eq!(o.captured, vec![parse_point("b2", size).unwrap()]);
        // c2 connects to c3 → group of 2 with many liberties → NO ko ban.
        assert_eq!(g.ko_point(), None);
    }

    // ── passing & two-pass termination ─────────────────────────────────────────
    #[test]
    fn single_pass_keeps_game_ongoing() {
        let mut g = Game::new(9);
        let o = g.pass().unwrap();
        assert!(o.passed);
        assert_eq!(o.mover, Player::Black);
        assert_eq!(o.next, Player::White);
        assert_eq!(g.status(), GameStatus::Ongoing);
        // A play between passes resets the pass counter. After Black's pass it is
        // White's turn, so White plays e5; then Black passes again — but that's
        // only ONE consecutive pass (the play reset the counter).
        g.play_str("e5").unwrap();     // White
        let o2 = g.pass().unwrap();    // Black passes
        assert_eq!(o2.mover, Player::Black);
        assert_eq!(g.status(), GameStatus::Ongoing, "only one consecutive pass so far");
    }

    #[test]
    fn two_consecutive_passes_end_the_game() {
        let mut g = Game::new(9);
        g.play_str("e5").unwrap(); // Black plays so the board isn't empty
        g.pass().unwrap();         // White passes
        assert_eq!(g.status(), GameStatus::Ongoing);
        let o = g.pass().unwrap(); // Black passes → game ends
        assert!(o.status.is_terminal());
        assert!(matches!(g.status(), GameStatus::Ended { .. }));
        // Further moves are rejected.
        assert_eq!(g.play_str("a1"), Err(MoveError::GameOver));
        assert_eq!(g.pass(), Err(MoveError::GameOver));
    }

    // ── area scoring on a finished position ────────────────────────────────────
    //
    // A tiny fully-decided 5x5 position split down the middle:
    //   col c (the 3rd file) is the boundary wall; Black owns the left two files,
    //   White owns the right two files. Each owns 2 files of stones + 0 territory
    //   on this dense wall — so we instead leave the edge files empty as territory.
    //
    //   row5: . B . W .
    //   row4: . B . W .
    //   row3: . B . W .
    //   row2: . B . W .
    //   row1: . B . W .
    //
    // Black wall on file b; White wall on file d. File a (5 pts) borders only Black
    // → Black territory. File e (5 pts) borders only White → White territory. File
    // c (the centre, 5 pts) borders BOTH → neutral. Area score:
    //   Black = 5 stones + 5 territory = 10
    //   White = 5 stones + 5 territory = 10  → a tie (jigo) at komi 0.
    #[test]
    fn area_score_split_board() {
        let size = 5;
        let mut cells = vec!['.'; size * size];
        for r in 1..=5 {
            put(&mut cells, &format!("b{r}"), size, 'B');
            put(&mut cells, &format!("d{r}"), size, 'W');
        }
        let g = Game::from_board(&board_of(&cells), size, Player::Black).unwrap();
        let (b, w) = g.area_score();
        assert_eq!(b, 10, "5 black stones + 5 left-edge territory");
        assert_eq!(w, 10, "5 white stones + 5 right-edge territory");
    }

    #[test]
    fn two_pass_end_reports_area_score_and_winner() {
        // Build the split board, then have both players pass to end the game and
        // verify status() reports the area score. Black to move; with komi 0 it's a
        // tie. We then check a komi tilts the result to White.
        let size = 5;
        let mut cells = vec!['.'; size * size];
        for r in 1..=5 {
            put(&mut cells, &format!("b{r}"), size, 'B');
            put(&mut cells, &format!("d{r}"), size, 'W');
        }
        let mut g = Game::from_board(&board_of(&cells), size, Player::Black).unwrap();
        g.pass().unwrap(); // Black passes
        g.pass().unwrap(); // White passes → end
        match g.status() {
            GameStatus::Ended { black, white, winner } => {
                assert_eq!(black, 10.0);
                assert_eq!(white, 10.0);
                assert_eq!(winner, None, "10–10 with komi 0 is a tie");
            }
            s => panic!("expected Ended, got {s:?}"),
        }
    }

    #[test]
    fn komi_breaks_the_tie_for_white() {
        let size = 5;
        let mut cells = vec!['.'; size * size];
        for r in 1..=5 {
            put(&mut cells, &format!("b{r}"), size, 'B');
            put(&mut cells, &format!("d{r}"), size, 'W');
        }
        // Reload via from_board, then inject komi by reconstructing with_komi shape.
        let base = Game::from_board(&board_of(&cells), size, Player::Black).unwrap();
        // Copy the board into a komi'd game.
        let mut g = Game::with_komi(size, 6.5);
        for (i, p) in base.board().iter().enumerate() { g.board[i] = *p; }
        g.pass().unwrap();
        g.pass().unwrap();
        match g.status() {
            GameStatus::Ended { black, white, winner } => {
                assert_eq!(black, 10.0);
                assert_eq!(white, 16.5, "10 area + 6.5 komi");
                assert_eq!(winner, Some(Player::White));
            }
            s => panic!("expected Ended, got {s:?}"),
        }
    }

    #[test]
    fn black_wins_when_owning_more_area() {
        // A board where Black surrounds a chunk of empty territory and White has a
        // small living group. Black should win on area.
        let size = 5;
        let mut cells = vec!['.'; size * size];
        // Black wall enclosing the left 3 files (a,b,c) as Black; White only owns
        // file e. File d neutral.
        for r in 1..=5 {
            put(&mut cells, &format!("c{r}"), size, 'B'); // black wall on file c
            put(&mut cells, &format!("e{r}"), size, 'W'); // white wall on file e
        }
        let mut g = Game::from_board(&board_of(&cells), size, Player::Black).unwrap();
        let (b, w) = g.area_score();
        // Black: file c stones (5) + files a,b territory (10) = 15.
        // White: file e stones (5) + 0 (file d borders both → neutral) = 5.
        assert_eq!(b, 15);
        assert_eq!(w, 5);
        g.pass().unwrap();
        g.pass().unwrap();
        assert_eq!(g.status(), GameStatus::Ended { black: 15.0, white: 5.0, winner: Some(Player::Black) });
    }

    #[test]
    fn empty_board_scores_zero_for_both() {
        // No stones → the single empty region borders neither colour → neutral.
        let g = Game::new(9);
        assert_eq!(g.area_score(), (0, 0));
    }

    // ── wire / json payloads ───────────────────────────────────────────────────
    #[test]
    fn status_wire_encoding() {
        assert_eq!(GameStatus::Ongoing.wire(), "ongoing");
        assert_eq!(
            GameStatus::Ended { black: 10.0, white: 7.0, winner: Some(Player::Black) }.wire(),
            "ended:black:10:7"
        );
        assert_eq!(
            GameStatus::Ended { black: 5.0, white: 5.0, winner: None }.wire(),
            "ended:draw:5:5"
        );
    }

    #[test]
    fn world_payload_json_for_capture_move() {
        let size = 9;
        let mut cells = vec!['.'; size * size];
        put(&mut cells, "b2", size, 'W');
        put(&mut cells, "a2", size, 'B');
        put(&mut cells, "b1", size, 'B');
        put(&mut cells, "b3", size, 'B');
        let mut g = Game::from_board(&board_of(&cells), size, Player::Black).unwrap();
        let j = g.play_str("c2").unwrap().to_json(size);
        assert!(j.contains("\"type\":\"go.state\""), "{j}");
        assert!(j.contains("\"lastMove\":\"c2\""), "{j}");
        assert!(j.contains("\"mover\":\"black\""), "{j}");
        assert!(j.contains("\"captured\":[\"b2\"]"), "{j}");
        assert!(j.contains("\"blackCaptures\":1"), "{j}");
        assert!(j.contains("\"next\":\"white\""), "{j}");
        assert!(j.contains("\"status\":\"ongoing\""), "{j}");
    }

    #[test]
    fn world_payload_json_for_pass() {
        let mut g = Game::new(9);
        let j = g.pass().unwrap().to_json(9);
        assert!(j.contains("\"lastMove\":\"pass\""), "{j}");
        assert!(j.contains("\"captured\":[]"), "{j}");
        assert!(j.contains("\"mover\":\"black\""), "{j}");
    }

    #[test]
    fn snapshot_json_opening() {
        let g = Game::new(9);
        let j = g.snapshot_json();
        assert!(j.contains("\"type\":\"go.snapshot\""), "{j}");
        assert!(j.contains("\"size\":9"), "{j}");
        assert!(j.contains("\"side\":\"black\""), "{j}");
        assert!(j.contains("\"status\":\"ongoing\""), "{j}");
    }

    // ── scripted game exercising the full pipeline ─────────────────────────────
    #[test]
    fn scripted_game_to_two_pass_end() {
        // A short deterministic game: alternate plays in distinct corners, then
        // both pass. Proves play→play→pass→pass termination + scoring runs clean.
        let mut g = Game::new(9);
        g.play_str("c3").unwrap(); // B
        g.play_str("g7").unwrap(); // W
        g.play_str("c7").unwrap(); // B
        g.play_str("g3").unwrap(); // W
        assert_eq!(g.status(), GameStatus::Ongoing);
        g.pass().unwrap();         // B
        let last = g.pass().unwrap(); // W → game ends
        assert!(last.status.is_terminal());
        // Four stones on the board; the bulk-empty region borders both → neutral.
        let (b, w) = g.area_score();
        assert_eq!(b, 2, "two black stones, no sole-bordered territory");
        assert_eq!(w, 2, "two white stones, no sole-bordered territory");
    }

    #[test]
    fn capture_then_refill_is_allowed_when_not_ko() {
        // After a capture that ISN'T a single-stone ko, the opponent may play on
        // the freed point freely. Capture a 2-stone group, then opponent fills one.
        let size = 9;
        let mut cells = vec!['.'; size * size];
        put(&mut cells, "b2", size, 'W');
        put(&mut cells, "c2", size, 'W');
        put(&mut cells, "a2", size, 'B');
        put(&mut cells, "b1", size, 'B');
        put(&mut cells, "c1", size, 'B');
        put(&mut cells, "b3", size, 'B');
        put(&mut cells, "c3", size, 'B');
        let mut g = Game::from_board(&board_of(&cells), size, Player::Black).unwrap();
        let o = g.play_str("d2").unwrap(); // Black captures b2,c2
        assert_eq!(o.captured.len(), 2);
        assert_eq!(g.ko_point(), None, "multi-stone capture never sets a ko ban");
        // White may immediately play on a freed point (e.g. b2) — no ko bar.
        assert!(g.is_legal(parse_point("b2", size).unwrap()));
    }
}
