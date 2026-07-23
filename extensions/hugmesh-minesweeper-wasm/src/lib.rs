//! `hugmesh-minesweeper-wasm` — a **thin, freestanding C-ABI / JSON shim**
//! around the pure [`hugmesh_minesweeper`] rules engine so a host with NO
//! JavaScript glue can drive authoritative Minesweeper: it loads in a browser
//! via `WebAssembly.instantiateStreaming` (empty imports) AND in a JVM WASM
//! runtime such as Chicory.
//!
//! ## What this module is
//!
//! * Plain `std` (NOT `no_std`). The core crate `hugmesh-minesweeper` itself
//!   uses `std` (`Vec`/`String`/`format!`), so std is unavoidably in the
//!   dependency graph; declaring `#![no_std]` here only triggers a duplicate
//!   `panic_impl` lang-item clash. Crucially, the `wasm32-unknown-unknown` std is
//!   the *freestanding* std — it is NOT wasi and NOT emscripten, so a pure-compute
//!   + `alloc` module like this one links with **zero host imports** (verified
//!   post-build by scanning the wasm import section). The host need only provide
//!   the module's own linear `memory`.
//! * There is NO `wasm-bindgen`, NO `wasi`, NO emscripten. The only dependency is
//!   the path crate `hugmesh-minesweeper`, left completely UNMODIFIED. The
//!   engine ALREADY emits its own JSON (`Game::to_json` → the
//!   `minesweeper.snapshot` shape), so this shim never invents a wire shape for
//!   state and never pulls in serde or a host. The error shape mirrors the chess /
//!   connect-four shims one-for-one (`<game>.error`).
//!
//! ## SINGLE-PLAYER, host-local
//!
//! Minesweeper is a SINGLE-PLAYER puzzle: the host browser runs the wasm engine
//! and there is no relay (the game-frame's `session_id`-absent local path drives
//! it). The server runs none of this logic.
//!
//! ## Instance model — SINGLE GLOBAL instance
//!
//! This shim keeps ONE global `Game` instance (`static mut GAME`). `mine_new`
//! (re)creates it. **The host instantiates the wasm module once per board**: a
//! host that needs concurrent puzzles instantiates the module multiple times
//! (each instance has its own private linear memory + global), which is the
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
//!  2. host writes its bytes (a config JSON, or a `"x,y"` coordinate) into
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
//! ### The ops (mirror `hugmesh_minesweeper::Game`, reusing its OWN JSON)
//!  * `mine_new(ptr, len) -> i32`
//!        Build/replace the global game from a tiny config JSON
//!        `{"width":W,"height":H,"mines":M,"seed":S}` (any missing field
//!        defaults: 9×9, 10 mines, seed 0 — the classic beginner board). The
//!        SEED makes mine placement fully deterministic (a SEEDED board for the
//!        tests). Always returns `1` (the config is clamped to a legal board).
//!  * `mine_reveal(ptr, len) -> u64`  → packed. The buffer is the decimal
//!        coordinate `"x,y"`. On success, exactly the engine's OWN
//!        `Game::to_json()` `minesweeper.snapshot` full-board payload (post-move).
//!        Out-of-bounds / game-over reveals return the error shape
//!        `{"type":"minesweeper.error","error":"..."}`. A reveal of a flagged or
//!        already-open cell is a benign no-op that returns the unchanged snapshot.
//!  * `mine_flag(ptr, len) -> u64`  → packed. The buffer is `"x,y"`. Toggles the
//!        flag on the cell and returns the post-move `minesweeper.snapshot`.
//!        Out-of-bounds / game-over returns the error shape.
//!  * `mine_state() -> u64`  → packed `Game::to_json()` for the current position
//!        (full `minesweeper.snapshot`). Empty `{}` when there is no game.
//!  * `mine_status() -> u64`  → packed `{"status":"<wire>"}` where `<wire>` is the
//!        engine's `GameStatus::wire()` string (`"playing"`, `"won"`, `"lost"`).
//!
//! There is no FEN-equivalent / from-snapshot op: the engine's snapshot resume is
//! out of scope for the in-browser game (a fresh seeded board per puzzle), and
//! the task is explicit that the shim must REUSE the existing public API.

#![allow(clippy::missing_safety_doc)]

// std is in the graph via the core crate; we use it directly. On
// wasm32-unknown-unknown this is the freestanding std (no wasi/emscripten host
// imports for pure compute + alloc). panic="abort" (in Cargo.toml) means a panic
// traps the instance — the right failure mode for an authoritative oracle.
use std::boxed::Box;
use std::string::String;
use std::vec::Vec;

use hugmesh_minesweeper::{ActionError, Game};

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
/// (no game, unparseable coordinate, empty config) which is the correct
/// fail-closed behaviour (empty config → the classic 9×9 default board).
unsafe fn str_in<'a>(ptr: u32, len: u32) -> &'a str {
    if ptr == 0 || len == 0 {
        return "";
    }
    let bytes = core::slice::from_raw_parts(ptr as *const u8, len as usize);
    core::str::from_utf8(bytes).unwrap_or("")
}

// ─────────────────────────────────────────────────────────────────────────────
//  Tiny JSON encoders (no serde — keeps the module host-import-free). We only
//  ever EMIT JSON here; the engine already emits the rich minesweeper.snapshot
//  payload via Game::to_json, so these helpers cover the two simple shapes the
//  shim itself synthesises: the single-field status object and the error.
// ─────────────────────────────────────────────────────────────────────────────

/// Escape a string into a JSON string body (no surrounding quotes). Mirrors the
/// chess / connect-four shims' `push_json_escaped`: backslash + double-quote only
/// (the data we wrap — wire status, error text — contains no control characters).
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

/// `{"type":"minesweeper.error","error":"<reason>"}` as bytes. Mirrors the chess /
/// connect-four shims' `<game>.error` shape exactly, swapping the game prefix.
fn error_payload(reason: &str) -> Vec<u8> {
    let mut s = String::with_capacity(reason.len() + 40);
    s.push_str("{\"type\":\"minesweeper.error\",\"error\":\"");
    push_json_escaped(&mut s, reason);
    s.push_str("\"}");
    s.into_bytes()
}

/// Map the engine's `ActionError` to a stable, human-readable wire reason.
fn action_error_str(e: ActionError) -> &'static str {
    match e {
        ActionError::OutOfBounds => "out-of-bounds",
        ActionError::GameOver => "game-over",
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Tiny field parsers (no serde). The config is a flat JSON of small unsigned
//  numbers; the coordinate is a `"x,y"` decimal pair. We hand-parse both to keep
//  the module dependency-free.
// ─────────────────────────────────────────────────────────────────────────────

/// Pull the first run of decimal digits that follows the literal `"<key>"` in
/// `src` and parse it as a `u64`. Returns `None` when the key is absent or the
/// value isn't a non-negative integer. This is a deliberately tiny extractor for
/// the flat `{"width":W,"height":H,"mines":M,"seed":S}` config — it does not aim
/// to be a general JSON parser, only to read the four numeric fields the shim
/// needs (values are plain integers, never quoted/nested).
fn json_u64_field(src: &str, key: &str) -> Option<u64> {
    // Find `"key"`.
    let needle = {
        let mut k = String::with_capacity(key.len() + 2);
        k.push('"');
        k.push_str(key);
        k.push('"');
        k
    };
    let start = src.find(&needle)? + needle.len();
    let rest = &src[start..];
    // Skip the colon + any whitespace, then read the digit run.
    let bytes = rest.as_bytes();
    let mut i = 0;
    // skip up to and including a ':' then whitespace
    while i < bytes.len() && bytes[i] != b':' {
        // bail if we hit a non-space/non-colon before the colon (malformed)
        if !bytes[i].is_ascii_whitespace() {
            return None;
        }
        i += 1;
    }
    if i >= bytes.len() {
        return None; // no colon
    }
    i += 1; // skip ':'
    while i < bytes.len() && bytes[i].is_ascii_whitespace() {
        i += 1;
    }
    let digit_start = i;
    while i < bytes.len() && bytes[i].is_ascii_digit() {
        i += 1;
    }
    if i == digit_start {
        return None; // no digits
    }
    core::str::from_utf8(&bytes[digit_start..i])
        .ok()?
        .parse::<u64>()
        .ok()
}

/// Parse a `"x,y"` decimal coordinate pair. Returns `None` for any malformed
/// input so the apply ops can return the `bad-coord` error shape rather than
/// guessing a cell.
fn parse_coord(s: &str) -> Option<(usize, usize)> {
    let t = s.trim();
    let mut parts = t.split(',');
    let x = parts.next()?.trim().parse::<usize>().ok()?;
    let y = parts.next()?.trim().parse::<usize>().ok()?;
    if parts.next().is_some() {
        return None; // more than two components → malformed
    }
    Some((x, y))
}

// ─────────────────────────────────────────────────────────────────────────────
//  The exported ops.
// ─────────────────────────────────────────────────────────────────────────────

/// Build / replace the global game from a flat config JSON
/// `{"width":W,"height":H,"mines":M,"seed":S}`. Missing fields default to the
/// classic beginner board: 9×9, 10 mines, seed 0. The seed makes mine placement
/// fully deterministic (a SEEDED board, reproducible for tests). The engine
/// clamps an over-budget mine count, so there is no fallible start position;
/// returns 1.
#[no_mangle]
pub unsafe extern "C" fn mine_new(ptr: u32, len: u32) -> i32 {
    let cfg = str_in(ptr, len);
    let width = json_u64_field(cfg, "width").unwrap_or(9) as usize;
    let height = json_u64_field(cfg, "height").unwrap_or(9) as usize;
    let mines = json_u64_field(cfg, "mines").unwrap_or(10) as usize;
    let seed = json_u64_field(cfg, "seed").unwrap_or(0);
    set_game(Game::new(width, height, mines, seed));
    1
}

/// Reveal the cell named (decimal `"x,y"`) in the buffer. On success, packed
/// (ptr<<32|len) of the engine's OWN `Game::to_json()` `minesweeper.snapshot`
/// full-board payload AFTER the reveal — the exact post-move world snapshot the
/// host renders. A reveal of a flagged or already-open cell is a benign no-op
/// that still returns the (unchanged) snapshot. Out-of-bounds / acting on a
/// finished game / no game / an unparseable coordinate return the error shape
/// `{"type":"minesweeper.error","error":"..."}`.
#[no_mangle]
pub unsafe extern "C" fn mine_reveal(ptr: u32, len: u32) -> u64 {
    let raw = str_in(ptr, len);
    let (x, y) = match parse_coord(raw) {
        Some(c) => c,
        None => return leak_packed(error_payload("bad-coord")),
    };
    match game_mut() {
        None => leak_packed(error_payload("no-game")),
        Some(g) => match g.reveal(x, y) {
            // Reuse the engine's full-board snapshot JSON: it carries the whole
            // player-visible board plus status, the same shape `mine_state`
            // returns, so the host renders the reveal/flood result directly.
            Ok(_outcome) => leak_packed(g.to_json().into_bytes()),
            Err(e) => leak_packed(error_payload(action_error_str(e))),
        },
    }
}

/// Toggle the flag on the cell named (decimal `"x,y"`) in the buffer. On success,
/// packed (ptr<<32|len) of the engine's OWN `Game::to_json()`
/// `minesweeper.snapshot` post-move payload. Flagging an already-revealed cell is
/// a benign no-op that still returns the (unchanged) snapshot. Out-of-bounds /
/// game-over / no game / an unparseable coordinate return the error shape.
#[no_mangle]
pub unsafe extern "C" fn mine_flag(ptr: u32, len: u32) -> u64 {
    let raw = str_in(ptr, len);
    let (x, y) = match parse_coord(raw) {
        Some(c) => c,
        None => return leak_packed(error_payload("bad-coord")),
    };
    match game_mut() {
        None => leak_packed(error_payload("no-game")),
        Some(g) => match g.flag(x, y) {
            Ok(_now_flagged) => leak_packed(g.to_json().into_bytes()),
            Err(e) => leak_packed(error_payload(action_error_str(e))),
        },
    }
}

/// Packed (ptr<<32|len) of the engine's `Game::to_json()` for the current
/// position (full `minesweeper.snapshot` — board + status + masks). An empty `{}`
/// object when there is no game.
#[no_mangle]
pub unsafe extern "C" fn mine_state() -> u64 {
    let json = match game_ref() {
        Some(g) => g.to_json(),
        None => String::from("{}"),
    };
    leak_packed(json.into_bytes())
}

/// Packed (ptr<<32|len) of `{"status":"<wire>"}` where `<wire>` is the engine's
/// `GameStatus::wire()` (`"playing"`, `"won"`, `"lost"`). Empty status when there
/// is no game.
#[no_mangle]
pub unsafe extern "C" fn mine_status() -> u64 {
    let wire = match game_ref() {
        Some(g) => g.status().wire(),
        None => "",
    };
    leak_packed(single_field_object("status", wire))
}
