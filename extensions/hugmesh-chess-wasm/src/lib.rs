//! `hugmesh-chess-wasm` — a **thin, freestanding C-ABI / JSON shim** around the
//! pure [`hugmesh_chess`] rules engine so a host with NO JavaScript glue can
//! drive authoritative chess: it loads in a browser via
//! `WebAssembly.instantiateStreaming` (empty imports) AND in a JVM WASM runtime
//! such as Chicory.
//!
//! ## What this module is
//!
//! * Plain `std` (NOT `no_std`). The core crate `hugmesh-chess` itself uses
//!   `std` (`Vec`/`String`/`format!`), so std is unavoidably in the dependency
//!   graph; declaring `#![no_std]` here only triggers a duplicate `panic_impl`
//!   lang-item clash. Crucially, the `wasm32-unknown-unknown` std is the
//!   *freestanding* std — it is NOT wasi and NOT emscripten, so a pure-compute +
//!   `alloc` module like this one links with **zero host imports** (verified
//!   post-build by scanning the wasm import section). The host need only provide
//!   the module's own linear `memory`.
//! * There is NO `wasm-bindgen`, NO `wasi`, NO emscripten. The only dependency is
//!   the path crate `hugmesh-chess`, left completely UNMODIFIED. The engine
//!   ALREADY emits its own JSON (`MoveOutcome::to_json` / `GameStatus::wire`), so
//!   this shim never invents a wire shape and never pulls in serde or a host.
//!
//! ## Instance model — SINGLE GLOBAL instance
//!
//! This shim keeps ONE global `Game` instance (`static mut GAME`). `chess_new` /
//! `chess_from_fen` (re)create it. **The host instantiates the wasm module once
//! per board**: a host that needs concurrent games instantiates the module
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
//!  2. host writes its bytes (a FEN string, a UCI string, a color name) into
//!     `[ptr, ptr+len)`;
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
//! ### The ops (mirror `hugmesh_chess::Game`, reusing its OWN JSON)
//!  * `chess_new() -> i32`
//!        Build/replace the global game at the standard start position. Always
//!        returns `1` (success — the start FEN is hard-coded in the core).
//!  * `chess_from_fen(ptr, len) -> i32`
//!        Build/replace the global game from a FEN string. Returns `1` on success,
//!        `0` on a bad FEN (the global game is left UNCHANGED on `0`).
//!  * `chess_legal_moves() -> u64`  → packed JSON **array of UCI strings** for the
//!        side to move, e.g. `["e2e4","e2e3",...]`. `[]` when there is no game.
//!  * `chess_apply_uci(ptr, len) -> u64`  → packed. On a legal move, exactly the
//!        engine's `MoveOutcome::to_json` wire payload
//!        (`{"type":"chess.state","lastMove","san","fen","status","check","mate"}`).
//!        On an illegal / unparseable move (or no game), the error shape
//!        `{"type":"chess.error","error":"..."}`.
//!  * `chess_fen() -> u64`  → packed `{"fen":"..."}` for the current position.
//!  * `chess_status() -> u64`  → packed `{"status":"<wire>"}` where `<wire>` is the
//!        engine's `GameStatus::wire()` string (e.g. `"checkmate:white"`,
//!        `"ongoing"`, `"draw:threefold"`).
//!  * `chess_resign(ptr, len) -> u64`  → packed `{"status":"<wire>"}` of the
//!        resulting status. The buffer names the resigning color: `"white"` /
//!        `"black"` (case-insensitive; also accepts `"w"` / `"b"`). The WINNER is
//!        the opponent (`GameStatus::Resigned { winner }`), matching the engine.

#![allow(clippy::missing_safety_doc)]

// std is in the graph via the core crate; we use it directly. On
// wasm32-unknown-unknown this is the freestanding std (no wasi/emscripten host
// imports for pure compute + alloc). panic="abort" (in Cargo.toml) means a panic
// traps the instance — the right failure mode for an authoritative oracle.
use std::boxed::Box;
use std::string::String;
use std::vec::Vec;

use hugmesh_chess::{square_str, Color, Game};

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
/// (no game, illegal move, bad fen) which is the correct fail-closed behaviour.
unsafe fn str_in<'a>(ptr: u32, len: u32) -> &'a str {
    if ptr == 0 || len == 0 {
        return "";
    }
    let bytes = core::slice::from_raw_parts(ptr as *const u8, len as usize);
    core::str::from_utf8(bytes).unwrap_or("")
}

// ─────────────────────────────────────────────────────────────────────────────
//  Tiny JSON encoders (no serde — keeps the module host-import-free). We only
//  ever EMIT JSON here; the engine already emits the rich MoveOutcome payload, so
//  these helpers cover the two simple shapes the shim itself synthesises plus the
//  UCI-array for legal_moves.
// ─────────────────────────────────────────────────────────────────────────────

/// Escape a string into a JSON string body (no surrounding quotes). Mirrors the
/// engine's own `json_escape`: backslash + double-quote only (the data we wrap —
/// FEN, wire status, error text — contains no control characters).
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
//  The exported ops.
// ─────────────────────────────────────────────────────────────────────────────

/// Build / replace the global game at the standard start position. Returns 1.
#[no_mangle]
pub unsafe extern "C" fn chess_new() -> i32 {
    set_game(Game::new());
    1
}

/// Build / replace the global game from a FEN string. Returns 1 on success, 0 on
/// a bad FEN (the existing global game, if any, is left UNCHANGED on failure).
#[no_mangle]
pub unsafe extern "C" fn chess_from_fen(ptr: u32, len: u32) -> i32 {
    let fen = str_in(ptr, len);
    match Game::from_fen(fen) {
        Ok(g) => {
            set_game(g);
            1
        }
        Err(_) => 0,
    }
}

/// Packed (ptr<<32|len) of a JSON array of UCI strings for the side to move.
/// `[]` when there is no game.
#[no_mangle]
pub unsafe extern "C" fn chess_legal_moves() -> u64 {
    let mut s = String::new();
    s.push('[');
    if let Some(g) = game_ref() {
        let mut first = true;
        for m in g.legal_moves() {
            if !first {
                s.push(',');
            }
            first = false;
            s.push('"');
            // UCI: from-square + to-square (+ optional promotion letter, lowercase).
            s.push_str(&square_str(m.from));
            s.push_str(&square_str(m.to));
            if let Some(k) = m.promotion {
                s.push(promo_letter_lower(k));
            }
            s.push('"');
        }
    }
    s.push(']');
    leak_packed(s.into_bytes())
}

/// Lowercase UCI promotion letter for a piece kind. Only the four promotable
/// kinds appear on a promotion move; anything else can't legally promote, so `'q'`
/// is a safe default it will never actually reach.
fn promo_letter_lower(k: hugmesh_chess::PieceKind) -> char {
    use hugmesh_chess::PieceKind::*;
    match k {
        Queen => 'q',
        Rook => 'r',
        Bishop => 'b',
        Knight => 'n',
        _ => 'q',
    }
}

/// Apply a UCI move. On success, packed (ptr<<32|len) of the engine's OWN
/// `MoveOutcome::to_json` wire payload. On an illegal/unparseable move (or no
/// game), packed of `{"type":"chess.error","error":"..."}`.
#[no_mangle]
pub unsafe extern "C" fn chess_apply_uci(ptr: u32, len: u32) -> u64 {
    let uci = str_in(ptr, len);
    match game_mut() {
        None => leak_packed(error_payload("no game")),
        Some(g) => match g.apply_uci(uci) {
            Ok(outcome) => leak_packed(outcome.to_json().into_bytes()),
            Err(e) => leak_packed(error_payload(move_error_str(e))),
        },
    }
}

/// Map the engine's `MoveError` to a stable, human-readable wire reason.
fn move_error_str(e: hugmesh_chess::MoveError) -> &'static str {
    use hugmesh_chess::MoveError::*;
    match e {
        BadUci => "bad-uci",
        IllegalMove => "illegal-move",
        GameOver => "game-over",
    }
}

/// `{"type":"chess.error","error":"<reason>"}` as bytes.
fn error_payload(reason: &str) -> Vec<u8> {
    let mut s = String::with_capacity(reason.len() + 32);
    s.push_str("{\"type\":\"chess.error\",\"error\":\"");
    push_json_escaped(&mut s, reason);
    s.push_str("\"}");
    s.into_bytes()
}

/// Packed (ptr<<32|len) of `{"fen":"<fen>"}` for the current position. Empty FEN
/// when there is no game.
#[no_mangle]
pub unsafe extern "C" fn chess_fen() -> u64 {
    let fen = match game_ref() {
        Some(g) => g.fen(),
        None => String::new(),
    };
    leak_packed(single_field_object("fen", &fen))
}

/// Packed (ptr<<32|len) of `{"status":"<wire>"}` where `<wire>` is the engine's
/// `GameStatus::wire()` (e.g. `"checkmate:white"`). Empty status when no game.
#[no_mangle]
pub unsafe extern "C" fn chess_status() -> u64 {
    let wire = match game_ref() {
        Some(g) => g.status().wire(),
        None => String::new(),
    };
    leak_packed(single_field_object("status", &wire))
}

/// Resign for the color named in the buffer (`"white"`/`"black"`, case-insensitive;
/// also `"w"`/`"b"`). The opponent becomes the winner (mirrors `Game::resign`).
/// Returns packed (ptr<<32|len) of the resulting `{"status":"<wire>"}`. A
/// missing game or unrecognised color is a no-op that returns the CURRENT status.
#[no_mangle]
pub unsafe extern "C" fn chess_resign(ptr: u32, len: u32) -> u64 {
    let name = str_in(ptr, len);
    let color = parse_color(name);
    let wire = match game_mut() {
        Some(g) => match color {
            Some(c) => g.resign(c).status.wire(),
            None => g.status().wire(), // unrecognised color → no state change
        },
        None => String::new(),
    };
    leak_packed(single_field_object("status", &wire))
}

/// Parse a color name. Case-insensitive; accepts full word or single letter.
fn parse_color(s: &str) -> Option<Color> {
    // ASCII-lowercase compare without allocating for the common short inputs.
    let lower: String = s.chars().map(|c| c.to_ascii_lowercase()).collect();
    match lower.as_str() {
        "white" | "w" => Some(Color::White),
        "black" | "b" => Some(Color::Black),
        _ => None,
    }
}
