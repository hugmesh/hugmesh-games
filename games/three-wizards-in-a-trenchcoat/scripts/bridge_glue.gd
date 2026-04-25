# Adapter that forwards the legacy GameBridge (game_bridge.gd) signal stream
# into a Rust HostBridge node so the rest of the scripts can migrate to
# HostBridge.movement_received / world_received / etc. without breaking the
# existing JS-bridge polling. Both bridges fire side-by-side until every
# subscriber has moved across; remove `game_bridge.gd` once nothing in
# this project subscribes to its old signals.
#
# Scene wiring (cave.tscn / field.tscn):
#
#   [node name="GameBridge" type="Node" script="res://scripts/game_bridge.gd"]
#   [node name="HostBridge" type="HostBridge" parent="."]
#   [node name="BridgeGlue" type="Node" parent="GameBridge"
#          script="res://scripts/bridge_glue.gd"]
#
# `BridgeGlue._ready()` finds the sibling `HostBridge` and subscribes to the
# parent `GameBridge`'s signals; no further config needed per scene.
#
# Local-play (Godot editor F5): `game_bridge.gd`'s no-API branch already
# echoes input through `movement_updated`, so `BridgeGlue` re-emits those
# through `HostBridge.movement_received` without any extra plumbing.

extends Node

@onready var game_bridge: Node = get_parent()
@onready var host_bridge: Node = _find_host_bridge()


func _ready() -> void:
	if host_bridge == null:
		push_warning("[bridge_glue] no HostBridge sibling node found; remaining game scripts must keep using GameBridge directly")
		return

	# Tell the bridge whether we have a parent module wired up. game_bridge's
	# `_ready` already detected web vs editor; we just mirror the result so
	# HostBridge.is_streaming() reports correctly to any HUD overlays.
	if OS.has_feature("web"):
		host_bridge.set_streaming_mode(true)

	game_bridge.state_updated.connect(_forward.bind("movement"))
	game_bridge.movement_updated.connect(_forward.bind("movement"))
	game_bridge.world_updated.connect(_forward.bind("world"))
	game_bridge.character_updated.connect(_forward.bind("character"))
	game_bridge.time_updated.connect(_forward.bind("time"))
	game_bridge.scene_updated.connect(_forward.bind("scene"))

	host_bridge.input_egress.connect(_on_input_egress)


# Picks up either a sibling `HostBridge` node or one named `HostBridge` at the
# scene root. `HostBridge` is the GodotClass exposed by the snapdragon-bridge
# Rust extension; `class_name` lookup works once the .gdextension is loaded.
func _find_host_bridge() -> Node:
	var sibling := get_parent().get_node_or_null("../HostBridge")
	if sibling != null:
		return sibling
	if get_tree() and get_tree().current_scene:
		return get_tree().current_scene.get_node_or_null("HostBridge")
	return null


# Generic per-channel forwarder. The legacy bridge emits dictionaries; the
# Rust bridge wants JSON strings (works across every gdext snapshot without
# Dictionary-typing drift), so we serialize once at the seam.
func _forward(payload: Dictionary, event_type: String) -> void:
	if host_bridge == null:
		return
	host_bridge.dispatch_event(event_type, JSON.stringify(payload))


# HostBridge.send_input(action, value) → input_egress(json) when streaming.
# We forward the JSON straight into window._twInputQueue so the federated
# host module can ship it back to the BFF; same lane as game_bridge's
# send_control() but without the legacy POST shape.
func _on_input_egress(payload_json: String) -> void:
	if not OS.has_feature("web"):
		return
	JavaScriptBridge.eval(
		"(window._twInputQueue ||= []).push(%s)" % payload_json
	)
