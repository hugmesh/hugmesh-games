# JavaScriptBridge ↔ HostBridge plumbing for the Snapdragon addon.
#
# Two responsibilities:
#   1. Forward outbound bridge signals (input_egress, view_egress,
#      api_request_egress) to JS-land where the host shell consumes
#      them.
#   2. Pump inbound responses (SSE events on the legacy paths;
#      SD_API_RESPONSE postMessages on the API path) back through
#      HostBridge.dispatch_event / dispatch_api_response.
#
# Sibling-node placement (cave.tscn / field.tscn / any user scene):
#   [node name="GameBridge"   type="Node"        script="res://scripts/game_bridge.gd"]
#   [node name="HostBridge"   type="HostBridge"  parent="."]
#   [node name="BridgeGlue"   type="Node"        parent="."
#          script="res://addons/snapdragon_bridge/bridge_glue.gd"]
#
# The script is part of the snapdragon_bridge addon — game projects
# don't ship their own copy. Updates to the contract land here and
# every game using the addon picks them up.

extends Node

@onready var host_bridge: Node = _find_host_bridge()


func _ready() -> void:
	if host_bridge == null:
		push_warning("[snapdragon_bridge] no HostBridge sibling node found; egress relay disabled")
		return

	# Mirror game_bridge.gd's web-vs-editor detection so HostBridge.is_streaming()
	# reports correctly to any HUD overlays.
	if OS.has_feature("web"):
		host_bridge.set_streaming_mode(true)
		_install_api_response_listener()

	host_bridge.input_egress.connect(_on_input_egress)
	host_bridge.view_egress.connect(_on_view_egress)
	host_bridge.api_request_egress.connect(_on_api_request_egress)


func _process(_delta: float) -> void:
	if not OS.has_feature("web") or host_bridge == null:
		return
	_drain_api_responses()


# Picks up either a sibling `HostBridge` node or one named `HostBridge`
# at the scene root. Works whether the bridge is a sibling of GameBridge
# or sits at the scene root next to a parent group.
func _find_host_bridge() -> Node:
	var sibling := get_parent().get_node_or_null("HostBridge")
	if sibling != null:
		return sibling
	if get_tree() and get_tree().current_scene:
		return get_tree().current_scene.get_node_or_null("HostBridge")
	return null


# ── Outbound to host ─────────────────────────────────────────────────

func _on_input_egress(payload_json: String) -> void:
	if not OS.has_feature("web"):
		return
	JavaScriptBridge.eval(
		"(window._twInputQueue ||= []).push(%s)" % payload_json
	)


# Per-player view sample → POST /api/view via the host shell.
# The host's ApiBridgeService catches the postMessage and forwards.
func _on_view_egress(payload_json: String) -> void:
	if not OS.has_feature("web"):
		return
	JavaScriptBridge.eval(
		"window.parent.postMessage({type:'SD_VIEW_EGRESS', payload: %s}, '*')" % payload_json
	)


# Outbound API request — host shell takes it, performs HttpClient call,
# replies via SD_API_RESPONSE.
func _on_api_request_egress(payload_json: String) -> void:
	if not OS.has_feature("web"):
		return
	JavaScriptBridge.eval(
		"window.parent.postMessage({type:'SD_API_REQUEST', payload: %s}, '*')" % payload_json
	)


# ── Inbound from host ────────────────────────────────────────────────

# Install a JS-side message listener that pushes incoming
# SD_API_RESPONSE payloads onto a queue we can drain from GDScript.
# Idempotent — repeated calls reset the queue but only attach one
# listener (guarded by window._twApiInboundInstalled).
func _install_api_response_listener() -> void:
	JavaScriptBridge.eval("""
(function() {
  window._twApiResponseQueue = window._twApiResponseQueue || [];
  if (window._twApiInboundInstalled) return;
  window._twApiInboundInstalled = true;
  window.addEventListener('message', function(ev) {
    var d = ev && ev.data;
    if (!d || d.type !== 'SD_API_RESPONSE' || !d.payload) return;
    window._twApiResponseQueue.push(d.payload);
  });
})();
	""")


func _drain_api_responses() -> void:
	var raw: Variant = JavaScriptBridge.eval(
		"JSON.stringify((window._twApiResponseQueue && window._twApiResponseQueue.splice(0, window._twApiResponseQueue.length)) || [])"
	)
	if raw == null or raw == "null":
		return
	var parsed = JSON.parse_string(str(raw))
	if not (parsed is Array):
		return
	for entry in parsed:
		# entry is the inner payload object; re-stringify so the Rust
		# side parses one consistent JSON shape.
		var as_str := JSON.stringify(entry)
		host_bridge.dispatch_api_response(as_str)
