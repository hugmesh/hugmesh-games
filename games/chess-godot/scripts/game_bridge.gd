extends Node

# Chess (Godot) — bridge bootstrap. Mirrors three-wizards' game_bridge.gd but
# trimmed to what a turn-based, host-authoritative board game needs:
#   * thread multiplayer URL params (session_id / character_id / slot_index)
#     into the sibling HostBridge so control writes are session-scoped,
#   * ask the Angular wrapper to open the SSE EventSource and forward events,
#   * wire session pause / resume / close to the scene tree.
#
# It does NOT contain any chess rules — those live in the Rust core, reached
# from chess_board.gd via the ChessEngine GDExtension node. This script only
# wires transport, exactly like every other Snapdragon Godot game.

var _host_bridge: Node = null


func _ready() -> void:
	# Sibling HostBridge resolves only after the scene tree is built.
	_host_bridge = get_parent().get_node_or_null("HostBridge")

	if _host_bridge != null:
		_host_bridge.session_control_received.connect(_on_session_control)

	if OS.has_feature("web"):
		_thread_multiplayer_url_params()
		_request_sse_subscription()


# Pause / resume / close routed in from the BFF's `session-control` SSE channel.
func _on_session_control(action: String, _payload: String) -> void:
	var tree := get_tree()
	if tree == null:
		return
	match action:
		"SESSION_PAUSED", "PLAYER_PAUSED":
			tree.paused = true
		"SESSION_RESUMED", "PLAYER_RESUMED":
			tree.paused = false
		"SESSION_CLOSED":
			tree.quit()


# Thread multiplayer context from the iframe URL into HostBridge:
#   ?session_id=<uuid>     active multiplayer session
#   ?character_id=<long>   character this slot is driving
#   ?slot_index=<int>      slot 0..maxPlayers-1 (0 = White, 1 = Black)
func _thread_multiplayer_url_params() -> void:
	if _host_bridge == null:
		return
	var raw: Variant = JavaScriptBridge.eval("""
(function() {
	var params = new URLSearchParams(window.location.search);
	return JSON.stringify({
		sessionId:   params.get('session_id')   || '',
		characterId: parseInt(params.get('character_id') || '-1', 10),
		slotIndex:   parseInt(params.get('slot_index')   || '-1', 10)
	});
})();
	""")
	if not raw or raw == "null":
		return
	var parsed = JSON.parse_string(str(raw))
	if not (parsed is Dictionary):
		return
	var sid: String = parsed.get("sessionId", "")
	var cid: int = int(parsed.get("characterId", -1))
	var slot: int = int(parsed.get("slotIndex", -1))
	if sid != "":
		_host_bridge.set_session_id(sid)
	if slot >= 0 and cid >= 0:
		_host_bridge.register_player_slot(slot, cid, true)
		print("[chess-godot] multiplayer — slot=%d characterId=%d session=%s" % [slot, cid, sid])


# Ask the Angular wrapper to subscribe to the session SSE stream on this
# iframe's behalf and forward each event back as SD_SSE_EVENT. BridgeGlue
# (Rust) routes them into HostBridge's typed signals (world_received etc.).
func _request_sse_subscription() -> void:
	var sid_raw: Variant = JavaScriptBridge.eval(
		"new URLSearchParams(window.location.search).get('session_id') || ''")
	var sid := str(sid_raw) if sid_raw != null else ""
	JavaScriptBridge.eval(
		"window.parent.postMessage({type:'SD_SSE_SUBSCRIBE', payload: {sessionId: '%s'}}, '*')" % sid
	)
