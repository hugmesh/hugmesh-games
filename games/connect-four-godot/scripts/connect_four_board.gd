extends Node2D

# Connect Four (Godot) — board rendering + input, ALL rules delegated to the Rust core.
#
# Source of truth: the `ConnectFourEngine` GDExtension node (snapdragon_bridge),
# which wraps the existing, validated `snapdragon-connect-four` Rust crate — the
# SAME engine the web-native bundle drives through its wasm C-ABI shim. This
# script NEVER decides Connect Four legality itself:
#
#   * `engine.can_drop(col)`   → whether a column can take a disc right now,
#   * `engine.apply_drop(col)` → validate + drop, returns connect_four.state JSON,
#   * `engine.board_str()`/`status()`/`side_to_move()` → render + end-state,
#   * `engine.height(col)`     → where a disc would land (the ghost preview).
#
# Multiplayer (host-authoritative, turn-based) — mirrors chess_board.gd:
#   * a drop is relayed to the BFF via the bridge API client; the BFF echoes the
#     canonical `world` event back over SSE,
#   * the local engine applies the drop immediately for responsiveness, then
#     re-syncs on each `world` event. Unlike chess (which owns `load_fen`), the
#     Connect Four core exposes NO `from_state` constructor, so re-sync means
#     RE-APPLYING the authoritative last drop verbatim (`apply_drop(lastCol)`)
#     and comparing `board_str()` to the relayed `board`. A relay that can't be
#     caught up by one drop degrades to a visible "out of sync" banner rather
#     than a silent divergence — the documented limit of the simple cores.

const COLS := 7
const ROWS := 6
const CELL := 84.0                      # px per grid cell
const BOARD_W := CELL * COLS            # 588
const BOARD_H := CELL * ROWS            # 504
const HOLE_R := CELL * 0.42
const DISC_R := CELL * 0.38

const BOARD_BLUE := Color(0.13, 0.30, 0.66)
const EMPTY := Color(0.05, 0.06, 0.10)              # carved hole / no disc
const RED := Color(0.85, 0.24, 0.24)
const YELLOW := Color(0.96, 0.80, 0.25)
const RED_GHOST := Color(0.85, 0.24, 0.24, 0.35)
const YELLOW_GHOST := Color(0.96, 0.80, 0.25, 0.35)
const COL_HILITE := Color(1.0, 1.0, 1.0, 0.10)
const LASTMOVE_RING := Color(0.95, 0.95, 0.35, 0.85)
const WIN_RING := Color(0.30, 0.95, 0.55, 0.95)

var _engine: Node = null                # ConnectFourEngine (Rust core)
var _host_bridge: Node = null
var _api: Node = null                   # BridgeApiClient (addon)

# Board model is derived from the engine's board_str — never hand-mutated here.
var _disc := {}                         # col * ROWS + row -> "R" / "Y"
var _hover_col: int = -1
var _last_col: int = -1
var _last_row: int = -1
var _winning: Array = []                # Array[Vector2i] winning-line cells
var _my_slot: int = -1                  # 0 = Red, 1 = Yellow, -1 = hot-seat / single
var _synced: bool = true                # false => local board diverged from the host

@onready var _status_label: Label = $"../Ui/Status"


func _ready() -> void:
	_host_bridge = get_parent().get_node_or_null("HostBridge")

	# The rules engine — the WHOLE point of the pivot. Instantiated from the
	# GDExtension class registered by snapdragon_bridge. If the class is missing
	# (extension failed to load) we surface it loudly rather than silently
	# reimplementing rules.
	if ClassDB.class_exists("ConnectFourEngine"):
		_engine = ClassDB.instantiate("ConnectFourEngine")
		add_child(_engine)
		_engine.new_game()
	else:
		push_error("[connect-four-godot] ConnectFourEngine GDExtension class not found — build the bridge (make build-rust).")

	# Bridge-routed API client (addon) for relaying drops to the BFF.
	var api_script := load("res://addons/snapdragon_bridge/bridge_api_client.gd")
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
	_parse_board(str(_engine.board_str()))
	_update_status()
	queue_redraw()


# board_str is 6 rows top-to-bottom joined by '/', each 7 chars of '.'/'R'/'Y'.
# rows[0] is the TOP (board row ROWS-1); rows[ROWS-1] is the bottom (row 0).
func _parse_board(board: String) -> void:
	_disc.clear()
	var rows := board.split("/")
	for i in range(rows.size()):
		var row_str: String = rows[i]
		var r: int = (ROWS - 1) - i                 # board row (0 = bottom, gravity)
		for c in range(min(COLS, row_str.length())):
			var ch := row_str[c]
			if ch == "R" or ch == "Y":
				_disc[c * ROWS + r] = ch


func _draw() -> void:
	draw_rect(Rect2(Vector2.ZERO, Vector2(BOARD_W, BOARD_H)), BOARD_BLUE)

	# Column hover highlight — only a live, playable column.
	if _hover_col >= 0 and _can_play_here(_hover_col):
		draw_rect(Rect2(Vector2(_hover_col * CELL, 0.0), Vector2(CELL, BOARD_H)), COL_HILITE)

	# Carved holes + settled discs.
	for c in range(COLS):
		for r in range(ROWS):
			var center := _cell_center(c, r)
			draw_circle(center, HOLE_R, EMPTY)
			var ch: String = _disc.get(c * ROWS + r, "")
			if ch == "R":
				draw_circle(center, DISC_R, RED)
			elif ch == "Y":
				draw_circle(center, DISC_R, YELLOW)

	# Ghost preview disc where the current player's drop would land.
	if _hover_col >= 0 and _can_play_here(_hover_col):
		var land_row: int = int(_engine.height(_hover_col))
		if land_row < ROWS:
			draw_circle(_cell_center(_hover_col, land_row), DISC_R,
				RED_GHOST if _side_is_red() else YELLOW_GHOST)

	# Last-move ring.
	if _last_col >= 0 and _last_row >= 0:
		draw_arc(_cell_center(_last_col, _last_row), DISC_R + 3.0, 0.0, TAU, 40, LASTMOVE_RING, 3.0)

	# Winning-line rings.
	for cell in _winning:
		draw_arc(_cell_center(cell.x, cell.y), DISC_R + 5.0, 0.0, TAU, 40, WIN_RING, 4.0)


# row 0 is the BOTTOM of a column; screen y grows downward so it sits lowest.
func _cell_center(col: int, row: int) -> Vector2:
	var screen_row: int = (ROWS - 1) - row
	return Vector2(col * CELL + CELL * 0.5, screen_row * CELL + CELL * 0.5)


func _col_at_x(x: float) -> int:
	if x < 0.0 or x >= BOARD_W:
		return -1
	return int(x / CELL)


# ── Input ────────────────────────────────────────────────────────────────────

func _unhandled_input(event: InputEvent) -> void:
	if event is InputEventMouseMotion:
		var over := _col_at_x(to_local((event as InputEventMouseMotion).position).x)
		if over != _hover_col:
			_hover_col = over
			queue_redraw()
		return

	if not (event is InputEventMouseButton):
		return
	var mb := event as InputEventMouseButton
	if not (mb.pressed and mb.button_index == MOUSE_BUTTON_LEFT):
		return
	if _engine == null or _engine.is_terminal():
		return

	var col := _col_at_x(to_local(mb.position).x)
	if col < 0 or not _can_play_here(col):
		return
	_attempt_drop(col)


func _side_is_red() -> bool:
	return _engine != null and str(_engine.side_to_move()) == "red"


# My colour from the multiplayer slot: 0 = Red, 1 = Yellow. "" = hot-seat/single
# (either side may play locally — the editor F5 path and solo play).
func _my_color() -> String:
	if _my_slot == 0:
		return "red"
	if _my_slot == 1:
		return "yellow"
	return ""


# Legality gate: the ENGINE owns can_drop; the slot owns whose turn it is. We
# refuse to act on a diverged local board (_synced == false) so a click can't
# fork state away from the host.
func _can_play_here(col: int) -> bool:
	if _engine == null or col < 0 or not _engine.can_drop(col) or not _synced:
		return false
	var mine := _my_color()
	return mine == "" or mine == str(_engine.side_to_move())


func _attempt_drop(col: int) -> void:
	# VALIDATE + APPLY via the Rust core. Never trust the click.
	var result_json := str(_engine.apply_drop(col))
	var result = JSON.parse_string(result_json)
	if not (result is Dictionary) or result.get("type", "") != "connect_four.state":
		print("[connect-four-godot] drop rejected by core: ", result_json)
		return

	_apply_state_highlights(result)
	_hover_col = -1
	_refresh_from_engine()

	# Relay to the BFF so the opponent's client receives the authoritative world
	# event. Single-player / hot-seat (no session) skips the relay.
	if OS.has_feature("web") and _host_bridge != null and _host_bridge.is_streaming():
		_relay_drop(col, result_json)


func _relay_drop(col: int, world_json: String) -> void:
	# Mirror the web bundle: post the drop on the control channel; the BFF fans
	# the world event back out on the session SSE stream. We use the bridge API
	# client so the host's auth + X-Session-Id interceptor apply.
	if _api == null:
		return
	var body := {"action": "CONNECT_FOUR_DROP", "col": col, "world": world_json}
	if _api.has_method("http_post"):
		_api.http_post("/api/controls/1", body, func(_status, _resp): pass)


# ── SSE ingress (authoritative re-sync) ──────────────────────────────────────

func _on_world_received(payload_json: String) -> void:
	var ev = JSON.parse_string(payload_json)
	if not (ev is Dictionary) or ev.get("type", "") != "connect_four.state":
		return
	if _engine == null:
		return
	var auth_board := str(ev.get("board", ""))
	if auth_board == "":
		return

	# Already matches (our own echoed drop, or nothing new): refresh highlight.
	if str(_engine.board_str()) == auth_board:
		_synced = true
		_apply_state_highlights(ev)
		_refresh_from_engine()
		return

	# Catch up by RE-APPLYING the authoritative last drop verbatim. The core has
	# no from_state constructor, so a deterministic replay is the only
	# rules-preserving re-sync — exactly the host-authoritative model.
	var last_col := int(ev.get("lastCol", -1))
	if last_col >= 0 and _engine.can_drop(last_col):
		_engine.apply_drop(last_col)

	_synced = str(_engine.board_str()) == auth_board
	_apply_state_highlights(ev)
	_hover_col = -1
	_refresh_from_engine()


# Some deployments relay drops on the `movement` channel (CONNECT_FOUR_DROP
# control events with an embedded world snapshot). Accept those too.
func _on_movement_received(payload_json: String) -> void:
	var ev = JSON.parse_string(payload_json)
	if ev is Dictionary and ev.has("world"):
		_on_world_received(str(ev["world"]))


func _apply_state_highlights(ev: Dictionary) -> void:
	_last_col = int(ev.get("lastCol", -1))
	_last_row = int(ev.get("lastRow", -1))
	_winning.clear()
	var line = ev.get("winningLine", [])
	if line is Array:
		for pair in line:
			if pair is Array and pair.size() >= 2:
				_winning.append(Vector2i(int(pair[0]), int(pair[1])))


# ── status ───────────────────────────────────────────────────────────────────

func _update_status() -> void:
	if _status_label == null or _engine == null:
		return
	var status := str(_engine.status())
	var side := str(_engine.side_to_move())
	var text := ""
	match status:
		"ongoing":
			text = "%s to move" % side.capitalize()
		"draw":
			text = "Draw — board full"
		"win:red":
			text = "Red wins!"
		"win:yellow":
			text = "Yellow wins!"
		_:
			text = status
	if not _synced:
		text += "  (out of sync — awaiting host)"
	_status_label.text = text
