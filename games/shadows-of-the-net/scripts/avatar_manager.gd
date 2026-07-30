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
#       ├── Billboard (MeshInstance3D) ← billboard mode only (hidden otherwise)
#       ├── NameTag (Label3D)
#       └── SpeakingRing (MeshInstance3D)
#
# The nametag, billboard and speaking ring are SIBLINGS of the rig, never its
# children: CharacterAssembler.assemble() frees ALL of its own children on every
# rebuild (`for child in get_children(): child.queue_free()`), so anything
# parented to the rig is destroyed the first time a participant publishes their
# config — which is the normal roster→avatar order, i.e. everyone.

const CharacterAssembler := preload("res://scripts/character_assembler.gd")
## Reused for its decoder, not for its nodes: sniff_format/decode_image are the
## room's ONE place that turns bytes into an Image, so a billboard sticker and a
## table placement accept exactly the same containers by construction.
const MediaSurfaces := preload("res://scripts/media_surfaces.gd")

const LERP_SPEED := 8.0
const MAX_AVATARS := 16
## Chamber radius (mirrors local_controller.ROOM_RADIUS). Remote poses are
## clamped into it — see set_pose().
const ROOM_RADIUS := 8.6
## Longest nametag rendered above a participant.
const MAX_NAMETAG := 24
## The blank billboard plate: what a billboard participant looks like before
## their sticker BYTES arrive, and what clearing a sticker restores.
const BILLBOARD_BLANK := Color(0.12, 0.14, 0.22)

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


## The nametag text for a participant: the DISPLAY NAME the host supplies in
## every roster row and avatar envelope, falling back to the mxid localpart.
##
## The host documents `name` as the nametag source and computes it on every
## poll; this side used to build the tag from the localpart and throw the
## supplied name away, so a participant's chosen display name never appeared in
## the room they were standing in. The name is sanitised with the SERVER's
## display-name rule (control/bidi/invisible code points stripped) before it
## goes anywhere near a Label3D — an unsanitised tag lets one member's name
## visually rewrite their neighbour's.
static func nametag_for(key: String, display_name: String) -> String:
	var clean := CharacterAssembler.display_name(display_name)
	if clean == "":
		clean = key.get_slice(":", 0).trim_prefix("@")
	return clean.substr(0, MAX_NAMETAG)


## Create/replace a participant's rig from a Char3DConfig (or null → keep).
##
## `meta` is the §5 avatar envelope's mode/sticker/name slots ({} when the
## caller has none — session mode, or an `ensure()` from the roster).
func upsert(key: String, config: Variant, meta: Dictionary = {}) -> void:
	if _avatars.has(key):
		if config != null:
			(_avatars[key]["rig"] as Node3D).call("assemble", config)
		_apply_meta(key, meta)
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

	# A flat sticker plane for billboard-mode participants — hidden until the
	# avatar envelope says `mode:"billboard"` or a `sticker` payload arrives.
	# It starts BLANK; set_sticker() is what puts a picture on it.
	var billboard := MeshInstance3D.new()
	billboard.name = "Billboard"
	var billboard_mesh := QuadMesh.new()
	billboard_mesh.size = Vector2(0.9, 1.2)
	billboard.mesh = billboard_mesh
	var billboard_mat := StandardMaterial3D.new()
	billboard_mat.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	billboard_mat.billboard_mode = BaseMaterial3D.BILLBOARD_ENABLED
	billboard_mat.albedo_color = BILLBOARD_BLANK
	billboard.material_override = billboard_mat
	billboard.position = Vector3(0, 0.95, 0)
	billboard.visible = false
	root_node.add_child(billboard)

	var label := Label3D.new()
	label.name = "NameTag"
	label.text = nametag_for(key, str(meta.get("name", "")))
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
			"seat": -1, "on_stage": false, "ring": ring, "label": label,
			"billboard": billboard, "mode": "", "sticker": "", "has_sticker": false}
	_apply_meta(key, meta)


## Apply the §5 avatar envelope's mode/sticker/name slots.
##
## BILLBOARD MODE is a real participant choice ("appear as my flat VN sticker,
## not as a 3D character"), and the host cannot express it any other way: it
## sends configJson=null and puts everything in these fields. Rendering such a
## participant as a randomised 3D character — which is what happened while the
## fields were unreachable — misrepresents them.
##
## THE MXC IS STILL NOT AN IMAGE. `sticker` here is an `mxc://` URL; Matrix
## media is authenticated and lives on the HOST's origin, and this wasm bundle
## runs on the games origin with no access token, so it can never fetch one.
## That has not changed and cannot. What changed is that the host now ships the
## BYTES on their own §5 channel (`sticker(userId, b64, mime)` → set_sticker),
## exactly as this docstring said it would need to — so a billboard participant
## renders as a blank plate only until those bytes land, instead of forever. The
## mxc is still kept because it is what the host DIFFS on, and because it is the
## only durable identity a sticker has in room state.
func _apply_meta(key: String, meta: Dictionary) -> void:
	if meta.is_empty() or not _avatars.has(key):
		return
	var av: Dictionary = _avatars[key]
	# A SUPPLIED name updates the tag. An absent/empty one must not clobber it:
	# the roster row is the other place the host publishes the display name, and
	# an older host's two-slot avatar envelope carries no name at all — resetting
	# everyone to their mxid localpart on the next avatar diff is not a fallback,
	# it is data loss.
	var supplied_name := str(meta.get("name", ""))
	if supplied_name != "":
		set_display_name(key, supplied_name)
	var mode := str(meta.get("mode", ""))
	if mode == "":
		return          # host said nothing — leave the current representation
	av["mode"] = mode
	av["sticker"] = str(meta.get("sticker", ""))
	_show_billboard(av, mode == "billboard")


## Flip a participant between their flat plate and their 3D rig. The ONE place
## that decides which of the two is visible — the avatar envelope's `mode` slot
## and the `sticker` byte channel are two producers for the same question, and
## they must not each grow their own copy of the answer.
func _show_billboard(av: Dictionary, on: bool) -> void:
	var billboard: Variant = av["billboard"]
	if is_instance_valid(billboard):
		(billboard as MeshInstance3D).visible = on
	(av["rig"] as Node3D).visible = not on


## Put a participant's VN sticker on their billboard plate — the §5
## `sticker(userId, b64, mime)` channel's landing spot.
##
## RECEIVING A STICKER *IS* THE BILLBOARD SIGNAL. `mode` deliberately does not
## travel on this channel: bytes mean "render this member as their flat sticker",
## EMPTY means "clear the plate and give them back the rig they have" (for a
## billboard member that is the deterministic stand-in `ensure()` built them).
## Neither side then has to keep a `mode` field in sync with the other.
##
## THIS MUTATOR MUST NEVER ensure(). Every other one here conjures a rig for an
## unknown key, which is right for channels the host orders after the roster
## row. A sticker is order-independent by contract — it may arrive before or
## after that row — so conjuring from it would let ARRIVAL ORDER decide whether
## a member exists, and a sticker for somebody who has left would resurrect
## them. An unknown key is dropped; the host re-sends when they reappear.
func set_sticker(key: String, bytes: PackedByteArray) -> void:
	if not _avatars.has(key):
		return
	var av: Dictionary = _avatars[key]
	var plate: Variant = av["billboard"]
	if not is_instance_valid(plate):
		return
	var mat := (plate as MeshInstance3D).material_override as StandardMaterial3D
	if mat == null:
		return
	if bytes.is_empty():
		mat.albedo_texture = null
		mat.albedo_color = BILLBOARD_BLANK
		av["has_sticker"] = false
		_show_billboard(av, false)
		return
	# The room's whole decoder surface, shared with placeMedia: the container is
	# sniffed only to choose which of load_png/jpg/webp_from_buffer to try
	# FIRST, so a sticker whose declared type and magic number disagree still
	# renders if any decoder can read it.
	var img := MediaSurfaces.decode_image(bytes)
	if img == null:
		# Undecodable bytes leave the participant EXACTLY as they were. Flipping
		# them to a blank plate would replace a character somebody is looking at
		# with a slab, which is a worse answer than "the new sticker did not
		# arrive".
		push_warning("[sotn] undecodable sticker for %s (not PNG/JPEG/WebP) — keeping the current look" % key)
		return
	mat.albedo_texture = ImageTexture.create_from_image(img)
	mat.albedo_color = Color.WHITE
	av["has_sticker"] = true
	_show_billboard(av, true)


## Whether this participant's plate is carrying a decoded sticker image
## (test/diagnostic hook — a plate can also be shown BLANK, by the mxc-only
## `mode:"billboard"` path, which is what happens before the bytes land).
func has_sticker_image(key: String) -> bool:
	return _avatars.has(key) and bool(_avatars[key]["has_sticker"])


## Set a participant's nametag from a host-supplied display name (roster row or
## avatar envelope). Empty ⇒ the mxid-localpart fallback.
func set_display_name(key: String, display_name: String) -> void:
	if not _avatars.has(key):
		return
	var label: Variant = _avatars[key]["label"]
	if is_instance_valid(label):
		(label as Label3D).text = nametag_for(key, display_name)


## How a participant chose to appear ("" until the host says).
func mode_of(key: String) -> String:
	return str(_avatars[key]["mode"]) if _avatars.has(key) else ""


## The billboard sticker mxc a participant published ("" when not billboard).
func sticker_of(key: String) -> String:
	return str(_avatars[key]["sticker"]) if _avatars.has(key) else ""


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
