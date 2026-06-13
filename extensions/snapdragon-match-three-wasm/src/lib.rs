//! `snapdragon-match-three-wasm` — a **thin, freestanding C-ABI / JSON shim**
//! around the pure, SEEDED [`snapdragon_match_three`] rules engine so a host with
//! NO JavaScript glue can drive an authoritative Match-3 puzzle: it loads in a
//! browser via `WebAssembly.instantiateStreaming` (empty imports) AND in a JVM
//! WASM runtime such as Chicory.
//!
//! ## What this module is
//!
//! * Plain `std` (NOT `no_std`). The core crate `snapdragon-match-three` itself
//!   uses `std` (`Vec`/`String`/`format!`), so std is unavoidably in the
//!   dependency graph; declaring `#![no_std]` here only triggers a duplicate
//!   `panic_impl` lang-item clash. Crucially, the `wasm32-unknown-unknown` std is
//!   the *freestanding* std — it is NOT wasi and NOT emscripten, so a pure-compute
//!   + `alloc` module like this one links with **zero host imports** (verified
//!   post-build by scanning the wasm import section). The host need only provide
//!   the module's own linear `memory`.
//! * There is NO `wasm-bindgen`, NO `wasi`, NO emscripten. The only dependency is
//!   the path crate `snapdragon-match-three`, left completely UNMODIFIED. The
//!   engine ALREADY emits its own JSON (`Outcome::to_json`, `Game::to_json`,
//!   `GameStatus::wire`, `Game::snapshot`), so this shim never invents a wire shape
//!   and never pulls in serde or a host. The shapes mirror the chess / reversi shim
//!   contract one-for-one (`match3.snapshot` view / `match3.swap` outcome /
//!   `match3.error`).
//!
//! ## SINGLE-PLAYER — NO relay
//!
//! Match-3 is a single-player score-attack puzzle (the engine's own doc-comment is
//! explicit: "no multiplayer, no server, no relay"). The board is fully determined
//! by its `(width, height, colors, seed)`, so the browser and the test suite agree
//! byte-for-byte. This shim is the in-browser entrypoint to that engine; there is
//! no server side and nothing is relayed for a single-player puzzle.
//!
//! ## Instance model — SINGLE GLOBAL instance
//!
//! This shim keeps ONE global `Game` instance (`static mut GAME`). `match3_new` /
//! `match3_restore` (re)create it. **The host instantiates the wasm module once per
//! board**: a host that needs concurrent boards instantiates the module multiple
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
//!  2. host writes its bytes into `[ptr, ptr+len)`;
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
//! ### The ops (mirror `snapdragon_match_three::Game`, reusing its OWN JSON)
//!  * `match3_new(ptr, len) -> i32`
//!        Build/replace the global board from a CSV config string
//!        `"<width>,<height>,<colors>,<seed>"` (e.g. `"8,8,6,42"`). Returns `1` on
//!        success, `0` on a malformed / degenerate config (the engine's `Game::new`
//!        panics on a degenerate request — `width`/`height` < 1, `colors` < 3, or a
//!        board too small to hold a 3-run — so this op VALIDATES the config first
//!        and returns `0` rather than letting a panic trap the instance). On `0`
//!        the existing global game, if any, is left UNCHANGED.
//!  * `match3_snapshot() -> u64`  → packed `Game::to_json()` for the current board
//!        (`{"type":"match3.snapshot","width","height","colors","score","board":[[…]],"legalSwaps","status"}`).
//!        Empty `{}` when there is no game.
//!  * `match3_legal() -> u64`  → packed JSON **array of legal swaps**, each a
//!        4-tuple `[ax, ay, bx, by]` (the two orthogonally-adjacent cells whose swap
//!        forms a 3+ run), e.g. `[[0,2,1,2],[3,0,3,1]]`. `[]` when there is no game
//!        OR the board is dead (no legal swap exists).
//!  * `match3_swap(ptr, len) -> u64`  → packed. The buffer is the CSV
//!        `"<ax>,<ay>,<bx>,<by>"`. On a legal swap, exactly the engine's OWN
//!        `Outcome::to_json` wire payload
//!        (`{"type":"match3.swap","cleared","cascades","score","steps":[…],"status"}`)
//!        — the result of clearing the match, applying gravity, refilling, and
//!        resolving the full cascade. The host then re-reads `match3_snapshot()` for
//!        the settled board. On a rejected swap (out-of-bounds, not-adjacent,
//!        no-match, game-over) or no game, the error shape
//!        `{"type":"match3.error","error":"..."}` and the board is UNTOUCHED.
//!  * `match3_status() -> u64`  → packed `{"status":"<wire>"}` where `<wire>` is the
//!        engine's `GameStatus::wire()` string (`"playing"` / `"no_moves"`). Empty
//!        status when there is no game.
//!  * `match3_score() -> u64`  → packed `{"score":<n>}` — the running cumulative
//!        score. `0` when there is no game.
//!  * `match3_save() -> u64`  → packed `{"save":"<save-string>"}` — the engine's
//!        compact round-trippable `Game::snapshot()` save string (carries the RNG
//!        position so a resumed game cascades identically). Empty `""` when there is
//!        no game.
//!  * `match3_restore(ptr, len) -> i32`  → rebuild the global game from a save
//!        string (the buffer, produced by `match3_save`). Returns `1` on success,
//!        `0` on a malformed save string (the existing global game, if any, is left
//!        UNCHANGED on `0`).

#![allow(clippy::missing_safety_doc)]

// std is in the graph via the core crate; we use it directly. On
// wasm32-unknown-unknown this is the freestanding std (no wasi/emscripten host
// imports for pure compute + alloc). panic="abort" (in Cargo.toml) means a panic
// traps the instance — the right failure mode for an authoritative oracle.
use std::boxed::Box;
use std::string::String;
use std::vec::Vec;

use snapdragon_match_three::{Game, Pos, SwapError};

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
/// (no game, unparseable config / swap) which is the correct fail-closed behaviour.
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
//  plus the legal-swap-array.
// ─────────────────────────────────────────────────────────────────────────────

/// Escape a string into a JSON string body (no surrounding quotes). Mirrors the
/// chess / reversi shim's `push_json_escaped`: backslash + double-quote only (the
/// data we wrap — wire status, error text, the save string — is ASCII
/// pipe/digit/base-36 with no control characters).
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

/// `{"type":"match3.error","error":"<reason>"}` as bytes. Mirrors the chess /
/// reversi shim's error shape exactly, swapping the game prefix.
fn error_payload(reason: &str) -> Vec<u8> {
    let mut s = String::with_capacity(reason.len() + 36);
    s.push_str("{\"type\":\"match3.error\",\"error\":\"");
    push_json_escaped(&mut s, reason);
    s.push_str("\"}");
    s.into_bytes()
}

/// Append a small unsigned integer's decimal digits to `out` without allocating.
fn push_u64(out: &mut String, mut n: u64) {
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
    // Every byte written is an ASCII digit, so this is valid UTF-8.
    out.push_str(core::str::from_utf8(&buf[i..]).unwrap_or("0"));
}

// ─────────────────────────────────────────────────────────────────────────────
//  Config / swap parsing.
// ─────────────────────────────────────────────────────────────────────────────

/// Parse a `"<width>,<height>,<colors>,<seed>"` config string and VALIDATE it
/// against the engine's `Game::new` preconditions, so a degenerate config returns
/// a clean `0` from `match3_new` instead of panicking (and trapping the instance).
/// Returns `(width, height, colors, seed)` on success.
fn parse_config(s: &str) -> Option<(u16, u16, u8, u64)> {
    let mut it = s.split(',');
    let width: u16 = it.next()?.trim().parse().ok()?;
    let height: u16 = it.next()?.trim().parse().ok()?;
    let colors: u8 = it.next()?.trim().parse().ok()?;
    let seed: u64 = it.next()?.trim().parse().ok()?;
    if it.next().is_some() {
        return None; // too many fields
    }
    // Mirror Game::new's asserts (width>=1, height>=1, colors>=3, can hold a 3-run).
    if width < 1 || height < 1 || colors < 3 {
        return None;
    }
    if width < 3 && height < 3 {
        return None;
    }
    Some((width, height, colors, seed))
}

/// Parse a `"<ax>,<ay>,<bx>,<by>"` swap string into the two cell positions.
/// Returns `None` for any non-numeric / wrong-arity input so the apply op can
/// return the `bad-swap` error shape rather than guessing.
fn parse_swap(s: &str) -> Option<(Pos, Pos)> {
    let mut it = s.split(',');
    let ax: u16 = it.next()?.trim().parse().ok()?;
    let ay: u16 = it.next()?.trim().parse().ok()?;
    let bx: u16 = it.next()?.trim().parse().ok()?;
    let by: u16 = it.next()?.trim().parse().ok()?;
    if it.next().is_some() {
        return None;
    }
    Some((Pos::new(ax, ay), Pos::new(bx, by)))
}

/// Map the engine's `SwapError` to a stable, human-readable wire reason.
fn swap_error_str(e: SwapError) -> &'static str {
    match e {
        SwapError::OutOfBounds => "out-of-bounds",
        SwapError::NotAdjacent => "not-adjacent",
        SwapError::NoMatch => "no-match",
        SwapError::GameOver => "game-over",
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  The exported ops.
// ─────────────────────────────────────────────────────────────────────────────

/// Build / replace the global board from a `"<width>,<height>,<colors>,<seed>"`
/// config string. Returns `1` on success, `0` on a malformed / degenerate config
/// (existing global game left UNCHANGED on `0`). The opening board is GENERATED
/// with no initial matches (seeded), so the player must create the first match.
#[no_mangle]
pub unsafe extern "C" fn match3_new(ptr: u32, len: u32) -> i32 {
    let raw = str_in(ptr, len);
    match parse_config(raw) {
        Some((w, h, colors, seed)) => {
            set_game(Game::new(w, h, colors, seed));
            1
        }
        None => 0,
    }
}

/// Packed (ptr<<32|len) of the engine's `Game::to_json()` for the current board
/// (full board + dimensions + score + legal-swap count + status). Empty `{}`
/// when there is no game.
#[no_mangle]
pub unsafe extern "C" fn match3_snapshot() -> u64 {
    let json = match game_ref() {
        Some(g) => g.to_json(),
        None => String::from("{}"),
    };
    leak_packed(json.into_bytes())
}

/// Packed (ptr<<32|len) of a JSON array of the legal swaps on the current board,
/// each a 4-tuple `[ax, ay, bx, by]`. `[]` when there is no game or the board is
/// dead.
#[no_mangle]
pub unsafe extern "C" fn match3_legal() -> u64 {
    let mut s = String::new();
    s.push('[');
    if let Some(g) = game_ref() {
        let mut first = true;
        for (a, b) in g.legal_swaps() {
            if !first {
                s.push(',');
            }
            first = false;
            s.push('[');
            push_u64(&mut s, a.x as u64);
            s.push(',');
            push_u64(&mut s, a.y as u64);
            s.push(',');
            push_u64(&mut s, b.x as u64);
            s.push(',');
            push_u64(&mut s, b.y as u64);
            s.push(']');
        }
    }
    s.push(']');
    leak_packed(s.into_bytes())
}

/// Swap the two orthogonally-adjacent cells named (CSV `"ax,ay,bx,by"`) in the
/// buffer and resolve the full cascade. On a legal swap, packed (ptr<<32|len) of
/// the engine's OWN `Outcome::to_json` wire payload (cleared / cascades / score /
/// per-step clears / status). On a rejected swap (out-of-bounds / not-adjacent /
/// no-match / game-over) or no game, packed of
/// `{"type":"match3.error","error":"..."}` and the board is left UNTOUCHED.
#[no_mangle]
pub unsafe extern "C" fn match3_swap(ptr: u32, len: u32) -> u64 {
    let raw = str_in(ptr, len);
    let (a, b) = match parse_swap(raw) {
        Some(pair) => pair,
        None => return leak_packed(error_payload("bad-swap")),
    };
    match game_mut() {
        None => leak_packed(error_payload("no-game")),
        Some(g) => match g.swap(a, b) {
            // Reuse the engine's OWN cascade-outcome JSON verbatim. The settled
            // board is then read by the host via match3_snapshot().
            Ok(outcome) => leak_packed(outcome.to_json().into_bytes()),
            Err(e) => leak_packed(error_payload(swap_error_str(e))),
        },
    }
}

/// Packed (ptr<<32|len) of `{"status":"<wire>"}` where `<wire>` is the engine's
/// `GameStatus::wire()` (`"playing"` / `"no_moves"`). Empty status when no game.
#[no_mangle]
pub unsafe extern "C" fn match3_status() -> u64 {
    let wire = match game_ref() {
        Some(g) => g.status().wire(),
        None => "",
    };
    leak_packed(single_field_object("status", wire))
}

/// Packed (ptr<<32|len) of `{"score":<n>}` — the running cumulative score. `0`
/// when there is no game.
#[no_mangle]
pub unsafe extern "C" fn match3_score() -> u64 {
    let score = match game_ref() {
        Some(g) => g.score(),
        None => 0,
    };
    let mut s = String::with_capacity(16);
    s.push_str("{\"score\":");
    push_u64(&mut s, score);
    s.push('}');
    leak_packed(s.into_bytes())
}

/// Packed (ptr<<32|len) of `{"save":"<save-string>"}` — the engine's compact,
/// round-trippable `Game::snapshot()` save string (it carries the RNG position so
/// a resumed game cascades identically). Empty `""` when there is no game.
#[no_mangle]
pub unsafe extern "C" fn match3_save() -> u64 {
    let save = match game_ref() {
        Some(g) => g.snapshot(),
        None => String::new(),
    };
    leak_packed(single_field_object("save", &save))
}

/// Rebuild the global game from a `Game::snapshot()` save string (the buffer,
/// produced by `match3_save`). Returns `1` on success, `0` on a malformed save
/// string (existing global game, if any, left UNCHANGED on `0`).
#[no_mangle]
pub unsafe extern "C" fn match3_restore(ptr: u32, len: u32) -> i32 {
    let raw = str_in(ptr, len);
    match Game::restore(raw) {
        Ok(g) => {
            set_game(g);
            1
        }
        Err(_) => 0,
    }
}
