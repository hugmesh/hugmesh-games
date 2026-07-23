extends Node2D

# Chess (Godot) — board rendering + input, ALL rules delegated to the Rust core.
#
# Source of truth: the `ChessEngine` GDExtension node (hugmesh_bridge),
# which wraps the existing, validated `hugmesh-chess` Rust crate — the SAME
# engine the web-native bundle drives through its wasm C-ABI shim. This script
# NEVER decides chess legality itself:
#
#   * `engine.legal_moves_from(sq)`  → which squares a clicked piece may go to,
#   * `engine.apply_uci(uci)`        → validate + apply, returns chess.state JSON,
#   * `engine.board_fen()`/`status()`→ render + end-state,
#   * `engine.load_fen(fen)`         → re-sync from an authoritative world event.
#
# Multiplayer (host-authoritative, turn-based):
#   * a move is relayed to the BFF via the bridge API client; the BFF echoes the
#     canonical world event back over SSE,
#   * the local engine applies the move immediately for responsiveness, then
#     re-syncs from the authoritative FEN whenever a world event arrives — so a
#     Godot guest is interchangeable with the existing web guest.

const BOARD := 8
const SQUARE_PX := 72.0
const BOARD_PX := SQUARE_PX * BOARD

const LIGHT := Color(0.918, 0.851, 0.710)   # ead9b5
const DARK := Color(0.612, 0.420, 0.247)    # 9c6b3f
const SELECT := Color(0.357, 0.549, 0.749, 0.55)
const TARGET := Color(0.357, 0.749, 0.451, 0.55)
const LASTMOVE := Color(0.86, 0.78, 0.30, 0.40)

const GLYPHS := {
	"P": "♙", "N": "♘", "B": "♗", "R": "♖", "Q": "♕", "K": "♔",
	"p": "♟", "n": "♞", "b": "♝", "r": "♜", "q": "♛", "k": "♚",
}

var _engine: Node = null            # ChessEngine (Rust core)
var _host_bridge: Node = null
var _api: Node = null               # BridgeApiClient (addon)

# Board model is derived from the engine's FEN — never hand-mutated here.
var _piece_at := {}                 # square index 0..63 -> FEN char ("P","n",...)
var _selected: int = -1             # selected origin square, -1 = none
var _targets := {}                  # set of legal destination squares for _selected
var _last_from: int = -1
var _last_to: int = -1
var _my_slot: int = -1              # 0 = White, 1 = Black, -1 = hot-seat / single

@onready var _status_label: Label = $"../Ui/Status"


func _ready() -> void:
	_host_bridge = get_parent().get_node_or_null("HostBridge")

	# The chess rules engine — the WHOLE point of the pivot. Instantiated from
	# the GDExtension class registered by hugmesh_bridge. If the class is
	# missing (extension failed to load) we surface it loudly rather than
	# silently reimplementing rules.
	if ClassDB.class_exists("ChessEngine"):
		_engine = ClassDB.instantiate("ChessEngine")
		add_child(_engine)
		_engine.new_game()
	else:
		push_error("[chess-godot] ChessEngine GDExtension class not found — build the bridge (make build-rust).")

	# Bridge-routed API client (addon) for relaying moves to the BFF.
	var api_script := load("res://addons/hugmesh_bridge/bridge_api_client.gd")
	if api_script == null and ClassDB.class_exists("BridgeApiClient"):
		_api = ClassDB.instantiate("BridgeApiClient")
		add_child(_api)
	elif api_script != null:
		_api = api_script.new()
		add_child(_api)

	if _host_bridge != null:
		_host_bridge.world_received.connect(_on_world_received)
		_host_bridge.movement_received.connect(_on_movement_received)
		_my_slot = int(_host_bridge.local_slot())

	_refresh_from_engine()


# ── Rendering ────────────────────────────────────────────────────────────────

func _refresh_from_engine() -> void:
	if _engine == null:
		return
	_parse_fen(str(_engine.board_fen()))
	_update_status()
	queue_redraw()


func _parse_fen(fen: String) -> void:
	_piece_at.clear()
	var board_part: String = fen.split(" ")[0]
	var rank: int = 7   # FEN starts at rank 8 (top)
	var file: int = 0
	for ch in board_part:
		if ch == "/":
			rank -= 1
			file = 0
		elif ch.is_valid_int():
			file += int(ch)
		else:
			var sq: int = rank * 8 + file   # core indexing: 0=a1, file=sq%8, rank=sq/8
			_piece_at[sq] = ch
			file += 1


func _draw() -> void:
	for sq in range(64):
		var f: int = sq % 8
		var r: int = sq / 8
		var pos := _square_origin(f, r)
		var rect := Rect2(pos, Vector2(SQUARE_PX, SQUARE_PX))
		draw_rect(rect, DARK if (f + r) % 2 == 0 else LIGHT)

	if _last_from >= 0:
		_shade(_last_from, LASTMOVE)
	if _last_to >= 0:
		_shade(_last_to, LASTMOVE)
	if _selected >= 0:
		_shade(_selected, SELECT)
		for t in _targets:
			_shade(t, TARGET)

	var font := ThemeDB.fallback_font
	for key in _piece_at:
		var sq: int = key
		var ch: String = _piece_at[sq]
		var glyph: String = GLYPHS.get(ch, "")
		if glyph == "":
			continue
		var f: int = sq % 8
		var r: int = sq / 8
		var origin := _square_origin(f, r)
		var col := Color(0.96, 0.96, 0.98) if ch == ch.to_upper() else Color(0.10, 0.09, 0.13)
		draw_string(font, origin + Vector2(SQUARE_PX * 0.16, SQUARE_PX * 0.78), glyph,
			HORIZONTAL_ALIGNMENT_LEFT, -1, int(SQUARE_PX * 0.72), col)


func _shade(sq: int, col: Color) -> void:
	var f: int = sq % 8
	var r: int = sq / 8
	draw_rect(Rect2(_square_origin(f, r), Vector2(SQUARE_PX, SQUARE_PX)), col)


# Board is drawn White-at-bottom; if this client drives Black, flip vertically
# so the local player sees their own pieces nearest. Pure presentation —
# the engine's square indices are unchanged.
func _square_origin(file: int, rank: int) -> Vector2:
	var ff := file
	var rr := rank
	if _my_slot == 1:
		ff = 7 - file
		rr = 7 - rank
	return Vector2(ff * SQUARE_PX, (7 - rr) * SQUARE_PX)


func _square_at_point(p: Vector2) -> int:
	if p.x < 0 or p.y < 0 or p.x >= BOARD_PX or p.y >= BOARD_PX:
		return -1
	var ff := int(p.x / SQUARE_PX)
	var rr := 7 - int(p.y / SQUARE_PX)
	var file := ff
	var rank := rr
	if _my_slot == 1:
		file = 7 - ff
		rank = 7 - rr
	return rank * 8 + file


# ── Input ────────────────────────────────────────────────────────────────────

func _unhandled_input(event: InputEvent) -> void:
	if not (event is InputEventMouseButton):
		return
	var mb := event as InputEventMouseButton
	if not (mb.pressed and mb.button_index == MOUSE_BUTTON_LEFT):
		return
	if _engine == null or _engine.is_terminal():
		return

	var sq := _square_at_point(to_local(mb.position))
	if sq < 0:
		return

	# If we already have a selection and this square is a legal target, move.
	if _selected >= 0 and _targets.has(sq):
		_attempt_move(_selected, sq)
		_clear_selection()
		return

	# Otherwise (re)select a piece that has legal moves. The ENGINE decides
	# which squares are legal — this script only asks.
	var moves: PackedStringArray = _engine.legal_moves_from(_square_name(sq))
	if moves.size() > 0:
		_selected = sq
		_targets.clear()
		for uci in moves:
			_targets[_square_index(uci.substr(2, 2))] = true
		queue_redraw()
	else:
		_clear_selection()


func _clear_selection() -> void:
	_selected = -1
	_targets.clear()
	queue_redraw()


func _attempt_move(from: int, to: int) -> void:
	# Promotion: auto-queen for the reference game (the wave plan notes a
	# promotion picker as a follow-up; the engine accepts the full UCI suffix).
	var uci := _square_name(from) + _square_name(to)
	var moving: String = _piece_at.get(from, "")
	var to_rank := to / 8
	if moving.to_lower() == "p" and (to_rank == 7 or to_rank == 0):
		uci += "q"

	# VALIDATE + APPLY via the Rust core. Never trust the click.
	var result_json := str(_engine.apply_uci(uci))
	var result = JSON.parse_string(result_json)
	if not (result is Dictionary) or result.get("type", "") != "chess.state":
		print("[chess-godot] move rejected by core: ", result_json)
		return

	_last_from = from
	_last_to = to
	_refresh_from_engine()

	# Relay to the BFF so the opponent's client receives the authoritative
	# world event. Single-player / hot-seat (no session) skips the relay.
	if OS.has_feature("web") and _host_bridge != null and _host_bridge.is_streaming():
		_relay_move(uci, result_json)


func _relay_move(uci: String, world_json: String) -> void:
	# Mirror the web bundle: post the move on the control channel; the BFF
	# fans the world event back out on the session SSE stream. We use the
	# bridge API client so the host's auth + X-Session-Id interceptor apply.
	if _api == null:
		return
	var body := {"action": "CHESS_MOVE", "uci": uci, "world": world_json}
	if _api.has_method("http_post"):
		_api.http_post("/api/controls/1", body, func(_status, _resp): pass)


# ── SSE ingress (authoritative re-sync) ──────────────────────────────────────

func _on_world_received(payload_json: String) -> void:
	var ev = JSON.parse_string(payload_json)
	if not (ev is Dictionary):
		return
	if ev.get("type", "") != "chess.state":
		return
	var fen: String = ev.get("fen", "")
	if fen != "" and _engine != null:
		# Authoritative state from the host wins — re-sync the local engine.
		_engine.load_fen(fen)
		var lm: String = ev.get("lastMove", "")
		if lm.length() >= 4:
			_last_from = _square_index(lm.substr(0, 2))
			_last_to = _square_index(lm.substr(2, 2))
		_clear_selection()
		_refresh_from_engine()


# Some deployments relay chess moves on the `movement` channel (CHESS_MOVE
# control events). Accept those too and apply the embedded world FEN.
func _on_movement_received(payload_json: String) -> void:
	var ev = JSON.parse_string(payload_json)
	if ev is Dictionary and ev.has("world"):
		_on_world_received(str(ev["world"]))


# ── status ───────────────────────────────────────────────────────────────────

func _update_status() -> void:
	if _status_label == null or _engine == null:
		return
	var status := str(_engine.status())
	var side := str(_engine.side_to_move())
	var text := ""
	match status:
		"ongoing": text = "%s to move" % side.capitalize()
		"check": text = "%s to move — CHECK" % side.capitalize()
		"stalemate": text = "Stalemate — draw"
		"draw:fifty": text = "Draw (fifty-move rule)"
		"draw:threefold": text = "Draw (threefold repetition)"
		"draw:material": text = "Draw (insufficient material)"
		_:
			if status.begins_with("checkmate:"):
				text = "Checkmate — %s wins" % status.split(":")[1].capitalize()
			elif status.begins_with("resigned:"):
				text = "%s wins (resignation)" % status.split(":")[1].capitalize()
			else:
				text = status
	_status_label.text = text


# ── square helpers (match core: 0=a1, file=sq%8, rank=sq/8) ──────────────────

func _square_name(sq: int) -> String:
	var f := char("a".unicode_at(0) + (sq % 8))
	var r := char("1".unicode_at(0) + (sq / 8))
	return f + r


func _square_index(sq_name: String) -> int:
	if sq_name.length() < 2:
		return -1
	var f: int = sq_name.unicode_at(0) - "a".unicode_at(0)
	var r: int = sq_name.unicode_at(1) - "1".unicode_at(0)
	if f < 0 or f > 7 or r < 0 or r > 7:
		return -1
	return r * 8 + f
