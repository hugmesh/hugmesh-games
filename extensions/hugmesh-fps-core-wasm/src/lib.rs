//! `hugmesh-fps-core-wasm` — a **thin, freestanding C-ABI / JSON shim** around
//! the pure [`hugmesh_fps_core`] sim so a host with NO JavaScript (a JVM WASM
//! runtime such as Chicory) can drive the authoritative FPS simulation.
//!
//! ## What this module is
//!
//! * Plain `std` (NOT `no_std`). The core crate `hugmesh-fps-core` itself uses
//!   `std` (`Vec`/`String`/`format!`), so std is unavoidably in the dependency
//!   graph; declaring `#![no_std]` here only triggers a duplicate `panic_impl`
//!   lang-item clash. Crucially, the `wasm32-unknown-unknown` std is the
//!   *freestanding* std — it is NOT wasi and NOT emscripten, so a pure-compute +
//!   `alloc` module like this one links with **zero host imports** (verified post
//!   build with `wasm-objdump -x` / a custom import scan). The host need only
//!   provide the module's own linear `memory`; Chicory can instantiate it bare.
//! * There is NO `wasm-bindgen`, NO `wasi`, NO emscripten. The only dependency is
//!   the path crate `hugmesh-fps-core`, left completely UNMODIFIED. All JSON
//!   parsing/encoding lives here, hand-rolled, so nothing pulls in serde or a host.
//!
//! ## Instance model — SINGLE GLOBAL instance
//!
//! Multi-instance handles are awkward in a freestanding `no_std` module, so this
//! shim keeps ONE global match instance (`static mut GAME`). `fps_new` (re)creates
//! it. A host that needs concurrent matches instantiates the wasm module multiple
//! times (each instance has its own private linear memory + global), which is the
//! idiomatic Chicory pattern anyway. The wasm is single-threaded so the `static
//! mut` access is sound (no concurrent callers within one instance).
//!
//! ## Memory contract (READ THIS before calling)
//!
//! All strings crossing the boundary are UTF-8 JSON bytes in the module's linear
//! memory. The host owns input buffers; the module owns output buffers.
//!
//! ### Passing bytes IN (host → module)
//!  1. host calls `alloc(len) -> ptr` to get a `len`-byte region it may write to;
//!  2. host writes its JSON bytes into `[ptr, ptr+len)`;
//!  3. host calls an op with `(ptr, len)`;
//!  4. after the op returns, the host calls `dealloc(ptr, len)` to release it.
//!
//! ### Receiving bytes OUT (module → host)  — the PACKED return
//! `fps_snapshot()` and `fps_result()` return a single `i64` (u64 bit pattern)
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
//! instance. (For a short differential test, leaking is harmless.)
//!
//! ### The ops (mirror `hugmesh_fps_core::Game`)
//!  * `fps_new(seed: u64, config_ptr: u32, config_len: u32) -> i32`
//!        Build/replace the global match from a CONFIG JSON (see below). Returns
//!        `0` on success, negative on a parse error. The `seed` argument is
//!        authoritative; a `"seed"` key in the config JSON, if present, is ignored
//!        in favour of the argument (the host passes the seed explicitly per the
//!        SimCore `from_seed` shape).
//!  * `fps_step(commands_ptr: u32, commands_len: u32)`
//!        Advance one tick applying a COMMANDS JSON array (see below). No-op if no
//!        game exists or the match is already terminal (mirrors `Game::step`).
//!  * `fps_snapshot() -> i64`  → packed (ptr<<32|len) of the snapshot JSON, exactly
//!        what `Game::snapshot()` / `to_json()` emits. Empty (`0`) if no game.
//!  * `fps_is_terminal() -> i32`  → `1` if the match has ended, else `0`.
//!  * `fps_result() -> i64`  → packed (ptr<<32|len) of a small RESULT JSON
//!        `{"type":"fps.result","terminal":bool,"status":"ongoing|win:N","tick":T}`
//!        synthesised from `Game::status()` + `tick_count()` (the core has no
//!        `result_json`; this is the shim's faithful projection of its public
//!        status API).
//!  * `fps_restore(replay_ptr: u32, replay_len: u32) -> i32`
//!        Reconstruct the global match from a REPLAY JSON `{config, log}` — the
//!        command-log-as-truth model the core actually supports
//!        (`Game::replay(seed, arena, players, log)`). The core deliberately never
//!        parses a snapshot back (`to_json` doc: "the server never parses this
//!        back; reconstruction uses replay"), so restore is replay-from-log, NOT
//!        snapshot-injection. Returns `0` on success, negative on a parse error.
//!
//! ### CONFIG JSON (consumed by `fps_new` and inside `fps_restore`'s `config`)
//! ```json
//! {
//!   "width": 400, "height": 300,
//!   "walls": [ {"x0":180,"y0":120,"x1":220,"y1":180} ],
//!   "frag_limit": 0,
//!   "players": [ {"id":1,"x":40,"y":150,"yaw":0}, ... ]
//! }
//! ```
//! `walls` and `frag_limit` are optional (default `[]` / `0`). Each player needs
//! `id`,`x`,`y`,`yaw` (world units / yaw step) — the Spawn shape.
//!
//! ### COMMANDS JSON (consumed by `fps_step`, and each `log` entry of restore)
//! A JSON array of per-player inputs for the tick. `move_forward`, `move_strafe`,
//! `turn` default `0`; `fire` defaults `false`:
//! ```json
//! [ {"id":1,"move_forward":1024,"move_strafe":0,"turn":4,"fire":true}, ... ]
//! ```
//! Magnitudes are the SAME fixed-point units the core's `Input` uses (`MOVE_SPEED
//! == 4*FP == 1024`). Ids that name no player are ignored by the core.

#![allow(clippy::missing_safety_doc)]

// std is in the graph via the core crate; we use it directly. On
// wasm32-unknown-unknown this is the freestanding std (no wasi/emscripten host
// imports for pure compute + alloc). panic="abort" (in Cargo.toml) means a panic
// traps the instance — the right failure mode for a determinism oracle.
use std::boxed::Box;
use std::string::String;
use std::vec::Vec;

use hugmesh_fps_core::{Arena, Game, Input, Spawn, Wall};

// ─────────────────────────────────────────────────────────────────────────────
//  The single global match instance.
// ─────────────────────────────────────────────────────────────────────────────
static mut GAME: Option<Game> = None;

#[inline]
unsafe fn game_mut() -> Option<&'static mut Game> {
    // Safe within this single-threaded wasm instance: no concurrent callers.
    let ptr = core::ptr::addr_of_mut!(GAME);
    (*ptr).as_mut()
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

/// Read a host-provided buffer as a byte slice (no copy).
unsafe fn slice_in<'a>(ptr: u32, len: u32) -> &'a [u8] {
    if ptr == 0 || len == 0 {
        return &[];
    }
    core::slice::from_raw_parts(ptr as *const u8, len as usize)
}

// ─────────────────────────────────────────────────────────────────────────────
//  Tiny hand-rolled JSON reader (no serde — keeps the module host-import-free).
//  Only the subset the contract needs: objects, arrays, numbers, bools, strings.
// ─────────────────────────────────────────────────────────────────────────────

struct Json<'a> {
    b: &'a [u8],
    i: usize,
}

impl<'a> Json<'a> {
    fn new(b: &'a [u8]) -> Json<'a> {
        Json { b, i: 0 }
    }
    fn ws(&mut self) {
        while self.i < self.b.len() {
            match self.b[self.i] {
                b' ' | b'\t' | b'\n' | b'\r' => self.i += 1,
                _ => break,
            }
        }
    }
    fn peek(&mut self) -> Option<u8> {
        self.ws();
        self.b.get(self.i).copied()
    }
    fn eat(&mut self, c: u8) -> bool {
        if self.peek() == Some(c) {
            self.i += 1;
            true
        } else {
            false
        }
    }
    /// Parse a (possibly negative) integer; returns it as i64.
    fn int(&mut self) -> Option<i64> {
        self.ws();
        let start = self.i;
        if self.peek() == Some(b'-') {
            self.i += 1;
        }
        let mut any = false;
        while self.i < self.b.len() && self.b[self.i].is_ascii_digit() {
            self.i += 1;
            any = true;
        }
        if !any {
            return None;
        }
        let mut val: i64 = 0;
        let mut neg = false;
        for &c in &self.b[start..self.i] {
            if c == b'-' {
                neg = true;
            } else {
                val = val.wrapping_mul(10).wrapping_add((c - b'0') as i64);
            }
        }
        Some(if neg { -val } else { val })
    }
    fn u64(&mut self) -> Option<u64> {
        self.int().map(|v| v as u64)
    }
    /// Parse a JSON string into an owned String (handles the escapes our inputs use).
    fn string(&mut self) -> Option<String> {
        if !self.eat(b'"') {
            return None;
        }
        let mut s = String::new();
        while self.i < self.b.len() {
            let c = self.b[self.i];
            self.i += 1;
            match c {
                b'"' => return Some(s),
                b'\\' => {
                    let e = *self.b.get(self.i)?;
                    self.i += 1;
                    match e {
                        b'"' => s.push('"'),
                        b'\\' => s.push('\\'),
                        b'/' => s.push('/'),
                        b'n' => s.push('\n'),
                        b't' => s.push('\t'),
                        b'r' => s.push('\r'),
                        _ => s.push(e as char),
                    }
                }
                _ => s.push(c as char),
            }
        }
        None
    }
    fn bool(&mut self) -> Option<bool> {
        self.ws();
        if self.b[self.i..].starts_with(b"true") {
            self.i += 4;
            Some(true)
        } else if self.b[self.i..].starts_with(b"false") {
            self.i += 5;
            Some(false)
        } else {
            None
        }
    }
    /// Skip one JSON value of any shape (used to ignore keys we don't read).
    fn skip_value(&mut self) {
        match self.peek() {
            Some(b'{') => {
                self.i += 1;
                loop {
                    if self.eat(b'}') {
                        break;
                    }
                    let _ = self.string();
                    self.eat(b':');
                    self.skip_value();
                    if !self.eat(b',') {
                        self.eat(b'}');
                        break;
                    }
                }
            }
            Some(b'[') => {
                self.i += 1;
                loop {
                    if self.eat(b']') {
                        break;
                    }
                    self.skip_value();
                    if !self.eat(b',') {
                        self.eat(b']');
                        break;
                    }
                }
            }
            Some(b'"') => {
                let _ = self.string();
            }
            Some(b't') | Some(b'f') => {
                let _ = self.bool();
            }
            _ => {
                let _ = self.int();
            }
        }
    }
}

// ── config / commands parsers ────────────────────────────────────────────────

struct ParsedConfig {
    arena: Arena,
    players: Vec<(u32, Spawn)>,
    frag_limit: u32,
}

/// Parse a CONFIG JSON object into the pieces `Game::with_frag_limit` needs.
fn parse_config(bytes: &[u8]) -> Option<ParsedConfig> {
    let mut j = Json::new(bytes);
    if !j.eat(b'{') {
        return None;
    }
    let mut width: i32 = 1;
    let mut height: i32 = 1;
    let mut frag_limit: u32 = 0;
    let mut walls: Vec<Wall> = Vec::new();
    let mut players: Vec<(u32, Spawn)> = Vec::new();

    loop {
        if j.eat(b'}') {
            break;
        }
        let key = j.string()?;
        j.eat(b':');
        match key.as_str() {
            "width" => width = j.int()? as i32,
            "height" => height = j.int()? as i32,
            "frag_limit" => frag_limit = j.u64()? as u32,
            "walls" => {
                if !j.eat(b'[') {
                    return None;
                }
                loop {
                    if j.eat(b']') {
                        break;
                    }
                    let w = parse_wall(&mut j)?;
                    walls.push(w);
                    if !j.eat(b',') {
                        j.eat(b']');
                        break;
                    }
                }
            }
            "players" => {
                if !j.eat(b'[') {
                    return None;
                }
                loop {
                    if j.eat(b']') {
                        break;
                    }
                    let p = parse_player_spawn(&mut j)?;
                    players.push(p);
                    if !j.eat(b',') {
                        j.eat(b']');
                        break;
                    }
                }
            }
            _ => j.skip_value(), // ignore unknown keys (e.g. a "seed" the arg owns)
        }
        if !j.eat(b',') {
            j.eat(b'}');
            break;
        }
    }

    let mut arena = Arena::new(width, height);
    for w in walls {
        arena = arena.with_wall(w);
    }
    Some(ParsedConfig {
        arena,
        players,
        frag_limit,
    })
}

fn parse_wall(j: &mut Json) -> Option<Wall> {
    if !j.eat(b'{') {
        return None;
    }
    let (mut x0, mut y0, mut x1, mut y1) = (0i32, 0i32, 0i32, 0i32);
    loop {
        if j.eat(b'}') {
            break;
        }
        let key = j.string()?;
        j.eat(b':');
        let v = j.int()? as i32;
        match key.as_str() {
            "x0" => x0 = v,
            "y0" => y0 = v,
            "x1" => x1 = v,
            "y1" => y1 = v,
            _ => {}
        }
        if !j.eat(b',') {
            j.eat(b'}');
            break;
        }
    }
    Some(Wall::new(x0, y0, x1, y1))
}

fn parse_player_spawn(j: &mut Json) -> Option<(u32, Spawn)> {
    if !j.eat(b'{') {
        return None;
    }
    let (mut id, mut x, mut y, mut yaw) = (0u32, 0i32, 0i32, 0i32);
    loop {
        if j.eat(b'}') {
            break;
        }
        let key = j.string()?;
        j.eat(b':');
        let v = j.int()?;
        match key.as_str() {
            "id" => id = v as u32,
            "x" => x = v as i32,
            "y" => y = v as i32,
            "yaw" => yaw = v as i32,
            _ => {}
        }
        if !j.eat(b',') {
            j.eat(b'}');
            break;
        }
    }
    Some((id, Spawn::new(x, y, yaw)))
}

/// Parse a COMMANDS JSON array into the `(id, Input)` pairs `Game::step` takes.
fn parse_commands(bytes: &[u8]) -> Option<Vec<(u32, Input)>> {
    let mut j = Json::new(bytes);
    let mut out: Vec<(u32, Input)> = Vec::new();
    if !j.eat(b'[') {
        // an empty / absent array is a neutral tick.
        return Some(out);
    }
    loop {
        if j.eat(b']') {
            break;
        }
        let cmd = parse_one_command(&mut j)?;
        out.push(cmd);
        if !j.eat(b',') {
            j.eat(b']');
            break;
        }
    }
    Some(out)
}

fn parse_one_command(j: &mut Json) -> Option<(u32, Input)> {
    if !j.eat(b'{') {
        return None;
    }
    let mut id = 0u32;
    let mut inp = Input::default();
    loop {
        if j.eat(b'}') {
            break;
        }
        let key = j.string()?;
        j.eat(b':');
        match key.as_str() {
            "id" => id = j.int()? as u32,
            "move_forward" => inp.move_forward = j.int()? as i32,
            "move_strafe" => inp.move_strafe = j.int()? as i32,
            "turn" => inp.turn = j.int()? as i32,
            "fire" => inp.fire = j.bool()?,
            _ => j.skip_value(),
        }
        if !j.eat(b',') {
            j.eat(b'}');
            break;
        }
    }
    Some((id, inp))
}

// ─────────────────────────────────────────────────────────────────────────────
//  The exported ops.
// ─────────────────────────────────────────────────────────────────────────────

/// Build / replace the global match. See the module-level CONFIG JSON contract.
/// Returns 0 on success, -1 on a config-parse failure.
#[no_mangle]
pub unsafe extern "C" fn fps_new(seed: u64, config_ptr: u32, config_len: u32) -> i32 {
    let bytes = slice_in(config_ptr, config_len);
    match parse_config(bytes) {
        Some(cfg) => {
            let g = Game::with_frag_limit(seed, cfg.arena, &cfg.players, cfg.frag_limit);
            set_game(g);
            0
        }
        None => -1,
    }
}

/// Advance one tick with the supplied COMMANDS JSON. No-op without a live game.
#[no_mangle]
pub unsafe extern "C" fn fps_step(commands_ptr: u32, commands_len: u32) {
    let bytes = slice_in(commands_ptr, commands_len);
    if let Some(cmds) = parse_commands(bytes) {
        if let Some(g) = game_mut() {
            g.step(&cmds);
        }
    }
}

/// Packed (ptr<<32|len) of the current snapshot JSON (`Game::snapshot()`).
/// Returns 0 when there is no game.
#[no_mangle]
pub unsafe extern "C" fn fps_snapshot() -> u64 {
    match game_mut() {
        Some(g) => leak_packed(g.snapshot().into_bytes()),
        None => 0,
    }
}

/// 1 if the match has reached a terminal state, else 0.
#[no_mangle]
pub unsafe extern "C" fn fps_is_terminal() -> i32 {
    match game_mut() {
        Some(g) => {
            if g.is_over() {
                1
            } else {
                0
            }
        }
        None => 0,
    }
}

/// Packed (ptr<<32|len) of a small RESULT JSON synthesised from the core's public
/// status API. Returns 0 when there is no game.
#[no_mangle]
pub unsafe extern "C" fn fps_result() -> u64 {
    match game_mut() {
        Some(g) => {
            let terminal = g.is_over();
            let status = g.status().wire();
            let tick = g.tick_count();
            let mut s = String::new();
            s.push_str("{\"type\":\"fps.result\",\"terminal\":");
            s.push_str(if terminal { "true" } else { "false" });
            s.push_str(",\"status\":\"");
            s.push_str(&status);
            s.push_str("\",\"tick\":");
            push_u64(&mut s, tick);
            s.push('}');
            leak_packed(s.into_bytes())
        }
        None => 0,
    }
}

/// Reconstruct the global match from a REPLAY JSON `{config, log}` — the
/// command-log-as-truth restore the core actually supports. Returns 0 on success,
/// -1 on a parse failure.
#[no_mangle]
pub unsafe extern "C" fn fps_restore(replay_ptr: u32, replay_len: u32) -> i32 {
    let bytes = slice_in(replay_ptr, replay_len);
    match parse_replay(bytes) {
        Some((seed, cfg, log)) => {
            let log_refs: Vec<Vec<(u32, Input)>> = log;
            let g = Game::replay_with_frag_limit(
                seed,
                cfg.arena,
                &cfg.players,
                cfg.frag_limit,
                &log_refs,
            );
            set_game(g);
            0
        }
        None => -1,
    }
}

/// Parse a REPLAY JSON object `{"seed":N,"config":{...},"log":[ [cmds], ... ]}`.
fn parse_replay(bytes: &[u8]) -> Option<(u64, ParsedConfig, Vec<Vec<(u32, Input)>>)> {
    // We need the raw spans for the nested config + log because the lightweight
    // reader is single-pass; re-scan by locating each key's value span.
    let mut j = Json::new(bytes);
    if !j.eat(b'{') {
        return None;
    }
    let mut seed: u64 = 0;
    let mut cfg: Option<ParsedConfig> = None;
    let mut log: Vec<Vec<(u32, Input)>> = Vec::new();
    loop {
        if j.eat(b'}') {
            break;
        }
        let key = j.string()?;
        j.eat(b':');
        match key.as_str() {
            "seed" => seed = j.u64()?,
            "config" => {
                let span = capture_span(&mut j)?;
                cfg = Some(parse_config(span)?);
            }
            "log" => {
                if !j.eat(b'[') {
                    return None;
                }
                loop {
                    if j.eat(b']') {
                        break;
                    }
                    let span = capture_span(&mut j)?;
                    log.push(parse_commands(span)?);
                    if !j.eat(b',') {
                        j.eat(b']');
                        break;
                    }
                }
            }
            _ => j.skip_value(),
        }
        if !j.eat(b',') {
            j.eat(b'}');
            break;
        }
    }
    Some((seed, cfg?, log))
}

/// Return the byte span of the next JSON value (object/array) without copying,
/// advancing the reader past it.
fn capture_span<'a>(j: &mut Json<'a>) -> Option<&'a [u8]> {
    j.ws();
    let start = j.i;
    j.skip_value();
    Some(&j.b[start..j.i])
}

/// Append a u64 to a String without `format!` (keeps no_std lean).
fn push_u64(s: &mut String, mut v: u64) {
    if v == 0 {
        s.push('0');
        return;
    }
    let mut buf = [0u8; 20];
    let mut n = 0;
    while v > 0 {
        buf[n] = b'0' + (v % 10) as u8;
        v /= 10;
        n += 1;
    }
    for k in (0..n).rev() {
        s.push(buf[k] as char);
    }
}
