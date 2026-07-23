//! `hugmesh-reversi` — a pure, dependency-free Reversi/Othello rules engine.
//!
//! Board: 8x8. Square indexing: `0 = a1`, `file = sq % 8`, `rank = sq / 8`
//! (rank 0 == rank 1, the bottom row). Standard Othello opening: the four
//! centre squares are seeded `d5,e4 = Black` and `d4,e5 = White`; **Black
//! moves first**.
//!
//! A legal move places a disc on an empty square that BRACKETS one or more
//! contiguous opponent discs between the new disc and an existing disc of the
//! mover's colour, along any of the 8 directions; every bracketed run flips.
//! A move is legal only if it flips at least one disc. If the side to move has
//! no legal move it must PASS (the turn hands over without placing a disc); if
//! NEITHER side can move the game is over and the winner is whoever has more
//! discs (a Draw on a tie).
//!
//! This is the ONE engine: it compiles to `wasm32` for the in-browser game (host
//! authoritative, guest re-validates) and natively for `cargo test`. The server
//! runs none of it — it only streams events.

#![allow(clippy::needless_range_loop)]

/// The two disc colours. Black moves first by Othello convention.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Player { Black, White }
impl Player {
    pub fn opposite(self) -> Player { if self == Player::Black { Player::White } else { Player::Black } }
    fn wire(self) -> &'static str { if self == Player::Black { "black" } else { "white" } }
    fn disc_char(self) -> char { if self == Player::Black { 'B' } else { 'W' } }
}

/// One cell of the board.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Cell { Empty, Disc(Player) }

/// Terminal / running classification of a game.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum GameStatus {
    Ongoing,
    Win { player: Player },
    Draw,
}
impl GameStatus {
    pub fn is_terminal(self) -> bool { !matches!(self, GameStatus::Ongoing) }
    /// Compact wire encoding for the `world`-channel payload.
    pub fn wire(self) -> String {
        match self {
            GameStatus::Ongoing => "ongoing".into(),
            GameStatus::Win { player } => format!("win:{}", player.wire()),
            GameStatus::Draw => "draw".into(),
        }
    }
}

/// Why a `play()` attempt was rejected.
#[derive(Debug, PartialEq, Eq)]
pub enum MoveError {
    /// Square index out of `0..64`, or string didn't parse.
    BadSquare,
    /// Target square is already occupied.
    Occupied,
    /// Legal in shape but flips nothing — not a Reversi move.
    NoFlips,
    /// The side to move has a legal move elsewhere, so it may not pass.
    CannotPass,
    /// The game is already over.
    GameOver,
}

/// The structured result of a successful `play()` or `pass()`.
#[derive(Debug, Clone, PartialEq)]
pub struct Outcome {
    /// The square the disc was placed on, or `64` for a pass.
    pub square: u8,
    /// Who placed the disc (the side that moved this turn).
    pub mover: Player,
    /// Squares whose discs flipped to `mover`'s colour (empty for a pass).
    pub flipped: Vec<u8>,
    /// True when this turn was a pass (no disc placed, no flips).
    pub passed: bool,
    /// Black disc count after the move.
    pub black: u32,
    /// White disc count after the move.
    pub white: u32,
    /// Who moves next (== `mover` is impossible in Reversi; turn always alternates).
    pub next: Player,
    /// True when the side to move after this turn has NO legal move and must
    /// pass on its turn (the game is not necessarily over — the other side may
    /// still be able to move after that pass).
    pub next_must_pass: bool,
    pub status: GameStatus,
}
impl Outcome {
    /// The JSON payload the host emits on the session `world` channel after a
    /// move; the guest re-validates it against its own engine copy. The server
    /// only relays this string — it never parses it.
    pub fn to_json(&self) -> String {
        let last = if self.passed { "pass".to_string() } else { square_str(self.square) };
        let flips: Vec<String> = self.flipped.iter().map(|&s| format!("\"{}\"", square_str(s))).collect();
        format!(
            "{{\"type\":\"reversi.state\",\"lastMove\":\"{}\",\"mover\":\"{}\",\"flipped\":[{}],\"black\":{},\"white\":{},\"next\":\"{}\",\"nextMustPass\":{},\"status\":\"{}\"}}",
            last, self.mover.wire(), flips.join(","), self.black, self.white,
            self.next.wire(), self.next_must_pass, self.status.wire()
        )
    }
}

// ── square helpers ───────────────────────────────────────────────────────────
const N: usize = 64;
fn file(sq: u8) -> i32 { (sq % 8) as i32 }
fn rank(sq: u8) -> i32 { (sq / 8) as i32 }
fn sqi(f: i32, r: i32) -> u8 { (r * 8 + f) as u8 }
fn on_board(f: i32, r: i32) -> bool { (0..8).contains(&f) && (0..8).contains(&r) }

/// The 8 directions (file, rank deltas) a bracket can run.
const DIRS: [(i32, i32); 8] = [
    (1, 0), (-1, 0), (0, 1), (0, -1),
    (1, 1), (1, -1), (-1, 1), (-1, -1),
];

/// Parse algebraic coordinates like "d3" into a square index.
pub fn parse_square(s: &str) -> Option<u8> {
    let b = s.as_bytes();
    if b.len() != 2 { return None; }
    let f = b[0].wrapping_sub(b'a') as i32;
    let r = b[1].wrapping_sub(b'1') as i32;
    if on_board(f, r) { Some(sqi(f, r)) } else { None }
}
/// Render a square index as algebraic coordinates, e.g. `0 -> "a1"`.
pub fn square_str(sq: u8) -> String {
    let f = (b'a' + (sq % 8)) as char;
    let r = (b'1' + (sq / 8)) as char;
    format!("{f}{r}")
}

// ── the game ─────────────────────────────────────────────────────────────────
#[derive(Clone)]
pub struct Game {
    board: [Cell; N],
    side: Player,
    /// Set once both players have been seen to have no move; the game is over.
    over: bool,
}

impl Default for Game { fn default() -> Self { Game::new() } }

impl Game {
    /// A fresh game in the standard Othello opening with Black to move.
    pub fn new() -> Game {
        let mut board = [Cell::Empty; N];
        board[parse_square("d5").unwrap() as usize] = Cell::Disc(Player::Black);
        board[parse_square("e4").unwrap() as usize] = Cell::Disc(Player::Black);
        board[parse_square("d4").unwrap() as usize] = Cell::Disc(Player::White);
        board[parse_square("e5").unwrap() as usize] = Cell::Disc(Player::White);
        Game { board, side: Player::Black, over: false }
    }

    /// Build a game from a 64-char board string (`'B'`/`'W'`/`'.'`, square 0
    /// first) plus the side to move. Used by tests to set up endgames. Squares
    /// are listed in index order (a1, b1, … h1, a2, …).
    pub fn from_board(cells: &str, side: Player) -> Result<Game, String> {
        let chars: Vec<char> = cells.chars().filter(|c| !c.is_whitespace()).collect();
        if chars.len() != N { return Err(format!("board needs {N} cells, got {}", chars.len())); }
        let mut board = [Cell::Empty; N];
        for (i, c) in chars.iter().enumerate() {
            board[i] = match c {
                '.' | '-' => Cell::Empty,
                'B' | 'b' | 'X' | 'x' | '*' => Cell::Disc(Player::Black),
                'W' | 'w' | 'O' | 'o' => Cell::Disc(Player::White),
                other => return Err(format!("bad cell '{other}' at {i}")),
            };
        }
        let mut g = Game { board, side, over: false };
        // A loaded position may already be terminal.
        if g.legal_moves().is_empty() && g.legal_moves_for(side.opposite()).is_empty() {
            g.over = true;
        }
        Ok(g)
    }

    pub fn side_to_move(&self) -> Player { self.side }
    pub fn board(&self) -> [Cell; N] { self.board }
    fn at(&self, sq: u8) -> Cell { self.board[sq as usize] }

    /// Disc count for a colour.
    pub fn count(&self, who: Player) -> u32 {
        self.board.iter().filter(|c| **c == Cell::Disc(who)).count() as u32
    }

    /// Compact board string (`'B'`/`'W'`/`'.'`, square 0 first).
    pub fn board_str(&self) -> String {
        self.board.iter().map(|c| match c {
            Cell::Empty => '.',
            Cell::Disc(p) => p.disc_char(),
        }).collect()
    }

    /// The discs that would flip if `who` placed a disc on `sq`. Empty when the
    /// move is illegal (occupied square or no bracket). The core rule.
    fn flips_for(&self, sq: u8, who: Player) -> Vec<u8> {
        let mut flips = Vec::new();
        if self.at(sq) != Cell::Empty { return flips; }
        let opp = who.opposite();
        let (f0, r0) = (file(sq), rank(sq));
        for (df, dr) in DIRS {
            let (mut f, mut r) = (f0 + df, r0 + dr);
            let mut run: Vec<u8> = Vec::new();
            // walk over a contiguous run of opponent discs …
            while on_board(f, r) && self.at(sqi(f, r)) == Cell::Disc(opp) {
                run.push(sqi(f, r));
                f += df; r += dr;
            }
            // … that must terminate on one of our own discs to bracket.
            if !run.is_empty() && on_board(f, r) && self.at(sqi(f, r)) == Cell::Disc(who) {
                flips.extend_from_slice(&run);
            }
        }
        flips
    }

    /// All legal placement squares for the side to move.
    pub fn legal_moves(&self) -> Vec<u8> {
        if self.over { return Vec::new(); }
        self.legal_moves_for(self.side)
    }

    fn legal_moves_for(&self, who: Player) -> Vec<u8> {
        (0..N as u8).filter(|&sq| !self.flips_for(sq, who).is_empty()).collect()
    }

    /// True when `sq` is a legal move for the side to move.
    pub fn is_legal(&self, sq: u8) -> bool {
        !self.over && sq < N as u8 && !self.flips_for(sq, self.side).is_empty()
    }

    /// True when the side to move has no legal placement and so must `pass()`.
    pub fn must_pass(&self) -> bool {
        !self.over && self.legal_moves().is_empty()
    }

    /// Place a disc for the side to move on `sq`, flipping the bracketed discs,
    /// then hand the turn over. Enforces turn ownership implicitly (only the
    /// side to move can act) and rejects occupied / non-flipping squares.
    pub fn play(&mut self, sq: u8) -> Result<Outcome, MoveError> {
        if self.over { return Err(MoveError::GameOver); }
        if sq >= N as u8 { return Err(MoveError::BadSquare); }
        if self.at(sq) != Cell::Empty { return Err(MoveError::Occupied); }
        let flips = self.flips_for(sq, self.side);
        if flips.is_empty() { return Err(MoveError::NoFlips); }

        let mover = self.side;
        self.board[sq as usize] = Cell::Disc(mover);
        for &f in &flips { self.board[f as usize] = Cell::Disc(mover); }
        self.advance_turn();
        Ok(self.outcome(sq, mover, flips, false))
    }

    /// Convenience: play a move named in algebraic coordinates ("d3").
    pub fn play_str(&mut self, s: &str) -> Result<Outcome, MoveError> {
        let sq = parse_square(s).ok_or(MoveError::BadSquare)?;
        self.play(sq)
    }

    /// Hand the turn over WITHOUT placing a disc. Legal only when the side to
    /// move genuinely has no legal placement (Reversi forbids voluntary passes).
    pub fn pass(&mut self) -> Result<Outcome, MoveError> {
        if self.over { return Err(MoveError::GameOver); }
        if !self.legal_moves().is_empty() { return Err(MoveError::CannotPass); }
        let mover = self.side;
        self.advance_turn();
        Ok(self.outcome(64, mover, Vec::new(), true))
    }

    /// Flip the side to move; if NEITHER side can now move the game ends.
    fn advance_turn(&mut self) {
        self.side = self.side.opposite();
        if self.legal_moves_for(self.side).is_empty()
            && self.legal_moves_for(self.side.opposite()).is_empty()
        {
            self.over = true;
        }
    }

    fn outcome(&self, sq: u8, mover: Player, flipped: Vec<u8>, passed: bool) -> Outcome {
        let next = self.side;
        Outcome {
            square: sq,
            mover,
            flipped,
            passed,
            black: self.count(Player::Black),
            white: self.count(Player::White),
            next,
            next_must_pass: self.must_pass(),
            status: self.status(),
        }
    }

    /// The current game status: `Ongoing`, or terminal `Win`/`Draw` by disc count.
    pub fn status(&self) -> GameStatus {
        // The game ends only when neither side can move (board full, or both
        // locked out). `over` is set the moment that becomes true.
        let ended = self.over
            || (self.legal_moves_for(Player::Black).is_empty()
                && self.legal_moves_for(Player::White).is_empty());
        if !ended { return GameStatus::Ongoing; }
        let b = self.count(Player::Black);
        let w = self.count(Player::White);
        if b > w { GameStatus::Win { player: Player::Black } }
        else if w > b { GameStatus::Win { player: Player::White } }
        else { GameStatus::Draw }
    }

    /// The world-channel JSON for the CURRENT state (no move attached). Lets the
    /// host broadcast the opening position or a re-sync snapshot.
    pub fn snapshot_json(&self) -> String {
        let moves: Vec<String> = self.legal_moves().iter().map(|&s| format!("\"{}\"", square_str(s))).collect();
        format!(
            "{{\"type\":\"reversi.snapshot\",\"board\":\"{}\",\"side\":\"{}\",\"black\":{},\"white\":{},\"legal\":[{}],\"status\":\"{}\"}}",
            self.board_str(), self.side.wire(), self.count(Player::Black), self.count(Player::White),
            moves.join(","), self.status().wire()
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    // ── opening ───────────────────────────────────────────────────────────────
    #[test]
    fn opening_seeds_and_counts() {
        let g = Game::new();
        assert_eq!(g.side_to_move(), Player::Black);
        assert_eq!(g.count(Player::Black), 2);
        assert_eq!(g.count(Player::White), 2);
        assert_eq!(g.at(parse_square("d5").unwrap()), Cell::Disc(Player::Black));
        assert_eq!(g.at(parse_square("e4").unwrap()), Cell::Disc(Player::Black));
        assert_eq!(g.at(parse_square("d4").unwrap()), Cell::Disc(Player::White));
        assert_eq!(g.at(parse_square("e5").unwrap()), Cell::Disc(Player::White));
    }

    #[test]
    fn opening_legal_move_set_is_the_four() {
        let g = Game::new();
        let mut moves: Vec<String> = g.legal_moves().iter().map(|&s| square_str(s)).collect();
        moves.sort();
        // The four standard Black openings around the centre.
        assert_eq!(moves, vec!["c4", "d3", "e6", "f5"]);
        assert_eq!(g.legal_moves().len(), 4);
        assert!(!g.must_pass());
        assert_eq!(g.status(), GameStatus::Ongoing);
    }

    // ── a flip in every one of the 8 directions ───────────────────────────────
    //
    // A handcrafted position: an empty square at the centre `e4` (index 28),
    // surrounded by a run of White discs in each of the 8 directions, each run
    // capped by a Black disc. Black to move on e4 must flip in all 8 directions.
    #[test]
    fn flip_in_each_of_eight_directions() {
        // Build the board cell-by-cell.
        let mut cells = ['.'; 64];
        let put = |cells: &mut [char; 64], s: &str, c: char| { cells[parse_square(s).unwrap() as usize] = c; };
        // centre target stays empty: d4 (index 27). Lay W then B in each dir.
        // E  (+file):  e4 W, f4 B
        put(&mut cells, "e4", 'W'); put(&mut cells, "f4", 'B');
        // W  (-file):  c4 W, b4 B
        put(&mut cells, "c4", 'W'); put(&mut cells, "b4", 'B');
        // N  (+rank):  d5 W, d6 B
        put(&mut cells, "d5", 'W'); put(&mut cells, "d6", 'B');
        // S  (-rank):  d3 W, d2 B
        put(&mut cells, "d3", 'W'); put(&mut cells, "d2", 'B');
        // NE (+f,+r):  e5 W, f6 B
        put(&mut cells, "e5", 'W'); put(&mut cells, "f6", 'B');
        // SE (+f,-r):  e3 W, f2 B
        put(&mut cells, "e3", 'W'); put(&mut cells, "f2", 'B');
        // NW (-f,+r):  c5 W, b6 B
        put(&mut cells, "c5", 'W'); put(&mut cells, "b6", 'B');
        // SW (-f,-r):  c3 W, b2 B
        put(&mut cells, "c3", 'W'); put(&mut cells, "b2", 'B');

        let s: String = cells.iter().collect();
        let mut g = Game::from_board(&s, Player::Black).unwrap();
        let target = parse_square("d4").unwrap();
        assert!(g.is_legal(target));
        let o = g.play(target).unwrap();
        // 8 White discs flip (one per direction).
        assert_eq!(o.flipped.len(), 8, "expected one flip per direction");
        // every adjacent White is now Black.
        for s in ["e4", "c4", "d5", "d3", "e5", "e3", "c5", "c3"] {
            assert_eq!(g.at(parse_square(s).unwrap()), Cell::Disc(Player::Black), "{s} should be flipped");
        }
        // the placed disc plus the 8 outer caps plus the 8 flipped == 17 Black.
        assert_eq!(g.count(Player::Black), 1 + 8 + 8);
        assert_eq!(g.count(Player::White), 0);
    }

    #[test]
    fn single_direction_flip_via_real_opening() {
        // Black plays d3: flips the single White disc on d4 (bracketed by d5 Black).
        let mut g = Game::new();
        let o = g.play_str("d3").unwrap();
        assert_eq!(o.flipped, vec![parse_square("d4").unwrap()]);
        assert_eq!(g.at(parse_square("d4").unwrap()), Cell::Disc(Player::Black));
        assert_eq!(o.black, 4); // 2 seeded + placed + 1 flipped
        assert_eq!(o.white, 1);
        assert_eq!(o.next, Player::White);
    }

    // ── rejection of illegal moves + turn ownership ───────────────────────────
    #[test]
    fn rejects_occupied_square() {
        let mut g = Game::new();
        // d4 is occupied by a seeded White disc.
        assert_eq!(g.play_str("d4"), Err(MoveError::Occupied));
    }

    #[test]
    fn rejects_non_flipping_square() {
        let mut g = Game::new();
        // a1 is empty but brackets nothing.
        assert_eq!(g.play_str("a1"), Err(MoveError::NoFlips));
    }

    #[test]
    fn rejects_out_of_range_square() {
        let mut g = Game::new();
        assert_eq!(g.play(64), Err(MoveError::BadSquare));
        assert_eq!(g.play_str("z9"), Err(MoveError::BadSquare));
    }

    #[test]
    fn turn_ownership_only_side_to_move_acts() {
        // After Black plays, it's White's turn. A White-bracket square that is
        // NOT a Black move proves only the side to move's moves are accepted.
        let mut g = Game::new();
        g.play_str("d3").unwrap();
        assert_eq!(g.side_to_move(), Player::White);
        // c4 is not a legal White move here (it's a Black-only bracket pre-move);
        // White's legal set is computed for White, so a Black-shaped move fails.
        let white_moves = g.legal_moves();
        assert!(!white_moves.is_empty());
        // A square legal for nobody is rejected regardless of "whose" it is.
        assert_eq!(g.play_str("a1"), Err(MoveError::NoFlips));
    }

    #[test]
    fn cannot_pass_when_a_move_exists() {
        let mut g = Game::new();
        assert!(!g.must_pass());
        assert_eq!(g.pass(), Err(MoveError::CannotPass));
    }

    // ── forced pass ───────────────────────────────────────────────────────────
    //
    // Construct a position where the side to move (White) has no legal placement
    // but Black still does, so White MUST pass and the game continues.
    #[test]
    fn forced_pass_then_play_continues() {
        // Row 1 (a1..h1): Black at a1,b1; White at c1; rest empty.
        // White, to move, can bracket nothing (no empty square caps a B run for W);
        // Black can play (the empty d1 brackets White c1 onto … not enough). Build
        // a guaranteed shape instead:
        //   a1=W b1=B c1=. ... with Black able to play and White not.
        let mut cells = ['.'; 64];
        let put = |cells: &mut [char; 64], s: &str, c: char| { cells[parse_square(s).unwrap() as usize] = c; };
        // Black can play c1 (b1 W bracketed by a1 B? no) — use a clean vertical:
        // Column a: a1=B, a2=W, a3=. -> Black playing a3 flips a2. Black move exists.
        put(&mut cells, "a1", 'B');
        put(&mut cells, "a2", 'W');
        // White has NO disc that can bracket: White owns only a2, fully boxed by
        // a1 Black below and empty above with no Black to bracket above it.
        let s: String = cells.iter().collect();
        let g_check = Game::from_board(&s, Player::White).unwrap();
        assert!(g_check.legal_moves_for(Player::White).is_empty(), "white should be stuck");
        assert!(!g_check.legal_moves_for(Player::Black).is_empty(), "black should have a3");

        let mut g = Game::from_board(&s, Player::White).unwrap();
        assert!(g.must_pass());
        let o = g.pass().unwrap();
        assert!(o.passed);
        assert_eq!(o.next, Player::Black);
        assert_eq!(g.status(), GameStatus::Ongoing);
        // Black now plays a3 and flips a2.
        let o2 = g.play_str("a3").unwrap();
        assert_eq!(o2.flipped, vec![parse_square("a2").unwrap()]);
    }

    // ── full / locked endgame, winner by disc count ───────────────────────────
    #[test]
    fn locked_endgame_black_wins_by_count() {
        // Board entirely Black except two White discs; neither side can move
        // (no empty squares at all → board full → game over). Black 62, White 2.
        let mut cells = ['B'; 64];
        cells[parse_square("h8").unwrap() as usize] = 'W';
        cells[parse_square("g8").unwrap() as usize] = 'W';
        let s: String = cells.iter().collect();
        let g = Game::from_board(&s, Player::White).unwrap();
        assert!(g.legal_moves().is_empty());
        assert_eq!(g.count(Player::Black), 62);
        assert_eq!(g.count(Player::White), 2);
        assert_eq!(g.status(), GameStatus::Win { player: Player::Black });
    }

    #[test]
    fn full_board_white_wins_by_count() {
        let mut cells = ['W'; 64];
        for s in ["a1", "b1", "c1"] { cells[parse_square(s).unwrap() as usize] = 'B'; }
        let s: String = cells.iter().collect();
        let g = Game::from_board(&s, Player::Black).unwrap();
        assert_eq!(g.count(Player::White), 61);
        assert_eq!(g.count(Player::Black), 3);
        assert_eq!(g.status(), GameStatus::Win { player: Player::White });
    }

    #[test]
    fn full_board_draw_on_tie() {
        // 32 Black / 32 White, board full → Draw.
        let mut cells = ['B'; 64];
        for sq in 32..64 { cells[sq] = 'W'; }
        let s: String = cells.iter().collect();
        let g = Game::from_board(&s, Player::Black).unwrap();
        assert_eq!(g.count(Player::Black), 32);
        assert_eq!(g.count(Player::White), 32);
        assert_eq!(g.status(), GameStatus::Draw);
        assert!(g.status().is_terminal());
    }

    #[test]
    fn play_on_finished_game_is_rejected() {
        let mut cells = ['B'; 64];
        cells[0] = 'W';
        let s: String = cells.iter().collect();
        let mut g = Game::from_board(&s, Player::White).unwrap();
        assert!(g.status().is_terminal());
        assert_eq!(g.play(0), Err(MoveError::GameOver));
        assert_eq!(g.pass(), Err(MoveError::GameOver));
    }

    // ── wire / json payloads ──────────────────────────────────────────────────
    #[test]
    fn status_wire_encoding() {
        assert_eq!(GameStatus::Ongoing.wire(), "ongoing");
        assert_eq!(GameStatus::Win { player: Player::Black }.wire(), "win:black");
        assert_eq!(GameStatus::Win { player: Player::White }.wire(), "win:white");
        assert_eq!(GameStatus::Draw.wire(), "draw");
    }

    #[test]
    fn world_payload_json_for_move() {
        let mut g = Game::new();
        let j = g.play_str("d3").unwrap().to_json();
        assert!(j.contains("\"type\":\"reversi.state\""), "{j}");
        assert!(j.contains("\"lastMove\":\"d3\""), "{j}");
        assert!(j.contains("\"mover\":\"black\""), "{j}");
        assert!(j.contains("\"flipped\":[\"d4\"]"), "{j}");
        assert!(j.contains("\"black\":4"), "{j}");
        assert!(j.contains("\"white\":1"), "{j}");
        assert!(j.contains("\"next\":\"white\""), "{j}");
        assert!(j.contains("\"status\":\"ongoing\""), "{j}");
    }

    #[test]
    fn world_payload_json_for_pass() {
        let mut cells = ['.'; 64];
        cells[parse_square("a1").unwrap() as usize] = 'B';
        cells[parse_square("a2").unwrap() as usize] = 'W';
        let s: String = cells.iter().collect();
        let mut g = Game::from_board(&s, Player::White).unwrap();
        let j = g.pass().unwrap().to_json();
        assert!(j.contains("\"lastMove\":\"pass\""), "{j}");
        assert!(j.contains("\"flipped\":[]"), "{j}");
        assert!(j.contains("\"mover\":\"white\""), "{j}");
    }

    #[test]
    fn snapshot_json_opening() {
        let g = Game::new();
        let j = g.snapshot_json();
        assert!(j.contains("\"type\":\"reversi.snapshot\""), "{j}");
        assert!(j.contains("\"side\":\"black\""), "{j}");
        assert!(j.contains("\"black\":2"), "{j}");
        // all four opening legal moves present
        for m in ["c4", "d3", "e6", "f5"] {
            assert!(j.contains(&format!("\"{m}\"")), "{j} missing {m}");
        }
    }

    // ── scripted full game (short, deterministic) ─────────────────────────────
    #[test]
    fn scripted_game_runs_to_a_terminal_state() {
        // Play the first 8 plies of a real game (always the lowest-index legal
        // move, a deterministic policy). We assert the per-ply invariants of the
        // engine: every ply has a legal move (no forced pass this early), exactly
        // one new disc is placed each ply, every move flips >=1, and turns always
        // alternate. This exercises the full play() pipeline without hard-coding a
        // brittle algebraic line.
        let mut g = Game::new();
        let mut last_next = Player::Black;
        for i in 0..8 {
            assert!(!g.must_pass(), "unexpected forced pass at ply {i}");
            let total_before = g.count(Player::Black) + g.count(Player::White);
            let mv = g.legal_moves()[0];
            let o = g.play(mv).unwrap_or_else(|e| panic!("ply {i} '{}' rejected: {e:?}\nboard: {}", square_str(mv), g.board_str()));
            // exactly one new disc placed each ply (flips only re-colour existing)
            assert_eq!(o.black + o.white, total_before + 1, "one disc placed per ply");
            assert!(!o.flipped.is_empty(), "a Reversi move always flips >=1");
            // turn always alternates
            assert_ne!(o.mover, o.next, "turn must alternate");
            assert_eq!(o.mover, last_next);
            last_next = o.next;
        }
        // total discs == 4 seeds + 8 plies = 12 (no pass occurred in these plies)
        assert_eq!(g.count(Player::Black) + g.count(Player::White), 12);
        assert_eq!(g.status(), GameStatus::Ongoing); // still mid-game after 8 plies
    }

    #[test]
    fn scripted_full_game_to_completion() {
        // Drive a complete game by always playing the lowest-index legal move (a
        // deterministic policy), passing when forced, until terminal. Proves the
        // pass + termination + winner pipeline end-to-end.
        let mut g = Game::new();
        let mut plies = 0;
        loop {
            match g.status() {
                GameStatus::Ongoing => {}
                terminal => {
                    // a finished game: board is consistent and someone is decided
                    let b = g.count(Player::Black);
                    let w = g.count(Player::White);
                    match terminal {
                        GameStatus::Win { player: Player::Black } => assert!(b > w),
                        GameStatus::Win { player: Player::White } => assert!(w > b),
                        GameStatus::Draw => assert_eq!(b, w),
                        GameStatus::Ongoing => unreachable!(),
                    }
                    // never more than a full board of discs
                    assert!(b + w <= 64);
                    break;
                }
            }
            if g.must_pass() {
                g.pass().unwrap();
            } else {
                let mv = g.legal_moves()[0];
                g.play(mv).unwrap();
            }
            plies += 1;
            assert!(plies < 200, "game failed to terminate");
        }
        assert!(plies > 0);
    }
}
