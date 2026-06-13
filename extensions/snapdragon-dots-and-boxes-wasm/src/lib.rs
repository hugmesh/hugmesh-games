//! `snapdragon-dots-and-boxes-wasm` — a **thin, freestanding C-ABI / JSON shim**
//! around the pure [`snapdragon_dots_and_boxes`] rules engine so a host with NO
//! JavaScript glue can drive authoritative Dots and Boxes: it loads in a browser
//! via `WebAssembly.instantiateStreaming` (empty imports) AND in a JVM WASM
//! runtime such as Chicory.
//!
//! This file is the EXACT structural mirror of the proven
//! [`snapdragon-chess-wasm`](../../snapdragon-chess-wasm/src/lib.rs) shim — same
//! single-global-instance model, same alloc/dealloc bump allocator, same packed
//! `(ptr<<32|len)` output ABI, same hand-rolled (no-serde) JSON. Only the ops
//! and the engine type differ.
//!
//! ## What this module is
//!
//! * Plain `std` (NOT `no_std`). The core crate uses `std` (`Vec`/`String`/
//!   `format!`), so std is unavoidably in the graph. Crucially the
//!   `wasm32-unknown-unknown` std is the *freestanding* std — NOT wasi, NOT
//!   emscripten — so a pure-compute + `alloc` module like this links with **zero
//!   host imports** (verified post-build by scanning the import section). The
//!   host need only provide the module's own linear `memory`.
//! * NO `wasm-bindgen`, NO `wasi`, NO emscripten. The only dependency is the path
//!   crate `snapdragon-dots-and-boxes`, left completely UNMODIFIED. The engine
//!   ALREADY emits its own JSON (`Outcome::to_json` → the `dab.state` wire shape;
//!   `GameStatus::wire`), so this shim never invents that wire shape.
//!
//! ## Instance model — SINGLE GLOBAL instance
//!
//! ONE global `Game` (`static mut GAME`). `dab_new` / `dab_from_edges` (re)create
//! it. The host instantiates the wasm module once per board; concurrent boards =
//! multiple instances (each with its own private memory + global). The wasm is
//! single-threaded so the `static mut` access is sound.
//!
//! ## Memory contract (READ THIS before calling) — identical to the chess shim
//!
//! All strings crossing the boundary are UTF-8 bytes in the module's linear
//! memory. The host owns input buffers; the module owns output buffers.
//!
//! ### Passing bytes IN (host → module)
//!  1. host calls `alloc(len) -> ptr`;
//!  2. host writes its UTF-8 bytes into `[ptr, ptr+len)`;
//!  3. host calls an op with `(ptr, len)` as the LAST two args;
//!  4. host calls `dealloc(ptr, len)` to release it.
//!
//! ### Receiving bytes OUT (module → host) — the PACKED return
//! The query/apply ops return a single `u64` packing pointer + length:
//!
//! ```text
//!     packed: u64  ==  (ptr as u64) << 32 | (len as u64 & 0xFFFF_FFFF)
//!     ptr = (packed >> 32) as u32
//!     len = (packed & 0xFFFF_FFFF) as u32
//! ```
//!
//! The pointed-to bytes are UTF-8 JSON; the buffer is a **leaked** `Box<[u8]>`
//! the host MUST hand back via `dealloc(ptr, len)` once read.
//!
//! ### The ops (mirror `snapdragon_dots_and_boxes::Game`, reusing its OWN JSON)
//!  * `dab_new() -> i32`
//!        Build/replace the global game at the empty start board. Always `1`.
//!  * `dab_from_edges(ptr, len) -> i32`
//!        Build/replace the global game by REPLAYING a JSON array of edge indices
//!        (e.g. `[0,3,12]`) in order from a fresh board — the from-state
//!        equivalent for an engine that has no FEN. Returns `1` on success, `0`
//!        if any index is out of bounds / already-claimed / the array is
//!        unparseable (the existing global game is left UNCHANGED on `0`).
//!  * `dab_legal() -> u64`  → packed JSON **array of unclaimed edge indices** for
//!        the side to move, e.g. `[0,1,2,...]`. `[]` when there is no game / it's
//!        over.
//!  * `dab_apply(ptr, len) -> u64`  → packed. The buffer is the edge index as a
//!        decimal ASCII string (e.g. `"7"`). On a legal claim, exactly the
//!        engine's `Outcome::to_json` wire payload
//!        (`{"type":"dab.state","edge","orient","row","col","by","boxes",
//!         "extraTurn","next","score":[a,b],"status"}`). On an illegal claim (or
//!        no game), the error shape
//!        `{"type":"dots-and-boxes.error","error":"..."}`.
//!  * `dab_state() -> u64`  → packed full-board snapshot for render / guest
//!        re-sync (the engine has no FEN, so the shim synthesises this from the
//!        engine's getters, reusing the engine's `wire()` vocabulary for status +
//!        player names):
//!        `{"type":"dab.full","edges":[bool;24],"owners":[null|"one"|"two";9],
//!          "turn":"one"|"two","score":[a,b],"status":"<wire>"}`.
//!  * `dab_status() -> u64`  → packed `{"status":"<wire>"}` where `<wire>` is
//!        `GameStatus::wire()` (`"ongoing"` / `"win:one"` / `"win:two"` /
//!        `"draw"`). Empty status when there is no game.
//!
//! There is intentionally NO `dab_resign` / `dab_pass`: the core engine exposes
//! neither (a non-completing claim already passes the turn, and there is no
//! resignation rule), so the shim does not invent one.

#![allow(clippy::missing_safety_doc)]

// std is in the graph via the core crate; we use it directly. On
// wasm32-unknown-unknown this is the freestanding std (no wasi/emscripten host
// imports for pure compute + alloc). panic="abort" (Cargo.toml) traps the
// instance on a panic — the right failure mode for an authoritative oracle.
use std::boxed::Box;
use std::string::String;
use std::vec::Vec;

use snapdragon_dots_and_boxes::{
    Edge, Game, GameStatus, MoveError, Orientation, Player, BOXES, EDGES, GRID,
};

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
//  Allocator exports (host ↔ module byte buffers). Byte-for-byte the chess shim.
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
/// null/empty buffer or invalid UTF-8 — every consumer treats `""` as "bad input".
unsafe fn str_in<'a>(ptr: u32, len: u32) -> &'a str {
    if ptr == 0 || len == 0 {
        return "";
    }
    let bytes = core::slice::from_raw_parts(ptr as *const u8, len as usize);
    core::str::from_utf8(bytes).unwrap_or("")
}

// ─────────────────────────────────────────────────────────────────────────────
//  Tiny JSON encoders (no serde — keeps the module host-import-free). We only
//  ever EMIT JSON here; the engine already emits the rich Outcome payload, so
//  these helpers cover the simple shapes the shim itself synthesises.
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

/// `{"type":"dots-and-boxes.error","error":"<reason>"}` as bytes.
fn error_payload(reason: &str) -> Vec<u8> {
    let mut s = String::with_capacity(reason.len() + 48);
    s.push_str("{\"type\":\"dots-and-boxes.error\",\"error\":\"");
    push_json_escaped(&mut s, reason);
    s.push_str("\"}");
    s.into_bytes()
}

/// Stable, human-readable wire reason for each engine `MoveError`.
fn move_error_str(e: MoveError) -> &'static str {
    match e {
        MoveError::OutOfBounds => "out-of-bounds",
        MoveError::AlreadyClaimed => "already-claimed",
        MoveError::GameOver => "game-over",
    }
}

/// The engine's player-wire string (matches `Player::wire`, which is private).
fn player_wire(p: Player) -> &'static str {
    match p {
        Player::One => "one",
        Player::Two => "two",
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  The exported ops.
// ─────────────────────────────────────────────────────────────────────────────

/// Build / replace the global game at the empty start board. Returns 1.
#[no_mangle]
pub unsafe extern "C" fn dab_new() -> i32 {
    set_game(Game::new());
    1
}

/// Build / replace the global game by REPLAYING a JSON array of edge indices
/// (e.g. `"[0,3,12]"`) in order on a fresh board. Returns 1 on success, 0 if the
/// array is unparseable or any claim is rejected (the existing global game, if
/// any, is left UNCHANGED on failure). This is the from-state path for an engine
/// with no FEN: the same ordered claim sequence rebuilds identical state
/// (turns + scores + ownership) deterministically.
#[no_mangle]
pub unsafe extern "C" fn dab_from_edges(ptr: u32, len: u32) -> i32 {
    let json = str_in(ptr, len);
    let indices = match parse_index_array(json) {
        Some(v) => v,
        None => return 0,
    };
    let mut g = Game::new();
    for idx in indices {
        if g.claim(Edge(idx)).is_err() {
            return 0; // any rejected claim ⇒ unchanged global game
        }
    }
    set_game(g);
    1
}

/// Packed (ptr<<32|len) JSON array of unclaimed edge indices for the side to
/// move. `[]` when there is no game / the game is over.
#[no_mangle]
pub unsafe extern "C" fn dab_legal() -> u64 {
    let mut s = String::new();
    s.push('[');
    if let Some(g) = game_ref() {
        let mut first = true;
        for e in g.legal_moves() {
            if !first {
                s.push(',');
            }
            first = false;
            push_usize(&mut s, e.index());
        }
    }
    s.push(']');
    leak_packed(s.into_bytes())
}

/// Claim the edge whose decimal index is in the buffer (e.g. `"7"`). On success,
/// packed (ptr<<32|len) of the engine's OWN `Outcome::to_json` `dab.state`
/// payload. On an illegal claim (or no game / bad index), packed of
/// `{"type":"dots-and-boxes.error","error":"..."}`.
#[no_mangle]
pub unsafe extern "C" fn dab_apply(ptr: u32, len: u32) -> u64 {
    let token = str_in(ptr, len);
    let idx = match parse_usize(token) {
        Some(i) => i,
        None => return leak_packed(error_payload("bad-index")),
    };
    match game_mut() {
        None => leak_packed(error_payload("no game")),
        Some(g) => match g.claim(Edge(idx)) {
            Ok(outcome) => leak_packed(outcome.to_json().into_bytes()),
            Err(e) => leak_packed(error_payload(move_error_str(e))),
        },
    }
}

/// Packed (ptr<<32|len) of the full-board snapshot the renderer + guest re-sync
/// use. The engine has no FEN, so this is synthesised from the engine's public
/// getters — it INVENTS no rules, only serialises observable state, reusing the
/// engine's status `wire()` + player-name vocabulary. Empty `{}`-less when no
/// game: a minimal terminal shape with empty arrays.
#[no_mangle]
pub unsafe extern "C" fn dab_state() -> u64 {
    let g = match game_ref() {
        Some(g) => g,
        None => {
            // No game: a well-formed but empty snapshot (host treats as not-ready).
            return leak_packed(
                b"{\"type\":\"dab.full\",\"edges\":[],\"owners\":[],\"turn\":\"one\",\"score\":[0,0],\"status\":\"ongoing\"}"
                    .to_vec(),
            );
        }
    };

    let mut s = String::with_capacity(256);
    s.push_str("{\"type\":\"dab.full\",\"edges\":[");
    for i in 0..EDGES {
        if i > 0 {
            s.push(',');
        }
        s.push_str(if g.is_claimed(Edge(i)) { "true" } else { "false" });
    }
    s.push_str("],\"owners\":[");
    for b in 0..BOXES {
        if b > 0 {
            s.push(',');
        }
        let br = b / GRID;
        let bc = b % GRID;
        match g.box_owner(br, bc) {
            Some(p) => {
                s.push('"');
                s.push_str(player_wire(p));
                s.push('"');
            }
            None => s.push_str("null"),
        }
    }
    s.push_str("],\"turn\":\"");
    s.push_str(player_wire(g.turn()));
    s.push_str("\",\"score\":[");
    let (a, b) = g.score();
    push_usize(&mut s, a as usize);
    s.push(',');
    push_usize(&mut s, b as usize);
    s.push_str("],\"status\":\"");
    push_json_escaped(&mut s, &g.status().wire());
    s.push_str("\"}");
    leak_packed(s.into_bytes())
}

/// Packed (ptr<<32|len) of `{"status":"<wire>"}` where `<wire>` is the engine's
/// `GameStatus::wire()` (`"ongoing"`, `"win:one"`, `"win:two"`, `"draw"`). Empty
/// status when there is no game.
#[no_mangle]
pub unsafe extern "C" fn dab_status() -> u64 {
    let wire = match game_ref() {
        Some(g) => g.status().wire(),
        None => String::new(),
    };
    leak_packed(single_field_object("status", &wire))
}

// ─────────────────────────────────────────────────────────────────────────────
//  Tiny parse / format helpers (no serde, no float). All inputs are small
//  non-negative integers (edge indices), so a hand-rolled decimal parser is
//  enough and keeps the import section empty.
// ─────────────────────────────────────────────────────────────────────────────

/// Append a `usize` as decimal ASCII (no allocation beyond the String's).
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
    // SAFETY: buf[i..] is all ASCII digits.
    out.push_str(core::str::from_utf8(&buf[i..]).unwrap());
}

/// Parse a trimmed decimal `usize`, or `None` (empty / non-digit / overflow).
fn parse_usize(s: &str) -> Option<usize> {
    let t = s.trim();
    if t.is_empty() {
        return None;
    }
    let mut n: usize = 0;
    for c in t.bytes() {
        if !c.is_ascii_digit() {
            return None;
        }
        n = n.checked_mul(10)?.checked_add((c - b'0') as usize)?;
    }
    Some(n)
}

/// Parse a flat JSON array of non-negative integers like `[0, 3, 12]` into a
/// `Vec<usize>`. Deliberately minimal (no nesting, no floats, no escapes — the
/// only callers pass an edge-index list). Returns `None` on any malformed token.
/// `"[]"` parses to an empty Vec (a from-edges call that rebuilds a fresh board).
fn parse_index_array(s: &str) -> Option<Vec<usize>> {
    let t = s.trim();
    let inner = t.strip_prefix('[')?.strip_suffix(']')?.trim();
    if inner.is_empty() {
        return Some(Vec::new());
    }
    let mut out = Vec::new();
    for tok in inner.split(',') {
        out.push(parse_usize(tok)?);
    }
    Some(out)
}

// ─────────────────────────────────────────────────────────────────────────────
//  A couple of compile-time references so a future engine API change that drops
//  one of the types this shim relies on fails the build loudly here.
// ─────────────────────────────────────────────────────────────────────────────
#[allow(dead_code)]
const _ASSERT_ORIENTATION: Orientation = Orientation::Horizontal;
#[allow(dead_code)]
fn _assert_status_variants() {
    let _ = GameStatus::Ongoing;
}
