//! `hugmesh-sudoku-wasm` — a **thin, freestanding C-ABI / JSON shim** around
//! the pure [`hugmesh_sudoku`] single-player Sudoku engine so a host with NO
//! JavaScript glue can drive the authoritative puzzle rules: it loads in a
//! browser via `WebAssembly.instantiateStreaming` (empty imports). Sudoku is a
//! SINGLE-PLAYER puzzle — there is no relay, no host/guest re-validation; the
//! whole game runs as `wasm32` in the browser and the server is never involved.
//!
//! ## What this module is
//!
//! * Plain `std` (NOT `no_std`). The core crate `hugmesh-sudoku` itself uses
//!   `std` (`Vec`/`String`/`format!`), so std is unavoidably in the dependency
//!   graph; declaring `#![no_std]` here only triggers a duplicate `panic_impl`
//!   lang-item clash. Crucially, the `wasm32-unknown-unknown` std is the
//!   *freestanding* std — it is NOT wasi and NOT emscripten, so a pure-compute +
//!   `alloc` module like this one links with **zero host imports** (verified
//!   post-build by scanning the wasm import section). The host need only provide
//!   the module's own linear `memory`.
//! * There is NO `wasm-bindgen`, NO `wasi`, NO emscripten. The only dependency is
//!   the path crate `hugmesh-sudoku`, left completely UNMODIFIED. The engine
//!   ALREADY emits its own JSON (`Game::to_json` / `GameStatus::wire`), so this
//!   shim never invents the snapshot wire shape and never pulls in serde or a
//!   host. The only shape the shim itself synthesises is the rejection envelope
//!   `{"type":"sudoku.error","error":"..."}` (mirroring the chess / connect-four
//!   shims' `<game>.error`).
//!
//! ## Instance model — SINGLE GLOBAL instance
//!
//! This shim keeps ONE global `Game` instance (`static mut GAME`). `sudoku_new`
//! / `sudoku_from_str` (re)create it. **The host instantiates the wasm module
//! once per puzzle**: a host that needs concurrent puzzles instantiates the
//! module multiple times (each instance has its own private linear memory +
//! global), which is the idiomatic browser pattern. The wasm is single-threaded
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
//!  2. host writes its bytes (an 81-cell puzzle string) into `[ptr, ptr+len)`;
//!  3. host calls an op with `(ptr, len)`;
//!  4. after the op returns, the host calls `dealloc(ptr, len)` to release it.
//!
//! NOTE: the cell ops (`sudoku_place` / `sudoku_clear`) take scalar `(row, col,
//! n)` arguments directly — there is no buffer to alloc/dealloc for them, which
//! mirrors the engine's own `Game::place(row, col, n)` / `Game::clear(row, col)`
//! signatures. Only the string-loading op (`sudoku_from_str`) uses the
//! alloc/write/(ptr,len)/dealloc path.
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
//! The pointed-to bytes are a UTF-8 JSON string the host should read
//! immediately. The buffer is a **leaked** `Box<[u8]>` owned by the module; the
//! host MUST hand it back via `dealloc(ptr, len)` once read, or it leaks for the
//! life of the instance.
//!
//! ### The ops (mirror `hugmesh_sudoku::Game`, reusing its OWN JSON)
//!  * `sudoku_new(seed_lo, seed_hi, clues) -> i32`
//!        Build/replace the global game from a deterministic seed (the `u64` seed
//!        is split into two `u32` halves `seed = (seed_hi << 32) | seed_lo`, since
//!        the wasm32 C-ABI passes 64-bit integers as two 32-bit args from JS;
//!        `clues` is the target number of givens). Always returns `1`.
//!  * `sudoku_from_str(ptr, len) -> i32`
//!        Build/replace the global game from an 81-cell puzzle string (digits
//!        `1..=9`, with `0`/`.`/`-`/`_`/whitespace for empties). Returns `1` on
//!        success, `0` on a bad / inconsistent grid (the existing global game, if
//!        any, is left UNCHANGED on failure).
//!  * `sudoku_state() -> u64`  → packed of the engine's OWN `Game::to_json()`
//!        snapshot (`{"type":"sudoku.snapshot","givens","board","givenCount",
//!        "filled","status"}`). Empty `{}` when there is no game.
//!  * `sudoku_place(row, col, n) -> u64`  → packed. On a legal placement, exactly
//!        the engine's `Game::to_json()` snapshot AFTER the placement (so the
//!        caller renders the new board + status in one read). On a rejected
//!        placement (out-of-range / bad-digit / given-cell / conflict, or no
//!        game), the error shape `{"type":"sudoku.error","error":"..."}`.
//!  * `sudoku_clear(row, col) -> u64`  → packed. On success, the post-clear
//!        snapshot; on a rejected clear (out-of-range / given-cell, or no game),
//!        the `sudoku.error` shape.
//!  * `sudoku_status() -> u64`  → packed `{"status":"<wire>"}` where `<wire>` is
//!        the engine's `GameStatus::wire()` (`"playing"` / `"solved"`). Empty
//!        status when there is no game.
//!  * `sudoku_is_given(row, col) -> i32`  → `1` if `(row, col)` is an immutable
//!        clue, else `0` (also `0` for out-of-range or no game). A scalar read so
//!        the renderer can paint givens distinctly without parsing the snapshot.
//!  * `sudoku_hint() -> u64`  → packed `{"type":"sudoku.hint","row","col",
//!        "digit"}` for some empty cell, or `{"type":"sudoku.error","error":
//!        "no-hint"}` when the board is full / unsolvable / there is no game.

#![allow(clippy::missing_safety_doc)]

// std is in the graph via the core crate; we use it directly. On
// wasm32-unknown-unknown this is the freestanding std (no wasi/emscripten host
// imports for pure compute + alloc). panic="abort" (in Cargo.toml) means a panic
// traps the instance — the right failure mode for an authoritative oracle.
use std::boxed::Box;
use std::string::String;
use std::vec::Vec;

use hugmesh_sudoku::{Game, MoveError};

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
/// null/empty buffer or invalid UTF-8 — every consumer treats `""` as "bad
/// input" (which `Game::from_str` rejects), the correct fail-closed behaviour.
unsafe fn str_in<'a>(ptr: u32, len: u32) -> &'a str {
    if ptr == 0 || len == 0 {
        return "";
    }
    let bytes = core::slice::from_raw_parts(ptr as *const u8, len as usize);
    core::str::from_utf8(bytes).unwrap_or("")
}

// ─────────────────────────────────────────────────────────────────────────────
//  Tiny JSON encoders (no serde — keeps the module host-import-free). We only
//  ever EMIT JSON here; the engine already emits the rich `sudoku.snapshot`
//  payload (Game::to_json), so these helpers cover only the simple shapes the
//  shim itself synthesises: the `{"status":...}` object, the `sudoku.error`
//  envelope, and the `sudoku.hint` object.
// ─────────────────────────────────────────────────────────────────────────────

/// Escape a string into a JSON string body (no surrounding quotes). Mirrors the
/// chess / connect-four shims' `push_json_escaped`: backslash + double-quote
/// only (the data we wrap — wire status, error text — contains no control chars).
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

/// `{"type":"sudoku.error","error":"<reason>"}` as bytes. Mirrors the chess /
/// connect-four shims' `<game>.error` shape exactly, swapping the game prefix.
fn error_payload(reason: &str) -> Vec<u8> {
    let mut s = String::with_capacity(reason.len() + 36);
    s.push_str("{\"type\":\"sudoku.error\",\"error\":\"");
    push_json_escaped(&mut s, reason);
    s.push_str("\"}");
    s.into_bytes()
}

/// Append a small unsigned integer's decimal digits to `out` without allocating.
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

/// Map the engine's `MoveError` to a stable, human-readable wire reason that the
/// `sudoku.error` envelope carries (mirrors the chess/c4 `move_error_str`).
fn move_error_str(e: MoveError) -> &'static str {
    match e {
        MoveError::OutOfRange => "out-of-range",
        MoveError::BadDigit => "bad-digit",
        MoveError::GivenCell => "given-cell",
        MoveError::Conflict => "conflict",
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  The exported ops.
// ─────────────────────────────────────────────────────────────────────────────

/// Build / replace the global game from a deterministic seed. The `u64` seed is
/// split into two `u32` halves (`seed = (seed_hi << 32) | seed_lo`) because the
/// wasm32 C-ABI / JS boundary passes 64-bit integers as two 32-bit args; `clues`
/// is the target number of givens (the engine clamps to `17..=81`). Returns 1
/// (there is no fallible seeded start position).
#[no_mangle]
pub unsafe extern "C" fn sudoku_new(seed_lo: u32, seed_hi: u32, clues: u32) -> i32 {
    let seed = ((seed_hi as u64) << 32) | seed_lo as u64;
    set_game(Game::new(seed, clues as usize));
    1
}

/// Build / replace the global game from an 81-cell puzzle string. Returns 1 on
/// success, 0 on a bad / inconsistent grid (the existing global game, if any, is
/// left UNCHANGED on failure).
#[no_mangle]
pub unsafe extern "C" fn sudoku_from_str(ptr: u32, len: u32) -> i32 {
    let s = str_in(ptr, len);
    match Game::from_str(s) {
        Ok(g) => {
            set_game(g);
            1
        }
        Err(_) => 0,
    }
}

/// Packed (ptr<<32|len) of the engine's OWN `Game::to_json()` snapshot for the
/// current position. Empty `{}` object when there is no game.
#[no_mangle]
pub unsafe extern "C" fn sudoku_state() -> u64 {
    let json = match game_ref() {
        Some(g) => g.to_json(),
        None => String::from("{}"),
    };
    leak_packed(json.into_bytes())
}

/// Place digit `n` (`1..=9`) at `(row, col)`. On success, packed (ptr<<32|len) of
/// the engine's `Game::to_json()` snapshot AFTER the placement. On a rejected
/// placement (out-of-range / bad-digit / given-cell / conflict, or no game),
/// packed of `{"type":"sudoku.error","error":"..."}`.
#[no_mangle]
pub unsafe extern "C" fn sudoku_place(row: u32, col: u32, n: u32) -> u64 {
    match game_mut() {
        None => leak_packed(error_payload("no-game")),
        Some(g) => match g.place(row as usize, col as usize, n as u8) {
            Ok(_status) => leak_packed(g.to_json().into_bytes()),
            Err(e) => leak_packed(error_payload(move_error_str(e))),
        },
    }
}

/// Clear the non-given cell at `(row, col)` back to empty. On success, packed of
/// the post-clear `Game::to_json()` snapshot. On a rejected clear (out-of-range
/// / given-cell, or no game), packed of `{"type":"sudoku.error","error":"..."}`.
#[no_mangle]
pub unsafe extern "C" fn sudoku_clear(row: u32, col: u32) -> u64 {
    match game_mut() {
        None => leak_packed(error_payload("no-game")),
        Some(g) => match g.clear(row as usize, col as usize) {
            Ok(()) => leak_packed(g.to_json().into_bytes()),
            Err(e) => leak_packed(error_payload(move_error_str(e))),
        },
    }
}

/// Packed (ptr<<32|len) of `{"status":"<wire>"}` where `<wire>` is the engine's
/// `GameStatus::wire()` (`"playing"` / `"solved"`). Empty status when no game.
#[no_mangle]
pub unsafe extern "C" fn sudoku_status() -> u64 {
    let wire = match game_ref() {
        Some(g) => g.status().wire(),
        None => "",
    };
    leak_packed(single_field_object("status", wire))
}

/// `1` if `(row, col)` is an immutable given (original clue), else `0` (also `0`
/// for out-of-range or no game). A scalar read so the renderer can paint givens
/// distinctly without parsing the snapshot's `givens` string.
#[no_mangle]
pub unsafe extern "C" fn sudoku_is_given(row: u32, col: u32) -> i32 {
    match game_ref() {
        Some(g) if g.is_given(row as usize, col as usize) => 1,
        _ => 0,
    }
}

/// Packed (ptr<<32|len) of `{"type":"sudoku.hint","row":r,"col":c,"digit":d}` for
/// some currently-empty cell, or `{"type":"sudoku.error","error":"no-hint"}` when
/// the board is full / unsolvable from here / there is no game.
#[no_mangle]
pub unsafe extern "C" fn sudoku_hint() -> u64 {
    let hint = match game_ref() {
        Some(g) => g.hint(),
        None => None,
    };
    match hint {
        Some((r, c, d)) => {
            let mut s = String::with_capacity(56);
            s.push_str("{\"type\":\"sudoku.hint\",\"row\":");
            push_usize(&mut s, r);
            s.push_str(",\"col\":");
            push_usize(&mut s, c);
            s.push_str(",\"digit\":");
            push_usize(&mut s, d as usize);
            s.push('}');
            leak_packed(s.into_bytes())
        }
        None => leak_packed(error_payload("no-hint")),
    }
}
