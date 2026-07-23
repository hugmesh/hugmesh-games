//! `hugmesh-connect-four` — a pure, dependency-free Connect Four rules engine.
//!
//! Board geometry: a classic 7-column × 6-row grid. A cell is addressed by
//! `(col, row)` with `col` in `0..7` and `row` in `0..6`, where `row = 0` is the
//! BOTTOM of a column (the first disc dropped into a column lands there) and
//! `row = 5` is the top. Dropping into a column places a disc on top of the
//! lowest empty cell — gravity, exactly like the physical game.
//!
//! This is the ONE engine: it compiles to `wasm32` for the in-browser game (host
//! authoritative, guest re-validates) and natively for `cargo test`. The server
//! runs none of it — it only streams events. See `docs/multiplayer-chess-design.md`
//! for the platform authority model the chess engine established and this mirrors.

#![allow(clippy::needless_range_loop)]

pub const COLS: usize = 7;
pub const ROWS: usize = 6;
const CONNECT: usize = 4; // discs in a row needed to win

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Player {
    Red,
    Yellow,
}
impl Player {
    /// The other player. Red and Yellow strictly alternate; this is the single
    /// side-to-move, so "wrong turn" is structurally impossible — you can only
    /// ever drop as the player whose turn it is.
    pub fn opposite(self) -> Player {
        match self {
            Player::Red => Player::Yellow,
            Player::Yellow => Player::Red,
        }
    }
    /// Compact wire token for the `world`-channel payload.
    pub fn wire(self) -> &'static str {
        match self {
            Player::Red => "red",
            Player::Yellow => "yellow",
        }
    }
    /// Single-character board glyph: `R` / `Y`.
    pub fn glyph(self) -> char {
        match self {
            Player::Red => 'R',
            Player::Yellow => 'Y',
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum GameStatus {
    Ongoing,
    Win { winner: Player },
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
            GameStatus::Win { winner } => format!("win:{}", winner.wire()),
            GameStatus::Draw => "draw".into(),
        }
    }
}

/// Why a `drop` was rejected. Turn ownership is NOT an error variant: the engine
/// owns the single side-to-move, so a caller cannot drop on the wrong player's
/// behalf — the engine always plays the disc for `side_to_move`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MoveError {
    /// Column index was outside `0..COLS`.
    OutOfRange,
    /// Column is full (all `ROWS` cells occupied) — no legal landing cell.
    ColumnFull,
    /// The game already ended (a win or a draw); no further drops are legal.
    GameOver,
}

/// The structured result of a successful `drop`: which player moved, the column
/// and the row the disc settled in, and the resulting status.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DropOutcome {
    pub player: Player,
    pub col: usize,
    pub row: usize,
    /// If this drop won, the four cells `(col, row)` that line up. Empty otherwise.
    pub winning_line: Vec<(usize, usize)>,
    pub status: GameStatus,
}

impl DropOutcome {
    /// The compact move-only JSON for the just-played drop (player + landing cell
    /// + status + winning line). For the full-board world-channel snapshot the
    /// host actually relays, use [`Game::to_json`] — it mirrors the chess
    /// engine's `MoveOutcome::to_json` shape (`{ "type": "<game>.state", ... }`).
    pub fn to_json(&self) -> String {
        let line = self
            .winning_line
            .iter()
            .map(|(c, r)| format!("[{c},{r}]"))
            .collect::<Vec<_>>()
            .join(",");
        format!(
            "{{\"type\":\"connect_four.move\",\"player\":\"{}\",\"col\":{},\"row\":{},\"status\":\"{}\",\"winningLine\":[{}]}}",
            self.player.wire(),
            self.col,
            self.row,
            self.status.wire(),
            line,
        )
    }
}

#[derive(Clone)]
pub struct Game {
    /// `cells[col][row]` — `None` empty, `Some(player)` occupied. `row = 0` is the
    /// bottom of the column.
    cells: [[Option<Player>; ROWS]; COLS],
    /// Number of discs currently in each column (== index of the next free row).
    heights: [usize; COLS],
    side: Player,
    status: GameStatus,
    moves_played: usize,
}

impl Default for Game {
    fn default() -> Self {
        Game::new()
    }
}

impl Game {
    /// A fresh empty board; Red moves first (classic convention).
    pub fn new() -> Game {
        Game {
            cells: [[None; ROWS]; COLS],
            heights: [0; COLS],
            side: Player::Red,
            status: GameStatus::Ongoing,
            moves_played: 0,
        }
    }

    pub fn side_to_move(&self) -> Player {
        self.side
    }
    pub fn status(&self) -> GameStatus {
        self.status
    }
    pub fn moves_played(&self) -> usize {
        self.moves_played
    }

    /// The disc at `(col, row)` if any. Out-of-range cells read as `None`.
    pub fn at(&self, col: usize, row: usize) -> Option<Player> {
        if col < COLS && row < ROWS {
            self.cells[col][row]
        } else {
            None
        }
    }

    /// How many discs are stacked in `col` (0..=ROWS).
    pub fn height(&self, col: usize) -> usize {
        if col < COLS {
            self.heights[col]
        } else {
            0
        }
    }

    /// Is `col` a legal drop target right now? (in range, not full, game live)
    pub fn can_drop(&self, col: usize) -> bool {
        !self.status.is_terminal() && col < COLS && self.heights[col] < ROWS
    }

    /// Every column that can currently be dropped into. Empty once the game ends.
    pub fn legal_moves(&self) -> Vec<usize> {
        if self.status.is_terminal() {
            return Vec::new();
        }
        (0..COLS).filter(|&c| self.heights[c] < ROWS).collect()
    }

    /// Drop a disc for the side to move into `col`. Enforces range, fullness, and
    /// game-over. On success advances the whole state (places the disc, detects a
    /// win/draw, flips the side) and returns the structured outcome.
    pub fn drop(&mut self, col: usize) -> Result<DropOutcome, MoveError> {
        if self.status.is_terminal() {
            return Err(MoveError::GameOver);
        }
        if col >= COLS {
            return Err(MoveError::OutOfRange);
        }
        let row = self.heights[col];
        if row >= ROWS {
            return Err(MoveError::ColumnFull);
        }

        let player = self.side;
        self.cells[col][row] = Some(player);
        self.heights[col] = row + 1;
        self.moves_played += 1;

        let winning_line = self.line_through(col, row, player);
        self.status = if !winning_line.is_empty() {
            GameStatus::Win { winner: player }
        } else if self.moves_played == COLS * ROWS {
            GameStatus::Draw
        } else {
            GameStatus::Ongoing
        };

        // Only advance the turn while the game is still live; once terminal the
        // side-to-move freezes (mirrors chess where status() is the terminal gate).
        if self.status == GameStatus::Ongoing {
            self.side = player.opposite();
        }

        Ok(DropOutcome {
            player,
            col,
            row,
            winning_line,
            status: self.status,
        })
    }

    /// If placing `player`'s disc at `(col, row)` completes a line of `CONNECT`,
    /// return all the cells of that line; otherwise an empty vec. Checks the four
    /// axes (horizontal, vertical, both diagonals) through the just-placed cell.
    fn line_through(&self, col: usize, row: usize, player: Player) -> Vec<(usize, usize)> {
        // (dcol, drow) for each of the four axes.
        const AXES: [(isize, isize); 4] = [
            (1, 0), // horizontal —
            (0, 1), // vertical |
            (1, 1), // diagonal /
            (1, -1), // diagonal \
        ];
        for (dc, dr) in AXES {
            let mut line = vec![(col, row)];
            // walk forward then backward along the axis collecting same-color cells
            for sign in [1isize, -1] {
                let mut c = col as isize + dc * sign;
                let mut r = row as isize + dr * sign;
                while c >= 0
                    && c < COLS as isize
                    && r >= 0
                    && r < ROWS as isize
                    && self.cells[c as usize][r as usize] == Some(player)
                {
                    line.push((c as usize, r as usize));
                    c += dc * sign;
                    r += dr * sign;
                }
            }
            if line.len() >= CONNECT {
                // Return a clean, sorted run of exactly the connected cells.
                line.sort_unstable();
                return line;
            }
        }
        Vec::new()
    }

    /// Compact board string for the wire payload + debugging: 6 rows top-to-bottom,
    /// columns left-to-right, `.`=empty `R`=red `Y`=yellow, rows joined by `/`.
    pub fn board_str(&self) -> String {
        let mut out = String::with_capacity(ROWS * (COLS + 1));
        for row in (0..ROWS).rev() {
            for col in 0..COLS {
                out.push(self.cells[col][row].map(|p| p.glyph()).unwrap_or('.'));
            }
            if row > 0 {
                out.push('/');
            }
        }
        out
    }

    /// The JSON payload the host emits on the session `world` channel after a
    /// drop — the full board + status. The server only relays this; the guest
    /// re-validates it against its own engine copy. Mirrors chess's `to_json`.
    pub fn to_json(&self, last: Option<&DropOutcome>) -> String {
        let (player, col, row, line) = match last {
            Some(o) => {
                let line = o
                    .winning_line
                    .iter()
                    .map(|(c, r)| format!("[{c},{r}]"))
                    .collect::<Vec<_>>()
                    .join(",");
                (o.player.wire(), o.col as isize, o.row as isize, line)
            }
            None => ("none", -1, -1, String::new()),
        };
        format!(
            "{{\"type\":\"connect_four.state\",\"lastPlayer\":\"{}\",\"lastCol\":{},\"lastRow\":{},\"toMove\":\"{}\",\"status\":\"{}\",\"winningLine\":[{}],\"board\":\"{}\"}}",
            player,
            col,
            row,
            self.side.wire(),
            self.status.wire(),
            line,
            self.board_str()
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Drive a scripted sequence of column drops; panics with context on any
    /// rejected move so a bad script is obvious.
    fn play(cols: &[usize]) -> Game {
        let mut g = Game::new();
        for (i, &c) in cols.iter().enumerate() {
            g.drop(c)
                .unwrap_or_else(|e| panic!("drop {} into col {} rejected: {:?}\n{}", i + 1, c, e, g.board_str()));
        }
        g
    }

    #[test]
    fn fresh_board_state() {
        let g = Game::new();
        assert_eq!(g.side_to_move(), Player::Red);
        assert_eq!(g.status(), GameStatus::Ongoing);
        assert_eq!(g.legal_moves(), vec![0, 1, 2, 3, 4, 5, 6]);
        assert_eq!(g.moves_played(), 0);
        for c in 0..COLS {
            assert_eq!(g.height(c), 0);
            for r in 0..ROWS {
                assert_eq!(g.at(c, r), None);
            }
        }
    }

    #[test]
    fn drop_lands_on_bottom_then_stacks() {
        let mut g = Game::new();
        let o0 = g.drop(3).unwrap();
        assert_eq!((o0.col, o0.row), (3, 0)); // first disc -> bottom row
        assert_eq!(o0.player, Player::Red);
        assert_eq!(g.side_to_move(), Player::Yellow); // turn flipped
        let o1 = g.drop(3).unwrap();
        assert_eq!((o1.col, o1.row), (3, 1)); // stacked on top
        assert_eq!(o1.player, Player::Yellow);
        assert_eq!(g.height(3), 2);
        assert_eq!(g.at(3, 0), Some(Player::Red));
        assert_eq!(g.at(3, 1), Some(Player::Yellow));
    }

    #[test]
    fn turn_strictly_alternates() {
        let mut g = Game::new();
        let seq = [0, 1, 2, 3, 4, 5, 6];
        let mut expect = Player::Red;
        for &c in &seq {
            assert_eq!(g.side_to_move(), expect);
            let o = g.drop(c).unwrap();
            assert_eq!(o.player, expect);
            expect = expect.opposite();
        }
    }

    // ── win directions ─────────────────────────────────────────────────────────

    #[test]
    fn horizontal_win() {
        // Red fills cols 0-3 on the bottom row; Yellow stacks harmlessly on col 6.
        // R0 Y6 R1 Y6 R2 Y6 R3#
        let mut g = Game::new();
        for &(c, terminal) in &[(0, false), (6, false), (1, false), (6, false), (2, false), (6, false)] {
            let o = g.drop(c).unwrap();
            assert_eq!(o.status.is_terminal(), terminal);
        }
        let win = g.drop(3).unwrap();
        assert_eq!(win.status, GameStatus::Win { winner: Player::Red });
        assert_eq!(g.status(), GameStatus::Win { winner: Player::Red });
        assert_eq!(
            win.winning_line,
            vec![(0, 0), (1, 0), (2, 0), (3, 0)]
        );
    }

    #[test]
    fn vertical_win() {
        // Red drops four times into col 2; Yellow answers in col 5 each time.
        // R2 Y5 R2 Y5 R2 Y5 R2#
        let g_pre = play(&[2, 5, 2, 5, 2, 5]);
        assert_eq!(g_pre.status(), GameStatus::Ongoing);
        let mut g = g_pre;
        let win = g.drop(2).unwrap();
        assert_eq!(win.status, GameStatus::Win { winner: Player::Red });
        assert_eq!(win.winning_line, vec![(2, 0), (2, 1), (2, 2), (2, 3)]);
    }

    #[test]
    fn diagonal_up_right_win() {
        // Build the "/" diagonal (0,0)-(1,1)-(2,2)-(3,3) for Red.
        // Staircase construction; Yellow's discs form the supporting steps.
        // Red: (0,0)(1,1)(2,2)(3,3).
        // sequence (col by col):
        //  R0            -> (0,0)
        //  Y1            -> (1,0)
        //  R1            -> (1,1)
        //  Y2            -> (2,0)
        //  R3 (parking)  -> (3,0)   [Red, but not on the diagonal]
        //  Y2            -> (2,1)
        //  R2            -> (2,2)
        //  Y3 (park)     -> (3,1)
        //  R6 (park)     -> (6,0)
        //  Y3 (park)     -> (3,2)
        //  R3            -> (3,3)#  completes (0,0)(1,1)(2,2)(3,3)
        let mut g = Game::new();
        for &c in &[0, 1, 1, 2, 3, 2, 2, 3, 6, 3] {
            let o = g.drop(c).unwrap();
            assert!(!o.status.is_terminal(), "premature end at col {c}\n{}", g.board_str());
        }
        let win = g.drop(3).unwrap();
        assert_eq!(win.player, Player::Red);
        assert_eq!(win.status, GameStatus::Win { winner: Player::Red });
        assert_eq!(win.winning_line, vec![(0, 0), (1, 1), (2, 2), (3, 3)]);
    }

    #[test]
    fn diagonal_down_right_win() {
        // Build the "\" diagonal (0,3)-(1,2)-(2,1)-(3,0) for Red.
        //  R3            -> (3,0)   on the diagonal
        //  Y2            -> (2,0)
        //  R2            -> (2,1)   on the diagonal
        //  Y1            -> (1,0)
        //  R6 (park)     -> (6,0)
        //  Y1            -> (1,1)
        //  R1            -> (1,2)   on the diagonal
        //  Y0            -> (0,0)
        //  Y... ensure col 0 has 3 supports before Red's (0,3)
        //  R6 (park)     -> (6,1)
        //  Y0            -> (0,1)
        //  R6 (park)     -> (6,2)
        //  Y0            -> (0,2)
        //  R0            -> (0,3)#  completes (0,3)(1,2)(2,1)(3,0)
        let mut g = Game::new();
        for &c in &[3, 2, 2, 1, 6, 1, 1, 0, 6, 0, 6, 0] {
            let o = g.drop(c).unwrap();
            assert!(!o.status.is_terminal(), "premature end at col {c}\n{}", g.board_str());
        }
        let win = g.drop(0).unwrap();
        assert_eq!(win.player, Player::Red);
        assert_eq!(win.status, GameStatus::Win { winner: Player::Red });
        assert_eq!(win.winning_line, vec![(0, 3), (1, 2), (2, 1), (3, 0)]);
    }

    #[test]
    fn yellow_can_win_too() {
        // Yellow horizontal on row 0: Red parks on col 6.
        // R6 Y0 R6 Y1 R6 Y2 R5 Y3#
        let mut g = Game::new();
        for &c in &[6, 0, 6, 1, 6, 2, 5] {
            assert!(!g.drop(c).unwrap().status.is_terminal());
        }
        let win = g.drop(3).unwrap();
        assert_eq!(win.status, GameStatus::Win { winner: Player::Yellow });
        assert_eq!(win.winning_line, vec![(0, 0), (1, 0), (2, 0), (3, 0)]);
    }

    // ── draw ────────────────────────────────────────────────────────────────────

    #[test]
    fn full_board_draw() {
        // A concrete, verified drop order that fills all 42 cells with NO
        // four-in-a-row in any direction. Columns are filled (bottom->top) one at
        // a time; with Red moving first the strict turn alternation lays down the
        // classic striped layout:
        //   YYYRYYY   (row 5, top)
        //   RRRYRRR
        //   YYYRYYY
        //   RRRYRRR
        //   YYYRYYY
        //   RRRYRRR   (row 0, bottom)
        // No horizontal/vertical/diagonal run of 4 same color exists. Asserting
        // "no win at any step" along the way also proves no premature terminal.
        const DRAW_ORDER: [usize; COLS * ROWS] = [
            0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 2, 2, 4, 3, 3, 3, 3, 3,
            3, 4, 4, 4, 4, 4, 5, 5, 5, 5, 5, 5, 6, 6, 6, 6, 6, 6,
        ];
        let mut g = Game::new();
        for (i, &c) in DRAW_ORDER.iter().enumerate() {
            let o = g.drop(c).unwrap();
            if i + 1 < COLS * ROWS {
                assert_eq!(o.status, GameStatus::Ongoing, "premature end at move {} col {c}\n{}", i + 1, g.board_str());
            }
        }
        assert_eq!(g.moves_played(), COLS * ROWS);
        assert_eq!(g.status(), GameStatus::Draw);
        assert!(g.legal_moves().is_empty());
        // every column is full
        for c in 0..COLS {
            assert_eq!(g.height(c), ROWS);
            assert_eq!(g.drop(c), Err(MoveError::GameOver)); // terminal beats full
        }
    }

    // ── illegal moves / rejection ────────────────────────────────────────────────

    #[test]
    fn drop_out_of_range_rejected() {
        let mut g = Game::new();
        assert_eq!(g.drop(7), Err(MoveError::OutOfRange));
        assert_eq!(g.drop(99), Err(MoveError::OutOfRange));
        // a rejected drop must not consume the turn or place anything
        assert_eq!(g.side_to_move(), Player::Red);
        assert_eq!(g.moves_played(), 0);
        assert!(g.drop(0).is_ok());
    }

    #[test]
    fn drop_into_full_column_rejected() {
        let mut g = Game::new();
        // alternately fill col 0 to the brim (6 discs) without anyone winning
        // elsewhere — interleave a harmless col-1 so colors don't matter here:
        // We just want col 0 full. R0 fills rows it lands on; both colors stack.
        for _ in 0..ROWS {
            g.drop(0).unwrap();
        }
        assert_eq!(g.height(0), ROWS);
        assert!(!g.can_drop(0));
        assert!(!g.legal_moves().contains(&0));
        assert_eq!(g.drop(0), Err(MoveError::ColumnFull));
        // other columns still work
        assert!(g.drop(1).is_ok());
    }

    #[test]
    fn no_drops_after_game_over() {
        // vertical win for Red, then every further drop is GameOver
        let mut g = play(&[2, 5, 2, 5, 2, 5]);
        assert_eq!(g.drop(2).unwrap().status, GameStatus::Win { winner: Player::Red });
        assert_eq!(g.drop(0), Err(MoveError::GameOver));
        assert_eq!(g.drop(4), Err(MoveError::GameOver));
        assert!(g.legal_moves().is_empty());
        // side to move freezes at the winner-relative state; status stays terminal
        assert_eq!(g.status(), GameStatus::Win { winner: Player::Red });
    }

    // ── scripted full games ──────────────────────────────────────────────────────

    #[test]
    fn scripted_short_game_red_vertical() {
        // A complete, minimal real game: Red wins by a vertical stack in col 0
        // while Yellow builds col 1. 7 moves total.
        let mut g = Game::new();
        let script = [
            (0, Player::Red, GameStatus::Ongoing),
            (1, Player::Yellow, GameStatus::Ongoing),
            (0, Player::Red, GameStatus::Ongoing),
            (1, Player::Yellow, GameStatus::Ongoing),
            (0, Player::Red, GameStatus::Ongoing),
            (1, Player::Yellow, GameStatus::Ongoing),
            (0, Player::Red, GameStatus::Win { winner: Player::Red }),
        ];
        for &(col, who, expect) in &script {
            assert_eq!(g.side_to_move(), who);
            let o = g.drop(col).unwrap();
            assert_eq!(o.player, who);
            assert_eq!(o.status, expect, "after dropping col {col}\n{}", g.board_str());
        }
        assert_eq!(g.status(), GameStatus::Win { winner: Player::Red });
    }

    #[test]
    fn scripted_short_game_yellow_horizontal() {
        // Yellow wins horizontally on row 1 (NOT the bottom row) — exercises the
        // gravity/height bookkeeping for an above-floor win. The row-0 supports are
        // a mix of both colors so Red never connects four first. This 12-move
        // sequence is machine-verified non-terminal through move 11; the 12th
        // (Yellow into col 0 -> (0,1)) completes the row-1 run (0,1)(1,1)(2,1)(3,1).
        let mut g = Game::new();
        for &c in &[3, 2, 5, 1, 0, 3, 6, 1, 5, 2, 5] {
            let o = g.drop(c).unwrap();
            assert!(!o.status.is_terminal(), "premature end at col {c}\n{}", g.board_str());
        }
        assert_eq!(g.side_to_move(), Player::Yellow);
        let win = g.drop(0).unwrap();
        assert_eq!(win.player, Player::Yellow);
        assert_eq!(win.status, GameStatus::Win { winner: Player::Yellow });
        assert_eq!(win.winning_line, vec![(0, 1), (1, 1), (2, 1), (3, 1)]);
    }

    // ── wire / JSON payload ──────────────────────────────────────────────────────

    #[test]
    fn status_wire_encoding() {
        assert_eq!(GameStatus::Ongoing.wire(), "ongoing");
        assert_eq!(GameStatus::Win { winner: Player::Red }.wire(), "win:red");
        assert_eq!(GameStatus::Win { winner: Player::Yellow }.wire(), "win:yellow");
        assert_eq!(GameStatus::Draw.wire(), "draw");
        assert!(GameStatus::Ongoing.is_terminal() == false);
        assert!(GameStatus::Draw.is_terminal());
        assert!(GameStatus::Win { winner: Player::Red }.is_terminal());
    }

    #[test]
    fn world_payload_json_initial() {
        let g = Game::new();
        let j = g.to_json(None);
        assert!(j.contains("\"type\":\"connect_four.state\""), "{j}");
        assert!(j.contains("\"toMove\":\"red\""), "{j}");
        assert!(j.contains("\"status\":\"ongoing\""), "{j}");
        assert!(j.contains("\"lastCol\":-1"), "{j}");
        // empty board: 6 rows of 7 dots joined by '/'
        assert!(j.contains("\"board\":\"......./......./......./......./......./.......\""), "{j}");
    }

    #[test]
    fn world_payload_json_after_drop_and_win() {
        let mut g = Game::new();
        let o = g.drop(3).unwrap();
        let j = g.to_json(Some(&o));
        assert!(j.contains("\"lastPlayer\":\"red\""), "{j}");
        assert!(j.contains("\"lastCol\":3"), "{j}");
        assert!(j.contains("\"lastRow\":0"), "{j}");
        assert!(j.contains("\"toMove\":\"yellow\""), "{j}");
        assert!(j.contains("\"status\":\"ongoing\""), "{j}");

        // now drive a Red vertical win in col 0 and check the winning-line payload
        let mut g = Game::new();
        for &c in &[0, 1, 0, 1, 0, 1] {
            g.drop(c).unwrap();
        }
        let win = g.drop(0).unwrap();
        let j = g.to_json(Some(&win));
        assert!(j.contains("\"status\":\"win:red\""), "{j}");
        assert!(j.contains("\"winningLine\":[[0,0],[0,1],[0,2],[0,3]]"), "{j}");
        // toMove freezes at the winner once terminal
        assert!(j.contains("\"toMove\":\"red\""), "{j}");
    }

    #[test]
    fn drop_outcome_move_json() {
        let mut g = Game::new();
        let o = g.drop(4).unwrap();
        let j = o.to_json();
        assert!(j.contains("\"type\":\"connect_four.move\""), "{j}");
        assert!(j.contains("\"player\":\"red\""), "{j}");
        assert!(j.contains("\"col\":4"), "{j}");
        assert!(j.contains("\"row\":0"), "{j}");
        assert!(j.contains("\"status\":\"ongoing\""), "{j}");
        assert!(j.contains("\"winningLine\":[]"), "{j}");
    }

    #[test]
    fn board_str_orientation_top_to_bottom() {
        let mut g = Game::new();
        g.drop(0).unwrap(); // Red bottom-left
        g.drop(6).unwrap(); // Yellow bottom-right
        let b = g.board_str();
        // 6 segments separated by '/', top row first
        let rows: Vec<&str> = b.split('/').collect();
        assert_eq!(rows.len(), ROWS);
        assert_eq!(rows[ROWS - 1], "R.....Y"); // bottom row last
        assert_eq!(rows[0], "......."); // top row empty
    }
}
