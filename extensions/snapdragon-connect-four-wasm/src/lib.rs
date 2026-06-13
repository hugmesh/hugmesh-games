//! `snapdragon-connect-four-wasm` — a **thin, freestanding C-ABI / JSON shim**
//! around the pure [`snapdragon_connect_four`] rules engine so a host with NO
//! JavaScript glue can drive authoritative Connect Four: it loads in a browser
//! via `WebAssembly.instantiateStreaming` (empty imports) AND in a JVM WASM
//! runtime such as Chicory.
//!
//! ## What this module is
//!
//! * Plain `std` (NOT `no_std`). The core crate `snapdragon-connect-four` itself
//!   uses `std` (`Vec`/`String`/`format!`), so std is unavoidably in the
//!   dependency graph; declaring `#![no_std]` here only triggers a duplicate
//!   `panic_impl` lang-item clash. Crucially, the `wasm32-unknown-unknown` std is
//!   the *freestanding* std — it is NOT wasi and NOT emscripten, so a pure-compute
//!   + `alloc` module like this one links with **zero host imports** (verified
//!   post-build by scanning the wasm import section). The host need only provide
//!   the module's own linear `memory`.
//! * There is NO `wasm-bindgen`, NO `wasi`, NO emscripten. The only dependency is
//!   the path crate `snapdragon-connect-four`, left completely UNMODIFIED. The
//!   engine ALREADY emits its own JSON (`DropOutcome::to_json`,
//!   `Game::to_json`, `GameStatus::wire`), so this shim never invents a wire
//!   shape and never pulls in serde or a host. The exact shapes mirror the chess
//!   shim's contract one-for-one (`<game>.state` world payload / `<game>.error`).
//!
//! ## Instance model — SINGLE GLOBAL instance
//!
//! This shim keeps ONE global `Game` instance (`static mut GAME`). `c4_new`
//! (re)creates it. **The host instantiates the wasm module once per board**: a
//! host that needs concurrent games instantiates the module multiple times (each
//! instance has its own private linear memory + global), which is the idiomatic
//! browser / Chicory pattern. The wasm is single-threaded so the `static mut`
//! access is sound (no concurrent callers within one instance).
//!
//! ## Memory contract (READ THIS before calling)
//!
//! All strings crossing the boundary are UTF-8 bytes in the module's linear
//! memory. The host owns input buffers; the module owns output buffers.
//!
//! ### Passing bytes IN (host → module)
//!  1. host calls `alloc(len) -> ptr` to get a `len`-byte region it may write to;
//!  2. host writes its bytes (a decimal column index like `"3"`) into
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
//! ### The ops (mirror `snapdragon_connect_four::Game`, reusing its OWN JSON)
//!  * `c4_new() -> i32`
//!        Build/replace the global game at the empty start position (Red to move).
//!        Always returns `1` (success — there is no fallible start position).
//!  * `c4_legal() -> u64`  → packed JSON **array of column indices** that can be
//!        dropped into right now, e.g. `[0,1,2,3,4,5,6]`. `[]` when there is no
//!        game OR the game is over.
//!  * `c4_apply_drop(ptr, len) -> u64`  → packed. The buffer is the decimal
//!        column index (`"0"`..`"6"`). On a legal drop, exactly the engine's OWN
//!        `Game::to_json(Some(&outcome))` full-board **world** payload
//!        (`{"type":"connect_four.state","lastPlayer",...,"board"}`) — the very
//!        snapshot the host relays on the `world` channel. On an illegal /
//!        unparseable column (or no game), the error shape
//!        `{"type":"connect_four.error","error":"..."}`.
//!  * `c4_state() -> u64`  → packed `Game::to_json(None)` for the current
//!        position (full board + side to move + status; no last move).
//!  * `c4_status() -> u64`  → packed `{"status":"<wire>"}` where `<wire>` is the
//!        engine's `GameStatus::wire()` string (e.g. `"win:red"`, `"ongoing"`,
//!        `"draw"`).
//!
//! There is NO `c4_from_state` / FEN-equivalent and NO `c4_resign` / `c4_pass`:
//! the connect-four engine exposes no `from_state`/`resign`/`pass` API, and the
//! task is explicit that the shim must REUSE the existing public API — it does
//! not invent ops the engine can't back.

#![allow(clippy::missing_safety_doc)]

// std is in the graph via the core crate; we use it directly. On
// wasm32-unknown-unknown this is the freestanding std (no wasi/emscripten host
// imports for pure compute + alloc). panic="abort" (in Cargo.toml) means a panic
// traps the instance — the right failure mode for an authoritative oracle.
use std::boxed::Box;
use std::string::String;
use std::vec::Vec;

use snapdragon_connect_four::Game;

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
/// (no game, unparseable column) which is the correct fail-closed behaviour.
unsafe fn str_in<'a>(ptr: u32, len: u32) -> &'a str {
    if ptr == 0 || len == 0 {
        return "";
    }
    let bytes = core::slice::from_raw_parts(ptr as *const u8, len as usize);
    core::str::from_utf8(bytes).unwrap_or("")
}

// ─────────────────────────────────────────────────────────────────────────────
//  Tiny JSON encoders (no serde — keeps the module host-import-free). We only
//  ever EMIT JSON here; the engine already emits the rich DropOutcome /
//  full-board world payloads, so these helpers cover the two simple shapes the
//  shim itself synthesises plus the legal-column-array.
// ─────────────────────────────────────────────────────────────────────────────

/// Escape a string into a JSON string body (no surrounding quotes). Mirrors the
/// chess shim's `push_json_escaped`: backslash + double-quote only (the data we
/// wrap — wire status, error text — contains no control characters).
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

/// `{"type":"connect_four.error","error":"<reason>"}` as bytes. Mirrors the chess
/// shim's `chess.error` shape exactly, swapping the game prefix.
fn error_payload(reason: &str) -> Vec<u8> {
    let mut s = String::with_capacity(reason.len() + 40);
    s.push_str("{\"type\":\"connect_four.error\",\"error\":\"");
    push_json_escaped(&mut s, reason);
    s.push_str("\"}");
    s.into_bytes()
}

/// Parse a decimal column index out of a host buffer. Returns `None` for any
/// non-numeric / empty input so the apply op can return the `unparseable` error
/// shape rather than guessing a column.
fn parse_col(s: &str) -> Option<usize> {
    let t = s.trim();
    if t.is_empty() {
        return None;
    }
    t.parse::<usize>().ok()
}

// ─────────────────────────────────────────────────────────────────────────────
//  The exported ops.
// ─────────────────────────────────────────────────────────────────────────────

/// Build / replace the global game at the empty start position (Red to move).
/// Returns 1 (there is no fallible start position for Connect Four).
#[no_mangle]
pub unsafe extern "C" fn c4_new() -> i32 {
    set_game(Game::new());
    1
}

/// Packed (ptr<<32|len) of a JSON array of the column indices that can currently
/// be dropped into, e.g. `[0,1,2,3,4,5,6]`. `[]` when there is no game or the game
/// is over.
#[no_mangle]
pub unsafe extern "C" fn c4_legal() -> u64 {
    let mut s = String::new();
    s.push('[');
    if let Some(g) = game_ref() {
        let mut first = true;
        for c in g.legal_moves() {
            if !first {
                s.push(',');
            }
            first = false;
            // column index 0..=6 — push the decimal digits directly.
            push_usize(&mut s, c);
        }
    }
    s.push(']');
    leak_packed(s.into_bytes())
}

/// Append a small unsigned integer's decimal digits to `out` without allocating
/// (columns are 0..=6, so a single digit, but this is general).
fn push_usize(out: &mut String, mut n: usize) {
    if n == 0 {
        out.push('0');
        return;
    }
    let mut buf = [0u8; 20];
    let mut i = buf.len();
    while n > 0 {
        i -= 1;
        buf[i] = b'0' + (n % 10) as u8;
        n /= 10;
    }
    // SAFETY: every byte written is an ASCII digit.
    out.push_str(core::str::from_utf8(&buf[i..]).unwrap_or("0"));
}

/// Drop a disc for the side to move into the column named (decimal) in the
/// buffer. On success, packed (ptr<<32|len) of the engine's OWN
/// `Game::to_json(Some(&outcome))` full-board **world** payload — the exact
/// snapshot the host relays on the `world` channel after a move. On an
/// illegal / unparseable column (or no game), packed of
/// `{"type":"connect_four.error","error":"..."}`.
#[no_mangle]
pub unsafe extern "C" fn c4_apply_drop(ptr: u32, len: u32) -> u64 {
    let raw = str_in(ptr, len);
    let col = match parse_col(raw) {
        Some(c) => c,
        None => return leak_packed(error_payload("bad-column")),
    };
    match game_mut() {
        None => leak_packed(error_payload("no-game")),
        Some(g) => match g.drop(col) {
            // Reuse the engine's full-board world JSON, mirroring how chess
            // relays MoveOutcome::to_json — Game::to_json carries the same
            // `{ "type":"connect_four.state", ... }` shape PLUS the whole board.
            Ok(outcome) => leak_packed(g.to_json(Some(&outcome)).into_bytes()),
            Err(e) => leak_packed(error_payload(move_error_str(e))),
        },
    }
}

/// Map the engine's `MoveError` to a stable, human-readable wire reason.
fn move_error_str(e: snapdragon_connect_four::MoveError) -> &'static str {
    use snapdragon_connect_four::MoveError::*;
    match e {
        OutOfRange => "out-of-range",
        ColumnFull => "column-full",
        GameOver => "game-over",
    }
}

/// Packed (ptr<<32|len) of the engine's `Game::to_json(None)` for the current
/// position (full board + side to move + status, no last move). An empty `{}`
/// object when there is no game.
#[no_mangle]
pub unsafe extern "C" fn c4_state() -> u64 {
    let json = match game_ref() {
        Some(g) => g.to_json(None),
        None => String::from("{}"),
    };
    leak_packed(json.into_bytes())
}

/// Packed (ptr<<32|len) of `{"status":"<wire>"}` where `<wire>` is the engine's
/// `GameStatus::wire()` (e.g. `"win:red"`, `"ongoing"`, `"draw"`). Empty status
/// when there is no game.
#[no_mangle]
pub unsafe extern "C" fn c4_status() -> u64 {
    let wire = match game_ref() {
        Some(g) => g.status().wire(),
        None => String::new(),
    };
    leak_packed(single_field_object("status", &wire))
}
