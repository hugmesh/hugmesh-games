extends Node3D

# Shadows of the Net – CHAT: room orchestrator. Branches on the `mode` URL
# query (XR-chat DESIGN §5):
#
#   mode=session (games-portal default) — standard wasm-game contract:
#     hugmesh-shim SD_* postMessage transport (session_bridge.gd),
#     movement channel = seat/pose relay via SOTN_* control-action strings,
#     worldNative kind:'world' = HOST-authoritative room state (seat map +
#     stage grant + media placements) broadcast as {type:'sotn.room', …} — the
#     BFF's Gate 0b already rejects kind:'world' from non-creators, and the BFF
#     pins slot 0 to the session creator (GameSessionService.CREATOR_SLOT), so
#     `slot_index == 0` and "may post kind:'world'" are the SAME set. If the
#     two ever disagree the BFF wins: a 403 on our world POST demotes us (see
#     _on_world_forbidden) instead of looping SD_SESSION_EXPIRED forever.
#   mode=matrix — NO games BFF. All I/O rides the versioned Chat3D
#     postMessage interface (chat3d_bridge.gd); the embedding matrix-ui page
#     owns roster/avatars/media/audio and this scene just renders + relays.
#     The host page queues everything until the game announces `ready`, so
#     _setup_matrix() MUST emit that handshake (chat3d_bridge.emit_ready).
#
# Movement-channel action grammar (session mode; PlayerMovement.action):
#   SOTN_POSE|<x>|<z>|<yaw>          any member, ~10 Hz local pose
#   SOTN_SEAT|<seat>                 seat request → host validates + rebroadcasts
#   SOTN_STAGE|<0|1>                 stage request/release → host arbitrates
#   SOTN_MEDIA|<surfaceId>           surface place/clear toggle → host arbitrates
#
# Every one of those four has a PRODUCER in this file — SEAT via
# _sync_local_seat, STAGE via _on_local_stage_clicked, MEDIA via
# _on_surface_activated, POSE via _on_local_pose. Adding a verb to the grammar
# without one is how the stage grant shipped unreachable. The mirror-image
# defect is a FIELD with no reader: SOTN_POSE used to carry a fifth <seat>
# field that every sender populated and no receiver ever read (see below), so
# the wire advertised an authority the format does not actually have. Parsing
# stays length-TOLERANT (>= 4) so a browser holding a cached older bundle,
# which still appends its seat, keeps working — the extra field is ignored.
#
# TRUST MODEL for the movement channel: the BFF relays a member's action string
# VERBATIM to the whole session, so every field of every SOTN_* action is
# attacker-controlled by any member of the room. Only <x>/<z>/<yaw> are applied
# from the sender (sanitized in avatar_manager.set_pose); seat, stage and media
# placement come EXCLUSIVELY from the host's authoritative world broadcast.
# That is precisely why SOTN_POSE carries no seat: a peer-declared seat MUST
# NOT be honoured (it would let any member teleport onto any anchor 10x/second,
# see test_seat_authority), and every legitimate seat change already travels
# the arbitrated SOTN_SEAT channel via _sync_local_seat. Matrix mode is
# different and keeps its seat argument: there the host PAGE owns the seat map
# and reads it off Chat3D.localPose(x,z,yaw,seat), which is a consumed field.
#
# COROLLARY — every seat change must travel the ARBITRATED channel. Because
# the pose carries no seat at all, a seat change that only ever appears there is
# invisible to the room. local_controller drops the seat to -1 on ANY walk step
# without emitting `seat_clicked`, so `_sync_local_seat` (driven off the pose
# tick) is what turns "I walked away" into a real SOTN_SEAT|-1 release.
#
# XR entry: xr_boot.gd (autoload). The in-scene Enter-VR button is the
# reliable user-gesture path; Chat3D.enterVR() is honoured too but reports
# honestly via vrState(false) when the browser denies the gesture-less request.

enum Mode { SESSION, MATRIX, NATIVE }

const POSE_ACTION := "SOTN_POSE"
const SEAT_ACTION := "SOTN_SEAT"
const STAGE_ACTION := "SOTN_STAGE"
const MEDIA_ACTION := "SOTN_MEDIA"
const WORLD_TYPE := "sotn.room"
const MAX_SEATS := 16
## Minimum interval between authoritative world broadcasts (10 Hz — the rate
## the room already sustains for poses).
##
## AMPLIFICATION: the BFF relays every member's control action to the session,
## so any ONE member can drive _host_assign_seat / _host_set_stage /
## _host_toggle_media at their own send rate, and each call used to produce an
## immediate world POST that the BFF then fanned out to EVERY member — a 1:N
## amplifier with a guest holding the trigger. Coalescing is safe because the
## payload is FULL state, not a delta: a dropped intermediate tick loses
## nothing and the last write still converges everyone.
const WORLD_BROADCAST_INTERVAL := 0.1
## Number of `|`-separated fields a SOTN_POSE must carry to be applied:
## SOTN_POSE|<x>|<z>|<yaw>. Trailing fields are tolerated (see the grammar
## note above) — a legacy sender's <seat> lands past the end and is dropped.
const POSE_MIN_PARTS := 4

var mode: Mode = Mode.SESSION

@onready var _session: Node = $SessionBridge
@onready var _chat3d: Node = $Chat3DBridge
@onready var _avatars: Node3D = $Avatars
@onready var _surfaces: Node3D = $Surfaces
@onready var _seats: Node3D = $Seats
@onready var _stage_anchor: Node3D = $StageAnchor
@onready var _local: Node3D = $DesktopRig
@onready var _xr_rig: XROrigin3D = $XRRig
@onready var _stage_spot: SpotLight3D = $Lights/StageSpot
@onready var _vr_button: Button = $Ui/EnterVrButton
@onready var _mode_label: Label = $Ui/ModeLabel

# ── session-mode room state ──────────────────────────────────────────────────
# The host OWNS these; every guest MIRRORS them from the authoritative world
# broadcast and never writes them from a peer's own movement action.
var _is_host := false
var _seat_by_cid: Dictionary = {}     # characterId(int) → seat(int)
var _placements: Dictionary = {}      # surfaceId(String) → characterId(int)
var _applied_placements: Dictionary = {}  # surfaceId(String) → label(String)
var _stage_cid := -1
var _world_tick := 0
## Host-only: room state changed and a broadcast is owed (see _flush_world).
var _world_dirty := false
var _world_accum := 0.0
## Last local seat we told the host about over the arbitrated SEAT channel.
## -1 == "standing", which is also the launch state, so a fresh room sends
## nothing until the local seat actually changes.
var _local_seat_sent := -1
## Edge-trigger for the local stage grant (see _apply_local_stage).
var _local_on_stage := false

var _local_key := ""


func _ready() -> void:
	mode = _detect_mode()
	_avatars.set("seats_root", _seats)
	_avatars.set("stage_anchor", _stage_anchor)
	_local.set("seats_root", _seats)
	_local.set("surfaces", _surfaces)
	_stage_spot.visible = false
	_spawn_seat_cushions()

	_local.set("stage_platform", $Chamber/StagePlatform)
	_local.connect("pose_changed", _on_local_pose)
	_local.connect("seat_clicked", _on_local_seat_clicked)
	_local.connect("surface_clicked", _on_local_surface_clicked)
	_local.connect("stage_clicked", _on_local_stage_clicked)
	_surfaces.connect("surface_activated", _on_surface_activated)
	XrBoot.xr_state_changed.connect(_on_xr_state)
	_vr_button.pressed.connect(_on_vr_button)

	match mode:
		Mode.SESSION:
			_setup_session()
		Mode.MATRIX:
			_setup_matrix()
		Mode.NATIVE:
			_local_key = "local"
			_avatars.call("ensure", _local_key)
	_mode_label.text = "shadows-of-the-net — %s mode" % Mode.keys()[mode].to_lower()


## One low cushion mesh per seat anchor (kept out of the .tscn — 16 identical
## nodes are cheaper to stamp here than to hand-maintain in the scene file).
func _spawn_seat_cushions() -> void:
	var cushion_mesh := CylinderMesh.new()
	cushion_mesh.top_radius = 0.34
	cushion_mesh.bottom_radius = 0.38
	cushion_mesh.height = 0.42
	var cushion_mat := StandardMaterial3D.new()
	cushion_mat.albedo_color = Color(0.08, 0.09, 0.16)
	cushion_mat.emission_enabled = true
	cushion_mat.emission = Color(0.16, 0.3, 0.4)
	cushion_mat.emission_energy_multiplier = 0.2
	for i in _seats.get_child_count():
		var anchor := _seats.get_child(i) as Node3D
		var cushion := MeshInstance3D.new()
		cushion.name = "Cushion"
		cushion.mesh = cushion_mesh
		cushion.material_override = cushion_mat
		cushion.position = Vector3(0, 0.21, 0)
		anchor.add_child(cushion)


func _detect_mode() -> Mode:
	if not OS.has_feature("web"):
		return Mode.NATIVE
	var raw: Variant = JavaScriptBridge.eval(
		"new URLSearchParams(window.location.search).get('mode') || 'session'")
	return Mode.MATRIX if str(raw) == "matrix" else Mode.SESSION


func _process(delta: float) -> void:
	if mode == Mode.SESSION:
		_flush_world(delta)
	# Keep the Enter-VR affordance honest: web + supported + not already in VR.
	_vr_button.visible = OS.has_feature("web") \
			and XrBoot.web_xr_supported and not XrBoot.xr_active
	# XR rig follows the local pose (v1: no artificial locomotion — the local
	# seat/pose is the play space anchor).
	if XrBoot.xr_active and _local != null:
		_xr_rig.position = Vector3(_local.get("pos").x, 0, _local.get("pos").z)
		_xr_rig.rotation.y = float(_local.get("yaw"))

# ═════════════════════════════════════════════════════════════════════════════
# session mode
# ═════════════════════════════════════════════════════════════════════════════

func _setup_session() -> void:
	_session.connect("movement_received", _on_movement)
	_session.connect("world_received", _on_world)
	_session.connect("session_control_received", _on_session_control)
	_session.connect("world_forbidden", _on_world_forbidden)
	_session.connect("roster_received", _on_session_roster)
	# Slot 0 is the creator's slot — the BFF pins it there on create and
	# refuses it to anyone else on join, so this election agrees with Gate 0b's
	# isCreator() check. _on_world_forbidden is the belt-and-braces path for a
	# session created before that pin existed.
	_is_host = int(_session.get("slot_index")) == 0
	_local_key = str(int(_session.get("character_id")))
	_avatars.set("local_key", _local_key)
	_avatars.call("ensure", _local_key)
	if _is_host:
		# Seed the initial (empty) room state so late guests sync. No longer
		# deferred: _broadcast_world only raises the dirty flag now, and
		# _flush_world does the POST from _process a tick later anyway.
		_broadcast_world()


func _on_movement(payload: Dictionary) -> void:
	var cid := int(payload.get("characterId", -1))
	if cid < 0:
		return
	var action := str(payload.get("action", ""))
	var parts := action.split("|")
	match parts[0]:
		POSE_ACTION:
			if parts.size() >= POSE_MIN_PARTS and str(cid) != _local_key:
				var pose := _parse_pose(parts)
				if pose.is_empty():
					return
				_avatars.call("ensure", str(cid))
				# The SEAT comes from the authoritative map, and from nowhere
				# else — the sender controls every byte of its own action
				# string, so a seat index carried in the pose would let any
				# member teleport onto any seat 10x/second and override the
				# host's arbitration. A legacy sender's trailing field is
				# simply not read.
				_avatars.call("set_pose", str(cid),
						pose[0], pose[1], pose[2], int(_seat_by_cid.get(cid, -1)))
		SEAT_ACTION:
			if _is_host and parts.size() >= 2:
				_host_assign_seat(cid, parts[1].to_int())
		STAGE_ACTION:
			if _is_host and parts.size() >= 2:
				_host_set_stage(cid, parts[1] == "1")
		MEDIA_ACTION:
			if _is_host and parts.size() >= 2:
				_host_toggle_media(cid, parts[1])


## Parse the three peer-supplied pose floats out of a SOTN_POSE action.
## Returns [] (⇒ drop the whole action) when any of them is not finite.
##
## `String.to_float()` is NOT a validator: "1e400" parses to +INF without a
## hint of error, and a single non-finite Vector3 reaching the renderer makes
## Godot assert every frame for the rest of the session AND permanently NaNs
## the smoothing target, so the victim never recovers even from later valid
## poses. Matrix mode already guarded this (chat3d_bridge._arg_f); session mode
## must too — the movement relay is open to every member of the room.
static func _parse_pose(parts: PackedStringArray) -> Array:
	var x := parts[1].to_float()
	var z := parts[2].to_float()
	var yaw := parts[3].to_float()
	if not (is_finite(x) and is_finite(z) and is_finite(yaw)):
		return []
	return [x, z, yaw]


## Host arbitration: first-come seat claims; -1 releases; a taken seat is
## refused (the guest's own pose keeps them free-roaming until re-request).
func _host_assign_seat(cid: int, seat: int) -> void:
	if seat >= MAX_SEATS or seat < -1:
		return
	# A no-op claim must not rebroadcast. `_sync_local_seat` re-affirms the
	# local seat whenever the authoritative echo moves us, so without this a
	# host-driven sit would bounce sit → pose → SEAT → broadcast → sit forever.
	if int(_seat_by_cid.get(cid, -1)) == seat:
		return
	if seat >= 0 and _seat_by_cid.values().has(seat):
		return
	if seat < 0:
		_seat_by_cid.erase(cid)
	else:
		_seat_by_cid[cid] = seat
	_broadcast_world()


## Host arbitration for media surfaces: first-come claim per surface, and only
## the claimant may release it. The surface id is checked against the scene's
## allow-list so a forged action can't invent a node name.
##
## Session mode carries NO image bytes (there is no authenticated media source
## behind the games portal the way matrix-ui fetches Matrix media): a placement
## is the metadata-only slate media_surfaces.place() already renders, labelled
## with the claiming character. Bytes stay a matrix-mode capability.
func _host_toggle_media(cid: int, surface_id: String) -> void:
	if not bool(_surfaces.call("has_surface", surface_id)):
		return
	if _placements.has(surface_id):
		if int(_placements[surface_id]) != cid:
			return
		_placements.erase(surface_id)
	else:
		_placements[surface_id] = cid
	_broadcast_world()


func _host_set_stage(cid: int, wants: bool) -> void:
	if wants and _stage_cid == -1:
		_stage_cid = cid
	elif not wants and _stage_cid == cid:
		_stage_cid = -1
	else:
		return
	_broadcast_world()


## Mark the room state dirty. The POST itself is coalesced — see
## WORLD_BROADCAST_INTERVAL and _flush_world.
func _broadcast_world() -> void:
	_world_dirty = true


## Host-only, driven from _process: emit at most one authoritative broadcast
## per WORLD_BROADCAST_INTERVAL, and only when something actually changed.
func _flush_world(delta: float) -> void:
	if not _is_host:
		return
	_world_accum += delta
	if not _world_dirty or _world_accum < WORLD_BROADCAST_INTERVAL:
		return
	_world_accum = 0.0
	_world_dirty = false
	_send_world_now()


func _send_world_now() -> void:
	_world_tick += 1
	# JSON keys must be strings — cids are stringified and re-parsed on apply.
	var seats := {}
	for cid in _seat_by_cid:
		seats[str(cid)] = _seat_by_cid[cid]
	var placements := {}
	for surface_id in _placements:
		placements[str(surface_id)] = int(_placements[surface_id])
	_session.call("send_world", {"type": WORLD_TYPE, "seats": seats,
			"placements": placements, "stage": _stage_cid, "tick": _world_tick})


func _on_world(payload: Dictionary) -> void:
	if str(payload.get("type", "")) != WORLD_TYPE:
		return
	var seats: Variant = payload.get("seats")
	if seats is Dictionary:
		# Mirror the authoritative seat map locally: _on_movement reads it back
		# so a peer's own SOTN_POSE can never move it off the host's decision.
		if not _is_host:
			_seat_by_cid.clear()
		for cid_key in seats:
			var seat := int(seats[cid_key])
			var key := str(cid_key)
			if not _is_host and seat >= 0 and seat < MAX_SEATS:
				_seat_by_cid[int(str(cid_key))] = seat
			_avatars.call("ensure", key)
			if key == _local_key:
				if seat != int(_local.get("seat")):
					_local.call("sit", seat)
			else:
				var p: Vector3 = _avatars.call("position_of", key)
				_avatars.call("set_pose", key, p.x, p.z, 0.0, seat)
		# A peer the authoritative echo does NOT seat must be un-seated too,
		# otherwise a stale seat (or one the host just refused) keeps them
		# glued to an anchor the host has already handed to someone else.
		for key in _avatars.call("keys"):
			var key_s := str(key)
			if key_s == _local_key or seats.has(key_s):
				continue
			if int(_avatars.call("seat_of", key_s)) >= 0:
				var p2: Vector3 = _avatars.call("position_of", key_s)
				_avatars.call("set_pose", key_s, p2.x, p2.z, 0.0, -1)
		# Authoritative echo does NOT hold a seat for us but we sat locally
		# (optimistic click on a seat the host then refused, or a release we
		# lost the race on) → stand up so we don't overlap the real holder.
		if not _is_host and not seats.has(_local_key) and int(_local.get("seat")) >= 0:
			_local.call("stand")
	var placements: Variant = payload.get("placements")
	if placements is Dictionary:
		_apply_placements(placements)
	var stage_cid := int(payload.get("stage", -1))
	if stage_cid >= 0:
		# The holder may hold no seat and may not have posed yet, so the seats
		# loop above will not have created their rig — and the host only
		# rebroadcasts when its state CHANGES. Without this the holder would
		# render on the floor for the entire grant on every other client.
		_avatars.call("ensure", str(stage_cid))
	for key in _avatars.call("keys"):
		_avatars.call("set_stage", key, str(stage_cid) == str(key))
	_stage_spot.visible = stage_cid >= 0
	if not _is_host:
		_stage_cid = stage_cid
	_apply_local_stage(str(stage_cid) == _local_key)


## Keep the desktop rig — and therefore the third-person camera — with the local
## avatar when a stage grant moves it. `avatar_manager.set_stage` teleports the
## RIG onto the stage anchor and then suppresses pose updates for as long as the
## grant is held, so without this the player would watch their character walk
## off while the camera stayed orbiting an empty patch of floor. Edge-triggered:
## the authoritative echo repeats the same grant on every tick.
func _apply_local_stage(on_stage: bool) -> void:
	if on_stage == _local_on_stage:
		return
	_local_on_stage = on_stage
	if on_stage:
		_local.call("stand_at", _stage_anchor.position)


## Reconcile the room's media surfaces with the authoritative placement map.
## Only diffs are pushed to media_surfaces so a re-broadcast at seat-click rate
## doesn't rebuild eleven materials each tick.
func _apply_placements(placements: Dictionary) -> void:
	var wanted: Dictionary = {}
	for surface_key in placements:
		var surface_id := str(surface_key)
		if not bool(_surfaces.call("has_surface", surface_id)):
			continue  # forged / unknown surface id from a hostile host
		wanted[surface_id] = "shared by #%d" % int(placements[surface_key])
	for surface_id in wanted:
		if str(_applied_placements.get(surface_id, "")) != str(wanted[surface_id]):
			_surfaces.call("place", surface_id,
					{"label": wanted[surface_id]}, PackedByteArray())
	for surface_id in _applied_placements:
		if not wanted.has(surface_id):
			_surfaces.call("clear", surface_id)
	_applied_placements = wanted
	if not _is_host:
		_placements.clear()
		for surface_key in placements:
			var surface_id2 := str(surface_key)
			if wanted.has(surface_id2):
				_placements[surface_id2] = int(placements[surface_key])


## The BFF refused our authoritative broadcast: whatever the launch URL said,
## this client is NOT the session creator. Stand down instead of re-POSTing
## (and instead of letting session_bridge treat it as an expired claim, which
## would loop connect/claimControl on every seat click).
func _on_world_forbidden() -> void:
	if not _is_host:
		return
	push_warning("[sotn] BFF refused kind:'world' — this client is not the session "
			+ "creator; standing down as room host")
	_is_host = false
	_seat_by_cid.clear()
	_placements.clear()
	# We were arbitrating our own seat locally; as a guest it has to be REQUESTED
	# from the real host. Forgetting what we "sent" makes the next pose tick
	# re-announce the current seat over the wire.
	_local_seat_sent = -1
	# Drop the owed broadcast with the state it described.
	#
	# Verified, not assumed: this is INERT today. `_flush_world` returns before
	# it reads the flag when `_is_host` is false, and there is no promotion path
	# — `_is_host` is set true only in `_setup_session`, which runs once from
	# `_ready`; SD_SESSION_EXPIRED makes the host shell re-run connect/
	# claimControl WITHOUT reloading the iframe, SESSION_RESUMED only unpauses
	# the tree, and no host migration exists. It is NOT inert under any future
	# promotion, which is the reason to clear it rather than leave it: a
	# re-promoted client would flush a broadcast describing the maps this
	# function just emptied, i.e. unseat the entire room and revoke every
	# placement on its first tick as host.
	_world_dirty = false
	_world_accum = 0.0


## Reconcile the room against the session's CURRENT membership.
##
## Matrix mode has had this since day one — the host page pushes `roster(json)`
## and `_on_roster` ends with `_avatars.prune(keys)`. Session mode had no
## equivalent: an avatar was created by `ensure()` the first time a characterId
## appeared on the movement channel and was NEVER removed, because no channel
## the iframe receives announces a departure (see session_bridge.request_roster
## for the full audit of why: session-control has no per-member verb, leave/kick
## emit nothing, and the typed WorldState roster on the `world` event is a
## chapter-scoped map that only grows). So `_avatars` filled toward MAX_AVATARS
## with ghosts over a long-lived session and the room then REFUSED real
## newcomers — `upsert()` legitimately declines past the cap.
##
## The seat / placement / stage maps are pruned in the SAME pass, host-only
## (guests mirror all three from the authoritative echo). Not optional: `_on_world`
## re-`ensure`s every cid named in `seats` and in `stage`, so a ghost that still
## held a seat would be re-created on every client at the next broadcast — the
## avatar prune alone would be undone by our own next tick — and the host would
## go on refusing that anchor to everyone else for the life of the room.
func _on_session_roster(character_ids: Array) -> void:
	if character_ids.is_empty():
		# We are ourselves a member, so an empty answer is never a true empty
		# room — it is a body we failed to understand. Treat it as "no fresh
		# roster this tick" rather than deleting every avatar in the chamber.
		return
	var keys: Array = []
	for cid in character_ids:
		keys.append(str(int(cid)))
	# Never prune ourselves: `session_members.character_id` is nullable between
	# join and slot-claim, so our own row can legitimately omit the id the
	# launch URL gave us.
	if _local_key != "" and not keys.has(_local_key):
		keys.append(_local_key)
	_avatars.call("prune", keys)
	if not _is_host:
		return
	var changed := false
	for cid in _seat_by_cid.keys():
		if not keys.has(str(cid)):
			_seat_by_cid.erase(cid)
			changed = true
	for surface_id in _placements.keys():
		if not keys.has(str(int(_placements[surface_id]))):
			_placements.erase(surface_id)
			changed = true
	if _stage_cid >= 0 and not keys.has(str(_stage_cid)):
		_stage_cid = -1
		changed = true
	if changed:
		_broadcast_world()


func _on_session_control(action: String) -> void:
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

# ═════════════════════════════════════════════════════════════════════════════
# matrix mode
# ═════════════════════════════════════════════════════════════════════════════

func _setup_matrix() -> void:
	_chat3d.connect("roster_received", _on_roster)
	_chat3d.connect("avatar_received", _on_avatar)
	_chat3d.connect("pose_received", _on_pose)
	_chat3d.connect("openness_received", _on_openness)
	_chat3d.connect("blendshapes_received", _on_blendshapes)
	_chat3d.connect("speaking_received", _on_speaking)
	_chat3d.connect("expression_received", _on_expression)
	_chat3d.connect("place_media_received", _on_place_media)
	_chat3d.connect("clear_media_received", _on_clear_media)
	_chat3d.connect("stage_received", _on_stage)
	_chat3d.connect("local_user_set", _on_set_local)
	_chat3d.connect("enter_vr_requested", _on_enter_vr_requested)
	# HANDSHAKE — not optional. Chat3dHostComponent queues EVERY outbound
	# envelope (roster/avatar/pose/setLocal/placeMedia/…) until the game
	# announces `ready`, and keeps the Enter-VR button inert until then. Without
	# this call the matrix-mode room renders an empty chamber forever.
	# Emitted only once the signal wiring above is in place so nothing the host
	# flushes in response can be dropped on the floor.
	_chat3d.call("announce_ready")


func _on_roster(list: Array) -> void:
	var keys: Array = []
	for entry in list:
		var user_id := ""
		var display_name := ""
		if entry is String:
			user_id = entry
		elif entry is Dictionary:
			user_id = str(entry.get("userId", ""))
			# The host ships `name` in EVERY roster row and documents it as the
			# nametag source. Building the tag from the mxid localpart and
			# discarding it is why nobody's chosen display name ever showed up.
			display_name = str(entry.get("name", ""))
		if user_id == "":
			continue
		keys.append(user_id)
		_avatars.call("ensure", user_id)
		_avatars.call("set_display_name", user_id, display_name)
	_avatars.call("prune", keys)


func _on_avatar(user_id: String, config: Variant, meta: Dictionary = {}) -> void:
	_avatars.call("upsert", user_id, config, meta)


func _on_pose(user_id: String, x: float, z: float, yaw: float, seat: int) -> void:
	if user_id != _local_key:
		_avatars.call("set_pose", user_id, x, z, yaw, seat)


func _on_openness(user_id: String, value: float) -> void:
	_avatars.call("set_openness", user_id, value)


func _on_blendshapes(user_id: String, weights: PackedByteArray) -> void:
	_avatars.call("set_blendshapes", user_id, weights)


func _on_speaking(user_id: String, value: bool) -> void:
	_avatars.call("set_speaking", user_id, value)


## CONVERGENCE NOTE — `expression` and `stage` are RECEIVE-ONLY here and the
## host currently has no producer for either, which is the same "a receiver
## nobody feeds" defect as the original `ready` handshake bug, in mirror image.
##
## The agreed resolution is to WIRE THE PRODUCERS on the host rather than delete
## the channels, because an honest source exists for both and both are named
## features: `expression` comes from the VN expression signal that already
## drives the 2D stage (matrix-ui `vn/expression.ts` — explicit `vn.expression`
## over the sentiment derivation, the same 10 canonical labels this rig poses),
## and `stage` from the moderator stage-grant surface (`stageModOnly` /
## STAGE_SURFACE_ID in `chat3d-scene.service.ts`). Deleting them would delete
## DESIGN §5 vocabulary that the game can already render today.
##
## So the receivers below STAY, and test_chat3d_convergence pins them: if the
## host lane deletes a channel instead, that pin is the thing that has to be
## deleted with it — not silently left passing.
func _on_expression(user_id: String, label: String) -> void:
	_avatars.call("set_expression", user_id, label)


func _on_place_media(surface_id: String, meta: Dictionary, image_bytes: PackedByteArray) -> void:
	_surfaces.call("place", surface_id, meta, image_bytes)


func _on_clear_media(surface_id: String) -> void:
	_surfaces.call("clear", surface_id)


func _on_stage(user_id: String, value: bool) -> void:
	_avatars.call("set_stage", user_id, value)
	_stage_spot.visible = value or _any_on_stage()
	if user_id == _local_key:
		_apply_local_stage(value)


func _any_on_stage() -> bool:
	# The spot stays lit while ANYONE holds the stage.
	for key in _avatars.call("keys"):
		if _avatars.call("is_on_stage", key):
			return true
	return false


func _on_set_local(user_id: String) -> void:
	_local_key = user_id
	_avatars.set("local_key", user_id)
	_avatars.call("ensure", user_id)


func _on_enter_vr_requested() -> void:
	# May fail without a forwarded user gesture — xr_boot reports honestly.
	if not XrBoot.enter_vr():
		_chat3d.call("emit_vr_state", false)

# ═════════════════════════════════════════════════════════════════════════════
# local pose / clicks → active transport
# ═════════════════════════════════════════════════════════════════════════════

func _on_local_pose(x: float, z: float, yaw: float, seat: int) -> void:
	if _local_key != "":
		_avatars.call("set_pose", _local_key, x, z, yaw, seat)
	match mode:
		Mode.SESSION:
			# The seat travels the ARBITRATED channel (_sync_local_seat), never
			# the pose: no receiver may honour a peer-declared seat, so
			# shipping one would be a field with no reader.
			_sync_local_seat(seat)
			_session.call("send_control",
					"%s|%.2f|%.2f|%.2f" % [POSE_ACTION, x, z, yaw])
		Mode.MATRIX:
			# Matrix mode has no in-game arbiter: the host page owns the seat
			# map and reads it straight off localPose, so no separate channel.
			_chat3d.call("emit_local_pose", x, z, yaw, seat)


func _on_local_seat_clicked(seat: int) -> void:
	if mode == Mode.SESSION:
		_sync_local_seat(seat)


## Take / release the stage. This is the ONLY producer of SOTN_STAGE: the host
## side (`_host_set_stage`) and the whole `stage` leg of the world payload
## shipped with no sender at all, so the spotlight could never light and DESIGN
## §5's "stage grants" half of the session-mode world state was dead geometry.
##
## Matrix mode is deliberately excluded: there the host page owns stage grants
## and the game's OUT vocabulary is localPose / mediaActivate / vrState only —
## inventing a `stageRequest` emit would be a unilateral protocol change.
func _on_local_stage_clicked() -> void:
	if mode != Mode.SESSION:
		return
	var cid := int(_session.get("character_id"))
	var wants := _stage_cid != cid       # click toggles; -1 means nobody holds it
	if _is_host:
		_host_set_stage(cid, wants)
	else:
		_session.call("send_control", "%s|%d" % [STAGE_ACTION, 1 if wants else 0])


## Push a CHANGE of the local seat onto the arbitrated SEAT channel.
##
## SOTN_POSE deliberately carries no seat (see the trust model at the top —
## no receiver may honour a peer-declared one), so a seat change that never
## reaches the host is invisible to the whole room. Clicking a seat is not the
## only way to change it: local_controller._process_desktop sets `seat = -1` on
## ANY walk step and emits only `pose_changed`, and `_on_world` calls
## sit()/stand() on us when the host arbitrates. Driving this off the pose tick
## therefore covers every source.
## Without it, walking off a seat left the walker glued to their anchor
## on every OTHER client and leaked the seat in `_seat_by_cid` permanently —
## the host kept refusing that seat to everyone else for the rest of the room.
##
## Only transitions are sent (the pose tick runs at 10 Hz); `_host_assign_seat`
## additionally drops no-op claims so the echo can't feed back into itself.
func _sync_local_seat(seat: int) -> void:
	if seat == _local_seat_sent:
		return
	_local_seat_sent = seat
	if not _is_host:
		_session.call("send_control", "%s|%d" % [SEAT_ACTION, seat])
		return
	var cid := int(_session.get("character_id"))
	_host_assign_seat(cid, seat)
	# The host's own claim goes through the SAME arbitration as everyone
	# else's, and can be refused when a guest already holds that anchor. A
	# guest learns this from the authoritative echo (`_on_world` stands us up
	# when the map omits us) but the host never receives that branch, so
	# without this it would stay visually seated on top of the real holder.
	if seat >= 0 and int(_seat_by_cid.get(cid, -1)) != seat:
		_local.call("stand")
		_local_seat_sent = -1


func _on_local_surface_clicked(surface_id: String) -> void:
	_surfaces.call("activate", surface_id)


func _on_surface_activated(surface_id: String) -> void:
	match mode:
		Mode.MATRIX:
			_chat3d.call("emit_media_activate", surface_id)
		Mode.SESSION:
			# DESIGN §5: session-mode placements live in the host-authoritative
			# world state. The host applies its own click directly; everyone
			# else asks over the movement channel and waits for the echo.
			if _is_host:
				_host_toggle_media(int(_session.get("character_id")), surface_id)
			else:
				_session.call("send_control", "%s|%s" % [MEDIA_ACTION, surface_id])


func _on_xr_state(active: bool) -> void:
	if _local_key != "":
		_avatars.call("set_visible_for", _local_key, not active)
	if mode == Mode.MATRIX:
		_chat3d.call("emit_vr_state", active)


func _on_vr_button() -> void:
	XrBoot.enter_vr()
