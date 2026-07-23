//! `hugmesh-reversi-wasm` — a **thin, freestanding C-ABI / JSON shim** around
//! the pure [`hugmesh_reversi`] rules engine so a host with NO JavaScript glue
//! can drive authoritative Reversi/Othello: it loads in a browser via
//! `WebAssembly.instantiateStreaming` (empty imports) AND in a JVM WASM runtime
//! such as Chicory.
//!
//! ## What this module is
//!
//! * Plain `std` (NOT `no_std`). The core crate `hugmesh_reversi` itself uses
//!   `std` (`Vec`/`String`/`format!`), so std is unavoidably in the dependency
//!   graph; declaring `#![no_std]` here only triggers a duplicate `panic_impl`
//!   lang-item clash. Crucially, the `wasm32-unknown-unknown` std is the
//!   *freestanding* std — it is NOT wasi and NOT emscripten, so a pure-compute +
//!   `alloc` module like this one links with **zero host imports** (verified
//!   post-build by scanning the wasm import section). The host need only provide
//!   the module's own linear `memory`.
//! * There is NO `wasm-bindgen`, NO `wasi`, NO emscripten. The only dependency is
//!   the path crate `hugmesh-reversi`, left completely UNMODIFIED. The engine
//!   ALREADY emits its own JSON (`Outcome::to_json` / `Game::snapshot_json` /
//!   `GameStatus::wire`), so this shim never invents a wire shape and never pulls
//!   in serde or a host.
//!
//! ## Instance model — SINGLE GLOBAL instance
//!
//! This shim keeps ONE global `Game` instance (`static mut GAME`). `reversi_new`
//! / `reversi_from_board` (re)create it. **The host instantiates the wasm module
//! once per board**: a host that needs concurrent games instantiates the module
//! multiple times (each instance has its own private linear memory + global),
//! which is the idiomatic browser / Chicory pattern. The wasm is single-threaded
//! so the `static mut` access is sound (no concurrent callers within one
//! instance).
//!
//! ## Memory contract (READ THIS before calling)
//!
//! All strings crossing the boundary are UTF-8 bytes in the module's linear
//! memory. The host owns input buffers; the module owns output buffers.
//!
//! ### Passing bytes IN (host → module)
//!  1. host calls `alloc(len) -> ptr` to get a `len`-byte region it may write to;
//!  2. host writes its bytes (a square string `"d3"`, a 64-cell board string,
//!     a player name) into `[ptr, ptr+len)`;
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
//! ### The ops (mirror `hugmesh_reversi::Game`, reusing its OWN JSON)
//!  * `reversi_new() -> i32`
//!        Build/replace the global game at the standard Othello opening (Black to
//!        move). Always returns `1` (success — the opening is hard-coded in core).
//!  * `reversi_from_board(boardPtr, boardLen, sidePtr, sideLen) -> i32`
//!        Build/replace the global game from a 64-char board string (`'B'`/`'W'`/
//!        `'.'`, square 0 first) plus a side-to-move name (`"black"`/`"white"`,
//!        also `"b"`/`"w"`). Returns `1` on success, `0` on a bad board / side
//!        (the global game is left UNCHANGED on `0`). Used to set up endgames.
//!  * `reversi_legal() -> u64`  → packed JSON **array of algebraic squares** the
//!        side to move may play, e.g. `["c4","d3","e6","f5"]`. `[]` when there is
//!        no game or no legal move (the latter means the side must pass).
//!  * `reversi_apply(ptr, len) -> u64`  → packed. On a legal placement, exactly
//!        the engine's `Outcome::to_json` wire payload
//!        (`{"type":"reversi.state","lastMove","mover","flipped","black","white",
//!        "next","nextMustPass","status"}`). On an illegal placement (occupied /
//!        no-flip / out-of-range / game-over / unparseable, or no game), the error
//!        shape `{"type":"reversi.error","error":"..."}`.
//!  * `reversi_pass() -> u64`  → packed. When the side to move genuinely has no
//!        legal placement, the engine's `Outcome::to_json` for the pass (a
//!        `"lastMove":"pass"` payload). Otherwise (a move exists, game over, or no
//!        game) the `{"type":"reversi.error","error":"..."}` shape.
//!  * `reversi_state() -> u64`  → packed `Game::snapshot_json()`, the
//!        `{"type":"reversi.snapshot","board","side","black","white","legal",
//!        "status"}` payload for the CURRENT position (the fen-equivalent
//!        full-state snapshot). Empty object `{}` when there is no game.
//!  * `reversi_status() -> u64`  → packed `{"status":"<wire>"}` where `<wire>` is
//!        the engine's `GameStatus::wire()` string (`"ongoing"`, `"win:black"`,
//!        `"win:white"`, `"draw"`).

#![allow(clippy::missing_safety_doc)]

// std is in the graph via the core crate; we use it directly. On
// wasm32-unknown-unknown this is the freestanding std (no wasi/emscripten host
// imports for pure compute + alloc). panic="abort" (in Cargo.toml) means a panic
// traps the instance — the right failure mode for an authoritative oracle.
use std::boxed::Box;
use std::string::String;
use std::vec::Vec;

use hugmesh_reversi::{square_str, Game, MoveError, Player};

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
/// (no game, illegal move, bad board) which is the correct fail-closed behaviour.
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
//  payloads, so these helpers cover the two simple shapes the shim itself
//  synthesises plus the algebraic-square array for legal moves.
// ─────────────────────────────────────────────────────────────────────────────

/// Escape a string into a JSON string body (no surrounding quotes). Mirrors the
/// engine's own escaping: backslash + double-quote only (the data we wrap —
/// wire status, error text — contains no control characters).
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

/// `{"type":"reversi.error","error":"<reason>"}` as bytes.
fn error_payload(reason: &str) -> Vec<u8> {
    let mut s = String::with_capacity(reason.len() + 36);
    s.push_str("{\"type\":\"reversi.error\",\"error\":\"");
    push_json_escaped(&mut s, reason);
    s.push_str("\"}");
    s.into_bytes()
}

// ─────────────────────────────────────────────────────────────────────────────
//  The exported ops.
// ─────────────────────────────────────────────────────────────────────────────

/// Build / replace the global game at the standard Othello opening. Returns 1.
#[no_mangle]
pub unsafe extern "C" fn reversi_new() -> i32 {
    set_game(Game::new());
    1
}

/// Build / replace the global game from a 64-char board string + a side-to-move
/// name. Returns 1 on success, 0 on a bad board / unrecognised side (the existing
/// global game, if any, is left UNCHANGED on failure).
#[no_mangle]
pub unsafe extern "C" fn reversi_from_board(
    board_ptr: u32,
    board_len: u32,
    side_ptr: u32,
    side_len: u32,
) -> i32 {
    let board = str_in(board_ptr, board_len);
    let side_name = str_in(side_ptr, side_len);
    let side = match parse_player(side_name) {
        Some(p) => p,
        None => return 0,
    };
    match Game::from_board(board, side) {
        Ok(g) => {
            set_game(g);
            1
        }
        Err(_) => 0,
    }
}

/// Packed (ptr<<32|len) of a JSON array of algebraic squares the side to move may
/// play, e.g. `["c4","d3","e6","f5"]`. `[]` when there is no game or no legal move
/// (which means the side must pass).
#[no_mangle]
pub unsafe extern "C" fn reversi_legal() -> u64 {
    let mut s = String::new();
    s.push('[');
    if let Some(g) = game_ref() {
        let mut first = true;
        for sq in g.legal_moves() {
            if !first {
                s.push(',');
            }
            first = false;
            s.push('"');
            s.push_str(&square_str(sq));
            s.push('"');
        }
    }
    s.push(']');
    leak_packed(s.into_bytes())
}

/// Apply a placement named in algebraic coordinates (`"d3"`). On success, packed
/// (ptr<<32|len) of the engine's OWN `Outcome::to_json` wire payload. On an
/// illegal / unparseable placement (or no game), packed of
/// `{"type":"reversi.error","error":"..."}`.
#[no_mangle]
pub unsafe extern "C" fn reversi_apply(ptr: u32, len: u32) -> u64 {
    let sq = str_in(ptr, len);
    match game_mut() {
        None => leak_packed(error_payload("no-game")),
        Some(g) => match g.play_str(sq) {
            Ok(outcome) => leak_packed(outcome.to_json().into_bytes()),
            Err(e) => leak_packed(error_payload(move_error_str(e))),
        },
    }
}

/// Pass the turn (only legal when the side to move genuinely has no placement).
/// On success, packed (ptr<<32|len) of the engine's `Outcome::to_json` for the
/// pass. On an illegal pass (a move exists / game over / no game), packed of
/// `{"type":"reversi.error","error":"..."}`.
#[no_mangle]
pub unsafe extern "C" fn reversi_pass() -> u64 {
    match game_mut() {
        None => leak_packed(error_payload("no-game")),
        Some(g) => match g.pass() {
            Ok(outcome) => leak_packed(outcome.to_json().into_bytes()),
            Err(e) => leak_packed(error_payload(move_error_str(e))),
        },
    }
}

/// Map the engine's `MoveError` to a stable, human-readable wire reason.
fn move_error_str(e: MoveError) -> &'static str {
    match e {
        MoveError::BadSquare => "bad-square",
        MoveError::Occupied => "occupied",
        MoveError::NoFlips => "no-flips",
        MoveError::CannotPass => "cannot-pass",
        MoveError::GameOver => "game-over",
    }
}

/// Packed (ptr<<32|len) of the engine's `Game::snapshot_json()` for the CURRENT
/// position — the fen-equivalent full-state snapshot
/// (`{"type":"reversi.snapshot","board","side","black","white","legal","status"}`).
/// Empty object `{}` when there is no game.
#[no_mangle]
pub unsafe extern "C" fn reversi_state() -> u64 {
    let json = match game_ref() {
        Some(g) => g.snapshot_json(),
        None => String::from("{}"),
    };
    leak_packed(json.into_bytes())
}

/// Packed (ptr<<32|len) of `{"status":"<wire>"}` where `<wire>` is the engine's
/// `GameStatus::wire()` (`"ongoing"` / `"win:black"` / `"win:white"` / `"draw"`).
/// Empty status when no game.
#[no_mangle]
pub unsafe extern "C" fn reversi_status() -> u64 {
    let wire = match game_ref() {
        Some(g) => g.status().wire(),
        None => String::new(),
    };
    leak_packed(single_field_object("status", &wire))
}

/// Parse a player name. Case-insensitive; accepts full word or single letter.
fn parse_player(s: &str) -> Option<Player> {
    let lower: String = s.chars().map(|c| c.to_ascii_lowercase()).collect();
    match lower.as_str() {
        "black" | "b" => Some(Player::Black),
        "white" | "w" => Some(Player::White),
        _ => None,
    }
}
