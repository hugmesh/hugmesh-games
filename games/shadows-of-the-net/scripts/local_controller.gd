extends Node3D

# Desktop (non-XR) fallback controls + local pose authority.
#
#   WASD / arrows  walk the local avatar on the chamber floor
#   right-drag     orbit the third-person camera
#   wheel          zoom
#   left-click     surface → activate | seat → sit | floor → walk target
#
# The node owns the LOCAL participant's pose (x, z, yaw, seat) in every mode;
# room_controller.gd relays pose_changed to the active transport (session
# movement channel or Chat3D.localPose) throttled to ~10 Hz. In XR the desktop
# camera is disabled and yaw follows the headset instead (v1: XR users stay
# at their seat — no artificial-locomotion pass yet).

signal pose_changed(x: float, z: float, yaw: float, seat: int)
signal seat_clicked(seat: int)
signal surface_clicked(surface_id: String)
## The player clicked the stage platform — a request to take (or release) the
## stage. Arbitration is NOT local: room_controller relays it and waits for the
## authoritative grant, exactly like a seat claim.
signal stage_clicked()

const WALK_SPEED := 2.6
const ROOM_RADIUS := 8.6
const SEAT_SNAP_DIST := 1.1
const EMIT_INTERVAL := 0.1   # 10 Hz
## Click radius of the stage platform disc (scenes/room.tscn StagePlatform is a
## CylinderMesh with top_radius 1.6 / bottom_radius 1.7).
const STAGE_CLICK_RADIUS := 1.7

var seats_root: Node3D = null
var surfaces: Node3D = null
## The StagePlatform mesh, injected by room_controller (same pattern as
## seats_root / surfaces). Null ⇒ the stage is simply not clickable.
var stage_platform: Node3D = null

var pos := Vector3(0, 0, 3.5)
var yaw := PI            # facing the room center from +Z
var seat := -1

var _cam_yaw := PI
var _cam_pitch := 0.35
var _cam_dist := 4.0
var _dragging := false
var _emit_accum := 0.0
var _dirty := true
var _walk_target: Vector3 = Vector3.INF

@onready var _camera: Camera3D = $Camera3D


func _ready() -> void:
	XrBoot.xr_state_changed.connect(_on_xr_state)
	_update_camera()


func _on_xr_state(active: bool) -> void:
	_camera.current = not active


func _process(delta: float) -> void:
	if not XrBoot.xr_active:
		_process_desktop(delta)
	_emit_accum += delta
	if _dirty and _emit_accum >= EMIT_INTERVAL:
		_emit_accum = 0.0
		_dirty = false
		pose_changed.emit(pos.x, pos.z, yaw, seat)


func _process_desktop(delta: float) -> void:
	var dir := Vector2.ZERO
	if Input.is_key_pressed(KEY_W) or Input.is_key_pressed(KEY_UP):
		dir.y -= 1
	if Input.is_key_pressed(KEY_S) or Input.is_key_pressed(KEY_DOWN):
		dir.y += 1
	if Input.is_key_pressed(KEY_A) or Input.is_key_pressed(KEY_LEFT):
		dir.x -= 1
	if Input.is_key_pressed(KEY_D) or Input.is_key_pressed(KEY_RIGHT):
		dir.x += 1

	if dir != Vector2.ZERO:
		# Key movement is camera-relative; any step stands up + cancels click-walk.
		seat = -1
		_walk_target = Vector3.INF
		var forward := Vector3(sin(_cam_yaw), 0, cos(_cam_yaw)) * -1.0
		var right := Vector3(-forward.z, 0, forward.x)
		var step := (forward * -dir.y + right * dir.x).normalized() * WALK_SPEED * delta
		_move_to(pos + step)
		yaw = atan2(step.x, step.z) if step.length() > 0.0001 else yaw
		_dirty = true
	elif _walk_target != Vector3.INF:
		var to_target := _walk_target - pos
		to_target.y = 0
		if to_target.length() < 0.05:
			_walk_target = Vector3.INF
		else:
			var step := to_target.normalized() * minf(WALK_SPEED * delta, to_target.length())
			_move_to(pos + step)
			yaw = atan2(step.x, step.z)
			_dirty = true
	_update_camera()


func _move_to(next: Vector3) -> void:
	next.y = 0
	# Stay inside the chamber and outside the center table.
	var r := Vector2(next.x, next.z).length()
	if r > ROOM_RADIUS:
		next = next * (ROOM_RADIUS / r)
	elif r < 2.6:
		next = next * (2.6 / maxf(r, 0.001))
	pos = next


func _update_camera() -> void:
	var eye := pos + Vector3(0, 1.5, 0)
	var offset := Vector3(
		sin(_cam_yaw) * cos(_cam_pitch),
		sin(_cam_pitch),
		cos(_cam_yaw) * cos(_cam_pitch)) * _cam_dist
	_camera.position = eye + offset
	_camera.look_at(eye, Vector3.UP)


func _unhandled_input(event: InputEvent) -> void:
	if XrBoot.xr_active:
		return
	if event is InputEventMouseButton:
		var mb := event as InputEventMouseButton
		if mb.button_index == MOUSE_BUTTON_RIGHT:
			_dragging = mb.pressed
		elif mb.button_index == MOUSE_BUTTON_WHEEL_UP and mb.pressed:
			_cam_dist = maxf(1.6, _cam_dist - 0.4)
		elif mb.button_index == MOUSE_BUTTON_WHEEL_DOWN and mb.pressed:
			_cam_dist = minf(9.0, _cam_dist + 0.4)
		elif mb.button_index == MOUSE_BUTTON_LEFT and mb.pressed:
			_handle_click(mb.position)
	elif event is InputEventMouseMotion and _dragging:
		var mm := event as InputEventMouseMotion
		_cam_yaw -= mm.relative.x * 0.008
		_cam_pitch = clampf(_cam_pitch + mm.relative.y * 0.006, -0.2, 1.2)
		_update_camera()


func _handle_click(screen_pos: Vector2) -> void:
	var origin := _camera.project_ray_origin(screen_pos)
	var dir := _camera.project_ray_normal(screen_pos)

	# 1. Media surface?
	if surfaces != null:
		var surface_id: String = surfaces.call("surface_at_ray", origin, dir)
		if surface_id != "":
			surface_clicked.emit(surface_id)
			return

	# 2. Floor plane intersection → seat snap or walk target.
	if absf(dir.y) < 0.0001:
		return
	var t := -origin.y / dir.y
	if t <= 0.0:
		return
	var hit := origin + dir * t
	var nearest := -1
	var nearest_d := SEAT_SNAP_DIST
	if seats_root != null:
		for i in seats_root.get_child_count():
			var anchor := seats_root.get_child(i) as Node3D
			var d := Vector2(hit.x - anchor.position.x, hit.z - anchor.position.z).length()
			if d < nearest_d:
				nearest_d = d
				nearest = i
	if nearest >= 0:
		sit(nearest)
		seat_clicked.emit(nearest)
	elif _hit_stage(hit):
		# Request only — the grant comes back through the authoritative echo
		# (session) or the host page (matrix), never from this click.
		stage_clicked.emit()
	elif Vector2(hit.x, hit.z).length() <= ROOM_RADIUS:
		_walk_target = Vector3(hit.x, 0, hit.z)


## Did the floor click land on the stage platform disc? Tested AFTER the seat
## snap so the seat ring (which the stage sits just outside of) keeps priority.
func _hit_stage(hit: Vector3) -> bool:
	if stage_platform == null:
		return false
	var c := stage_platform.position
	return Vector2(hit.x - c.x, hit.z - c.z).length() <= STAGE_CLICK_RADIUS


## Snap the local pose onto a seat anchor (also used when the host assigns us).
func sit(seat_index: int) -> void:
	if seats_root == null or seat_index < 0 or seat_index >= seats_root.get_child_count():
		return
	var anchor := seats_root.get_child(seat_index) as Node3D
	seat = seat_index
	_walk_target = Vector3.INF
	pos = Vector3(anchor.position.x, 0, anchor.position.z)
	yaw = atan2(-anchor.position.x, -anchor.position.z)
	_dirty = true
	_update_camera()


func stand() -> void:
	seat = -1
	_dirty = true


## Place the rig at an explicit point — used when the stage grant lands on us.
##
## avatar_manager.set_stage() teleports the local AVATAR onto the stage anchor
## and then suppresses pose updates while the grant is held. Without moving the
## desktop rig too, the third-person camera would stay behind orbiting an empty
## patch of floor while the player's character stood across the room. Leaving
## the seat is part of taking the stage, so this stands us up as well.
func stand_at(point: Vector3) -> void:
	seat = -1
	_walk_target = Vector3.INF
	pos = Vector3(point.x, 0, point.z)
	yaw = atan2(-point.x, -point.z)
	_dirty = true
	_update_camera()
