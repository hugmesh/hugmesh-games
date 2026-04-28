## Default pre-game lobby UI for any Snapdragon-hosted Godot game.
##
## A Control overlay with three lists/forms:
##   • Open sessions for the current game (Join button per row).
##   • Create-new-session form (name + max players).
##   • Active-members preview once you've joined.
##
## Drop the matching .tscn into a scene OR instantiate this script
## directly:
##
##     var menu := preload("res://addons/snapdragon_bridge/multiplayer_lobby_menu.gd").new()
##     menu.game_slug = "three-wizards"
##     add_child(menu)
##     menu.session_joined.connect(func(session_id, slot_index):
##         _start_game(session_id, slot_index))
##
## All API calls flow through BridgeApiClient — no direct HTTPRequest.

class_name SnapdragonLobbyMenu
extends Control

@export var game_slug: String = ""
@export var character_id: int = 1

signal session_joined(session_id: String, slot_index: int)

var _api: BridgeApiClient = null
var _sessions: Array = []

@onready var _list_root: VBoxContainer = $Layout/Sessions/List
@onready var _name_input: LineEdit       = $Layout/Create/NameInput
@onready var _max_input: SpinBox         = $Layout/Create/MaxInput
@onready var _create_btn: Button         = $Layout/Create/CreateBtn
@onready var _refresh_btn: Button        = $Layout/Sessions/RefreshBtn
@onready var _status_label: Label        = $Layout/StatusLabel


func _ready() -> void:
	_api = BridgeApiClient.new()
	add_child(_api)
	if _refresh_btn != null:
		_refresh_btn.pressed.connect(_refresh)
	if _create_btn != null:
		_create_btn.pressed.connect(_create)
	_refresh()


func _refresh() -> void:
	_set_status("Loading sessions…")
	var path := "/api/sessions"
	if game_slug != "":
		path += "?gameSlug=" + game_slug.uri_encode()
	_api.get(path, func(status, body):
		if status != 200 or not (body is Array):
			_set_status("Couldn't load sessions (status %d)" % status)
			return
		_sessions = body
		_set_status("%d open session%s" % [_sessions.size(), "s" if _sessions.size() != 1 else ""])
		_render_sessions()
	)


func _render_sessions() -> void:
	if _list_root == null:
		return
	for child in _list_root.get_children():
		child.queue_free()
	for s in _sessions:
		if not (s is Dictionary):
			continue
		var row := HBoxContainer.new()
		var label := Label.new()
		label.text = "%s — %d / %d" % [
			str(s.get("name", "(unnamed)")),
			int(s.get("activePlayers", 0)),
			int(s.get("maxPlayers", 0)),
		]
		label.size_flags_horizontal = Control.SIZE_EXPAND_FILL
		row.add_child(label)
		var join_btn := Button.new()
		join_btn.text = "Join"
		join_btn.disabled = int(s.get("activePlayers", 0)) >= int(s.get("maxPlayers", 0))
		join_btn.pressed.connect(_join.bind(s))
		row.add_child(join_btn)
		_list_root.add_child(row)


func _join(session: Dictionary) -> void:
	var sid: String = str(session.get("id", ""))
	if sid == "":
		return
	var slot := _first_free_slot(session)
	if slot < 0:
		_set_status("Session is full.")
		return
	_api.post("/api/sessions/%s/join" % sid,
		{"characterId": character_id, "slotIndex": slot},
		func(status, _body):
			if status >= 200 and status < 300:
				emit_signal("session_joined", sid, slot)
			else:
				_set_status("Join failed (status %d)" % status)
	)


func _create() -> void:
	if game_slug == "":
		_set_status("game_slug not configured.")
		return
	var name := _name_input.text.strip_edges() if _name_input else ""
	if name == "":
		_set_status("Session name required.")
		return
	var max_players := int(_max_input.value) if _max_input else 3
	_api.post("/api/sessions",
		{"gameSlug": game_slug, "name": name, "maxPlayers": max_players,
		 "characterId": character_id, "slotIndex": 0},
		func(status, body):
			if status >= 200 and status < 300 and body is Dictionary:
				emit_signal("session_joined", str(body.get("id", "")), 0)
			else:
				_set_status("Create failed (status %d)" % status)
	)


func _first_free_slot(session: Dictionary) -> int:
	var claimed := {}
	var members = session.get("members", [])
	if members is Array:
		for m in members:
			if m is Dictionary:
				var s = m.get("slotIndex")
				if s != null:
					claimed[int(s)] = true
	var max_players := int(session.get("maxPlayers", 3))
	for i in range(max_players):
		if not claimed.has(i):
			return i
	return -1


func _set_status(msg: String) -> void:
	if _status_label != null:
		_status_label.text = msg
