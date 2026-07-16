//! `ConnectFourEngine` — a GDExtension node that REUSES the deterministic
//! `snapdragon-connect-four` Rust core from inside Godot.
//!
//! This mirrors `chess_engine.rs` exactly: rendering, input and the
//! multiplayer relay live in Godot (scenes + the `HostBridge`), while ALL game
//! rules stay in the existing pure-Rust core. The Connect Four engine is the
//! single source of truth — the SAME crate the web-native bundle drives through
//! its wasm C-ABI shim (`extensions/snapdragon-connect-four-wasm`). Here we link
//! the same crate as an `rlib` and call its Rust API directly, so:
//!
//!   * native builds (editor F5 / `cargo test`) get the validated engine, and
//!   * the wasm32-unknown-emscripten bridge build that ships in the Web export
//!     gets the SAME engine compiled into `snapdragon_bridge.wasm`.
//!
//! GDScript NEVER reimplements Connect Four rules. It calls:
//!
//! ```gdscript
//! var engine := ConnectFourEngine.new()
//! engine.new_game()
//! var cols := engine.legal_moves()             # PackedInt32Array of columns
//! var outcome := engine.apply_drop(3)          # JSON string (connect_four.state | .error)
//! var board := engine.board_str()              # "......./…" 6 rows top→bottom
//! var status := engine.status()                # wire status string
//! ```
//!
//! The JSON shapes are byte-for-byte the payloads the web bundle and the BFF
//! relay over the session `world` channel
//! (`{"type":"connect_four.state","lastCol":..,"board":..,"status":..}`), so a
//! Godot guest re-applying a relayed drop is interchangeable with the existing
//! web guest.
//!
//! NOTE on re-sync: unlike chess (`load_fen`), the Connect Four core exposes no
//! `from_state` constructor — reconstructing a board would require reimplementing
//! rules, which this wrapper must not do. A guest therefore re-syncs by
//! RE-APPLYING the authoritative last drop verbatim (`apply_drop(lastCol)`) and
//! comparing `board_str()` to the relayed `board`. That is the exact
//! host-authoritative model the wave plan calls for; a dropped/reordered relay
//! that can't be caught up by one drop degrades to a visible "out of sync"
//! banner (the documented limitation of the simple cores), never a silent
//! divergence.

use godot::classes::{INode, Node};
use godot::prelude::*;

use snapdragon_connect_four::{DropOutcome, Game, GameStatus, MoveError, Player};

/// Wraps one `snapdragon_connect_four::Game` per node. Host-authoritative games
/// keep one engine per session on the host; a guest keeps its own copy and
/// re-applies relayed drops, re-syncing from the authoritative board on any
/// divergence — identical in spirit to the web-native Connect Four guest path.
#[derive(GodotClass)]
#[class(base=Node)]
pub struct ConnectFourEngine {
    base: Base<Node>,
    game: Game,
    /// The most recent successful drop, so `world_json()` can emit the same
    /// full-board snapshot the host relays (`lastPlayer`/`lastCol`/`lastRow`/
    /// `winningLine`). `None` before the first drop or right after `new_game`.
    last: Option<DropOutcome>,
}

#[godot_api]
impl INode for ConnectFourEngine {
    fn init(base: Base<Node>) -> Self {
        Self {
            base,
            game: Game::new(),
            last: None,
        }
    }
}

#[godot_api]
impl ConnectFourEngine {
    /// Reset to a fresh empty board (Red to move). Mirrors the core's `Game::new`.
    #[func]
    pub fn new_game(&mut self) {
        self.game = Game::new();
        self.last = None;
    }

    /// Drop a disc for the side to move into `col`. On success returns the
    /// canonical `connect_four.state` JSON (the full-board world snapshot the web
    /// bundle and BFF relay on the `world` channel):
    ///   `{"type":"connect_four.state","lastPlayer":"red","lastCol":3,"lastRow":0,
    ///     "toMove":"yellow","status":"ongoing","winningLine":[],"board":"…"}`
    /// On rejection returns
    ///   `{"type":"connect_four.error","error":"out-of-range"|"column-full"|"game-over"}`
    /// The engine — not the scene — decides legality, so the rules can never
    /// drift from the validated core.
    #[func]
    pub fn apply_drop(&mut self, col: i64) -> GString {
        let col = if col < 0 { usize::MAX } else { col as usize };
        match self.game.drop(col) {
            Ok(outcome) => {
                let json = self.game.to_json(Some(&outcome));
                self.last = Some(outcome);
                GString::from(json.as_str())
            }
            Err(e) => GString::from(error_json(&e).as_str()),
        }
    }

    /// The current authoritative `connect_four.state` snapshot (full board +
    /// status + the last drop, if any). This is what the host emits on `world`
    /// and what a fresh scene renders before any move. Mirrors chess's
    /// `board_fen()` role but carries the whole board like the web bundle relays.
    #[func]
    pub fn world_json(&self) -> GString {
        GString::from(self.game.to_json(self.last.as_ref()).as_str())
    }

    /// Compact board string: 6 rows top-to-bottom, 7 columns left-to-right,
    /// `.`=empty `R`=red `Y`=yellow, rows joined by `/`. The scene parses this to
    /// render, and compares it to a relayed `board` to detect divergence.
    #[func]
    pub fn board_str(&self) -> GString {
        GString::from(self.game.board_str().as_str())
    }

    /// Side to move: `"red"` or `"yellow"`. Freezes at the winner once terminal.
    #[func]
    pub fn side_to_move(&self) -> GString {
        GString::from(self.game.side_to_move().wire())
    }

    /// Compact wire status (`"ongoing"`, `"win:red"`, `"win:yellow"`, `"draw"`).
    #[func]
    pub fn status(&self) -> GString {
        GString::from(self.game.status().wire().as_str())
    }

    /// Whether the game has ended (a win or a full-board draw).
    #[func]
    pub fn is_terminal(&self) -> bool {
        self.game.status().is_terminal()
    }

    /// The winner colour (`"red"`/`"yellow"`) or `""` when the game is ongoing or
    /// drawn. Convenience for the scene's end-of-game label.
    #[func]
    pub fn winner(&self) -> GString {
        match self.game.status() {
            GameStatus::Win { winner } => GString::from(winner.wire()),
            _ => GString::from(""),
        }
    }

    /// Columns that can currently be dropped into (`0..7`), as ints. Empty once
    /// the game ends. The scene uses this to gate input and highlight targets —
    /// illegal clicks never leave the client.
    #[func]
    pub fn legal_moves(&self) -> PackedInt32Array {
        let mut out = PackedInt32Array::new();
        for c in self.game.legal_moves() {
            out.push(c as i32);
        }
        out
    }

    /// Is `col` a legal drop target right now? (in range, not full, game live)
    #[func]
    pub fn can_drop(&self, col: i64) -> bool {
        col >= 0 && self.game.can_drop(col as usize)
    }

    /// How many discs are stacked in `col` (0..=6) — i.e. the row the next disc
    /// would land in. Out-of-range columns read as 0.
    #[func]
    pub fn height(&self, col: i64) -> i64 {
        if col < 0 {
            0
        } else {
            self.game.height(col as usize) as i64
        }
    }

    /// The disc glyph at `(col, row)` — `"R"`, `"Y"`, or `""` for empty /
    /// out-of-range. `row = 0` is the BOTTOM of the column (gravity).
    #[func]
    pub fn cell(&self, col: i64, row: i64) -> GString {
        if col < 0 || row < 0 {
            return GString::from("");
        }
        match self.game.at(col as usize, row as usize) {
            Some(p) => GString::from(String::from(p.glyph()).as_str()),
            None => GString::from(""),
        }
    }

    /// Total discs played so far. Lets the scene reason about whose turn it is
    /// and whether a relayed board is exactly one move ahead.
    #[func]
    pub fn moves_played(&self) -> i64 {
        self.game.moves_played() as i64
    }
}

/// Map a core `MoveError` to the wire error JSON the scene checks for. Mirrors
/// chess's `error_json`; the `connect_four.error` type namespaces it per game.
fn error_json(e: &MoveError) -> String {
    let kind = match e {
        MoveError::OutOfRange => "out-of-range",
        MoveError::ColumnFull => "column-full",
        MoveError::GameOver => "game-over",
    };
    format!(r#"{{"type":"connect_four.error","error":"{kind}"}}"#)
}

#[cfg(test)]
mod tests {
    use super::*;

    // These exercise the reuse path (bridge -> core) with no Godot runtime:
    // they prove the linked core produces the same wire shapes the web bundle
    // relies on, so a Godot guest is interchangeable with the web guest. They
    // mirror `chess_engine::tests`.

    #[test]
    fn fresh_board_has_seven_legal_columns() {
        let g = Game::new();
        let cols: Vec<usize> = g.legal_moves();
        assert_eq!(cols, vec![0, 1, 2, 3, 4, 5, 6]);
        assert_eq!(g.side_to_move(), Player::Red);
    }

    #[test]
    fn apply_drop_returns_connect_four_state_json() {
        let mut g = Game::new();
        let o = g.drop(3).expect("col 3 is legal from an empty board");
        let json = g.to_json(Some(&o));
        assert!(json.contains("\"type\":\"connect_four.state\""), "{json}");
        assert!(json.contains("\"lastPlayer\":\"red\""), "{json}");
        assert!(json.contains("\"lastCol\":3"), "{json}");
        assert!(json.contains("\"lastRow\":0"), "{json}");
        assert!(json.contains("\"toMove\":\"yellow\""), "{json}");
        assert!(json.contains("\"status\":\"ongoing\""), "{json}");
    }

    #[test]
    fn out_of_range_drop_is_rejected_by_core() {
        let mut g = Game::new();
        let err = g.drop(7).expect_err("col 7 is out of range on a 7-wide board");
        assert_eq!(
            error_json(&err),
            r#"{"type":"connect_four.error","error":"out-of-range"}"#
        );
        // a rejected drop must not consume the turn
        assert_eq!(g.side_to_move(), Player::Red);
    }

    #[test]
    fn vertical_win_reports_winning_line_in_state_json() {
        // Red stacks col 0 four high; Yellow answers harmlessly in col 1.
        let mut g = Game::new();
        for &c in &[0, 1, 0, 1, 0, 1] {
            g.drop(c).unwrap();
        }
        let win = g.drop(0).unwrap();
        assert_eq!(win.status, GameStatus::Win { winner: Player::Red });
        let json = g.to_json(Some(&win));
        assert!(json.contains("\"status\":\"win:red\""), "{json}");
        assert!(
            json.contains("\"winningLine\":[[0,0],[0,1],[0,2],[0,3]]"),
            "{json}"
        );
    }

    #[test]
    fn board_str_and_error_mapping_cover_full_column() {
        let mut g = Game::new();
        // Fill column 0 to the brim (6 discs), no one wins elsewhere.
        for _ in 0..6 {
            g.drop(0).unwrap();
        }
        // Bottom row (last segment) has a disc in col 0; top row (first) too.
        let b = g.board_str();
        let rows: Vec<&str> = b.split('/').collect();
        assert_eq!(rows.len(), 6);
        assert!(rows[5].starts_with('R') || rows[5].starts_with('Y'), "{b}");
        // A 7th drop into the full column maps to the column-full wire error.
        let err = g.drop(0).expect_err("column 0 is full");
        assert_eq!(
            error_json(&err),
            r#"{"type":"connect_four.error","error":"column-full"}"#
        );
    }
}
