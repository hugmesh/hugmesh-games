# Local player body for a produced RPG bundle (hugmesh-rpg-builder).
#
# Ported from three-wizards-in-a-trenchcoat/player_controller.gd movement core,
# re-indented to 4 spaces. Driven by the AUTHORITATIVE SSE echo from the sibling
# HostBridge node (game_bridge.gd POSTs a control -> BFF echoes a movement event
# -> HostBridge.movement_received), NOT directly by keys — so the local body and
# every other client stay consistent. Movement events are FILTERED to this
# client's own character id; remote_avatars.gd drives everyone else's body.
#
# Movement is in WORLD axes (MOVE_FORWARD = -Z = the "N" portal wall) so walking
# lines up with the baked portal directions.
extends CharacterBody3D

const AvatarConfig := preload("res://scripts/avatar_config.gd")
const AvatarFactory := preload("res://scripts/avatar_factory.gd")
const AvatarPoser := preload("res://scripts/avatar_poser.gd")

const SPEED := 5.0
const JUMP_VELOCITY := 6.0
const GRAVITY := 14.0

var _host_bridge: Node = null
var _dir := Vector3.ZERO
var _pending_jump := false
var _local_cid := -1

func _ready() -> void:
    add_to_group("local_player")
    _build_body()
    _build_camera()
    _host_bridge = get_parent().get_node_or_null("HostBridge")
    if _host_bridge != null:
        _host_bridge.movement_received.connect(_on_movement)
        _local_cid = int(_host_bridge.local_character_id())
    else:
        push_warning("[player] HostBridge sibling missing — input won't drive the player")

var _poser = null

func _build_body() -> void:
    # Physics capsule stays here; the visuals come from the baked
    # per-project avatar config (hue_shift 0.0 = the exact configured
    # hero colours — remote_avatars applies per-cid hue shifts instead).
    var col := CollisionShape3D.new()
    var cap := CapsuleShape3D.new()
    cap.radius = 0.4
    cap.height = 1.6
    col.shape = cap
    col.position = Vector3(0, 0.9, 0)
    add_child(col)
    var avatar := AvatarFactory.build(AvatarConfig.AVATAR, 0.0)
    add_child(avatar)
    # The poser walks the rig's joints, so it must attach AFTER the tree exists.
    _poser = AvatarPoser.new()
    _poser.setup(avatar)

func _build_camera() -> void:
    var pivot := Node3D.new()
    pivot.name = "CameraPivot"
    pivot.position = Vector3(0, 1.5, 0)
    add_child(pivot)
    var cam := Camera3D.new()
    cam.name = "Camera3D"
    cam.position = Vector3(0, 4.0, 9.0)
    cam.rotation = Vector3(deg_to_rad(-22.0), 0.0, 0.0)
    pivot.add_child(cam)

func _on_movement(payload_json: String) -> void:
    var parsed: Variant = JSON.parse_string(payload_json)
    if not (parsed is Dictionary):
        return
    var state: Dictionary = parsed
    # Drop echoes for OTHER characters — otherwise every session member would
    # drive this body (CRITIQUE mustFix). remote_avatars.gd handles them.
    if state.has("characterId") and _local_cid >= 0 and int(state.get("characterId", -1)) != _local_cid:
        return
    if not state.has("action") or state["action"] == null:
        return
    var pressed: bool = state.get("pressed", false)
    match state["action"]:
        "MOVE_FORWARD":  _dir.z = -1.0 if pressed else 0.0
        "MOVE_BACKWARD": _dir.z =  1.0 if pressed else 0.0
        "MOVE_LEFT":     _dir.x = -1.0 if pressed else 0.0
        "MOVE_RIGHT":    _dir.x =  1.0 if pressed else 0.0
        "JUMP":          if pressed: _pending_jump = true

var _telemetry_frames := 0

func _physics_process(delta: float) -> void:
    # 2s-interval body telemetry: the HUD POS mirrors SERVER echo state, so a
    # body silently falling/teleporting is invisible without this print.
    _telemetry_frames += 1
    if _telemetry_frames % 120 == 0:
        print("[player] pos=%s floor=%s room=%s" % [global_position,
            is_on_floor(), str(get_parent().get("current_room") != null)])
    if not is_on_floor():
        velocity.y -= GRAVITY * delta
    if _pending_jump and is_on_floor():
        velocity.y = JUMP_VELOCITY
        _pending_jump = false
    var move := _dir
    if move.length() > 0.001:
        move = move.normalized()
    velocity.x = move.x * SPEED
    velocity.z = move.z * SPEED
    move_and_slide()
    # Fed the POST-move_and_slide velocity: that is the speed actually
    # achieved, so walking into a wall stops the legs instead of moonwalking.
    if _poser != null:
        _poser.update(delta, velocity)

# Called by world.gd on every room transition to drop the body at the arrival
# doorway (offset inward beyond the reciprocal portal so it doesn't bounce back).
func teleport_to(pos: Vector3) -> void:
    global_position = pos
    velocity = Vector3.ZERO
    _dir = Vector3.ZERO
