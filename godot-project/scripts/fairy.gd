extends Node3D

@export var float_speed := 0.7
@export var float_height := 0.5
@export var drift_radius := 1.2

var _origin: Vector3
var _t := 0.0
var _phase := 0.0

func _ready() -> void:
	_origin = position
	_phase = randf() * TAU

func _process(delta: float) -> void:
	_t += delta
	position.y = _origin.y + sin(_t * float_speed + _phase) * float_height
	position.x = _origin.x + sin(_t * 0.31 + _phase) * drift_radius
	position.z = _origin.z + cos(_t * 0.23 + _phase * 0.8) * drift_radius
