extends Node
##
## Multiplayer slot orchestration for three-wizards.
##
## A scene script (cave or field) wires this in alongside the HostBridge
## node. On scene load we:
##   1. Subscribe to HostBridge.slot_registered / slot_released so any
##      slot bookkeeping the parent does flows into the scene.
##   2. Fetch the active session's member roster from
##      `/api/sessions/{id}` and call `register_player_slot` for every
##      remote slot we learn about (the local slot is registered by
##      game_bridge.gd's URL-param threading).
##   3. Spawn one wizard CharacterBody3D per registered slot — slot 0
##      is the local player; slots 1+ are remote and driven by SSE
##      movement events whose payload carries `slot` (see HostBridge's
##      send_input enrichment).
##
## The wizard avatar template is built programmatically (no .tscn
## needed) so this works without a scene-editor pass on cave / field —
## the existing scene just needs a HostBridge + GameBridge + this node
## as siblings, plus a Node3D parent named "Wizards" where the avatars
## get attached.

const WIZARD_PALETTES := [
	Color(0.42, 0.31, 0.78), # slot 0 — purple
	Color(0.92, 0.45, 0.20), # slot 1 — orange
	Color(0.20, 0.72, 0.55), # slot 2 — green
	Color(0.85, 0.75, 0.30), # slot 3 — yellow (extras for N-player)
	Color(0.33, 0.58, 0.92), # slot 4 — blue
]

var _host_bridge: Node = null
var _wizards: Dictionary = {} # slot:int -> CharacterBody3D


func _ready() -> void:
	_host_bridge = get_parent().get_node_or_null("HostBridge")
	if _host_bridge == null:
		push_warning("[MultiplayerLobby] no HostBridge sibling — lobby disabled")
		return
	_host_bridge.connect("slot_registered", Callable(self, "_on_slot_registered"))
	_host_bridge.connect("slot_released",   Callable(self, "_on_slot_released"))
	_host_bridge.connect("movement_received", Callable(self, "_on_movement_received"))

	if OS.has_feature("web"):
		_fetch_session_members()


func _on_slot_registered(slot: int, character_id: int, is_local: bool) -> void:
	if _wizards.has(slot):
		return
	var wizard := _spawn_wizard(slot, character_id, is_local)
	_wizards[slot] = wizard


func _on_slot_released(slot: int) -> void:
	if not _wizards.has(slot):
		return
	var wizard: Node3D = _wizards[slot]
	if is_instance_valid(wizard):
		wizard.queue_free()
	_wizards.erase(slot)


func _on_movement_received(payload: String) -> void:
	# Payload is a JSON string carrying {slot, characterId, x, y, z, ...}.
	# We only relay remote slots' movement to remote wizards; the local
	# slot drives its own CharacterBody3D via player_controller.gd.
	var parsed = JSON.parse_string(payload)
	if not (parsed is Dictionary):
		return
	var slot: int = int(parsed.get("slot", -1))
	if slot < 0 or slot == _host_bridge.local_slot():
		return
	if not _wizards.has(slot):
		return
	var wizard: Node3D = _wizards[slot]
	if not is_instance_valid(wizard):
		return
	var x: float = float(parsed.get("x", wizard.position.x))
	var y: float = float(parsed.get("y", wizard.position.y))
	var z: float = float(parsed.get("z", wizard.position.z))
	wizard.position = Vector3(x, y, z)


func _fetch_session_members() -> void:
	# Read session id from URL, fetch member list, register each remote
	# slot. The local slot is already registered by game_bridge.gd before
	# we get here.
	JavaScriptBridge.eval("""
(function() {
	var params = new URLSearchParams(window.location.search);
	var sid = params.get('session_id');
	if (!sid) { window._twSessionMembers = null; return; }
	fetch('/api/sessions/' + sid)
		.then(function(r) { return r.json(); })
		.then(function(s) { window._twSessionMembers = s.members || []; })
		.catch(function() { window._twSessionMembers = []; });
})();
	""")
	# Allow one frame for the fetch to land then drain it.
	await get_tree().create_timer(0.4).timeout
	var raw: Variant = JavaScriptBridge.eval("JSON.stringify(window._twSessionMembers || [])")
	if raw == null or raw == "null":
		return
	var parsed = JSON.parse_string(str(raw))
	if not (parsed is Array):
		return
	var local_slot := _host_bridge.local_slot()
	for entry in parsed:
		if not (entry is Dictionary):
			continue
		var slot: int = int(entry.get("slotIndex", -1))
		var cid: int = int(entry.get("characterId", -1))
		if slot < 0 or slot == local_slot:
			continue
		_host_bridge.register_player_slot(slot, cid, false)


func _spawn_wizard(slot: int, character_id: int, is_local: bool) -> CharacterBody3D:
	var wizard := CharacterBody3D.new()
	wizard.name = "Wizard_%d" % slot
	wizard.position = _starting_position_for_slot(slot)

	# Visible body — capsule + cone-shaped hat hinted via two MeshInstance3D.
	var body := MeshInstance3D.new()
	body.mesh = CapsuleMesh.new()
	body.mesh.radius = 0.25
	body.mesh.height = 1.4
	var mat := StandardMaterial3D.new()
	mat.albedo_color = WIZARD_PALETTES[slot % WIZARD_PALETTES.size()]
	body.material_override = mat
	wizard.add_child(body)

	var hat := MeshInstance3D.new()
	var cone := CylinderMesh.new()
	cone.top_radius = 0.0
	cone.bottom_radius = 0.18
	cone.height = 0.45
	hat.mesh = cone
	hat.position = Vector3(0, 0.95, 0)
	hat.material_override = mat
	wizard.add_child(hat)

	# Collision — keep it simple so the wizard interacts with the cave floor.
	var collision := CollisionShape3D.new()
	var shape := CapsuleShape3D.new()
	shape.radius = 0.25
	shape.height = 1.4
	collision.shape = shape
	wizard.add_child(collision)

	# Tag with metadata so other scripts can correlate wizards back to slots.
	wizard.set_meta("slot", slot)
	wizard.set_meta("character_id", character_id)
	wizard.set_meta("is_local", is_local)

	# Attach under the "Wizards" parent if the scene provides one;
	# otherwise just sit under our own parent. Both forms work.
	var parent := get_parent().get_node_or_null("Wizards")
	if parent == null:
		parent = get_parent()
	parent.add_child(wizard)
	return wizard


# Starting positions arranged in a small triangle around origin so the
# three default wizards spawn visibly separated. Extras tile outward.
func _starting_position_for_slot(slot: int) -> Vector3:
	var radius := 1.5
	var angle := float(slot) * (TAU / 3.0)
	return Vector3(cos(angle) * radius, 0.0, sin(angle) * radius)
