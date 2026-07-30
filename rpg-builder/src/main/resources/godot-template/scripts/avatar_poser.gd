# Procedural animation for a rig built by avatar_factory.gd.
#
# There is no AnimationPlayer, no AnimationTree and no imported clip anywhere in
# this pipeline — the rig is rigid segments on plain Node3D joints, so motion is
# just rotating those joints per frame. That keeps the whole feature asset-free:
# nothing to author, nothing to import, and it works identically for the local
# hero and for every remote avatar (decision 028).
#
# Layers, applied in order over the rest pose:
#   1. idle    — breathing + a little arm sway, always on
#   2. locomotion — leg/arm counter-swing keyed to DISTANCE TRAVELLED, so the
#                   cycle speeds up with the character instead of sliding
#   3. gesture — a timed one-shot that overrides the arms
#   4. expression — the baked EXPRESSIONS table on the five face joints
#
# Every joint lookup is guarded. A produced bundle whose rig lacks a joint must
# degrade to a stiffer avatar, never crash the game.
extends RefCounted

const AvatarConfig = preload("res://scripts/avatar_config.gd")

# Whitelist — a gesture name arrives over the network, so it is matched against
# this and never used to build a node path or anything else executable.
const GESTURES := ["wave", "point", "nod", "bow", "clap", "shrug", "cheer", "dance"]

const WALK_CYCLE_SPEED := 2.4      # radians of phase per metre travelled
const WALK_MIN_SPEED := 0.15       # m/s below which the character is "standing"
const GESTURE_SECONDS := 1.6

var _joints := {}
var _rest := {}
var _phase := 0.0
var _time := 0.0
var _gesture := ""
var _gesture_left := 0.0
var _expression := "neutral"


# Point the poser at the Node3D returned by AvatarFactory.build().
func setup(avatar_root: Node3D) -> void:
    _joints.clear()
    _rest.clear()
    if avatar_root == null:
        return
    _collect(avatar_root)


func _collect(node: Node) -> void:
    if node is Node3D and not (node is MeshInstance3D):
        var n3 := node as Node3D
        _joints[n3.name] = n3
        _rest[n3.name] = {"pos": n3.position, "rot": n3.rotation, "scale": n3.scale}
    for child in node.get_children():
        _collect(child)


func play_gesture(name: String) -> void:
    if not GESTURES.has(name):
        return
    _gesture = name
    _gesture_left = GESTURE_SECONDS


func set_expression(name: String) -> void:
    if AvatarConfig.EXPRESSIONS.has(name) or name == "neutral":
        _expression = name


func is_gesturing() -> bool:
    return _gesture != ""


func update(delta: float, velocity: Vector3) -> void:
    if _joints.is_empty():
        return
    _time += delta

    var speed := Vector2(velocity.x, velocity.z).length()
    # Phase advances with distance, not time, so a slow walk and a run share one
    # cycle definition and the feet never appear to skate.
    _phase += speed * delta * WALK_CYCLE_SPEED

    if _gesture != "":
        _gesture_left -= delta
        if _gesture_left <= 0.0:
            _gesture = ""

    _reset()
    _apply_idle()
    if speed > WALK_MIN_SPEED:
        _apply_locomotion(speed)
    if _gesture != "":
        _apply_gesture()
    _apply_expression()


func _reset() -> void:
    for name in _joints:
        var node: Node3D = _joints[name]
        if not is_instance_valid(node):
            continue
        var rest: Dictionary = _rest[name]
        node.position = rest["pos"]
        node.rotation = rest["rot"]
        node.scale = rest["scale"]


func _joint(name: String) -> Node3D:
    var node = _joints.get(name)
    return node if node != null and is_instance_valid(node) else null


func _rot(name: String, euler: Vector3) -> void:
    var node := _joint(name)
    if node != null:
        node.rotation = _rest[name]["rot"] + euler


func _apply_idle() -> void:
    var breath := sin(_time * 1.6)
    var chest := _joint("Chest")
    if chest != null:
        chest.scale = _rest["Chest"]["scale"] * Vector3(1.0, 1.0 + breath * 0.012, 1.0)
    var sway := breath * 0.035
    _rot("UpperArmL", Vector3(sway, 0.0, 0.06))
    _rot("UpperArmR", Vector3(-sway, 0.0, -0.06))
    var head := _joint("Head")
    if head != null:
        head.rotation = _rest["Head"]["rot"] + Vector3(breath * 0.012, 0.0, 0.0)


func _apply_locomotion(speed: float) -> void:
    var swing: float = clampf(speed * 0.16, 0.18, 0.55)
    var s := sin(_phase)
    var s_opp := sin(_phase + PI)

    _rot("ThighL", Vector3(s * swing, 0.0, 0.0))
    _rot("ThighR", Vector3(s_opp * swing, 0.0, 0.0))
    # Knees only bend one way — a negative shin angle is a broken leg.
    _rot("ShinL", Vector3(-maxf(0.0, -s) * swing * 1.4, 0.0, 0.0))
    _rot("ShinR", Vector3(-maxf(0.0, -s_opp) * swing * 1.4, 0.0, 0.0))
    _rot("FootL", Vector3(maxf(0.0, s) * swing * 0.5, 0.0, 0.0))
    _rot("FootR", Vector3(maxf(0.0, s_opp) * swing * 0.5, 0.0, 0.0))

    # Arms counter-swing against the legs.
    _rot("UpperArmL", Vector3(s_opp * swing * 0.8, 0.0, 0.06))
    _rot("UpperArmR", Vector3(s * swing * 0.8, 0.0, -0.06))
    _rot("ForearmL", Vector3(-absf(s_opp) * swing * 0.35, 0.0, 0.0))
    _rot("ForearmR", Vector3(-absf(s) * swing * 0.35, 0.0, 0.0))

    # Two bobs per stride — the body rises on each foot plant.
    var hips := _joint("Hips")
    if hips != null:
        hips.position = _rest["Hips"]["pos"] + Vector3(0.0, absf(s) * 0.025, 0.0)


func _apply_gesture() -> void:
    # t runs 0 -> 1 across the gesture so each one can shape its own envelope.
    var t: float = 1.0 - (_gesture_left / GESTURE_SECONDS)
    var wave := sin(t * TAU * 2.0)

    match _gesture:
        "wave":
            _rot("UpperArmR", Vector3(0.0, 0.0, -2.3))
            _rot("ForearmR", Vector3(0.0, 0.0, wave * 0.5 - 0.3))
        "point":
            _rot("UpperArmR", Vector3(-1.5, 0.0, -0.25))
            _rot("ForearmR", Vector3(-0.15, 0.0, 0.0))
        "nod":
            _rot("Head", Vector3(sin(t * TAU * 2.0) * 0.28, 0.0, 0.0))
        "bow":
            var bend: float = sin(t * PI) * 0.7
            _rot("Torso", Vector3(bend, 0.0, 0.0))
            _rot("Head", Vector3(bend * 0.4, 0.0, 0.0))
        "clap":
            var close: float = absf(sin(t * TAU * 3.0)) * 0.45
            _rot("UpperArmL", Vector3(-1.2, 0.0, 0.7 - close))
            _rot("UpperArmR", Vector3(-1.2, 0.0, -0.7 + close))
        "shrug":
            var lift: float = sin(t * PI) * 0.35
            _rot("ShoulderL", Vector3(0.0, 0.0, -lift))
            _rot("ShoulderR", Vector3(0.0, 0.0, lift))
            _rot("UpperArmL", Vector3(0.0, 0.0, 0.9))
            _rot("UpperArmR", Vector3(0.0, 0.0, -0.9))
        "cheer":
            var raise: float = sin(t * PI) * 0.5
            _rot("UpperArmL", Vector3(0.0, 0.0, 2.6 + raise))
            _rot("UpperArmR", Vector3(0.0, 0.0, -2.6 - raise))
        "dance":
            var swing := sin(t * TAU * 2.0)
            _rot("Hips", Vector3(0.0, swing * 0.3, 0.0))
            _rot("Torso", Vector3(0.0, -swing * 0.2, swing * 0.12))
            _rot("UpperArmL", Vector3(0.0, 0.0, 1.2 + swing * 0.4))
            _rot("UpperArmR", Vector3(0.0, 0.0, -1.2 + swing * 0.4))


func _apply_expression() -> void:
    if _expression == "neutral":
        return
    var pose = AvatarConfig.EXPRESSIONS.get(_expression)
    if pose == null:
        return
    for joint_name in pose:
        var node := _joint(joint_name)
        if node == null:
            continue
        var p: Dictionary = pose[joint_name]
        var rest: Dictionary = _rest[joint_name]
        node.position = rest["pos"] + p.get("offset", Vector3.ZERO)
        node.scale = rest["scale"] * p.get("scale", Vector3.ONE)
        node.rotation = rest["rot"] + p.get("rot", Vector3.ZERO)
