//! `snapdragon-tron-wasm` — a **thin, freestanding C-ABI / JSON shim** around the
//! pure [`snapdragon_tron`] real-time light-cycles engine so a host with NO
//! JavaScript glue can drive the authoritative two-player Tron tick simulation:
//! it loads in a browser via `WebAssembly.instantiateStreaming` (empty imports)
//! AND in a JVM WASM runtime such as Chicory.
//!
//! ## What this module is
//!
//! * Plain `std` (NOT `no_std`). The core crate `snapdragon_tron` itself uses
//!   `std` (`Vec`/`String`/`format!`), so std is unavoidably in the dependency
//!   graph; declaring `#![no_std]` here only triggers a duplicate `panic_impl`
//!   lang-item clash. Crucially, the `wasm32-unknown-unknown` std is the
//!   *freestanding* std — it is NOT wasi and NOT emscripten, so a pure-compute +
//!   `alloc` module like this one links with **zero host imports** (verified
//!   post-build by scanning the wasm import section). The host need only provide
//!   the module's own linear `memory`.
//! * There is NO `wasm-bindgen`, NO `wasi`, NO emscripten. The only dependency is
//!   the path crate `snapdragon-tron`, left completely UNMODIFIED. The engine
//!   ALREADY emits its own JSON (`Outcome::to_json` / `Game::snapshot` /
//!   `GameStatus::wire`), so this shim never invents a wire shape and never pulls
//!   in serde or a host.
//!
//! ## Real-time, host-authoritative, deterministic
//!
//! Tron is a REALTIME DUEL run as a HOST-AUTHORITATIVE TICK LOOP
//! (`docs/multiplayer-chess-design.md`): the host browser advances the
//! simulation on a fixed-step clock ([`tron_tick`]) and the server only STREAMS
//! each player's direction input ([`tron_set_dir`]); the guest re-simulates and
//! re-validates the host's broadcast world. Because the engine is bit-exact for a
//! given seed + input sequence, this shim adds NO non-determinism: every export
//! is a pure call straight into the engine.
//!
//! ## Instance model — SINGLE GLOBAL instance
//!
//! This shim keeps ONE global `Game` instance (`static mut GAME`). `tron_new` /
//! `tron_new_sized` (re)create it. **The host instantiates the wasm module once
//! per match**: a host that needs concurrent matches instantiates the module
//! multiple times (each instance has its own private linear memory + global),
//! which is the idiomatic browser / Chicory pattern. The wasm is single-threaded
//! so the `static mut` access is sound (no concurrent callers within one
//! instance).
//!
//! ## Memory contract (READ THIS before calling)
//!
//! All strings crossing the boundary are UTF-8 bytes in the module's linear
//! memory. The host owns input buffers; the module owns output buffers. The
//! direction / player / seed / size arguments are passed as plain integers (no
//! buffer), so the realtime ops are allocation-free on the hot path.
//!
//! ### Receiving bytes OUT (module → host) — the PACKED return
//! The snapshot/tick/status ops return a single `u64` (an `i64` bit pattern to a
//! JS host) that PACKS pointer and length:
//!
//! ```text
//!     packed: u64  ==  (ptr as u64) << 32 | (len as u64 & 0xFFFF_FFFF)
//!     ptr = (packed >> 32) as u32
//!     len = (packed & 0xFFFF_FFFF) as u32
//! ```
//!
//! The pointed-to bytes are a UTF-8 JSON string the host should read
//! immediately. The buffer is a **leaked** `Box<[u8]>` owned by the module; the
//! host MUST hand it back via `dealloc(ptr, len)` once read, or it leaks for the
//! life of the instance. The `alloc`/`dealloc` exports remain for symmetry with
//! the platform's other shims (and a future buffer-input op), even though the
//! realtime ops here take only integer arguments.
//!
//! ### The ops (mirror `snapdragon_tron::Game`, reusing its OWN JSON)
//!  * `tron_new(seed: i64) -> i32`
//!        Build/replace the global match on the default `20 x 20` grid seeded by
//!        `seed` (the seed is taken as a `u64` bit pattern). Always returns `1`.
//!  * `tron_new_sized(seed: i64, w: i32, h: i32) -> i32`
//!        Build/replace the global match on a `w x h` grid (clamped up to `3 x 3`
//!        by the engine). Always returns `1`. Used to set up tight scenarios /
//!        forced terminals.
//!  * `tron_set_dir(player: i32, dir: i32) -> i32`
//!        Queue `player`'s heading for the NEXT tick. `player` is `0` (One) or
//!        `1` (Two); `dir` is `0`=up `1`=down `2`=left `3`=right. Returns `1` if
//!        the direction was accepted, `0` if it was rejected — an illegal 180°
//!        reversal, the game is already over, no game, or a bad player/dir code.
//!  * `tron_tick() -> u64`  → packed (ptr<<32|len) of the engine's OWN
//!        `Outcome::to_json` wire payload for the tick that just advanced
//!        (`{"type":"tron.tick","tick","oneCrashed","twoCrashed","status"}`). This
//!        is the host-authoritative `world` event the guest re-validates. Empty
//!        object `{}` when there is no game.
//!  * `tron_snapshot() -> u64`  → packed `Game::snapshot()` (alias of
//!        `to_json`), the `{"type":"tron.snapshot","width","height","tick",
//!        "board","one":{x,y,dir,alive},"two":{...},"status"}` full-state payload
//!        for the CURRENT position (the fen-equivalent snapshot a guest re-seats
//!        from). Empty object `{}` when there is no game.
//!  * `tron_status() -> u64`  → packed `{"status":"<wire>"}` where `<wire>` is the
//!        engine's `GameStatus::wire()` string (`"playing"`, `"win:one"`,
//!        `"win:two"`, `"draw"`). Empty status when there is no game.

#![allow(clippy::missing_safety_doc)]

// std is in the graph via the core crate; we use it directly. On
// wasm32-unknown-unknown this is the freestanding std (no wasi/emscripten host
// imports for pure compute + alloc). panic="abort" (in Cargo.toml) means a panic
// traps the instance — the right failure mode for an authoritative oracle.
use std::boxed::Box;
use std::string::String;
use std::vec::Vec;

use snapdragon_tron::{Dir, Game, Player};

// ─────────────────────────────────────────────────────────────────────────────
//  The single global match instance.
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
//  Tiny JSON encoder (no serde — keeps the module host-import-free). We only EMIT
//  JSON here; the engine already emits the rich Outcome / snapshot payloads, so
//  this single helper covers the one simple shape the shim itself synthesises
//  (the status object).
// ─────────────────────────────────────────────────────────────────────────────

/// Escape a string into a JSON string body (no surrounding quotes). Mirrors the
/// engine's own escaping: backslash + double-quote only (the data we wrap — the
/// wire status string — contains no control characters).
fn push_json_escaped(out: &mut String, s: &str) {
    for c in s.chars() {
        match c {
            '\\' => out.push_str("\\\\"),
            '"' => out.push_str("\\\""),
            _ => out.push(c),
        }
    }
}

/// `{"<key>":"<value>"}` with `value` JSON-escaped.
fn single_field_object(key: &str, value: &str) -> Vec<u8> {
    let mut s = String::with_capacity(key.len() + value.len() + 8);
    s.push('{');
    s.push('"');
    push_json_escaped(&mut s, key);
    s.push('"');
    s.push(':');
    s.push('"');
    push_json_escaped(&mut s, value);
    s.push('"');
    s.push('}');
    s.into_bytes()
}

// ─────────────────────────────────────────────────────────────────────────────
//  Integer code → engine enum mappers.
// ─────────────────────────────────────────────────────────────────────────────

/// `0` → One, `1` → Two; any other code is rejected.
fn player_from_code(code: i32) -> Option<Player> {
    match code {
        0 => Some(Player::One),
        1 => Some(Player::Two),
        _ => None,
    }
}

/// `0`=up `1`=down `2`=left `3`=right; any other code is rejected.
fn dir_from_code(code: i32) -> Option<Dir> {
    match code {
        0 => Some(Dir::Up),
        1 => Some(Dir::Down),
        2 => Some(Dir::Left),
        3 => Some(Dir::Right),
        _ => None,
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  The exported ops.
// ─────────────────────────────────────────────────────────────────────────────

/// Build / replace the global match on the engine's default grid, seeded by
/// `seed` (taken as a `u64` bit pattern). Returns 1.
#[no_mangle]
pub unsafe extern "C" fn tron_new(seed: i64) -> i32 {
    set_game(Game::new(seed as u64));
    1
}

/// Build / replace the global match on a `w x h` grid seeded by `seed`
/// (the engine clamps a grid smaller than `3 x 3` up to `3 x 3`). Returns 1.
#[no_mangle]
pub unsafe extern "C" fn tron_new_sized(seed: i64, w: i32, h: i32) -> i32 {
    set_game(Game::with_size(seed as u64, w, h));
    1
}

/// Queue `player`'s heading for the next [`tron_tick`]. `player` is `0` (One) /
/// `1` (Two); `dir` is `0`=up `1`=down `2`=left `3`=right. Returns 1 when the
/// engine accepted the heading, 0 when it was rejected (illegal 180° reversal,
/// game over, no game, or a bad player/dir code).
#[no_mangle]
pub unsafe extern "C" fn tron_set_dir(player: i32, dir: i32) -> i32 {
    let p = match player_from_code(player) {
        Some(p) => p,
        None => return 0,
    };
    let d = match dir_from_code(dir) {
        Some(d) => d,
        None => return 0,
    };
    match game_mut() {
        Some(g) => i32::from(g.set_dir(p, d)),
        None => 0,
    }
}

/// Advance the simulation one fixed timestep. Returns packed (ptr<<32|len) of the
/// engine's OWN `Outcome::to_json` for the tick that just completed
/// (`{"type":"tron.tick","tick","oneCrashed","twoCrashed","status"}`) — the
/// host-authoritative `world` event. Empty object `{}` when there is no game.
#[no_mangle]
pub unsafe extern "C" fn tron_tick() -> u64 {
    match game_mut() {
        Some(g) => leak_packed(g.tick().to_json().into_bytes()),
        None => leak_packed(b"{}".to_vec()),
    }
}

/// Packed (ptr<<32|len) of the engine's `Game::snapshot()` for the CURRENT
/// position — the fen-equivalent full-state snapshot
/// (`{"type":"tron.snapshot","width","height","tick","board","one","two",
/// "status"}`). Empty object `{}` when there is no game.
#[no_mangle]
pub unsafe extern "C" fn tron_snapshot() -> u64 {
    let json = match game_ref() {
        Some(g) => g.snapshot(),
        None => String::from("{}"),
    };
    leak_packed(json.into_bytes())
}

/// Packed (ptr<<32|len) of `{"status":"<wire>"}` where `<wire>` is the engine's
/// `GameStatus::wire()` (`"playing"` / `"win:one"` / `"win:two"` / `"draw"`).
/// Empty status when there is no game.
#[no_mangle]
pub unsafe extern "C" fn tron_status() -> u64 {
    let wire = match game_ref() {
        Some(g) => g.status().wire(),
        None => String::new(),
    };
    leak_packed(single_field_object("status", &wire))
}
