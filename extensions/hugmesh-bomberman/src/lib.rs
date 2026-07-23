//! `hugmesh-bomberman` — a pure, dependency-free, two-player Bomberman engine.
//!
//! Two players fight on a rectangular grid of `WIDTH` × `HEIGHT` cells. The grid
//! has a classic Bomberman skeleton: an indestructible border, indestructible
//! pillars on the interior even/even lattice, and **seeded destructible blocks**
//! scattered over the remaining floor (the two players' spawn corners and their
//! immediate escape cells are always kept clear). Each tick a player issues ONE
//! action: move one cell orthogonally, place a bomb, or wait. A bomb detonates
//! after a fixed fuse of [`FUSE`] ticks, exploding in a `+` cross out to
//! [`RANGE`] cells, stopping at indestructible walls, destroying destructible
//! blocks (one deep — the block absorbs the rest of that arm), killing any player
//! standing in the blast, and **chain-detonating** any other bomb a flame cell
//! reaches. Last player alive wins; if both die on the same tick it's a Draw.
//!
//! ## Platform model — a deterministic tick simulation
//!
//! Per `docs/multiplayer-chess-design.md`, real-time games are HOST-AUTHORITATIVE
//! and run as WASM in the browser; the server only STREAMS inputs. So this engine
//! is a pure **fixed-timestep tick simulation**: [`Game::set_action`] records each
//! player's intent for the upcoming tick, and [`Game::tick`] advances the world by
//! exactly one discrete step, resolving **both players' inputs simultaneously**.
//! The host runs the authoritative copy; the guest re-runs the same engine with
//! the same streamed inputs and re-validates. There is no wall-clock time inside
//! the engine — the caller decides the real cadence.
//!
//! ## Determinism
//!
//! * The ONLY source of randomness — the destructible-block layout — is driven by
//!   a tiny seeded PRNG ([`Rng`], a 64-bit xorshift\*). Construct with
//!   [`Game::new(seed)`] and the board is fully reproducible.
//! * All state is integer (cell coordinates, fuse counters). No floats anywhere,
//!   so re-simulation is bit-exact across machines.
//! * Each tick resolves both players' inputs in a fixed, simultaneous order: bomb
//!   placement first, then movement (with swap/collision arbitration), then fuse
//!   countdown + explosions, then death checks. Same seed + same input sequence
//!   always yields the same [`Outcome`] and the same board.
//!
//! No `rand` crate, no system entropy, no `std::time` — zero external deps.

#![allow(clippy::needless_range_loop)]

// ── seeded PRNG ──────────────────────────────────────────────────────────────

/// A tiny deterministic PRNG (`xorshift64*`). The ONLY source of randomness in
/// the engine; constructed from an explicit `seed` so every board is reproducible.
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
    pub fn below(&mut self, n: u32) -> u32 {
        debug_assert!(n > 0);
        (((self.next_u64() >> 33) as u64 * n as u64) >> 31) as u32
    }

    /// Bernoulli trial: `true` with probability `pct`/100.
    pub fn chance(&mut self, pct: u32) -> bool {
        self.below(100) < pct
    }
}

// ── geometry ─────────────────────────────────────────────────────────────────

/// Board width in cells (must be odd so the pillar lattice closes on a border).
pub const WIDTH: i32 = 13;
/// Board height in cells (must be odd so the pillar lattice closes on a border).
pub const HEIGHT: i32 = 11;

/// Fuse length: a bomb placed on tick *t* detonates during the tick that brings
/// its remaining fuse to zero — i.e. on the `FUSE`-th [`Game::tick`] after it was
/// placed.
pub const FUSE: u32 = 8;

/// Explosion reach in cells out from the bomb along each of the four arms.
pub const RANGE: i32 = 2;

/// Percentage chance a free floor cell receives a destructible block during
/// seeded layout generation.
pub const FILL_PCT: u32 = 75;

/// A movement direction on the grid.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Dir {
    Up,
    Down,
    Left,
    Right,
}

impl Dir {
    /// The `(dx, dy)` step for this direction. `y` grows downward (row 0 at top).
    pub fn delta(self) -> (i32, i32) {
        match self {
            Dir::Up => (0, -1),
            Dir::Down => (0, 1),
            Dir::Left => (-1, 0),
            Dir::Right => (1, 0),
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

    /// All four directions, in canonical order.
    pub const ALL: [Dir; 4] = [Dir::Up, Dir::Down, Dir::Left, Dir::Right];
}

/// What a cell of the static terrain contains. Bombs, flames, and players are
/// tracked separately (overlaid on the floor), so a cell is one of these three.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Tile {
    /// Open floor — walkable, and a bomb's flame passes through it.
    Floor,
    /// Indestructible wall (border + interior pillars). Blocks movement and
    /// flame; never destroyed.
    Wall,
    /// Destructible block. Blocks movement; a flame destroys it (and the arm
    /// stops there, one block deep).
    Block,
}

impl Tile {
    fn glyph(self) -> char {
        match self {
            Tile::Floor => '.',
            Tile::Wall => '#',
            Tile::Block => '+',
        }
    }
}

// ── players & actions ────────────────────────────────────────────────────────

/// The two players. `P1` spawns top-left, `P2` bottom-right.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Player {
    P1,
    P2,
}

impl Player {
    /// Both players in canonical order.
    pub const ALL: [Player; 2] = [Player::P1, Player::P2];

    /// Array index (0 or 1) for internal storage.
    pub fn index(self) -> usize {
        match self {
            Player::P1 => 0,
            Player::P2 => 1,
        }
    }

    /// The other player.
    pub fn other(self) -> Player {
        match self {
            Player::P1 => Player::P2,
            Player::P2 => Player::P1,
        }
    }

    /// Lower-case wire token used in JSON payloads.
    pub fn wire(self) -> &'static str {
        match self {
            Player::P1 => "p1",
            Player::P2 => "p2",
        }
    }
}

/// A single per-tick intent for one player.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Action {
    /// Step one cell in `dir` (blocked by walls, blocks, bombs, the other player).
    Move(Dir),
    /// Place a bomb on the player's current cell.
    Bomb,
    /// Do nothing this tick.
    Wait,
}

impl Default for Action {
    fn default() -> Action {
        Action::Wait
    }
}

// ── live entities ────────────────────────────────────────────────────────────

/// A live player position + liveness flag.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Pawn {
    pub x: i32,
    pub y: i32,
    pub alive: bool,
    /// Bombs this player currently has on the board (capacity-limited).
    pub active_bombs: u32,
}

/// A ticking bomb.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Bomb {
    pub x: i32,
    pub y: i32,
    /// Ticks remaining before detonation. Decremented once per [`Game::tick`].
    pub fuse: u32,
    /// Who placed it (so capacity is returned on detonation).
    pub owner: Player,
    /// This bomb's blast reach in cells.
    pub range: i32,
}

// ── status / outcome ─────────────────────────────────────────────────────────

/// Terminal / running classification of a match.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Status {
    /// Both players are alive; the match continues.
    Playing,
    /// Exactly one player remains alive — they win.
    Win { player: Player },
    /// Both players died (on the same tick, or only one was ever standing and the
    /// other already gone leaving none): no winner.
    Draw,
}

impl Status {
    /// Whether the match has ended.
    pub fn is_terminal(self) -> bool {
        !matches!(self, Status::Playing)
    }

    /// Compact wire encoding.
    pub fn wire(self) -> String {
        match self {
            Status::Playing => "playing".into(),
            Status::Win { player } => format!("win:{}", player.wire()),
            Status::Draw => "draw".into(),
        }
    }
}

/// The result of advancing the world by exactly one [`Game::tick`].
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Outcome {
    /// The tick number that just completed (1-based; the first `tick` returns 1).
    pub tick: u64,
    /// Bombs that detonated during this tick.
    pub detonations: Vec<(i32, i32)>,
    /// Cells whose flame appeared during this tick (the union of all blasts).
    pub flames: Vec<(i32, i32)>,
    /// Destructible blocks destroyed during this tick.
    pub destroyed_blocks: Vec<(i32, i32)>,
    /// Players who died during this tick.
    pub deaths: Vec<Player>,
    /// The match status after the tick resolved.
    pub status: Status,
}

impl Outcome {
    /// Whether anything blew up this tick.
    pub fn had_explosion(&self) -> bool {
        !self.detonations.is_empty()
    }
}

/// Why a [`Game::set_action`] was rejected.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ActionError {
    /// The match is already over; no further input is accepted.
    GameOver,
    /// The targeted player is already dead and cannot act.
    PlayerDead,
}

// ── the game ─────────────────────────────────────────────────────────────────

/// Bomb capacity per player (how many of their bombs may be live at once).
pub const BOMB_CAPACITY: u32 = 1;

/// One full two-player Bomberman match: a deterministic tick simulation.
#[derive(Clone)]
pub struct Game {
    /// `tiles[y][x]` static terrain. Row 0 is the top.
    tiles: Vec<Vec<Tile>>,
    pawns: [Pawn; 2],
    bombs: Vec<Bomb>,
    /// Cells currently showing flame (cleared at the start of the next tick).
    flames: Vec<(i32, i32)>,
    /// Pending action for each player, applied on the next [`Game::tick`].
    pending: [Action; 2],
    tick_count: u64,
    status: Status,
}

impl Game {
    /// Start a fresh match seeded by `seed`. Generates the indestructible
    /// skeleton, scatters seeded destructible blocks, and spawns both players in
    /// opposite corners with their escape cells kept clear.
    pub fn new(seed: u64) -> Game {
        let mut rng = Rng::new(seed);
        let tiles = Game::generate_layout(&mut rng);

        // P1 top-left interior corner, P2 bottom-right interior corner.
        let p1 = Pawn { x: 1, y: 1, alive: true, active_bombs: 0 };
        let p2 = Pawn { x: WIDTH - 2, y: HEIGHT - 2, alive: true, active_bombs: 0 };

        Game {
            tiles,
            pawns: [p1, p2],
            bombs: Vec::new(),
            flames: Vec::new(),
            pending: [Action::Wait, Action::Wait],
            tick_count: 0,
            status: Status::Playing,
        }
    }

    /// Build the static terrain: indestructible border + interior even/even
    /// pillars, then scatter destructible blocks on the remaining floor with the
    /// spawn corners + escape lanes kept clear. Deterministic given `rng`.
    fn generate_layout(rng: &mut Rng) -> Vec<Vec<Tile>> {
        let w = WIDTH as usize;
        let h = HEIGHT as usize;
        let mut tiles = vec![vec![Tile::Floor; w]; h];

        for y in 0..HEIGHT {
            for x in 0..WIDTH {
                let cell = if x == 0 || y == 0 || x == WIDTH - 1 || y == HEIGHT - 1 {
                    // Indestructible border.
                    Tile::Wall
                } else if x % 2 == 0 && y % 2 == 0 {
                    // Interior pillar lattice.
                    Tile::Wall
                } else {
                    Tile::Floor
                };
                tiles[y as usize][x as usize] = cell;
            }
        }

        // Cells that must stay open: both spawn corners and the two cells adjacent
        // to each, so a player is never boxed in at the start.
        let clear = Game::spawn_clear_cells();

        // IMPORTANT for determinism: iterate in a fixed (row-major) order and draw
        // from the seeded PRNG once per eligible floor cell.
        for y in 1..HEIGHT - 1 {
            for x in 1..WIDTH - 1 {
                if tiles[y as usize][x as usize] != Tile::Floor {
                    continue;
                }
                if clear.iter().any(|&(cx, cy)| cx == x && cy == y) {
                    continue;
                }
                if rng.chance(FILL_PCT) {
                    tiles[y as usize][x as usize] = Tile::Block;
                }
            }
        }

        tiles
    }

    /// The cells kept free of destructible blocks at spawn: each corner plus its
    /// two orthogonal escape cells.
    fn spawn_clear_cells() -> Vec<(i32, i32)> {
        vec![
            // P1 top-left.
            (1, 1),
            (2, 1),
            (1, 2),
            // P2 bottom-right.
            (WIDTH - 2, HEIGHT - 2),
            (WIDTH - 3, HEIGHT - 2),
            (WIDTH - 2, HEIGHT - 3),
        ]
    }

    // ── read-only accessors ──────────────────────────────────────────────────

    /// The current match status.
    pub fn status(&self) -> Status {
        self.status
    }

    /// Whether the match has ended.
    pub fn is_over(&self) -> bool {
        self.status.is_terminal()
    }

    /// How many ticks have been simulated.
    pub fn tick_count(&self) -> u64 {
        self.tick_count
    }

    /// The static terrain tile at `(x, y)`. Out-of-bounds reads as [`Tile::Wall`].
    pub fn tile(&self, x: i32, y: i32) -> Tile {
        if x < 0 || y < 0 || x >= WIDTH || y >= HEIGHT {
            Tile::Wall
        } else {
            self.tiles[y as usize][x as usize]
        }
    }

    /// The pawn record for a player (position + liveness).
    pub fn pawn(&self, player: Player) -> Pawn {
        self.pawns[player.index()]
    }

    /// Whether a player is currently alive.
    pub fn is_alive(&self, player: Player) -> bool {
        self.pawns[player.index()].alive
    }

    /// The live bombs on the board.
    pub fn bombs(&self) -> &[Bomb] {
        &self.bombs
    }

    /// A live bomb at `(x, y)`, if any.
    pub fn bomb_at(&self, x: i32, y: i32) -> Option<&Bomb> {
        self.bombs.iter().find(|b| b.x == x && b.y == y)
    }

    /// Whether a live bomb occupies `(x, y)`.
    pub fn has_bomb(&self, x: i32, y: i32) -> bool {
        self.bombs.iter().any(|b| b.x == x && b.y == y)
    }

    /// Cells currently showing flame (from the most recent tick's explosions).
    pub fn flames(&self) -> &[(i32, i32)] {
        &self.flames
    }

    /// Whether `(x, y)` currently shows flame.
    pub fn is_flame(&self, x: i32, y: i32) -> bool {
        self.flames.iter().any(|&(fx, fy)| fx == x && fy == y)
    }

    /// The pending (not-yet-applied) action for a player.
    pub fn pending(&self, player: Player) -> Action {
        self.pending[player.index()]
    }

    // ── input ────────────────────────────────────────────────────────────────

    /// Record `player`'s action for the next [`Game::tick`]. Overwrites any
    /// previously-set action for the same player this tick. Rejected once the
    /// match is over, or if the player is already dead.
    ///
    /// NOTE: validity of a *move* (would it hit a wall?) is NOT checked here — it
    /// is resolved at tick time, simultaneously with the other player's move, so a
    /// blocked move simply has no effect. This keeps input recording pure and the
    /// simulation the single source of truth.
    pub fn set_action(&mut self, player: Player, action: Action) -> Result<(), ActionError> {
        if self.status.is_terminal() {
            return Err(ActionError::GameOver);
        }
        if !self.pawns[player.index()].alive {
            return Err(ActionError::PlayerDead);
        }
        self.pending[player.index()] = action;
        Ok(())
    }

    // ── the tick ─────────────────────────────────────────────────────────────

    /// Advance the world by exactly one discrete tick, resolving BOTH players'
    /// pending actions simultaneously, then ticking fuses and resolving any
    /// explosions and deaths. Returns the [`Outcome`] of this tick. Pending
    /// actions are reset to [`Action::Wait`] afterwards.
    ///
    /// Resolution order (fixed, for determinism):
    /// 1. Clear last tick's flames.
    /// 2. Bomb placements (both players) — placing does not move the player.
    /// 3. Movements (both players) with swap/collision arbitration.
    /// 4. Fuse countdown; detonate any bomb reaching zero, chain-detonating
    ///    neighbours its flame reaches; collect flame cells + destroyed blocks.
    /// 5. Kill any player standing on a flame cell.
    /// 6. Recompute status.
    pub fn tick(&mut self) -> Outcome {
        // A tick on a finished match is a no-op that reports the terminal status.
        if self.status.is_terminal() {
            return Outcome {
                tick: self.tick_count,
                detonations: Vec::new(),
                flames: Vec::new(),
                destroyed_blocks: Vec::new(),
                deaths: Vec::new(),
                status: self.status,
            };
        }

        self.tick_count += 1;

        // 1. Last tick's flames expire.
        self.flames.clear();

        // 2. Bomb placements first (in fixed player order), so a player can place
        //    a bomb and then NOT move off it (placement and movement are one tick
        //    apart only if the player issues separate actions). Placing is its own
        //    action; a Move is a separate action.
        for &p in &Player::ALL {
            if let Action::Bomb = self.pending[p.index()] {
                self.try_place_bomb(p);
            }
        }

        // 3. Movements, resolved simultaneously.
        self.resolve_moves();

        // 4. Fuses + explosions (with chains).
        let (detonations, flames, destroyed_blocks) = self.resolve_explosions();
        self.flames = flames.clone();

        // 5. Deaths: any live pawn on a flame cell dies.
        let mut deaths = Vec::new();
        for &p in &Player::ALL {
            let idx = p.index();
            if self.pawns[idx].alive {
                let (px, py) = (self.pawns[idx].x, self.pawns[idx].y);
                if flames.iter().any(|&(fx, fy)| fx == px && fy == py) {
                    self.pawns[idx].alive = false;
                    deaths.push(p);
                }
            }
        }

        // 6. Status.
        self.status = self.compute_status();

        // Reset pending actions for the next tick.
        self.pending = [Action::Wait, Action::Wait];

        Outcome {
            tick: self.tick_count,
            detonations,
            flames,
            destroyed_blocks,
            deaths,
            status: self.status,
        }
    }

    /// Attempt to place a bomb for `player` on their current cell. Fails silently
    /// (no effect) if the player is dead, at bomb capacity, or a bomb already
    /// occupies the cell.
    fn try_place_bomb(&mut self, player: Player) {
        let idx = player.index();
        if !self.pawns[idx].alive {
            return;
        }
        if self.pawns[idx].active_bombs >= BOMB_CAPACITY {
            return;
        }
        let (x, y) = (self.pawns[idx].x, self.pawns[idx].y);
        if self.has_bomb(x, y) {
            return;
        }
        self.bombs.push(Bomb { x, y, fuse: FUSE, owner: player, range: RANGE });
        self.pawns[idx].active_bombs += 1;
    }

    /// Resolve both players' movement intents simultaneously. A move is blocked by
    /// walls, destructible blocks, live bombs, or the destination being the other
    /// player's cell. If both players would land on the same empty cell, neither
    /// moves (head-on contention). A direct position swap is also disallowed.
    fn resolve_moves(&mut self) {
        // Compute each player's *desired* destination (their current cell if they
        // aren't moving / are blocked by static terrain or a bomb).
        let mut dest = [
            (self.pawns[0].x, self.pawns[0].y),
            (self.pawns[1].x, self.pawns[1].y),
        ];

        for &p in &Player::ALL {
            let idx = p.index();
            if !self.pawns[idx].alive {
                continue;
            }
            if let Action::Move(dir) = self.pending[idx] {
                let (dx, dy) = dir.delta();
                let nx = self.pawns[idx].x + dx;
                let ny = self.pawns[idx].y + dy;
                if self.is_walkable(nx, ny) {
                    dest[idx] = (nx, ny);
                }
            }
        }

        let alive0 = self.pawns[0].alive;
        let alive1 = self.pawns[1].alive;

        // Arbitrate the two destinations against each other.
        if alive0 && alive1 {
            let cur0 = (self.pawns[0].x, self.pawns[0].y);
            let cur1 = (self.pawns[1].x, self.pawns[1].y);

            // Same target cell → neither may move there.
            if dest[0] == dest[1] {
                dest[0] = cur0;
                dest[1] = cur1;
            } else {
                // Direct swap (each moving into the other's current cell) → block.
                if dest[0] == cur1 && dest[1] == cur0 {
                    dest[0] = cur0;
                    dest[1] = cur1;
                } else {
                    // One stepping into the other's *unchanged* cell is blocked.
                    if dest[0] == cur1 && dest[1] == cur1 {
                        dest[0] = cur0;
                    }
                    if dest[1] == cur0 && dest[0] == cur0 {
                        dest[1] = cur1;
                    }
                }
            }
        }

        self.pawns[0].x = dest[0].0;
        self.pawns[0].y = dest[0].1;
        self.pawns[1].x = dest[1].0;
        self.pawns[1].y = dest[1].1;
    }

    /// Whether a player may step onto `(x, y)`: in-bounds open floor with no live
    /// bomb on it. (Player-vs-player contention is handled in [`Self::resolve_moves`].)
    fn is_walkable(&self, x: i32, y: i32) -> bool {
        if self.tile(x, y) != Tile::Floor {
            return false;
        }
        !self.has_bomb(x, y)
    }

    /// Tick every bomb's fuse, detonate the ones that reach zero, chain-detonate
    /// any bomb whose cell is reached by a flame, and gather the resulting flame
    /// cells + destroyed blocks. Returns `(detonation_cells, flame_cells,
    /// destroyed_blocks)`.
    fn resolve_explosions(&mut self) -> (Vec<(i32, i32)>, Vec<(i32, i32)>, Vec<(i32, i32)>) {
        // Count down every fuse.
        for b in &mut self.bombs {
            b.fuse = b.fuse.saturating_sub(1);
        }

        // Seed the detonation queue with bombs whose fuse hit zero this tick.
        let mut exploded = vec![false; self.bombs.len()];
        let mut queue: Vec<usize> = (0..self.bombs.len()).filter(|&i| self.bombs[i].fuse == 0).collect();

        let mut detonations: Vec<(i32, i32)> = Vec::new();
        let mut flame_set: Vec<(i32, i32)> = Vec::new();
        let mut destroyed: Vec<(i32, i32)> = Vec::new();

        // Process detonations, propagating chains until the queue drains. Indices
        // are processed in ascending order via repeated scan so the result order
        // is deterministic regardless of discovery order.
        while let Some(i) = Self::pop_min_unexploded(&queue, &exploded) {
            // Remove `i` from the queue (all occurrences).
            queue.retain(|&j| j != i);
            if exploded[i] {
                continue;
            }
            exploded[i] = true;

            let bomb = self.bombs[i];
            detonations.push((bomb.x, bomb.y));
            // Return capacity to the owner.
            let owner_idx = bomb.owner.index();
            if self.pawns[owner_idx].active_bombs > 0 {
                self.pawns[owner_idx].active_bombs -= 1;
            }

            // The bomb's own cell always flames.
            Self::add_unique(&mut flame_set, bomb.x, bomb.y);

            // Each of the four arms, out to `range`, stopping at walls; a
            // destructible block flames then stops the arm; another bomb is
            // chain-queued (and the flame continues past it).
            for dir in Dir::ALL {
                let (dx, dy) = dir.delta();
                for step in 1..=bomb.range {
                    let cx = bomb.x + dx * step;
                    let cy = bomb.y + dy * step;
                    match self.tile(cx, cy) {
                        Tile::Wall => break, // hard stop, no flame.
                        Tile::Block => {
                            // Destroy the block; flame occupies its cell; arm stops.
                            self.tiles[cy as usize][cx as usize] = Tile::Floor;
                            Self::add_unique(&mut flame_set, cx, cy);
                            if !destroyed.iter().any(|&(bx, by)| bx == cx && by == cy) {
                                destroyed.push((cx, cy));
                            }
                            break;
                        }
                        Tile::Floor => {
                            Self::add_unique(&mut flame_set, cx, cy);
                            // Chain-detonate any not-yet-exploded bomb here.
                            for (j, ob) in self.bombs.iter().enumerate() {
                                if !exploded[j] && ob.x == cx && ob.y == cy && !queue.contains(&j) {
                                    queue.push(j);
                                }
                            }
                            // Flame continues through floor (and through the bomb
                            // cell) to the arm's full range.
                        }
                    }
                }
            }
        }

        // Drop the bombs that exploded, keeping the survivors.
        if exploded.iter().any(|&e| e) {
            let mut kept = Vec::with_capacity(self.bombs.len());
            for (i, b) in self.bombs.iter().enumerate() {
                if !exploded[i] {
                    kept.push(*b);
                }
            }
            self.bombs = kept;
        }

        // Deterministic ordering of the reported cell lists.
        detonations.sort();
        flame_set.sort();
        destroyed.sort();

        (detonations, flame_set, destroyed)
    }

    /// The smallest index present in `queue` that has not yet exploded, or `None`.
    fn pop_min_unexploded(queue: &[usize], exploded: &[bool]) -> Option<usize> {
        queue
            .iter()
            .copied()
            .filter(|&i| !exploded[i])
            .min()
    }

    /// Push `(x, y)` into `set` if not already present.
    fn add_unique(set: &mut Vec<(i32, i32)>, x: i32, y: i32) {
        if !set.iter().any(|&(sx, sy)| sx == x && sy == y) {
            set.push((x, y));
        }
    }

    /// Derive the match status from current liveness.
    fn compute_status(&self) -> Status {
        let a0 = self.pawns[0].alive;
        let a1 = self.pawns[1].alive;
        match (a0, a1) {
            (true, true) => Status::Playing,
            (true, false) => Status::Win { player: Player::P1 },
            (false, true) => Status::Win { player: Player::P2 },
            (false, false) => Status::Draw,
        }
    }

    // ── snapshot / json / render ─────────────────────────────────────────────

    /// The static terrain as a `HEIGHT`-line string (top row first): `'#'` wall,
    /// `'+'` destructible block, `'.'` floor. Bombs / flames / players are NOT
    /// drawn (use [`Game::render`] for an overlaid view).
    pub fn board_str(&self) -> String {
        let mut s = String::with_capacity((WIDTH as usize + 1) * HEIGHT as usize);
        for y in 0..HEIGHT {
            for x in 0..WIDTH {
                s.push(self.tiles[y as usize][x as usize].glyph());
            }
            if y + 1 < HEIGHT {
                s.push('\n');
            }
        }
        s
    }

    /// A human-readable overlaid view: terrain with bombs (`b`), flames (`*`), and
    /// players (`1`/`2`) drawn on top. Useful for tests / display. Precedence
    /// (highest first): player, flame, bomb, terrain.
    pub fn render(&self) -> String {
        let w = WIDTH as usize;
        let h = HEIGHT as usize;
        let mut grid: Vec<Vec<char>> = (0..h)
            .map(|y| (0..w).map(|x| self.tiles[y][x].glyph()).collect())
            .collect();

        for b in &self.bombs {
            grid[b.y as usize][b.x as usize] = 'b';
        }
        for &(fx, fy) in &self.flames {
            grid[fy as usize][fx as usize] = '*';
        }
        for &p in &Player::ALL {
            let pw = self.pawns[p.index()];
            if pw.alive {
                grid[pw.y as usize][pw.x as usize] = match p {
                    Player::P1 => '1',
                    Player::P2 => '2',
                };
            }
        }

        grid.iter()
            .map(|r| r.iter().collect::<String>())
            .collect::<Vec<_>>()
            .join("\n")
    }

    /// A compact single-line terrain encoding (`WIDTH*HEIGHT` chars, no newlines,
    /// top row first) for the JSON payload.
    fn board_flat(&self) -> String {
        let mut s = String::with_capacity(WIDTH as usize * HEIGHT as usize);
        for y in 0..HEIGHT {
            for x in 0..WIDTH {
                s.push(self.tiles[y as usize][x as usize].glyph());
            }
        }
        s
    }

    /// The full snapshot as JSON. Captures the terrain, both pawns, every live
    /// bomb, current flames, the tick count, and the status — everything a browser
    /// needs to redraw or re-validate. The server never parses this; it only
    /// relays inputs and the host streams this state.
    pub fn to_json(&self) -> String {
        let pawn_json = |p: Player| {
            let pw = self.pawns[p.index()];
            format!(
                "{{\"id\":\"{}\",\"x\":{},\"y\":{},\"alive\":{},\"bombs\":{}}}",
                p.wire(),
                pw.x,
                pw.y,
                pw.alive,
                pw.active_bombs
            )
        };

        let bombs_json = self
            .bombs
            .iter()
            .map(|b| {
                format!(
                    "{{\"x\":{},\"y\":{},\"fuse\":{},\"owner\":\"{}\",\"range\":{}}}",
                    b.x,
                    b.y,
                    b.fuse,
                    b.owner.wire(),
                    b.range
                )
            })
            .collect::<Vec<_>>()
            .join(",");

        let flames_json = self
            .flames
            .iter()
            .map(|&(x, y)| format!("[{},{}]", x, y))
            .collect::<Vec<_>>()
            .join(",");

        format!(
            "{{\"type\":\"bomberman.snapshot\",\"width\":{w},\"height\":{h},\
\"board\":\"{board}\",\"players\":[{p1},{p2}],\"bombs\":[{bombs}],\
\"flames\":[{flames}],\"tick\":{tick},\"status\":\"{status}\"}}",
            w = WIDTH,
            h = HEIGHT,
            board = self.board_flat(),
            p1 = pawn_json(Player::P1),
            p2 = pawn_json(Player::P2),
            bombs = bombs_json,
            flames = flames_json,
            tick = self.tick_count,
            status = self.status.wire(),
        )
    }

    /// Alias for [`Game::to_json`].
    pub fn snapshot(&self) -> String {
        self.to_json()
    }

    // ── test-only surgery helpers ────────────────────────────────────────────

    /// Force a player's position + liveness. Test-only; does not validate.
    #[cfg(test)]
    fn place_pawn(&mut self, player: Player, x: i32, y: i32) {
        let idx = player.index();
        self.pawns[idx].x = x;
        self.pawns[idx].y = y;
    }

    /// Overwrite a terrain tile. Test-only board surgery.
    #[cfg(test)]
    fn set_tile(&mut self, x: i32, y: i32, tile: Tile) {
        self.tiles[y as usize][x as usize] = tile;
    }

    /// Clear ALL destructible blocks → an open arena (border + pillars remain).
    /// Test-only; makes movement / blast geometry tests easy to reason about.
    #[cfg(test)]
    fn clear_blocks(&mut self) {
        for y in 0..HEIGHT {
            for x in 0..WIDTH {
                if self.tiles[y as usize][x as usize] == Tile::Block {
                    self.tiles[y as usize][x as usize] = Tile::Floor;
                }
            }
        }
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
        for n in 1..=20u32 {
            for _ in 0..200 {
                assert!(r.below(n) < n);
            }
        }
    }

    // ── deterministic seeded layout ──────────────────────────────────────────

    #[test]
    fn same_seed_yields_identical_layout() {
        let a = Game::new(0xC0FFEE);
        let b = Game::new(0xC0FFEE);
        assert_eq!(a.board_str(), b.board_str(), "same seed → identical board");
        // And the full snapshot matches too.
        assert_eq!(a.to_json(), b.to_json());
    }

    #[test]
    fn different_seeds_usually_differ() {
        let a = Game::new(1);
        let b = Game::new(2);
        assert_ne!(a.board_str(), b.board_str(), "distinct seeds → distinct boards");
    }

    #[test]
    fn border_and_pillars_are_indestructible_walls() {
        let g = Game::new(42);
        // Entire border is wall.
        for x in 0..WIDTH {
            assert_eq!(g.tile(x, 0), Tile::Wall);
            assert_eq!(g.tile(x, HEIGHT - 1), Tile::Wall);
        }
        for y in 0..HEIGHT {
            assert_eq!(g.tile(0, y), Tile::Wall);
            assert_eq!(g.tile(WIDTH - 1, y), Tile::Wall);
        }
        // Interior even/even pillars are walls.
        for y in 2..HEIGHT - 1 {
            for x in 2..WIDTH - 1 {
                if x % 2 == 0 && y % 2 == 0 {
                    assert_eq!(g.tile(x, y), Tile::Wall, "pillar at ({x},{y})");
                }
            }
        }
    }

    #[test]
    fn spawn_corners_and_escape_lanes_are_clear() {
        // Across many seeds the spawn cells + escape lanes must never be blocked.
        for seed in 0..200u64 {
            let g = Game::new(seed);
            for (x, y) in Game::spawn_clear_cells() {
                assert_eq!(g.tile(x, y), Tile::Floor, "seed {seed} cell ({x},{y}) not clear");
            }
            // Players start where we said.
            assert_eq!((g.pawn(Player::P1).x, g.pawn(Player::P1).y), (1, 1));
            assert_eq!(
                (g.pawn(Player::P2).x, g.pawn(Player::P2).y),
                (WIDTH - 2, HEIGHT - 2)
            );
        }
    }

    #[test]
    fn fresh_game_is_playing_with_both_alive() {
        let g = Game::new(7);
        assert_eq!(g.status(), Status::Playing);
        assert!(!g.is_over());
        assert!(g.is_alive(Player::P1));
        assert!(g.is_alive(Player::P2));
        assert_eq!(g.tick_count(), 0);
    }

    // ── movement ──────────────────────────────────────────────────────────────

    #[test]
    fn move_steps_one_cell_per_tick() {
        let mut g = Game::new(1);
        g.clear_blocks();
        let (x0, y0) = (g.pawn(Player::P1).x, g.pawn(Player::P1).y);
        g.set_action(Player::P1, Action::Move(Dir::Right)).unwrap();
        g.tick();
        assert_eq!((g.pawn(Player::P1).x, g.pawn(Player::P1).y), (x0 + 1, y0));
        assert_eq!(g.tick_count(), 1);
    }

    #[test]
    fn move_blocked_by_wall_is_a_noop() {
        let mut g = Game::new(1);
        g.clear_blocks();
        // P1 at (1,1); moving up hits the border wall at (1,0).
        g.set_action(Player::P1, Action::Move(Dir::Up)).unwrap();
        g.tick();
        assert_eq!((g.pawn(Player::P1).x, g.pawn(Player::P1).y), (1, 1), "wall blocks");
    }

    #[test]
    fn move_blocked_by_destructible_block() {
        let mut g = Game::new(1);
        g.clear_blocks();
        // Drop a block to P1's right and try to walk into it.
        g.set_tile(2, 1, Tile::Block);
        g.set_action(Player::P1, Action::Move(Dir::Right)).unwrap();
        g.tick();
        assert_eq!((g.pawn(Player::P1).x, g.pawn(Player::P1).y), (1, 1), "block blocks");
    }

    #[test]
    fn move_blocked_by_a_bomb() {
        let mut g = Game::new(1);
        g.clear_blocks();
        // P1 places a bomb on (1,1), steps right next tick, then tries to step back
        // onto the bomb — blocked.
        g.set_action(Player::P1, Action::Bomb).unwrap();
        g.tick();
        assert!(g.has_bomb(1, 1));
        g.set_action(Player::P1, Action::Move(Dir::Right)).unwrap();
        g.tick();
        assert_eq!((g.pawn(Player::P1).x, g.pawn(Player::P1).y), (2, 1));
        // Now try to walk back left onto the bomb.
        g.set_action(Player::P1, Action::Move(Dir::Left)).unwrap();
        g.tick();
        assert_eq!((g.pawn(Player::P1).x, g.pawn(Player::P1).y), (2, 1), "bomb blocks");
    }

    #[test]
    fn players_cannot_occupy_the_same_cell() {
        let mut g = Game::new(1);
        g.clear_blocks();
        // Put the two pawns two cells apart on a clear row and have them walk
        // toward each other — they should not pass through or stack.
        g.place_pawn(Player::P1, 5, 1);
        g.place_pawn(Player::P2, 7, 1);
        // Both aim for (6,1) at once → head-on contention, neither moves.
        g.set_action(Player::P1, Action::Move(Dir::Right)).unwrap();
        g.set_action(Player::P2, Action::Move(Dir::Left)).unwrap();
        g.tick();
        assert_eq!((g.pawn(Player::P1).x, g.pawn(Player::P1).y), (5, 1));
        assert_eq!((g.pawn(Player::P2).x, g.pawn(Player::P2).y), (7, 1));
    }

    #[test]
    fn players_cannot_swap_through_each_other() {
        let mut g = Game::new(1);
        g.clear_blocks();
        g.place_pawn(Player::P1, 5, 1);
        g.place_pawn(Player::P2, 6, 1);
        // Adjacent; each tries to step into the other's cell → swap is disallowed.
        g.set_action(Player::P1, Action::Move(Dir::Right)).unwrap();
        g.set_action(Player::P2, Action::Move(Dir::Left)).unwrap();
        g.tick();
        assert_eq!((g.pawn(Player::P1).x, g.pawn(Player::P1).y), (5, 1));
        assert_eq!((g.pawn(Player::P2).x, g.pawn(Player::P2).y), (6, 1));
    }

    #[test]
    fn both_players_move_simultaneously() {
        let mut g = Game::new(1);
        g.clear_blocks();
        g.set_action(Player::P1, Action::Move(Dir::Right)).unwrap();
        g.set_action(Player::P2, Action::Move(Dir::Left)).unwrap();
        g.tick();
        assert_eq!((g.pawn(Player::P1).x, g.pawn(Player::P1).y), (2, 1));
        assert_eq!(
            (g.pawn(Player::P2).x, g.pawn(Player::P2).y),
            (WIDTH - 3, HEIGHT - 2)
        );
    }

    // ── bomb placement + fuse timing ──────────────────────────────────────────

    #[test]
    fn bomb_explodes_after_exactly_the_fuse() {
        let mut g = Game::new(1);
        g.clear_blocks();
        // Place a bomb on tick 1.
        g.set_action(Player::P1, Action::Bomb).unwrap();
        let o1 = g.tick();
        assert!(g.has_bomb(1, 1), "bomb is live");
        assert!(!o1.had_explosion());

        // It should detonate on the FUSE-th tick after placement. We placed it
        // during tick 1; ticks 2..=FUSE count it down. So the (FUSE)-th tick number
        // overall — i.e. FUSE-1 more ticks — detonates it.
        for t in 2..FUSE as u64 {
            let o = g.tick();
            assert!(!o.had_explosion(), "no boom before the fuse (tick {t})");
            assert!(g.has_bomb(1, 1), "bomb still ticking at tick {t}");
        }
        let boom = g.tick();
        assert_eq!(boom.tick, FUSE as u64);
        assert!(boom.had_explosion(), "bomb detonates exactly on the fuse tick");
        assert_eq!(boom.detonations, vec![(1, 1)]);
        assert!(!g.has_bomb(1, 1), "bomb consumed by its own explosion");
    }

    #[test]
    fn bomb_capacity_limits_concurrent_bombs() {
        let mut g = Game::new(1);
        g.clear_blocks();
        g.set_action(Player::P1, Action::Bomb).unwrap();
        g.tick();
        assert_eq!(g.bombs().len(), 1);
        assert_eq!(g.pawn(Player::P1).active_bombs, 1);
        // Step away, then try to place again — at capacity, so no second bomb.
        g.set_action(Player::P1, Action::Move(Dir::Right)).unwrap();
        g.tick();
        g.set_action(Player::P1, Action::Bomb).unwrap();
        g.tick();
        assert_eq!(g.bombs().len(), 1, "capacity 1 means only one live bomb");
    }

    #[test]
    fn capacity_returns_after_detonation() {
        let mut g = Game::new(1);
        g.clear_blocks();
        g.set_action(Player::P1, Action::Bomb).unwrap();
        g.tick();
        // Walk far away so we survive our own blast.
        for _ in 0..4 {
            g.set_action(Player::P1, Action::Move(Dir::Right)).ok();
            g.tick();
        }
        // Let the first bomb detonate.
        while g.bombs().iter().any(|b| b.owner == Player::P1) {
            g.tick();
        }
        assert_eq!(g.pawn(Player::P1).active_bombs, 0, "capacity restored");
        // Now a fresh bomb may be placed.
        if g.is_alive(Player::P1) {
            g.set_action(Player::P1, Action::Bomb).unwrap();
            g.tick();
            assert_eq!(g.bombs().len(), 1);
        }
    }

    // ── blast geometry ────────────────────────────────────────────────────────

    #[test]
    fn blast_destroys_a_block_and_stops_at_a_wall() {
        let mut g = Game::new(1);
        g.clear_blocks();
        // Move P1 out of harm's way; place a bomb in a controlled spot.
        // Put a bomb at (3,3): an interior floor cell (odd,odd). To its right:
        //   (4,3) is floor (4 even,3 odd → floor), (5,3) floor.
        // Place a destructible block at (4,3) and a wall is naturally at (4,2)?
        // We'll test a horizontal arm: block at (4,3) should be destroyed and the
        // arm stops there; (5,3) must NOT flame.
        g.set_tile(4, 3, Tile::Block);
        // And confirm a wall stops an arm: the left arm from (3,3) hits floor (2,3)
        // is even? 2 even, 3 odd → floor. So put a wall to test: set (2,3) wall.
        g.set_tile(2, 3, Tile::Wall);

        // Manually place a bomb at (3,3) by moving a pawn there is fiddly; use the
        // owner-agnostic path: put P1 on (3,3) and bomb.
        g.place_pawn(Player::P1, 3, 3);
        // Move P2 far away and keep it idle.
        g.place_pawn(Player::P2, WIDTH - 2, HEIGHT - 2);
        g.set_action(Player::P1, Action::Bomb).unwrap();
        g.tick();
        // Walk P1 off the bomb so it doesn't die (range 2 reaches it otherwise).
        for _ in 0..(FUSE) {
            // Move down to escape the cross (down arm: (3,4) floor,(3,5) floor —
            // get to (3,6)+ which is out of a range-2 vertical arm).
            g.set_action(Player::P1, Action::Move(Dir::Down)).ok();
            let o = g.tick();
            if o.had_explosion() {
                // Right arm: (4,3) block destroyed, (5,3) not reached.
                assert!(
                    o.destroyed_blocks.contains(&(4, 3)),
                    "block at (4,3) destroyed: {:?}",
                    o.destroyed_blocks
                );
                assert!(o.flames.contains(&(4, 3)), "flame on the destroyed block");
                assert!(
                    !o.flames.contains(&(5, 3)),
                    "arm stops at the block; (5,3) must not flame"
                );
                // Left arm: wall at (2,3) blocks → (2,3) does NOT flame.
                assert!(!o.flames.contains(&(2, 3)), "wall stops the arm, no flame on it");
                // The terrain block is gone now.
                assert_eq!(g.tile(4, 3), Tile::Floor, "destroyed block is now floor");
                return;
            }
        }
        panic!("bomb never detonated");
    }

    #[test]
    fn blast_extends_to_full_range_over_open_floor() {
        let mut g = Game::new(1);
        g.clear_blocks();
        // Clear a horizontal lane around (3,3): pillars sit at even/even so (4,3)
        // and (2,3) are floor; range 2 reaches (1,3) and (5,3).
        g.place_pawn(Player::P1, 3, 3);
        g.place_pawn(Player::P2, WIDTH - 2, HEIGHT - 2);
        g.set_action(Player::P1, Action::Bomb).unwrap();
        g.tick();
        for _ in 0..FUSE {
            g.set_action(Player::P1, Action::Move(Dir::Down)).ok();
            let o = g.tick();
            if o.had_explosion() {
                // Centre + both horizontal arms to range 2.
                assert!(o.flames.contains(&(3, 3)));
                assert!(o.flames.contains(&(2, 3)));
                assert!(o.flames.contains(&(1, 3)));
                assert!(o.flames.contains(&(4, 3)));
                assert!(o.flames.contains(&(5, 3)));
                // Vertical arms too.
                assert!(o.flames.contains(&(3, 2)));
                assert!(o.flames.contains(&(3, 1)));
                assert!(o.flames.contains(&(3, 4)));
                assert!(o.flames.contains(&(3, 5)));
                return;
            }
        }
        panic!("bomb never detonated");
    }

    // ── killing players ───────────────────────────────────────────────────────

    #[test]
    fn blast_kills_a_player_in_range() {
        let mut g = Game::new(1);
        g.clear_blocks();
        // P1 bombs at (3,3); P2 stands one cell away at (4,3) (within range 2).
        g.place_pawn(Player::P1, 3, 3);
        g.place_pawn(Player::P2, 4, 3);
        g.set_action(Player::P1, Action::Bomb).unwrap();
        g.tick();
        // P1 flees so only P2 dies.
        let mut killed = false;
        for _ in 0..FUSE {
            g.set_action(Player::P1, Action::Move(Dir::Down)).ok();
            // P2 stays put (Wait).
            let o = g.tick();
            if o.had_explosion() {
                assert!(o.deaths.contains(&Player::P2), "P2 in the blast dies: {:?}", o.deaths);
                assert!(!g.is_alive(Player::P2));
                killed = true;
                break;
            }
        }
        assert!(killed);
        assert_eq!(g.status(), Status::Win { player: Player::P1 });
    }

    #[test]
    fn you_can_die_to_your_own_bomb() {
        let mut g = Game::new(1);
        g.clear_blocks();
        g.place_pawn(Player::P1, 3, 3);
        // Park P2 far away and let it sit.
        g.place_pawn(Player::P2, WIDTH - 2, HEIGHT - 2);
        g.set_action(Player::P1, Action::Bomb).unwrap();
        g.tick();
        // P1 stands ON the bomb the whole time (Wait) → dies to its own blast.
        let mut died = false;
        for _ in 0..FUSE {
            let o = g.tick();
            if o.had_explosion() {
                assert!(o.deaths.contains(&Player::P1), "own bomb kills you");
                assert!(!g.is_alive(Player::P1));
                died = true;
                break;
            }
        }
        assert!(died);
        // P1 dead, P2 alive → P2 wins.
        assert_eq!(g.status(), Status::Win { player: Player::P2 });
    }

    // ── chain reactions ───────────────────────────────────────────────────────

    #[test]
    fn one_bomb_chain_detonates_another() {
        let mut g = Game::new(1);
        g.clear_blocks();
        // Two bombs two cells apart on a clear lane: bomb A at (3,3), bomb B at
        // (5,3). A's right arm (range 2) reaches (5,3) → chains B even though B's
        // own fuse hasn't expired. We place A first, then B one tick later, so B's
        // fuse is strictly longer and would NOT have fired on its own this tick.
        g.place_pawn(Player::P1, 3, 3);
        g.place_pawn(Player::P2, 5, 3);
        g.set_action(Player::P1, Action::Bomb).unwrap(); // A at (3,3) on tick 1
        g.tick();
        // P2 drops B at (5,3) on tick 2 (fuse longer than A by 1).
        g.set_action(Player::P2, Action::Bomb).unwrap();
        g.tick();
        // Both players flee the cross. Walk them straight down out of range.
        let mut chained = false;
        for _ in 0..(FUSE + 2) {
            g.set_action(Player::P1, Action::Move(Dir::Down)).ok();
            g.set_action(Player::P2, Action::Move(Dir::Down)).ok();
            let o = g.tick();
            if o.detonations.contains(&(3, 3)) {
                // A fired; B must chain on the SAME tick (its cell is in A's arm).
                assert!(
                    o.detonations.contains(&(5, 3)),
                    "B chains with A in the same tick: {:?}",
                    o.detonations
                );
                assert!(g.bombs().is_empty(), "both bombs consumed");
                chained = true;
                break;
            }
        }
        assert!(chained, "the chain must trigger");
    }

    #[test]
    fn chain_reaction_can_be_three_deep() {
        let mut g = Game::new(1);
        g.clear_blocks();
        // Three bombs in a line, each within the next's range: (3,3),(5,3),(7,3).
        // Place them on successive ticks so fuses descend A<B<C; A fires and chains
        // B then C in one cascade.
        g.place_pawn(Player::P1, 3, 3);
        g.place_pawn(Player::P2, 5, 3);
        g.set_action(Player::P1, Action::Bomb).unwrap();
        g.set_action(Player::P2, Action::Bomb).unwrap();
        g.tick(); // A@(3,3), B@(5,3) — same fuse here, fine.
        // Manually inject a third bomb C at (7,3) with a long fuse via a pawn.
        g.place_pawn(Player::P1, 7, 3); // capacity is per-player; P1 already has A.
        // P1 is at capacity (A live), so we can't place a 2nd P1 bomb. Instead
        // move P2 to (7,3)? P2's B is live too. So manufacture C directly:
        g.bombs.push(Bomb { x: 7, y: 3, fuse: FUSE, owner: Player::P1, range: RANGE });
        // Flee everyone.
        g.place_pawn(Player::P1, 1, 1);
        g.place_pawn(Player::P2, 1, 9);
        let mut detonated_all = false;
        for _ in 0..(FUSE + 3) {
            let o = g.tick();
            if o.detonations.contains(&(3, 3)) {
                assert!(o.detonations.contains(&(5, 3)), "B chains");
                assert!(o.detonations.contains(&(7, 3)), "C chains off B");
                assert!(g.bombs().is_empty());
                detonated_all = true;
                break;
            }
        }
        assert!(detonated_all);
    }

    // ── win / draw ────────────────────────────────────────────────────────────

    #[test]
    fn last_player_alive_wins() {
        let mut g = Game::new(1);
        g.clear_blocks();
        g.place_pawn(Player::P1, 3, 3);
        g.place_pawn(Player::P2, 4, 3); // in range
        g.set_action(Player::P1, Action::Bomb).unwrap();
        g.tick();
        for _ in 0..FUSE {
            g.set_action(Player::P1, Action::Move(Dir::Down)).ok();
            let o = g.tick();
            if o.had_explosion() {
                break;
            }
        }
        assert_eq!(g.status(), Status::Win { player: Player::P1 });
        assert!(g.is_over());
    }

    #[test]
    fn simultaneous_death_is_a_draw() {
        let mut g = Game::new(1);
        g.clear_blocks();
        // Both pawns adjacent to a single bomb's cross so they die on the same tick.
        // Bomb at (3,3); P1 at (2,3), P2 at (4,3) — both within range 1.
        g.place_pawn(Player::P1, 2, 3);
        g.place_pawn(Player::P2, 4, 3);
        // Inject the bomb directly so neither pawn is sitting on it (they sit in
        // the arms) and they don't need to place/flee.
        g.bombs.push(Bomb { x: 3, y: 3, fuse: FUSE, owner: Player::P1, range: RANGE });
        g.pawns[0].active_bombs = 1;
        let mut both_dead = false;
        for _ in 0..FUSE {
            // Both Wait, standing in the blast arms.
            let o = g.tick();
            if o.had_explosion() {
                assert!(o.deaths.contains(&Player::P1));
                assert!(o.deaths.contains(&Player::P2));
                assert_eq!(o.deaths.len(), 2, "both die the same tick");
                both_dead = true;
                break;
            }
        }
        assert!(both_dead);
        assert_eq!(g.status(), Status::Draw);
        assert!(g.is_over());
    }

    // ── illegal input handling ────────────────────────────────────────────────

    #[test]
    fn input_rejected_after_game_over() {
        let mut g = Game::new(1);
        g.clear_blocks();
        g.place_pawn(Player::P1, 3, 3);
        g.place_pawn(Player::P2, 4, 3);
        g.set_action(Player::P1, Action::Bomb).unwrap();
        g.tick();
        for _ in 0..FUSE {
            g.set_action(Player::P1, Action::Move(Dir::Down)).ok();
            if g.tick().had_explosion() {
                break;
            }
        }
        assert!(g.is_over());
        assert_eq!(
            g.set_action(Player::P1, Action::Wait),
            Err(ActionError::GameOver)
        );
        assert_eq!(
            g.set_action(Player::P2, Action::Move(Dir::Up)),
            Err(ActionError::GameOver)
        );
    }

    #[test]
    fn input_rejected_for_a_dead_player() {
        let mut g = Game::new(1);
        g.clear_blocks();
        // Kill ONLY P2, keep P1 alive (P1 wins → game over). To exercise the
        // PlayerDead branch we need a still-running game with one dead pawn, which
        // can't happen in 2-player (one death ends it). So assert via a crafted
        // state: directly mark P2 dead but keep status Playing is impossible
        // through the public API; instead verify the GameOver guard already covers
        // the only reachable dead-player case. We still check the error variant on
        // the natural path:
        g.place_pawn(Player::P1, 3, 3);
        g.place_pawn(Player::P2, 4, 3);
        g.set_action(Player::P1, Action::Bomb).unwrap();
        g.tick();
        for _ in 0..FUSE {
            g.set_action(Player::P1, Action::Move(Dir::Down)).ok();
            if g.tick().had_explosion() {
                break;
            }
        }
        // P2 is dead; the match is over, so setting P2's action returns GameOver
        // (the terminal guard fires first, which is the correct, observable error).
        assert!(matches!(
            g.set_action(Player::P2, Action::Wait),
            Err(ActionError::GameOver)
        ));
    }

    #[test]
    fn dead_player_branch_is_reachable_in_isolation() {
        // White-box check of the PlayerDead guard independent of status: build a
        // game, mark P2 dead but force status back to Playing, and confirm the
        // guard rejects P2 while still accepting P1.
        let mut g = Game::new(1);
        g.pawns[Player::P2.index()].alive = false;
        g.status = Status::Playing; // pretend the match continues
        assert_eq!(
            g.set_action(Player::P2, Action::Wait),
            Err(ActionError::PlayerDead)
        );
        assert!(g.set_action(Player::P1, Action::Wait).is_ok());
    }

    #[test]
    fn tick_on_finished_game_is_inert() {
        let mut g = Game::new(1);
        g.clear_blocks();
        g.place_pawn(Player::P1, 3, 3);
        g.place_pawn(Player::P2, 4, 3);
        g.set_action(Player::P1, Action::Bomb).unwrap();
        g.tick();
        for _ in 0..FUSE {
            g.set_action(Player::P1, Action::Move(Dir::Down)).ok();
            if g.tick().had_explosion() {
                break;
            }
        }
        assert!(g.is_over());
        let snap_before = g.to_json();
        let o = g.tick();
        assert!(o.deaths.is_empty());
        assert!(!o.had_explosion());
        assert_eq!(o.status, g.status());
        assert_eq!(g.to_json(), snap_before, "finished game does not mutate on tick");
    }

    // ── full-game determinism ─────────────────────────────────────────────────

    /// Deterministically replay a scripted match. Same seed + same input script
    /// MUST produce the same terminal state, tick-by-tick.
    fn scripted_match(seed: u64) -> (u64, Status, String) {
        let mut g = Game::new(seed);
        // A fixed script: P1 bombs then dances down-right; P2 bombs then dances
        // up-left. Deterministic regardless of actual legality (illegal moves are
        // simply no-ops).
        let script: [(Action, Action); 6] = [
            (Action::Bomb, Action::Bomb),
            (Action::Move(Dir::Down), Action::Move(Dir::Up)),
            (Action::Move(Dir::Right), Action::Move(Dir::Left)),
            (Action::Wait, Action::Wait),
            (Action::Move(Dir::Down), Action::Move(Dir::Up)),
            (Action::Move(Dir::Right), Action::Move(Dir::Left)),
        ];
        let mut step = 0usize;
        let mut guard = 0;
        while !g.is_over() {
            let (a1, a2) = script[step % script.len()];
            // Only set actions for still-alive players (always true while !is_over).
            let _ = g.set_action(Player::P1, a1);
            let _ = g.set_action(Player::P2, a2);
            g.tick();
            step += 1;
            guard += 1;
            if guard > 5000 {
                break; // safety — a script may never end; the determinism check
                       // below still compares two identical runs.
            }
        }
        (g.tick_count(), g.status(), g.to_json())
    }

    #[test]
    fn scripted_match_is_deterministic() {
        let a = scripted_match(0xBEEF);
        let b = scripted_match(0xBEEF);
        assert_eq!(a.0, b.0, "same tick count");
        assert_eq!(a.1, b.1, "same status");
        assert_eq!(a.2, b.2, "same final snapshot");
    }

    #[test]
    fn replaying_inputs_reproduces_every_tick() {
        // Capture a per-tick log from one run; replay the SAME inputs into a fresh
        // game and assert each tick's Outcome + snapshot match exactly.
        let seed = 0x1234_5678;
        let inputs: [(Action, Action); 10] = [
            (Action::Bomb, Action::Wait),
            (Action::Move(Dir::Down), Action::Move(Dir::Up)),
            (Action::Move(Dir::Down), Action::Move(Dir::Up)),
            (Action::Move(Dir::Right), Action::Move(Dir::Left)),
            (Action::Wait, Action::Bomb),
            (Action::Move(Dir::Down), Action::Move(Dir::Up)),
            (Action::Wait, Action::Wait),
            (Action::Wait, Action::Wait),
            (Action::Wait, Action::Wait),
            (Action::Wait, Action::Wait),
        ];

        let run = || {
            let mut g = Game::new(seed);
            let mut log = Vec::new();
            for &(a1, a2) in &inputs {
                if g.is_over() {
                    break;
                }
                let _ = g.set_action(Player::P1, a1);
                let _ = g.set_action(Player::P2, a2);
                let o = g.tick();
                log.push((o, g.to_json()));
            }
            log
        };

        let log_a = run();
        let log_b = run();
        assert_eq!(log_a.len(), log_b.len());
        for (i, ((oa, ja), (ob, jb))) in log_a.iter().zip(log_b.iter()).enumerate() {
            assert_eq!(oa, ob, "tick {i} outcome differs");
            assert_eq!(ja, jb, "tick {i} snapshot differs");
        }
    }

    // ── snapshot / json ───────────────────────────────────────────────────────

    #[test]
    fn snapshot_json_has_expected_fields() {
        let g = Game::new(2024);
        let j = g.to_json();
        assert!(j.contains("\"type\":\"bomberman.snapshot\""), "{j}");
        assert!(j.contains(&format!("\"width\":{WIDTH}")), "{j}");
        assert!(j.contains(&format!("\"height\":{HEIGHT}")), "{j}");
        assert!(j.contains("\"status\":\"playing\""), "{j}");
        assert!(j.contains("\"tick\":0"), "{j}");
        assert!(j.contains("\"id\":\"p1\""), "{j}");
        assert!(j.contains("\"id\":\"p2\""), "{j}");
        assert!(j.contains("\"bombs\":[]"), "no bombs yet: {j}");
        assert!(j.contains("\"flames\":[]"), "no flames yet: {j}");
        // flat board length == WIDTH*HEIGHT.
        let start = j.find("\"board\":\"").unwrap() + "\"board\":\"".len();
        let end = j[start..].find('"').unwrap();
        assert_eq!(end, (WIDTH * HEIGHT) as usize, "flat board length");
    }

    #[test]
    fn snapshot_reflects_a_live_bomb_and_tick() {
        let mut g = Game::new(2024);
        g.clear_blocks();
        g.set_action(Player::P1, Action::Bomb).unwrap();
        g.tick();
        let j = g.to_json();
        assert!(j.contains("\"tick\":1"), "{j}");
        assert!(j.contains("\"owner\":\"p1\""), "bomb owner present: {j}");
        assert!(j.contains(&format!("\"fuse\":{}", FUSE - 1)), "fuse counted down: {j}");
    }

    #[test]
    fn status_wire_encoding() {
        assert_eq!(Status::Playing.wire(), "playing");
        assert_eq!(Status::Win { player: Player::P1 }.wire(), "win:p1");
        assert_eq!(Status::Win { player: Player::P2 }.wire(), "win:p2");
        assert_eq!(Status::Draw.wire(), "draw");
    }

    #[test]
    fn dir_and_player_wire_tokens() {
        assert_eq!(Dir::Up.wire(), "up");
        assert_eq!(Dir::Down.wire(), "down");
        assert_eq!(Dir::Left.wire(), "left");
        assert_eq!(Dir::Right.wire(), "right");
        assert_eq!(Player::P1.wire(), "p1");
        assert_eq!(Player::P2.wire(), "p2");
        assert_eq!(Player::P1.other(), Player::P2);
        assert_eq!(Player::P2.other(), Player::P1);
    }

    // ── rendering ─────────────────────────────────────────────────────────────

    #[test]
    fn render_overlays_entities() {
        let mut g = Game::new(1);
        g.clear_blocks();
        g.set_action(Player::P1, Action::Bomb).unwrap();
        g.tick();
        let r = g.render();
        assert_eq!(r.lines().count(), HEIGHT as usize);
        for line in r.lines() {
            assert_eq!(line.chars().count(), WIDTH as usize);
        }
        // Players drawn as 1 / 2; one bomb drawn as 'b'. (P1 stands on its own
        // bomb cell, so the player glyph wins precedence there.)
        assert!(r.contains('1'), "P1 glyph present");
        assert!(r.contains('2'), "P2 glyph present");
    }

    #[test]
    fn board_str_dimensions_and_border() {
        let g = Game::new(1);
        let b = g.board_str();
        assert_eq!(b.lines().count(), HEIGHT as usize);
        for line in b.lines() {
            assert_eq!(line.chars().count(), WIDTH as usize);
        }
        // Top and bottom rows are all walls.
        let lines: Vec<&str> = b.lines().collect();
        assert!(lines[0].chars().all(|c| c == '#'));
        assert!(lines[HEIGHT as usize - 1].chars().all(|c| c == '#'));
    }

    // ── tile / geometry sanity ────────────────────────────────────────────────

    #[test]
    fn out_of_bounds_reads_as_wall() {
        let g = Game::new(1);
        assert_eq!(g.tile(-1, 0), Tile::Wall);
        assert_eq!(g.tile(0, -1), Tile::Wall);
        assert_eq!(g.tile(WIDTH, 0), Tile::Wall);
        assert_eq!(g.tile(0, HEIGHT), Tile::Wall);
    }

    #[test]
    fn dir_deltas_are_unit_orthogonal() {
        for d in Dir::ALL {
            let (dx, dy) = d.delta();
            assert_eq!(dx.abs() + dy.abs(), 1, "each direction is one orthogonal step");
        }
    }

    #[test]
    fn fuse_and_range_constants_are_sane() {
        assert!(FUSE >= 1);
        assert!(RANGE >= 1);
        assert!(WIDTH % 2 == 1 && HEIGHT % 2 == 1, "odd dims close the pillar lattice");
    }
}
