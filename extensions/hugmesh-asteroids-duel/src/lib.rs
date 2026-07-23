//! `hugmesh-asteroids-duel` — a pure, dependency-free, deterministic Asteroids
//! engine. Plays as single-ship SURVIVAL or a two-ship DUEL.
//!
//! A ship rotates, thrusts, and fires; bullets destroy asteroids, which SPLIT
//! (large → 2 medium → 2 small → gone). A ship that touches any asteroid is
//! destroyed. The play-field WRAPS at every edge. Destroying an asteroid scores.
//!
//! This is the ONE engine: it compiles to `wasm32` for the in-browser game and
//! natively for `cargo test`. The platform is HOST-AUTHORITATIVE: the host runs
//! this simulation, the server only STREAMS each player's per-tick input, and the
//! guest re-simulates the same inputs to re-validate. See
//! `docs/multiplayer-chess-design.md`.
//!
//! ## Determinism
//! Everything is integer / fixed-point — there are NO floats anywhere in the
//! state path, so re-simulation is bit-exact on every platform.
//!
//! * Positions and velocities are `i32` in a fixed-point scale (`FP == 1 << 8`).
//! * Angles are integers `0..ANGLE_STEPS` indexing precomputed integer sin/cos
//!   tables; the ship turns by a whole number of steps per tick.
//! * The field wraps with plain modular arithmetic.
//! * The seeded asteroid field, asteroid split headings, and any other randomness
//!   come from a tiny seeded PRNG (`Rng`, a 64-bit xorshift\*) built from
//!   [`Game::new`]'s `seed`. No `rand` crate, no system entropy.
//! * A tick resolves ALL players' inputs SIMULTANEOUSLY: every ship reads the
//!   input set for the tick, all bodies integrate, then collisions are resolved
//!   in a fixed iteration order. Same seed + same input sequence ⇒ identical
//!   state, asserted bit-for-bit in the test-suite.

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
        Rng {
            state: if seed == 0 {
                0x9E37_79B9_7F4A_7C15
            } else {
                seed
            },
        }
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

    /// A uniform value in the inclusive range `lo..=hi` (`lo <= hi`).
    pub fn range(&mut self, lo: i32, hi: i32) -> i32 {
        debug_assert!(lo <= hi);
        lo + self.below((hi - lo + 1) as u32) as i32
    }
}

// ── fixed-point geometry ─────────────────────────────────────────────────────

/// Fixed-point fractional bits. A world unit is `FP` integer units; multiplying
/// two FP values requires a `>> FP_BITS` to renormalise.
pub const FP_BITS: i32 = 8;
/// One world unit in fixed-point representation (`1 << FP_BITS`).
pub const FP: i32 = 1 << FP_BITS;

/// Field width in **world units** (the fixed-point span is `WIDTH * FP`).
pub const WIDTH: i32 = 1024;
/// Field height in **world units**.
pub const HEIGHT: i32 = 768;

/// Number of discrete heading steps in a full turn. The ship rotates by a whole
/// number of these per tick, and the sin/cos lookup tables have this many entries.
pub const ANGLE_STEPS: i32 = 256;

/// Wrap a fixed-point coordinate into `[0, span)` with plain Euclidean modulo so
/// the field tiles seamlessly. `span` is the field extent in fixed-point units.
fn wrap(v: i32, span: i32) -> i32 {
    let m = v % span;
    if m < 0 {
        m + span
    } else {
        m
    }
}

/// The signed shortest delta from `a` to `b` on a wrapping axis of length `span`
/// (result in `(-span/2, span/2]`). Used by distance / collision so a body near
/// one edge is correctly "close" to a body near the opposite edge.
fn wrap_delta(a: i32, b: i32, span: i32) -> i32 {
    let mut d = (b - a) % span;
    if d > span / 2 {
        d -= span;
    } else if d < -span / 2 {
        d += span;
    }
    d
}

/// Integer sine of a heading step, scaled by `FP` (so the range is `-FP..=FP`).
/// A 256-entry quarter-symmetric table computed once with no floating point in
/// the state path. The table itself is a `const` of precomputed i16 values.
fn sin_fp(angle: i32) -> i32 {
    SIN_TABLE[(wrap(angle, ANGLE_STEPS)) as usize] as i32
}

/// Integer cosine: `cos(a) == sin(a + quarter-turn)`.
fn cos_fp(angle: i32) -> i32 {
    sin_fp(angle + ANGLE_STEPS / 4)
}

// ── status / errors ──────────────────────────────────────────────────────────

/// The two ships. In SURVIVAL mode only `One` exists.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Player {
    One,
    Two,
}

impl Player {
    /// The other ship.
    pub fn opposite(self) -> Player {
        match self {
            Player::One => Player::Two,
            Player::Two => Player::One,
        }
    }
    /// Wire token used in JSON payloads.
    pub fn wire(self) -> &'static str {
        match self {
            Player::One => "p1",
            Player::Two => "p2",
        }
    }
    fn index(self) -> usize {
        match self {
            Player::One => 0,
            Player::Two => 1,
        }
    }
}

/// Terminal / running classification of a game.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum GameStatus {
    /// The simulation is live (at least one ship alive, or — in a duel — the win
    /// condition is not yet decided).
    Playing,
    /// SURVIVAL: the lone ship was destroyed. Carries the final score, OR the duel
    /// ended with no winner (both ships destroyed on the same tick / field clear
    /// with everyone dead). `score` is player one's score.
    GameOver { score: u64 },
    /// DUEL: exactly one ship is left alive while the other is destroyed. Carries
    /// the surviving player.
    Win { player: Player },
}

impl GameStatus {
    /// True once the game has reached a terminal state.
    pub fn is_terminal(self) -> bool {
        !matches!(self, GameStatus::Playing)
    }

    /// Compact wire encoding for the `world`-channel payload.
    pub fn wire(self) -> String {
        match self {
            GameStatus::Playing => "playing".into(),
            GameStatus::GameOver { score } => format!("gameover:{score}"),
            GameStatus::Win { player } => format!("win:{}", player.wire()),
        }
    }
}

/// Why an action / construction was rejected.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum InputError {
    /// `set_input` named a player that does not exist in this game (e.g. `Two`
    /// in a single-ship survival game).
    NoSuchPlayer,
}

// ── input ────────────────────────────────────────────────────────────────────

/// The intended turn direction for a tick.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Turn {
    /// No rotation this tick.
    None,
    /// Rotate counter-clockwise (decreasing heading) by one rotation step.
    Left,
    /// Rotate clockwise (increasing heading) by one rotation step.
    Right,
}

impl Default for Turn {
    fn default() -> Turn {
        Turn::None
    }
}

/// One ship's input for a single tick. The host fixes this per player per tick;
/// the engine resolves every player's `Input` simultaneously in [`Game::tick`].
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct Input {
    /// Apply forward thrust along the ship's heading this tick.
    pub thrust: bool,
    /// Rotate the ship this tick.
    pub turn: Turn,
    /// Attempt to fire a bullet this tick (subject to the fire cooldown).
    pub fire: bool,
}

impl Input {
    /// An input that thrusts forward (no turn, no fire).
    pub fn thrust() -> Input {
        Input {
            thrust: true,
            ..Input::default()
        }
    }
    /// An input that fires (no thrust, no turn).
    pub fn fire() -> Input {
        Input {
            fire: true,
            ..Input::default()
        }
    }
    /// An input that turns left.
    pub fn left() -> Input {
        Input {
            turn: Turn::Left,
            ..Input::default()
        }
    }
    /// An input that turns right.
    pub fn right() -> Input {
        Input {
            turn: Turn::Right,
            ..Input::default()
        }
    }
}

// ── tuning constants (all integer / fixed-point) ─────────────────────────────

/// Heading steps turned per tick when rotating.
const TURN_RATE: i32 = 3;
/// Forward acceleration applied per thrust tick, in FP velocity units.
const THRUST_ACCEL: i32 = 12;
/// Per-tick velocity damping numerator/denominator (very light friction so the
/// integer state stays bounded). `v = v * FRICTION_NUM / FRICTION_DEN`.
const FRICTION_NUM: i32 = 255;
const FRICTION_DEN: i32 = 256;
/// Maximum ship speed magnitude per axis component contribution cap (FP units).
const MAX_SHIP_SPEED: i32 = 6 * FP;
/// Bullet speed (FP velocity units along the heading).
const BULLET_SPEED: i32 = 9 * FP;
/// Bullet lifetime in ticks before it expires.
const BULLET_LIFE: u32 = 60;
/// Ticks between shots (fire cooldown).
const FIRE_COOLDOWN: u32 = 8;
/// Ship collision radius (FP units).
const SHIP_RADIUS: i32 = 8 * FP;
/// Bullet collision radius (FP units).
const BULLET_RADIUS: i32 = 2 * FP;

/// Size class of an asteroid. Splitting goes Large → Medium → Small → gone.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Size {
    Large,
    Medium,
    Small,
}

impl Size {
    /// Collision radius in FP units for this size class.
    pub fn radius(self) -> i32 {
        match self {
            Size::Large => 36 * FP,
            Size::Medium => 20 * FP,
            Size::Small => 11 * FP,
        }
    }
    /// Points awarded when an asteroid of this size is destroyed.
    pub fn score(self) -> u64 {
        match self {
            Size::Large => 20,
            Size::Medium => 50,
            Size::Small => 100,
        }
    }
    /// The size an asteroid splits INTO, or `None` if it is destroyed outright.
    pub fn split(self) -> Option<Size> {
        match self {
            Size::Large => Some(Size::Medium),
            Size::Medium => Some(Size::Small),
            Size::Small => None,
        }
    }
    /// Base speed (FP units) for a fresh asteroid of this size — smaller is faster.
    fn base_speed(self) -> i32 {
        match self {
            Size::Large => FP,
            Size::Medium => 2 * FP,
            Size::Small => 3 * FP,
        }
    }
    fn wire(self) -> &'static str {
        match self {
            Size::Large => "L",
            Size::Medium => "M",
            Size::Small => "S",
        }
    }
}

// ── bodies ───────────────────────────────────────────────────────────────────

/// A drifting asteroid. Position / velocity are fixed-point; `heading` is unused
/// for motion but kept for rendering. Asteroids wrap and never thrust.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Asteroid {
    /// Fixed-point X position (`0..WIDTH*FP`).
    pub x: i32,
    /// Fixed-point Y position (`0..HEIGHT*FP`).
    pub y: i32,
    /// Fixed-point X velocity per tick.
    pub vx: i32,
    /// Fixed-point Y velocity per tick.
    pub vy: i32,
    /// Size class.
    pub size: Size,
}

/// A bullet fired by a ship. Lives for `BULLET_LIFE` ticks, then expires.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Bullet {
    pub x: i32,
    pub y: i32,
    pub vx: i32,
    pub vy: i32,
    /// Ticks remaining before this bullet expires.
    pub life: u32,
    /// Which ship fired it (so a duel can attribute kills / friendly fire rules).
    pub owner: Player,
}

/// A player's ship. When `alive` is false the ship is a wreck (its fields hold
/// the last live state for rendering); a destroyed ship accepts no input.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Ship {
    pub x: i32,
    pub y: i32,
    pub vx: i32,
    pub vy: i32,
    /// Heading step `0..ANGLE_STEPS` (0 points along +X).
    pub heading: i32,
    /// Ticks until this ship may fire again.
    pub cooldown: u32,
    /// False once the ship has been destroyed by an asteroid.
    pub alive: bool,
}

// ── the game ─────────────────────────────────────────────────────────────────

/// One Asteroids simulation: single-ship survival (`players == 1`) or two-ship
/// duel (`players == 2`).
#[derive(Clone)]
pub struct Game {
    rng: Rng,
    players: u8,
    ships: Vec<Ship>,
    asteroids: Vec<Asteroid>,
    bullets: Vec<Bullet>,
    inputs: Vec<Input>,
    scores: Vec<u64>,
    tick: u64,
    status: GameStatus,
}

impl Game {
    /// Start a single-ship SURVIVAL game seeded by `seed`. Equivalent to
    /// [`Game::with_players(seed, 1)`].
    pub fn new(seed: u64) -> Game {
        Game::with_players(seed, 1)
    }

    /// Start a game with `players` ships (1 = survival, 2 = duel). `players` is
    /// clamped to `1..=2`. The asteroid field is laid out from `seed`.
    pub fn with_players(seed: u64, players: u8) -> Game {
        let players = players.clamp(1, 2);
        let mut rng = Rng::new(seed);

        // Ships start at fixed, symmetric positions (NOT random) so a re-sim is
        // bit-exact regardless of how the RNG was advanced for the field.
        let mut ships = Vec::with_capacity(players as usize);
        // Player one: left-of-centre, heading +X (right).
        ships.push(Ship {
            x: (WIDTH / 3) * FP,
            y: (HEIGHT / 2) * FP,
            vx: 0,
            vy: 0,
            heading: 0,
            cooldown: 0,
            alive: true,
        });
        if players == 2 {
            // Player two: right-of-centre, heading -X (pointing back at p1).
            ships.push(Ship {
                x: (2 * WIDTH / 3) * FP,
                y: (HEIGHT / 2) * FP,
                vx: 0,
                vy: 0,
                heading: ANGLE_STEPS / 2,
                cooldown: 0,
                alive: true,
            });
        }

        // Seeded asteroid field: a handful of large rocks placed away from the
        // ship spawn points so nobody dies on tick zero.
        let count = 4;
        let mut asteroids = Vec::with_capacity(count);
        for _ in 0..count {
            let a = Game::spawn_field_asteroid(&mut rng, &ships);
            asteroids.push(a);
        }

        Game {
            rng,
            players,
            ships,
            asteroids,
            bullets: Vec::new(),
            inputs: vec![Input::default(); players as usize],
            scores: vec![0; players as usize],
            tick: 0,
            status: GameStatus::Playing,
        }
    }

    /// Place one large asteroid at a seeded position clear of every ship, with a
    /// seeded heading + speed. Deterministic given the RNG state.
    fn spawn_field_asteroid(rng: &mut Rng, ships: &[Ship]) -> Asteroid {
        // Rejection-sample a position far enough from any ship spawn. Bounded
        // attempts keep it deterministic and terminating.
        let mut x = 0;
        let mut y = 0;
        let clearance = Size::Large.radius() + SHIP_RADIUS + 16 * FP;
        for _attempt in 0..16 {
            x = rng.range(0, WIDTH - 1) * FP;
            y = rng.range(0, HEIGHT - 1) * FP;
            let mut ok = true;
            for s in ships {
                let dx = wrap_delta(s.x, x, WIDTH * FP);
                let dy = wrap_delta(s.y, y, HEIGHT * FP);
                if (dx as i64 * dx as i64 + dy as i64 * dy as i64)
                    < (clearance as i64 * clearance as i64)
                {
                    ok = false;
                    break;
                }
            }
            if ok {
                break;
            }
        }
        // Seeded heading + speed → integer velocity via the sin/cos table.
        let angle = rng.range(0, ANGLE_STEPS - 1);
        let speed = Size::Large.base_speed();
        let vx = mul_fp(cos_fp(angle), speed);
        let vy = mul_fp(sin_fp(angle), speed);
        Asteroid {
            x,
            y,
            vx,
            vy,
            size: Size::Large,
        }
    }

    // ── input ───────────────────────────────────────────────────────────────

    /// Set `player`'s intended input for the NEXT [`Game::tick`]. Inputs persist
    /// until overwritten, so a held key need only be re-sent on change; the host
    /// is free to set it every tick. Returns [`InputError::NoSuchPlayer`] for a
    /// player that does not exist in this game.
    pub fn set_input(&mut self, player: Player, input: Input) -> Result<(), InputError> {
        let idx = player.index();
        if idx >= self.players as usize {
            return Err(InputError::NoSuchPlayer);
        }
        self.inputs[idx] = input;
        Ok(())
    }

    /// Clear a player's input back to neutral (no thrust / turn / fire).
    pub fn clear_input(&mut self, player: Player) -> Result<(), InputError> {
        self.set_input(player, Input::default())
    }

    // ── the tick ──────────────────────────────────────────────────────────────

    /// Advance the simulation one fixed timestep. The whole step is deterministic
    /// and resolves every player's [`Input`] SIMULTANEOUSLY:
    ///
    /// 1. each ALIVE ship applies its input (turn, thrust, fire) — all read the
    ///    same input set captured for this tick;
    /// 2. every body (ships, asteroids, bullets) integrates + wraps;
    /// 3. bullets age and expire;
    /// 4. bullet↔asteroid collisions are resolved in a fixed order (splitting
    ///    asteroids and scoring), then ship↔asteroid collisions destroy ships;
    /// 5. the win / game-over status is recomputed.
    ///
    /// A terminal game is a no-op (the call returns without mutating state).
    pub fn tick(&mut self) {
        if self.status.is_terminal() {
            return;
        }

        // 1. ships act on their captured inputs (simultaneous).
        let mut new_bullets: Vec<Bullet> = Vec::new();
        for i in 0..self.ships.len() {
            let input = self.inputs[i];
            let ship = &mut self.ships[i];
            if ship.cooldown > 0 {
                ship.cooldown -= 1;
            }
            if !ship.alive {
                continue;
            }
            match input.turn {
                Turn::Left => ship.heading = wrap(ship.heading - TURN_RATE, ANGLE_STEPS),
                Turn::Right => ship.heading = wrap(ship.heading + TURN_RATE, ANGLE_STEPS),
                Turn::None => {}
            }
            if input.thrust {
                ship.vx += mul_fp(cos_fp(ship.heading), THRUST_ACCEL);
                ship.vy += mul_fp(sin_fp(ship.heading), THRUST_ACCEL);
                ship.vx = ship.vx.clamp(-MAX_SHIP_SPEED, MAX_SHIP_SPEED);
                ship.vy = ship.vy.clamp(-MAX_SHIP_SPEED, MAX_SHIP_SPEED);
            }
            // light friction keeps the integer velocity bounded and replayable.
            ship.vx = ship.vx * FRICTION_NUM / FRICTION_DEN;
            ship.vy = ship.vy * FRICTION_NUM / FRICTION_DEN;

            if input.fire && ship.cooldown == 0 {
                let owner = if i == 0 { Player::One } else { Player::Two };
                // bullet spawns at the ship nose, inheriting heading velocity.
                let nose = SHIP_RADIUS + BULLET_RADIUS;
                let bx = wrap(ship.x + mul_fp(cos_fp(ship.heading), nose), WIDTH * FP);
                let by = wrap(ship.y + mul_fp(sin_fp(ship.heading), nose), HEIGHT * FP);
                new_bullets.push(Bullet {
                    x: bx,
                    y: by,
                    vx: mul_fp(cos_fp(ship.heading), BULLET_SPEED),
                    vy: mul_fp(sin_fp(ship.heading), BULLET_SPEED),
                    life: BULLET_LIFE,
                    owner,
                });
                ship.cooldown = FIRE_COOLDOWN;
            }
        }
        self.bullets.extend(new_bullets);

        // 2. integrate + wrap every body.
        for s in &mut self.ships {
            s.x = wrap(s.x + s.vx, WIDTH * FP);
            s.y = wrap(s.y + s.vy, HEIGHT * FP);
        }
        for a in &mut self.asteroids {
            a.x = wrap(a.x + a.vx, WIDTH * FP);
            a.y = wrap(a.y + a.vy, HEIGHT * FP);
        }
        for b in &mut self.bullets {
            b.x = wrap(b.x + b.vx, WIDTH * FP);
            b.y = wrap(b.y + b.vy, HEIGHT * FP);
        }

        // 3. age bullets; expired ones are dropped.
        for b in &mut self.bullets {
            b.life = b.life.saturating_sub(1);
        }
        self.bullets.retain(|b| b.life > 0);

        // 4a. bullet ↔ asteroid: each bullet hits at most one asteroid (the first
        //     in index order it overlaps); a hit splits/destroys the asteroid and
        //     scores for the bullet's owner. Iterate in a fixed order so the
        //     spawned children are appended deterministically.
        let mut spawned: Vec<Asteroid> = Vec::new();
        let mut dead_asteroids = vec![false; self.asteroids.len()];
        let mut dead_bullets = vec![false; self.bullets.len()];
        for bi in 0..self.bullets.len() {
            for ai in 0..self.asteroids.len() {
                if dead_asteroids[ai] {
                    continue;
                }
                let b = self.bullets[bi];
                let a = self.asteroids[ai];
                if circles_overlap(b.x, b.y, BULLET_RADIUS, a.x, a.y, a.size.radius()) {
                    dead_bullets[bi] = true;
                    dead_asteroids[ai] = true;
                    self.add_score(b.owner, a.size.score());
                    if let Some(child) = a.size.split() {
                        // Large → 2 medium → 2 small. Two children fly apart along
                        // seeded opposite-ish headings so the field stays varied
                        // but replayable.
                        let base = self.rng.range(0, ANGLE_STEPS - 1);
                        let speed = child.base_speed();
                        for k in 0..2 {
                            let angle = wrap(base + k * (ANGLE_STEPS / 2), ANGLE_STEPS);
                            spawned.push(Asteroid {
                                x: a.x,
                                y: a.y,
                                vx: mul_fp(cos_fp(angle), speed),
                                vy: mul_fp(sin_fp(angle), speed),
                                size: child,
                            });
                        }
                    }
                    break; // this bullet is spent
                }
            }
        }
        // compact dead bodies, then append the freshly-split children.
        let mut ai = 0;
        self.asteroids.retain(|_| {
            let keep = !dead_asteroids[ai];
            ai += 1;
            keep
        });
        let mut bi = 0;
        self.bullets.retain(|_| {
            let keep = !dead_bullets[bi];
            bi += 1;
            keep
        });
        self.asteroids.extend(spawned);

        // 4b. ship ↔ asteroid: any overlap destroys the ship. Resolved after the
        //     bullet pass so a rock killed this tick can't also kill a ship.
        for s in &mut self.ships {
            if !s.alive {
                continue;
            }
            for a in &self.asteroids {
                if circles_overlap(s.x, s.y, SHIP_RADIUS, a.x, a.y, a.size.radius()) {
                    s.alive = false;
                    break;
                }
            }
        }

        // 5. status.
        self.tick += 1;
        self.recompute_status();
    }

    fn add_score(&mut self, owner: Player, points: u64) {
        let idx = owner.index();
        if idx < self.scores.len() {
            self.scores[idx] += points;
        }
    }

    /// Recompute [`Game::status`] from the live ship set.
    fn recompute_status(&mut self) {
        let alive: Vec<Player> = self
            .ships
            .iter()
            .enumerate()
            .filter(|(_, s)| s.alive)
            .map(|(i, _)| if i == 0 { Player::One } else { Player::Two })
            .collect();

        if self.players == 1 {
            // SURVIVAL: alive ⇒ keep playing; dead ⇒ game over with the score.
            self.status = if alive.is_empty() {
                GameStatus::GameOver {
                    score: self.scores[0],
                }
            } else {
                GameStatus::Playing
            };
            return;
        }

        // DUEL.
        self.status = match alive.len() {
            // both alive: still playing.
            2 => GameStatus::Playing,
            // exactly one alive: that player wins.
            1 => GameStatus::Win { player: alive[0] },
            // both destroyed: a draw, reported as GameOver (no winner).
            _ => GameStatus::GameOver {
                score: self.scores[0],
            },
        };
    }

    // ── read-only accessors ───────────────────────────────────────────────────

    /// The current terminal/running status.
    pub fn status(&self) -> GameStatus {
        self.status
    }
    /// Whether the game has ended.
    pub fn is_over(&self) -> bool {
        self.status.is_terminal()
    }
    /// Number of ships in this game (1 = survival, 2 = duel).
    pub fn player_count(&self) -> u8 {
        self.players
    }
    /// The current tick count (number of completed [`Game::tick`]s).
    pub fn tick_count(&self) -> u64 {
        self.tick
    }
    /// A player's running score, or `0` for a non-existent player.
    pub fn score(&self, player: Player) -> u64 {
        self.scores.get(player.index()).copied().unwrap_or(0)
    }
    /// Player one's score (the survival score).
    pub fn survival_score(&self) -> u64 {
        self.scores[0]
    }
    /// Snapshot of a player's ship, or `None` for a non-existent player.
    pub fn ship(&self, player: Player) -> Option<Ship> {
        self.ships.get(player.index()).copied()
    }
    /// Whether a player's ship is currently alive.
    pub fn is_alive(&self, player: Player) -> bool {
        self.ships.get(player.index()).map(|s| s.alive).unwrap_or(false)
    }
    /// The live asteroid field.
    pub fn asteroids(&self) -> &[Asteroid] {
        &self.asteroids
    }
    /// The number of asteroids currently in the field.
    pub fn asteroid_count(&self) -> usize {
        self.asteroids.len()
    }
    /// The in-flight bullets.
    pub fn bullets(&self) -> &[Bullet] {
        &self.bullets
    }
    /// The number of bullets currently in flight.
    pub fn bullet_count(&self) -> usize {
        self.bullets.len()
    }

    // ── snapshot / json ───────────────────────────────────────────────────────

    /// The full snapshot as JSON: the field dimensions, every ship (position /
    /// velocity / heading / alive), every asteroid (position / velocity / size),
    /// every bullet, the per-player scores, the tick, and the status. Everything
    /// a browser needs to redraw or re-sync. The server never parses this; it
    /// only relays it.
    pub fn to_json(&self) -> String {
        let mut ships = String::new();
        for (i, s) in self.ships.iter().enumerate() {
            if i > 0 {
                ships.push(',');
            }
            ships.push_str(&format!(
                "{{\"x\":{},\"y\":{},\"vx\":{},\"vy\":{},\"heading\":{},\"alive\":{},\"score\":{}}}",
                s.x, s.y, s.vx, s.vy, s.heading, s.alive, self.scores[i]
            ));
        }
        let mut rocks = String::new();
        for (i, a) in self.asteroids.iter().enumerate() {
            if i > 0 {
                rocks.push(',');
            }
            rocks.push_str(&format!(
                "{{\"x\":{},\"y\":{},\"vx\":{},\"vy\":{},\"size\":\"{}\"}}",
                a.x,
                a.y,
                a.vx,
                a.vy,
                a.size.wire()
            ));
        }
        let mut shots = String::new();
        for (i, b) in self.bullets.iter().enumerate() {
            if i > 0 {
                shots.push(',');
            }
            shots.push_str(&format!(
                "{{\"x\":{},\"y\":{},\"vx\":{},\"vy\":{},\"life\":{},\"owner\":\"{}\"}}",
                b.x,
                b.y,
                b.vx,
                b.vy,
                b.life,
                b.owner.wire()
            ));
        }
        format!(
            "{{\"type\":\"asteroids.snapshot\",\"fp\":{fp},\"width\":{w},\"height\":{h},\
\"players\":{players},\"tick\":{tick},\
\"ships\":[{ships}],\"asteroids\":[{rocks}],\"bullets\":[{shots}],\
\"status\":\"{status}\"}}",
            fp = FP,
            w = WIDTH,
            h = HEIGHT,
            players = self.players,
            tick = self.tick,
            ships = ships,
            rocks = rocks,
            shots = shots,
            status = self.status.wire(),
        )
    }

    /// Alias for [`Game::to_json`].
    pub fn snapshot(&self) -> String {
        self.to_json()
    }

    // ── test-only construction helpers ───────────────────────────────────────

    /// Clear the asteroid field. Test-only board surgery.
    #[cfg(test)]
    fn clear_asteroids(&mut self) {
        self.asteroids.clear();
        self.recompute_status();
    }

    /// Clear all in-flight bullets. Test-only.
    #[cfg(test)]
    fn clear_bullets(&mut self) {
        self.bullets.clear();
    }

    /// Replace the asteroid field with a single asteroid of `size` at `(x,y)`
    /// (world units) at rest. Returns nothing; test-only.
    #[cfg(test)]
    fn place_asteroid(&mut self, size: Size, x: i32, y: i32) {
        self.asteroids.push(Asteroid {
            x: x * FP,
            y: y * FP,
            vx: 0,
            vy: 0,
            size,
        });
    }

    /// Position a ship directly (world units, heading step). Test-only.
    #[cfg(test)]
    fn place_ship(&mut self, player: Player, x: i32, y: i32, heading: i32) {
        let s = &mut self.ships[player.index()];
        s.x = wrap(x * FP, WIDTH * FP);
        s.y = wrap(y * FP, HEIGHT * FP);
        s.vx = 0;
        s.vy = 0;
        s.heading = wrap(heading, ANGLE_STEPS);
        s.cooldown = 0;
        s.alive = true;
    }
}

// ── fixed-point helpers ──────────────────────────────────────────────────────

/// Multiply two fixed-point values and renormalise. `a` is treated as a unit
/// fraction in `-FP..=FP` (a sin/cos value) and `b` as a magnitude.
fn mul_fp(a: i32, b: i32) -> i32 {
    ((a as i64 * b as i64) >> FP_BITS) as i32
}

/// Do two circles (centres + radii in FP units) overlap on the wrapping field?
fn circles_overlap(ax: i32, ay: i32, ar: i32, bx: i32, by: i32, br: i32) -> bool {
    let dx = wrap_delta(ax, bx, WIDTH * FP) as i64;
    let dy = wrap_delta(ay, by, HEIGHT * FP) as i64;
    let r = (ar + br) as i64;
    dx * dx + dy * dy < r * r
}

// ── integer sin table (quarter-symmetric, scaled by FP) ──────────────────────

/// `SIN_TABLE[a]` is `round(sin(2*pi*a/ANGLE_STEPS) * FP)`. Generated below as a
/// compile-time constant so the state path touches NO floating point. The values
/// were produced once (and verified in the test-suite against the identity
/// `sin^2 + cos^2 ≈ 1`).
const SIN_TABLE: [i16; ANGLE_STEPS as usize] = build_sin_table();

/// Build the sin table at compile time using only integer arithmetic (a CORDIC-
/// free Taylor/Bhaskara approximation evaluated in `const fn`). We approximate
/// `sin` over `[0, pi]` with Bhaskara I's formula and mirror it for `[pi, 2pi]`.
const fn build_sin_table() -> [i16; ANGLE_STEPS as usize] {
    let mut table = [0i16; ANGLE_STEPS as usize];
    let mut a = 0usize;
    while a < ANGLE_STEPS as usize {
        // angle in "degrees" scaled to 0..360 over the 256 steps, ×1000 for
        // integer precision: deg_milli = a * 360_000 / 256.
        let deg_milli = (a as i64 * 360_000) / ANGLE_STEPS as i64;
        table[a] = bhaskara_sin_fp(deg_milli) as i16;
        a += 1;
    }
    table
}

/// Bhaskara I sine approximation, integer-only, returning `sin(x) * FP`.
/// Input `deg_milli` is the angle in degrees × 1000 (`0..360_000`). For the
/// first half `[0,180)` it uses `sin x = 4x(180-x) / (40500 - x(180-x))`; the
/// second half mirrors with a negative sign. Maximum error vs true sine is well
/// under 1/256, so the FP-scaled result is exact-to-rounding for our table.
const fn bhaskara_sin_fp(deg_milli: i64) -> i64 {
    // reduce to [0,360000)
    let mut d = deg_milli % 360_000;
    if d < 0 {
        d += 360_000;
    }
    let (x, sign) = if d <= 180_000 {
        (d, 1i64)
    } else {
        (d - 180_000, -1i64)
    };
    // Bhaskara works in degrees; x is in milli-degrees. Convert by keeping the
    // milli scale consistent: numerator = 4*x*(180000 - x), denominator =
    // 40500*1_000_000 - x*(180000 - x). Then multiply by FP and divide.
    let xm = x; // milli-degrees in [0,180000]
    let comp = 180_000 - xm;
    let num = 4 * xm * comp; // up to 4*180000^2 ≈ 1.3e11, fits i64
    let den = 40_500 * 1_000_000 - xm * comp; // ≈ 4.05e10
    // sin = num/den in [0,1]; scale by FP and round.
    let scaled = (num * FP as i64 * 2 + den) / (den * 2); // round-to-nearest
    sign * scaled
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
        for n in 1..=50u32 {
            for _ in 0..200 {
                assert!(r.below(n) < n);
            }
        }
    }

    #[test]
    fn rng_range_is_inclusive_and_bounded() {
        let mut r = Rng::new(7);
        for _ in 0..2000 {
            let v = r.range(-5, 5);
            assert!((-5..=5).contains(&v));
        }
    }

    // ── fixed-point trig sanity (the only place a float ever existed) ─────────
    #[test]
    fn sin_cos_table_obeys_pythagoras() {
        // sin^2 + cos^2 must be ~FP^2 for every heading step.
        for a in 0..ANGLE_STEPS {
            let s = sin_fp(a) as i64;
            let c = cos_fp(a) as i64;
            let mag = s * s + c * c;
            let target = (FP as i64) * (FP as i64);
            // allow a small rounding tolerance from the integer approximation.
            let err = (mag - target).abs();
            assert!(
                err <= target / 20,
                "angle {a}: sin^2+cos^2={mag}, target={target}, err={err}"
            );
        }
    }

    #[test]
    fn sin_cos_cardinal_points() {
        // 0 → (cos=+FP, sin=0); quarter → (cos=0, sin=+FP); etc. Allow ±2 LSB.
        let near = |v: i32, t: i32| (v - t).abs() <= 2;
        assert!(near(cos_fp(0), FP) && near(sin_fp(0), 0));
        assert!(near(cos_fp(ANGLE_STEPS / 4), 0) && near(sin_fp(ANGLE_STEPS / 4), FP));
        assert!(near(cos_fp(ANGLE_STEPS / 2), -FP) && near(sin_fp(ANGLE_STEPS / 2), 0));
        assert!(near(cos_fp(3 * ANGLE_STEPS / 4), 0) && near(sin_fp(3 * ANGLE_STEPS / 4), -FP));
    }

    #[test]
    fn wrap_and_wrap_delta_are_consistent() {
        let span = WIDTH * FP;
        assert_eq!(wrap(-1, span), span - 1);
        assert_eq!(wrap(span, span), 0);
        assert_eq!(wrap(span + 5, span), 5);
        // a body at 1 and a body at span-1 are 2 apart across the seam.
        assert_eq!(wrap_delta(1, span - 1, span), -2);
        assert_eq!(wrap_delta(span - 1, 1, span), 2);
    }

    // ── construction ──────────────────────────────────────────────────────────
    #[test]
    fn new_is_single_ship_survival() {
        let g = Game::new(1);
        assert_eq!(g.player_count(), 1);
        assert!(g.is_alive(Player::One));
        assert_eq!(g.status(), GameStatus::Playing);
        assert!(g.asteroid_count() > 0, "seeded field is non-empty");
        // there is no player two in survival.
        assert!(g.ship(Player::Two).is_none());
    }

    #[test]
    fn duel_has_two_ships() {
        let g = Game::with_players(1, 2);
        assert_eq!(g.player_count(), 2);
        assert!(g.is_alive(Player::One) && g.is_alive(Player::Two));
        assert!(g.ship(Player::Two).is_some());
    }

    #[test]
    fn player_count_is_clamped() {
        assert_eq!(Game::with_players(1, 0).player_count(), 1);
        assert_eq!(Game::with_players(1, 9).player_count(), 2);
    }

    #[test]
    fn seeded_field_keeps_ships_clear_at_spawn() {
        // No asteroid overlaps a ship at tick 0 for a range of seeds.
        for seed in 0..40u64 {
            let g = Game::with_players(seed, 2);
            for p in [Player::One, Player::Two] {
                let s = g.ship(p).unwrap();
                for a in g.asteroids() {
                    assert!(
                        !circles_overlap(s.x, s.y, SHIP_RADIUS, a.x, a.y, a.size.radius()),
                        "seed {seed}: spawn overlap"
                    );
                }
            }
        }
    }

    // ── illegal input handling ────────────────────────────────────────────────
    #[test]
    fn set_input_rejects_nonexistent_player() {
        let mut g = Game::new(1); // survival: only player one
        assert_eq!(g.set_input(Player::One, Input::thrust()), Ok(()));
        assert_eq!(
            g.set_input(Player::Two, Input::thrust()),
            Err(InputError::NoSuchPlayer)
        );
    }

    #[test]
    fn duel_accepts_both_players() {
        let mut g = Game::with_players(1, 2);
        assert_eq!(g.set_input(Player::One, Input::left()), Ok(()));
        assert_eq!(g.set_input(Player::Two, Input::right()), Ok(()));
    }

    // ── motion: thrust, rotation, wrap ────────────────────────────────────────
    #[test]
    fn thrust_accelerates_along_heading() {
        let mut g = Game::new(1);
        g.clear_asteroids();
        g.place_ship(Player::One, 100, 100, 0); // heading +X
        g.set_input(Player::One, Input::thrust()).unwrap();
        let x0 = g.ship(Player::One).unwrap().x;
        g.tick();
        let s = g.ship(Player::One).unwrap();
        assert!(s.vx > 0, "thrust at heading 0 builds +X velocity");
        assert!(s.x > x0, "ship moved in +X");
        assert_eq!(s.vy, 0, "no Y velocity when heading is exactly +X");
    }

    #[test]
    fn rotation_changes_heading_deterministically() {
        let mut g = Game::new(1);
        g.clear_asteroids();
        g.place_ship(Player::One, 100, 100, 0);
        g.set_input(Player::One, Input::right()).unwrap();
        g.tick();
        assert_eq!(g.ship(Player::One).unwrap().heading, TURN_RATE);
        g.set_input(Player::One, Input::left()).unwrap();
        g.tick();
        g.set_input(Player::One, Input::left()).unwrap();
        g.tick();
        assert_eq!(
            g.ship(Player::One).unwrap().heading,
            wrap(TURN_RATE - 2 * TURN_RATE, ANGLE_STEPS)
        );
    }

    #[test]
    fn position_wraps_around_the_field() {
        let mut g = Game::new(1);
        g.clear_asteroids();
        // park a ship at the far-right edge heading +X with a known velocity by
        // thrusting; it must reappear on the left after crossing the seam.
        g.place_ship(Player::One, WIDTH - 2, 100, 0);
        // give it a big shove with several thrust ticks, then coast across.
        for _ in 0..6 {
            g.set_input(Player::One, Input::thrust()).unwrap();
            g.tick();
        }
        // keep coasting (no thrust) until it has certainly passed the right edge.
        for _ in 0..40 {
            g.clear_input(Player::One).unwrap();
            g.tick();
        }
        let s = g.ship(Player::One).unwrap();
        assert!(s.x >= 0 && s.x < WIDTH * FP, "x stays within the wrapped field");
        assert!(s.y >= 0 && s.y < HEIGHT * FP, "y stays within the wrapped field");
        // it actually wrapped: an x near the left side proves the seam crossing
        // (it started at WIDTH-2 moving +X).
        assert!(s.x < (WIDTH / 2) * FP, "ship wrapped to the left half: x={}", s.x);
    }

    #[test]
    fn asteroids_wrap_too() {
        let mut g = Game::new(1);
        g.clear_asteroids();
        // a single asteroid at the right edge moving +X
        g.asteroids.push(Asteroid {
            x: (WIDTH - 1) * FP,
            y: 100 * FP,
            vx: 3 * FP,
            vy: 0,
            size: Size::Small,
        });
        g.tick();
        let a = g.asteroids()[0];
        assert!(a.x >= 0 && a.x < WIDTH * FP);
        // crossed the seam → small x
        assert!(a.x < 10 * FP, "asteroid wrapped: x={}", a.x);
    }

    // ── firing ────────────────────────────────────────────────────────────────
    #[test]
    fn firing_spawns_a_bullet_with_cooldown() {
        let mut g = Game::new(1);
        g.clear_asteroids();
        g.place_ship(Player::One, 100, 100, 0);
        assert_eq!(g.bullet_count(), 0);
        g.set_input(Player::One, Input::fire()).unwrap();
        g.tick();
        assert_eq!(g.bullet_count(), 1, "one shot fired");
        // The cooldown is (re)set to FIRE_COOLDOWN on the firing tick, AFTER the
        // start-of-tick decrement, so it reads the full value right after firing.
        assert_eq!(g.ship(Player::One).unwrap().cooldown, FIRE_COOLDOWN);
        // holding fire during cooldown produces no second bullet immediately.
        g.set_input(Player::One, Input::fire()).unwrap();
        g.tick();
        assert_eq!(g.bullet_count(), 1, "cooldown blocks rapid re-fire");
    }

    #[test]
    fn bullet_expires_after_its_lifetime() {
        let mut g = Game::new(1);
        g.clear_asteroids();
        g.place_ship(Player::One, 100, 100, 0);
        g.set_input(Player::One, Input::fire()).unwrap();
        g.tick();
        assert_eq!(g.bullet_count(), 1);
        g.clear_input(Player::One).unwrap();
        // bullet has BULLET_LIFE ticks of life; after the spawn tick it was
        // decremented once, so it lasts BULLET_LIFE-1 more ticks.
        for _ in 0..(BULLET_LIFE - 1) {
            g.tick();
        }
        assert_eq!(g.bullet_count(), 0, "bullet expired on schedule");
    }

    // ── asteroid splitting + scoring ──────────────────────────────────────────
    #[test]
    fn bullet_splits_a_large_asteroid_into_two_mediums() {
        let mut g = Game::new(1);
        g.clear_asteroids();
        g.clear_bullets();
        // place a large asteroid right in front of the ship's nose, then fire.
        g.place_ship(Player::One, 100, 100, 0);
        g.place_asteroid(Size::Large, 130, 100); // 30 units ahead, within nose+radius
        let before_score = g.survival_score();
        // Fire and let the bullet travel into the asteroid.
        g.set_input(Player::One, Input::fire()).unwrap();
        g.tick();
        g.clear_input(Player::One).unwrap();
        let mut split = false;
        for _ in 0..20 {
            g.tick();
            if g.asteroid_count() == 2 {
                split = true;
                break;
            }
        }
        assert!(split, "large asteroid split into two");
        for a in g.asteroids() {
            assert_eq!(a.size, Size::Medium, "children are medium");
        }
        assert_eq!(
            g.survival_score(),
            before_score + Size::Large.score(),
            "scored the large rock"
        );
    }

    #[test]
    fn medium_splits_into_two_smalls() {
        let mut g = Game::new(1);
        g.clear_asteroids();
        g.clear_bullets();
        g.place_ship(Player::One, 100, 100, 0);
        g.place_asteroid(Size::Medium, 125, 100);
        g.set_input(Player::One, Input::fire()).unwrap();
        g.tick();
        g.clear_input(Player::One).unwrap();
        let mut ok = false;
        for _ in 0..20 {
            g.tick();
            if g.asteroid_count() == 2 && g.asteroids().iter().all(|a| a.size == Size::Small) {
                ok = true;
                break;
            }
        }
        assert!(ok, "medium split into two smalls");
    }

    #[test]
    fn small_asteroid_is_removed_when_shot() {
        let mut g = Game::new(1);
        g.clear_asteroids();
        g.clear_bullets();
        g.place_ship(Player::One, 100, 100, 0);
        g.place_asteroid(Size::Small, 120, 100);
        let before = g.survival_score();
        g.set_input(Player::One, Input::fire()).unwrap();
        g.tick();
        g.clear_input(Player::One).unwrap();
        let mut gone = false;
        for _ in 0..20 {
            g.tick();
            if g.asteroid_count() == 0 {
                gone = true;
                break;
            }
        }
        assert!(gone, "small asteroid removed entirely (no children)");
        assert_eq!(
            g.survival_score(),
            before + Size::Small.score(),
            "small rock scores the most"
        );
        assert_eq!(g.bullet_count(), 0, "the bullet was consumed by the hit");
    }

    #[test]
    fn scoring_is_size_tiered() {
        assert_eq!(Size::Large.score(), 20);
        assert_eq!(Size::Medium.score(), 50);
        assert_eq!(Size::Small.score(), 100);
        // full destruction of one large rock = large + 2 medium + 4 small.
        let full = Size::Large.score() + 2 * Size::Medium.score() + 4 * Size::Small.score();
        assert_eq!(full, 20 + 100 + 400);
    }

    #[test]
    fn destroying_a_full_chain_accumulates_score() {
        // Score accumulates as a large rock is broken down: large, then each
        // medium child, then each small grandchild. Rather than chase drifting
        // children with a forward-only gun, we destroy each tier explicitly so
        // the test deterministically reaches dust.
        let mut g = Game::new(1);

        // helper: place a rock in the ship's nose line and fire until it's hit,
        // returning the score delta from the hit.
        fn shoot_one(g: &mut Game, size: Size) -> u64 {
            g.clear_asteroids();
            g.clear_bullets();
            g.place_ship(Player::One, 100, 300, 0);
            g.place_asteroid(size, 130, 300);
            let before = g.survival_score();
            g.set_input(Player::One, Input::fire()).unwrap();
            for _ in 0..20 {
                g.tick();
                g.clear_input(Player::One).unwrap();
                // stop once the placed rock is gone (children, if any, were
                // spawned at the hit point but we only score the parent here).
                if g.survival_score() != before {
                    break;
                }
            }
            g.survival_score() - before
        }

        // Each tier scores exactly its size value when shot.
        assert_eq!(shoot_one(&mut g, Size::Large), Size::Large.score());
        assert_eq!(shoot_one(&mut g, Size::Medium), Size::Medium.score());
        assert_eq!(shoot_one(&mut g, Size::Small), Size::Small.score());

        // A whole-chain destruction (1 large + 2 medium + 4 small) is worth a
        // fixed, known total — the canonical asteroids scoring identity.
        let chain = Size::Large.score() + 2 * Size::Medium.score() + 4 * Size::Small.score();
        assert_eq!(chain, 520);
    }

    // ── ship ↔ asteroid collision ─────────────────────────────────────────────
    #[test]
    fn ship_asteroid_collision_ends_survival() {
        let mut g = Game::new(1);
        g.clear_asteroids();
        g.place_ship(Player::One, 200, 200, 0);
        // drop a rock right on top of the ship.
        g.place_asteroid(Size::Large, 200, 200);
        assert_eq!(g.status(), GameStatus::Playing);
        g.tick();
        assert!(!g.is_alive(Player::One), "ship destroyed by the rock");
        assert!(matches!(g.status(), GameStatus::GameOver { .. }));
        assert!(g.is_over());
    }

    #[test]
    fn game_over_carries_the_score() {
        let mut g = Game::new(1);
        g.clear_asteroids();
        g.clear_bullets();
        g.place_ship(Player::One, 100, 100, 0);
        // score a small rock first.
        g.place_asteroid(Size::Small, 120, 100);
        g.set_input(Player::One, Input::fire()).unwrap();
        for _ in 0..10 {
            g.tick();
            g.clear_input(Player::One).unwrap();
            if g.asteroid_count() == 0 {
                break;
            }
        }
        let earned = g.survival_score();
        assert_eq!(earned, Size::Small.score());
        // now collide with a fresh rock.
        g.place_asteroid(Size::Large, 100, 100);
        g.tick();
        match g.status() {
            GameStatus::GameOver { score } => assert_eq!(score, earned),
            other => panic!("expected GameOver, got {other:?}"),
        }
    }

    #[test]
    fn terminal_game_ignores_further_ticks_and_input() {
        let mut g = Game::new(1);
        g.clear_asteroids();
        g.place_ship(Player::One, 200, 200, 0);
        g.place_asteroid(Size::Large, 200, 200);
        g.tick();
        assert!(g.is_over());
        let snapshot = g.to_json();
        // ticking a terminal game is a no-op.
        g.tick();
        g.tick();
        assert_eq!(g.to_json(), snapshot, "terminal game state is frozen");
        // input can still be set, but it never takes effect.
        assert_eq!(g.set_input(Player::One, Input::thrust()), Ok(()));
        g.tick();
        assert_eq!(g.to_json(), snapshot);
    }

    // ── duel win conditions ───────────────────────────────────────────────────
    #[test]
    fn duel_one_survivor_wins() {
        let mut g = Game::with_players(7, 2);
        g.clear_asteroids();
        // kill player two by dropping a rock on it; player one survives elsewhere.
        g.place_ship(Player::One, 100, 100, 0);
        g.place_ship(Player::Two, 600, 600, ANGLE_STEPS / 2);
        g.place_asteroid(Size::Large, 600, 600); // on top of p2 only
        g.tick();
        assert!(g.is_alive(Player::One));
        assert!(!g.is_alive(Player::Two));
        assert_eq!(g.status(), GameStatus::Win { player: Player::One });
    }

    #[test]
    fn duel_mutual_destruction_is_a_draw() {
        let mut g = Game::with_players(7, 2);
        g.clear_asteroids();
        g.place_ship(Player::One, 100, 100, 0);
        g.place_ship(Player::Two, 600, 600, ANGLE_STEPS / 2);
        // one rock on each ship → both die on the same tick.
        g.place_asteroid(Size::Large, 100, 100);
        g.place_asteroid(Size::Large, 600, 600);
        g.tick();
        assert!(!g.is_alive(Player::One) && !g.is_alive(Player::Two));
        assert!(matches!(g.status(), GameStatus::GameOver { .. }), "draw → game over");
    }

    #[test]
    fn duel_stays_playing_while_both_alive() {
        let mut g = Game::with_players(7, 2);
        g.clear_asteroids();
        for _ in 0..30 {
            g.tick();
            assert_eq!(g.status(), GameStatus::Playing, "nobody died with an empty field");
        }
    }

    #[test]
    fn duel_attributes_score_to_the_firing_ship() {
        let mut g = Game::with_players(11, 2);
        g.clear_asteroids();
        g.clear_bullets();
        g.place_ship(Player::One, 100, 100, 0);
        g.place_ship(Player::Two, 700, 400, ANGLE_STEPS / 2);
        // a rock only in front of player one.
        g.place_asteroid(Size::Small, 120, 100);
        g.set_input(Player::One, Input::fire()).unwrap();
        for _ in 0..12 {
            g.tick();
            g.clear_input(Player::One).unwrap();
            if g.asteroid_count() == 0 {
                break;
            }
        }
        assert_eq!(g.score(Player::One), Size::Small.score());
        assert_eq!(g.score(Player::Two), 0, "p2 never fired");
    }

    // ── determinism: same seed + inputs ⇒ identical state ─────────────────────

    /// Run a scripted duel from a seed and return the final JSON snapshot. A
    /// small deterministic input policy that exercises turning, thrust, and fire.
    fn scripted_run(seed: u64) -> String {
        let mut g = Game::with_players(seed, 2);
        for t in 0..300u64 {
            if g.is_over() {
                break;
            }
            // player one: rotate right, thrust on even ticks, fire every 4th.
            let p1 = Input {
                thrust: t % 2 == 0,
                turn: Turn::Right,
                fire: t % 4 == 0,
            };
            // player two: rotate left, thrust on odd ticks, fire every 5th.
            let p2 = Input {
                thrust: t % 2 == 1,
                turn: Turn::Left,
                fire: t % 5 == 0,
            };
            g.set_input(Player::One, p1).unwrap();
            g.set_input(Player::Two, p2).unwrap();
            g.tick();
        }
        g.to_json()
    }

    #[test]
    fn replay_is_bit_exact_for_a_seed() {
        let a = scripted_run(0xDEADBEEF);
        let b = scripted_run(0xDEADBEEF);
        assert_eq!(a, b, "same seed + same inputs ⇒ identical final snapshot");
    }

    #[test]
    fn replay_diverges_for_different_seeds() {
        let a = scripted_run(1);
        let b = scripted_run(2);
        assert_ne!(a, b, "different seeds ⇒ different fields/outcomes");
    }

    #[test]
    fn step_by_step_replay_matches_at_every_tick() {
        // Two independent games fed the SAME inputs must agree at EVERY tick, not
        // just the end — the strongest determinism guarantee.
        let seed = 0xABCDEF;
        let mut a = Game::with_players(seed, 2);
        let mut b = Game::with_players(seed, 2);
        for t in 0..250u64 {
            let p1 = Input {
                thrust: t % 3 == 0,
                turn: if t % 2 == 0 { Turn::Left } else { Turn::Right },
                fire: t % 6 == 0,
            };
            let p2 = Input {
                thrust: t % 2 == 0,
                turn: Turn::Right,
                fire: t % 3 == 0,
            };
            for g in [&mut a, &mut b] {
                g.set_input(Player::One, p1).unwrap();
                g.set_input(Player::Two, p2).unwrap();
                g.tick();
            }
            assert_eq!(a.to_json(), b.to_json(), "diverged at tick {t}");
        }
    }

    #[test]
    fn input_persists_until_changed() {
        // set thrust once; it should keep applying every tick until cleared.
        let mut g = Game::new(1);
        g.clear_asteroids();
        g.place_ship(Player::One, 100, 100, 0);
        g.set_input(Player::One, Input::thrust()).unwrap();
        g.tick();
        let v1 = g.ship(Player::One).unwrap().vx;
        g.tick(); // no new set_input — thrust persists
        let v2 = g.ship(Player::One).unwrap().vx;
        assert!(v2 > v1, "persisted thrust kept accelerating: {v1} -> {v2}");
    }

    #[test]
    fn simultaneous_resolution_is_order_independent() {
        // Setting p2 before p1 vs p1 before p2 yields the same tick result —
        // inputs are captured then resolved together, not sequentially.
        let mk = |p1_first: bool| -> String {
            let mut g = Game::with_players(3, 2);
            g.clear_asteroids();
            let p1 = Input::thrust();
            let p2 = Input::fire();
            if p1_first {
                g.set_input(Player::One, p1).unwrap();
                g.set_input(Player::Two, p2).unwrap();
            } else {
                g.set_input(Player::Two, p2).unwrap();
                g.set_input(Player::One, p1).unwrap();
            }
            g.tick();
            g.to_json()
        };
        assert_eq!(mk(true), mk(false));
    }

    // ── snapshot / json ───────────────────────────────────────────────────────
    #[test]
    fn snapshot_has_expected_fields() {
        let g = Game::with_players(2024, 2);
        let j = g.to_json();
        assert!(j.contains("\"type\":\"asteroids.snapshot\""), "{j}");
        assert!(j.contains(&format!("\"fp\":{FP}")), "{j}");
        assert!(j.contains(&format!("\"width\":{WIDTH}")), "{j}");
        assert!(j.contains(&format!("\"height\":{HEIGHT}")), "{j}");
        assert!(j.contains("\"players\":2"), "{j}");
        assert!(j.contains("\"tick\":0"), "{j}");
        assert!(j.contains("\"status\":\"playing\""), "{j}");
        assert!(j.contains("\"ships\":["), "{j}");
        assert!(j.contains("\"asteroids\":["), "{j}");
        assert!(j.contains("\"bullets\":[]"), "no bullets at start: {j}");
    }

    #[test]
    fn snapshot_reflects_status_transition() {
        let mut g = Game::new(1);
        g.clear_asteroids();
        g.place_ship(Player::One, 200, 200, 0);
        g.place_asteroid(Size::Large, 200, 200);
        g.tick();
        let j = g.to_json();
        assert!(j.contains("\"status\":\"gameover:"), "terminal status in json: {j}");
        assert!(j.contains("\"alive\":false"), "dead ship flagged: {j}");
    }

    #[test]
    fn status_wire_encoding() {
        assert_eq!(GameStatus::Playing.wire(), "playing");
        assert_eq!(GameStatus::GameOver { score: 250 }.wire(), "gameover:250");
        assert_eq!(GameStatus::Win { player: Player::Two }.wire(), "win:p2");
    }

    #[test]
    fn snapshot_alias_matches() {
        let g = Game::with_players(5, 2);
        assert_eq!(g.snapshot(), g.to_json());
    }

    // ── geometry / helper sanity ──────────────────────────────────────────────
    #[test]
    fn circles_overlap_respects_wrap() {
        // two points either side of the right seam, both small radii, are "close".
        let r = 5 * FP;
        assert!(circles_overlap(2, 100, r, WIDTH * FP - 2, 100, r));
        // two points dead-centre apart are not.
        assert!(!circles_overlap(0, 0, r, (WIDTH / 2) * FP, (HEIGHT / 2) * FP, r));
    }

    #[test]
    fn split_table_is_correct() {
        assert_eq!(Size::Large.split(), Some(Size::Medium));
        assert_eq!(Size::Medium.split(), Some(Size::Small));
        assert_eq!(Size::Small.split(), None);
    }

    #[test]
    fn player_helpers() {
        assert_eq!(Player::One.opposite(), Player::Two);
        assert_eq!(Player::Two.opposite(), Player::One);
        assert_eq!(Player::One.wire(), "p1");
        assert_eq!(Player::Two.wire(), "p2");
    }

    // ── long-run survival is deterministic ────────────────────────────────────
    #[test]
    fn survival_run_is_deterministic_to_the_end() {
        // A passive survival run (no input) eventually ends when a drifting rock
        // finds the stationary ship. The end-state must be identical per seed.
        let play = |seed: u64| -> (u64, u64) {
            let mut g = Game::new(seed);
            let mut guard = 0u64;
            while !g.is_over() && guard < 100_000 {
                g.tick();
                guard += 1;
            }
            (g.survival_score(), guard)
        };
        assert_eq!(play(42), play(42), "same seed → identical survival outcome");
    }
}
