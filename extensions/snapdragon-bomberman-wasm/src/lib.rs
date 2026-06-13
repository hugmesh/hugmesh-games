//! `snapdragon-bomberman-wasm` — a **thin, freestanding C-ABI / JSON shim** around
//! the pure [`snapdragon_bomberman`] tick-simulation engine so a host with NO
//! JavaScript glue can drive an authoritative two-player Bomberman duel: it loads
//! in a browser via `WebAssembly.instantiateStreaming` (empty imports) AND in a
//! JVM WASM runtime such as Chicory.
//!
//! ## Category — REALTIME DUEL (host-authoritative TICK LOOP)
//!
//! Per `docs/multiplayer-chess-design.md`, real-time games are HOST-AUTHORITATIVE
//! and run as WASM in the browser; the server only STREAMS inputs. The engine is a
//! pure **fixed-timestep tick simulation**: [`bomberman_input`] records a player's
//! intent for the upcoming tick and [`bomberman_tick`] advances the world by one
//! discrete step, resolving BOTH players' inputs simultaneously. The host runs the
//! authoritative copy and broadcasts the resulting snapshot on the `world` channel;
//! the guest applies those snapshots (or re-runs the same engine with the same
//! streamed inputs and re-validates). There is no wall-clock time inside the
//! engine — the caller (a `requestAnimationFrame` loop) decides the real cadence.
//!
//! ## What this module is
//!
//! * Plain `std` (NOT `no_std`). The core crate `snapdragon_bomberman` uses `std`
//!   (`Vec`/`String`/`format!`), so std is unavoidably in the dependency graph;
//!   declaring `#![no_std]` here only triggers a duplicate `panic_impl` lang-item
//!   clash. Crucially, the `wasm32-unknown-unknown` std is the *freestanding* std —
//!   it is NOT wasi and NOT emscripten, so a pure-compute + `alloc` module like
//!   this one links with **zero host imports** (verified post-build by scanning the
//!   wasm import section). The host need only provide the module's own linear
//!   `memory`.
//! * There is NO `wasm-bindgen`, NO `wasi`, NO emscripten. The only dependency is
//!   the path crate `snapdragon-bomberman`, left completely UNMODIFIED. The engine
//!   ALREADY emits its own JSON (`Game::to_json` / `Game::snapshot` /
//!   `Status::wire`), so this shim never invents a wire shape and never pulls in
//!   serde or a host.
//!
//! ## Instance model — SINGLE GLOBAL instance
//!
//! This shim keeps ONE global `Game` instance (`static mut GAME`). `bomberman_new`
//! (re)creates it from a seed. **The host instantiates the wasm module once per
//! match**: a host that needs concurrent games instantiates the module multiple
//! times (each instance has its own private linear memory + global), which is the
//! idiomatic browser / Chicory pattern. The wasm is single-threaded so the
//! `static mut` access is sound (no concurrent callers within one instance).
//!
//! ## Memory contract (READ THIS before calling)
//!
//! All strings crossing the boundary are UTF-8 bytes in the module's linear
//! memory. The host owns input buffers; the module owns output buffers.
//!
//! ### Passing bytes IN (host → module)
//!  1. host calls `alloc(len) -> ptr` to get a `len`-byte region it may write to;
//!  2. host writes its bytes (an input token like `"p1:up"`) into `[ptr, ptr+len)`;
//!  3. host calls an op with `(ptr, len)`;
//!  4. after the op returns, the host calls `dealloc(ptr, len)` to release it.
//!
//! ### Receiving bytes OUT (module → host) — the PACKED return
//! The query/apply ops return a single `u64` (an `i64` bit pattern to a JS host)
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
//! ### The ops (mirror `snapdragon_bomberman::Game`, reusing its OWN JSON)
//!  * `bomberman_new(seed: i64) -> i32`
//!        Build/replace the global game seeded by `seed` (the seed drives the
//!        destructible-block layout — same seed ⇒ identical board). Always returns
//!        `1`. The seed is taken as the leading scalar arg (no string buffer); a
//!        JS host calls `bomberman_new(BigInt(seed))`.
//!  * `bomberman_input(ptr, len) -> i32`
//!        Record ONE player's action for the next tick from a token
//!        `"<player>:<action>"` where player is `p1`/`p2`/`1`/`2` and action is one
//!        of `up`/`down`/`left`/`right` (move that direction), `bomb` (place a
//!        bomb), or `wait`. Returns `1` on accepted, `0` on a rejected/unparseable
//!        input (game over, the player already dead, or a bad token). Validity of a
//!        *move* (would it hit a wall?) is NOT checked here — it is resolved at
//!        tick time, so a blocked move simply has no effect.
//!  * `bomberman_tick() -> u64`  → packed JSON. Advance the world by exactly one
//!        discrete tick (resolving both players' recorded inputs simultaneously),
//!        then return the engine's `Game::to_json()` snapshot AFTER the tick
//!        (`{"type":"bomberman.snapshot","width","height","board","players",
//!        "bombs","flames","tick","status"}`). Empty object `{}` when there is no
//!        game. A tick on a finished match is a no-op that re-reports the terminal
//!        snapshot.
//!  * `bomberman_snapshot() -> u64`  → packed `Game::to_json()`, the full-state
//!        snapshot for the CURRENT position (no tick advance). Empty object `{}`
//!        when there is no game.
//!  * `bomberman_status() -> u64`  → packed `{"status":"<wire>"}` where `<wire>` is
//!        the engine's `Status::wire()` string (`"playing"`, `"win:p1"`,
//!        `"win:p2"`, `"draw"`). Empty status when no game.

#![allow(clippy::missing_safety_doc)]

// std is in the graph via the core crate; we use it directly. On
// wasm32-unknown-unknown this is the freestanding std (no wasi/emscripten host
// imports for pure compute + alloc). panic="abort" (in Cargo.toml) means a panic
// traps the instance — the right failure mode for an authoritative oracle.
use std::boxed::Box;
use std::string::String;
use std::vec::Vec;

use snapdragon_bomberman::{Action, Dir, Game, Player};

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

/// Read a host-provided buffer as a `&str` (UTF-8, no copy). Returns `""` for a
/// null/empty buffer or invalid UTF-8 — every consumer treats `""` as "bad input"
/// (no game, unparseable token) which is the correct fail-closed behaviour.
unsafe fn str_in<'a>(ptr: u32, len: u32) -> &'a str {
    if ptr == 0 || len == 0 {
        return "";
    }
    let bytes = core::slice::from_raw_parts(ptr as *const u8, len as usize);
    core::str::from_utf8(bytes).unwrap_or("")
}

// ─────────────────────────────────────────────────────────────────────────────
//  Tiny JSON encoder (no serde — keeps the module host-import-free). We only ever
//  EMIT JSON here for the one simple shape the shim synthesises (`{"status":...}`);
//  the engine already emits the rich snapshot payload.
// ─────────────────────────────────────────────────────────────────────────────

/// `{"status":"<wire>"}` as bytes. `wire` contains only `[a-z0-9:]` so no escaping
/// is needed.
fn status_payload(wire: &str) -> Vec<u8> {
    let mut s = String::with_capacity(wire.len() + 14);
    s.push_str("{\"status\":\"");
    s.push_str(wire);
    s.push_str("\"}");
    s.into_bytes()
}

// ─────────────────────────────────────────────────────────────────────────────
//  Input-token parsing.
// ─────────────────────────────────────────────────────────────────────────────

/// Parse a player name. Case-insensitive; accepts `p1`/`p2` or bare `1`/`2`.
fn parse_player(s: &str) -> Option<Player> {
    let lower: String = s.chars().map(|c| c.to_ascii_lowercase()).collect();
    match lower.as_str() {
        "p1" | "1" => Some(Player::P1),
        "p2" | "2" => Some(Player::P2),
        _ => None,
    }
}

/// Parse an action word into an [`Action`]. Case-insensitive.
fn parse_action(s: &str) -> Option<Action> {
    let lower: String = s.chars().map(|c| c.to_ascii_lowercase()).collect();
    match lower.as_str() {
        "up" => Some(Action::Move(Dir::Up)),
        "down" => Some(Action::Move(Dir::Down)),
        "left" => Some(Action::Move(Dir::Left)),
        "right" => Some(Action::Move(Dir::Right)),
        "bomb" => Some(Action::Bomb),
        "wait" | "" => Some(Action::Wait),
        _ => None,
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  The exported ops.
// ─────────────────────────────────────────────────────────────────────────────

/// Build / replace the global game seeded by `seed`. The seed drives ONLY the
/// destructible-block layout — same seed ⇒ identical board. Always returns 1.
#[no_mangle]
pub unsafe extern "C" fn bomberman_new(seed: i64) -> i32 {
    set_game(Game::new(seed as u64));
    1
}

/// Record ONE player's action for the next tick from a `"<player>:<action>"`
/// token. Returns 1 on accepted, 0 on a rejected/unparseable input (no game, game
/// over, the player already dead, or a bad token). The existing recorded action
/// for that player this tick (if any) is overwritten on success.
#[no_mangle]
pub unsafe extern "C" fn bomberman_input(ptr: u32, len: u32) -> i32 {
    let token = str_in(ptr, len);
    let (who, what) = match token.split_once(':') {
        Some(parts) => parts,
        None => return 0,
    };
    let player = match parse_player(who.trim()) {
        Some(p) => p,
        None => return 0,
    };
    let action = match parse_action(what.trim()) {
        Some(a) => a,
        None => return 0,
    };
    match game_mut() {
        None => 0,
        Some(g) => match g.set_action(player, action) {
            Ok(()) => 1,
            Err(_) => 0,
        },
    }
}

/// Advance the world by exactly one discrete tick (resolving both players' recorded
/// inputs simultaneously), then return packed (ptr<<32|len) of the engine's
/// `Game::to_json()` snapshot AFTER the tick. Empty object `{}` when there is no
/// game. A tick on a finished match is a no-op that re-reports the terminal
/// snapshot.
#[no_mangle]
pub unsafe extern "C" fn bomberman_tick() -> u64 {
    match game_mut() {
        None => leak_packed(String::from("{}").into_bytes()),
        Some(g) => {
            let _outcome = g.tick();
            leak_packed(g.to_json().into_bytes())
        }
    }
}

/// Packed (ptr<<32|len) of the engine's `Game::to_json()` for the CURRENT position
/// (no tick advance) — the full-state snapshot
/// (`{"type":"bomberman.snapshot","width","height","board","players","bombs",
/// "flames","tick","status"}`). Empty object `{}` when there is no game.
#[no_mangle]
pub unsafe extern "C" fn bomberman_snapshot() -> u64 {
    let json = match game_ref() {
        Some(g) => g.to_json(),
        None => String::from("{}"),
    };
    leak_packed(json.into_bytes())
}

/// Packed (ptr<<32|len) of `{"status":"<wire>"}` where `<wire>` is the engine's
/// `Status::wire()` (`"playing"` / `"win:p1"` / `"win:p2"` / `"draw"`). Empty
/// status when no game.
#[no_mangle]
pub unsafe extern "C" fn bomberman_status() -> u64 {
    let wire = match game_ref() {
        Some(g) => g.status().wire(),
        None => String::new(),
    };
    leak_packed(status_payload(&wire))
}
