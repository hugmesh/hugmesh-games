//! `snapdragon-tetris` — a pure, dependency-free, single-player Tetris engine.
//!
//! A classic 10-wide by 20-tall well. The seven tetrominoes (`I O T S Z J L`)
//! are dealt from a **seeded 7-bag**: each bag is a shuffle of all seven pieces,
//! so a piece never appears three times before every other piece has appeared
//! once. Pieces spawn at the top, fall under gravity (`tick`), can be moved
//! left/right, rotated clockwise/counter-clockwise (with simple wall kicks),
//! soft-dropped, hard-dropped, and optionally held. A piece **locks** when it
//! can fall no further; full rows then clear (1=single … 4=tetris) and score.
//! Clearing lines raises the level, which speeds up gravity. The game is **over**
//! when a freshly-spawned piece overlaps the existing stack.
//!
//! This is the ONE engine: it compiles to `wasm32` for the in-browser game and
//! natively for `cargo test`. Because these are SINGLE-PLAYER puzzles the server
//! is never involved — the whole thing runs in the browser as WASM. There is NO
//! networking here.
//!
//! ## Determinism
//! Every source of randomness (the piece bag) is driven by a tiny seeded PRNG
//! (`Rng`, a 64-bit xorshift). Construct a game with [`Game::new(seed)`] and the
//! entire piece sequence — and therefore the entire game given the same inputs —
//! is fully reproducible. No `rand` crate, no system entropy.

#![allow(clippy::needless_range_loop)]

// ── seeded PRNG ──────────────────────────────────────────────────────────────

/// A tiny deterministic PRNG (`xorshift64*`). The ONLY source of randomness in
/// the engine; constructed from an explicit `seed` so every game is reproducible.
#[derive(Debug, Clone)]
pub struct Rng {
    state: u64,
}

impl Rng {
    /// Seed the generator. A zero seed is remapped to a non-zero constant
    /// (xorshift cannot leave the all-zero state), so `new(0)` is still valid.
    pub fn new(seed: u64) -> Rng {
        Rng { state: if seed == 0 { 0x9E37_79B9_7F4A_7C15 } else { seed } }
    }

    /// Next raw 64-bit value (advances the state).
    pub fn next_u64(&mut self) -> u64 {
        // xorshift64*
        let mut x = self.state;
        x ^= x >> 12;
        x ^= x << 25;
        x ^= x >> 27;
        self.state = x;
        x.wrapping_mul(0x2545_F491_4F6C_DD1D)
    }

    /// A uniform value in `0..n` (n > 0). Uses the high bits, which are the best
    /// quality bits of the multiplier output.
    pub fn below(&mut self, n: usize) -> usize {
        debug_assert!(n > 0);
        (((self.next_u64() >> 33) as usize) * n) >> 31
    }
}

// ── pieces ───────────────────────────────────────────────────────────────────

/// The seven tetromino kinds.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Piece {
    I,
    O,
    T,
    S,
    Z,
    J,
    L,
}

impl Piece {
    /// All seven kinds in canonical bag order (`I O T S Z J L`).
    pub const ALL: [Piece; 7] = [
        Piece::I,
        Piece::O,
        Piece::T,
        Piece::S,
        Piece::Z,
        Piece::J,
        Piece::L,
    ];

    /// The single ASCII glyph used in board / snapshot strings.
    pub fn glyph(self) -> char {
        match self {
            Piece::I => 'I',
            Piece::O => 'O',
            Piece::T => 'T',
            Piece::S => 'S',
            Piece::Z => 'Z',
            Piece::J => 'J',
            Piece::L => 'L',
        }
    }

    /// Lower-case wire token used in JSON payloads.
    pub fn wire(self) -> &'static str {
        match self {
            Piece::I => "i",
            Piece::O => "o",
            Piece::T => "t",
            Piece::S => "s",
            Piece::Z => "z",
            Piece::J => "j",
            Piece::L => "l",
        }
    }

    /// Index into [`Piece::ALL`]. Used by the bag-balance test invariants.
    #[allow(dead_code)]
    fn index(self) -> usize {
        match self {
            Piece::I => 0,
            Piece::O => 1,
            Piece::T => 2,
            Piece::S => 3,
            Piece::Z => 4,
            Piece::J => 5,
            Piece::L => 6,
        }
    }

    /// The four occupied cells of this piece in rotation state `rot` (0..4),
    /// expressed as `(col, row)` offsets from the piece's bounding-box origin.
    /// `row` grows downward (row 0 is the top), `col` grows rightward.
    ///
    /// `O` is rotationally symmetric (all four states identical). `I/S/Z` use a
    /// 2-state simplification (states 2/3 mirror 0/1) which keeps the kick tests
    /// simple while still distinguishing horizontal from vertical. `T/J/L` use
    /// the full four distinct states.
    fn cells(self, rot: u8) -> [(i32, i32); 4] {
        let r = (rot & 3) as usize;
        match self {
            // I spans a 4-wide box: horizontal on even states, vertical on odd.
            Piece::I => {
                if r % 2 == 0 {
                    [(0, 1), (1, 1), (2, 1), (3, 1)]
                } else {
                    [(2, 0), (2, 1), (2, 2), (2, 3)]
                }
            }
            // O never rotates.
            Piece::O => [(1, 0), (2, 0), (1, 1), (2, 1)],
            // T — all four distinct.
            Piece::T => match r {
                0 => [(1, 0), (0, 1), (1, 1), (2, 1)],
                1 => [(1, 0), (1, 1), (2, 1), (1, 2)],
                2 => [(0, 1), (1, 1), (2, 1), (1, 2)],
                _ => [(1, 0), (0, 1), (1, 1), (1, 2)],
            },
            // S — 2-state.
            Piece::S => {
                if r % 2 == 0 {
                    [(1, 0), (2, 0), (0, 1), (1, 1)]
                } else {
                    [(1, 0), (1, 1), (2, 1), (2, 2)]
                }
            }
            // Z — 2-state.
            Piece::Z => {
                if r % 2 == 0 {
                    [(0, 0), (1, 0), (1, 1), (2, 1)]
                } else {
                    [(2, 0), (1, 1), (2, 1), (1, 2)]
                }
            }
            // J — all four distinct.
            Piece::J => match r {
                0 => [(0, 0), (0, 1), (1, 1), (2, 1)],
                1 => [(1, 0), (2, 0), (1, 1), (1, 2)],
                2 => [(0, 1), (1, 1), (2, 1), (2, 2)],
                _ => [(1, 0), (1, 1), (0, 2), (1, 2)],
            },
            // L — all four distinct.
            Piece::L => match r {
                0 => [(2, 0), (0, 1), (1, 1), (2, 1)],
                1 => [(1, 0), (1, 1), (1, 2), (2, 2)],
                2 => [(0, 1), (1, 1), (2, 1), (0, 2)],
                _ => [(0, 0), (1, 0), (1, 1), (1, 2)],
            },
        }
    }
}

// ── seeded 7-bag ─────────────────────────────────────────────────────────────

/// A 7-bag randomiser: yields all seven pieces in a shuffled order, then reloads
/// and reshuffles, so the sequence is balanced and (given the seed) deterministic.
#[derive(Debug, Clone)]
struct Bag {
    rng: Rng,
    queue: Vec<Piece>,
}

impl Bag {
    fn new(seed: u64) -> Bag {
        let mut b = Bag { rng: Rng::new(seed), queue: Vec::with_capacity(7) };
        b.refill();
        b
    }

    /// Reload all seven kinds and Fisher–Yates shuffle them. The queue is popped
    /// from the back, so we reverse the shuffle order to deal in shuffled order.
    fn refill(&mut self) {
        let mut order: Vec<Piece> = Piece::ALL.to_vec();
        // Fisher–Yates over the seeded PRNG.
        for i in (1..order.len()).rev() {
            let j = self.rng.below(i + 1);
            order.swap(i, j);
        }
        order.reverse();
        self.queue = order;
    }

    /// Pull the next piece, refilling the bag when it empties.
    fn next(&mut self) -> Piece {
        if self.queue.is_empty() {
            self.refill();
        }
        self.queue.pop().expect("bag refilled, so non-empty")
    }

    /// Peek the piece that `next()` would return without consuming it.
    fn peek(&mut self) -> Piece {
        if self.queue.is_empty() {
            self.refill();
        }
        *self.queue.last().expect("bag refilled, so non-empty")
    }
}

// ── board ────────────────────────────────────────────────────────────────────

/// Well width in cells.
pub const WIDTH: usize = 10;
/// Well height in cells.
pub const HEIGHT: usize = 20;

/// An active (falling) piece: kind, rotation state, and bounding-box top-left
/// position on the board (`x` = column, `y` = row, row 0 at the top).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Active {
    pub piece: Piece,
    pub rot: u8,
    pub x: i32,
    pub y: i32,
}

impl Active {
    /// The four absolute `(col, row)` board cells this piece currently occupies.
    pub fn cells(self) -> [(i32, i32); 4] {
        let mut out = [(0, 0); 4];
        for (k, (cx, cy)) in self.piece.cells(self.rot).iter().enumerate() {
            out[k] = (self.x + cx, self.y + cy);
        }
        out
    }
}

// ── status / errors ──────────────────────────────────────────────────────────

/// Terminal / running classification of a game.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum GameStatus {
    /// A piece is in play (or about to spawn) and the game continues.
    Playing,
    /// The stack reached the top: a new piece could not spawn. Carries the final
    /// score and total lines cleared.
    GameOver { score: u64, lines: u32 },
}

impl GameStatus {
    pub fn is_terminal(self) -> bool {
        matches!(self, GameStatus::GameOver { .. })
    }

    /// Compact wire encoding.
    pub fn wire(self) -> String {
        match self {
            GameStatus::Playing => "playing".into(),
            GameStatus::GameOver { score, lines } => format!("gameover:{score}:{lines}"),
        }
    }
}

/// Why an action was rejected.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MoveError {
    /// The game is already over; no action is accepted.
    GameOver,
    /// The move would push the piece into a wall, the floor, or the stack.
    Blocked,
    /// `hold` was attempted twice for the same piece (only one hold per piece).
    AlreadyHeld,
}

// ── the game ─────────────────────────────────────────────────────────────────

/// One full single-player Tetris game.
#[derive(Clone)]
pub struct Game {
    /// `cells[row][col]` — `None` empty, `Some(piece)` a locked block. Row 0 top.
    cells: [[Option<Piece>; WIDTH]; HEIGHT],
    bag: Bag,
    active: Active,
    /// Held piece, if any.
    held: Option<Piece>,
    /// Whether `hold` may be used for the current piece (reset on each spawn).
    can_hold: bool,
    score: u64,
    lines: u32,
    level: u32,
    /// Set when a spawn fails. `active` then holds the (overlapping) piece that
    /// could not be placed, for inspection / rendering.
    over: bool,
}

impl Game {
    /// Start a fresh game seeded by `seed`. The first piece is dealt and spawned.
    pub fn new(seed: u64) -> Game {
        let mut bag = Bag::new(seed);
        let first = bag.next();
        let active = Game::spawn_for(first);
        Game {
            cells: [[None; WIDTH]; HEIGHT],
            bag,
            active,
            held: None,
            can_hold: true,
            score: 0,
            lines: 0,
            level: 0,
            over: false,
        }
    }

    /// The spawn placement for a piece: horizontally centred near the top. The
    /// bounding box origin is chosen so the visible cells sit at the top rows.
    fn spawn_for(piece: Piece) -> Active {
        // Centre a 4-wide bounding box: columns 3..7 keeps every piece centred.
        Active { piece, rot: 0, x: 3, y: 0 }
    }

    // ── read-only accessors ────────────────────────────────────────────────

    pub fn score(&self) -> u64 {
        self.score
    }
    pub fn lines(&self) -> u32 {
        self.lines
    }
    pub fn level(&self) -> u32 {
        self.level
    }
    pub fn is_over(&self) -> bool {
        self.over
    }
    /// The currently-falling piece (valid even after game-over, for rendering).
    pub fn active(&self) -> Active {
        self.active
    }
    pub fn held(&self) -> Option<Piece> {
        self.held
    }
    /// The piece that will spawn next (peeked from the bag, non-destructive).
    pub fn next_piece(&self) -> Piece {
        // peek requires &mut for lazy refill; clone the bag to stay &self.
        let mut b = self.bag.clone();
        b.peek()
    }

    /// The locked-block grid (does NOT include the active piece). Row 0 is the
    /// top. `None` is an empty cell.
    pub fn cells(&self) -> [[Option<Piece>; WIDTH]; HEIGHT] {
        self.cells
    }

    /// The block at `(col, row)` in the locked grid (active piece not merged).
    pub fn at(&self, col: usize, row: usize) -> Option<Piece> {
        self.cells[row][col]
    }

    /// True when the locked cell at `(col,row)` is filled.
    pub fn filled(&self, col: usize, row: usize) -> bool {
        self.cells[row][col].is_some()
    }

    /// The number of non-empty rows from the bottom up to the highest block
    /// (the stack height). Zero on an empty board.
    pub fn stack_height(&self) -> usize {
        for row in 0..HEIGHT {
            if self.cells[row].iter().any(|c| c.is_some()) {
                return HEIGHT - row;
            }
        }
        0
    }

    pub fn status(&self) -> GameStatus {
        if self.over {
            GameStatus::GameOver { score: self.score, lines: self.lines }
        } else {
            GameStatus::Playing
        }
    }

    // ── collision ──────────────────────────────────────────────────────────

    /// Would the active piece, transformed to `(piece,rot,x,y)`, overlap a wall,
    /// the floor, or a locked block? (`true` ⇒ illegal placement.)
    fn collides(&self, probe: Active) -> bool {
        for (cx, cy) in probe.cells() {
            // Out of the side walls or below the floor is a collision. Above the
            // top (cy < 0) is allowed — pieces may straddle the ceiling on spawn.
            if cx < 0 || cx >= WIDTH as i32 || cy >= HEIGHT as i32 {
                return true;
            }
            if cy >= 0 && self.cells[cy as usize][cx as usize].is_some() {
                return true;
            }
        }
        false
    }

    /// Try to replace the active piece with `probe`; succeeds only if it doesn't
    /// collide.
    fn try_move(&mut self, probe: Active) -> Result<(), MoveError> {
        if self.over {
            return Err(MoveError::GameOver);
        }
        if self.collides(probe) {
            return Err(MoveError::Blocked);
        }
        self.active = probe;
        Ok(())
    }

    // ── horizontal / rotational moves ──────────────────────────────────────

    /// Shift the active piece one column left.
    pub fn move_left(&mut self) -> Result<(), MoveError> {
        let mut p = self.active;
        p.x -= 1;
        self.try_move(p)
    }

    /// Shift the active piece one column right.
    pub fn move_right(&mut self) -> Result<(), MoveError> {
        let mut p = self.active;
        p.x += 1;
        self.try_move(p)
    }

    /// The simple wall-kick offsets tried, in order, when a rotation collides in
    /// place: no shift, then right/left by one and two, then up by one. This is a
    /// pragmatic kick table (not full SRS) sufficient to let pieces rotate off
    /// the walls and out of shallow pockets.
    const KICKS: [(i32, i32); 7] =
        [(0, 0), (1, 0), (-1, 0), (2, 0), (-2, 0), (0, -1), (1, -1)];

    fn rotate(&mut self, cw: bool) -> Result<(), MoveError> {
        if self.over {
            return Err(MoveError::GameOver);
        }
        let new_rot = if cw {
            (self.active.rot + 1) & 3
        } else {
            (self.active.rot + 3) & 3
        };
        for (dx, dy) in Game::KICKS {
            let probe = Active {
                piece: self.active.piece,
                rot: new_rot,
                x: self.active.x + dx,
                y: self.active.y + dy,
            };
            if !self.collides(probe) {
                self.active = probe;
                return Ok(());
            }
        }
        Err(MoveError::Blocked)
    }

    /// Rotate the active piece clockwise (with wall kicks).
    pub fn rotate_cw(&mut self) -> Result<(), MoveError> {
        self.rotate(true)
    }

    /// Rotate the active piece counter-clockwise (with wall kicks).
    pub fn rotate_ccw(&mut self) -> Result<(), MoveError> {
        self.rotate(false)
    }

    // ── gravity / dropping ─────────────────────────────────────────────────

    /// One gravity step: move the piece down one row. If it cannot fall it locks
    /// (merges into the stack, clears full lines, spawns the next piece — which
    /// may end the game). Returns `Ok(true)` if a lock occurred this tick,
    /// `Ok(false)` if the piece merely fell one row.
    pub fn tick(&mut self) -> Result<bool, MoveError> {
        if self.over {
            return Err(MoveError::GameOver);
        }
        let mut down = self.active;
        down.y += 1;
        if self.collides(down) {
            self.lock_and_next();
            Ok(true)
        } else {
            self.active = down;
            Ok(false)
        }
    }

    /// Alias for [`Game::tick`] — one gravity step.
    pub fn step(&mut self) -> Result<bool, MoveError> {
        self.tick()
    }

    /// Soft drop: move the piece down one row WITHOUT locking. Awards 1 point per
    /// cell descended (classic soft-drop scoring). Returns `Ok(false)` and awards
    /// nothing when the piece is already resting (does not lock — call `tick` /
    /// `hard_drop` to lock). Errors only if the game is over.
    pub fn soft_drop(&mut self) -> Result<bool, MoveError> {
        if self.over {
            return Err(MoveError::GameOver);
        }
        let mut down = self.active;
        down.y += 1;
        if self.collides(down) {
            Ok(false)
        } else {
            self.active = down;
            self.score += 1;
            Ok(true)
        }
    }

    /// Hard drop: slam the piece straight down to its resting row, award 2 points
    /// per cell dropped (classic hard-drop scoring), then lock + advance. Returns
    /// the number of rows the piece fell.
    pub fn hard_drop(&mut self) -> Result<i32, MoveError> {
        if self.over {
            return Err(MoveError::GameOver);
        }
        let mut dropped = 0;
        loop {
            let mut down = self.active;
            down.y += 1;
            if self.collides(down) {
                break;
            }
            self.active = down;
            dropped += 1;
        }
        self.score += (dropped as u64) * 2;
        self.lock_and_next();
        Ok(dropped)
    }

    /// Hold the current piece. The first hold stashes the active piece and spawns
    /// the next from the bag; a later hold swaps the active piece with the held
    /// one. Only ONE hold is allowed per piece (until the next piece locks).
    /// A swap that would immediately collide ends the game (the swapped-in piece
    /// cannot be placed).
    pub fn hold(&mut self) -> Result<(), MoveError> {
        if self.over {
            return Err(MoveError::GameOver);
        }
        if !self.can_hold {
            return Err(MoveError::AlreadyHeld);
        }
        let current = self.active.piece;
        let incoming = match self.held {
            Some(h) => h,
            None => self.bag.next(),
        };
        self.held = Some(current);
        self.can_hold = false;
        let spawned = Game::spawn_for(incoming);
        if self.collides(spawned) {
            self.active = spawned;
            self.over = true;
        } else {
            self.active = spawned;
        }
        Ok(())
    }

    // ── locking, line-clears, scoring, spawning ────────────────────────────

    /// Merge the active piece into the stack, clear full lines & score, level up,
    /// then spawn the next piece (ending the game if it can't be placed).
    fn lock_and_next(&mut self) {
        for (cx, cy) in self.active.cells() {
            if cy >= 0 && cy < HEIGHT as i32 && cx >= 0 && cx < WIDTH as i32 {
                self.cells[cy as usize][cx as usize] = Some(self.active.piece);
            }
        }
        let cleared = self.clear_lines();
        self.apply_clear_score(cleared);
        self.spawn_next();
    }

    /// Remove every fully-filled row, compacting the stack downward. Returns the
    /// count of rows cleared (0..=4).
    fn clear_lines(&mut self) -> u32 {
        let mut new_rows: Vec<[Option<Piece>; WIDTH]> = Vec::with_capacity(HEIGHT);
        let mut cleared = 0u32;
        for row in 0..HEIGHT {
            if self.cells[row].iter().all(|c| c.is_some()) {
                cleared += 1;
            } else {
                new_rows.push(self.cells[row]);
            }
        }
        // Rebuild: empty rows on top, surviving rows pushed to the bottom.
        let mut rebuilt = [[None; WIDTH]; HEIGHT];
        let offset = HEIGHT - new_rows.len();
        for (i, r) in new_rows.into_iter().enumerate() {
            rebuilt[offset + i] = r;
        }
        self.cells = rebuilt;
        cleared
    }

    /// Apply the classic line-clear score (scaled by level+1) and bump the level
    /// every 10 cleared lines. 1=100, 2=300, 3=500, 4(tetris)=800.
    fn apply_clear_score(&mut self, cleared: u32) {
        if cleared == 0 {
            return;
        }
        let base = match cleared {
            1 => 100,
            2 => 300,
            3 => 500,
            _ => 800,
        };
        self.score += base * (self.level as u64 + 1);
        self.lines += cleared;
        self.level = self.lines / 10;
    }

    /// Pull the next piece from the bag and spawn it; if it overlaps the stack
    /// the game is over. Re-enables `hold` for the new piece.
    fn spawn_next(&mut self) {
        let piece = self.bag.next();
        let spawned = Game::spawn_for(piece);
        self.can_hold = true;
        self.active = spawned;
        if self.collides(spawned) {
            self.over = true;
        }
    }

    /// Gravity period in ticks-per-cell for the current level. Faster as the
    /// level rises (classic-style curve), floored at 1. The caller decides the
    /// real-time cadence; this is just the relative speed.
    pub fn gravity_period(&self) -> u32 {
        // Start slow (level 0 == 48 frames/cell) and tighten with level.
        let l = self.level;
        if l >= 9 {
            1
        } else {
            (48u32).saturating_sub(l * 5).max(1)
        }
    }

    // ── snapshot / json ────────────────────────────────────────────────────

    /// The locked-stack grid as a `HEIGHT`-line string (top row first), `'.'` for
    /// empty cells and the piece glyph for filled ones. The active piece is NOT
    /// drawn (use [`Game::render`] for that).
    pub fn board_str(&self) -> String {
        let mut s = String::with_capacity(HEIGHT * (WIDTH + 1));
        for row in 0..HEIGHT {
            for col in 0..WIDTH {
                s.push(self.cells[row][col].map(|p| p.glyph()).unwrap_or('.'));
            }
            if row + 1 < HEIGHT {
                s.push('\n');
            }
        }
        s
    }

    /// Like [`Game::board_str`] but with the active piece overlaid as `'@'`
    /// (off-screen cells above the top are not drawn). Handy for tests / display.
    pub fn render(&self) -> String {
        let mut grid: Vec<Vec<char>> = (0..HEIGHT)
            .map(|row| {
                (0..WIDTH)
                    .map(|col| self.cells[row][col].map(|p| p.glyph()).unwrap_or('.'))
                    .collect()
            })
            .collect();
        for (cx, cy) in self.active.cells() {
            if cy >= 0 && cy < HEIGHT as i32 && cx >= 0 && cx < WIDTH as i32 {
                grid[cy as usize][cx as usize] = '@';
            }
        }
        grid.iter()
            .map(|r| r.iter().collect::<String>())
            .collect::<Vec<_>>()
            .join("\n")
    }

    /// A compact single-line board encoding (`WIDTH*HEIGHT` chars, no newlines,
    /// top row first) for the JSON payload.
    fn board_flat(&self) -> String {
        let mut s = String::with_capacity(HEIGHT * WIDTH);
        for row in 0..HEIGHT {
            for col in 0..WIDTH {
                s.push(self.cells[row][col].map(|p| p.glyph()).unwrap_or('.'));
            }
        }
        s
    }

    /// The full save-resume snapshot as JSON. Captures the board, the active
    /// piece (kind/rotation/position), the held piece, the next piece, and the
    /// running score/lines/level/status — everything a browser needs to redraw
    /// or resume. The server never parses this; it only lives in the browser.
    pub fn to_json(&self) -> String {
        let held = match self.held {
            Some(p) => format!("\"{}\"", p.wire()),
            None => "null".to_string(),
        };
        format!(
            "{{\"type\":\"tetris.snapshot\",\"width\":{w},\"height\":{h},\"board\":\"{board}\",\
\"active\":{{\"piece\":\"{ap}\",\"rot\":{ar},\"x\":{ax},\"y\":{ay}}},\
\"held\":{held},\"canHold\":{ch},\"next\":\"{next}\",\
\"score\":{score},\"lines\":{lines},\"level\":{level},\"status\":\"{status}\"}}",
            w = WIDTH,
            h = HEIGHT,
            board = self.board_flat(),
            ap = self.active.piece.wire(),
            ar = self.active.rot,
            ax = self.active.x,
            ay = self.active.y,
            held = held,
            ch = self.can_hold,
            next = self.next_piece().wire(),
            score = self.score,
            lines = self.lines,
            level = self.level,
            status = self.status().wire(),
        )
    }

    /// Alias for [`Game::to_json`].
    pub fn snapshot(&self) -> String {
        self.to_json()
    }

    // ── test-only construction helpers ─────────────────────────────────────

    /// Set the active piece directly (kind/rotation/position). Test-only; lets a
    /// test place a known piece without dealing the bag. Does not validate.
    #[cfg(test)]
    fn set_active(&mut self, piece: Piece, rot: u8, x: i32, y: i32) {
        self.active = Active { piece, rot, x, y };
    }

    /// Fill `(col,row)` in the locked grid. Test-only board surgery.
    #[cfg(test)]
    fn set_cell(&mut self, col: usize, row: usize, piece: Option<Piece>) {
        self.cells[row][col] = piece;
    }
}

// ═════════════════════════════════════════════════════════════════════════════
//  TESTS
// ═════════════════════════════════════════════════════════════════════════════

#[cfg(test)]
mod tests {
    use super::*;

    // ── PRNG determinism ──────────────────────────────────────────────────────
    #[test]
    fn rng_is_deterministic_for_a_seed() {
        let mut a = Rng::new(12345);
        let mut b = Rng::new(12345);
        for _ in 0..1000 {
            assert_eq!(a.next_u64(), b.next_u64());
        }
    }

    #[test]
    fn rng_diverges_for_different_seeds() {
        let mut a = Rng::new(1);
        let mut b = Rng::new(2);
        // Overwhelmingly unlikely to match on the very first draw.
        assert_ne!(a.next_u64(), b.next_u64());
    }

    #[test]
    fn rng_zero_seed_is_valid_and_nontrivial() {
        let mut a = Rng::new(0);
        let x = a.next_u64();
        let y = a.next_u64();
        assert_ne!(x, 0);
        assert_ne!(x, y);
    }

    #[test]
    fn rng_below_stays_in_range() {
        let mut r = Rng::new(99);
        for n in 1..=20usize {
            for _ in 0..200 {
                assert!(r.below(n) < n);
            }
        }
    }

    // ── 7-bag properties ──────────────────────────────────────────────────────
    #[test]
    fn bag_yields_all_seven_each_cycle() {
        let mut bag = Bag::new(777);
        for _cycle in 0..50 {
            let mut seen = [0u32; 7];
            for _ in 0..7 {
                seen[bag.next().index()] += 1;
            }
            // every kind exactly once per 7-pull window
            assert_eq!(seen, [1; 7], "a 7-bag must contain each kind exactly once");
        }
    }

    #[test]
    fn bag_peek_does_not_consume() {
        let mut bag = Bag::new(42);
        let p = bag.peek();
        assert_eq!(p, bag.peek());
        assert_eq!(p, bag.next());
    }

    // ── deterministic piece sequence ──────────────────────────────────────────
    #[test]
    fn deterministic_seed_yields_deterministic_sequence() {
        let seq = |seed: u64| -> Vec<Piece> {
            let mut g = Game::new(seed);
            let mut out = vec![g.active().piece];
            // hard-drop 34 pieces; collect each spawned active piece.
            for _ in 0..34 {
                if g.is_over() {
                    break;
                }
                g.hard_drop().ok();
                out.push(g.active().piece);
            }
            out
        };
        let a = seq(0xABCD);
        let b = seq(0xABCD);
        assert_eq!(a, b, "same seed must reproduce the same piece sequence");
        assert!(a.len() > 7);
        // each successive 7-window is a permutation of all seven (bag invariant)
        for chunk in a.chunks(7).filter(|c| c.len() == 7) {
            let mut seen = [0u32; 7];
            for p in chunk {
                seen[p.index()] += 1;
            }
            assert_eq!(seen, [1; 7]);
        }
    }

    #[test]
    fn different_seeds_usually_differ() {
        let mut g1 = Game::new(1);
        let mut g2 = Game::new(2);
        let mut s1 = Vec::new();
        let mut s2 = Vec::new();
        for _ in 0..14 {
            s1.push(g1.active().piece);
            s2.push(g2.active().piece);
            g1.hard_drop().ok();
            g2.hard_drop().ok();
        }
        assert_ne!(s1, s2, "distinct seeds should give distinct sequences");
    }

    #[test]
    fn next_piece_matches_what_spawns() {
        let mut g = Game::new(555);
        let predicted = g.next_piece();
        g.hard_drop().unwrap();
        assert_eq!(g.active().piece, predicted);
    }

    // ── a placed piece locks ──────────────────────────────────────────────────
    #[test]
    fn hard_drop_locks_a_piece_into_the_stack() {
        let mut g = Game::new(7);
        assert_eq!(g.stack_height(), 0);
        let dropped = g.hard_drop().unwrap();
        assert!(dropped > 0, "piece fell from the top");
        // the bottom row(s) now hold the four cells of the locked piece
        let filled: usize = (0..HEIGHT)
            .flat_map(|r| (0..WIDTH).map(move |c| (c, r)))
            .filter(|&(c, r)| g.filled(c, r))
            .count();
        assert_eq!(filled, 4, "exactly four cells locked");
        assert!(g.stack_height() >= 1);
    }

    #[test]
    fn tick_locks_when_it_cannot_fall() {
        let mut g = Game::new(3);
        // tick until the first lock happens
        let mut locked = false;
        for _ in 0..HEIGHT + 2 {
            if g.tick().unwrap() {
                locked = true;
                break;
            }
        }
        assert!(locked, "the piece must eventually lock by gravity");
        let filled: usize = (0..HEIGHT)
            .flat_map(|r| (0..WIDTH).map(move |c| (c, r)))
            .filter(|&(c, r)| g.filled(c, r))
            .count();
        // Four cells from the just-locked piece, plus a freshly spawned active
        // piece (which is NOT in the locked grid). So exactly four locked cells.
        assert_eq!(filled, 4);
    }

    #[test]
    fn tick_only_falls_one_row_when_unobstructed() {
        let mut g = Game::new(11);
        let y0 = g.active().y;
        let locked = g.tick().unwrap();
        assert!(!locked, "a high piece shouldn't lock on the first tick");
        assert_eq!(g.active().y, y0 + 1);
    }

    // ── line clears: single / double / triple / tetris ────────────────────────

    /// Fill the bottom `rows` rows completely EXCEPT a single empty column, so a
    /// vertically-oriented bar dropped into that column completes them at once.
    fn prime_rows_with_gap(g: &mut Game, rows: usize, gap_col: usize) {
        for r in (HEIGHT - rows)..HEIGHT {
            for c in 0..WIDTH {
                if c != gap_col {
                    g.set_cell(c, r, Some(Piece::O));
                }
            }
        }
    }

    /// The line-clear bonus alone: total score minus the hard-drop bonus
    /// (`2 * rows fallen`). Lets a test assert the clear bonus exactly without
    /// depending on how far the bar happened to fall.
    fn clear_bonus(score: u64, dropped: i32) -> u64 {
        score - (dropped as u64) * 2
    }

    #[test]
    fn single_line_clear_scores_100() {
        let mut g = Game::new(1);
        // bottom row missing only column 0
        prime_rows_with_gap(&mut g, 1, 0);
        // Put a vertical I into column 0 and hard-drop to complete the row.
        g.set_active(Piece::I, 1, -2, 0); // rot 1 (vertical), cells at col x+2=0
        assert_eq!(g.active().cells().iter().map(|c| c.0).min().unwrap(), 0);
        let before = g.lines();
        let dropped = g.hard_drop().unwrap();
        assert_eq!(g.lines(), before + 1, "one row cleared");
        assert_eq!(clear_bonus(g.score(), dropped), 100, "single == 100 at level 0");
        // bottom row is now empty again (cleared), stack collapsed
        assert!(!g.filled(1, HEIGHT - 1), "cleared row should be empty");
    }

    #[test]
    fn double_line_clear_scores_300() {
        let mut g = Game::new(1);
        prime_rows_with_gap(&mut g, 2, 0);
        g.set_active(Piece::I, 1, -2, 0); // vertical I in column 0
        let dropped = g.hard_drop().unwrap();
        assert_eq!(g.lines(), 2);
        // I is 4 tall, fills the two gap cells; both primed rows complete.
        assert_eq!(clear_bonus(g.score(), dropped), 300, "double == 300 at level 0");
    }

    #[test]
    fn triple_line_clear_scores_500() {
        let mut g = Game::new(1);
        prime_rows_with_gap(&mut g, 3, 0);
        g.set_active(Piece::I, 1, -2, 0); // vertical I, 4 tall, fills 3-row gap
        let dropped = g.hard_drop().unwrap();
        assert_eq!(g.lines(), 3);
        assert_eq!(clear_bonus(g.score(), dropped), 500, "triple == 500 at level 0");
    }

    #[test]
    fn tetris_four_line_clear_scores_800() {
        let mut g = Game::new(1);
        prime_rows_with_gap(&mut g, 4, 0);
        // A vertical I is exactly 4 tall: it fills the 4-row gap column → tetris.
        g.set_active(Piece::I, 1, -2, 0);
        let dropped = g.hard_drop().unwrap();
        assert_eq!(g.lines(), 4, "four rows cleared at once == tetris");
        assert_eq!(clear_bonus(g.score(), dropped), 800, "tetris == 800 at level 0");
        // every primed row is gone; the board is empty again
        let any = (0..HEIGHT)
            .flat_map(|r| (0..WIDTH).map(move |c| (c, r)))
            .any(|(c, r)| g.filled(c, r));
        assert!(!any, "a full tetris clear empties the board");
    }

    #[test]
    fn partial_fill_does_not_clear() {
        let mut g = Game::new(1);
        // bottom row missing TWO columns; a single I only fills one of them.
        for c in 0..WIDTH {
            if c != 0 && c != 1 {
                g.set_cell(c, HEIGHT - 1, Some(Piece::O));
            }
        }
        g.set_active(Piece::I, 1, -2, 0);
        let dropped = g.hard_drop().unwrap();
        assert_eq!(g.lines(), 0, "an incomplete row must not clear");
        // no line bonus at all — the entire score is the hard-drop bonus
        assert_eq!(clear_bonus(g.score(), dropped), 0, "no line bonus when nothing clears");
    }

    #[test]
    fn level_and_speed_increase_after_ten_lines() {
        let mut g = Game::new(1);
        assert_eq!(g.level(), 0);
        let p0 = g.gravity_period();
        // Clear ten single rows the cheap way: drive lines() up by repeated
        // single clears using primed-gap drops.
        for _ in 0..10 {
            prime_rows_with_gap(&mut g, 1, 0);
            g.set_active(Piece::I, 1, -2, 0);
            g.hard_drop().unwrap();
        }
        assert_eq!(g.lines(), 10);
        assert_eq!(g.level(), 1, "ten lines == level 1");
        assert!(g.gravity_period() < p0, "higher level falls faster");
    }

    // ── movement + rotation, including walls ───────────────────────────────────
    #[test]
    fn move_left_right_and_wall_block() {
        let mut g = Game::new(1);
        g.set_active(Piece::O, 0, 4, 0);
        g.move_left().unwrap();
        assert_eq!(g.active().x, 3);
        g.move_right().unwrap();
        assert_eq!(g.active().x, 4);
        // shove to the left wall; O occupies cols x+1,x+2 so the wall is at x=-1
        for _ in 0..20 {
            let _ = g.move_left();
        }
        let x_min = g.active().cells().iter().map(|c| c.0).min().unwrap();
        assert_eq!(x_min, 0, "piece rests flush against the left wall");
        // one more left is rejected
        assert_eq!(g.move_left(), Err(MoveError::Blocked));
    }

    #[test]
    fn cannot_move_into_the_right_wall() {
        let mut g = Game::new(1);
        g.set_active(Piece::O, 0, 4, 0);
        for _ in 0..20 {
            let _ = g.move_right();
        }
        let x_max = g.active().cells().iter().map(|c| c.0).max().unwrap();
        assert_eq!(x_max, (WIDTH - 1) as i32, "piece rests flush against right wall");
        assert_eq!(g.move_right(), Err(MoveError::Blocked));
    }

    #[test]
    fn rotation_changes_state_and_returns_to_origin() {
        let mut g = Game::new(1);
        g.set_active(Piece::T, 0, 3, 0);
        assert_eq!(g.active().rot, 0);
        g.rotate_cw().unwrap();
        assert_eq!(g.active().rot, 1);
        g.rotate_cw().unwrap();
        g.rotate_cw().unwrap();
        g.rotate_cw().unwrap();
        assert_eq!(g.active().rot, 0, "four CW rotations return to the start");
        g.rotate_ccw().unwrap();
        assert_eq!(g.active().rot, 3, "CCW from 0 wraps to 3");
    }

    #[test]
    fn rotation_near_a_wall_kicks_off_it() {
        // An I pushed flush to the left wall, horizontal. Rotating to vertical in
        // place is fine, but place a T flush left and rotate to prove a kick.
        let mut g = Game::new(1);
        // Put a T at the far left in its spawn (flat-top) state.
        g.set_active(Piece::T, 0, -1, 5); // x=-1 puts the left cell at col 0
        // Its cells in rot 0: (1,0),(0,1),(1,1),(2,1) → with x=-1: cols -? compute
        // Ensure it's not already colliding (kick logic handles edges):
        // rotate CCW which would otherwise push a cell off the left wall.
        let before = g.active().rot;
        let res = g.rotate_ccw();
        assert!(res.is_ok(), "rotation near the wall should kick into a legal spot");
        // it actually rotated (state advanced) and is on-board (no collision)
        assert_ne!(g.active().rot, before);
        for (cx, _cy) in g.active().cells() {
            assert!(cx >= 0 && cx < WIDTH as i32, "kicked piece stays on-board");
        }
    }

    #[test]
    fn rotation_fully_blocked_is_rejected() {
        // Surround the active piece so NO kick offset is legal.
        let mut g = Game::new(1);
        // Fill the entire board except a 1-wide vertical slot for a vertical I.
        for r in 0..HEIGHT {
            for c in 0..WIDTH {
                if c != 4 {
                    g.set_cell(c, r, Some(Piece::O));
                }
            }
        }
        // A vertical I sitting in column 4. Rotating to horizontal needs 3 more
        // columns that are all filled → every kick collides.
        g.set_active(Piece::I, 1, 2, 0); // vertical, occupies col 4
        assert_eq!(g.rotate_cw(), Err(MoveError::Blocked));
    }

    #[test]
    fn soft_drop_moves_down_and_scores_one() {
        let mut g = Game::new(1);
        let y0 = g.active().y;
        let s0 = g.score();
        let moved = g.soft_drop().unwrap();
        assert!(moved);
        assert_eq!(g.active().y, y0 + 1);
        assert_eq!(g.score(), s0 + 1, "soft drop awards 1 per cell");
    }

    #[test]
    fn soft_drop_at_rest_does_not_lock() {
        let mut g = Game::new(1);
        // drop to the floor first
        while g.soft_drop().unwrap() {}
        let active_before = g.active();
        // at rest: soft_drop is a no-op (false), piece is NOT locked
        assert_eq!(g.soft_drop().unwrap(), false);
        assert_eq!(g.active(), active_before, "still the same un-locked piece");
        assert_eq!(g.stack_height(), 0, "soft drop never locks");
    }

    #[test]
    fn hard_drop_scores_two_per_cell() {
        let mut g = Game::new(1);
        g.set_active(Piece::O, 0, 4, 0);
        let dropped = g.hard_drop().unwrap();
        assert_eq!(g.score(), (dropped as u64) * 2, "hard drop == 2/cell, no lines");
        assert!(dropped > 0);
    }

    // ── hold ───────────────────────────────────────────────────────────────────
    #[test]
    fn hold_stashes_and_swaps() {
        let mut g = Game::new(1);
        let first = g.active().piece;
        assert_eq!(g.held(), None);
        g.hold().unwrap();
        assert_eq!(g.held(), Some(first), "first hold stashes the active piece");
        // a second hold for the same (new) piece is rejected
        assert_eq!(g.hold(), Err(MoveError::AlreadyHeld));
        // lock the current piece → hold re-enabled
        g.hard_drop().unwrap();
        let now = g.active().piece;
        g.hold().unwrap(); // swap: held(first) ↔ active(now)
        assert_eq!(g.active().piece, first, "swap brings the held piece in");
        assert_eq!(g.held(), Some(now), "the swapped-out piece is now held");
    }

    #[test]
    fn hold_resets_after_lock() {
        let mut g = Game::new(2);
        g.hold().unwrap();
        assert_eq!(g.hold(), Err(MoveError::AlreadyHeld));
        g.hard_drop().unwrap();
        // new piece: hold allowed again
        assert!(g.hold().is_ok());
    }

    // ── game over ──────────────────────────────────────────────────────────────
    #[test]
    fn game_over_when_stack_reaches_the_top() {
        let mut g = Game::new(123);
        let mut guard = 0;
        // Keep hard-dropping. With no horizontal spreading the stack rises in the
        // centre columns and eventually a spawn collides → game over.
        while !g.is_over() {
            // ignore Blocked/GameOver; just keep slamming pieces down
            let _ = g.hard_drop();
            guard += 1;
            assert!(guard < 1000, "game should terminate well before 1000 drops");
        }
        match g.status() {
            GameStatus::GameOver { score, lines } => {
                assert_eq!(score, g.score());
                assert_eq!(lines, g.lines());
            }
            GameStatus::Playing => panic!("expected GameOver"),
        }
        assert!(g.status().is_terminal());
    }

    #[test]
    fn actions_rejected_after_game_over() {
        let mut g = Game::new(123);
        let mut guard = 0;
        while !g.is_over() {
            let _ = g.hard_drop();
            guard += 1;
            assert!(guard < 1000);
        }
        assert_eq!(g.tick(), Err(MoveError::GameOver));
        assert_eq!(g.move_left(), Err(MoveError::GameOver));
        assert_eq!(g.move_right(), Err(MoveError::GameOver));
        assert_eq!(g.rotate_cw(), Err(MoveError::GameOver));
        assert_eq!(g.rotate_ccw(), Err(MoveError::GameOver));
        assert_eq!(g.soft_drop(), Err(MoveError::GameOver));
        assert_eq!(g.hard_drop(), Err(MoveError::GameOver));
        assert_eq!(g.hold(), Err(MoveError::GameOver));
    }

    #[test]
    fn game_over_is_deterministic_for_a_seed() {
        let play = |seed: u64| -> (u64, u32, u32) {
            let mut g = Game::new(seed);
            let mut guard = 0;
            while !g.is_over() {
                let _ = g.hard_drop();
                guard += 1;
                assert!(guard < 5000);
            }
            (g.score(), g.lines(), guard)
        };
        assert_eq!(play(98765), play(98765), "same seed → identical end state");
    }

    // ── snapshot / json round-trip-ish ─────────────────────────────────────────
    #[test]
    fn snapshot_json_has_expected_fields() {
        let g = Game::new(2024);
        let j = g.to_json();
        assert!(j.contains("\"type\":\"tetris.snapshot\""), "{j}");
        assert!(j.contains(&format!("\"width\":{WIDTH}")), "{j}");
        assert!(j.contains(&format!("\"height\":{HEIGHT}")), "{j}");
        assert!(j.contains("\"score\":0"), "{j}");
        assert!(j.contains("\"lines\":0"), "{j}");
        assert!(j.contains("\"level\":0"), "{j}");
        assert!(j.contains("\"status\":\"playing\""), "{j}");
        assert!(j.contains("\"held\":null"), "{j}");
        // active piece wire token present
        assert!(j.contains(&format!("\"piece\":\"{}\"", g.active().piece.wire())), "{j}");
        // flat board is WIDTH*HEIGHT chars
        let start = j.find("\"board\":\"").unwrap() + "\"board\":\"".len();
        let end = j[start..].find('"').unwrap();
        assert_eq!(end, WIDTH * HEIGHT, "flat board length");
    }

    #[test]
    fn snapshot_reflects_score_and_held_after_play() {
        let mut g = Game::new(2024);
        g.hold().unwrap();
        g.set_active(Piece::O, 0, 4, 0);
        g.hard_drop().unwrap();
        let j = g.to_json();
        assert!(!j.contains("\"held\":null"), "held should be populated: {j}");
        assert!(j.contains(&format!("\"score\":{}", g.score())), "{j}");
        assert!(j.contains("\"canHold\":true"), "hold re-enabled after a lock: {j}");
    }

    #[test]
    fn status_wire_encoding() {
        assert_eq!(GameStatus::Playing.wire(), "playing");
        assert_eq!(
            GameStatus::GameOver { score: 1234, lines: 9 }.wire(),
            "gameover:1234:9"
        );
    }

    // ── rendering ──────────────────────────────────────────────────────────────
    #[test]
    fn render_overlays_active_piece() {
        let mut g = Game::new(1);
        g.set_active(Piece::O, 0, 4, 0);
        let r = g.render();
        // exactly four '@' marks for the active O
        assert_eq!(r.chars().filter(|&c| c == '@').count(), 4);
        // HEIGHT rows joined by HEIGHT-1 newlines
        assert_eq!(r.lines().count(), HEIGHT);
    }

    #[test]
    fn board_str_dimensions() {
        let g = Game::new(1);
        let b = g.board_str();
        assert_eq!(b.lines().count(), HEIGHT);
        for line in b.lines() {
            assert_eq!(line.chars().count(), WIDTH);
        }
    }

    // ── piece geometry sanity ──────────────────────────────────────────────────
    #[test]
    fn every_piece_has_four_cells_in_every_rotation() {
        for &p in &Piece::ALL {
            for rot in 0..4u8 {
                let cells = p.cells(rot);
                assert_eq!(cells.len(), 4, "{:?} rot {rot}", p);
                // all four cells distinct
                let mut v = cells.to_vec();
                v.sort();
                v.dedup();
                assert_eq!(v.len(), 4, "{:?} rot {rot} has duplicate cells", p);
                // within a 4x4 bounding box
                for (cx, cy) in cells {
                    assert!((0..4).contains(&cx) && (0..4).contains(&cy));
                }
            }
        }
    }

    #[test]
    fn o_piece_is_rotation_invariant() {
        let mut g = Game::new(1);
        g.set_active(Piece::O, 0, 4, 5);
        let cells0 = g.active().cells();
        g.rotate_cw().unwrap();
        let cells1 = g.active().cells();
        // same occupied squares regardless of rot state
        let mut a: Vec<_> = cells0.to_vec();
        let mut b: Vec<_> = cells1.to_vec();
        a.sort();
        b.sort();
        assert_eq!(a, b, "O occupies the same cells after rotation");
    }

    // ── full scripted game stays consistent ────────────────────────────────────
    #[test]
    fn scripted_game_invariants_hold_until_terminal() {
        let mut g = Game::new(0xFACE);
        let mut moves = 0;
        loop {
            if g.is_over() {
                break;
            }
            // a small deterministic policy: nudge, rotate, then hard drop
            let _ = g.move_left();
            let _ = g.rotate_cw();
            // locked-cell count is always a multiple of 4 minus cleared lines'
            // worth — just assert it never exceeds the board capacity.
            let filled: usize = (0..HEIGHT)
                .flat_map(|r| (0..WIDTH).map(move |c| (c, r)))
                .filter(|&(c, r)| g.filled(c, r))
                .count();
            assert!(filled <= WIDTH * HEIGHT);
            // score and lines never decrease
            let s_before = g.score();
            let l_before = g.lines();
            let _ = g.hard_drop();
            assert!(g.score() >= s_before);
            assert!(g.lines() >= l_before);
            moves += 1;
            assert!(moves < 5000, "game must terminate");
        }
        assert!(moves > 0);
        assert!(g.status().is_terminal());
    }
}
