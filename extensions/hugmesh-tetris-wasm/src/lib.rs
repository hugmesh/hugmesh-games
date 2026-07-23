//! `hugmesh-tetris-wasm` — a **thin, freestanding C-ABI / JSON shim** around
//! the pure [`hugmesh_tetris`] puzzle engine so a host with NO JavaScript glue
//! can drive a deterministic, seed-based single-player Tetris: it loads in a
//! browser via `WebAssembly.instantiateStreaming` (empty imports) AND in a JVM
//! WASM runtime such as Chicory.
//!
//! ## What this module is
//!
//! * Plain `std` (NOT `no_std`). The core crate `hugmesh_tetris` itself uses
//!   `std` (`Vec`/`String`/`format!`), so std is unavoidably in the dependency
//!   graph; declaring `#![no_std]` here only triggers a duplicate `panic_impl`
//!   lang-item clash. Crucially, the `wasm32-unknown-unknown` std is the
//!   *freestanding* std — it is NOT wasi and NOT emscripten, so a pure-compute +
//!   `alloc` module like this one links with **zero host imports** (verified
//!   post-build by scanning the wasm import section). The host need only provide
//!   the module's own linear `memory`.
//! * There is NO `wasm-bindgen`, NO `wasi`, NO emscripten. The only dependency is
//!   the path crate `hugmesh-tetris`, left completely UNMODIFIED. The engine
//!   ALREADY emits its own JSON (`Game::to_json` / `Game::snapshot` and
//!   `GameStatus::wire`), so this shim never invents the snapshot wire shape and
//!   never pulls in serde or a host.
//!
//! ## SINGLE-PLAYER PUZZLE — local only, no relay
//!
//! Tetris is a SINGLE-PLAYER seed-based puzzle: there is NO networking, NO SSE
//! relay, NO opponent. The browser news the engine with a seed (`tetris_new`),
//! applies the player's actions locally, and renders the snapshot. The server is
//! never involved. Determinism comes entirely from the seed: the same seed +
//! the same input sequence reproduces the identical game.
//!
//! ## Instance model — SINGLE GLOBAL instance
//!
//! This shim keeps ONE global `Game` instance (`static mut GAME`). `tetris_new`
//! (re)creates it from a seed. **The host instantiates the wasm module once per
//! game**: a host that needs concurrent games instantiates the module multiple
//! times (each instance has its own private linear memory + global), which is the
//! idiomatic browser / Chicory pattern. The wasm is single-threaded so the
//! `static mut` access is sound (no concurrent callers within one instance).
//!
//! ## Memory contract (READ THIS before calling)
//!
//! All strings crossing the boundary are UTF-8 bytes in the module's linear
//! memory. The module owns output buffers; the host owns any input buffers.
//!
//! ### Receiving bytes OUT (module → host) — the PACKED return
//! The query/action ops return a single `u64` (an `i64` bit pattern to a JS host)
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
//! ### Passing bytes IN (host → module)
//! Only the allocator pair (`alloc`/`dealloc`) crosses the boundary as a buffer;
//! the gameplay ops here take **only scalar arguments** (`tetris_new(seed: u64)`,
//! `tetris_filled(col, row)`), so no input buffer is needed for them. `alloc`/
//! `dealloc` remain exported because the shared `WasmHost` contract requires
//! them and the host uses `dealloc` to hand packed output buffers back.
//!
//! ### The ops (mirror `hugmesh_tetris::Game`, reusing its OWN JSON)
//!  * `tetris_new(seed: u64) -> i32`
//!        Build/replace the global game seeded by `seed`; the first piece is dealt
//!        and spawned. Always returns `1`.
//!  * `tetris_left()  -> u64`  → packed `Game::to_json()` snapshot AFTER trying to
//!        shift the active piece one column left. A blocked move leaves the state
//!        unchanged — the snapshot simply reflects the unchanged position (the
//!        host always gets the authoritative state; there is no separate "rejected"
//!        wire for a no-op nudge).
//!  * `tetris_right() -> u64`  → as above, one column right.
//!  * `tetris_rotate_cw()  -> u64`  → snapshot after a clockwise rotation (with the
//!        engine's wall kicks).
//!  * `tetris_rotate_ccw() -> u64`  → snapshot after a counter-clockwise rotation.
//!  * `tetris_soft_drop()  -> u64`  → snapshot after one soft-drop row (does NOT
//!        lock; awards 1 point per cell descended in the engine).
//!  * `tetris_hard_drop()  -> u64`  → snapshot after slamming the piece down,
//!        locking it, clearing any full lines, scoring, and spawning the next
//!        piece (which may end the game).
//!  * `tetris_hold()       -> u64`  → snapshot after holding/swapping the active
//!        piece (one hold per piece). A hold attempted twice, or after game-over,
//!        leaves the state unchanged.
//!  * `tetris_tick()       -> u64`  → ONE gravity step: the piece falls one row, or
//!        locks (clear/score/spawn) if it cannot. Drive this from
//!        `requestAnimationFrame` on an interval that shortens with `level` (the
//!        snapshot's `"level"` field; the engine's `gravity_period()` curve).
//!        After game-over the snapshot is returned unchanged.
//!  * `tetris_snapshot() -> u64`  → packed `Game::to_json()` for the CURRENT
//!        position WITHOUT mutating — the full
//!        `{"type":"tetris.snapshot","width","height","board","active","held",
//!        "canHold","next","score","lines","level","status"}` payload. Empty
//!        object `{}` when there is no game.
//!  * `tetris_status() -> u64`  → packed `{"status":"<wire>"}` where `<wire>` is
//!        the engine's `GameStatus::wire()` string (`"playing"` or
//!        `"gameover:<score>:<lines>"`). Empty status when no game.
//!  * `tetris_filled(col: i32, row: i32) -> i32`  → `1` if the LOCKED cell at
//!        `(col, row)` is filled (the active piece is NOT merged), else `0`. `0`
//!        for an out-of-range cell or when there is no game.

#![allow(clippy::missing_safety_doc)]

// std is in the graph via the core crate; we use it directly. On
// wasm32-unknown-unknown this is the freestanding std (no wasi/emscripten host
// imports for pure compute + alloc). panic="abort" (in Cargo.toml) means a panic
// traps the instance — the right failure mode for a deterministic engine.
use std::boxed::Box;
use std::string::String;
use std::vec::Vec;

use hugmesh_tetris::{Game, HEIGHT, WIDTH};

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
//  Tiny JSON encoder (no serde — keeps the module host-import-free). We only
//  ever EMIT JSON here; the engine already emits the rich snapshot payload, so
//  this helper covers the single simple shape the shim itself synthesises (the
//  status object). The status wire string contains no control characters, so a
//  backslash + double-quote escape mirrors the engine's own escaping.
// ─────────────────────────────────────────────────────────────────────────────

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

/// The current game's `to_json()` snapshot, or `{}` when there is no game.
/// Every action op returns THIS after attempting its mutation, so the host
/// always receives the authoritative position.
unsafe fn snapshot_bytes() -> Vec<u8> {
    match game_ref() {
        Some(g) => g.to_json().into_bytes(),
        None => String::from("{}").into_bytes(),
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  The exported ops.
// ─────────────────────────────────────────────────────────────────────────────

/// Build / replace the global game seeded by `seed`; the first piece is dealt and
/// spawned. Always returns `1`.
#[no_mangle]
pub unsafe extern "C" fn tetris_new(seed: u64) -> i32 {
    set_game(Game::new(seed));
    1
}

/// Shift the active piece one column left, then return the packed snapshot. A
/// blocked nudge leaves the position unchanged.
#[no_mangle]
pub unsafe extern "C" fn tetris_left() -> u64 {
    if let Some(g) = game_mut() {
        let _ = g.move_left();
    }
    leak_packed(snapshot_bytes())
}

/// Shift the active piece one column right, then return the packed snapshot.
#[no_mangle]
pub unsafe extern "C" fn tetris_right() -> u64 {
    if let Some(g) = game_mut() {
        let _ = g.move_right();
    }
    leak_packed(snapshot_bytes())
}

/// Rotate the active piece clockwise (engine wall kicks), then packed snapshot.
#[no_mangle]
pub unsafe extern "C" fn tetris_rotate_cw() -> u64 {
    if let Some(g) = game_mut() {
        let _ = g.rotate_cw();
    }
    leak_packed(snapshot_bytes())
}

/// Rotate the active piece counter-clockwise, then packed snapshot.
#[no_mangle]
pub unsafe extern "C" fn tetris_rotate_ccw() -> u64 {
    if let Some(g) = game_mut() {
        let _ = g.rotate_ccw();
    }
    leak_packed(snapshot_bytes())
}

/// Soft-drop the active piece one row (no lock; +1 score per cell), then packed
/// snapshot.
#[no_mangle]
pub unsafe extern "C" fn tetris_soft_drop() -> u64 {
    if let Some(g) = game_mut() {
        let _ = g.soft_drop();
    }
    leak_packed(snapshot_bytes())
}

/// Hard-drop the active piece (lock + clear + score + spawn next), then packed
/// snapshot. May end the game.
#[no_mangle]
pub unsafe extern "C" fn tetris_hard_drop() -> u64 {
    if let Some(g) = game_mut() {
        let _ = g.hard_drop();
    }
    leak_packed(snapshot_bytes())
}

/// Hold / swap the active piece (one hold per piece), then packed snapshot.
#[no_mangle]
pub unsafe extern "C" fn tetris_hold() -> u64 {
    if let Some(g) = game_mut() {
        let _ = g.hold();
    }
    leak_packed(snapshot_bytes())
}

/// One gravity step: the piece falls one row, or locks (clear/score/spawn) if it
/// cannot. Returns the packed snapshot. Drive from requestAnimationFrame at an
/// interval that shortens with `level`.
#[no_mangle]
pub unsafe extern "C" fn tetris_tick() -> u64 {
    if let Some(g) = game_mut() {
        let _ = g.tick();
    }
    leak_packed(snapshot_bytes())
}

/// Packed `Game::to_json()` for the CURRENT position WITHOUT mutating. `{}` when
/// there is no game.
#[no_mangle]
pub unsafe extern "C" fn tetris_snapshot() -> u64 {
    leak_packed(snapshot_bytes())
}

/// Packed `{"status":"<wire>"}` where `<wire>` is the engine's `GameStatus::wire()`
/// (`"playing"` or `"gameover:<score>:<lines>"`). Empty status when no game.
#[no_mangle]
pub unsafe extern "C" fn tetris_status() -> u64 {
    let wire = match game_ref() {
        Some(g) => g.status().wire(),
        None => String::new(),
    };
    leak_packed(single_field_object("status", &wire))
}

/// `1` if the LOCKED cell at `(col, row)` is filled (active piece NOT merged),
/// else `0`. `0` for an out-of-range cell or when there is no game.
#[no_mangle]
pub unsafe extern "C" fn tetris_filled(col: i32, row: i32) -> i32 {
    if col < 0 || row < 0 || col >= WIDTH as i32 || row >= HEIGHT as i32 {
        return 0;
    }
    match game_ref() {
        Some(g) if g.filled(col as usize, row as usize) => 1,
        _ => 0,
    }
}
