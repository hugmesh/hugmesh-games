# HostBridge → JavaScriptBridge egress handler.
#
# After the consumer migration (PLAN section G):
#   • game_bridge.gd dispatches GameBridge events directly into the
#     sibling HostBridge node via `host_bridge.dispatch_event(...)`.
#   • player_controller.gd + scene_loader.gd subscribe on HostBridge's
#     typed channels (movement_received, time_received, etc.).
#
# So this glue script's previous role (subscribe to legacy GDScript
# signals + forward to HostBridge) is GONE — the only piece left is
# the egress hop: when game code calls `HostBridge.send_input(...)`,
# the bridge fires `input_egress`; we pipe that to
# `window._twInputQueue` so the federated host module can ship it
# back to the BFF.
#
# Scene wiring (cave.tscn / field.tscn):
#
#   [node name="GameBridge"  type="Node"       script="res://scripts/game_bridge.gd"]
#   [node name="HostBridge"  type="HostBridge" parent="."]
#   [node name="BridgeGlue"  type="Node"       parent="GameBridge"
#          script="res://scripts/bridge_glue.gd"]

extends Node

@onready var host_bridge: Node = _find_host_bridge()


func _ready() -> void:
	if host_bridge == null:
		push_warning("[bridge_glue] no HostBridge sibling node found; input_egress relay disabled")
		return

	# Mirror game_bridge.gd's web-vs-editor detection so HostBridge.is_streaming()
	# reports correctly to any HUD overlays.
	if OS.has_feature("web"):
		host_bridge.set_streaming_mode(true)

	host_bridge.input_egress.connect(_on_input_egress)


# Picks up either a sibling `HostBridge` node or one named `HostBridge` at
# the scene root. `HostBridge` is the GodotClass exposed by the
# snapdragon-bridge Rust extension; lookup works once the .gdextension
# is loaded.
func _find_host_bridge() -> Node:
	var sibling := get_parent().get_node_or_null("../HostBridge")
	if sibling != null:
		return sibling
	if get_tree() and get_tree().current_scene:
		return get_tree().current_scene.get_node_or_null("HostBridge")
	return null


# HostBridge.send_input(action, value) → input_egress(json) when streaming.
# Forward the JSON straight into window._twInputQueue so the federated
# host module can ship it back to the BFF; same lane as game_bridge's
# send_control() but using the typed-channel egress.
func _on_input_egress(payload_json: String) -> void:
	if not OS.has_feature("web"):
		return
	JavaScriptBridge.eval(
		"(window._twInputQueue ||= []).push(%s)" % payload_json
	)
