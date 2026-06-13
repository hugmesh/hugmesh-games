//! `snapdragon-chess` — a pure, dependency-free chess rules engine.
//!
//! Square indexing: `0 = a1`, `file = sq % 8`, `rank = sq / 8` (rank 0 == rank 1,
//! white's back rank). White advances toward higher ranks.
//!
//! This is the ONE engine: it compiles to `wasm32` for the in-browser game (host
//! authoritative, guest re-validates) and natively for `cargo test` + the gnuchess
//! differential oracle. The server runs none of it — it only streams events.

#![allow(clippy::needless_range_loop)]

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Color { White, Black }
impl Color {
    pub fn opposite(self) -> Color { if self == Color::White { Color::Black } else { Color::White } }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PieceKind { Pawn, Knight, Bishop, Rook, Queen, King }

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Piece { pub color: Color, pub kind: PieceKind }
impl Piece {
    fn fen_char(self) -> char {
        let c = match self.kind {
            PieceKind::Pawn => 'p', PieceKind::Knight => 'n', PieceKind::Bishop => 'b',
            PieceKind::Rook => 'r', PieceKind::Queen => 'q', PieceKind::King => 'k',
        };
        if self.color == Color::White { c.to_ascii_uppercase() } else { c }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Move { pub from: u8, pub to: u8, pub promotion: Option<PieceKind> }

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum GameStatus {
    Ongoing,
    Check,
    Checkmate { winner: Color },
    Stalemate,
    DrawFiftyMove,
    DrawThreefold,
    DrawInsufficientMaterial,
    Resigned { winner: Color },
}
impl GameStatus {
    pub fn is_terminal(self) -> bool {
        !matches!(self, GameStatus::Ongoing | GameStatus::Check)
    }
    /// Compact wire encoding for the `world`-channel payload.
    pub fn wire(self) -> String {
        match self {
            GameStatus::Ongoing => "ongoing".into(),
            GameStatus::Check => "check".into(),
            GameStatus::Checkmate { winner } => format!("checkmate:{}", color_wire(winner)),
            GameStatus::Stalemate => "stalemate".into(),
            GameStatus::DrawFiftyMove => "draw:fifty".into(),
            GameStatus::DrawThreefold => "draw:threefold".into(),
            GameStatus::DrawInsufficientMaterial => "draw:material".into(),
            GameStatus::Resigned { winner } => format!("resigned:{}", color_wire(winner)),
        }
    }
}

fn color_wire(c: Color) -> &'static str { if c == Color::White { "white" } else { "black" } }

#[derive(Debug, Clone, PartialEq)]
pub struct MoveOutcome {
    pub from: u8,
    pub to: u8,
    pub piece: PieceKind,
    pub capture: bool,
    pub is_castle: bool,
    pub is_en_passant: bool,
    pub promotion: Option<PieceKind>,
    pub gives_check: bool,
    pub is_mate: bool,
    pub san: String,
    pub fen: String,
    pub status: GameStatus,
}

impl MoveOutcome {
    /// The JSON payload the host emits on the session `world` channel after a
    /// move; the guest re-validates it against its own engine copy. The server
    /// only relays this string — it never parses it.
    pub fn to_json(&self) -> String {
        let last = if self.from < 64 {
            let mut s = format!("{}{}", square_str(self.from), square_str(self.to));
            if let Some(k) = self.promotion { s.push(promo_letter(k).to_ascii_lowercase()); }
            s
        } else { String::new() };
        format!(
            "{{\"type\":\"chess.state\",\"lastMove\":\"{}\",\"san\":\"{}\",\"fen\":\"{}\",\"status\":\"{}\",\"check\":{},\"mate\":{}}}",
            last, json_escape(&self.san), json_escape(&self.fen), self.status.wire(),
            self.gives_check, self.is_mate
        )
    }
}

fn json_escape(s: &str) -> String { s.replace('\\', "\\\\").replace('"', "\\\"") }

#[derive(Debug, PartialEq, Eq)]
pub enum MoveError { BadUci, IllegalMove, GameOver }

// ── square helpers ───────────────────────────────────────────────────────────
fn file(sq: u8) -> i32 { (sq % 8) as i32 }
fn rank(sq: u8) -> i32 { (sq / 8) as i32 }
fn sqi(f: i32, r: i32) -> u8 { (r * 8 + f) as u8 }
fn on_board(f: i32, r: i32) -> bool { (0..8).contains(&f) && (0..8).contains(&r) }

pub fn parse_square(s: &str) -> Option<u8> {
    let b = s.as_bytes();
    if b.len() != 2 { return None; }
    let f = b[0].wrapping_sub(b'a') as i32;
    let r = b[1].wrapping_sub(b'1') as i32;
    if on_board(f, r) { Some(sqi(f, r)) } else { None }
}
pub fn square_str(sq: u8) -> String {
    let f = (b'a' + (sq % 8)) as char;
    let r = (b'1' + (sq / 8)) as char;
    format!("{f}{r}")
}
fn promo_from_char(c: char) -> Option<PieceKind> {
    match c { 'q' => Some(PieceKind::Queen), 'r' => Some(PieceKind::Rook),
              'b' => Some(PieceKind::Bishop), 'n' => Some(PieceKind::Knight), _ => None }
}
fn promo_letter(k: PieceKind) -> char {
    match k { PieceKind::Queen => 'Q', PieceKind::Rook => 'R',
              PieceKind::Bishop => 'B', PieceKind::Knight => 'N', _ => '?' }
}

// ── the game ─────────────────────────────────────────────────────────────────
// castling index: 0 = White-kingside, 1 = White-queenside, 2 = Black-kingside, 3 = Black-queenside
#[derive(Clone)]
pub struct Game {
    board: [Option<Piece>; 64],
    side: Color,
    castling: [bool; 4],
    ep: Option<u8>,
    halfmove: u32,
    fullmove: u32,
    history: Vec<String>,
    resigned: Option<Color>, // winner of a resignation
}

impl Default for Game { fn default() -> Self { Game::new() } }

impl Game {
    pub fn new() -> Game {
        Game::from_fen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1").unwrap()
    }

    pub fn from_fen(fen: &str) -> Result<Game, String> {
        let parts: Vec<&str> = fen.split_whitespace().collect();
        if parts.len() < 4 { return Err("FEN needs >=4 fields".into()); }
        let mut board: [Option<Piece>; 64] = [None; 64];
        let rows: Vec<&str> = parts[0].split('/').collect();
        if rows.len() != 8 { return Err("FEN board needs 8 ranks".into()); }
        for (i, row) in rows.iter().enumerate() {
            let r = 7 - i as i32; // FEN lists rank 8 first
            let mut f = 0i32;
            for ch in row.chars() {
                if let Some(d) = ch.to_digit(10) { f += d as i32; continue; }
                let color = if ch.is_ascii_uppercase() { Color::White } else { Color::Black };
                let kind = match ch.to_ascii_lowercase() {
                    'p' => PieceKind::Pawn, 'n' => PieceKind::Knight, 'b' => PieceKind::Bishop,
                    'r' => PieceKind::Rook, 'q' => PieceKind::Queen, 'k' => PieceKind::King,
                    other => return Err(format!("bad piece '{other}'")),
                };
                if !on_board(f, r) { return Err("FEN row too long".into()); }
                board[sqi(f, r) as usize] = Some(Piece { color, kind });
                f += 1;
            }
        }
        let side = if parts[1] == "w" { Color::White } else { Color::Black };
        let cr = parts[2];
        let castling = [cr.contains('K'), cr.contains('Q'), cr.contains('k'), cr.contains('q')];
        let ep = if parts[3] == "-" { None } else { parse_square(parts[3]) };
        let halfmove = parts.get(4).and_then(|s| s.parse().ok()).unwrap_or(0);
        let fullmove = parts.get(5).and_then(|s| s.parse().ok()).unwrap_or(1);
        let mut g = Game { board, side, castling, ep, halfmove, fullmove, history: Vec::new(), resigned: None };
        g.history.push(g.position_key());
        Ok(g)
    }

    pub fn side_to_move(&self) -> Color { self.side }
    pub fn board(&self) -> [Option<Piece>; 64] { self.board }
    fn at(&self, sq: u8) -> Option<Piece> { self.board[sq as usize] }

    fn position_key(&self) -> String {
        let mut s = String::with_capacity(72);
        for sq in 0..64 { s.push(self.board[sq].map(|p| p.fen_char()).unwrap_or('.')); }
        s.push(if self.side == Color::White { 'w' } else { 'b' });
        for (i, c) in ['K', 'Q', 'k', 'q'].iter().enumerate() {
            s.push(if self.castling[i] { *c } else { '-' });
        }
        s.push_str(&self.ep.map(square_str).unwrap_or_else(|| "-".into()));
        s
    }

    pub fn fen(&self) -> String {
        let mut out = String::new();
        for i in 0..8 {
            let r = 7 - i as i32;
            let mut empty = 0;
            for f in 0..8 {
                match self.board[sqi(f, r) as usize] {
                    None => empty += 1,
                    Some(p) => {
                        if empty > 0 { out.push_str(&empty.to_string()); empty = 0; }
                        out.push(p.fen_char());
                    }
                }
            }
            if empty > 0 { out.push_str(&empty.to_string()); }
            if i < 7 { out.push('/'); }
        }
        out.push(' ');
        out.push(if self.side == Color::White { 'w' } else { 'b' });
        out.push(' ');
        let mut cr = String::new();
        for (i, c) in ['K', 'Q', 'k', 'q'].iter().enumerate() { if self.castling[i] { cr.push(*c); } }
        out.push_str(if cr.is_empty() { "-" } else { &cr });
        out.push(' ');
        out.push_str(&self.ep.map(square_str).unwrap_or_else(|| "-".into()));
        out.push_str(&format!(" {} {}", self.halfmove, self.fullmove));
        out
    }

    fn king_sq(&self, color: Color) -> u8 {
        for sq in 0..64u8 {
            if let Some(p) = self.at(sq) { if p.color == color && p.kind == PieceKind::King { return sq; } }
        }
        64 // no king (shouldn't happen in legal positions)
    }

    /// Is `sq` attacked by any piece of color `by`?
    fn is_attacked(&self, sq: u8, by: Color) -> bool {
        if sq >= 64 { return false; }
        let (f, r) = (file(sq), rank(sq));
        // pawns: a `by` pawn attacks diagonally in its advance direction
        let pr = if by == Color::White { -1 } else { 1 };
        for df in [-1, 1] {
            let (af, ar) = (f + df, r + pr);
            if on_board(af, ar) {
                if let Some(p) = self.at(sqi(af, ar)) {
                    if p.color == by && p.kind == PieceKind::Pawn { return true; }
                }
            }
        }
        // knights
        for (df, dr) in [(1, 2), (2, 1), (-1, 2), (-2, 1), (1, -2), (2, -1), (-1, -2), (-2, -1)] {
            let (af, ar) = (f + df, r + dr);
            if on_board(af, ar) {
                if let Some(p) = self.at(sqi(af, ar)) {
                    if p.color == by && p.kind == PieceKind::Knight { return true; }
                }
            }
        }
        // king
        for df in -1..=1 { for dr in -1..=1 {
            if df == 0 && dr == 0 { continue; }
            let (af, ar) = (f + df, r + dr);
            if on_board(af, ar) {
                if let Some(p) = self.at(sqi(af, ar)) {
                    if p.color == by && p.kind == PieceKind::King { return true; }
                }
            }
        }}
        // sliding: bishops/queens
        for (df, dr) in [(1, 1), (1, -1), (-1, 1), (-1, -1)] {
            let (mut af, mut ar) = (f + df, r + dr);
            while on_board(af, ar) {
                if let Some(p) = self.at(sqi(af, ar)) {
                    if p.color == by && (p.kind == PieceKind::Bishop || p.kind == PieceKind::Queen) { return true; }
                    break;
                }
                af += df; ar += dr;
            }
        }
        // sliding: rooks/queens
        for (df, dr) in [(1, 0), (-1, 0), (0, 1), (0, -1)] {
            let (mut af, mut ar) = (f + df, r + dr);
            while on_board(af, ar) {
                if let Some(p) = self.at(sqi(af, ar)) {
                    if p.color == by && (p.kind == PieceKind::Rook || p.kind == PieceKind::Queen) { return true; }
                    break;
                }
                af += df; ar += dr;
            }
        }
        false
    }

    pub fn in_check(&self, color: Color) -> bool {
        self.is_attacked(self.king_sq(color), color.opposite())
    }

    fn push_pawn_moves(&self, from: u8, to: u8, out: &mut Vec<Move>) {
        let last = if self.side == Color::White { 7 } else { 0 };
        if rank(to) == last {
            for k in [PieceKind::Queen, PieceKind::Rook, PieceKind::Bishop, PieceKind::Knight] {
                out.push(Move { from, to, promotion: Some(k) });
            }
        } else {
            out.push(Move { from, to, promotion: None });
        }
    }

    fn pseudo_legal(&self) -> Vec<Move> {
        let mut out = Vec::new();
        let me = self.side;
        for sq in 0..64u8 {
            let p = match self.at(sq) { Some(p) if p.color == me => p, _ => continue };
            let (f, r) = (file(sq), rank(sq));
            match p.kind {
                PieceKind::Pawn => {
                    let dir = if me == Color::White { 1 } else { -1 };
                    let start = if me == Color::White { 1 } else { 6 };
                    // single push
                    let one = sqi(f, r + dir);
                    if on_board(f, r + dir) && self.at(one).is_none() {
                        self.push_pawn_moves(sq, one, &mut out);
                        // double push
                        if r == start {
                            let two = sqi(f, r + 2 * dir);
                            if self.at(two).is_none() { out.push(Move { from: sq, to: two, promotion: None }); }
                        }
                    }
                    // captures (incl. en passant)
                    for df in [-1, 1] {
                        let (cf, cr) = (f + df, r + dir);
                        if !on_board(cf, cr) { continue; }
                        let tsq = sqi(cf, cr);
                        let enemy = matches!(self.at(tsq), Some(t) if t.color != me);
                        if enemy || Some(tsq) == self.ep {
                            self.push_pawn_moves(sq, tsq, &mut out);
                        }
                    }
                }
                PieceKind::Knight => {
                    for (df, dr) in [(1, 2), (2, 1), (-1, 2), (-2, 1), (1, -2), (2, -1), (-1, -2), (-2, -1)] {
                        let (af, ar) = (f + df, r + dr);
                        if on_board(af, ar) && !matches!(self.at(sqi(af, ar)), Some(t) if t.color == me) {
                            out.push(Move { from: sq, to: sqi(af, ar), promotion: None });
                        }
                    }
                }
                PieceKind::King => {
                    for df in -1..=1 { for dr in -1..=1 {
                        if df == 0 && dr == 0 { continue; }
                        let (af, ar) = (f + df, r + dr);
                        if on_board(af, ar) && !matches!(self.at(sqi(af, ar)), Some(t) if t.color == me) {
                            out.push(Move { from: sq, to: sqi(af, ar), promotion: None });
                        }
                    }}
                    self.gen_castles(&mut out);
                }
                PieceKind::Bishop | PieceKind::Rook | PieceKind::Queen => {
                    let dirs: &[(i32, i32)] = match p.kind {
                        PieceKind::Bishop => &[(1, 1), (1, -1), (-1, 1), (-1, -1)],
                        PieceKind::Rook => &[(1, 0), (-1, 0), (0, 1), (0, -1)],
                        _ => &[(1, 1), (1, -1), (-1, 1), (-1, -1), (1, 0), (-1, 0), (0, 1), (0, -1)],
                    };
                    for &(df, dr) in dirs {
                        let (mut af, mut ar) = (f + df, r + dr);
                        while on_board(af, ar) {
                            let tsq = sqi(af, ar);
                            match self.at(tsq) {
                                None => out.push(Move { from: sq, to: tsq, promotion: None }),
                                Some(t) => { if t.color != me { out.push(Move { from: sq, to: tsq, promotion: None }); } break; }
                            }
                            af += df; ar += dr;
                        }
                    }
                }
            }
        }
        out
    }

    fn is_piece(&self, sq: u8, color: Color, kind: PieceKind) -> bool {
        matches!(self.at(sq), Some(p) if p.color == color && p.kind == kind)
    }

    fn gen_castles(&self, out: &mut Vec<Move>) {
        let me = self.side;
        let opp = me.opposite();
        let (home, k_idx, q_idx) = if me == Color::White { (0u8, 0, 1) } else { (56u8, 2, 3) };
        let king = home + 4; // e1 / e8
        if !self.is_piece(king, me, PieceKind::King) { return; }
        if self.in_check(me) { return; }
        // kingside: f,g empty; e,f,g not attacked; rook on h
        if self.castling[k_idx]
            && self.at(home + 5).is_none() && self.at(home + 6).is_none()
            && self.is_piece(home + 7, me, PieceKind::Rook)
            && !self.is_attacked(home + 5, opp) && !self.is_attacked(home + 6, opp) {
            out.push(Move { from: king, to: home + 6, promotion: None });
        }
        // queenside: b,c,d empty; e,d,c not attacked; rook on a
        if self.castling[q_idx]
            && self.at(home + 1).is_none() && self.at(home + 2).is_none() && self.at(home + 3).is_none()
            && self.is_piece(home, me, PieceKind::Rook)
            && !self.is_attacked(home + 3, opp) && !self.is_attacked(home + 2, opp) {
            out.push(Move { from: king, to: home + 2, promotion: None });
        }
    }

    pub fn legal_moves(&self) -> Vec<Move> {
        let me = self.side;
        self.pseudo_legal().into_iter().filter(|m| {
            let mut g = self.clone();
            g.make(m);
            !g.is_attacked(g.king_sq(me), me.opposite())
        }).collect()
    }

    /// perft: count leaf nodes at `depth`. The standard move-generation
    /// correctness check — the exact known counts catch any bug in castling,
    /// en passant, promotion, or check handling.
    pub fn perft(&self, depth: u32) -> u64 {
        if depth == 0 { return 1; }
        let moves = self.legal_moves();
        if depth == 1 { return moves.len() as u64; }
        let mut nodes = 0;
        for m in moves {
            let mut g = self.clone();
            g.make(&m);
            nodes += g.perft(depth - 1);
        }
        nodes
    }

    /// Apply a move that is assumed pseudo-legal. Advances the whole state.
    fn make(&mut self, m: &Move) {
        let mover = self.board[m.from as usize].take().expect("move from empty square");
        let is_pawn = mover.kind == PieceKind::Pawn;
        let mut is_capture = self.board[m.to as usize].is_some();
        // en passant capture
        if is_pawn && Some(m.to) == self.ep && self.board[m.to as usize].is_none() {
            is_capture = true;
            let cap = if mover.color == Color::White { m.to - 8 } else { m.to + 8 };
            self.board[cap as usize] = None;
        }
        // place the piece (promotion swaps the kind)
        let placed = match m.promotion { Some(k) => Piece { color: mover.color, kind: k }, None => mover };
        self.board[m.to as usize] = Some(placed);
        // castling: move the rook
        if mover.kind == PieceKind::King && (file(m.from) - file(m.to)).abs() == 2 {
            let rr = rank(m.from);
            if file(m.to) == 6 { // kingside
                self.board[sqi(5, rr) as usize] = self.board[sqi(7, rr) as usize].take();
            } else if file(m.to) == 2 { // queenside
                self.board[sqi(3, rr) as usize] = self.board[sqi(0, rr) as usize].take();
            }
        }
        // new en-passant target
        self.ep = if is_pawn && (rank(m.to) - rank(m.from)).abs() == 2 {
            Some(((m.from as i32 + m.to as i32) / 2) as u8)
        } else { None };
        // castling rights
        if mover.kind == PieceKind::King {
            if mover.color == Color::White { self.castling[0] = false; self.castling[1] = false; }
            else { self.castling[2] = false; self.castling[3] = false; }
        }
        for &s in &[m.from, m.to] {
            match s { 0 => self.castling[1] = false, 7 => self.castling[0] = false,
                      56 => self.castling[3] = false, 63 => self.castling[2] = false, _ => {} }
        }
        // clocks + side
        if is_pawn || is_capture { self.halfmove = 0; } else { self.halfmove += 1; }
        if self.side == Color::Black { self.fullmove += 1; }
        self.side = self.side.opposite();
        // NB: history (for threefold) is pushed by the public move path
        // (apply_uci), NOT here — so legality/perft trial-makes stay cheap.
    }

    fn insufficient_material(&self) -> bool {
        let mut knights = 0;
        let mut bishops_light = 0;
        let mut bishops_dark = 0;
        for sq in 0..64u8 {
            if let Some(p) = self.at(sq) {
                match p.kind {
                    PieceKind::King => {}
                    PieceKind::Knight => knights += 1,
                    PieceKind::Bishop => {
                        if (file(sq) + rank(sq)) % 2 == 0 { bishops_dark += 1; } else { bishops_light += 1; }
                    }
                    _ => return false, // pawn / rook / queen => sufficient
                }
            }
        }
        let minors = knights + bishops_light + bishops_dark;
        if minors <= 1 { return true; } // KvK, or K + single minor vs K
        // only bishops, all on one color complex => cannot mate
        knights == 0 && (bishops_light == 0 || bishops_dark == 0)
    }

    fn threefold(&self) -> bool {
        if let Some(cur) = self.history.last() {
            self.history.iter().filter(|k| *k == cur).count() >= 3
        } else { false }
    }

    pub fn status(&self) -> GameStatus {
        if let Some(w) = self.resigned { return GameStatus::Resigned { winner: w }; }
        let moves = self.legal_moves();
        if moves.is_empty() {
            return if self.in_check(self.side) { GameStatus::Checkmate { winner: self.side.opposite() } }
                   else { GameStatus::Stalemate };
        }
        if self.insufficient_material() { return GameStatus::DrawInsufficientMaterial; }
        if self.halfmove >= 100 { return GameStatus::DrawFiftyMove; }
        if self.threefold() { return GameStatus::DrawThreefold; }
        if self.in_check(self.side) { GameStatus::Check } else { GameStatus::Ongoing }
    }

    pub fn resign(&mut self, who: Color) -> MoveOutcome {
        self.resigned = Some(who.opposite());
        MoveOutcome {
            from: 64, to: 64, piece: PieceKind::King, capture: false, is_castle: false,
            is_en_passant: false, promotion: None, gives_check: false, is_mate: false,
            san: format!("resign {who:?}"), fen: self.fen(),
            status: GameStatus::Resigned { winner: who.opposite() },
        }
    }

    /// Apply a UCI move ("e2e4", "e7e8q", "e1g1"). Enforces turn ownership +
    /// legality; returns the structured outcome (board state, status, flags).
    pub fn apply_uci(&mut self, uci: &str) -> Result<MoveOutcome, MoveError> {
        if self.status().is_terminal() { return Err(MoveError::GameOver); }
        if uci.len() < 4 { return Err(MoveError::BadUci); }
        let from = parse_square(&uci[0..2]).ok_or(MoveError::BadUci)?;
        let to = parse_square(&uci[2..4]).ok_or(MoveError::BadUci)?;
        let promo = uci.chars().nth(4).and_then(promo_from_char);
        let chosen = self.legal_moves().into_iter()
            .find(|m| m.from == from && m.to == to && m.promotion == promo)
            .ok_or(MoveError::IllegalMove)?;
        // capture/castle/ep flags from the pre-move state
        let mover = self.at(from).unwrap();
        let is_castle = mover.kind == PieceKind::King && (file(from) - file(to)).abs() == 2;
        let was_occupied = self.at(to).is_some();
        let is_ep = mover.kind == PieceKind::Pawn && Some(to) == self.ep && !was_occupied;
        let capture = was_occupied || is_ep;
        self.make(&chosen);
        self.history.push(self.position_key()); // record for threefold detection
        let gives_check = self.is_attacked(self.king_sq(self.side), self.side.opposite());
        let status = self.status();
        let is_mate = matches!(status, GameStatus::Checkmate { .. });
        let san = build_san(mover.kind, from, to, capture, is_castle, promo, gives_check, is_mate);
        Ok(MoveOutcome { from, to, piece: mover.kind, capture, is_castle, is_en_passant: is_ep,
                         promotion: promo, gives_check, is_mate, san, fen: self.fen(), status })
    }
}

fn build_san(kind: PieceKind, from: u8, to: u8, capture: bool, castle: bool,
             promo: Option<PieceKind>, check: bool, mate: bool) -> String {
    let mut s = String::new();
    if castle {
        s.push_str(if file(to) == 6 { "O-O" } else { "O-O-O" });
    } else {
        match kind {
            PieceKind::Pawn => { if capture { s.push((b'a' + (from % 8)) as char); } }
            PieceKind::Knight => s.push('N'), PieceKind::Bishop => s.push('B'),
            PieceKind::Rook => s.push('R'), PieceKind::Queen => s.push('Q'), PieceKind::King => s.push('K'),
        }
        if capture { s.push('x'); }
        s.push_str(&square_str(to));
        if let Some(k) = promo { s.push('='); s.push(promo_letter(k)); }
    }
    if mate { s.push('#'); } else if check { s.push('+'); }
    s
}

#[cfg(test)]
mod tests {
    use super::*;
    use PieceKind::*;

    fn play(moves: &[&str]) -> Game {
        let mut g = Game::new();
        for (i, m) in moves.iter().enumerate() {
            g.apply_uci(m).unwrap_or_else(|e| panic!("move {} '{}' rejected: {:?}\nfen: {}", i + 1, m, e, g.fen()));
        }
        g
    }

    #[test]
    fn start_position_has_20_moves() {
        assert_eq!(Game::new().legal_moves().len(), 20);
        assert_eq!(Game::new().fen(), "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1");
    }

    #[test]
    fn wrong_turn_and_illegal_are_rejected() {
        let mut g = Game::new();
        assert_eq!(g.apply_uci("e7e5"), Err(MoveError::IllegalMove)); // black piece on white's turn
        assert_eq!(g.apply_uci("e2e5"), Err(MoveError::IllegalMove)); // pawn can't jump 3
        assert!(g.apply_uci("e2e4").is_ok());
    }

    // ── famous games: terminal states ──
    #[test]
    fn fools_mate() {
        let g = play(&["f2f3", "e7e5", "g2g4", "d8h4"]);
        assert_eq!(g.status(), GameStatus::Checkmate { winner: Color::Black });
    }
    #[test]
    fn scholars_mate() {
        let g = play(&["e2e4", "e7e5", "f1c4", "b8c6", "d1h5", "g8f6", "h5f7"]);
        assert_eq!(g.status(), GameStatus::Checkmate { winner: Color::White });
    }
    #[test]
    fn legals_mate() {
        // 1.e4 e5 2.Nf3 Nc6 3.Bc4 d6 4.Nc3 Bg4 5.Nxe5 Bxd1 6.Bxf7+ Ke7 7.Nd5#
        let g = play(&["e2e4", "e7e5", "g1f3", "b8c6", "f1c4", "d7d6", "b1c3",
                       "c8g4", "f3e5", "g4d1", "c4f7", "e8e7", "c3d5"]);
        assert_eq!(g.status(), GameStatus::Checkmate { winner: Color::White });
    }

    // ── special states ──
    #[test]
    fn en_passant_capture() {
        let mut g = play(&["e2e4", "a7a6", "e4e5", "d7d5"]);
        let o = g.apply_uci("e5d6").expect("ep legal");
        assert!(o.is_en_passant && o.capture);
        assert!(g.at(parse_square("d5").unwrap()).is_none(), "captured pawn removed");
        assert!(g.at(parse_square("d6").unwrap()).is_some(), "pawn advanced to d6");
    }
    #[test]
    fn promotion_and_underpromotion() {
        let mut g = Game::from_fen("7k/P7/8/8/8/8/8/7K w - - 0 1").unwrap();
        let o = g.apply_uci("a7a8q").unwrap();
        assert_eq!(o.promotion, Some(Queen));
        assert_eq!(g.at(parse_square("a8").unwrap()).unwrap().kind, Queen);
        let mut g2 = Game::from_fen("7k/P7/8/8/8/8/8/7K w - - 0 1").unwrap();
        assert_eq!(g2.apply_uci("a7a8n").unwrap().promotion, Some(Knight));
    }
    #[test]
    fn castling_both_sides() {
        let mut g = Game::from_fen("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1").unwrap();
        let o = g.apply_uci("e1g1").unwrap();
        assert!(o.is_castle);
        assert!(g.is_piece(parse_square("g1").unwrap(), Color::White, King));
        assert!(g.is_piece(parse_square("f1").unwrap(), Color::White, Rook));
        let mut g2 = Game::from_fen("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1").unwrap();
        assert!(g2.apply_uci("e1c1").unwrap().is_castle);
        assert!(g2.is_piece(parse_square("c1").unwrap(), Color::White, King));
        assert!(g2.is_piece(parse_square("d1").unwrap(), Color::White, Rook));
    }
    #[test]
    fn castling_through_check_is_illegal() {
        // black rook on f8 attacks f1 -> white may not castle kingside
        let mut g = Game::from_fen("5r2/8/8/8/8/8/8/4K2R w K - 0 1").unwrap();
        assert_eq!(g.apply_uci("e1g1"), Err(MoveError::IllegalMove));
    }
    #[test]
    fn stalemate_is_draw_not_mate() {
        let g = Game::from_fen("7k/5Q2/6K1/8/8/8/8/8 b - - 0 1").unwrap();
        assert_eq!(g.status(), GameStatus::Stalemate);
    }
    #[test]
    fn insufficient_material_kk_and_kn() {
        assert_eq!(Game::from_fen("8/8/8/4k3/8/4K3/8/8 w - - 0 1").unwrap().status(),
                   GameStatus::DrawInsufficientMaterial);
        assert_eq!(Game::from_fen("8/8/8/4k3/8/4K1N1/8/8 w - - 0 1").unwrap().status(),
                   GameStatus::DrawInsufficientMaterial);
    }
    #[test]
    fn fifty_move_draw() {
        let g = Game::from_fen("4k3/8/8/8/8/8/r7/4K3 w - - 100 1").unwrap();
        assert_eq!(g.status(), GameStatus::DrawFiftyMove);
    }
    #[test]
    fn threefold_repetition() {
        let g = play(&["g1f3", "g8f6", "f3g1", "f6g8", "g1f3", "g8f6", "f3g1", "f6g8"]);
        assert_eq!(g.status(), GameStatus::DrawThreefold);
    }
    #[test]
    fn resignation() {
        let mut g = Game::new();
        assert_eq!(g.resign(Color::White).status, GameStatus::Resigned { winner: Color::Black });
        assert_eq!(g.status(), GameStatus::Resigned { winner: Color::Black });
    }

    // ── perft: rigorous move-generation proof (exact known node counts) ──
    #[test]
    fn perft_startpos() {
        let g = Game::new();
        assert_eq!(g.perft(1), 20);
        assert_eq!(g.perft(2), 400);
        assert_eq!(g.perft(3), 8902);
        assert_eq!(g.perft(4), 197281);
    }
    #[test]
    fn status_wire_encoding() {
        assert_eq!(GameStatus::Ongoing.wire(), "ongoing");
        assert_eq!(GameStatus::Checkmate { winner: Color::White }.wire(), "checkmate:white");
        assert_eq!(GameStatus::Resigned { winner: Color::Black }.wire(), "resigned:black");
        assert_eq!(GameStatus::DrawThreefold.wire(), "draw:threefold");
    }
    #[test]
    fn world_payload_json() {
        let mut g = Game::new();
        let j = g.apply_uci("e2e4").unwrap().to_json();
        assert!(j.contains("\"lastMove\":\"e2e4\""), "{j}");
        assert!(j.contains("\"status\":\"ongoing\""), "{j}");
        assert!(j.contains("\"fen\":\"rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1\""), "{j}");
    }
    #[test]
    fn perft_kiwipete() {
        // The classic "Kiwipete" position — dense with castling, en passant,
        // promotions, pins, and checks. If perft matches here, move-gen is sound.
        let g = Game::from_fen("r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1").unwrap();
        assert_eq!(g.perft(1), 48);
        assert_eq!(g.perft(2), 2039);
        assert_eq!(g.perft(3), 97862);
    }
}
