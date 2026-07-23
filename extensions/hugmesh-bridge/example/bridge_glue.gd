# Reference template: drop a copy of this into a new game project under
# `scripts/bridge_glue.gd`, attach it to a `HostBridge` Node3D in the
# main scene, and connect to its signals from your game scripts. Same
# code path runs in Streaming (federated host attached) and Standalone
# (Godot IDE local-play) modes — game scripts never branch on which.
#
# This file lives under extensions/hugmesh-bridge/example/ so it
# doesn't auto-load anywhere; copy + adapt per game.

extends Node

# Recommended setup in your main scene .tscn:
#
#   [node name="HostBridge" type="HostBridge"]
#   [node name="BridgeGlue" type="Node" parent="HostBridge"]
#   script = ExtResource("res://scripts/bridge_glue.gd")

@onready var bridge: Node = get_parent()  # the HostBridge node
const POLL_HZ: float = 30.0

var _poll_accum: float = 0.0


func _ready() -> void:
	# Detect a parent module (HugMesh federated host wires up
	# `window._twSceneEvents`) and flip HostBridge into Streaming mode.
	# Standalone is the default — the bridge already faked a scene_ready
	# in its own _ready(); your game can listen on `scene_received`.
	if OS.has_feature("web") and _has_parent_module():
		bridge.set_streaming_mode(true)
		bridge.input_egress.connect(_on_input_egress)
		# Outbound audio + console egress also forward to the host.
		bridge.audio_egress.connect(_on_audio_egress)
		bridge.console_egress.connect(_on_console_egress)
		print("[bridge_glue] streaming mode wired")
	else:
		print("[bridge_glue] standalone mode — local input echoes through movement_received")


func _process(delta: float) -> void:
	if not bridge.is_streaming():
		return
	_poll_accum += delta
	var interval := 1.0 / POLL_HZ
	if _poll_accum < interval:
		return
	_poll_accum = 0.0

	# Drain the parent's SSE queue in one eval and dispatch each event
	# into the HostBridge so signals fire on the same frame.
	var raw: Variant = JavaScriptBridge.eval(
		"JSON.stringify(window._twSceneEvents?.splice(0) ?? [])"
	)
	var raw_str := str(raw) if raw != null else "[]"
	if raw_str != "" and raw_str != "[]":
		var events = JSON.parse_string(raw_str)
		if events is Array:
			for event in events:
				if event is Dictionary:
					var t: String = str(event.get("type", ""))
					var payload_json: String = JSON.stringify(event.get("payload", {}))
					bridge.dispatch_event(t, payload_json)

	# Drain the hugmesh-shim input queue: gamepad polls, mouse
	# motion, keyboard, touch — all forwarded by the host shell via
	# postMessage and stashed on window._sdInputQueue. Each entry has
	# the shape { kind, type, payload, ts }; HostBridge.dispatch_event
	# routes the kind to the matching per-kind signal so game scripts
	# subscribe to gamepad_received / mouse_received / etc. without
	# caring whether the source was the BFF SSE or the host's gamepad
	# poll.
	var raw_input: Variant = JavaScriptBridge.eval(
		"JSON.stringify(window._sdInputQueue?.splice(0) ?? [])"
	)
	var raw_in_str := str(raw_input) if raw_input != null else "[]"
	if raw_in_str != "" and raw_in_str != "[]":
		var inputs = JSON.parse_string(raw_in_str)
		if inputs is Array:
			for inp in inputs:
				if inp is Dictionary:
					var kind: String = str(inp.get("kind", ""))
					var payload_json: String = JSON.stringify(inp.get("payload", {}))
					if kind != "":
						bridge.dispatch_event(kind, payload_json)


func _on_input_egress(payload_json: String) -> void:
	# HostBridge already JSON-encoded the payload as
	# `{"action":"<x>","value":<y>}`; the parent module reads it from
	# window._twInputQueue.
	var snippet := "(window._twInputQueue ||= []).push(%s)" % payload_json
	JavaScriptBridge.eval(snippet)


func _on_audio_egress(payload_json: String) -> void:
	# Forward an outbound audio cue to the host devtools subscribers.
	# The host shell's WasmConsoleForwarder already listens for
	# postMessage; the BFF's audio SSE channel is the destination.
	var snippet := "window.parent.postMessage({type:'SD_AUDIO_EGRESS',payload:%s}, '*')" % payload_json
	JavaScriptBridge.eval(snippet)


func _on_console_egress(payload_json: String) -> void:
	# Forward a console line to the host. The shim already wraps the
	# default console and onerror channels — this path is for explicit
	# game-side `bridge.report_error(...)` calls that catch GDScript
	# exceptions Godot's default print pipeline would swallow.
	var snippet := "window.parent.postMessage(Object.assign({type:'SD_WASM_CONSOLE'}, %s), '*')" % payload_json
	JavaScriptBridge.eval(snippet)


func _has_parent_module() -> bool:
	var probe: Variant = JavaScriptBridge.eval(
		"typeof window._twSceneEvents !== 'undefined'"
	)
	return bool(probe) if probe != null else false
