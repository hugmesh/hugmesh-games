//! `hugmesh-minesweeper` — a pure, dependency-free, DETERMINISTIC Minesweeper engine.
//!
//! Board geometry: a `W × H` grid. A cell is addressed by `(x, y)` with `x` in
//! `0..width` (column, left→right) and `y` in `0..height` (row, top→bottom). The
//! flat index of a cell is `y * width + x`.
//!
//! Rules. A configurable number of mines is hidden on the board. The player
//! REVEALS cells: revealing a mine loses the game; revealing a safe cell shows
//! its adjacency count (the number of mines among its up-to-8 neighbours). A
//! revealed cell with a `0` adjacency count auto-opens its neighbours via a
//! FLOOD FILL (which cascades through the connected zero-region and stops at its
//! numbered border). The player may FLAG / UNFLAG suspected mines; a flagged
//! cell cannot be revealed (the flag is a safety interlock). The game is WON the
//! moment every non-mine cell has been revealed, and LOST the moment a mine is
//! revealed.
//!
//! First-click safety. Mines are NOT placed until the first `reveal`, and the
//! first revealed cell (plus, where the mine budget allows, its 8 neighbours) is
//! guaranteed mine-free — so the opening click never loses and ideally opens a
//! region. This mirrors the modern Minesweeper convention.
//!
//! Determinism. ALL randomness (mine placement) comes from a small SEEDED
//! xorshift PRNG built via [`Rng::new`]. The same `(width, height, mines, seed)`
//! and the same first-click coordinate ALWAYS produce the same board, so games
//! are reproducible and tests assert on fixed seeded outcomes. There is no use of
//! the `rand` crate or any system entropy.
//!
//! Platform. This is a SINGLE-PLAYER puzzle: it compiles to `wasm32` and runs
//! entirely in the browser. The server runs none of this logic — it is not
//! involved at all. The engine is nonetheless the single source of truth for the
//! rules, compiled native for `cargo test` and to wasm for the game.

#![allow(clippy::needless_range_loop)]

// ── seeded PRNG ──────────────────────────────────────────────────────────────
//
// A tiny xorshift64* generator. Constructed via `Rng::new(seed)`; identical
// seeds yield identical streams on every platform (native + wasm), which is what
// makes mine placement — and therefore every test — fully reproducible. We do
// NOT pull in the `rand` crate or touch any system entropy.

/// A small deterministic xorshift64* pseudo-random generator. Build one with
/// [`Rng::new`]; the same seed always yields the same sequence.
#[derive(Debug, Clone)]
pub struct Rng {
    state: u64,
}

impl Rng {
    /// Construct a generator from a seed. A zero seed is folded to a non-zero
    /// constant (xorshift cannot escape the all-zero state), so `new(0)` is still
    /// a valid, deterministic stream rather than a stuck generator.
    pub fn new(seed: u64) -> Rng {
        Rng {
            state: if seed == 0 { 0x9E3779B97F4A7C15 } else { seed },
        }
    }

    /// The next 64-bit value (xorshift64*). Advances the state.
    pub fn next_u64(&mut self) -> u64 {
        let mut x = self.state;
        x ^= x >> 12;
        x ^= x << 25;
        x ^= x >> 27;
        self.state = x;
        x.wrapping_mul(0x2545F4914F6CDD1D)
    }

    /// A uniformly-distributed value in `0..bound` (Lemire-style rejection to
    /// avoid modulo bias). `bound` must be non-zero.
    pub fn below(&mut self, bound: usize) -> usize {
        debug_assert!(bound > 0, "below(0) has no valid output");
        let bound = bound as u64;
        // Rejection sampling: discard the small unfair tail so the result is an
        // unbiased uniform draw, keeping placement statistically clean.
        let zone = u64::MAX - (u64::MAX % bound);
        loop {
            let r = self.next_u64();
            if r < zone {
                return (r % bound) as usize;
            }
        }
    }
}

// ── cell + status ────────────────────────────────────────────────────────────

/// The player-visible state of a single cell.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CellView {
    /// Not yet revealed and not flagged.
    Hidden,
    /// Hidden, but the player has planted a flag (cannot be revealed until
    /// unflagged).
    Flagged,
    /// Revealed safe cell; `0..=8` is the count of adjacent mines.
    Revealed(u8),
    /// A revealed mine — only ever produced once the game is Lost.
    Mine,
}

/// Running / terminal classification of a game.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum GameStatus {
    /// The game is in progress (this also covers the pre-first-click state).
    Playing,
    /// Every non-mine cell has been revealed.
    Won,
    /// A mine was revealed.
    Lost,
}
impl GameStatus {
    pub fn is_terminal(self) -> bool {
        !matches!(self, GameStatus::Playing)
    }
    /// Compact wire encoding for a status payload.
    pub fn wire(self) -> &'static str {
        match self {
            GameStatus::Playing => "playing",
            GameStatus::Won => "won",
            GameStatus::Lost => "lost",
        }
    }
}

/// The result of a `reveal` attempt that the engine accepted (or rejected).
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Outcome {
    /// The reveal opened one or more safe cells; the game continues. `opened` is
    /// the list of `(x, y)` cells newly revealed by this action (a single cell,
    /// or the whole flood-fill region for a zero-cell). The game status is
    /// `Playing`.
    Revealed { opened: Vec<(usize, usize)> },
    /// The reveal opened the final safe cell(s); the game is now `Won`. `opened`
    /// is the cells revealed by this action.
    Won { opened: Vec<(usize, usize)> },
    /// A mine was revealed; the game is now `Lost`. `(x, y)` is the detonated
    /// mine.
    Lost { mine: (usize, usize) },
    /// The action did nothing (the cell was already revealed, or was flagged so
    /// the reveal was blocked) and the game is unchanged.
    NoChange,
}

/// Why a `reveal` / `flag` was rejected outright (as opposed to a benign
/// `Outcome::NoChange`).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ActionError {
    /// The coordinate `(x, y)` is outside the board.
    OutOfBounds,
    /// The game is already over (Won or Lost); no further actions are accepted.
    GameOver,
}

// ── the game ─────────────────────────────────────────────────────────────────

/// A single Minesweeper game. Construct with [`Game::new`]; drive it with
/// [`Game::reveal`] and [`Game::flag`]; observe it with [`Game::status`],
/// [`Game::cell`], and [`Game::snapshot`] / [`Game::to_json`].
#[derive(Clone)]
pub struct Game {
    width: usize,
    height: usize,
    mine_count: usize,
    seed: u64,
    /// `true` where a mine sits. Empty until the first reveal (first-click-safe
    /// deferred placement).
    mines: Vec<bool>,
    /// Adjacency count `0..=8` per cell. Computed alongside mine placement.
    adjacency: Vec<u8>,
    /// Per-cell revealed flag.
    revealed: Vec<bool>,
    /// Per-cell player flag.
    flagged: Vec<bool>,
    /// Set once mines have been placed (i.e. after the first reveal).
    placed: bool,
    status: GameStatus,
    /// Count of revealed non-mine cells (used to detect the win).
    safe_revealed: usize,
    /// The detonated mine once Lost, for rendering.
    exploded: Option<usize>,
}

impl Game {
    /// Create a fresh game on a `width × height` board with `mines` hidden mines
    /// and a `seed` for deterministic placement.
    ///
    /// Mines are clamped to the legal range. Because the first click is always
    /// safe, the maximum number of mines is `width*height - 1` (at least the
    /// first-clicked cell must be free); requesting more is clamped down. A
    /// dimension of `0`, or `0` mines, yields a degenerate-but-valid board (with
    /// `0` mines the first reveal immediately wins).
    ///
    /// Mines are NOT placed here — placement is deferred to the first
    /// [`reveal`](Game::reveal) so that the opening click can be guaranteed safe.
    pub fn new(width: usize, height: usize, mines: usize, seed: u64) -> Game {
        let cells = width.saturating_mul(height);
        // Reserve the first-clicked cell as guaranteed-safe: cap at cells-1.
        let max_mines = cells.saturating_sub(1);
        let mine_count = mines.min(max_mines);
        Game {
            width,
            height,
            mine_count,
            seed,
            mines: vec![false; cells],
            adjacency: vec![0; cells],
            revealed: vec![false; cells],
            flagged: vec![false; cells],
            placed: false,
            status: GameStatus::Playing,
            safe_revealed: 0,
            exploded: None,
        }
    }

    // ── dimensions / accessors ───────────────────────────────────────────────

    pub fn width(&self) -> usize {
        self.width
    }
    pub fn height(&self) -> usize {
        self.height
    }
    /// The (clamped) number of mines this game places.
    pub fn mine_count(&self) -> usize {
        self.mine_count
    }
    pub fn seed(&self) -> u64 {
        self.seed
    }
    pub fn status(&self) -> GameStatus {
        self.status
    }
    /// `true` once the first reveal has placed the mines.
    pub fn is_started(&self) -> bool {
        self.placed
    }

    /// Total non-mine cells on the board (the win threshold).
    pub fn safe_cells(&self) -> usize {
        self.width.saturating_mul(self.height) - self.mine_count
    }

    /// Number of mines NOT currently flagged (a player HUD figure). Before
    /// placement this is just the mine budget.
    pub fn mines_remaining(&self) -> isize {
        self.mine_count as isize - self.flag_count() as isize
    }

    /// How many flags the player has currently planted.
    pub fn flag_count(&self) -> usize {
        self.flagged.iter().filter(|&&f| f).count()
    }

    fn idx(&self, x: usize, y: usize) -> usize {
        y * self.width + x
    }
    fn in_bounds(&self, x: usize, y: usize) -> bool {
        x < self.width && y < self.height
    }

    /// The player-visible state of cell `(x, y)`. Out-of-bounds reads return
    /// `CellView::Hidden`. While the game is `Playing` or `Won`, mines never leak
    /// (they render as `Hidden`/`Flagged`); once `Lost`, every mine renders as
    /// `CellView::Mine` so the board can show the explosion.
    pub fn cell(&self, x: usize, y: usize) -> CellView {
        if !self.in_bounds(x, y) {
            return CellView::Hidden;
        }
        let i = self.idx(x, y);
        if self.revealed[i] {
            if self.mines[i] {
                CellView::Mine
            } else {
                CellView::Revealed(self.adjacency[i])
            }
        } else if self.status == GameStatus::Lost && self.mines[i] {
            // On loss the whole minefield is exposed.
            CellView::Mine
        } else if self.flagged[i] {
            CellView::Flagged
        } else {
            CellView::Hidden
        }
    }

    /// `true` if cell `(x, y)` has been revealed. False out of bounds.
    pub fn is_revealed(&self, x: usize, y: usize) -> bool {
        self.in_bounds(x, y) && self.revealed[self.idx(x, y)]
    }
    /// `true` if cell `(x, y)` is currently flagged. False out of bounds.
    pub fn is_flagged(&self, x: usize, y: usize) -> bool {
        self.in_bounds(x, y) && self.flagged[self.idx(x, y)]
    }
    /// `true` if cell `(x, y)` holds a mine. Always `false` before placement
    /// (the first reveal). Intended for tests / post-game rendering — it reads
    /// the ground truth regardless of reveal state.
    pub fn is_mine(&self, x: usize, y: usize) -> bool {
        self.in_bounds(x, y) && self.mines[self.idx(x, y)]
    }
    /// The adjacency count (`0..=8`) of cell `(x, y)`. `0` before placement /
    /// out of bounds.
    pub fn adjacent_mines(&self, x: usize, y: usize) -> u8 {
        if self.in_bounds(x, y) {
            self.adjacency[self.idx(x, y)]
        } else {
            0
        }
    }

    // ── neighbours ───────────────────────────────────────────────────────────

    /// The in-bounds neighbours (up to 8) of `(x, y)`, including diagonals.
    fn neighbours(&self, x: usize, y: usize) -> Vec<(usize, usize)> {
        let mut out = Vec::with_capacity(8);
        let x = x as isize;
        let y = y as isize;
        for dy in -1..=1isize {
            for dx in -1..=1isize {
                if dx == 0 && dy == 0 {
                    continue;
                }
                let nx = x + dx;
                let ny = y + dy;
                if nx >= 0 && nx < self.width as isize && ny >= 0 && ny < self.height as isize {
                    out.push((nx as usize, ny as usize));
                }
            }
        }
        out
    }

    // ── mine placement (first-click-safe, seeded) ────────────────────────────

    /// Place all mines deterministically from the seed, keeping `(safe_x, safe_y)`
    /// AND (budget permitting) its neighbours mine-free, then compute every
    /// cell's adjacency count. Called exactly once, on the first reveal.
    fn place_mines(&mut self, safe_x: usize, safe_y: usize) {
        let cells = self.width * self.height;
        let safe_i = self.idx(safe_x, safe_y);

        // Build the candidate pool: every cell index that may hold a mine. We try
        // to forbid the clicked cell AND its neighbours (so the opening reveal
        // flood-fills), but only if enough room remains for all the mines —
        // otherwise we forbid just the clicked cell (the minimum first-click
        // guarantee). This never silently drops a mine.
        let mut forbidden = vec![false; cells];
        forbidden[safe_i] = true;
        for (nx, ny) in self.neighbours(safe_x, safe_y) {
            forbidden[self.idx(nx, ny)] = true;
        }
        let free_with_ring = forbidden.iter().filter(|&&f| !f).count();
        if free_with_ring < self.mine_count {
            // Not enough room to also spare the ring; spare only the clicked cell.
            forbidden = vec![false; cells];
            forbidden[safe_i] = true;
        }

        let mut pool: Vec<usize> = (0..cells).filter(|&i| !forbidden[i]).collect();

        // Partial Fisher–Yates: draw `mine_count` distinct indices from the pool
        // using the seeded PRNG. Deterministic for a fixed (seed, first click).
        let mut rng = Rng::new(self.seed);
        let take = self.mine_count.min(pool.len());
        for k in 0..take {
            let j = k + rng.below(pool.len() - k);
            pool.swap(k, j);
            self.mines[pool[k]] = true;
        }

        // Compute adjacency counts now that mines are fixed.
        for y in 0..self.height {
            for x in 0..self.width {
                let i = self.idx(x, y);
                if self.mines[i] {
                    continue;
                }
                let count = self
                    .neighbours(x, y)
                    .iter()
                    .filter(|&&(nx, ny)| self.mines[self.idx(nx, ny)])
                    .count() as u8;
                self.adjacency[i] = count;
            }
        }

        self.placed = true;
    }

    // ── reveal ───────────────────────────────────────────────────────────────

    /// Reveal cell `(x, y)`.
    ///
    /// On the very first reveal this lazily places the (first-click-safe) mines.
    /// Revealing a mine loses the game; revealing a `0`-adjacency cell flood-fills
    /// the connected zero-region and its numbered border. Revealing the final
    /// safe cell wins.
    ///
    /// Rejections: out-of-bounds and acting on a finished game are
    /// [`ActionError`]s. Revealing an already-revealed cell, or a flagged cell
    /// (the flag is a safety interlock), is a benign [`Outcome::NoChange`].
    pub fn reveal(&mut self, x: usize, y: usize) -> Result<Outcome, ActionError> {
        if self.status.is_terminal() {
            return Err(ActionError::GameOver);
        }
        if !self.in_bounds(x, y) {
            return Err(ActionError::OutOfBounds);
        }

        // First reveal: place mines now, sparing this cell (and its ring).
        if !self.placed {
            self.place_mines(x, y);
        }

        let i = self.idx(x, y);
        if self.revealed[i] || self.flagged[i] {
            // Already open, or guarded by a flag — nothing to do.
            return Ok(Outcome::NoChange);
        }

        // Stepped on a mine → loss; expose it.
        if self.mines[i] {
            self.revealed[i] = true;
            self.exploded = Some(i);
            self.status = GameStatus::Lost;
            return Ok(Outcome::Lost { mine: (x, y) });
        }

        // Safe cell: reveal it, flood-filling if it (or a cascaded cell) is a 0.
        let opened = self.flood_reveal(x, y);

        if self.safe_revealed == self.safe_cells() {
            self.status = GameStatus::Won;
            Ok(Outcome::Won { opened })
        } else {
            Ok(Outcome::Revealed { opened })
        }
    }

    /// Reveal `(sx, sy)` and, for every revealed `0`-adjacency cell, cascade into
    /// its hidden neighbours (the classic flood fill). Flagged cells are treated
    /// as walls — the cascade does not blow through a flag. Returns every cell
    /// newly revealed by this call, in discovery order. Never steps on a mine
    /// (the seeded counts guarantee a `0` cell has no mine neighbours).
    fn flood_reveal(&mut self, sx: usize, sy: usize) -> Vec<(usize, usize)> {
        let mut opened = Vec::new();
        let mut stack = vec![(sx, sy)];
        while let Some((x, y)) = stack.pop() {
            let i = self.idx(x, y);
            if self.revealed[i] || self.flagged[i] {
                continue;
            }
            // A flood never reaches a mine: it only expands from 0-cells, whose
            // neighbours are by definition all safe. Guard anyway for safety.
            if self.mines[i] {
                continue;
            }
            self.revealed[i] = true;
            self.safe_revealed += 1;
            opened.push((x, y));
            if self.adjacency[i] == 0 {
                for (nx, ny) in self.neighbours(x, y) {
                    let ni = self.idx(nx, ny);
                    if !self.revealed[ni] && !self.flagged[ni] {
                        stack.push((nx, ny));
                    }
                }
            }
        }
        opened
    }

    // ── flag ─────────────────────────────────────────────────────────────────

    /// Toggle the flag on cell `(x, y)`; returns the new flag state (`true` =
    /// flagged). A revealed cell cannot be flagged (returns `Ok(false)` without
    /// changing it). Out-of-bounds and finished-game attempts are rejected.
    pub fn flag(&mut self, x: usize, y: usize) -> Result<bool, ActionError> {
        if self.status.is_terminal() {
            return Err(ActionError::GameOver);
        }
        if !self.in_bounds(x, y) {
            return Err(ActionError::OutOfBounds);
        }
        let i = self.idx(x, y);
        if self.revealed[i] {
            // Can't flag what's already open.
            return Ok(false);
        }
        self.flagged[i] = !self.flagged[i];
        Ok(self.flagged[i])
    }

    /// Explicitly set the flag on `(x, y)` to `on` (idempotent; unlike [`flag`]
    /// it does not toggle). Same rejection rules as [`flag`].
    pub fn set_flag(&mut self, x: usize, y: usize, on: bool) -> Result<bool, ActionError> {
        if self.status.is_terminal() {
            return Err(ActionError::GameOver);
        }
        if !self.in_bounds(x, y) {
            return Err(ActionError::OutOfBounds);
        }
        let i = self.idx(x, y);
        if self.revealed[i] {
            return Ok(false);
        }
        self.flagged[i] = on;
        Ok(on)
    }

    // ── snapshot / save-resume ───────────────────────────────────────────────

    /// A compact ASCII rendering of the CURRENT player view, one row per line:
    /// `#`=hidden, `F`=flagged, `.`=revealed-0, `1..8`=revealed count, `*`=mine
    /// (only shown when Lost). Used for debugging + the snapshot JSON.
    pub fn board_str(&self) -> String {
        let mut out = String::with_capacity(self.height * (self.width + 1));
        for y in 0..self.height {
            for x in 0..self.width {
                out.push(match self.cell(x, y) {
                    CellView::Hidden => '#',
                    CellView::Flagged => 'F',
                    CellView::Mine => '*',
                    CellView::Revealed(0) => '.',
                    CellView::Revealed(n) => (b'0' + n) as char,
                });
            }
            if y + 1 < self.height {
                out.push('\n');
            }
        }
        out
    }

    /// A full save-resume snapshot of the game. Captures enough to reconstruct
    /// the exact game with [`Game::from_snapshot`]: dimensions, mine count, seed,
    /// status, and the per-cell mine / revealed / flagged truth. (Adjacency is
    /// derived, so it is not stored.)
    pub fn snapshot(&self) -> Snapshot {
        Snapshot {
            width: self.width,
            height: self.height,
            mine_count: self.mine_count,
            seed: self.seed,
            placed: self.placed,
            status: self.status,
            mines: self.mines.clone(),
            revealed: self.revealed.clone(),
            flagged: self.flagged.clone(),
        }
    }

    /// Reconstruct a game from a [`Snapshot`] (e.g. one loaded from storage).
    /// Recomputes the derived adjacency + safe-revealed counters so the resumed
    /// game behaves identically to the saved one.
    pub fn from_snapshot(s: &Snapshot) -> Game {
        let cells = s.width.saturating_mul(s.height);
        let mut g = Game {
            width: s.width,
            height: s.height,
            mine_count: s.mine_count,
            seed: s.seed,
            mines: s.mines.clone(),
            adjacency: vec![0; cells],
            revealed: s.revealed.clone(),
            flagged: s.flagged.clone(),
            placed: s.placed,
            status: s.status,
            safe_revealed: 0,
            exploded: None,
        };
        if g.placed {
            for y in 0..g.height {
                for x in 0..g.width {
                    let i = g.idx(x, y);
                    if !g.mines[i] {
                        let c = g
                            .neighbours(x, y)
                            .iter()
                            .filter(|&&(nx, ny)| g.mines[g.idx(nx, ny)])
                            .count() as u8;
                        g.adjacency[i] = c;
                    }
                }
            }
        }
        g.safe_revealed = (0..cells).filter(|&i| g.revealed[i] && !g.mines[i]).count();
        if g.status == GameStatus::Lost {
            g.exploded = (0..cells).find(|&i| g.revealed[i] && g.mines[i]);
        }
        g
    }

    /// The save-resume JSON payload. Embeds the [`Snapshot`] fields plus the
    /// rendered board for convenience. The single-player client persists this to
    /// resume an in-progress puzzle; the server never parses it.
    pub fn to_json(&self) -> String {
        let bits = |v: &[bool]| -> String {
            v.iter().map(|&b| if b { '1' } else { '0' }).collect::<String>()
        };
        let board = self.board_str().replace('\n', "/");
        format!(
            "{{\"type\":\"minesweeper.snapshot\",\"width\":{},\"height\":{},\"mines\":{},\"seed\":{},\"started\":{},\"status\":\"{}\",\"flags\":{},\"safeRevealed\":{},\"safeCells\":{},\"mineMask\":\"{}\",\"revealedMask\":\"{}\",\"flaggedMask\":\"{}\",\"board\":\"{}\"}}",
            self.width,
            self.height,
            self.mine_count,
            self.seed,
            self.placed,
            self.status.wire(),
            self.flag_count(),
            self.safe_revealed,
            self.safe_cells(),
            bits(&self.mines),
            bits(&self.revealed),
            bits(&self.flagged),
            board,
        )
    }
}

/// A serialisable save-resume snapshot of a [`Game`]. Produced by
/// [`Game::snapshot`], restored by [`Game::from_snapshot`].
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Snapshot {
    pub width: usize,
    pub height: usize,
    pub mine_count: usize,
    pub seed: u64,
    pub placed: bool,
    pub status: GameStatus,
    pub mines: Vec<bool>,
    pub revealed: Vec<bool>,
    pub flagged: Vec<bool>,
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Count every mine on the board (post-placement).
    fn total_mines(g: &Game) -> usize {
        let mut n = 0;
        for y in 0..g.height() {
            for x in 0..g.width() {
                if g.is_mine(x, y) {
                    n += 1;
                }
            }
        }
        n
    }

    /// Brute-force recompute the adjacency of `(x, y)` straight off the ground
    /// truth, independent of the engine's stored counts — the oracle.
    fn brute_adjacency(g: &Game, x: usize, y: usize) -> u8 {
        let mut n = 0u8;
        let xi = x as isize;
        let yi = y as isize;
        for dy in -1..=1isize {
            for dx in -1..=1isize {
                if dx == 0 && dy == 0 {
                    continue;
                }
                let nx = xi + dx;
                let ny = yi + dy;
                if nx >= 0
                    && nx < g.width() as isize
                    && ny >= 0
                    && ny < g.height() as isize
                    && g.is_mine(nx as usize, ny as usize)
                {
                    n += 1;
                }
            }
        }
        n
    }

    // ── PRNG determinism ─────────────────────────────────────────────────────

    #[test]
    fn rng_is_deterministic_for_a_seed() {
        let mut a = Rng::new(12345);
        let mut b = Rng::new(12345);
        for _ in 0..1000 {
            assert_eq!(a.next_u64(), b.next_u64());
        }
        // Different seeds diverge.
        let mut c = Rng::new(12345);
        let mut d = Rng::new(54321);
        assert_ne!(c.next_u64(), d.next_u64());
    }

    #[test]
    fn rng_below_stays_in_range_and_is_seeded() {
        let mut r = Rng::new(7);
        for _ in 0..10_000 {
            let v = r.below(10);
            assert!(v < 10);
        }
        // zero seed is folded to a valid stream, not stuck at 0
        let mut z = Rng::new(0);
        let first = z.next_u64();
        assert_ne!(first, 0);
        assert_ne!(z.next_u64(), first);
    }

    // ── construction / dimensions ────────────────────────────────────────────

    #[test]
    fn fresh_game_state() {
        let g = Game::new(9, 9, 10, 42);
        assert_eq!(g.width(), 9);
        assert_eq!(g.height(), 9);
        assert_eq!(g.mine_count(), 10);
        assert_eq!(g.seed(), 42);
        assert_eq!(g.status(), GameStatus::Playing);
        assert!(!g.is_started(), "mines deferred until first reveal");
        assert_eq!(g.safe_cells(), 81 - 10);
        assert_eq!(g.flag_count(), 0);
        assert_eq!(g.mines_remaining(), 10);
        // every cell hidden, no mines placed yet
        for y in 0..9 {
            for x in 0..9 {
                assert_eq!(g.cell(x, y), CellView::Hidden);
                assert!(!g.is_mine(x, y));
            }
        }
    }

    #[test]
    fn mine_count_clamped_to_first_click_safe_maximum() {
        // 9 cells, ask for 100 mines: clamp to cells-1 == 8 (the first click is
        // always free).
        let g = Game::new(3, 3, 100, 1);
        assert_eq!(g.mine_count(), 8);
        assert_eq!(g.safe_cells(), 1);
    }

    // ── seeded mine placement ────────────────────────────────────────────────

    #[test]
    fn placement_is_deterministic_for_seed_and_first_click() {
        // Same seed + same first click => identical minefields.
        let mut g1 = Game::new(16, 16, 40, 0xC0FFEE);
        let mut g2 = Game::new(16, 16, 40, 0xC0FFEE);
        g1.reveal(0, 0).unwrap();
        g2.reveal(0, 0).unwrap();
        for y in 0..16 {
            for x in 0..16 {
                assert_eq!(g1.is_mine(x, y), g2.is_mine(x, y), "mismatch at {x},{y}");
            }
        }
        // The exact placed count matches the budget.
        assert_eq!(total_mines(&g1), 40);
    }

    #[test]
    fn different_seeds_produce_different_boards() {
        let mut a = Game::new(16, 16, 40, 1);
        let mut b = Game::new(16, 16, 40, 2);
        a.reveal(8, 8).unwrap();
        b.reveal(8, 8).unwrap();
        let mut diff = false;
        for y in 0..16 {
            for x in 0..16 {
                if a.is_mine(x, y) != b.is_mine(x, y) {
                    diff = true;
                }
            }
        }
        assert!(diff, "two different seeds gave identical boards");
    }

    #[test]
    fn exact_mine_count_is_placed() {
        for seed in [0u64, 1, 2, 99, 123456789] {
            let mut g = Game::new(12, 10, 23, seed);
            g.reveal(5, 5).unwrap();
            assert_eq!(total_mines(&g), 23, "seed {seed} placed wrong count");
        }
    }

    // ── adjacency counts ─────────────────────────────────────────────────────

    #[test]
    fn adjacency_counts_match_brute_force_oracle() {
        // Across several seeds, every cell's stored adjacency equals the
        // independently-recomputed neighbour mine count.
        for seed in [3u64, 17, 1000, 0xABCDEF] {
            let mut g = Game::new(14, 11, 30, seed);
            g.reveal(0, 0).unwrap();
            for y in 0..g.height() {
                for x in 0..g.width() {
                    if !g.is_mine(x, y) {
                        assert_eq!(
                            g.adjacent_mines(x, y),
                            brute_adjacency(&g, x, y),
                            "adjacency mismatch at {x},{y} seed {seed}"
                        );
                    }
                }
            }
        }
    }

    #[test]
    fn adjacency_corner_edge_and_interior() {
        // A hand-built board: place a single mine and check the ring of 1s and
        // the corner counts. We force a known board by loading a snapshot.
        let w = 5;
        let h = 5;
        let mut mines = vec![false; w * h];
        let mine_xy = (2, 2);
        mines[mine_xy.1 * w + mine_xy.0] = true;
        let snap = Snapshot {
            width: w,
            height: h,
            mine_count: 1,
            seed: 0,
            placed: true,
            status: GameStatus::Playing,
            mines,
            revealed: vec![false; w * h],
            flagged: vec![false; w * h],
        };
        let g = Game::from_snapshot(&snap);
        // the 8 cells around (2,2) are all 1
        for (x, y) in [(1, 1), (2, 1), (3, 1), (1, 2), (3, 2), (1, 3), (2, 3), (3, 3)] {
            assert_eq!(g.adjacent_mines(x, y), 1, "ring cell {x},{y}");
        }
        // far corners are 0
        for (x, y) in [(0, 0), (4, 0), (0, 4), (4, 4)] {
            assert_eq!(g.adjacent_mines(x, y), 0, "corner {x},{y}");
        }
    }

    #[test]
    fn cell_with_eight_neighbouring_mines_reads_eight() {
        // Centre cell ringed by 8 mines.
        let w = 3;
        let h = 3;
        let mut mines = vec![true; w * h];
        mines[1 * w + 1] = false; // centre safe
        let snap = Snapshot {
            width: w,
            height: h,
            mine_count: 8,
            seed: 0,
            placed: true,
            status: GameStatus::Playing,
            mines,
            revealed: vec![false; w * h],
            flagged: vec![false; w * h],
        };
        let g = Game::from_snapshot(&snap);
        assert_eq!(g.adjacent_mines(1, 1), 8);
    }

    // ── flood-fill reveal ────────────────────────────────────────────────────

    #[test]
    fn flood_fill_opens_connected_zero_region() {
        // A board with one mine in a corner: revealing the opposite corner must
        // flood-fill the whole zero-region (everything except the 1-ring around
        // the mine stays connected through 0s). With one mine, the safe area is
        // fully connected, so the first reveal opens ALL safe cells (and wins).
        let w = 6;
        let h = 6;
        let mut mines = vec![false; w * h];
        mines[0] = true; // mine at (0,0)
        let snap = Snapshot {
            width: w,
            height: h,
            mine_count: 1,
            seed: 0,
            placed: true,
            status: GameStatus::Playing,
            mines,
            revealed: vec![false; w * h],
            flagged: vec![false; w * h],
        };
        let mut g = Game::from_snapshot(&snap);
        // reveal the far corner (5,5), a 0-cell far from the mine
        let out = g.reveal(5, 5).unwrap();
        match out {
            Outcome::Won { opened } => {
                // exactly the 35 safe cells opened
                assert_eq!(opened.len(), w * h - 1);
            }
            other => panic!("expected Won via flood, got {other:?}"),
        }
        // every non-mine cell is now revealed; the mine cell is not
        for y in 0..h {
            for x in 0..w {
                if (x, y) == (0, 0) {
                    assert!(!g.is_revealed(x, y));
                } else {
                    assert!(g.is_revealed(x, y), "{x},{y} should be flooded open");
                }
            }
        }
    }

    #[test]
    fn flood_fill_stops_at_numbered_border_and_is_connected() {
        // A 9-cell strip with mines at BOTH ends and a third mine in the middle
        // that splits the strip into TWO disjoint safe regions:
        //   [M . . M . . . . M]   mines at x=0, x=3, x=8
        // Region A is x=1,2 (both bordering the x=0/x=3 mines, so both are
        // numbered — there is no 0-cell here). Region B is x=4..7 with a 0-cell
        // at x=5,6. Revealing the 0-cell at x=5 must flood ONLY region B (x4..7)
        // and STOP at the x=3 / x=8 mine border — it must not leak into region A,
        // and must not win (region A stays hidden).
        let w = 9;
        let h = 1; // a 1-D strip makes "regions" crisp
        let mut mines = vec![false; w * h];
        mines[0] = true;
        mines[3] = true;
        mines[8] = true;
        let snap = Snapshot {
            width: w,
            height: h,
            mine_count: 3,
            seed: 0,
            placed: true,
            status: GameStatus::Playing,
            mines,
            revealed: vec![false; w * h],
            flagged: vec![false; w * h],
        };
        let mut g = Game::from_snapshot(&snap);
        // adjacency: x1=1, x2=1, x4=1, x5=0, x6=0, x7=1
        assert_eq!(g.adjacent_mines(4, 0), 1);
        assert_eq!(g.adjacent_mines(5, 0), 0);
        assert_eq!(g.adjacent_mines(6, 0), 0);
        assert_eq!(g.adjacent_mines(7, 0), 1);
        // reveal the 0-cell (5,0): floods 5,6 (the 0s) plus the bordering 1s at
        // x=4 and x=7, stopping before the x=3 and x=8 mines.
        let out = g.reveal(5, 0).unwrap();
        let opened = match out {
            Outcome::Revealed { opened } => opened,
            other => panic!("expected Revealed (region A still hidden), got {other:?}"),
        };
        let mut xs: Vec<usize> = opened.iter().map(|&(x, _)| x).collect();
        xs.sort_unstable();
        assert_eq!(xs, vec![4, 5, 6, 7], "flood region B + its numbered border only");
        // it did NOT leak into region A across the x=3 mine wall
        assert!(!g.is_revealed(1, 0), "region A must stay hidden");
        assert!(!g.is_revealed(2, 0), "region A must stay hidden");
        // mines never revealed
        assert!(!g.is_revealed(0, 0));
        assert!(!g.is_revealed(3, 0));
        assert!(!g.is_revealed(8, 0));
        // not won — region A's two safe cells remain
        assert_eq!(g.status(), GameStatus::Playing);
    }

    #[test]
    fn flood_fill_does_not_cross_a_flag() {
        // A flagged cell acts as a wall: the cascade won't reveal through it.
        let w = 5;
        let h = 1;
        let mines = vec![false; w * h]; // no mines -> everything is 0
        let mut snap = Snapshot {
            width: w,
            height: h,
            mine_count: 0,
            seed: 0,
            placed: true,
            status: GameStatus::Playing,
            mines,
            revealed: vec![false; w * h],
            flagged: vec![false; w * h],
        };
        snap.flagged[2] = true; // flag the middle cell
        let mut g = Game::from_snapshot(&snap);
        // reveal (0,0): floods right but stops at the flagged (2,0); (3,0),(4,0)
        // stay hidden because the only path to them runs through the flag.
        let out = g.reveal(0, 0).unwrap();
        let opened = match out {
            Outcome::Revealed { opened } => opened,
            other => panic!("expected Revealed (flag blocks the win), got {other:?}"),
        };
        let mut xs: Vec<usize> = opened.iter().map(|&(x, _)| x).collect();
        xs.sort_unstable();
        assert_eq!(xs, vec![0, 1], "flood blocked at the flag");
        assert!(!g.is_revealed(2, 0));
        assert!(!g.is_revealed(3, 0));
    }

    // ── win condition ────────────────────────────────────────────────────────

    #[test]
    fn revealing_all_safe_cells_wins() {
        // 3x3 with mines on the whole bottom row except we keep it simple: mines
        // at (0,0) and (2,0); 7 safe cells. Reveal each safe cell; the last
        // reveal flips to Won.
        let w = 3;
        let h = 3;
        let mut mines = vec![false; w * h];
        mines[0] = true; // (0,0)
        mines[2] = true; // (2,0)
        let snap = Snapshot {
            width: w,
            height: h,
            mine_count: 2,
            seed: 0,
            placed: true,
            status: GameStatus::Playing,
            mines,
            revealed: vec![false; w * h],
            flagged: vec![false; w * h],
        };
        let mut g = Game::from_snapshot(&snap);
        let safe: Vec<(usize, usize)> = (0..h)
            .flat_map(|y| (0..w).map(move |x| (x, y)))
            .filter(|&(x, y)| !g.is_mine(x, y))
            .collect();
        for &(x, y) in safe.iter() {
            // A flood-fill on a 0-cell can win EARLY (it opens many cells at
            // once), after which further reveals would be rejected with GameOver.
            // Only act while the game is still live; some reveals are also a
            // benign NoChange because a prior flood already opened that cell.
            if g.status() != GameStatus::Playing {
                break;
            }
            let out = g.reveal(x, y).unwrap();
            // we never step on a mine here — these are all the safe cells
            assert!(!matches!(out, Outcome::Lost { .. }));
            assert_ne!(g.status(), GameStatus::Lost);
        }
        assert_eq!(g.status(), GameStatus::Won);
        // a win never exposes the board as a loss; mines stay hidden
        assert_eq!(g.cell(0, 0), CellView::Hidden);
    }

    #[test]
    fn zero_mine_board_wins_on_first_reveal() {
        let mut g = Game::new(5, 5, 0, 7);
        let out = g.reveal(2, 2).unwrap();
        assert!(matches!(out, Outcome::Won { .. }));
        assert_eq!(g.status(), GameStatus::Won);
        // the whole board flooded open
        for y in 0..5 {
            for x in 0..5 {
                assert!(g.is_revealed(x, y));
            }
        }
    }

    // ── lose condition ───────────────────────────────────────────────────────

    #[test]
    fn revealing_a_mine_loses() {
        let mut g = Game::new(9, 9, 10, 42);
        g.reveal(4, 4).unwrap(); // first click, safe, places mines
        // find an actual mine and step on it
        let mut stepped = false;
        'outer: for y in 0..9 {
            for x in 0..9 {
                if g.is_mine(x, y) {
                    let out = g.reveal(x, y).unwrap();
                    assert_eq!(out, Outcome::Lost { mine: (x, y) });
                    stepped = true;
                    break 'outer;
                }
            }
        }
        assert!(stepped, "expected at least one mine to exist");
        assert_eq!(g.status(), GameStatus::Lost);
        // on loss the whole minefield is exposed
        for y in 0..9 {
            for x in 0..9 {
                if g.is_mine(x, y) {
                    assert_eq!(g.cell(x, y), CellView::Mine, "mine {x},{y} should show");
                }
            }
        }
    }

    // ── first-click safety ───────────────────────────────────────────────────

    #[test]
    fn first_click_is_never_a_mine() {
        // Across many seeds and both an edge and an interior first click, the
        // first reveal never loses and the clicked cell is never a mine.
        for seed in 0..200u64 {
            for &(fx, fy) in &[(0usize, 0usize), (4, 4), (8, 8)] {
                let mut g = Game::new(9, 9, 10, seed);
                let out = g.reveal(fx, fy).unwrap();
                assert!(
                    !matches!(out, Outcome::Lost { .. }),
                    "first click lost at seed {seed} ({fx},{fy})"
                );
                assert!(!g.is_mine(fx, fy), "first click was a mine: seed {seed}");
            }
        }
    }

    #[test]
    fn first_click_neighbourhood_is_safe_when_budget_allows() {
        // With a modest mine budget the 3x3 around the first click is all safe,
        // so the opening reveal flood-fills (opens > 1 cell).
        for seed in 0..50u64 {
            let mut g = Game::new(16, 16, 40, seed);
            let out = g.reveal(8, 8).unwrap();
            // the clicked cell and its 8 neighbours are mine-free
            for (nx, ny) in [
                (7, 7), (8, 7), (9, 7),
                (7, 8), (9, 8),
                (7, 9), (8, 9), (9, 9),
            ] {
                assert!(!g.is_mine(nx, ny), "ring not safe at seed {seed}: {nx},{ny}");
            }
            // because the centre's neighbours are all safe, (8,8) is a 0 → flood
            let opened = match out {
                Outcome::Revealed { opened } | Outcome::Won { opened } => opened,
                other => panic!("seed {seed}: unexpected {other:?}"),
            };
            assert!(opened.len() > 1, "expected a flood at seed {seed}");
        }
    }

    #[test]
    fn dense_board_first_click_still_safe_even_without_ring() {
        // 3x3 asking for 8 mines (the max): only the clicked cell can be spared
        // (the ring can't), but the first click must still never be a mine.
        for seed in 0..50u64 {
            let mut g = Game::new(3, 3, 8, seed);
            let out = g.reveal(1, 1).unwrap();
            assert!(!g.is_mine(1, 1), "dense first click was a mine, seed {seed}");
            // 8 mines + 1 safe cell -> revealing the safe cell wins immediately
            assert!(matches!(out, Outcome::Won { .. }), "seed {seed}: {out:?}");
            assert_eq!(total_mines(&g), 8);
        }
    }

    // ── flag toggling ────────────────────────────────────────────────────────

    #[test]
    fn flag_toggles_and_blocks_reveal() {
        let mut g = Game::new(9, 9, 10, 5);
        assert_eq!(g.flag(3, 3), Ok(true));
        assert!(g.is_flagged(3, 3));
        assert_eq!(g.cell(3, 3), CellView::Flagged);
        assert_eq!(g.flag_count(), 1);
        assert_eq!(g.mines_remaining(), 9);
        // a flagged cell cannot be revealed
        let out = g.reveal(3, 3).unwrap();
        assert_eq!(out, Outcome::NoChange);
        assert!(!g.is_revealed(3, 3));
        // toggle back off
        assert_eq!(g.flag(3, 3), Ok(false));
        assert!(!g.is_flagged(3, 3));
        assert_eq!(g.flag_count(), 0);
    }

    #[test]
    fn set_flag_is_idempotent() {
        let mut g = Game::new(5, 5, 3, 1);
        assert_eq!(g.set_flag(2, 2, true), Ok(true));
        assert_eq!(g.set_flag(2, 2, true), Ok(true)); // no toggle
        assert!(g.is_flagged(2, 2));
        assert_eq!(g.set_flag(2, 2, false), Ok(false));
        assert!(!g.is_flagged(2, 2));
    }

    #[test]
    fn cannot_flag_a_revealed_cell() {
        // Build a board where a single NUMBERED (non-zero) cell can be revealed
        // without winning or flooding, so the game stays Playing and we can test
        // the "can't flag a revealed cell" rule. A 3x3 with a mine at (0,0): the
        // cell (1,1) is a numbered 1 (no flood), and 7 other safe cells remain
        // hidden, so revealing it leaves the game in progress.
        let w = 3;
        let h = 3;
        let mut mines = vec![false; w * h];
        mines[0] = true; // mine at (0,0)
        let snap = Snapshot {
            width: w,
            height: h,
            mine_count: 1,
            seed: 0,
            placed: true,
            status: GameStatus::Playing,
            mines,
            revealed: vec![false; w * h],
            flagged: vec![false; w * h],
        };
        let mut g = Game::from_snapshot(&snap);
        assert_eq!(g.adjacent_mines(1, 1), 1, "(1,1) is a numbered cell, no flood");
        let out = g.reveal(1, 1).unwrap();
        assert_eq!(out, Outcome::Revealed { opened: vec![(1, 1)] });
        assert_eq!(g.status(), GameStatus::Playing, "game still live");
        assert!(g.is_revealed(1, 1));
        assert_eq!(g.flag(1, 1), Ok(false), "flagging a revealed cell is a no-op");
        assert!(!g.is_flagged(1, 1));
    }

    // ── out-of-bounds + game-over rejection ──────────────────────────────────

    #[test]
    fn out_of_bounds_actions_are_rejected() {
        let mut g = Game::new(9, 9, 10, 1);
        assert_eq!(g.reveal(9, 0), Err(ActionError::OutOfBounds));
        assert_eq!(g.reveal(0, 9), Err(ActionError::OutOfBounds));
        assert_eq!(g.reveal(100, 100), Err(ActionError::OutOfBounds));
        assert_eq!(g.flag(9, 9), Err(ActionError::OutOfBounds));
        assert_eq!(g.set_flag(9, 9, true), Err(ActionError::OutOfBounds));
        // a rejected reveal must not have started the game
        assert!(!g.is_started());
    }

    #[test]
    fn actions_after_game_over_are_rejected() {
        // win a 0-mine board then assert all actions reject with GameOver
        let mut g = Game::new(4, 4, 0, 1);
        g.reveal(0, 0).unwrap();
        assert_eq!(g.status(), GameStatus::Won);
        assert_eq!(g.reveal(1, 1), Err(ActionError::GameOver));
        assert_eq!(g.flag(1, 1), Err(ActionError::GameOver));
        assert_eq!(g.set_flag(1, 1, true), Err(ActionError::GameOver));

        // lose a board then assert the same
        let mut g2 = Game::new(9, 9, 20, 3);
        g2.reveal(4, 4).unwrap();
        'outer: for y in 0..9 {
            for x in 0..9 {
                if g2.is_mine(x, y) {
                    g2.reveal(x, y).unwrap();
                    break 'outer;
                }
            }
        }
        assert_eq!(g2.status(), GameStatus::Lost);
        assert_eq!(g2.reveal(0, 0), Err(ActionError::GameOver));
        assert_eq!(g2.flag(0, 0), Err(ActionError::GameOver));
    }

    #[test]
    fn revealing_an_already_open_cell_is_a_noop() {
        let mut g = Game::new(9, 9, 10, 8);
        g.reveal(4, 4).unwrap();
        // whatever opened, re-revealing the clicked cell changes nothing
        if g.status() == GameStatus::Playing {
            let out = g.reveal(4, 4).unwrap();
            assert_eq!(out, Outcome::NoChange);
        }
    }

    // ── snapshot / save-resume ───────────────────────────────────────────────

    #[test]
    fn snapshot_round_trips_an_in_progress_game() {
        let mut g = Game::new(12, 9, 18, 0xBEEF);
        g.reveal(5, 4).unwrap();
        g.flag(0, 0).unwrap();
        g.flag(11, 8).unwrap();
        let snap = g.snapshot();
        let g2 = Game::from_snapshot(&snap);
        // identical observable state
        assert_eq!(g2.status(), g.status());
        assert_eq!(g2.flag_count(), g.flag_count());
        assert_eq!(g2.safe_cells(), g.safe_cells());
        for y in 0..9 {
            for x in 0..12 {
                assert_eq!(g2.cell(x, y), g.cell(x, y), "cell {x},{y}");
                assert_eq!(g2.is_mine(x, y), g.is_mine(x, y));
                assert_eq!(g2.adjacent_mines(x, y), g.adjacent_mines(x, y));
            }
        }
        // and the resumed game keeps playing correctly: same next reveal result
        let mut a = g.clone();
        let mut b = g2;
        // pick an unrevealed, unflagged, non-mine cell
        let mut target = None;
        'find: for y in 0..9 {
            for x in 0..12 {
                if !a.is_revealed(x, y) && !a.is_flagged(x, y) && !a.is_mine(x, y) {
                    target = Some((x, y));
                    break 'find;
                }
            }
        }
        if let Some((x, y)) = target {
            assert_eq!(a.reveal(x, y), b.reveal(x, y));
            assert_eq!(a.status(), b.status());
        }
    }

    #[test]
    fn snapshot_round_trips_a_fresh_unstarted_game() {
        let g = Game::new(30, 16, 99, 2024);
        let snap = g.snapshot();
        assert!(!snap.placed);
        let mut g2 = Game::from_snapshot(&snap);
        assert!(!g2.is_started());
        // placement still deferred; first reveal works on the resumed game
        let out = g2.reveal(15, 8).unwrap();
        assert!(!matches!(out, Outcome::Lost { .. }));
        assert_eq!(total_mines(&g2), 99);
    }

    #[test]
    fn to_json_payload_shape() {
        let mut g = Game::new(8, 8, 10, 0xABC);
        let j0 = g.to_json();
        assert!(j0.contains("\"type\":\"minesweeper.snapshot\""), "{j0}");
        assert!(j0.contains("\"width\":8"), "{j0}");
        assert!(j0.contains("\"height\":8"), "{j0}");
        assert!(j0.contains("\"mines\":10"), "{j0}");
        assert!(j0.contains("\"seed\":2748"), "{j0}"); // 0xABC == 2748
        assert!(j0.contains("\"started\":false"), "{j0}");
        assert!(j0.contains("\"status\":\"playing\""), "{j0}");
        assert!(j0.contains("\"safeCells\":54"), "{j0}");
        // after a reveal the started flag flips and masks are present
        g.reveal(3, 3).unwrap();
        let j1 = g.to_json();
        assert!(j1.contains("\"started\":true"), "{j1}");
        assert!(j1.contains("\"mineMask\":\""), "{j1}");
        assert!(j1.contains("\"revealedMask\":\""), "{j1}");
        assert!(j1.contains("\"board\":\""), "{j1}");
    }

    #[test]
    fn status_wire_encoding() {
        assert_eq!(GameStatus::Playing.wire(), "playing");
        assert_eq!(GameStatus::Won.wire(), "won");
        assert_eq!(GameStatus::Lost.wire(), "lost");
        assert!(!GameStatus::Playing.is_terminal());
        assert!(GameStatus::Won.is_terminal());
        assert!(GameStatus::Lost.is_terminal());
    }

    // ── board_str rendering ──────────────────────────────────────────────────

    #[test]
    fn board_str_reflects_player_view() {
        let mut g = Game::new(5, 5, 0, 1); // no mines
        // before any move: all hidden
        let pre = g.board_str();
        assert!(pre.lines().all(|l| l.chars().all(|c| c == '#')));
        assert_eq!(pre.lines().count(), 5);
        // reveal -> all open as '.' (0-adjacency); board wins
        g.reveal(2, 2).unwrap();
        let post = g.board_str();
        assert!(post.lines().all(|l| l.chars().all(|c| c == '.')), "{post}");
    }

    // ── deterministic end-to-end scripted game ───────────────────────────────

    #[test]
    fn scripted_seeded_game_is_reproducible() {
        // Two engines with the same seed, driven by the same script of actions,
        // end in byte-identical states — the determinism guarantee end-to-end.
        let script = [(4usize, 4usize), (0, 0), (8, 8), (1, 7), (7, 1)];
        let run = || {
            let mut g = Game::new(9, 9, 12, 0xDEADBEEF);
            for &(x, y) in &script {
                if g.status() == GameStatus::Playing {
                    let _ = g.reveal(x, y);
                }
            }
            g.to_json()
        };
        assert_eq!(run(), run(), "same seed + script must be byte-identical");
    }

    #[test]
    fn full_solve_with_perfect_information_wins() {
        // Play a real seeded board to a WIN by using ground-truth knowledge:
        // reveal every non-mine cell. Proves the win pipeline on a true random
        // board (not a hand-built snapshot).
        let mut g = Game::new(16, 16, 40, 0x1234_5678);
        g.reveal(0, 0).unwrap(); // first click safe + places mines
        for y in 0..16 {
            for x in 0..16 {
                if g.status() != GameStatus::Playing {
                    break;
                }
                if !g.is_mine(x, y) && !g.is_revealed(x, y) {
                    let out = g.reveal(x, y).unwrap();
                    assert!(
                        !matches!(out, Outcome::Lost { .. }),
                        "stepped on a mine while solving at {x},{y}"
                    );
                }
            }
        }
        assert_eq!(g.status(), GameStatus::Won);
        // every safe cell open, every mine still hidden
        for y in 0..16 {
            for x in 0..16 {
                if g.is_mine(x, y) {
                    assert!(!g.is_revealed(x, y));
                } else {
                    assert!(g.is_revealed(x, y), "safe {x},{y} not revealed at win");
                }
            }
        }
    }
}
