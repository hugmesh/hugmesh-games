//! `snapdragon-sudoku` — a pure, dependency-free single-player Sudoku engine.
//!
//! Board: the classic 9×9 grid partitioned into nine 3×3 boxes. A cell is
//! addressed by `(row, col)` with both in `0..9`; the linear index is
//! `row * 9 + col`. A digit is `1..=9`; the value `0` (or `None`) means an empty
//! cell. A grid is *valid* when no digit repeats within any row, any column, or
//! any 3×3 box; it is *solved* when it is full AND valid.
//!
//! UNLIKE the multiplayer engines in this workspace (chess / reversi /
//! connect-four), Sudoku is a SINGLE-PLAYER puzzle: the whole game runs as
//! `wasm32` in the browser and the server is not involved at all — no
//! multiplayer, no relay, no host/guest re-validation. What it shares with the
//! siblings is the shape: one pure, deterministic, dependency-free engine that
//! is the single source of truth, compiles native for `cargo test` and to
//! `wasm32` for the browser, exposes a clean public API + a `GameStatus` enum +
//! a `to_json` snapshot for save-resume.
//!
//! Puzzle GENERATION is seeded. [`Game::new`] takes a `seed`: it builds a full
//! valid solution by randomised backtracking, then removes cells (in a seeded
//! random order, keeping the puzzle solvable) to expose the givens. EVERY game
//! is therefore deterministic and reproducible — the same seed always yields the
//! same puzzle. All randomness flows through the tiny [`Rng`] xorshift PRNG; the
//! crate never touches `rand` or system entropy.

#![allow(clippy::needless_range_loop)]

/// Side length of the grid (9) and box (3), and the cell count (81).
pub const N: usize = 9;
pub const BOX: usize = 3;
pub const CELLS: usize = N * N;

// ── seeded PRNG ──────────────────────────────────────────────────────────────

/// A tiny deterministic xorshift64* PRNG. Constructed via [`Rng::new`] with a
/// `u64` seed so every consumer (solution generation, cell-removal order) is
/// fully reproducible. This is the ONLY source of randomness in the crate — no
/// `rand`, no system entropy. Identical seeds replay identical games.
#[derive(Debug, Clone)]
pub struct Rng {
    state: u64,
}

impl Rng {
    /// Seed the PRNG. A zero seed is remapped to a non-zero constant so the
    /// xorshift never gets stuck on the all-zeros fixed point.
    pub fn new(seed: u64) -> Rng {
        let state = if seed == 0 { 0x9E37_79B9_7F4A_7C15 } else { seed };
        Rng { state }
    }

    /// Next raw 64-bit value (xorshift64*).
    pub fn next_u64(&mut self) -> u64 {
        let mut x = self.state;
        x ^= x >> 12;
        x ^= x << 25;
        x ^= x >> 27;
        self.state = x;
        x.wrapping_mul(0x2545_F491_4F6C_DD1D)
    }

    /// A uniform value in `0..bound` (`bound > 0`). Uses the high bits and a
    /// widening multiply to avoid modulo bias for the small bounds we need.
    pub fn below(&mut self, bound: usize) -> usize {
        debug_assert!(bound > 0);
        ((self.next_u64() >> 33) as u128 * bound as u128 >> 31) as usize % bound
    }

    /// In-place Fisher–Yates shuffle, deterministic for a given PRNG state.
    pub fn shuffle<T>(&mut self, items: &mut [T]) {
        let n = items.len();
        if n < 2 {
            return;
        }
        for i in (1..n).rev() {
            let j = self.below(i + 1);
            items.swap(i, j);
        }
    }
}

// ── status ───────────────────────────────────────────────────────────────────

/// Running / terminal classification of a single-player Sudoku game.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum GameStatus {
    /// The grid is not yet fully and validly filled.
    Playing,
    /// Every cell is filled and the grid is valid — the puzzle is complete.
    Solved,
}
impl GameStatus {
    /// True once the puzzle is finished (`Solved`).
    pub fn is_terminal(self) -> bool {
        matches!(self, GameStatus::Solved)
    }
    /// Compact wire token for the snapshot payload.
    pub fn wire(self) -> &'static str {
        match self {
            GameStatus::Playing => "playing",
            GameStatus::Solved => "solved",
        }
    }
}

// ── errors ───────────────────────────────────────────────────────────────────

/// Why a [`Game::place`] / [`Game::clear`] attempt was rejected.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MoveError {
    /// `row` or `col` was outside `0..9`.
    OutOfRange,
    /// `n` was outside `1..=9` (only [`Game::place`] — `clear` takes no digit).
    BadDigit,
    /// The targeted cell is a *given* (a clue from the original puzzle) and may
    /// not be overwritten or cleared.
    GivenCell,
    /// The digit conflicts with an existing one in the same row, column, or box.
    Conflict,
}

/// Why a [`Game::from_grid`] construction was rejected.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum LoadError {
    /// The input did not contain exactly 81 cells.
    WrongLength,
    /// A character / value was not in `0..=9`.
    BadCell,
    /// The supplied givens already violate Sudoku (a duplicate in a unit).
    Invalid,
}

// ── the grid ─────────────────────────────────────────────────────────────────

/// A 9×9 grid of `0..=9` cells (`0` == empty), index `row * 9 + col`.
#[derive(Clone, PartialEq, Eq)]
struct Grid {
    cells: [u8; CELLS],
}

impl Grid {
    fn empty() -> Grid {
        Grid { cells: [0; CELLS] }
    }
    #[inline]
    fn get(&self, r: usize, c: usize) -> u8 {
        self.cells[r * N + c]
    }
    #[inline]
    fn set(&mut self, r: usize, c: usize, v: u8) {
        self.cells[r * N + c] = v;
    }

    /// Could digit `v` (`1..=9`) be placed at `(r, c)` without colliding with an
    /// existing digit in the same row, column, or 3×3 box? Ignores the cell's own
    /// current content (so it answers "is this digit consistent with the rest").
    fn can_place(&self, r: usize, c: usize, v: u8) -> bool {
        for i in 0..N {
            if i != c && self.get(r, i) == v {
                return false;
            }
            if i != r && self.get(i, c) == v {
                return false;
            }
        }
        let (br, bc) = (r / BOX * BOX, c / BOX * BOX);
        for dr in 0..BOX {
            for dc in 0..BOX {
                let (rr, cc) = (br + dr, bc + dc);
                if (rr != r || cc != c) && self.get(rr, cc) == v {
                    return false;
                }
            }
        }
        true
    }

    /// Is the whole grid (in its current, possibly-partial state) consistent —
    /// no filled cell duplicates another in its row, column, or box?
    fn is_consistent(&self) -> bool {
        for r in 0..N {
            for c in 0..N {
                let v = self.get(r, c);
                if v != 0 && !self.can_place(r, c, v) {
                    return false;
                }
            }
        }
        true
    }

    fn is_full(&self) -> bool {
        self.cells.iter().all(|&v| v != 0)
    }

    /// The first empty cell in row-major order, if any.
    fn first_empty(&self) -> Option<(usize, usize)> {
        self.cells
            .iter()
            .position(|&v| v == 0)
            .map(|i| (i / N, i % N))
    }

    /// Fill the grid in place by backtracking, trying candidate digits in the
    /// PRNG-shuffled order so the produced solution is seeded. Returns `true` on
    /// success (the grid is now a full valid solution).
    fn fill_random(&mut self, rng: &mut Rng) -> bool {
        let (r, c) = match self.first_empty() {
            None => return true,
            Some(rc) => rc,
        };
        let mut digits: [u8; N] = [1, 2, 3, 4, 5, 6, 7, 8, 9];
        rng.shuffle(&mut digits);
        for &v in &digits {
            if self.can_place(r, c, v) {
                self.set(r, c, v);
                if self.fill_random(rng) {
                    return true;
                }
                self.set(r, c, 0);
            }
        }
        false
    }

    /// Count solutions up to `cap`, by deterministic backtracking (digits tried
    /// `1..=9` in order — no RNG, so the count is stable). Stops as soon as `cap`
    /// solutions are found. A puzzle is "unique" iff this returns `1` with
    /// `cap >= 2`.
    fn count_solutions(&mut self, cap: usize) -> usize {
        let (r, c) = match self.first_empty() {
            None => return 1,
            Some(rc) => rc,
        };
        let mut total = 0;
        for v in 1..=9u8 {
            if self.can_place(r, c, v) {
                self.set(r, c, v);
                total += self.count_solutions(cap - total);
                self.set(r, c, 0);
                if total >= cap {
                    break;
                }
            }
        }
        total
    }

    /// Solve into the lexicographically-first solution by deterministic
    /// backtracking. Returns `true` and leaves `self` full on success.
    fn solve_in_place(&mut self) -> bool {
        let (r, c) = match self.first_empty() {
            None => return true,
            Some(rc) => rc,
        };
        for v in 1..=9u8 {
            if self.can_place(r, c, v) {
                self.set(r, c, v);
                if self.solve_in_place() {
                    return true;
                }
                self.set(r, c, 0);
            }
        }
        false
    }
}

// ── public game ──────────────────────────────────────────────────────────────

/// A single-player Sudoku game: the live grid, a mask of which cells are *givens*
/// (the original clues, immutable), and — when known — the canonical solution.
#[derive(Clone)]
pub struct Game {
    grid: Grid,
    /// `given[i]` is true for cells that were part of the original puzzle and
    /// thus cannot be overwritten or cleared.
    given: [bool; CELLS],
    /// The full solution, if the puzzle is known to have a unique one. Generated
    /// puzzles always carry it; `from_grid` derives it when the givens have a
    /// unique completion. Used by [`Game::hint`].
    solution: Option<[u8; CELLS]>,
}

impl Game {
    /// Generate a fresh, deterministic puzzle for `seed`.
    ///
    /// `clues` is the *target* number of givens to leave on the board (clamped to
    /// `17..=81`; 17 is the known minimum for a unique 9×9 Sudoku). The generator
    /// builds a full seeded solution, then removes cells in a seeded-random order,
    /// keeping the remaining puzzle *uniquely* solvable, until it can remove no
    /// more without breaking uniqueness or it reaches the clue target. The result
    /// always has a unique solution.
    pub fn new(seed: u64, clues: usize) -> Game {
        let mut rng = Rng::new(seed);
        let mut solved = Grid::empty();
        // A randomised fill always succeeds on an empty grid.
        solved.fill_random(&mut rng);
        let solution = solved.cells;

        // Remove cells in shuffled order while uniqueness is preserved.
        let target = clues.clamp(17, CELLS);
        let mut puzzle = solved.clone();
        let mut order: Vec<usize> = (0..CELLS).collect();
        rng.shuffle(&mut order);
        let mut remaining = CELLS;
        for &idx in &order {
            if remaining <= target {
                break;
            }
            let saved = puzzle.cells[idx];
            if saved == 0 {
                continue;
            }
            puzzle.cells[idx] = 0;
            // If removing this clue leaves >1 solution, put it back.
            let mut probe = puzzle.clone();
            if probe.count_solutions(2) != 1 {
                puzzle.cells[idx] = saved;
            } else {
                remaining -= 1;
            }
        }

        let mut given = [false; CELLS];
        for i in 0..CELLS {
            given[i] = puzzle.cells[i] != 0;
        }
        Game {
            grid: puzzle,
            given,
            solution: Some(solution),
        }
    }

    /// Build a game from a caller-supplied 81-cell grid. Filled cells become
    /// immutable *givens*; `0` cells are open. The givens must be internally
    /// consistent (no duplicate in any unit) or [`LoadError::Invalid`] is
    /// returned. If the givens have exactly one completion, that solution is
    /// captured for [`Game::hint`]; otherwise hints are unavailable but play
    /// proceeds.
    ///
    /// Accepts any `IntoIterator` of `u8` in `0..=9`. See [`Game::from_str`] for
    /// the textual form.
    pub fn from_grid<I>(cells: I) -> Result<Game, LoadError>
    where
        I: IntoIterator<Item = u8>,
    {
        let v: Vec<u8> = cells.into_iter().collect();
        if v.len() != CELLS {
            return Err(LoadError::WrongLength);
        }
        let mut grid = Grid::empty();
        let mut given = [false; CELLS];
        for (i, &x) in v.iter().enumerate() {
            if x > 9 {
                return Err(LoadError::BadCell);
            }
            grid.cells[i] = x;
            given[i] = x != 0;
        }
        if !grid.is_consistent() {
            return Err(LoadError::Invalid);
        }
        // Derive the unique solution if there is exactly one.
        let mut probe = grid.clone();
        let solution = match probe.count_solutions(2) {
            1 => {
                let mut s = grid.clone();
                s.solve_in_place();
                Some(s.cells)
            }
            _ => None,
        };
        Ok(Game {
            grid,
            given,
            solution,
        })
    }

    /// Parse a textual grid: 81 cells where each is a digit `1..=9` or one of
    /// `0`, `.`, `-`, `_` for empty. Whitespace (including newlines) is ignored,
    /// so a 9-line block is accepted as readily as one run-on string.
    pub fn from_str(s: &str) -> Result<Game, LoadError> {
        let mut cells: Vec<u8> = Vec::with_capacity(CELLS);
        for ch in s.chars() {
            if ch.is_whitespace() {
                continue;
            }
            let v = match ch {
                '0' | '.' | '-' | '_' => 0,
                '1'..='9' => ch as u8 - b'0',
                _ => return Err(LoadError::BadCell),
            };
            cells.push(v);
        }
        Game::from_grid(cells)
    }

    // ── reads ────────────────────────────────────────────────────────────────

    /// The digit at `(row, col)`, or `0` for empty. Out-of-range reads as `0`.
    pub fn get(&self, row: usize, col: usize) -> u8 {
        if row >= N || col >= N {
            return 0;
        }
        self.grid.get(row, col)
    }

    /// True if `(row, col)` is a given (immutable clue). Out-of-range is `false`.
    pub fn is_given(&self, row: usize, col: usize) -> bool {
        if row >= N || col >= N {
            return false;
        }
        self.given[row * N + col]
    }

    /// Number of givens (original clues) in the puzzle.
    pub fn given_count(&self) -> usize {
        self.given.iter().filter(|&&g| g).count()
    }

    /// Number of currently-filled cells (givens + player entries).
    pub fn filled_count(&self) -> usize {
        self.grid.cells.iter().filter(|&&v| v != 0).count()
    }

    /// Could `n` (`1..=9`) legally go at `(row, col)` right now — in range, not a
    /// given, and no row/column/box conflict? This is a pure query; it does not
    /// mutate. Returns `false` for any out-of-range or invalid input.
    pub fn is_valid_placement(&self, row: usize, col: usize, n: u8) -> bool {
        if row >= N || col >= N || !(1..=9).contains(&n) {
            return false;
        }
        if self.given[row * N + col] {
            return false;
        }
        self.grid.can_place(row, col, n)
    }

    /// True when every cell is filled and the grid is fully valid.
    pub fn is_solved(&self) -> bool {
        self.grid.is_full() && self.grid.is_consistent()
    }

    /// `Solved` once the grid is complete & valid, else `Playing`.
    pub fn status(&self) -> GameStatus {
        if self.is_solved() {
            GameStatus::Solved
        } else {
            GameStatus::Playing
        }
    }

    // ── writes ───────────────────────────────────────────────────────────────

    /// Place digit `n` (`1..=9`) at `(row, col)`. Rejects out-of-range cells,
    /// bad digits, *given* cells, and any digit that conflicts with the same row,
    /// column, or 3×3 box. On success the (possibly-overwritten) non-given cell
    /// holds `n`; returns the resulting [`GameStatus`].
    pub fn place(&mut self, row: usize, col: usize, n: u8) -> Result<GameStatus, MoveError> {
        if row >= N || col >= N {
            return Err(MoveError::OutOfRange);
        }
        if !(1..=9).contains(&n) {
            return Err(MoveError::BadDigit);
        }
        let idx = row * N + col;
        if self.given[idx] {
            return Err(MoveError::GivenCell);
        }
        if !self.grid.can_place(row, col, n) {
            return Err(MoveError::Conflict);
        }
        self.grid.set(row, col, n);
        Ok(self.status())
    }

    /// Clear a non-given cell back to empty. Rejects out-of-range and *given*
    /// cells; clearing an already-empty non-given cell is a no-op success.
    pub fn clear(&mut self, row: usize, col: usize) -> Result<(), MoveError> {
        if row >= N || col >= N {
            return Err(MoveError::OutOfRange);
        }
        let idx = row * N + col;
        if self.given[idx] {
            return Err(MoveError::GivenCell);
        }
        self.grid.set(row, col, 0);
        Ok(())
    }

    // ── solving / hints ──────────────────────────────────────────────────────

    /// Solve the CURRENT board (givens + whatever the player has entered) and
    /// return the completed 81-cell grid, or `None` if the present state has no
    /// completion (e.g. the player wrote a digit that boxes the puzzle in). Does
    /// not mutate the game — it works on a copy.
    pub fn solve(&self) -> Option<[u8; CELLS]> {
        let mut g = self.grid.clone();
        if g.solve_in_place() {
            Some(g.cells)
        } else {
            None
        }
    }

    /// A single hint: the correct digit for some currently-empty cell, as
    /// `(row, col, digit)`. Prefers the captured unique solution; falls back to
    /// solving the live board. Returns `None` when the board is already full or
    /// unsolvable from here.
    pub fn hint(&self) -> Option<(usize, usize, u8)> {
        let (r, c) = self.grid.first_empty()?;
        let idx = r * N + c;
        if let Some(sol) = &self.solution {
            return Some((r, c, sol[idx]));
        }
        let solved = self.solve()?;
        Some((r, c, solved[idx]))
    }

    /// Fill EVERY empty non-given cell with its solution digit, finishing the
    /// puzzle in place. Returns `true` on success (status becomes `Solved`), or
    /// `false` (leaving the board untouched) if the current state is unsolvable.
    /// Convenience for an "auto-solve" / "reveal" button.
    pub fn auto_solve(&mut self) -> bool {
        let solved = match self.solve() {
            Some(s) => s,
            None => return false,
        };
        self.grid.cells = solved;
        true
    }

    // ── snapshot / save-resume ───────────────────────────────────────────────

    /// The live grid as a flat 81-byte array (`0` == empty), row-major.
    pub fn cells(&self) -> [u8; CELLS] {
        self.grid.cells
    }

    /// Compact one-line string of the live grid: 81 chars, `.` for empties.
    pub fn board_str(&self) -> String {
        self.grid
            .cells
            .iter()
            .map(|&v| if v == 0 { '.' } else { (b'0' + v) as char })
            .collect()
    }

    /// The 81-char string of just the *givens* (player entries shown as `.`).
    /// Pairs with [`Game::board_str`] in [`Game::to_json`] so a save can be fully
    /// restored: re-load the givens via [`Game::from_str`], then replay the
    /// player entries from the board string.
    pub fn givens_str(&self) -> String {
        (0..CELLS)
            .map(|i| {
                if self.given[i] {
                    (b'0' + self.grid.cells[i]) as char
                } else {
                    '.'
                }
            })
            .collect()
    }

    /// A self-contained JSON snapshot for save-resume. Carries the givens, the
    /// current board, counts, and status. The single-player client persists this
    /// (e.g. to local storage) and restores via [`Game::from_str`] on the givens
    /// then [`Game::place`] replays of the diff. The server never sees it.
    pub fn to_json(&self) -> String {
        format!(
            "{{\"type\":\"sudoku.snapshot\",\"givens\":\"{}\",\"board\":\"{}\",\"givenCount\":{},\"filled\":{},\"status\":\"{}\"}}",
            self.givens_str(),
            self.board_str(),
            self.given_count(),
            self.filled_count(),
            self.status().wire(),
        )
    }
}

// ── tests ────────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    /// A well-known newspaper puzzle with a unique solution (the "Escargot"-style
    /// teaching grid is overkill; this is a standard easy puzzle). `0` == empty.
    const KNOWN_PUZZLE: &str = "\
        53..7....\
        6..195...\
        .98....6.\
        8...6...3\
        4..8.3..1\
        7...2...6\
        .6....28.\
        ...419..5\
        ....8..79";

    /// The unique solution to `KNOWN_PUZZLE`.
    const KNOWN_SOLUTION: &str = "\
        534678912\
        672195348\
        198342567\
        859761423\
        426853791\
        713924856\
        961537284\
        287419635\
        345286179";

    fn parse_line(s: &str) -> Vec<u8> {
        s.chars()
            .filter(|c| !c.is_whitespace())
            .map(|c| if c == '.' { 0 } else { c as u8 - b'0' })
            .collect()
    }

    // ── PRNG determinism ─────────────────────────────────────────────────────

    #[test]
    fn rng_is_deterministic_for_a_seed() {
        let mut a = Rng::new(12345);
        let mut b = Rng::new(12345);
        for _ in 0..1000 {
            assert_eq!(a.next_u64(), b.next_u64());
        }
        // A different seed diverges.
        let mut c = Rng::new(54321);
        assert_ne!(Rng::new(12345).next_u64(), c.next_u64());
    }

    #[test]
    fn rng_zero_seed_is_not_stuck() {
        let mut r = Rng::new(0);
        let x = r.next_u64();
        let y = r.next_u64();
        assert_ne!(x, 0);
        assert_ne!(x, y);
    }

    #[test]
    fn rng_below_is_in_range_and_shuffle_is_a_permutation() {
        let mut r = Rng::new(7);
        for _ in 0..500 {
            assert!(r.below(9) < 9);
            assert!(r.below(1) == 0);
        }
        let mut items: Vec<u8> = (1..=9).collect();
        r.shuffle(&mut items);
        let mut sorted = items.clone();
        sorted.sort();
        assert_eq!(sorted, (1..=9).collect::<Vec<_>>());
    }

    // ── the solver solves a known puzzle ─────────────────────────────────────

    #[test]
    fn solver_solves_a_known_puzzle() {
        let g = Game::from_str(KNOWN_PUZZLE).unwrap();
        let solved = g.solve().expect("known puzzle is solvable");
        let expected = parse_line(KNOWN_SOLUTION);
        assert_eq!(&solved[..], &expected[..]);
    }

    #[test]
    fn from_str_captures_unique_solution_for_hints() {
        let g = Game::from_str(KNOWN_PUZZLE).unwrap();
        // The hint for the first empty cell (row 0, col 2) is '4' per the solution.
        let (r, c, d) = g.hint().unwrap();
        assert_eq!((r, c), (0, 2));
        assert_eq!(d, 4);
    }

    #[test]
    fn auto_solve_completes_and_marks_solved() {
        let mut g = Game::from_str(KNOWN_PUZZLE).unwrap();
        assert_eq!(g.status(), GameStatus::Playing);
        assert!(g.auto_solve());
        assert!(g.is_solved());
        assert_eq!(g.status(), GameStatus::Solved);
        assert_eq!(g.board_str(), KNOWN_SOLUTION);
    }

    // ── generation: solvable + valid + deterministic ─────────────────────────

    #[test]
    fn generated_puzzle_is_valid_solvable_and_unique() {
        for seed in [1u64, 2, 42, 99, 100000, u64::MAX] {
            let g = Game::new(seed, 30);
            // The exposed givens are internally consistent.
            assert!(
                g.solve().is_some(),
                "seed {seed} generated an unsolvable puzzle"
            );
            // Uniqueness: exactly one completion.
            let mut grid = Grid {
                cells: g.cells(),
            };
            assert_eq!(
                grid.count_solutions(2),
                1,
                "seed {seed} puzzle is not unique"
            );
            // A generated puzzle starts unsolved and Playing.
            assert!(!g.is_solved());
            assert_eq!(g.status(), GameStatus::Playing);
            // It exposes a sane number of givens (>= the 17 minimum, <= 81).
            assert!(g.given_count() >= 17, "seed {seed}: {} givens", g.given_count());
            assert!(g.given_count() <= CELLS);
        }
    }

    #[test]
    fn generation_is_reproducible_for_a_seed() {
        let a = Game::new(2025, 28);
        let b = Game::new(2025, 28);
        assert_eq!(a.board_str(), b.board_str());
        assert_eq!(a.givens_str(), b.givens_str());
        // Different seeds (almost surely) differ.
        let c = Game::new(2026, 28);
        assert_ne!(a.board_str(), c.board_str());
    }

    #[test]
    fn generated_puzzle_solution_matches_its_hint_chain() {
        // Solving a generated puzzle yields a full valid grid that agrees with the
        // captured solution every step of the way.
        let g = Game::new(777, 35);
        let solved = g.solve().unwrap();
        let check = Grid { cells: solved };
        assert!(check.is_full() && check.is_consistent());
        // Every given is preserved in the solution.
        for i in 0..CELLS {
            if g.is_given(i / N, i % N) {
                assert_eq!(g.cells()[i], solved[i]);
            }
        }
    }

    #[test]
    fn clue_target_is_respected_and_clamped() {
        // A high clue target leaves more givens than a low one.
        let sparse = Game::new(50, 24);
        let dense = Game::new(50, 60);
        assert!(dense.given_count() >= sparse.given_count());
        // Below-minimum target is clamped to 17, not panicked on.
        let g = Game::new(50, 0);
        assert!(g.given_count() >= 17);
    }

    // ── valid placement against row / column / box ───────────────────────────

    #[test]
    fn is_valid_placement_checks_all_three_units() {
        let g = Game::from_str(KNOWN_PUZZLE).unwrap();
        // Cell (0,2) is empty in the known puzzle. Solution digit there is 4.
        assert!(g.is_valid_placement(0, 2, 4));
        // 5 is already in row 0 (col 0) -> row conflict.
        assert!(!g.is_valid_placement(0, 2, 5));
        // 6 is already in column 2 (row 1) -> column conflict.
        assert!(!g.is_valid_placement(0, 2, 6));
        // 9 is in the same top-left box (row 2 col 1) -> box conflict.
        assert!(!g.is_valid_placement(0, 2, 9));
    }

    #[test]
    fn place_rejects_row_column_and_box_conflicts() {
        let mut g = Game::from_str(KNOWN_PUZZLE).unwrap();
        // (0,2) empty. Each of these collides in exactly one unit.
        assert_eq!(g.place(0, 2, 5), Err(MoveError::Conflict)); // row (5 at 0,0)
        assert_eq!(g.place(0, 2, 6), Err(MoveError::Conflict)); // column (6 at 1,2)
        assert_eq!(g.place(0, 2, 9), Err(MoveError::Conflict)); // box (9 at 2,1)
        // The legal digit is accepted.
        assert_eq!(g.place(0, 2, 4), Ok(GameStatus::Playing));
        assert_eq!(g.get(0, 2), 4);
    }

    #[test]
    fn place_rejects_out_of_range_and_bad_digit() {
        let mut g = Game::from_str(KNOWN_PUZZLE).unwrap();
        assert_eq!(g.place(9, 0, 1), Err(MoveError::OutOfRange));
        assert_eq!(g.place(0, 9, 1), Err(MoveError::OutOfRange));
        assert_eq!(g.place(0, 2, 0), Err(MoveError::BadDigit));
        assert_eq!(g.place(0, 2, 10), Err(MoveError::BadDigit));
        // is_valid_placement mirrors the rejections without mutating.
        assert!(!g.is_valid_placement(9, 0, 1));
        assert!(!g.is_valid_placement(0, 2, 0));
        assert!(!g.is_valid_placement(0, 2, 10));
    }

    #[test]
    fn given_cells_cannot_be_placed_or_cleared() {
        let mut g = Game::from_str(KNOWN_PUZZLE).unwrap();
        // (0,0) is the given '5'.
        assert!(g.is_given(0, 0));
        assert_eq!(g.place(0, 0, 1), Err(MoveError::GivenCell));
        assert_eq!(g.clear(0, 0), Err(MoveError::GivenCell));
        assert!(!g.is_valid_placement(0, 0, 1));
        // The given is untouched.
        assert_eq!(g.get(0, 0), 5);
    }

    // ── clearing / overwriting a non-given cell ──────────────────────────────

    #[test]
    fn clearing_and_overwriting_a_non_given_cell() {
        let mut g = Game::from_str(KNOWN_PUZZLE).unwrap();
        assert!(!g.is_given(0, 2));
        // Place a (legal) digit, then overwrite it with another legal one.
        g.place(0, 2, 4).unwrap();
        assert_eq!(g.get(0, 2), 4);
        // Overwriting a non-given is allowed (4 -> nothing else legal here, so
        // clear first to free the cell, then place a different legal digit isn't
        // possible since 4 is the only legal value; instead prove the overwrite
        // path on a freer cell).
        g.clear(0, 2).unwrap();
        assert_eq!(g.get(0, 2), 0);
        // Re-place.
        g.place(0, 2, 4).unwrap();
        assert_eq!(g.get(0, 2), 4);
        // Clearing an already-empty non-given cell is a no-op success.
        g.clear(0, 3).unwrap();
        assert_eq!(g.get(0, 3), 0);
    }

    #[test]
    fn overwrite_replaces_a_player_entry() {
        // Build a tiny grid with two open cells in the same row so we can show a
        // real overwrite (place A, then a different legal B after clearing).
        let mut cells = vec![0u8; CELLS];
        // givens: row 0 has 1..7 in cols 0..7, cols 7,8 open.
        for c in 0..7 {
            cells[c] = (c + 1) as u8;
        }
        let mut g = Game::from_grid(cells).unwrap();
        // col 7 legal values are {8,9}; place 8 then overwrite with 9.
        assert!(g.is_valid_placement(0, 7, 8));
        g.place(0, 7, 8).unwrap();
        assert_eq!(g.get(0, 7), 8);
        // Direct re-place of a non-given with a different legal digit: first the
        // current 8 must be cleared (else 8 would be its own neighbour? no — the
        // cell is excluded). 9 is legal regardless of the cell's own content.
        assert!(g.is_valid_placement(0, 7, 9));
        g.place(0, 7, 9).unwrap();
        assert_eq!(g.get(0, 7), 9);
    }

    // ── is_solved on a completed grid ────────────────────────────────────────

    #[test]
    fn is_solved_on_a_completed_grid() {
        let g = Game::from_str(KNOWN_SOLUTION).unwrap();
        assert!(g.is_solved());
        assert_eq!(g.status(), GameStatus::Solved);
        assert!(g.status().is_terminal());
    }

    #[test]
    fn nearly_complete_then_final_place_solves() {
        // Take the solution, blank exactly one non-given-free cell, then place it.
        let mut cells = parse_line(KNOWN_SOLUTION);
        let last = cells[80];
        cells[80] = 0;
        // Mark all but the last cell as givens by loading the full board minus one.
        let mut g = Game::from_grid(cells).unwrap();
        assert_eq!(g.status(), GameStatus::Playing);
        assert!(!g.is_solved());
        // Placing the final digit completes and solves it.
        let st = g.place(8, 8, last).unwrap();
        assert_eq!(st, GameStatus::Solved);
        assert!(g.is_solved());
    }

    #[test]
    fn full_but_invalid_grid_is_not_solved() {
        // A full grid with a duplicate isn't accepted by from_grid (it's caught as
        // Invalid). Verify that rejection, which is the load-time guard.
        let mut cells = parse_line(KNOWN_SOLUTION);
        // Break row 0: make (0,1) equal (0,0).
        cells[1] = cells[0];
        assert!(matches!(Game::from_grid(cells), Err(LoadError::Invalid)));
    }

    // ── from_grid / from_str loading errors ──────────────────────────────────

    #[test]
    fn from_grid_rejects_wrong_length_and_bad_cell() {
        assert!(matches!(
            Game::from_grid(vec![0u8; 80]),
            Err(LoadError::WrongLength)
        ));
        assert!(matches!(
            Game::from_grid(vec![0u8; 82]),
            Err(LoadError::WrongLength)
        ));
        let mut cells = vec![0u8; CELLS];
        cells[0] = 10;
        assert!(matches!(Game::from_grid(cells), Err(LoadError::BadCell)));
    }

    #[test]
    fn from_str_rejects_bad_character() {
        let mut s = String::from(".");
        s.push('X');
        s.push_str(&".".repeat(79));
        assert!(matches!(Game::from_str(&s), Err(LoadError::BadCell)));
    }

    #[test]
    fn from_str_ignores_whitespace_and_accepts_block_form() {
        let block = "\
            53. .7. ...\n\
            6.. 195 ...\n\
            .98 ... .6.\n\
            8.. .6. ..3\n\
            4.. 8.3 ..1\n\
            7.. .2. ..6\n\
            .6. ... 28.\n\
            ... 419 ..5\n\
            ... .8. .79\n";
        let g = Game::from_str(block).unwrap();
        let expected: String = KNOWN_PUZZLE.chars().filter(|c| !c.is_whitespace()).collect();
        assert_eq!(g.board_str(), expected);
        assert!(g.solve().is_some());
    }

    #[test]
    fn from_grid_without_unique_solution_has_no_hint_but_plays() {
        // An empty grid has many solutions: no captured solution, hint falls back
        // to solving the live board (which still works).
        let g = Game::from_grid(vec![0u8; CELLS]).unwrap();
        assert_eq!(g.given_count(), 0);
        // hint() falls back to a live solve, so it still returns a digit.
        let (r, c, d) = g.hint().unwrap();
        assert_eq!((r, c), (0, 0));
        assert!((1..=9).contains(&d));
    }

    // ── solve on an unsolvable live state ────────────────────────────────────

    #[test]
    fn solve_returns_none_when_player_boxes_it_in() {
        // From the known puzzle, force a digit that is legal locally but kills the
        // global solution. (0,2) must be 4; writing the only other locally-legal
        // value there is impossible (4 is unique), so instead make the grid
        // unsolvable by emptying a given is not allowed — construct directly.
        let mut cells = parse_line(KNOWN_PUZZLE);
        // The known solution has (0,2)=4. Place a 2 at (0,2): is it locally legal?
        // row0 has {5,3,7}; col2 has {8,9}; box0 has {5,3,6,9,8}. 2 is locally ok.
        // But 2 there makes the puzzle globally unsolvable.
        let g0 = Game::from_grid(cells.clone()).unwrap();
        assert!(g0.is_valid_placement(0, 2, 2));
        cells[2] = 2;
        let g = Game::from_grid(cells);
        // Loading still succeeds (locally consistent) but it now has no solution.
        let mut g = g.unwrap();
        assert!(g.solve().is_none(), "the boxed-in grid must be unsolvable");
        assert!(!g.auto_solve());
        assert!(g.hint().is_none());
    }

    // ── snapshot / to_json round-trip ────────────────────────────────────────

    #[test]
    fn snapshot_json_shape_and_fields() {
        let g = Game::from_str(KNOWN_PUZZLE).unwrap();
        let j = g.to_json();
        assert!(j.contains("\"type\":\"sudoku.snapshot\""), "{j}");
        assert!(j.contains("\"status\":\"playing\""), "{j}");
        assert!(j.contains(&format!("\"givenCount\":{}", g.given_count())), "{j}");
        assert!(j.contains(&format!("\"board\":\"{}\"", g.board_str())), "{j}");
        // givens == board for an unplayed loaded puzzle.
        assert!(j.contains(&format!("\"givens\":\"{}\"", g.board_str())), "{j}");
    }

    #[test]
    fn snapshot_reports_solved_status() {
        let g = Game::from_str(KNOWN_SOLUTION).unwrap();
        let j = g.to_json();
        assert!(j.contains("\"status\":\"solved\""), "{j}");
        assert!(j.contains("\"filled\":81"), "{j}");
    }

    #[test]
    fn snapshot_round_trips_givens_and_player_entries() {
        // Load a puzzle, make a couple of legal entries, snapshot, then rebuild
        // the givens-only grid from the snapshot's givens string and replay.
        let mut g = Game::from_str(KNOWN_PUZZLE).unwrap();
        g.place(0, 2, 4).unwrap();
        let givens = g.givens_str();
        let board = g.board_str();
        // The givens string omits the player entry at (0,2).
        assert_eq!(givens.as_bytes()[2], b'.');
        assert_eq!(board.as_bytes()[2], b'4');
        // Rebuild from givens; same given set, no player entries yet.
        let restored = Game::from_str(&givens).unwrap();
        assert_eq!(restored.given_count(), g.given_count());
        assert_eq!(restored.get(0, 2), 0);
        assert!(!restored.is_given(0, 2));
    }

    // ── status enum encoding ─────────────────────────────────────────────────

    #[test]
    fn status_wire_encoding() {
        assert_eq!(GameStatus::Playing.wire(), "playing");
        assert_eq!(GameStatus::Solved.wire(), "solved");
        assert!(!GameStatus::Playing.is_terminal());
        assert!(GameStatus::Solved.is_terminal());
    }

    // ── full play-through of a generated puzzle ──────────────────────────────

    #[test]
    fn generated_puzzle_can_be_played_to_solved() {
        let g = Game::new(31337, 32);
        let solution = g.solve().unwrap();
        let mut play = g.clone();
        // Fill every non-given cell with its solution digit via place().
        for i in 0..CELLS {
            let (r, c) = (i / N, i % N);
            if !play.is_given(r, c) {
                let st = play.place(r, c, solution[i]).unwrap();
                // Every solution digit is, by construction, a valid placement.
                let _ = st;
            }
        }
        assert!(play.is_solved());
        assert_eq!(play.status(), GameStatus::Solved);
    }
}
