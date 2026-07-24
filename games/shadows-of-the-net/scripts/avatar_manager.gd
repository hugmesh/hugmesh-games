extends Node3D

# Roster → rigs. Owns one CharacterAssembler rig per participant (keyed by a
# string user key: mxid in matrix mode, characterId in session mode), applies
# pose / seat / openness / expression / blendshape / speaking updates, and
# smooths remote motion. Seat + stage anchors are resolved from the scene by
# the room controller and injected via `seats_root` / `stage_anchor`.

const CharacterAssembler := preload("res://scripts/character_assembler.gd")

const LERP_SPEED := 8.0
const MAX_AVATARS := 16

var seats_root: Node3D = null
var stage_anchor: Node3D = null
var local_key := ""

## key → {rig, target_pos: Vector3, target_yaw: float, seat: int,
##        on_stage: bool, ring: MeshInstance3D, label: Label3D}
var _avatars: Dictionary = {}


func _process(delta: float) -> void:
	var t := clampf(delta * LERP_SPEED, 0.0, 1.0)
	for key in _avatars:
		var av: Dictionary = _avatars[key]
		var rig: Node3D = av["rig"]
		rig.position = rig.position.lerp(av["target_pos"], t)
		rig.rotation.y = lerp_angle(rig.rotation.y, av["target_yaw"], t)


## Create/replace a participant's rig from a Char3DConfig (or null → keep).
func upsert(key: String, config: Variant) -> void:
	if _avatars.has(key):
		if config != null:
			(_avatars[key]["rig"] as Node3D).call("assemble", config)
		return
	if _avatars.size() >= MAX_AVATARS:
		push_warning("[sotn] avatar cap (%d) reached — ignoring %s" % [MAX_AVATARS, key])
		return
	var rig := CharacterAssembler.new()
	rig.name = "Avatar_" + key.validate_node_name()
	add_child(rig)
	rig.assemble(config if config != null else CharacterAssembler.random_config(hash(key)))

	var label := Label3D.new()
	label.name = "NameTag"
	label.text = key.get_slice(":", 0).trim_prefix("@").substr(0, 24)
	label.billboard = BaseMaterial3D.BILLBOARD_ENABLED
	label.pixel_size = 0.004
	label.position = Vector3(0, 1.75, 0)
	label.modulate = Color(0.66, 0.68, 0.77)
	rig.add_child(label)

	var ring := MeshInstance3D.new()
	ring.name = "SpeakingRing"
	var ring_mesh := TorusMesh.new()
	ring_mesh.inner_radius = 0.16
	ring_mesh.outer_radius = 0.2
	ring.mesh = ring_mesh
	var ring_mat := StandardMaterial3D.new()
	ring_mat.albedo_color = Color(0.22, 0.83, 0.73)
	ring_mat.emission_enabled = true
	ring_mat.emission = Color(0.22, 0.83, 0.73)
	ring.material_override = ring_mat
	ring.position = Vector3(0, 1.62, 0)
	ring.visible = false
	rig.add_child(ring)

	_avatars[key] = {"rig": rig, "target_pos": Vector3.ZERO, "target_yaw": 0.0,
			"seat": -1, "on_stage": false, "ring": ring, "label": label}


## Deterministic guest fallback for participants without a config.
func ensure(key: String) -> void:
	if not _avatars.has(key):
		upsert(key, null)


func remove(key: String) -> void:
	if _avatars.has(key):
		(_avatars[key]["rig"] as Node3D).queue_free()
		_avatars.erase(key)


## Drop every avatar not present in `keys` (roster reconciliation).
func prune(keys: Array) -> void:
	for key in _avatars.keys():
		if not keys.has(key):
			remove(key)


func has_avatar(key: String) -> bool:
	return _avatars.has(key)


func keys() -> Array:
	return _avatars.keys()


## Free-roam pose (seat -1) or seated (snapped to the seat anchor).
func set_pose(key: String, x: float, z: float, yaw: float, seat: int) -> void:
	ensure(key)
	var av: Dictionary = _avatars[key]
	if av["on_stage"]:
		return  # stage placement wins until revoked
	av["seat"] = seat
	var anchor := _seat_anchor(seat)
	if anchor != null:
		av["target_pos"] = anchor.position
		# Seats have no baked rotation — face the room center.
		av["target_yaw"] = atan2(-anchor.position.x, -anchor.position.z)
	else:
		av["target_pos"] = Vector3(x, 0, z)
		av["target_yaw"] = yaw


func seat_of(key: String) -> int:
	return int(_avatars[key]["seat"]) if _avatars.has(key) else -1


func is_on_stage(key: String) -> bool:
	return bool(_avatars[key]["on_stage"]) if _avatars.has(key) else false


func set_openness(key: String, value: float) -> void:
	ensure(key)
	(_avatars[key]["rig"] as Node3D).call("set_openness", clampf(value, 0.0, 1.0))


func set_expression(key: String, label: String) -> void:
	ensure(key)
	(_avatars[key]["rig"] as Node3D).call("set_expression", label)


## 52 quantized MediaPipe blendshapes (raw model-card order incl. _neutral at
## index 0; 0..255 ≙ 0..1). The assembler's head rig consumes the full vector:
## per-eye blink lids, conjugate eyeLook* gaze, brow raise/lower, jawOpen.
func set_blendshapes(key: String, weights: PackedByteArray) -> void:
	if weights.size() != 52:
		return
	ensure(key)
	(_avatars[key]["rig"] as Node3D).call("set_blendshapes", weights)


func set_speaking(key: String, value: bool) -> void:
	ensure(key)
	(_avatars[key]["ring"] as MeshInstance3D).visible = value


## Stage grant: move onto the stage platform; revoke: return to last seat/pose.
func set_stage(key: String, value: bool) -> void:
	ensure(key)
	var av: Dictionary = _avatars[key]
	av["on_stage"] = value
	if value and stage_anchor != null:
		av["target_pos"] = stage_anchor.position
		av["target_yaw"] = atan2(-stage_anchor.position.x, -stage_anchor.position.z)
	elif not value:
		var anchor := _seat_anchor(int(av["seat"]))
		if anchor != null:
			av["target_pos"] = anchor.position


func position_of(key: String) -> Vector3:
	return (_avatars[key]["rig"] as Node3D).position if _avatars.has(key) else Vector3.ZERO


## Hide/show a rig (the local rig is hidden while an XR session runs so the
## wearer doesn't see their own head geometry).
func set_visible_for(key: String, value: bool) -> void:
	if _avatars.has(key):
		(_avatars[key]["rig"] as Node3D).visible = value


func _seat_anchor(seat: int) -> Node3D:
	if seats_root == null or seat < 0 or seat >= seats_root.get_child_count():
		return null
	return seats_root.get_child(seat) as Node3D
