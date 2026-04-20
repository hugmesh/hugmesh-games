extends CharacterBody3D

const SPEED        := 5.0
const JUMP_VELOCITY := 4.5
const GRAVITY      := 9.8

var _game_bridge: Node = null
var _sse_dir    := Vector3.ZERO
var _pending_jump := false

func _ready() -> void:
	_game_bridge = get_node_or_null("/root/Cave/GameBridge")
	if _game_bridge:
		_game_bridge.state_updated.connect(_on_state_updated)
	else:
		push_warning("PlayerController: GameBridge not found at /root/Cave/GameBridge")

func _physics_process(delta: float) -> void:
	if not is_on_floor():
		velocity.y -= GRAVITY * delta

	if _pending_jump and is_on_floor():
		velocity.y = JUMP_VELOCITY
		_pending_jump = false

	# Transform local-space direction into world space so forward always
	# matches whichever way the camera (and therefore the player body) is facing.
	var world_move := transform.basis * Vector3(_sse_dir.x, 0.0, _sse_dir.z) * SPEED
	velocity.x = world_move.x
	velocity.z = world_move.z
	move_and_slide()

func _on_state_updated(state: Dictionary) -> void:
	if not state.has("action") or state["action"] == null:
		return
	var pressed: bool = state.get("pressed", false)
	match state["action"]:
		"MOVE_FORWARD":  _sse_dir.z = -1.0 if pressed else 0.0
		"MOVE_BACKWARD": _sse_dir.z =  1.0 if pressed else 0.0
		"MOVE_LEFT":     _sse_dir.x = -1.0 if pressed else 0.0
		"MOVE_RIGHT":    _sse_dir.x =  1.0 if pressed else 0.0
		"JUMP":          if pressed: _pending_jump = true
		"CAST_SPELL_1":  _cast_spell(1)
		"CAST_SPELL_2":  _cast_spell(2)
		"CAST_SPELL_3":  _cast_spell(3)

func _cast_spell(slot: int) -> void:
	print("Player casting spell from slot: ", slot)
