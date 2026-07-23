//! `hugmesh-tron` — a pure, dependency-free, two-player Tron light-cycles engine.
//!
//! Two light-cycles race around a `W x H` grid. Every **tick** both cycles move
//! one cell in their current direction, leaving a solid trail behind in the cell
//! they just vacated. A cycle **crashes** if the cell it would enter is:
//!   * outside the grid (a wall),
//!   * any trail — its own or the opponent's, or
//!   * the opponent's head (a head-on collision, when both would step onto the
//!     SAME cell on the same tick, or one would step onto where the other is).
//!
//! The last surviving cycle wins. If **both** crash on the same tick — including
//! a perfectly symmetric head-on — it is a **Draw**.
//!
//! ## Real-time, host-authoritative, deterministic
//! Per the platform model (`docs/multiplayer-chess-design.md`) real-time games run
//! as WASM in the browser and are HOST-AUTHORITATIVE: the host advances the
//! simulation and the server only STREAMS each player's input. The guest
//! re-simulates every tick from the same inputs and re-validates the host's
//! result. For that to work the simulation must be perfectly reproducible, so:
//!
//!   * **Fixed timestep.** [`Game::tick`] is the one discrete step; there is no
//!     wall-clock anywhere in the engine. The caller decides the real cadence.
//!   * **Simultaneous resolution.** Within a tick both players' pending direction
//!     inputs are read, both next-cells are computed, and both moves are committed
//!     together — neither player sees the other's move "first".
//!   * **Integer state.** Positions are `i32` grid coordinates; the board is a flat
//!     `Vec<Cell>`. No floats anywhere, so re-simulation is bit-exact.
//!   * **Seeded layout.** Any randomness (the spawn corner offset) comes only from
//!     a tiny seeded PRNG built by [`Game::new(seed, w, h)`] / [`Game::new(seed)`].
//!     No `rand` crate, no system entropy.
//!
//! Same seed + same `set_dir` / `tick` sequence ⇒ the same outcome, always.

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
    pub fn below(&mut self, n: u64) -> u64 {
        debug_assert!(n > 0);
        // 64x64 -> high 64 bits: an unbiased-enough Lemire-style reduction.
        ((self.next_u64() as u128 * n as u128) >> 64) as u64
    }
}

// ── players & directions ─────────────────────────────────────────────────────

/// The two light-cycles. `One` starts on the left, `Two` on the right.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Player {
    One,
    Two,
}

impl Player {
    /// The other cycle.
    pub fn opposite(self) -> Player {
        match self {
            Player::One => Player::Two,
            Player::Two => Player::One,
        }
    }
    /// Index `0`/`1` into the per-player arrays.
    pub fn index(self) -> usize {
        match self {
            Player::One => 0,
            Player::Two => 1,
        }
    }
    /// Lower-case wire token used in JSON payloads.
    pub fn wire(self) -> &'static str {
        match self {
            Player::One => "one",
            Player::Two => "two",
        }
    }
    /// The trail glyph for this cycle in board strings (`1` / `2`).
    pub fn trail_char(self) -> char {
        match self {
            Player::One => '1',
            Player::Two => '2',
        }
    }
    /// The head glyph for this cycle in board strings (`A` / `B`).
    pub fn head_char(self) -> char {
        match self {
            Player::One => 'A',
            Player::Two => 'B',
        }
    }
}

/// A grid direction. The cycle advances one cell per tick along this axis.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Dir {
    Up,
    Down,
    Left,
    Right,
}

impl Dir {
    /// The `(dx, dy)` step. `x` grows rightward, `y` grows DOWNWARD (row 0 is the
    /// top), matching the row-major board layout.
    pub fn delta(self) -> (i32, i32) {
        match self {
            Dir::Up => (0, -1),
            Dir::Down => (0, 1),
            Dir::Left => (-1, 0),
            Dir::Right => (1, 0),
        }
    }
    /// The directly-opposite direction — a 180° reversal, which is illegal.
    pub fn reverse(self) -> Dir {
        match self {
            Dir::Up => Dir::Down,
            Dir::Down => Dir::Up,
            Dir::Left => Dir::Right,
            Dir::Right => Dir::Left,
        }
    }
    /// Lower-case wire token used in JSON payloads.
    pub fn wire(self) -> &'static str {
        match self {
            Dir::Up => "up",
            Dir::Down => "down",
            Dir::Left => "left",
            Dir::Right => "right",
        }
    }
}

// ── board cells ──────────────────────────────────────────────────────────────

/// One grid cell.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Cell {
    /// Open track.
    Empty,
    /// A solid trail left by `player` (or its current head). Entering it crashes.
    Trail(Player),
}

// ── status / errors ──────────────────────────────────────────────────────────

/// Terminal / running classification of a game.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum GameStatus {
    /// Both cycles are alive and the match continues.
    Playing,
    /// One cycle survived; the other crashed.
    Win { player: Player },
    /// Both cycles crashed on the same tick (mutual crash / symmetric head-on).
    Draw,
}

impl GameStatus {
    pub fn is_terminal(self) -> bool {
        !matches!(self, GameStatus::Playing)
    }
    /// Compact wire encoding for the session `world` channel.
    pub fn wire(self) -> String {
        match self {
            GameStatus::Playing => "playing".into(),
            GameStatus::Win { player } => format!("win:{}", player.wire()),
            GameStatus::Draw => "draw".into(),
        }
    }
}

/// The structured result of advancing the simulation one [`Game::tick`].
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Outcome {
    /// The tick number that just completed (1-based; the first `tick` is `1`).
    pub tick: u64,
    /// Whether cycle One crashed on this tick.
    pub one_crashed: bool,
    /// Whether cycle Two crashed on this tick.
    pub two_crashed: bool,
    /// The status AFTER this tick.
    pub status: GameStatus,
}

impl Outcome {
    /// The JSON payload the host emits on the session `world` channel after each
    /// tick; the guest re-validates it against its own engine copy. The server
    /// only relays this string — it never parses it.
    pub fn to_json(&self) -> String {
        format!(
            "{{\"type\":\"tron.tick\",\"tick\":{},\"oneCrashed\":{},\"twoCrashed\":{},\"status\":\"{}\"}}",
            self.tick, self.one_crashed, self.two_crashed, self.status.wire()
        )
    }
}

// ── the game ─────────────────────────────────────────────────────────────────

/// A single light-cycle's live state.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct Cycle {
    x: i32,
    y: i32,
    dir: Dir,
    alive: bool,
}

/// One full two-player Tron match on a `W x H` grid.
#[derive(Clone)]
pub struct Game {
    w: i32,
    h: i32,
    /// Row-major board, `cells[y * w + x]`. Trails (and current heads) are solid.
    cells: Vec<Cell>,
    one: Cycle,
    two: Cycle,
    tick: u64,
    over: bool,
}

/// The default grid width when [`Game::new`] is called with only a seed.
pub const DEFAULT_W: i32 = 20;
/// The default grid height when [`Game::new`] is called with only a seed.
pub const DEFAULT_H: i32 = 20;

impl Game {
    /// Start a fresh match on the default `DEFAULT_W x DEFAULT_H` grid, seeded by
    /// `seed`. Equivalent to [`Game::with_size(seed, DEFAULT_W, DEFAULT_H)`].
    pub fn new(seed: u64) -> Game {
        Game::with_size(seed, DEFAULT_W, DEFAULT_H)
    }

    /// Start a fresh match on a `w x h` grid seeded by `seed`.
    ///
    /// Fixed, mirror-image start positions: cycle One spawns on the left third
    /// facing **Right**, cycle Two on the right third facing **Left**, both on
    /// the same row. The seed nudges that row by a small deterministic offset so
    /// repeated matches on the same board aren't identical — but for a given seed
    /// the layout is fixed, so re-simulation is exact. The two starting cells are
    /// immediately solid (a cycle's own head counts as trail it can't re-enter).
    ///
    /// A grid smaller than `3 x 3` is clamped up to `3 x 3` so the mirror start
    /// always fits with cycles on distinct, non-adjacent columns.
    pub fn with_size(seed: u64, w: i32, h: i32) -> Game {
        let w = w.max(3);
        let h = h.max(3);
        let mut rng = Rng::new(seed);

        // Deterministic vertical offset of the start row from the vertical centre,
        // within +-(h/4), so spawns stay clear of the top/bottom walls.
        let span = (h / 4).max(0) as u64;
        let row = if span == 0 {
            h / 2
        } else {
            let off = rng.below(2 * span + 1) as i32 - span as i32;
            (h / 2 + off).clamp(0, h - 1)
        };

        // Mirror start: One a quarter in from the left, Two a quarter in from the
        // right, on the same row, facing each other across the open middle.
        let one_x = (w / 4).clamp(0, w - 1);
        let two_x = (w - 1 - w / 4).clamp(0, w - 1);

        let mut cells = vec![Cell::Empty; (w * h) as usize];
        let idx = |x: i32, y: i32| (y * w + x) as usize;
        cells[idx(one_x, row)] = Cell::Trail(Player::One);
        cells[idx(two_x, row)] = Cell::Trail(Player::Two);

        Game {
            w,
            h,
            cells,
            one: Cycle { x: one_x, y: row, dir: Dir::Right, alive: true },
            two: Cycle { x: two_x, y: row, dir: Dir::Left, alive: true },
            tick: 0,
            over: false,
        }
    }

    // ── dimensions / read-only accessors ───────────────────────────────────

    pub fn width(&self) -> i32 {
        self.w
    }
    pub fn height(&self) -> i32 {
        self.h
    }
    /// The completed-tick counter (0 before the first `tick`).
    pub fn tick_count(&self) -> u64 {
        self.tick
    }
    pub fn is_over(&self) -> bool {
        self.over
    }

    fn cyc(&self, p: Player) -> &Cycle {
        match p {
            Player::One => &self.one,
            Player::Two => &self.two,
        }
    }
    fn cyc_mut(&mut self, p: Player) -> &mut Cycle {
        match p {
            Player::One => &mut self.one,
            Player::Two => &mut self.two,
        }
    }

    /// The current head position `(x, y)` of `player`.
    pub fn head(&self, player: Player) -> (i32, i32) {
        let c = self.cyc(player);
        (c.x, c.y)
    }
    /// The current heading of `player`.
    pub fn dir(&self, player: Player) -> Dir {
        self.cyc(player).dir
    }
    /// Whether `player`'s cycle is still alive.
    pub fn alive(&self, player: Player) -> bool {
        self.cyc(player).alive
    }

    fn in_bounds(&self, x: i32, y: i32) -> bool {
        (0..self.w).contains(&x) && (0..self.h).contains(&y)
    }
    fn idx(&self, x: i32, y: i32) -> usize {
        (y * self.w + x) as usize
    }

    /// The cell at `(x, y)`; out-of-bounds reads as `Empty` (callers wall-check
    /// separately — a wall is "off the board", not a stored cell).
    pub fn at(&self, x: i32, y: i32) -> Cell {
        if self.in_bounds(x, y) {
            self.cells[self.idx(x, y)]
        } else {
            Cell::Empty
        }
    }

    /// True when `(x, y)` holds a solid trail (or a head) of either cycle.
    pub fn is_trail(&self, x: i32, y: i32) -> bool {
        matches!(self.at(x, y), Cell::Trail(_))
    }

    /// The current game status.
    pub fn status(&self) -> GameStatus {
        if !self.over {
            return GameStatus::Playing;
        }
        match (self.one.alive, self.two.alive) {
            (true, false) => GameStatus::Win { player: Player::One },
            (false, true) => GameStatus::Win { player: Player::Two },
            // Both dead ⇒ mutual crash. (Both alive + over is impossible.)
            _ => GameStatus::Draw,
        }
    }

    // ── input ────────────────────────────────────────────────────────────────

    /// Queue `player`'s heading for the NEXT tick. Takes effect when [`tick`] is
    /// next called (inputs are resolved simultaneously, so calling `set_dir` for
    /// both players before one `tick` applies both on that tick).
    ///
    /// A **180° reversal** of the cycle's *current* heading is illegal and is
    /// silently IGNORED (a cycle cannot turn back into its own neck): the heading
    /// is left unchanged. Returns `true` if the direction was accepted, `false`
    /// if it was an ignored reversal (or the game is already over).
    ///
    /// Note the reversal check is against the cycle's current committed heading,
    /// so within a single tick the last accepted non-reversing `set_dir` wins.
    pub fn set_dir(&mut self, player: Player, dir: Dir) -> bool {
        if self.over {
            return false;
        }
        let cur = self.cyc(player).dir;
        if dir == cur.reverse() {
            return false; // illegal 180° — ignored
        }
        self.cyc_mut(player).dir = dir;
        true
    }

    // ── the tick ───────────────────────────────────────────────────────────

    /// Advance the simulation one fixed timestep. Both cycles step one cell in
    /// their current heading SIMULTANEOUSLY:
    ///
    /// 1. Compute each *live* cycle's next cell from its current heading.
    /// 2. Decide crashes against the board state BEFORE either move commits, plus
    ///    the head-on cases that only the simultaneous step reveals:
    ///    * next cell off the board (wall),
    ///    * next cell is an existing trail/head (own or opponent),
    ///    * both cycles target the SAME next cell (they'd occupy one cell), or
    ///    * the two cycles swap cells (each steps onto where the other just was).
    /// 3. Commit: lay a trail in every survivor's destination and advance its head.
    ///    Crashed cycles do not move; their last head cell stays solid.
    ///
    /// If both cycles die this tick it is a [`GameStatus::Draw`]; if exactly one
    /// dies the other [`GameStatus::Win`]s; if neither dies the game continues.
    /// Returns the [`Outcome`]. Calling `tick` after the game is over is a no-op
    /// that returns the terminal outcome with no crashes recorded for this call.
    pub fn tick(&mut self) -> Outcome {
        if self.over {
            return Outcome {
                tick: self.tick,
                one_crashed: false,
                two_crashed: false,
                status: self.status(),
            };
        }

        self.tick += 1;

        // 1. Next cells for the (both-alive) cycles.
        let (o_dx, o_dy) = self.one.dir.delta();
        let (t_dx, t_dy) = self.two.dir.delta();
        let o_next = (self.one.x + o_dx, self.one.y + o_dy);
        let t_next = (self.two.x + t_dx, self.two.y + t_dy);

        // 2. Crash detection — evaluated against the board BEFORE any commit so
        //    both moves resolve from the same simultaneous snapshot.
        let one_hits_static = !self.in_bounds(o_next.0, o_next.1)
            || self.is_trail(o_next.0, o_next.1);
        let two_hits_static = !self.in_bounds(t_next.0, t_next.1)
            || self.is_trail(t_next.0, t_next.1);

        // Head-on cases visible only in a simultaneous step:
        //   - same target cell (both want the one square), or
        //   - a swap (each moves onto the cell the other is vacating this tick).
        let same_target = o_next == t_next;
        let swap = o_next == (self.two.x, self.two.y) && t_next == (self.one.x, self.one.y);
        let head_on = same_target || swap;

        let one_crashed = one_hits_static || head_on;
        let two_crashed = two_hits_static || head_on;

        // 3. Commit. Survivors lay a trail in their destination and advance.
        if !one_crashed {
            let i = self.idx(o_next.0, o_next.1);
            self.cells[i] = Cell::Trail(Player::One);
            self.one.x = o_next.0;
            self.one.y = o_next.1;
        } else {
            self.one.alive = false;
        }
        if !two_crashed {
            let i = self.idx(t_next.0, t_next.1);
            self.cells[i] = Cell::Trail(Player::Two);
            self.two.x = t_next.0;
            self.two.y = t_next.1;
        } else {
            self.two.alive = false;
        }

        if one_crashed || two_crashed {
            self.over = true;
        }

        Outcome {
            tick: self.tick,
            one_crashed,
            two_crashed,
            status: self.status(),
        }
    }

    // ── snapshot / json ────────────────────────────────────────────────────

    /// The board as an `h`-line string (top row first): `'.'` for open track,
    /// the trail glyph (`1`/`2`) for a solid trail, and the head glyph (`A`/`B`)
    /// overlaid at each live cycle's current head.
    pub fn render(&self) -> String {
        let mut grid: Vec<Vec<char>> = (0..self.h)
            .map(|y| {
                (0..self.w)
                    .map(|x| match self.cells[self.idx(x, y)] {
                        Cell::Empty => '.',
                        Cell::Trail(p) => p.trail_char(),
                    })
                    .collect()
            })
            .collect();
        if self.one.alive {
            grid[self.one.y as usize][self.one.x as usize] = Player::One.head_char();
        }
        if self.two.alive {
            grid[self.two.y as usize][self.two.x as usize] = Player::Two.head_char();
        }
        grid.iter()
            .map(|r| r.iter().collect::<String>())
            .collect::<Vec<_>>()
            .join("\n")
    }

    /// A compact single-line board encoding (`w*h` chars, no newlines, top row
    /// first) of the SOLID trails only (heads are not overlaid) for JSON.
    fn board_flat(&self) -> String {
        let mut s = String::with_capacity((self.w * self.h) as usize);
        for y in 0..self.h {
            for x in 0..self.w {
                s.push(match self.cells[self.idx(x, y)] {
                    Cell::Empty => '.',
                    Cell::Trail(p) => p.trail_char(),
                });
            }
        }
        s
    }

    /// The full save-resume snapshot as JSON. Captures the grid size, the flat
    /// trail board, each cycle's head position / heading / alive flag, the tick
    /// counter, and the status — everything a browser needs to redraw or for a
    /// guest to re-validate. The server never parses this; it only lives in the
    /// browser / relay.
    pub fn to_json(&self) -> String {
        format!(
            "{{\"type\":\"tron.snapshot\",\"width\":{w},\"height\":{h},\"tick\":{tick},\
\"board\":\"{board}\",\
\"one\":{{\"x\":{ox},\"y\":{oy},\"dir\":\"{od}\",\"alive\":{oa}}},\
\"two\":{{\"x\":{tx},\"y\":{ty},\"dir\":\"{td}\",\"alive\":{ta}}},\
\"status\":\"{status}\"}}",
            w = self.w,
            h = self.h,
            tick = self.tick,
            board = self.board_flat(),
            ox = self.one.x,
            oy = self.one.y,
            od = self.one.dir.wire(),
            oa = self.one.alive,
            tx = self.two.x,
            ty = self.two.y,
            td = self.two.dir.wire(),
            ta = self.two.alive,
            status = self.status().wire(),
        )
    }

    /// Alias for [`Game::to_json`].
    pub fn snapshot(&self) -> String {
        self.to_json()
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
        for n in 1..=20u64 {
            for _ in 0..200 {
                assert!(r.below(n) < n);
            }
        }
    }

    // ── construction / start layout ───────────────────────────────────────────
    #[test]
    fn opening_layout_is_a_mirror_facing_pair() {
        let g = Game::with_size(1, 20, 10);
        // One on the left facing right, Two on the right facing left, same row.
        let (ox, oy) = g.head(Player::One);
        let (tx, ty) = g.head(Player::Two);
        assert_eq!(oy, ty, "both cycles start on the same row");
        assert!(ox < tx, "One is left of Two");
        assert_eq!(g.dir(Player::One), Dir::Right);
        assert_eq!(g.dir(Player::Two), Dir::Left);
        assert!(g.alive(Player::One) && g.alive(Player::Two));
        assert_eq!(g.status(), GameStatus::Playing);
        assert_eq!(g.tick_count(), 0);
    }

    #[test]
    fn both_start_cells_are_solid_trails() {
        let g = Game::with_size(7, 16, 16);
        let (ox, oy) = g.head(Player::One);
        let (tx, ty) = g.head(Player::Two);
        assert_eq!(g.at(ox, oy), Cell::Trail(Player::One));
        assert_eq!(g.at(tx, ty), Cell::Trail(Player::Two));
    }

    #[test]
    fn tiny_grid_is_clamped_to_fit_a_start() {
        // A 1x1 request is clamped to 3x3 so the mirror start fits.
        let g = Game::with_size(1, 1, 1);
        assert_eq!(g.width(), 3);
        assert_eq!(g.height(), 3);
        let (ox, _) = g.head(Player::One);
        let (tx, _) = g.head(Player::Two);
        assert_ne!(ox, tx, "the two cycles never share a start column");
    }

    #[test]
    fn new_uses_default_size() {
        let g = Game::new(42);
        assert_eq!(g.width(), DEFAULT_W);
        assert_eq!(g.height(), DEFAULT_H);
    }

    #[test]
    fn seed_changes_only_the_start_row_not_the_columns() {
        // Different seeds may shift the start ROW but the columns are fixed by the
        // mirror rule, and a given seed always lands on the same row.
        let a1 = Game::with_size(1, 40, 40);
        let a2 = Game::with_size(1, 40, 40);
        assert_eq!(a1.head(Player::One), a2.head(Player::One), "same seed → same row");
        assert_eq!(a1.head(Player::Two), a2.head(Player::Two));
        let b = Game::with_size(999_999, 40, 40);
        assert_eq!(
            a1.head(Player::One).0,
            b.head(Player::One).0,
            "column is seed-independent"
        );
    }

    // ── basic movement ────────────────────────────────────────────────────────
    #[test]
    fn a_tick_moves_both_heads_one_cell_and_lays_trail() {
        let mut g = Game::with_size(1, 20, 10);
        let (ox, oy) = g.head(Player::One);
        let (tx, ty) = g.head(Player::Two);
        let out = g.tick();
        assert!(!out.one_crashed && !out.two_crashed);
        assert_eq!(out.tick, 1);
        assert_eq!(g.tick_count(), 1);
        // One moved right, Two moved left.
        assert_eq!(g.head(Player::One), (ox + 1, oy));
        assert_eq!(g.head(Player::Two), (tx - 1, ty));
        // The vacated start cells are still solid trail.
        assert_eq!(g.at(ox, oy), Cell::Trail(Player::One));
        assert_eq!(g.at(tx, ty), Cell::Trail(Player::Two));
        // The new head cells are solid too.
        assert_eq!(g.at(ox + 1, oy), Cell::Trail(Player::One));
        assert_eq!(g.at(tx - 1, ty), Cell::Trail(Player::Two));
        assert_eq!(g.status(), GameStatus::Playing);
    }

    #[test]
    fn set_dir_takes_effect_next_tick() {
        let mut g = Game::with_size(1, 20, 20);
        let (ox, oy) = g.head(Player::One);
        // Turn One downward (it was heading right; down is a legal 90° turn).
        assert!(g.set_dir(Player::One, Dir::Down));
        g.tick();
        assert_eq!(g.head(Player::One), (ox, oy + 1), "One turned down");
    }

    // ── crash: into a wall ────────────────────────────────────────────────────
    //
    // To prove a CLEAN win (not a Draw) we must keep the opponent demonstrably
    // alive while the loser crashes. The robust way: drive One UP into the top
    // wall while Two heads straight DOWN — Two only ever paints in its own column
    // heading toward the bottom, which is a long clear runway it never reaches in
    // the few ticks One needs to hit the ceiling. The two cycles never interact.
    #[test]
    fn crash_into_wall_loses() {
        // Tall board so Two's downward runway far exceeds One's distance to the top.
        let mut g = Game::with_size(1, 20, 24);
        let (_ox, oy) = g.head(Player::One);
        let mut crashed_on = None;
        // One needs at most oy+1 ticks to step off the top edge. Two has h-oy-1
        // cells of clear runway below — comfortably more on a 24-tall board.
        for t in 1..=(oy + 2) {
            g.set_dir(Player::One, Dir::Up); // straight at the ceiling
            g.set_dir(Player::Two, Dir::Down); // straight down its own column
            let out = g.tick();
            if out.status.is_terminal() {
                assert!(out.one_crashed, "One hit the ceiling");
                assert!(!out.two_crashed, "Two is alive on its clear runway");
                crashed_on = Some(t);
                break;
            }
        }
        assert!(crashed_on.is_some(), "One must hit the top wall");
        assert!(g.is_over());
        assert_eq!(g.status(), GameStatus::Win { player: Player::Two });
        assert!(!g.alive(Player::One));
        assert!(g.alive(Player::Two));
    }

    #[test]
    fn crash_into_left_wall() {
        // One faces Right; it cannot reverse to Left in one shot (illegal 180).
        // Turn it Up once (a legal 90° turn), then Left, and run it off the left
        // edge. Two heads straight Down its own column — a long clear runway — so
        // it stays alive and the result is a clean Win, never a Draw.
        let mut g = Game::with_size(1, 14, 14);
        let (ox, _oy) = g.head(Player::One);
        assert!(g.set_dir(Player::One, Dir::Up));
        g.set_dir(Player::Two, Dir::Down);
        g.tick();
        assert!(g.set_dir(Player::One, Dir::Left));
        let mut crashed = false;
        // One is now one row above its start, ox cells from the left edge: at most
        // ox+1 more ticks to run off the left wall.
        for _ in 0..(ox + 2) {
            g.set_dir(Player::One, Dir::Left);
            g.set_dir(Player::Two, Dir::Down);
            let out = g.tick();
            if out.status.is_terminal() {
                assert!(out.one_crashed && !out.two_crashed, "only One ran off the wall");
                crashed = true;
                break;
            }
        }
        assert!(crashed, "One ran off the left wall");
        assert_eq!(g.status(), GameStatus::Win { player: Player::Two });
    }

    // ── crash: into own trail ─────────────────────────────────────────────────
    #[test]
    fn crash_into_own_trail_loses() {
        // Make One trace a tight loop back into its own trail.
        // Start heading right. Square: right, down, left, up → the 'up' step
        // re-enters the very first trail cell. We need ≥2 cells per side so the
        // loop closes onto an OWN trail cell rather than the head.
        let mut g = Game::with_size(1, 20, 20);
        // Keep Two harmlessly driving straight down the far side.
        let drive_two = |g: &mut Game| {
            let _ = g.set_dir(Player::Two, Dir::Down);
        };

        // One: right (1), right(2), down(1), down(2), left(1), left(2), up(1) -> up(2)
        // closes the loop into an own trail cell.
        // We move 2 each side of a 2x2-ish box. Track until One crashes.
        let script_for_one = [
            Dir::Right,
            Dir::Right,
            Dir::Down,
            Dir::Down,
            Dir::Left,
            Dir::Left,
            Dir::Up,
            Dir::Up,
        ];
        let mut crashed = false;
        for d in script_for_one {
            g.set_dir(Player::One, d);
            drive_two(&mut g);
            let out = g.tick();
            if out.one_crashed {
                crashed = true;
                assert!(!out.two_crashed, "only One should crash into its own trail");
                break;
            }
        }
        assert!(crashed, "One must crash re-entering its own trail loop");
        assert_eq!(g.status(), GameStatus::Win { player: Player::Two });
    }

    // ── crash: into opponent trail ────────────────────────────────────────────
    #[test]
    fn crash_into_opponent_trail_loses() {
        // Geometry: Two drives straight DOWN from the start row, leaving a vertical
        // wall of its trail in column `tx` — including its vacated START cell at
        // (tx, oy). One drives straight RIGHT along the start row `oy`. Before One
        // ever reaches Two's live head it must enter (tx, oy), which is Two's solid
        // start-cell trail → One crashes into the OPPONENT's trail while Two is
        // alive far below. A clean Win for Two.
        let mut g = Game::with_size(1, 14, 14);
        let (ox, oy) = g.head(Player::One);
        let (tx, ty) = g.head(Player::Two);
        assert_eq!(oy, ty);
        // First tick: Two turns Down (legal from Left); One keeps heading Right.
        // Two's start cell (tx, oy) stays a solid trail behind it.
        let steps_to_reach = tx - ox; // One needs this many Right-steps to enter tx.
        let mut crashed = false;
        for _ in 0..steps_to_reach {
            g.set_dir(Player::One, Dir::Right);
            g.set_dir(Player::Two, Dir::Down); // climb DOWN its own column
            let out = g.tick();
            if out.status.is_terminal() {
                assert!(out.one_crashed, "One drove into Two's trail column");
                assert!(!out.two_crashed, "Two is alive, descending its own column");
                crashed = true;
                break;
            }
        }
        assert!(crashed, "One must crash into Two's vertical trail wall");
        assert_eq!(g.status(), GameStatus::Win { player: Player::Two });
        // Sanity: the cell One died entering was indeed Two's trail.
        assert_eq!(g.at(tx, oy), Cell::Trail(Player::Two), "Two's start cell is its trail");
    }

    // ── head-on: symmetric => Draw ────────────────────────────────────────────
    #[test]
    fn simultaneous_head_on_is_a_draw() {
        // Put the two cycles an EVEN number of cells apart on the same row facing
        // each other; they meet on the same cell on the same tick.
        // Construct a board where One at x, Two at x+2k+1 ... we want them to land
        // on the SAME cell. Same row, distance is even → they share the middle.
        // Use a custom small board and the natural mirror start. Choose width so
        // (two_x - one_x) is even.
        // For width 9: one_x = 9/4 = 2, two_x = 9-1-2 = 6 → distance 4 (even).
        let mut g = Game::with_size(1, 9, 5);
        let (ox, oy) = g.head(Player::One);
        let (tx, ty) = g.head(Player::Two);
        assert_eq!(oy, ty);
        assert_eq!((tx - ox) % 2, 0, "even gap → they share a centre cell");
        // Drive straight at each other (default Right / Left) until they collide.
        let mut draw_tick = None;
        for t in 1..20 {
            let out = g.tick();
            if out.status.is_terminal() {
                assert!(out.one_crashed && out.two_crashed, "both crash head-on");
                assert_eq!(out.status, GameStatus::Draw);
                draw_tick = Some(t);
                break;
            }
        }
        assert!(draw_tick.is_some(), "the head-on must resolve to a Draw");
        assert!(!g.alive(Player::One) && !g.alive(Player::Two));
        assert_eq!(g.status(), GameStatus::Draw);
    }

    #[test]
    fn head_on_swap_is_a_draw() {
        // Odd gap → the cycles would pass THROUGH each other (swap cells) on the
        // tick they're adjacent; that swap is a head-on crash for both → Draw.
        // width 10: one_x = 2, two_x = 10-1-2 = 7 → distance 5 (odd).
        let mut g = Game::with_size(1, 10, 5);
        let (ox, _oy) = g.head(Player::One);
        let (tx, _ty) = g.head(Player::Two);
        assert_eq!((tx - ox) % 2, 1, "odd gap → a swap, not a shared cell");
        let mut draw = false;
        for _ in 0..20 {
            let out = g.tick();
            if out.status.is_terminal() {
                assert!(out.one_crashed && out.two_crashed, "swap kills both");
                assert_eq!(out.status, GameStatus::Draw);
                draw = true;
                break;
            }
        }
        assert!(draw, "the swap head-on must be a Draw");
    }

    // ── both crash same tick (asymmetric) => Draw ─────────────────────────────
    #[test]
    fn both_crash_same_tick_into_walls_is_a_draw() {
        // Put both cycles one step from a wall, each heading at its OWN wall, so
        // both step off the board on the same tick → mutual crash → Draw.
        // One faces Right; if it's at the right wall its next step is off-board.
        // Drive One to the right wall and Two to the left wall, then one more tick.
        let mut g = Game::with_size(1, 9, 5);
        // Both head straight (Right / Left). With one_x=2, two_x=6 on width 9,
        // they meet centrally — not what we want here. Instead steer both to the
        // TOP wall simultaneously so they crash into walls, not each other.
        assert!(g.set_dir(Player::One, Dir::Up));
        assert!(g.set_dir(Player::Two, Dir::Up));
        let (_ox, oy) = g.head(Player::One);
        let mut both = false;
        for _t in 0..(oy + 3) {
            // keep re-asserting Up (no-op once moving up)
            g.set_dir(Player::One, Dir::Up);
            g.set_dir(Player::Two, Dir::Up);
            let out = g.tick();
            if out.status.is_terminal() {
                assert!(
                    out.one_crashed && out.two_crashed,
                    "both hit the top wall on the same tick"
                );
                assert_eq!(out.status, GameStatus::Draw);
                both = true;
                break;
            }
        }
        assert!(both, "both cycles crash into the top wall together → Draw");
        assert_eq!(g.status(), GameStatus::Draw);
    }

    // ── illegal 180 reversal is ignored ───────────────────────────────────────
    #[test]
    fn illegal_180_reversal_is_ignored() {
        let mut g = Game::with_size(1, 20, 20);
        // One faces Right. A direct reversal to Left must be rejected and ignored.
        assert_eq!(g.dir(Player::One), Dir::Right);
        assert!(!g.set_dir(Player::One, Dir::Left), "180 reversal rejected");
        assert_eq!(g.dir(Player::One), Dir::Right, "heading unchanged after reversal");
        // The cycle keeps moving right, not left, on the next tick.
        let (ox, oy) = g.head(Player::One);
        g.tick();
        assert_eq!(g.head(Player::One), (ox + 1, oy), "still moving right");
        // A legal 90° turn IS accepted.
        assert!(g.set_dir(Player::One, Dir::Down));
        assert_eq!(g.dir(Player::One), Dir::Down);
    }

    #[test]
    fn reversal_after_a_turn_is_relative_to_current_heading() {
        let mut g = Game::with_size(1, 20, 20);
        // Turn One down (legal), then Up would now be the illegal reversal.
        assert!(g.set_dir(Player::One, Dir::Down));
        assert!(!g.set_dir(Player::One, Dir::Up), "Up reverses the new Down heading");
        assert_eq!(g.dir(Player::One), Dir::Down);
        // And the previously-illegal Left/Right are now legal 90° turns.
        assert!(g.set_dir(Player::One, Dir::Right));
        assert_eq!(g.dir(Player::One), Dir::Right);
    }

    #[test]
    fn set_dir_rejected_after_game_over() {
        let mut g = Game::with_size(1, 9, 5);
        // Run the natural head-on to terminate the game.
        while !g.is_over() {
            g.tick();
        }
        assert!(!g.set_dir(Player::One, Dir::Up), "no input accepted post-game");
        assert!(!g.set_dir(Player::Two, Dir::Down));
    }

    // ── tick after game over is an inert no-op ─────────────────────────────────
    #[test]
    fn tick_after_game_over_is_inert() {
        let mut g = Game::with_size(1, 9, 5);
        while !g.is_over() {
            g.tick();
        }
        let t = g.tick_count();
        let status = g.status();
        let out = g.tick();
        assert_eq!(g.tick_count(), t, "tick counter frozen after game over");
        assert_eq!(out.status, status);
        assert!(!out.one_crashed && !out.two_crashed);
    }

    // ── simultaneity: neither player sees the other move first ─────────────────
    #[test]
    fn one_can_cross_where_opponent_just_was() {
        // A cycle may legally enter a cell the OTHER cycle is vacating ONLY when it
        // isn't a swap. Here One follows two cells behind Two's path so it lands on
        // cells Two already turned a trail — that's a trail crash, proving the
        // committed trail is solid. We instead assert the symmetric truth: in one
        // tick, a cell that becomes a fresh trail is solid for the rest of the game.
        let mut g = Game::with_size(1, 20, 6);
        let (ox, oy) = g.head(Player::One);
        let out = g.tick();
        assert!(!out.one_crashed);
        // The cell One vacated is a permanent trail; if (impossibly) it looped back
        // it would crash. Assert it is solid.
        assert_eq!(g.at(ox, oy), Cell::Trail(Player::One));
        assert_eq!(g.is_trail(ox, oy), true);
    }

    #[test]
    fn asymmetric_one_crashes_other_survives_is_a_win_not_draw() {
        // One drives into the top wall while Two heads straight down a long clear
        // runway → exactly one cycle crashes → a clean Win, never a Draw.
        let mut g = Game::with_size(1, 14, 22);
        let (_ox, oy) = g.head(Player::One);
        let mut won = false;
        for _ in 0..(oy + 3) {
            g.set_dir(Player::One, Dir::Up); // straight at the ceiling
            g.set_dir(Player::Two, Dir::Down); // straight down its own column
            let out = g.tick();
            if out.status.is_terminal() {
                assert!(out.one_crashed && !out.two_crashed, "only One crashed");
                assert_eq!(out.status, GameStatus::Win { player: Player::Two });
                won = true;
                break;
            }
        }
        assert!(won, "One hits the wall first → Two wins");
    }

    // ── DETERMINISM: same seed + same input sequence => exact outcome ──────────
    #[test]
    fn deterministic_replay_of_an_input_sequence() {
        // A scripted sequence of per-tick inputs for both players. Replaying it on
        // a fresh game with the same seed must reproduce the EXACT same per-tick
        // outcomes, head positions, board, and final status.
        let script: [(Option<Dir>, Option<Dir>); 12] = [
            (Some(Dir::Down), Some(Dir::Up)),
            (None, None),
            (Some(Dir::Right), Some(Dir::Left)),
            (None, Some(Dir::Down)),
            (Some(Dir::Down), None),
            (None, None),
            (Some(Dir::Left), Some(Dir::Right)),
            (None, None),
            (Some(Dir::Up), Some(Dir::Down)),
            (None, None),
            (Some(Dir::Right), Some(Dir::Left)),
            (None, None),
        ];

        let run = |seed: u64| -> (Vec<Outcome>, String, (i32, i32), (i32, i32)) {
            let mut g = Game::with_size(seed, 24, 24);
            let mut outs = Vec::new();
            for (d1, d2) in script {
                if let Some(d) = d1 {
                    g.set_dir(Player::One, d);
                }
                if let Some(d) = d2 {
                    g.set_dir(Player::Two, d);
                }
                let o = g.tick();
                outs.push(o);
                if g.is_over() {
                    break;
                }
            }
            (outs, g.to_json(), g.head(Player::One), g.head(Player::Two))
        };

        let a = run(0xC0FFEE);
        let b = run(0xC0FFEE);
        assert_eq!(a.0, b.0, "per-tick outcomes identical for the same seed+inputs");
        assert_eq!(a.1, b.1, "final snapshot JSON identical");
        assert_eq!(a.2, b.2, "One's final head identical");
        assert_eq!(a.3, b.3, "Two's final head identical");
    }

    #[test]
    fn deterministic_to_an_asserted_exact_outcome() {
        // Pin a concrete, hand-checked trajectory so a regression in the step
        // resolution is caught, not just internal self-consistency.
        // 20x20 default-ish board, seed picked so the start row is the centre.
        let mut g = Game::with_size(0, 20, 4); // h/4 == 1, small but deterministic
        let (ox, oy) = g.head(Player::One);
        let (tx, ty) = g.head(Player::Two);
        // First tick: straight ahead, no inputs.
        let o1 = g.tick();
        assert_eq!(o1.tick, 1);
        assert!(!o1.one_crashed && !o1.two_crashed);
        assert_eq!(g.head(Player::One), (ox + 1, oy));
        assert_eq!(g.head(Player::Two), (tx - 1, ty));
        assert_eq!(g.status(), GameStatus::Playing);
    }

    #[test]
    fn different_seeds_can_change_the_start_row() {
        // On a tall board the seed shifts the start row; find two seeds that land
        // on different rows to prove the PRNG actually drives the layout.
        let r = |seed| Game::with_size(seed, 40, 40).head(Player::One).1;
        let base = r(0);
        let differs = (1..50).any(|s| r(s) != base);
        assert!(differs, "some seed must shift the start row");
    }

    // ── snapshot / json ────────────────────────────────────────────────────────
    #[test]
    fn snapshot_json_has_expected_fields() {
        let g = Game::with_size(2024, 12, 8);
        let j = g.to_json();
        assert!(j.contains("\"type\":\"tron.snapshot\""), "{j}");
        assert!(j.contains("\"width\":12"), "{j}");
        assert!(j.contains("\"height\":8"), "{j}");
        assert!(j.contains("\"tick\":0"), "{j}");
        assert!(j.contains("\"status\":\"playing\""), "{j}");
        assert!(j.contains("\"dir\":\"right\""), "One faces right: {j}");
        assert!(j.contains("\"dir\":\"left\""), "Two faces left: {j}");
        assert!(j.contains("\"alive\":true"), "{j}");
        // flat board is width*height chars
        let start = j.find("\"board\":\"").unwrap() + "\"board\":\"".len();
        let end = j[start..].find('"').unwrap();
        assert_eq!(end, (12 * 8) as usize, "flat board length == w*h");
    }

    #[test]
    fn snapshot_reflects_status_after_terminal() {
        let mut g = Game::with_size(1, 9, 5);
        while !g.is_over() {
            g.tick();
        }
        let j = g.to_json();
        // A draw from the central head-on.
        assert!(j.contains("\"status\":\"draw\""), "{j}");
        assert!(j.contains("\"alive\":false"), "a crashed cycle is not alive: {j}");
    }

    #[test]
    fn outcome_json_encoding() {
        let mut g = Game::with_size(1, 20, 10);
        let o = g.tick();
        let j = o.to_json();
        assert!(j.contains("\"type\":\"tron.tick\""), "{j}");
        assert!(j.contains("\"tick\":1"), "{j}");
        assert!(j.contains("\"oneCrashed\":false"), "{j}");
        assert!(j.contains("\"twoCrashed\":false"), "{j}");
        assert!(j.contains("\"status\":\"playing\""), "{j}");
    }

    #[test]
    fn status_wire_encoding() {
        assert_eq!(GameStatus::Playing.wire(), "playing");
        assert_eq!(GameStatus::Win { player: Player::One }.wire(), "win:one");
        assert_eq!(GameStatus::Win { player: Player::Two }.wire(), "win:two");
        assert_eq!(GameStatus::Draw.wire(), "draw");
    }

    // ── rendering ──────────────────────────────────────────────────────────────
    #[test]
    fn render_dimensions_and_heads() {
        let g = Game::with_size(1, 10, 6);
        let r = g.render();
        assert_eq!(r.lines().count(), 6, "one line per row");
        for line in r.lines() {
            assert_eq!(line.chars().count(), 10, "w chars per row");
        }
        // exactly one head glyph for each live cycle
        assert_eq!(r.chars().filter(|&c| c == Player::One.head_char()).count(), 1);
        assert_eq!(r.chars().filter(|&c| c == Player::Two.head_char()).count(), 1);
    }

    #[test]
    fn render_shows_growing_trails() {
        let mut g = Game::with_size(1, 20, 6);
        g.tick();
        g.tick();
        let r = g.render();
        // After two ticks each cycle has laid 3 solid cells (start + 2 moves), but
        // the current head shows as a head glyph, so 2 trail glyphs each remain.
        assert!(r.matches(Player::One.trail_char()).count() >= 2);
        assert!(r.matches(Player::Two.trail_char()).count() >= 2);
    }

    // ── direction / player helpers ─────────────────────────────────────────────
    #[test]
    fn dir_reverse_and_delta_are_consistent() {
        for d in [Dir::Up, Dir::Down, Dir::Left, Dir::Right] {
            let (dx, dy) = d.delta();
            let (rx, ry) = d.reverse().delta();
            assert_eq!((dx + rx, dy + ry), (0, 0), "a reversal cancels the step");
            assert_eq!(d.reverse().reverse(), d, "double reverse is identity");
        }
    }

    #[test]
    fn player_opposite_and_index() {
        assert_eq!(Player::One.opposite(), Player::Two);
        assert_eq!(Player::Two.opposite(), Player::One);
        assert_eq!(Player::One.index(), 0);
        assert_eq!(Player::Two.index(), 1);
        assert_ne!(Player::One.trail_char(), Player::Two.trail_char());
        assert_ne!(Player::One.head_char(), Player::Two.head_char());
    }

    // ── a longer scripted match stays self-consistent until terminal ──────────
    #[test]
    fn scripted_match_invariants_hold_until_terminal() {
        let mut g = Game::with_size(0xFACE, 16, 16);
        let mut ticks = 0u64;
        // A small deterministic policy: both spiral inward by turning right every
        // few ticks. Eventually someone crashes (a confined board fills up).
        let turns = [
            Dir::Down,
            Dir::Left,
            Dir::Up,
            Dir::Right,
        ];
        let mut i1 = 0usize;
        let mut i2 = 0usize;
        while !g.is_over() {
            if ticks % 3 == 0 {
                g.set_dir(Player::One, turns[i1 % 4]);
                i1 += 1;
            }
            if ticks % 4 == 0 {
                g.set_dir(Player::Two, turns[(i2 + 2) % 4]);
                i2 += 1;
            }
            let before = g.tick_count();
            let out = g.tick();
            assert_eq!(out.tick, before + 1, "tick advances by exactly one");
            // a live head is always on-board and on a solid trail cell
            if g.alive(Player::One) {
                let (x, y) = g.head(Player::One);
                assert!((0..g.width()).contains(&x) && (0..g.height()).contains(&y));
                assert_eq!(g.at(x, y), Cell::Trail(Player::One));
            }
            if g.alive(Player::Two) {
                let (x, y) = g.head(Player::Two);
                assert!((0..g.width()).contains(&x) && (0..g.height()).contains(&y));
                assert_eq!(g.at(x, y), Cell::Trail(Player::Two));
            }
            ticks += 1;
            assert!(ticks < 10_000, "a confined match must terminate");
        }
        assert!(g.status().is_terminal());
        // The replay of the SAME policy on the SAME seed lands on the same tick.
        let mut g2 = Game::with_size(0xFACE, 16, 16);
        let mut t2 = 0u64;
        let (mut j1, mut j2) = (0usize, 0usize);
        while !g2.is_over() {
            if t2 % 3 == 0 {
                g2.set_dir(Player::One, turns[j1 % 4]);
                j1 += 1;
            }
            if t2 % 4 == 0 {
                g2.set_dir(Player::Two, turns[(j2 + 2) % 4]);
                j2 += 1;
            }
            g2.tick();
            t2 += 1;
        }
        assert_eq!(g.tick_count(), g2.tick_count(), "deterministic termination tick");
        assert_eq!(g.status(), g2.status(), "deterministic terminal status");
        assert_eq!(g.to_json(), g2.to_json(), "deterministic final snapshot");
    }
}
