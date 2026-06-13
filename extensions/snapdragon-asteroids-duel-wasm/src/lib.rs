//! `snapdragon-asteroids-duel-wasm` — a **thin, freestanding C-ABI / JSON shim**
//! around the pure [`snapdragon_asteroids_duel`] tick-simulation engine so a host
//! with NO JavaScript glue can drive authoritative real-time Asteroids: it loads
//! in a browser via `WebAssembly.instantiateStreaming` (empty imports) AND in a
//! JVM WASM runtime such as Chicory.
//!
//! ## What this module is
//!
//! * Plain `std` (NOT `no_std`). The core crate `snapdragon_asteroids_duel` itself
//!   uses `std` (`Vec`/`String`/`format!`), so std is unavoidably in the
//!   dependency graph; declaring `#![no_std]` here only triggers a duplicate
//!   `panic_impl` lang-item clash. Crucially, the `wasm32-unknown-unknown` std is
//!   the *freestanding* std — it is NOT wasi and NOT emscripten, so a pure-compute
//!   + `alloc` module like this one links with **zero host imports** (verified
//!   post-build by scanning the wasm import section). The host need only provide
//!   the module's own linear `memory`.
//! * There is NO `wasm-bindgen`, NO `wasi`, NO emscripten. The only dependency is
//!   the path crate `snapdragon-asteroids-duel`, left completely UNMODIFIED. The
//!   engine ALREADY emits its own JSON (`Game::to_json` / `Game::snapshot` /
//!   `GameStatus::wire`), so this shim never invents a wire shape and never pulls
//!   in serde or a host.
//!
//! ## Category — REALTIME DUEL (host-authoritative TICK LOOP)
//!
//! Unlike the turn-based shims (chess/reversi) this engine has a clock. The host
//! browser drives a `requestAnimationFrame` loop that, at the engine's tick rate,
//! sets each ship's per-tick input (`asteroids_input`), advances the fixed-step
//! simulation (`asteroids_tick`), and re-renders the snapshot (`asteroids_snapshot`).
//! The host is AUTHORITATIVE: it broadcasts the snapshot on the `world` channel;
//! a guest re-applies host snapshots (the engine is deterministic, so a guest
//! fed the same inputs re-simulates bit-exactly — see the engine's
//! `step_by_step_replay_matches_at_every_tick` test). Single-player (no session)
//! is local hot-seat / survival against the seeded asteroid field.
//!
//! ## Instance model — SINGLE GLOBAL instance
//!
//! This shim keeps ONE global `Game` instance (`static mut GAME`). `asteroids_new`
//! (re)creates it. **The host instantiates the wasm module once per game**: a host
//! that needs concurrent games instantiates the module multiple times (each
//! instance has its own private linear memory + global), which is the idiomatic
//! browser / Chicory pattern. The wasm is single-threaded so the `static mut`
//! access is sound (no concurrent callers within one instance).
//!
//! ## Memory contract (READ THIS before calling)
//!
//! All strings crossing the boundary are UTF-8 bytes in the module's linear
//! memory. The host owns input buffers; the module owns output buffers. The
//! gameplay ops here take SCALAR i32 args (ship index + input flags) and return a
//! PACKED snapshot, so there is no input-buffer marshalling on the hot path — only
//! the output-buffer dealloc.
//!
//! ### Receiving bytes OUT (module → host) — the PACKED return
//! The query/snapshot ops return a single `u64` (an `i64` bit pattern to a JS host)
//! that PACKS pointer and length:
//!
//! ```text
//!     packed: u64  ==  (ptr as u64) << 32 | (len as u64 & 0xFFFF_FFFF)
//!     ptr = (packed >> 32) as u32
//!     len = (packed & 0xFFFF_FFFF) as u32
//! ```
//!
//! The pointed-to bytes are a UTF-8 JSON string the host should read immediately.
//! The buffer is a **leaked** `Box<[u8]>` owned by the module; the host MUST hand
//! it back via `dealloc(ptr, len)` once read, or it leaks for the life of the
//! instance.
//!
//! ### The ops (mirror `snapdragon_asteroids_duel::Game`, reusing its OWN JSON)
//!  * `asteroids_new(seed_lo, seed_hi, players) -> i32`
//!        Build/replace the global game. `seed` is a u64 split across two i32 args
//!        (`seed = (seed_hi as u32 as u64) << 32 | seed_lo as u32 as u64`) because
//!        the JS↔wasm boundary marshals i32 cleanly. `players` is clamped to 1..=2
//!        by the engine (1 = single-ship SURVIVAL, 2 = two-ship DUEL). Always
//!        returns `1` (construction can't fail).
//!  * `asteroids_input(ship, thrust, turn, fire) -> i32`
//!        Set `ship`'s (0 = p1, 1 = p2) intended input for the NEXT tick. `thrust`
//!        / `fire` are 0/non-zero booleans; `turn` is `-1` (left) / `0` (none) /
//!        `+1` (right). Inputs PERSIST until overwritten (a held key need only be
//!        re-sent on change). Returns `1` on success, `0` for a ship index that
//!        doesn't exist in this game (e.g. ship 1 in survival) or no game.
//!  * `asteroids_tick() -> i32`
//!        Advance the simulation ONE fixed timestep, resolving every ship's input
//!        simultaneously. A terminal game is a no-op (the engine guards it).
//!        Returns `1` if a game exists, `0` if not.
//!  * `asteroids_snapshot() -> u64`  → packed `Game::to_json()`, the full
//!        `{"type":"asteroids.snapshot","fp","width","height","players","tick",
//!        "ships":[…],"asteroids":[…],"bullets":[…],"status"}` payload — everything
//!        a browser needs to redraw or a guest needs to re-sync. Empty object `{}`
//!        when there is no game.
//!  * `asteroids_status() -> u64`  → packed `{"status":"<wire>"}` where `<wire>` is
//!        the engine's `GameStatus::wire()` string (`"playing"`, `"gameover:<n>"`,
//!        `"win:p1"`, `"win:p2"`). Empty status when no game.

#![allow(clippy::missing_safety_doc)]

// std is in the graph via the core crate; we use it directly. On
// wasm32-unknown-unknown this is the freestanding std (no wasi/emscripten host
// imports for pure compute + alloc). panic="abort" (in Cargo.toml) means a panic
// traps the instance — the right failure mode for an authoritative oracle.
use std::boxed::Box;
use std::string::String;
use std::vec::Vec;

use snapdragon_asteroids_duel::{Game, Input, Player, Turn};

// ─────────────────────────────────────────────────────────────────────────────
//  The single global game instance.
// ─────────────────────────────────────────────────────────────────────────────
static mut GAME: Option<Game> = None;

#[inline]
unsafe fn game_mut() -> Option<&'static mut Game> {
    // Safe within this single-threaded wasm instance: no concurrent callers.
    let ptr = core::ptr::addr_of_mut!(GAME);
    (*ptr).as_mut()
}

#[inline]
unsafe fn game_ref() -> Option<&'static Game> {
    let ptr = core::ptr::addr_of!(GAME);
    (*ptr).as_ref()
}

#[inline]
unsafe fn set_game(g: Game) {
    let ptr = core::ptr::addr_of_mut!(GAME);
    *ptr = Some(g);
}

// ─────────────────────────────────────────────────────────────────────────────
//  Allocator exports (host ↔ module byte buffers).
// ─────────────────────────────────────────────────────────────────────────────

/// Allocate a `len`-byte buffer the host may write into; returns its linear-memory
/// offset. Backed by a leaked `Vec<u8>`; freed by `dealloc(ptr, len)`.
#[no_mangle]
pub extern "C" fn alloc(len: u32) -> u32 {
    let mut buf: Vec<u8> = Vec::with_capacity(len as usize);
    let ptr = buf.as_mut_ptr();
    core::mem::forget(buf);
    ptr as u32
}

/// Release a buffer previously returned by `alloc` (or by a packed output
/// pointer). `len` must equal the original allocation length.
#[no_mangle]
pub unsafe extern "C" fn dealloc(ptr: u32, len: u32) {
    if ptr == 0 {
        return;
    }
    // Reconstruct the Vec with the SAME capacity it was forgotten with so the
    // global allocator frees the exact region.
    let _ = Vec::from_raw_parts(ptr as *mut u8, 0, len as usize);
}

/// Leak a byte string into linear memory and pack `(ptr<<32|len)` for return.
fn leak_packed(bytes: Vec<u8>) -> u64 {
    let boxed: Box<[u8]> = bytes.into_boxed_slice();
    let len = boxed.len() as u64;
    let ptr = Box::into_raw(boxed) as *mut u8 as u64;
    (ptr << 32) | (len & 0xFFFF_FFFF)
}

// ─────────────────────────────────────────────────────────────────────────────
//  Argument decoders.
// ─────────────────────────────────────────────────────────────────────────────

/// Map a ship index (0 = p1, 1 = p2) to the engine `Player`. Any other index is
/// `None` (rejected by the caller as "no such ship").
fn player_of(ship: i32) -> Option<Player> {
    match ship {
        0 => Some(Player::One),
        1 => Some(Player::Two),
        _ => None,
    }
}

/// Decode the three input scalars into the engine's `Input`. `turn` is the trit
/// `-1` (left) / `0` (none) / `+1` (right); `thrust` / `fire` are 0/non-zero.
fn input_of(thrust: i32, turn: i32, fire: i32) -> Input {
    let turn = match turn {
        t if t < 0 => Turn::Left,
        t if t > 0 => Turn::Right,
        _ => Turn::None,
    };
    Input {
        thrust: thrust != 0,
        turn,
        fire: fire != 0,
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Tiny JSON encoder (no serde — keeps the module host-import-free). We only ever
//  EMIT JSON here; the engine already emits the rich snapshot payload, so this
//  helper covers the single simple shape the shim itself synthesises (the status
//  object).
// ─────────────────────────────────────────────────────────────────────────────

/// Escape a string into a JSON string body (no surrounding quotes). The data we
/// wrap (a wire status like `"gameover:520"`) contains no control characters, so
/// backslash + double-quote escaping is sufficient — mirrors the engine's own
/// escaping.
fn push_json_escaped(out: &mut String, s: &str) {
    for c in s.chars() {
        match c {
            '\\' => out.push_str("\\\\"),
            '"' => out.push_str("\\\""),
            _ => out.push(c),
        }
    }
}

/// `{"status":"<value>"}` with `value` JSON-escaped.
fn status_object(value: &str) -> Vec<u8> {
    let mut s = String::with_capacity(value.len() + 16);
    s.push_str("{\"status\":\"");
    push_json_escaped(&mut s, value);
    s.push_str("\"}");
    s.into_bytes()
}

// ─────────────────────────────────────────────────────────────────────────────
//  The exported ops.
// ─────────────────────────────────────────────────────────────────────────────

/// Build / replace the global game. `seed` is reassembled from the two i32 halves
/// (`seed = (seed_hi as u32 as u64) << 32 | seed_lo as u32 as u64`); `players` is
/// clamped to 1..=2 by the engine (1 = survival, 2 = duel). Returns 1.
#[no_mangle]
pub unsafe extern "C" fn asteroids_new(seed_lo: i32, seed_hi: i32, players: i32) -> i32 {
    let seed = ((seed_hi as u32 as u64) << 32) | (seed_lo as u32 as u64);
    let players = players.clamp(0, 255) as u8;
    set_game(Game::with_players(seed, players));
    1
}

/// Set `ship`'s (0 = p1, 1 = p2) input for the NEXT tick. `thrust` / `fire` are
/// 0/non-zero booleans; `turn` is `-1` (left) / `0` (none) / `+1` (right). Inputs
/// PERSIST until overwritten. Returns 1 on success, 0 for a non-existent ship or
/// no game.
#[no_mangle]
pub unsafe extern "C" fn asteroids_input(ship: i32, thrust: i32, turn: i32, fire: i32) -> i32 {
    let player = match player_of(ship) {
        Some(p) => p,
        None => return 0,
    };
    match game_mut() {
        None => 0,
        Some(g) => match g.set_input(player, input_of(thrust, turn, fire)) {
            Ok(()) => 1,
            Err(_) => 0,
        },
    }
}

/// Advance the simulation ONE fixed timestep (all ships' inputs resolve
/// simultaneously). A terminal game is a no-op (guarded inside the engine).
/// Returns 1 if a game exists, 0 if not.
#[no_mangle]
pub unsafe extern "C" fn asteroids_tick() -> i32 {
    match game_mut() {
        None => 0,
        Some(g) => {
            g.tick();
            1
        }
    }
}

/// Packed (ptr<<32|len) of the engine's `Game::to_json()` for the CURRENT state —
/// the full `{"type":"asteroids.snapshot",…}` payload (field dims, every ship,
/// asteroid, and bullet, the per-player scores, the tick, and the status). Empty
/// object `{}` when there is no game.
#[no_mangle]
pub unsafe extern "C" fn asteroids_snapshot() -> u64 {
    let json = match game_ref() {
        Some(g) => g.to_json(),
        None => String::from("{}"),
    };
    leak_packed(json.into_bytes())
}

/// Packed (ptr<<32|len) of `{"status":"<wire>"}` where `<wire>` is the engine's
/// `GameStatus::wire()` (`"playing"` / `"gameover:<n>"` / `"win:p1"` / `"win:p2"`).
/// Empty status when no game.
#[no_mangle]
pub unsafe extern "C" fn asteroids_status() -> u64 {
    let wire = match game_ref() {
        Some(g) => g.status().wire(),
        None => String::new(),
    };
    leak_packed(status_object(&wire))
}
