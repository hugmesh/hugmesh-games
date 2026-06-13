//! `snapdragon-go-wasm` — a **thin, freestanding C-ABI / JSON shim** around the
//! pure [`snapdragon_go`] rules engine so a host with NO JavaScript glue can
//! drive authoritative Go (Weiqi / Baduk): it loads in a browser via
//! `WebAssembly.instantiateStreaming` (empty imports) AND in a JVM WASM runtime
//! such as Chicory.
//!
//! It is the EXACT structural twin of the proven `snapdragon-chess-wasm` /
//! `snapdragon-gomoku-wasm` / `snapdragon-connect-four-wasm` shims.
//!
//! ## What this module is
//!
//! * Plain `std` (NOT `no_std`). The core crate `snapdragon-go` itself uses
//!   `std` (`Vec`/`String`/`format!`), so std is unavoidably in the dependency
//!   graph; declaring `#![no_std]` here only triggers a duplicate `panic_impl`
//!   lang-item clash. Crucially, the `wasm32-unknown-unknown` std is the
//!   *freestanding* std — it is NOT wasi and NOT emscripten, so a pure-compute +
//!   `alloc` module like this one links with **zero host imports** (verified
//!   post-build by scanning the wasm import section). The host need only provide
//!   the module's own linear `memory`.
//! * There is NO `wasm-bindgen`, NO `wasi`, NO emscripten. The only dependency is
//!   the path crate `snapdragon-go`, left completely UNMODIFIED. The engine
//!   ALREADY emits its own JSON (`Outcome::to_json` / `Game::snapshot_json` /
//!   `GameStatus::wire`), so this shim never invents a wire shape and never pulls
//!   in serde or a host.
//!
//! ## Instance model — SINGLE GLOBAL instance
//!
//! This shim keeps ONE global `Game` instance (`static mut GAME`). `go_new` /
//! `go_new_size` / `go_from_board` (re)create it. **The host instantiates the
//! wasm module once per board**: a host that needs concurrent games instantiates
//! the module multiple times (each instance has its own private linear memory +
//! global), which is the idiomatic browser / Chicory pattern. The wasm is
//! single-threaded so the `static mut` access is sound (no concurrent callers
//! within one instance). The board `size` is read back from the live `Game`
//! itself (`Game::size()`), so coordinate ↔ index mapping always agrees with the
//! engine.
//!
//! ## Memory contract (READ THIS before calling)
//!
//! All strings crossing the boundary are UTF-8 bytes in the module's linear
//! memory. The host owns input buffers; the module owns output buffers. Identical
//! to the chess shim:
//!
//! ### Passing bytes IN (host → module)
//!  1. host calls `alloc(len) -> ptr` to get a `len`-byte region it may write to;
//!  2. host writes its bytes (a coordinate string like `"c3"`, or a board string
//!     for `go_from_board`) into `[ptr, ptr+len)`;
//!  3. host calls an op with `(ptr, len)` as the LAST two args;
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
//! ### The ops (mirror `snapdragon_go::Game`, reusing its OWN JSON)
//!  * `go_new() -> i32`
//!        Build/replace the global game at the empty 9×9 board (Black to move).
//!        Always returns `1`.
//!  * `go_new_size(size: u32) -> i32`
//!        Build/replace the global game at an empty `size`×`size` board (clamped
//!        to `2..=25` by the engine). Always returns `1`.
//!  * `go_from_board(boardPtr, boardLen, size: u32, side: u32) -> i32`
//!        Build/replace the global game from a board string (`'B'`/`'W'`/`'.'`,
//!        point 0 first, BOTTOM row up — the engine's `from_board` layout) plus a
//!        side to move (`side == 0` ⇒ Black, anything else ⇒ White). Returns `1`
//!        if the board parsed, `0` otherwise (the existing global game is left
//!        UNCHANGED on `0`). This is the world-resync fallback the host uses when
//!        a relayed move can't be re-applied incrementally. NOTE: the leading
//!        scalar args (`size`, `side`) come BEFORE the `(ptr,len)` pair, matching
//!        the `WasmHost.callRaw(fn, str, [leadingArgs])` convention — but the ABI
//!        order is `(ptr, len, size, side)`: the FIRST two are the buffer, the
//!        LAST two the scalars (see the export signature).
//!  * `go_legal() -> u64`  → packed JSON **array of coordinate strings** for the
//!        empty legal points of the side to move, e.g. `["a1","b1",...]`. `[]`
//!        when there is no game or the game is over. (The pass move is always
//!        available while ongoing and is NOT listed here — drive it via `go_pass`
//!        or `go_apply("pass")`.)
//!  * `go_apply(ptr, len) -> u64`  → packed. The buffer is a coordinate (`"c3"`)
//!        OR the literal `"pass"`. On a legal play/pass, exactly the engine's OWN
//!        `Outcome::to_json(size)` wire payload
//!        (`{"type":"go.state","lastMove","mover","captured":[...],"blackCaptures",
//!        "whiteCaptures","next","status"}`). On an illegal / unparseable move (or
//!        no game), the error shape `{"type":"go.error","error":"..."}`.
//!  * `go_pass() -> u64`  → packed. Convenience for `go_apply("pass")`: passes the
//!        turn (two consecutive passes end the game). Same `go.state` / `go.error`
//!        shapes.
//!  * `go_state() -> u64`  → packed `Game::snapshot_json()` — the current full
//!        position (`{"type":"go.snapshot","size","board","side","blackCaptures",
//!        "whiteCaptures","areaBlack","areaWhite","komi","status"}`). An empty
//!        `{}` object when there is no game.
//!  * `go_status() -> u64`  → packed `{"status":"<wire>"}` where `<wire>` is the
//!        engine's `GameStatus::wire()` string (e.g. `"ongoing"`,
//!        `"ended:black:10:7"`, `"ended:draw:5:5"`). Empty status when no game.

#![allow(clippy::missing_safety_doc)]

// std is in the graph via the core crate; we use it directly. On
// wasm32-unknown-unknown this is the freestanding std (no wasi/emscripten host
// imports for pure compute + alloc). panic="abort" (in Cargo.toml) means a panic
// traps the instance — the right failure mode for an authoritative oracle.
use std::boxed::Box;
use std::string::String;
use std::vec::Vec;

use snapdragon_go::{point_str, Game, MoveError, Player};

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
/// (no game, illegal move, bad coordinate) which is the correct fail-closed
/// behaviour.
unsafe fn str_in<'a>(ptr: u32, len: u32) -> &'a str {
    if ptr == 0 || len == 0 {
        return "";
    }
    let bytes = core::slice::from_raw_parts(ptr as *const u8, len as usize);
    core::str::from_utf8(bytes).unwrap_or("")
}

// ─────────────────────────────────────────────────────────────────────────────
//  Tiny JSON encoders (no serde — keeps the module host-import-free). We only
//  ever EMIT JSON here; the engine already emits the rich Outcome / snapshot
//  payloads, so these helpers cover the simple shapes the shim itself synthesises
//  plus the coordinate-array for legal moves.
// ─────────────────────────────────────────────────────────────────────────────

/// Escape a string into a JSON string body (no surrounding quotes). Mirrors the
/// engine's own escaping: backslash + double-quote only (the data we wrap —
/// coordinate, wire status, error text — contains no control characters).
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

/// `{"type":"go.error","error":"<reason>"}` as bytes.
fn error_payload(reason: &str) -> Vec<u8> {
    let mut s = String::with_capacity(reason.len() + 32);
    s.push_str("{\"type\":\"go.error\",\"error\":\"");
    push_json_escaped(&mut s, reason);
    s.push_str("\"}");
    s.into_bytes()
}

/// Map the engine's `MoveError` to a stable, human-readable wire reason.
fn move_error_str(e: MoveError) -> &'static str {
    match e {
        MoveError::BadPoint => "bad-point",
        MoveError::Occupied => "occupied",
        MoveError::Suicide => "suicide",
        MoveError::Ko => "ko",
        MoveError::GameOver => "game-over",
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  The exported ops.
// ─────────────────────────────────────────────────────────────────────────────

/// Build / replace the global game at the empty 9×9 board (Black to move).
/// Returns 1.
#[no_mangle]
pub unsafe extern "C" fn go_new() -> i32 {
    set_game(Game::new(9));
    1
}

/// Build / replace the global game at an empty `size`×`size` board (the engine
/// clamps to `2..=25`), Black to move. Returns 1.
#[no_mangle]
pub unsafe extern "C" fn go_new_size(size: u32) -> i32 {
    set_game(Game::new(size as usize));
    1
}

/// Build / replace the global game from a board string + size + side to move. The
/// board string is `'B'`/`'W'`/`'.'` (point 0 first, BOTTOM row up — the engine's
/// `from_board` layout); `side == 0` ⇒ Black, anything else ⇒ White. Returns 1 if
/// the board parsed, 0 otherwise (existing game left UNCHANGED on 0). This is the
/// world-resync fallback (the engine has no FEN; the board+side is its restorable
/// canonical state).
///
/// ABI: the `(ptr, len)` buffer pair come FIRST, the `size`/`side` scalars LAST,
/// so a `WasmHost.callRaw('go_from_board', boardStr, [size, side])` would NOT
/// match — drive it via the trailing-scalar order documented here (the JS
/// frontend calls it directly through the same alloc/(ptr,len)/dealloc dance).
#[no_mangle]
pub unsafe extern "C" fn go_from_board(ptr: u32, len: u32, size: u32, side: u32) -> i32 {
    let cells = str_in(ptr, len);
    let mover = if side == 0 { Player::Black } else { Player::White };
    match Game::from_board(cells, size as usize, mover) {
        Ok(g) => {
            set_game(g);
            1
        }
        Err(_) => 0,
    }
}

/// Packed (ptr<<32|len) of a JSON array of coordinate strings for the empty legal
/// points of the side to move, e.g. `["a1","b1",...]`. `[]` when there is no game
/// or the game is over. The pass move is not listed (drive it via `go_pass` /
/// `go_apply("pass")`).
#[no_mangle]
pub unsafe extern "C" fn go_legal() -> u64 {
    let mut s = String::new();
    s.push('[');
    if let Some(g) = game_ref() {
        let size = g.size();
        let mut first = true;
        for idx in g.legal_moves() {
            if !first {
                s.push(',');
            }
            first = false;
            s.push('"');
            s.push_str(&point_str(idx, size));
            s.push('"');
        }
    }
    s.push(']');
    leak_packed(s.into_bytes())
}

/// Apply a move named in the buffer: a coordinate (`"c3"`) OR the literal `"pass"`
/// (case-insensitive). On success, packed (ptr<<32|len) of the engine's OWN
/// `Outcome::to_json(size)` wire payload. On an illegal / unparseable move (or no
/// game), packed of `{"type":"go.error","error":"..."}`.
#[no_mangle]
pub unsafe extern "C" fn go_apply(ptr: u32, len: u32) -> u64 {
    let raw = str_in(ptr, len);
    let token = raw.trim();
    match game_mut() {
        None => leak_packed(error_payload("no-game")),
        Some(g) => {
            let size = g.size();
            let result = if token.eq_ignore_ascii_case("pass") {
                g.pass()
            } else {
                g.play_str(token)
            };
            match result {
                Ok(outcome) => leak_packed(outcome.to_json(size).into_bytes()),
                Err(e) => leak_packed(error_payload(move_error_str(e))),
            }
        }
    }
}

/// Pass the turn (two consecutive passes end the game). Packed (ptr<<32|len) of
/// the engine's OWN `Outcome::to_json(size)` payload, or `go.error` (no game /
/// game-over).
#[no_mangle]
pub unsafe extern "C" fn go_pass() -> u64 {
    match game_mut() {
        None => leak_packed(error_payload("no-game")),
        Some(g) => {
            let size = g.size();
            match g.pass() {
                Ok(outcome) => leak_packed(outcome.to_json(size).into_bytes()),
                Err(e) => leak_packed(error_payload(move_error_str(e))),
            }
        }
    }
}

/// Packed (ptr<<32|len) of the engine's `Game::snapshot_json()` for the current
/// position (the `go.snapshot` shape carrying size, full board string, side to
/// move, capture counts, area scores, komi, status). An empty `{}` object when
/// there is no game.
#[no_mangle]
pub unsafe extern "C" fn go_state() -> u64 {
    let json = match game_ref() {
        Some(g) => g.snapshot_json(),
        None => String::from("{}"),
    };
    leak_packed(json.into_bytes())
}

/// Packed (ptr<<32|len) of `{"status":"<wire>"}` where `<wire>` is the engine's
/// `GameStatus::wire()` (e.g. `"ongoing"`, `"ended:black:10:7"`). Empty status
/// when there is no game.
#[no_mangle]
pub unsafe extern "C" fn go_status() -> u64 {
    let wire = match game_ref() {
        Some(g) => g.status().wire(),
        None => String::new(),
    };
    leak_packed(single_field_object("status", &wire))
}
