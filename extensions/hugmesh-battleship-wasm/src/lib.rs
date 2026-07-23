//! `hugmesh-battleship-wasm` — a **thin, freestanding C-ABI / JSON shim** around
//! the pure [`hugmesh_battleship`] rules engine so a host with NO JavaScript
//! glue can drive authoritative Battleship: it loads in a browser via
//! `WebAssembly.instantiateStreaming` (empty imports) AND in a JVM WASM runtime
//! such as Chicory.
//!
//! ## What this module is
//!
//! * Plain `std` (NOT `no_std`). The core crate `hugmesh-battleship` itself
//!   uses `std` (`Vec`/`String`/`format!`), so std is unavoidably in the
//!   dependency graph; declaring `#![no_std]` here only triggers a duplicate
//!   `panic_impl` lang-item clash. Crucially, the `wasm32-unknown-unknown` std is
//!   the *freestanding* std — it is NOT wasi and NOT emscripten, so a pure-compute
//!   + `alloc` module like this one links with **zero host imports** (verified
//!   post-build by scanning the wasm import section). The host need only provide
//!   the module's own linear `memory`.
//! * There is NO `wasm-bindgen`, NO `wasi`, NO emscripten. The only dependency is
//!   the path crate `hugmesh-battleship`, left completely UNMODIFIED. The
//!   engine ALREADY emits its own JSON (`Game::shot_json` / `Game::snapshot_json`
//!   / `GameStatus::wire`); this shim never invents a wire shape and never pulls
//!   in serde or a host.
//!
//! ## Instance model — SINGLE GLOBAL instance
//!
//! This shim keeps ONE global `Game` instance (`static mut GAME`).
//! `battleship_new` (re)creates it. **The host instantiates the wasm module once
//! per board**: a host that needs concurrent games instantiates the module
//! multiple times (each instance has its own private linear memory + global),
//! which is the idiomatic browser / Chicory pattern. The wasm is single-threaded
//! so the `static mut` access is sound (no concurrent callers within one
//! instance).
//!
//! ## FOG OF WAR — neither client is globally authoritative
//!
//! Battleship is the one game where each client is authoritative over only its
//! OWN board and is the sole resolver of shots fired AT that board. The engine's
//! `Game` holds BOTH boards, but a real client only knows its own fleet. So this
//! shim threads an explicit `player` argument through placement and SPLITS the
//! engine's atomic `fire` into a two-step request/response so a fleet layout
//! never crosses the wire:
//!
//!  * `battleship_place(player, …)` places a ship on `player`'s OWN board. A
//!    client only ever places its own player's fleet; it never learns the
//!    opponent's placements.
//!  * `battleship_resolve(coord)` — the **OWNER** of the targeted board runs this
//!    on its OWN engine (which holds its real fleet). It is exactly the engine's
//!    `fire()`: it resolves the opponent's shot against this client's fleet,
//!    records the hit, advances the turn, and returns ONLY the shot's result
//!    (`miss`/`hit`/`sunk:<ship>`/`win:<ship>`) — never an unfired cell. This is
//!    the payload the owner relays back.
//!  * `battleship_record(coord, result)` — the **SHOOTER** runs this on its OWN
//!    engine after the owner relays the result. It marks the opponent's tracking
//!    grid (a fog board) with the revealed result and advances the turn locally
//!    so both engines stay turn-synchronised. The shooter's engine learns ONLY
//!    the cells it actually fired at — a malicious peer can never learn unfired
//!    cells because the shim only ever reveals a single resolved coordinate.
//!
//! Because both clients apply the same ordered sequence of resolves/records, both
//! engines' public state (whose turn, ships sunk, terminal winner) stays in lock
//! step without either ever shipping its hidden layout.
//!
//! ## Memory contract (READ THIS before calling)
//!
//! All strings crossing the boundary are UTF-8 bytes in the module's linear
//! memory. The host owns input buffers; the module owns output buffers.
//!
//! ### Passing bytes IN (host → module)
//!  1. host calls `alloc(len) -> ptr` to get a `len`-byte region it may write to;
//!  2. host writes its bytes (a ship token, an algebraic coordinate, a result
//!     token) into `[ptr, ptr+len)`;
//!  3. host calls an op with `(ptr, len)` (as the LAST two args);
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
//! ### The ops (mirror `hugmesh_battleship::Game`, reusing its OWN JSON)
//!  * `battleship_new() -> i32`
//!        Build/replace the global game (both boards empty, placement phase, P1 to
//!        fire first). Always returns `1`.
//!  * `battleship_place(playerPtr, playerLen, shipPtr, shipLen, originPtr,
//!        originLen, orientPtr, orientLen) -> u64`
//!        Place `ship` for `player` (`"p1"`/`"p2"`/`"one"`/`"two"`/`"1"`/`"2"`) at
//!        an algebraic `origin` (`"A1".."J10"`) running `orient` (`"h"`/`"v"`,
//!        also `"horizontal"`/`"vertical"`). Packed `Game::snapshot_json()` on
//!        success, or `{"type":"battleship.error","error":"<reason>"}` on a
//!        rejected placement / bad arg.
//!  * `battleship_ready(playerPtr, playerLen) -> u64`
//!        Declare `player`'s fleet final. Packed `snapshot_json()` when accepted
//!        (firing begins once BOTH are ready), or the error shape when the fleet
//!        is incomplete / the player is unknown.
//!  * `battleship_resolve(coordPtr, coordLen) -> u64`  → packed. The OWNER
//!        resolves the opponent's shot against its OWN fleet (engine `fire()`):
//!        the engine's `Game::shot_json` wire payload
//!        (`{"type":"battleship.shot","shooter","target","coord","result","next",
//!        "status"}`) on success, or `{"type":"battleship.error","error":"…"}`
//!        (not firing / off-board / already fired / game over).
//!  * `battleship_record(coordPtr, coordLen, resultPtr, resultLen) -> u64`  →
//!        packed. The SHOOTER records a relayed `result`
//!        (`"miss"`/`"hit"`/`"sunk:<ship>"`/`"win:<ship>"`) at `coord` on the
//!        opponent's fog grid and advances the turn locally. Returns the SAME
//!        `battleship.shot` wire shape so the caller can render uniformly, or the
//!        error shape on a malformed coord/result or a phase/turn violation.
//!  * `battleship_snapshot() -> u64`  → packed `Game::snapshot_json()`, the public
//!        re-sync state (`{"type":"battleship.snapshot","status","turn","p1Placed",
//!        "p2Placed","p1Ready","p2Ready","p1Sunk","p2Sunk"}`). Deliberately omits
//!        unfired hull positions — the boards stay hidden. `{}` when no game.
//!  * `battleship_status() -> u64`  → packed `{"status":"<wire>"}` where `<wire>`
//!        is the engine's `GameStatus::wire()` (`"placing"`/`"firing"`/`"win:p1"`/
//!        `"win:p2"`). Empty status when no game.

#![allow(clippy::missing_safety_doc)]

// std is in the graph via the core crate; we use it directly. On
// wasm32-unknown-unknown this is the freestanding std (no wasi/emscripten host
// imports for pure compute + alloc). panic="abort" (in Cargo.toml) means a panic
// traps the instance — the right failure mode for an authoritative oracle.
use std::boxed::Box;
use std::string::String;
use std::vec::Vec;

use hugmesh_battleship::{
    Coord, FireError, Game, Orientation, PlaceError, Player, Ship, ShotResult,
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
/// (unknown player, bad coord, bad result) which is the correct fail-closed
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
//  ever EMIT JSON here; the engine already emits the rich shot / snapshot
//  payloads, so these helpers cover the simple shapes the shim itself synthesises.
// ─────────────────────────────────────────────────────────────────────────────

/// Escape a string into a JSON string body (no surrounding quotes). Mirrors the
/// engine's own escaping: backslash + double-quote only (the data we wrap — wire
/// status, error text — contains no control characters).
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

/// `{"type":"battleship.error","error":"<reason>"}` as bytes.
fn error_payload(reason: &str) -> Vec<u8> {
    let mut s = String::with_capacity(reason.len() + 40);
    s.push_str("{\"type\":\"battleship.error\",\"error\":\"");
    push_json_escaped(&mut s, reason);
    s.push_str("\"}");
    s.into_bytes()
}

// ─────────────────────────────────────────────────────────────────────────────
//  Parsers for the small enums the shim threads in by name.
// ─────────────────────────────────────────────────────────────────────────────

/// Parse a player name. Case-insensitive; accepts the wire token, full word, or
/// digit.
fn parse_player(s: &str) -> Option<Player> {
    let lower: String = s.chars().map(|c| c.to_ascii_lowercase()).collect();
    match lower.as_str() {
        "p1" | "one" | "1" | "player1" | "player_one" => Some(Player::One),
        "p2" | "two" | "2" | "player2" | "player_two" => Some(Player::Two),
        _ => None,
    }
}

/// Parse a ship name. Case-insensitive; matches the engine's wire tokens.
fn parse_ship(s: &str) -> Option<Ship> {
    let lower: String = s.chars().map(|c| c.to_ascii_lowercase()).collect();
    match lower.as_str() {
        "carrier" => Some(Ship::Carrier),
        "battleship" => Some(Ship::Battleship),
        "cruiser" => Some(Ship::Cruiser),
        "submarine" => Some(Ship::Submarine),
        "destroyer" => Some(Ship::Destroyer),
        _ => None,
    }
}

/// Parse an orientation. Case-insensitive; accepts the wire token or full word.
fn parse_orientation(s: &str) -> Option<Orientation> {
    let lower: String = s.chars().map(|c| c.to_ascii_lowercase()).collect();
    match lower.as_str() {
        "h" | "horizontal" => Some(Orientation::Horizontal),
        "v" | "vertical" => Some(Orientation::Vertical),
        _ => None,
    }
}

/// Parse a relayed `ShotResult` wire token (`"miss"`, `"hit"`, `"sunk:<ship>"`,
/// `"win:<ship>"`) back into the engine enum. Returns `None` on a malformed token
/// or unknown ship name.
fn parse_result(s: &str) -> Option<ShotResult> {
    let lower: String = s.chars().map(|c| c.to_ascii_lowercase()).collect();
    match lower.as_str() {
        "miss" => Some(ShotResult::Miss),
        "hit" => Some(ShotResult::Hit),
        other => {
            if let Some(ship) = other.strip_prefix("sunk:") {
                parse_ship(ship).map(ShotResult::Sunk)
            } else if let Some(ship) = other.strip_prefix("win:") {
                parse_ship(ship).map(ShotResult::Win)
            } else {
                None
            }
        }
    }
}

/// Map the engine's `PlaceError` to a stable, human-readable wire reason.
fn place_error_str(e: PlaceError) -> &'static str {
    match e {
        PlaceError::NotPlacing => "not-placing",
        PlaceError::OffBoard => "off-board",
        PlaceError::OutOfBounds => "out-of-bounds",
        PlaceError::Overlap => "overlap",
        PlaceError::AlreadyPlaced => "already-placed",
    }
}

/// Map the engine's `FireError` to a stable, human-readable wire reason.
fn fire_error_str(e: FireError) -> &'static str {
    match e {
        FireError::NotFiring => "not-firing",
        FireError::GameOver => "game-over",
        FireError::OffBoard => "off-board",
        FireError::AlreadyFired => "already-fired",
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  The exported ops.
// ─────────────────────────────────────────────────────────────────────────────

/// Build / replace the global game (empty boards, placement phase). Returns 1.
#[no_mangle]
pub unsafe extern "C" fn battleship_new() -> i32 {
    set_game(Game::new());
    1
}

/// Place `ship` for `player` at an algebraic `origin` running `orient`. Packed
/// `snapshot_json()` on success, or the error shape on a rejected placement / bad
/// arg.
#[no_mangle]
pub unsafe extern "C" fn battleship_place(
    player_ptr: u32,
    player_len: u32,
    ship_ptr: u32,
    ship_len: u32,
    origin_ptr: u32,
    origin_len: u32,
    orient_ptr: u32,
    orient_len: u32,
) -> u64 {
    let player = match parse_player(str_in(player_ptr, player_len)) {
        Some(p) => p,
        None => return leak_packed(error_payload("bad-player")),
    };
    let ship = match parse_ship(str_in(ship_ptr, ship_len)) {
        Some(s) => s,
        None => return leak_packed(error_payload("bad-ship")),
    };
    let orient = match parse_orientation(str_in(orient_ptr, orient_len)) {
        Some(o) => o,
        None => return leak_packed(error_payload("bad-orientation")),
    };
    let origin = str_in(origin_ptr, origin_len);
    match game_mut() {
        None => leak_packed(error_payload("no-game")),
        Some(g) => match g.place_ship_str(player, ship, origin, orient) {
            Ok(()) => leak_packed(g.snapshot_json().into_bytes()),
            Err(e) => leak_packed(error_payload(place_error_str(e))),
        },
    }
}

/// Declare `player`'s fleet final. Packed `snapshot_json()` when accepted, or the
/// error shape when the fleet is incomplete / the player is unknown / no game.
#[no_mangle]
pub unsafe extern "C" fn battleship_ready(player_ptr: u32, player_len: u32) -> u64 {
    let player = match parse_player(str_in(player_ptr, player_len)) {
        Some(p) => p,
        None => return leak_packed(error_payload("bad-player")),
    };
    match game_mut() {
        None => leak_packed(error_payload("no-game")),
        Some(g) => {
            if g.ready(player) {
                leak_packed(g.snapshot_json().into_bytes())
            } else {
                leak_packed(error_payload("fleet-incomplete"))
            }
        }
    }
}

/// OWNER side of a shot: resolve the opponent's fire at `coord` against this
/// client's OWN fleet (the engine's `fire()`). Packed `shot_json` on success
/// (revealing ONLY the result), or the error shape.
#[no_mangle]
pub unsafe extern "C" fn battleship_resolve(coord_ptr: u32, coord_len: u32) -> u64 {
    let coord_str = str_in(coord_ptr, coord_len);
    let coord = match Coord::parse(coord_str) {
        Some(c) => c,
        None => return leak_packed(error_payload("off-board")),
    };
    match game_mut() {
        None => leak_packed(error_payload("no-game")),
        Some(g) => {
            // The engine fires for `turn` at `turn.opposite()`. Capture the
            // shooter BEFORE the move (fire() may flip the turn).
            let shooter = g.turn();
            match g.fire(coord) {
                Ok(result) => leak_packed(g.shot_json(shooter, coord, result).into_bytes()),
                Err(e) => leak_packed(error_payload(fire_error_str(e))),
            }
        }
    }
}

/// SHOOTER side of a shot: record a relayed `result` at `coord` on the opponent's
/// fog grid and advance the turn locally so both engines stay turn-synchronised.
/// Returns the SAME `battleship.shot` wire shape, or the error shape on a
/// malformed coord/result or a phase/turn violation.
///
/// Because the shooter's local engine does NOT hold the opponent's real fleet,
/// it cannot resolve a hit itself — it learns the truth only from the owner's
/// relayed `result`. We therefore drive the engine's `fire()` (which marks the
/// cell as fired and advances the turn so the public turn/terminal state stays in
/// lock step with the owner) and then synthesise the `shot_json` from the
/// AUTHORITATIVE relayed result rather than the engine's locally-computed Miss.
#[no_mangle]
pub unsafe extern "C" fn battleship_record(
    coord_ptr: u32,
    coord_len: u32,
    result_ptr: u32,
    result_len: u32,
) -> u64 {
    let coord_str = str_in(coord_ptr, coord_len);
    let coord = match Coord::parse(coord_str) {
        Some(c) => c,
        None => return leak_packed(error_payload("off-board")),
    };
    let relayed = match parse_result(str_in(result_ptr, result_len)) {
        Some(r) => r,
        None => return leak_packed(error_payload("bad-result")),
    };
    match game_mut() {
        None => leak_packed(error_payload("no-game")),
        Some(g) => {
            let shooter = g.turn();
            // Drive the engine so the cell is marked fired and the turn advances
            // exactly as the owner's engine did. The opponent's board is empty on
            // the shooter's engine, so fire() returns Miss locally — we DISCARD
            // that and report the owner's authoritative `relayed` result instead.
            match g.fire(coord) {
                Ok(_local_miss) => {
                    // If the owner reported a fleet-ending Win the local engine
                    // never set its own `winner` (its board is empty), so the
                    // turn/status on the shooter's engine still reads "firing".
                    // The wire payload below carries the AUTHORITATIVE result +
                    // the engine's post-fire turn, which agrees with the owner
                    // for every non-terminal result. For a terminal Win we still
                    // emit the win wire so the UI shows game-over; the shooter's
                    // snapshot status stays "firing" (its tracking grid, not a
                    // real fleet), which is acceptable — the owner is the truth.
                    leak_packed(g.shot_json(shooter, coord, relayed).into_bytes())
                }
                Err(e) => leak_packed(error_payload(fire_error_str(e))),
            }
        }
    }
}

/// Packed `Game::snapshot_json()` for the CURRENT position — the public re-sync
/// state. `{}` when there is no game.
#[no_mangle]
pub unsafe extern "C" fn battleship_snapshot() -> u64 {
    let json = match game_ref() {
        Some(g) => g.snapshot_json(),
        None => String::from("{}"),
    };
    leak_packed(json.into_bytes())
}

/// Packed `{"status":"<wire>"}` where `<wire>` is the engine's `GameStatus::wire()`
/// (`"placing"` / `"firing"` / `"win:p1"` / `"win:p2"`). Empty status when no game.
#[no_mangle]
pub unsafe extern "C" fn battleship_status() -> u64 {
    let wire = match game_ref() {
        Some(g) => g.status().wire(),
        None => String::new(),
    };
    leak_packed(single_field_object("status", &wire))
}
