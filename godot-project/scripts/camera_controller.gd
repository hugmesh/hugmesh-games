extends Node3D  # CameraPivot — child of Player, positioned at eye height

const ZOOM_SPEED   := 0.5
const ZOOM_MIN     := 0.0
const ZOOM_MAX     := 12.0
const FP_THRESHOLD := 0.5   # arm length at which we snap to first-person
const MOUSE_SENS   := 0.003
const PITCH_MIN    := deg_to_rad(-80.0)
const PITCH_MAX    := deg_to_rad(25.0)

var _arm   := 5.0
var _yaw   := 0.0
var _pitch := deg_to_rad(15.0)  # positive = camera above looking down

@onready var _cam: Camera3D = $Camera3D

func _ready() -> void:
	call_deferred("_update")

func _unhandled_input(event: InputEvent) -> void:
	if event is InputEventMouseButton:
		if event.button_index == MOUSE_BUTTON_WHEEL_UP:
			_arm = clamp(_arm - ZOOM_SPEED, ZOOM_MIN, ZOOM_MAX)
			_update()
		elif event.button_index == MOUSE_BUTTON_WHEEL_DOWN:
			_arm = clamp(_arm + ZOOM_SPEED, ZOOM_MIN, ZOOM_MAX)
			_update()
	elif event is InputEventMouseMotion:
		if Input.is_mouse_button_pressed(MOUSE_BUTTON_LEFT):
			_yaw   -= event.relative.x * MOUSE_SENS
			_pitch  = clamp(_pitch + event.relative.y * MOUSE_SENS, PITCH_MIN, PITCH_MAX)
			_update()

func _update() -> void:
	# Rotate the player body so WASD always moves relative to the camera direction
	get_parent().rotation.y = _yaw

	if _arm <= FP_THRESHOLD:
		# First-person: camera sits at pivot origin (eye height), pitch drives vertical look
		_cam.position = Vector3.ZERO
		_cam.rotation = Vector3(_pitch, 0.0, 0.0)
	else:
		# Third-person: offset camera along the arm, pitched up/down
		_cam.position = Vector3(0.0, sin(_pitch) * _arm, cos(_pitch) * _arm)
		_cam.look_at(global_position, Vector3.UP)
