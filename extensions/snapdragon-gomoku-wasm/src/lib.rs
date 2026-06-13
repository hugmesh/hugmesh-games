//! `snapdragon-gomoku-wasm` — a **thin, freestanding C-ABI / JSON shim** around the
//! pure [`snapdragon_gomoku`] rules engine so a host with NO JavaScript glue can
//! drive authoritative gomoku (five-in-a-row): it loads in a browser via
//! `WebAssembly.instantiateStreaming` (empty imports) AND in a JVM WASM runtime
//! such as Chicory.
//!
//! It is the EXACT structural twin of the proven `snapdragon-chess-wasm` shim.
//!
//! ## What this module is
//!
//! * Plain `std` (NOT `no_std`). The core crate `snapdragon-gomoku` itself uses
//!   `std` (`Vec`/`String`/`format!`), so std is unavoidably in the dependency
//!   graph; declaring `#![no_std]` here only triggers a duplicate `panic_impl`
//!   lang-item clash. Crucially, the `wasm32-unknown-unknown` std is the
//!   *freestanding* std — it is NOT wasi and NOT emscripten, so a pure-compute +
//!   `alloc` module like this one links with **zero host imports** (verified
//!   post-build by scanning the wasm import section). The host need only provide
//!   the module's own linear `memory`.
//! * There is NO `wasm-bindgen`, NO `wasi`, NO emscripten. The only dependency is
//!   the path crate `snapdragon-gomoku`, left completely UNMODIFIED. The engine
//!   ALREADY emits its own JSON (`MoveOutcome::to_json` / `GameStatus::wire`), so
//!   this shim never invents a wire shape and never pulls in serde or a host.
//!
//! ## Instance model — SINGLE GLOBAL instance
//!
//! This shim keeps ONE global `Game` instance (`static mut GAME`). `gomoku_new` /
//! `gomoku_from_moves` (re)create it. **The host instantiates the wasm module once
//! per board**: a host that needs concurrent games instantiates the module
//! multiple times (each instance has its own private linear memory + global),
//! which is the idiomatic browser / Chicory pattern. The wasm is single-threaded
//! so the `static mut` access is sound (no concurrent callers within one
//! instance).
//!
//! ## Memory contract (READ THIS before calling)
//!
//! All strings crossing the boundary are UTF-8 bytes in the module's linear
//! memory. The host owns input buffers; the module owns output buffers. Identical
//! to the chess shim:
//!
//! ### Passing bytes IN (host → module)
//!  1. host calls `alloc(len) -> ptr` to get a `len`-byte region it may write to;
//!  2. host writes its bytes (a coordinate string, a space-separated move list)
//!     into `[ptr, ptr+len)`;
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
//! ### The ops (mirror `snapdragon_gomoku::Game`, reusing its OWN JSON)
//!  * `gomoku_new() -> i32`
//!        Build/replace the global game at the empty board (Black to move).
//!        Always returns `1`.
//!  * `gomoku_from_moves(ptr, len) -> i32`
//!        Build/replace the global game by replaying a whitespace-separated list
//!        of algebraic coordinates (e.g. `"h8 h9 i8"`), alternating Black/White
//!        as the engine dictates. Returns `1` if the WHOLE list replayed legally,
//!        `0` otherwise (the global game is left UNCHANGED on `0`). This is the
//!        gomoku equivalent of chess's `from_fen` — gomoku has no FEN, the move
//!        list is its restorable canonical state.
//!  * `gomoku_legal() -> u64`  → packed JSON **array of coordinate strings** for
//!        the side to move, e.g. `["a1","b1",...]`. `[]` when there is no game or
//!        the game is over.
//!  * `gomoku_apply(ptr, len) -> u64`  → packed. On a legal placement, exactly the
//!        engine's `MoveOutcome::to_json` wire payload
//!        (`{"type":"gomoku.state","player","lastMove","status","win","winLine"}`).
//!        On an illegal / unparseable placement (or no game), the error shape
//!        `{"type":"gomoku.error","error":"..."}`.
//!  * `gomoku_state() -> u64`  → packed
//!        `{"type":"gomoku.board","board":"<225-char .XO>","turn":"<black|white>","status":"<wire>","moves":<n>}`
//!        — the current full position (the FEN-equivalent state query). The
//!        `board` string is row-major (`a1` first), `.`=empty `X`=black `O`=white,
//!        exactly the engine's `ascii()` ordering minus the row newlines.
//!  * `gomoku_status() -> u64`  → packed `{"status":"<wire>"}` where `<wire>` is the
//!        engine's `GameStatus::wire()` string (e.g. `"win:black"`, `"ongoing"`,
//!        `"draw"`).

#![allow(clippy::missing_safety_doc)]

// std is in the graph via the core crate; we use it directly. On
// wasm32-unknown-unknown this is the freestanding std (no wasi/emscripten host
// imports for pure compute + alloc). panic="abort" (in Cargo.toml) means a panic
// traps the instance — the right failure mode for an authoritative oracle.
use std::boxed::Box;
use std::string::String;
use std::vec::Vec;

use snapdragon_gomoku::{coord_str, Cell, Game, MoveError, Player};

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
//  ever EMIT JSON here; the engine already emits the rich MoveOutcome payload, so
//  these helpers cover the simple shapes the shim itself synthesises plus the
//  coordinate-array for legal moves and the board-state query.
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

/// `{"type":"gomoku.error","error":"<reason>"}` as bytes.
fn error_payload(reason: &str) -> Vec<u8> {
    let mut s = String::with_capacity(reason.len() + 36);
    s.push_str("{\"type\":\"gomoku.error\",\"error\":\"");
    push_json_escaped(&mut s, reason);
    s.push_str("\"}");
    s.into_bytes()
}

/// Map the engine's `MoveError` to a stable, human-readable wire reason.
fn move_error_str(e: MoveError) -> &'static str {
    match e {
        MoveError::OffBoard => "off-board",
        MoveError::Occupied => "occupied",
        MoveError::GameOver => "game-over",
        MoveError::BadCoord => "bad-coord",
    }
}

fn player_str(p: Player) -> &'static str {
    match p {
        Player::Black => "black",
        Player::White => "white",
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  The exported ops.
// ─────────────────────────────────────────────────────────────────────────────

/// Build / replace the global game at the empty board (Black to move). Returns 1.
#[no_mangle]
pub unsafe extern "C" fn gomoku_new() -> i32 {
    set_game(Game::new());
    1
}

/// Build / replace the global game by replaying a whitespace-separated list of
/// algebraic coordinates (e.g. `"h8 h9 i8"`). Returns 1 if the WHOLE list replays
/// legally, 0 otherwise (the existing global game, if any, is left UNCHANGED on
/// failure). This is the gomoku equivalent of chess's `from_fen` — gomoku has no
/// FEN; its move list is the restorable canonical state.
#[no_mangle]
pub unsafe extern "C" fn gomoku_from_moves(ptr: u32, len: u32) -> i32 {
    let list = str_in(ptr, len);
    let mut g = Game::new();
    for tok in list.split_whitespace() {
        if g.place_coord(tok).is_err() {
            return 0; // any rejected move ⇒ whole restore fails, leave GAME as-is
        }
    }
    set_game(g);
    1
}

/// Packed (ptr<<32|len) of a JSON array of coordinate strings for the side to
/// move (all empty points). `[]` when there is no game or the game is over.
#[no_mangle]
pub unsafe extern "C" fn gomoku_legal() -> u64 {
    let mut s = String::new();
    s.push('[');
    if let Some(g) = game_ref() {
        let mut first = true;
        for idx in g.legal_moves() {
            if !first {
                s.push(',');
            }
            first = false;
            s.push('"');
            s.push_str(&coord_str(idx));
            s.push('"');
        }
    }
    s.push(']');
    leak_packed(s.into_bytes())
}

/// Apply a placement at the algebraic coordinate in the buffer (e.g. `"h8"`). On
/// success, packed (ptr<<32|len) of the engine's OWN `MoveOutcome::to_json` wire
/// payload. On an illegal / unparseable placement (or no game), packed of
/// `{"type":"gomoku.error","error":"..."}`.
#[no_mangle]
pub unsafe extern "C" fn gomoku_apply(ptr: u32, len: u32) -> u64 {
    let coord = str_in(ptr, len);
    match game_mut() {
        None => leak_packed(error_payload("no game")),
        Some(g) => match g.place_coord(coord) {
            Ok(outcome) => leak_packed(outcome.to_json().into_bytes()),
            Err(e) => leak_packed(error_payload(move_error_str(e))),
        },
    }
}

/// Packed (ptr<<32|len) of the current full position — the FEN-equivalent state
/// query:
/// `{"type":"gomoku.board","board":"<225-char>","turn":"<color>","status":"<wire>","moves":<n>}`.
/// The board string is row-major from `a1`, `.`=empty `X`=black `O`=white. Empty
/// board / `"ongoing"` / `0` moves when there is no game.
#[no_mangle]
pub unsafe extern "C" fn gomoku_state() -> u64 {
    let mut s = String::with_capacity(280);
    s.push_str("{\"type\":\"gomoku.board\",\"board\":\"");
    let (turn, wire, moves) = match game_ref() {
        Some(g) => {
            for cell in g.board() {
                s.push(match cell {
                    Cell::Empty => '.',
                    Cell::Stone(Player::Black) => 'X',
                    Cell::Stone(Player::White) => 'O',
                });
            }
            (player_str(g.side_to_move()), g.status().wire(), g.legal_moves_count())
        }
        None => {
            for _ in 0..snapdragon_gomoku::POINTS {
                s.push('.');
            }
            ("black", String::from("ongoing"), 0u16)
        }
    };
    s.push_str("\",\"turn\":\"");
    s.push_str(turn);
    s.push_str("\",\"status\":\"");
    push_json_escaped(&mut s, &wire);
    s.push_str("\",\"moves\":");
    s.push_str(&moves.to_string());
    s.push('}');
    leak_packed(s.into_bytes())
}

/// Packed (ptr<<32|len) of `{"status":"<wire>"}` where `<wire>` is the engine's
/// `GameStatus::wire()` (e.g. `"win:black"`). Empty status when no game.
#[no_mangle]
pub unsafe extern "C" fn gomoku_status() -> u64 {
    let wire = match game_ref() {
        Some(g) => g.status().wire(),
        None => String::new(),
    };
    leak_packed(single_field_object("status", &wire))
}

// ─────────────────────────────────────────────────────────────────────────────
//  A small extension trait so `gomoku_state` can report the stone count without
//  modifying the engine crate. We DON'T touch snapdragon-gomoku; we just derive
//  the count from its public `board()` accessor.
// ─────────────────────────────────────────────────────────────────────────────
trait StoneCount {
    fn legal_moves_count(&self) -> u16;
}
impl StoneCount for Game {
    fn legal_moves_count(&self) -> u16 {
        // Stones placed = occupied points (board() is public on the engine).
        self.board()
            .iter()
            .filter(|&&c| c != Cell::Empty)
            .count() as u16
    }
}
