## Multiplayer slot orchestration — addon version.
##
## Generic across N-player Snapdragon games. Drop this script as a node
## sibling to HostBridge + BridgeGlue + a (optional) `Wizards` /
## `Players` parent node where slot avatars get attached.
##
## Behaviour:
##   1. Subscribes to HostBridge.slot_registered / slot_released so any
##      slot bookkeeping the parent does flows into the scene.
##   2. Fetches `/api/sessions/{id}` *via the bridge* to learn about
##      remote slots, then calls register_player_slot for each.
##   3. Spawns one programmatic CharacterBody3D per registered slot —
##      slot 0 is the local player; slots 1+ are remote and driven by
##      movement events whose payload's `slot` doesn't match the local.
##
## Game-side customisation: subclass and override `spawn_avatar(slot,
## character_id, is_local) -> Node3D` to use your own player scene
## instead of the default capsule + cone hat. The default avatar exists
## so a fresh game project gets a working visual without writing code.

class_name SnapdragonMultiplayerLobby
extends Node

const _DEFAULT_PALETTES := [
	Color(0.42, 0.31, 0.78), # slot 0 — purple
	Color(0.92, 0.45, 0.20), # slot 1 — orange
	Color(0.20, 0.72, 0.55), # slot 2 — green
	Color(0.85, 0.75, 0.30), # slot 3 — yellow
	Color(0.33, 0.58, 0.92), # slot 4 — blue
	Color(0.85, 0.30, 0.65), # slot 5 — pink
	Color(0.55, 0.55, 0.55), # slot 6 — grey
	Color(0.95, 0.95, 0.95), # slot 7 — white
]

@export var avatar_parent_path: NodePath = NodePath("../Wizards")

var _host_bridge: Node = null
var _api: BridgeApiClient = null
var _avatars: Dictionary = {} # slot:int -> Node3D


func _ready() -> void:
	_host_bridge = _find_host_bridge()
	if _host_bridge == null:
		push_warning("[snapdragon_bridge] no HostBridge — multiplayer lobby disabled")
		return

	_api = BridgeApiClient.new()
	add_child(_api)

	_host_bridge.connect("slot_registered", Callable(self, "_on_slot_registered"))
	_host_bridge.connect("slot_released",   Callable(self, "_on_slot_released"))
	_host_bridge.connect("movement_received", Callable(self, "_on_movement_received"))

	if OS.has_feature("web"):
		_fetch_session_members()


# ── Hookable surface ─────────────────────────────────────────────────

## Override in a subclass to spawn a custom avatar per slot. The default
## builds a capsule + cone-hat wizard, palette-coloured by slot. Returns
## the new Node3D after attaching to the avatar parent.
func spawn_avatar(slot: int, character_id: int, is_local: bool) -> Node3D:
	var av := CharacterBody3D.new()
	av.name = "Player_%d" % slot

	var body := MeshInstance3D.new()
	var capsule := CapsuleMesh.new()
	capsule.radius = 0.25
	capsule.height = 1.4
	body.mesh = capsule
	var mat := StandardMaterial3D.new()
	mat.albedo_color = _DEFAULT_PALETTES[slot % _DEFAULT_PALETTES.size()]
	body.material_override = mat
	av.add_child(body)

	var hat := MeshInstance3D.new()
	var cone := CylinderMesh.new()
	cone.top_radius = 0.0
	cone.bottom_radius = 0.18
	cone.height = 0.45
	hat.mesh = cone
	hat.position = Vector3(0, 0.95, 0)
	hat.material_override = mat
	av.add_child(hat)

	var collision := CollisionShape3D.new()
	var shape := CapsuleShape3D.new()
	shape.radius = 0.25
	shape.height = 1.4
	collision.shape = shape
	av.add_child(collision)

	av.set_meta("slot", slot)
	av.set_meta("character_id", character_id)
	av.set_meta("is_local", is_local)
	av.position = _starting_position_for_slot(slot)

	var parent: Node = get_node_or_null(avatar_parent_path)
	if parent == null:
		parent = get_parent()
	parent.add_child(av)
	return av


# Override in a subclass to lay your players out differently. The default
# spreads them in a small circle around origin so two-to-N players spawn
# visibly separated.
func _starting_position_for_slot(slot: int) -> Vector3:
	var radius := 1.5
	var angle := float(slot) * (TAU / 3.0)
	return Vector3(cos(angle) * radius, 0.0, sin(angle) * radius)


# ── Internals ────────────────────────────────────────────────────────

func _find_host_bridge() -> Node:
	var sibling := get_parent().get_node_or_null("HostBridge")
	if sibling != null:
		return sibling
	if get_tree() and get_tree().current_scene:
		return get_tree().current_scene.get_node_or_null("HostBridge")
	return null


func _on_slot_registered(slot: int, character_id: int, is_local: bool) -> void:
	if _avatars.has(slot):
		return
	_avatars[slot] = spawn_avatar(slot, character_id, is_local)


func _on_slot_released(slot: int) -> void:
	if not _avatars.has(slot):
		return
	var av: Node3D = _avatars[slot]
	if is_instance_valid(av):
		av.queue_free()
	_avatars.erase(slot)


func _on_movement_received(payload_json: String) -> void:
	var parsed = JSON.parse_string(payload_json)
	if not (parsed is Dictionary):
		return
	var slot: int = int(parsed.get("slot", -1))
	if slot < 0 or slot == _host_bridge.local_slot():
		return
	if not _avatars.has(slot):
		return
	var av: Node3D = _avatars[slot]
	if not is_instance_valid(av):
		return
	var x: float = float(parsed.get("x", av.position.x))
	var y: float = float(parsed.get("y", av.position.y))
	var z: float = float(parsed.get("z", av.position.z))
	av.position = Vector3(x, y, z)


func _fetch_session_members() -> void:
	var sid: String = str(_host_bridge.session_id())
	if sid == "":
		return
	_api.get("/api/sessions/" + sid, func(status, body):
		if status != 200 or not (body is Dictionary):
			return
		var members = body.get("members", [])
		if not (members is Array):
			return
		var local_slot := _host_bridge.local_slot()
		for entry in members:
			if not (entry is Dictionary):
				continue
			var slot: int = int(entry.get("slotIndex", -1))
			var cid: int = int(entry.get("characterId", -1))
			if slot < 0 or slot == local_slot:
				continue
			_host_bridge.register_player_slot(slot, cid, false)
	)
