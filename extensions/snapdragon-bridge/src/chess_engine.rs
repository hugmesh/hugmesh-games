//! `ChessEngine` — a GDExtension node that REUSES the deterministic
//! `snapdragon-chess` Rust core from inside Godot.
//!
//! This is the canonical pattern for the Godot pivot: the game's rendering,
//! input and multiplayer relay live in Godot (scenes + the `HostBridge`),
//! while ALL game logic stays in the existing pure-Rust core. The chess rules
//! engine is the single source of truth — exactly the crate the web-native
//! bundle drives through its wasm C-ABI shim
//! (`extensions/snapdragon-chess-wasm`). Here we link the same crate as an
//! `rlib` and call its Rust API directly, so:
//!
//!   * native builds (editor F5 / `cargo test`) get the validated engine, and
//!   * the wasm32-unknown-emscripten bridge build that ships in the Web export
//!     gets the SAME engine compiled into `snapdragon_bridge.wasm`.
//!
//! GDScript NEVER reimplements chess rules. It calls:
//!
//! ```gdscript
//! var engine := ChessEngine.new()
//! engine.new_game()
//! var moves := engine.legal_moves()          # PackedStringArray of UCI
//! var outcome := engine.apply_uci("e2e4")     # JSON string (chess.state | chess.error)
//! var fen := engine.board_fen()               # current FEN
//! var status := engine.status()               # wire status string
//! engine.load_fen(fen)                        # re-sync from an authoritative world event
//! ```
//!
//! The JSON shapes are byte-for-byte the same payloads the web bundle and the
//! BFF relay over the session `world` channel
//! (`{"type":"chess.state","lastMove":..,"san":..,"fen":..,"status":..}`),
//! so a Godot guest re-validating a relayed move is interchangeable with the
//! existing web guest.

use godot::classes::{INode, Node};
use godot::prelude::*;

use snapdragon_chess::{square_str, Color, Game, Move, MoveError, PieceKind};

/// Wraps one `snapdragon_chess::Game` per node. Host-authoritative games keep
/// one engine per session on the host; a guest keeps its own copy and
/// re-validates relayed moves, re-syncing from the authoritative FEN on any
/// divergence — identical to the web-native chess guest path.
#[derive(GodotClass)]
#[class(base=Node)]
pub struct ChessEngine {
    base: Base<Node>,
    game: Game,
}

#[godot_api]
impl INode for ChessEngine {
    fn init(base: Base<Node>) -> Self {
        Self {
            base,
            game: Game::new(),
        }
    }
}

#[godot_api]
impl ChessEngine {
    /// Reset to the standard start position. Mirrors the core's `chess_new`.
    #[func]
    pub fn new_game(&mut self) {
        self.game = Game::new();
    }

    /// Replace the current position from a FEN string. Returns `true` on
    /// success; on a bad FEN the existing game is left unchanged and `false`
    /// is returned (matching `chess_from_fen`). Used by a guest to re-sync
    /// from an authoritative `world` event.
    #[func]
    pub fn load_fen(&mut self, fen: GString) -> bool {
        match Game::from_fen(&fen.to_string()) {
            Ok(g) => {
                self.game = g;
                true
            }
            Err(_) => false,
        }
    }

    /// Current position as FEN.
    #[func]
    pub fn board_fen(&self) -> GString {
        GString::from(self.game.fen().as_str())
    }

    /// Side to move: `"white"` or `"black"`.
    #[func]
    pub fn side_to_move(&self) -> GString {
        GString::from(color_wire(self.game.side_to_move()))
    }

    /// Compact wire status (`"ongoing"`, `"check"`, `"checkmate:white"`,
    /// `"stalemate"`, `"draw:threefold"`, `"resigned:black"`, …).
    #[func]
    pub fn status(&self) -> GString {
        GString::from(self.game.status().wire().as_str())
    }

    /// Whether the game has ended (checkmate / stalemate / draw / resignation).
    #[func]
    pub fn is_terminal(&self) -> bool {
        self.game.status().is_terminal()
    }

    /// Legal moves for the side to move, as UCI strings
    /// (`"e2e4"`, `"e7e8q"`, …). The scene uses this to highlight targets and
    /// to gate input — illegal clicks never leave the client.
    #[func]
    pub fn legal_moves(&self) -> PackedStringArray {
        let mut out = PackedStringArray::new();
        for m in self.game.legal_moves() {
            out.push(&GString::from(uci_of(&m).as_str()));
        }
        out
    }

    /// Legal destination squares for a given origin square (e.g. `"e2"`),
    /// as UCI strings. Convenience for "click a piece, show its moves".
    #[func]
    pub fn legal_moves_from(&self, from: GString) -> PackedStringArray {
        let from = from.to_string();
        let mut out = PackedStringArray::new();
        for m in self.game.legal_moves() {
            if square_str(m.from) == from {
                out.push(&GString::from(uci_of(&m).as_str()));
            }
        }
        out
    }

    /// Apply a UCI move. On success returns the canonical `chess.state` JSON
    /// (same shape the web bundle and BFF relay on the `world` channel):
    ///   `{"type":"chess.state","lastMove":"e2e4","san":"e4","fen":"…",
    ///     "status":"ongoing","check":false,"mate":false}`
    /// On rejection returns
    ///   `{"type":"chess.error","error":"illegal-move"|"bad-uci"|"game-over"}`
    /// The engine — not the scene — decides legality, so the rules can never
    /// drift from the validated core.
    #[func]
    pub fn apply_uci(&mut self, uci: GString) -> GString {
        match self.game.apply_uci(&uci.to_string()) {
            Ok(outcome) => GString::from(outcome.to_json().as_str()),
            Err(e) => GString::from(error_json(&e).as_str()),
        }
    }

    /// Resign the named colour (`"white"`/`"black"`/`"w"`/`"b"`). Returns the
    /// `chess.state` JSON with the resulting `resigned:*` status.
    #[func]
    pub fn resign(&mut self, who: GString) -> GString {
        let w = match who.to_string().to_lowercase().as_str() {
            "white" | "w" => Color::White,
            "black" | "b" => Color::Black,
            _ => return GString::from(r#"{"type":"chess.error","error":"bad-color"}"#),
        };
        GString::from(self.game.resign(w).to_json().as_str())
    }
}

fn color_wire(c: Color) -> &'static str {
    if c == Color::White {
        "white"
    } else {
        "black"
    }
}

fn promo_letter(k: PieceKind) -> Option<char> {
    match k {
        PieceKind::Queen => Some('q'),
        PieceKind::Rook => Some('r'),
        PieceKind::Bishop => Some('b'),
        PieceKind::Knight => Some('n'),
        _ => None,
    }
}

/// UCI encoding of a core `Move` (e.g. 12,28 -> "e2e4"; with promotion "e7e8q").
fn uci_of(m: &Move) -> String {
    let mut s = format!("{}{}", square_str(m.from), square_str(m.to));
    if let Some(p) = m.promotion {
        if let Some(c) = promo_letter(p) {
            s.push(c);
        }
    }
    s
}

fn error_json(e: &MoveError) -> String {
    let kind = match e {
        MoveError::BadUci => "bad-uci",
        MoveError::IllegalMove => "illegal-move",
        MoveError::GameOver => "game-over",
    };
    format!(r#"{{"type":"chess.error","error":"{kind}"}}"#)
}

#[cfg(test)]
mod tests {
    use super::*;

    // These exercise the reuse path (bridge -> core) with no Godot runtime:
    // they prove the linked core produces the same wire shapes the web bundle
    // relies on, so a Godot guest is interchangeable with the web guest.
    #[test]
    fn uci_encoding_roundtrips_through_core() {
        let g = Game::new();
        let uci: Vec<String> = g.legal_moves().iter().map(uci_of).collect();
        assert_eq!(uci.len(), 20, "start position has 20 legal moves");
        assert!(uci.contains(&"e2e4".to_string()));
        assert!(uci.contains(&"g1f3".to_string()));
    }

    #[test]
    fn apply_uci_returns_chess_state_json() {
        let mut g = Game::new();
        let out = g.apply_uci("e2e4").expect("e2e4 is legal");
        let json = out.to_json();
        assert!(json.contains("\"type\":\"chess.state\""));
        assert!(json.contains("\"lastMove\":\"e2e4\""));
        assert!(json.contains("\"status\":\"ongoing\""));
    }

    #[test]
    fn illegal_move_is_rejected_by_core() {
        let mut g = Game::new();
        let err = g.apply_uci("e2e5").expect_err("e2e5 is illegal from start");
        assert_eq!(error_json(&err), r#"{"type":"chess.error","error":"illegal-move"}"#);
    }

    #[test]
    fn promotion_uci_encodes_suffix() {
        // White pawn one step from promotion, black king parked away.
        let g = Game::from_fen("8/P7/8/8/8/8/8/k6K w - - 0 1").expect("valid fen");
        let uci: Vec<String> = g.legal_moves().iter().map(uci_of).collect();
        assert!(uci.contains(&"a7a8q".to_string()), "got {uci:?}");
    }
}
