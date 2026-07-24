extends Node3D

# Shadows of the Net – CHAT: room orchestrator. Branches on the `mode` URL
# query (XR-chat DESIGN §5):
#
#   mode=session (games-portal default) — standard wasm-game contract:
#     hugmesh-shim SD_* postMessage transport (session_bridge.gd),
#     movement channel = seat/pose relay via SOTN_* control-action strings,
#     worldNative kind:'world' = HOST-authoritative room state (seat map +
#     stage grant) broadcast as {type:'sotn.room', …} — the BFF's Gate 0b
#     already rejects kind:'world' from non-creators, so slot 0 is the host.
#   mode=matrix — NO games BFF. All I/O rides the versioned Chat3D
#     postMessage interface (chat3d_bridge.gd); the embedding matrix-ui page
#     owns roster/avatars/media/audio and this scene just renders + relays.
#
# Movement-channel action grammar (session mode; PlayerMovement.action):
#   SOTN_POSE|<x>|<z>|<yaw>|<seat>   any member, ~10 Hz local pose
#   SOTN_SEAT|<seat>                 seat request → host validates + rebroadcasts
#   SOTN_STAGE|<0|1>                 stage request/release → host arbitrates
#
# XR entry: xr_boot.gd (autoload). The in-scene Enter-VR button is the
# reliable user-gesture path; Chat3D.enterVR() is honoured too but reports
# honestly via vrState(false) when the browser denies the gesture-less request.

enum Mode { SESSION, MATRIX, NATIVE }

const POSE_ACTION := "SOTN_POSE"
const SEAT_ACTION := "SOTN_SEAT"
const STAGE_ACTION := "SOTN_STAGE"
const WORLD_TYPE := "sotn.room"

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

# ── session-mode host state (authoritative only on the host) ─────────────────
var _is_host := false
var _seat_by_cid: Dictionary = {}   # characterId(int) → seat(int)
var _stage_cid := -1
var _world_tick := 0

var _local_key := ""


func _ready() -> void:
	mode = _detect_mode()
	_avatars.set("seats_root", _seats)
	_avatars.set("stage_anchor", _stage_anchor)
	_local.set("seats_root", _seats)
	_local.set("surfaces", _surfaces)
	_stage_spot.visible = false
	_spawn_seat_cushions()

	_local.connect("pose_changed", _on_local_pose)
	_local.connect("seat_clicked", _on_local_seat_clicked)
	_local.connect("surface_clicked", _on_local_surface_clicked)
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


func _process(_delta: float) -> void:
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
	_is_host = int(_session.get("slot_index")) == 0
	_local_key = str(int(_session.get("character_id")))
	_avatars.set("local_key", _local_key)
	_avatars.call("ensure", _local_key)
	if _is_host:
		# Seed + broadcast the initial (empty) room state so late guests sync.
		_broadcast_world.call_deferred()


func _on_movement(payload: Dictionary) -> void:
	var cid := int(payload.get("characterId", -1))
	if cid < 0:
		return
	var action := str(payload.get("action", ""))
	var parts := action.split("|")
	match parts[0]:
		POSE_ACTION:
			if parts.size() >= 5 and str(cid) != _local_key:
				_avatars.call("ensure", str(cid))
				_avatars.call("set_pose", str(cid),
						parts[1].to_float(), parts[2].to_float(),
						parts[3].to_float(), parts[4].to_int())
		SEAT_ACTION:
			if _is_host and parts.size() >= 2:
				_host_assign_seat(cid, parts[1].to_int())
		STAGE_ACTION:
			if _is_host and parts.size() >= 2:
				_host_set_stage(cid, parts[1] == "1")


## Host arbitration: first-come seat claims; -1 releases; a taken seat is
## refused (the guest's own pose keeps them free-roaming until re-request).
func _host_assign_seat(cid: int, seat: int) -> void:
	if seat >= 16 or seat < -1:
		return
	if seat >= 0 and _seat_by_cid.values().has(seat) and _seat_by_cid.get(cid, -1) != seat:
		return
	if seat < 0:
		_seat_by_cid.erase(cid)
	else:
		_seat_by_cid[cid] = seat
	_broadcast_world()


func _host_set_stage(cid: int, wants: bool) -> void:
	if wants and _stage_cid == -1:
		_stage_cid = cid
	elif not wants and _stage_cid == cid:
		_stage_cid = -1
	else:
		return
	_broadcast_world()


func _broadcast_world() -> void:
	_world_tick += 1
	# JSON keys must be strings — cids are stringified and re-parsed on apply.
	var seats := {}
	for cid in _seat_by_cid:
		seats[str(cid)] = _seat_by_cid[cid]
	_session.call("send_world",
			{"type": WORLD_TYPE, "seats": seats, "stage": _stage_cid, "tick": _world_tick})


func _on_world(payload: Dictionary) -> void:
	if str(payload.get("type", "")) != WORLD_TYPE:
		return
	var seats: Variant = payload.get("seats")
	if seats is Dictionary:
		for cid_key in seats:
			var seat := int(seats[cid_key])
			var key := str(cid_key)
			_avatars.call("ensure", key)
			if key == _local_key:
				if seat != int(_local.get("seat")):
					_local.call("sit", seat)
			else:
				var p: Vector3 = _avatars.call("position_of", key)
				_avatars.call("set_pose", key, p.x, p.z, 0.0, seat)
		# Authoritative echo does NOT hold a seat for us but we sat locally
		# (optimistic click on a seat the host then refused, or a release we
		# lost the race on) → stand up so we don't overlap the real holder.
		if not _is_host and not seats.has(_local_key) and int(_local.get("seat")) >= 0:
			_local.call("stand")
	var stage_cid := int(payload.get("stage", -1))
	for key in _avatars.call("keys"):
		_avatars.call("set_stage", key, str(stage_cid) == str(key))
	_stage_spot.visible = stage_cid >= 0
	if not _is_host:
		_stage_cid = stage_cid


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


func _on_roster(list: Array) -> void:
	var keys: Array = []
	for entry in list:
		var user_id := ""
		if entry is String:
			user_id = entry
		elif entry is Dictionary:
			user_id = str(entry.get("userId", ""))
		if user_id == "":
			continue
		keys.append(user_id)
		_avatars.call("ensure", user_id)
	_avatars.call("prune", keys)


func _on_avatar(user_id: String, config: Dictionary) -> void:
	_avatars.call("upsert", user_id, config)


func _on_pose(user_id: String, x: float, z: float, yaw: float, seat: int) -> void:
	if user_id != _local_key:
		_avatars.call("set_pose", user_id, x, z, yaw, seat)


func _on_openness(user_id: String, value: float) -> void:
	_avatars.call("set_openness", user_id, value)


func _on_blendshapes(user_id: String, weights: PackedByteArray) -> void:
	_avatars.call("set_blendshapes", user_id, weights)


func _on_speaking(user_id: String, value: bool) -> void:
	_avatars.call("set_speaking", user_id, value)


func _on_expression(user_id: String, label: String) -> void:
	_avatars.call("set_expression", user_id, label)


func _on_place_media(surface_id: String, meta: Dictionary, png: PackedByteArray) -> void:
	_surfaces.call("place", surface_id, meta, png)


func _on_clear_media(surface_id: String) -> void:
	_surfaces.call("clear", surface_id)


func _on_stage(user_id: String, value: bool) -> void:
	_avatars.call("set_stage", user_id, value)
	_stage_spot.visible = value or _any_on_stage()


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
			_session.call("send_control",
					"%s|%.2f|%.2f|%.2f|%d" % [POSE_ACTION, x, z, yaw, seat])
		Mode.MATRIX:
			_chat3d.call("emit_local_pose", x, z, yaw, seat)


func _on_local_seat_clicked(seat: int) -> void:
	if mode == Mode.SESSION:
		if _is_host:
			_host_assign_seat(int(_session.get("character_id")), seat)
		else:
			_session.call("send_control", "%s|%d" % [SEAT_ACTION, seat])


func _on_local_surface_clicked(surface_id: String) -> void:
	_surfaces.call("activate", surface_id)


func _on_surface_activated(surface_id: String) -> void:
	if mode == Mode.MATRIX:
		_chat3d.call("emit_media_activate", surface_id)


func _on_xr_state(active: bool) -> void:
	if _local_key != "":
		_avatars.call("set_visible_for", _local_key, not active)
	if mode == Mode.MATRIX:
		_chat3d.call("emit_vr_state", active)


func _on_vr_button() -> void:
	XrBoot.enter_vr()
