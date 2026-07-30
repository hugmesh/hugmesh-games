extends Node3D

# Roster → rigs. Owns one CharacterAssembler rig per participant (keyed by a
# string user key: mxid in matrix mode, characterId in session mode), applies
# pose / seat / openness / expression / blendshape / speaking updates, and
# smooths remote motion. Seat + stage anchors are resolved from the scene by
# the room controller and injected via `seats_root` / `stage_anchor`.
#
# NODE SHAPE (load-bearing — do not flatten):
#
#   AvatarManager
#   └── Avatar_<key> (Node3D)      ← the node this manager MOVES (target_pos/yaw)
#       ├── Rig (CharacterAssembler)  ← re-assembled on every avatar() update
#       ├── NameTag (Label3D)
#       └── SpeakingRing (MeshInstance3D)
#
# The nametag and speaking ring are SIBLINGS of the rig, never its children:
# CharacterAssembler.assemble() frees ALL of its own children on every rebuild
# (`for child in get_children(): child.queue_free()`), so anything parented to
# the rig is destroyed the first time a participant publishes their config —
# which is the normal roster→avatar order, i.e. it would happen to everyone.

const CharacterAssembler := preload("res://scripts/character_assembler.gd")

const LERP_SPEED := 8.0
const MAX_AVATARS := 16
## Chamber radius (mirrors local_controller.ROOM_RADIUS). Remote poses are
## clamped into it — see set_pose().
const ROOM_RADIUS := 8.6

var seats_root: Node3D = null
var stage_anchor: Node3D = null
var local_key := ""

## key → {root: Node3D, rig: CharacterAssembler, target_pos: Vector3,
##        target_yaw: float, seat: int, on_stage: bool,
##        ring: MeshInstance3D, label: Label3D}
var _avatars: Dictionary = {}


func _process(delta: float) -> void:
	var t := clampf(delta * LERP_SPEED, 0.0, 1.0)
	for key in _avatars:
		var av: Dictionary = _avatars[key]
		var root_node: Node3D = av["root"]
		root_node.position = root_node.position.lerp(av["target_pos"], t)
		root_node.rotation.y = lerp_angle(root_node.rotation.y, av["target_yaw"], t)


## Create/replace a participant's rig from a Char3DConfig (or null → keep).
func upsert(key: String, config: Variant) -> void:
	if _avatars.has(key):
		if config != null:
			(_avatars[key]["rig"] as Node3D).call("assemble", config)
		return
	if _avatars.size() >= MAX_AVATARS:
		push_warning("[sotn] avatar cap (%d) reached — ignoring %s" % [MAX_AVATARS, key])
		return
	var root_node := Node3D.new()
	root_node.name = "Avatar_" + key.validate_node_name()
	add_child(root_node)

	# The rig is a CHILD of the per-participant root — assemble() wipes its own
	# children, so the room-owned decorations below must not live under it.
	var rig := CharacterAssembler.new()
	rig.name = "Rig"
	root_node.add_child(rig)
	rig.assemble(config if config != null else CharacterAssembler.random_config(hash(key)))

	var label := Label3D.new()
	label.name = "NameTag"
	label.text = key.get_slice(":", 0).trim_prefix("@").substr(0, 24)
	label.billboard = BaseMaterial3D.BILLBOARD_ENABLED
	label.pixel_size = 0.004
	label.position = Vector3(0, 1.75, 0)
	label.modulate = Color(0.66, 0.68, 0.77)
	root_node.add_child(label)

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
	root_node.add_child(ring)

	_avatars[key] = {"root": root_node, "rig": rig,
			"target_pos": Vector3.ZERO, "target_yaw": 0.0,
			"seat": -1, "on_stage": false, "ring": ring, "label": label}


## Deterministic guest fallback for participants without a config.
##
## Returns whether `key` HAS a rig once the call returns — i.e. false when the
## room is already at MAX_AVATARS and this key is new. Every mutator below MUST
## check it: `upsert()` legitimately refuses over the cap, and `_avatars[key]`
## on a refused key is a hard GDScript error ("Invalid access to property or
## key"), not a silent no-op. A 17th participant used to make set_pose /
## set_speaking / set_openness / set_expression / set_blendshapes / set_stage
## throw on EVERY update for that peer — 10-15 Hz per pose channel — and the
## games shim republishes each one to the embedding page as an SD_WASM_CONSOLE
## message. Reachable from either transport: a Matrix roster larger than the
## clamp, or session-mode member churn (nothing prunes departed characterIds,
## so `_avatars` grows past 16 across a long-lived session).
func ensure(key: String) -> bool:
	if not _avatars.has(key):
		upsert(key, null)
	return _avatars.has(key)


func remove(key: String) -> void:
	if _avatars.has(key):
		(_avatars[key]["root"] as Node3D).queue_free()
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
##
## SECURITY: x/z/yaw are PEER-SUPPLIED in both transports. A non-finite value
## reaching a Node3D transform makes the renderer assert
## (`instance_set_transform: !v.is_finite()`) EVERY frame and poisons the lerp
## target permanently (lerp(INF, finite) == NaN), so one forged pose from any
## room member would wreck every other participant's view until they reload.
## This is the single choke point for every pose source (session movement
## relay, Chat3D matrix relay, authoritative world echo, local rig), so the
## finiteness rejection and the chamber clamp live HERE, not only at the
## parsers. Non-finite ⇒ drop the update entirely; finite-but-absurd ⇒ clamp
## into the chamber so a peer can't park itself outside the room either.
func set_pose(key: String, x: float, z: float, yaw: float, seat: int) -> void:
	if not (is_finite(x) and is_finite(z) and is_finite(yaw)):
		return
	if not ensure(key):
		return
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
		av["target_pos"] = _clamp_to_room(x, z)
		# Yaw is periodic: wrap rather than clamp (a spun-around avatar is
		# fine; a 1e30 yaw fed into lerp_angle is not).
		av["target_yaw"] = wrapf(yaw, -PI, PI)


## Component-clamp FIRST (keeps the magnitude finite and small enough that
## Vector2.length() can never overflow), then radius-clamp onto the chamber.
func _clamp_to_room(x: float, z: float) -> Vector3:
	var flat := Vector2(clampf(x, -ROOM_RADIUS, ROOM_RADIUS), clampf(z, -ROOM_RADIUS, ROOM_RADIUS))
	if flat.length() > ROOM_RADIUS:
		flat = flat.normalized() * ROOM_RADIUS
	return Vector3(flat.x, 0.0, flat.y)


func seat_of(key: String) -> int:
	return int(_avatars[key]["seat"]) if _avatars.has(key) else -1


func is_on_stage(key: String) -> bool:
	return bool(_avatars[key]["on_stage"]) if _avatars.has(key) else false


func set_openness(key: String, value: float) -> void:
	if not ensure(key):
		return
	# openness is peer-supplied: NaN would propagate into the jaw rotation.
	if not is_finite(value):
		return
	(_avatars[key]["rig"] as Node3D).call("set_openness", clampf(value, 0.0, 1.0))


func set_expression(key: String, label: String) -> void:
	if not ensure(key):
		return
	(_avatars[key]["rig"] as Node3D).call("set_expression", label)


## 52 quantized MediaPipe blendshapes (raw model-card order incl. _neutral at
## index 0; 0..255 ≙ 0..1). The assembler's head rig consumes the full vector:
## per-eye blink lids, conjugate eyeLook* gaze, brow raise/lower, jawOpen.
func set_blendshapes(key: String, weights: PackedByteArray) -> void:
	if weights.size() != 52:
		return
	if not ensure(key):
		return
	(_avatars[key]["rig"] as Node3D).call("set_blendshapes", weights)


func set_speaking(key: String, value: bool) -> void:
	if not ensure(key):
		return
	var ring: Variant = _avatars[key]["ring"]
	# Defence in depth: a cast of a freed object is a hard GDScript error that
	# would kill the whole speaking channel for the room. The ring is a sibling
	# of the rig now (see the node-shape note at the top) so it survives every
	# re-assemble — the guard just keeps a future regression non-fatal.
	if is_instance_valid(ring):
		(ring as MeshInstance3D).visible = value


## Stage grant: move onto the stage platform; revoke: return to last seat/pose.
func set_stage(key: String, value: bool) -> void:
	if not ensure(key):
		return
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
	return (_avatars[key]["root"] as Node3D).position if _avatars.has(key) else Vector3.ZERO


## Hide/show a participant (the local avatar is hidden while an XR session runs
## so the wearer doesn't see their own head geometry). Hides the whole
## participant node — rig, nametag and speaking ring together.
func set_visible_for(key: String, value: bool) -> void:
	if _avatars.has(key):
		(_avatars[key]["root"] as Node3D).visible = value


func _seat_anchor(seat: int) -> Node3D:
	if seats_root == null or seat < 0 or seat >= seats_root.get_child_count():
		return null
	return seats_root.get_child(seat) as Node3D
