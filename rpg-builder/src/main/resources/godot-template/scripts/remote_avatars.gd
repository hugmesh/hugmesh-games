# Remote-avatar manager for a produced RPG bundle (hugmesh-rpg-builder).
#
# Replaces SnapdragonMultiplayerLobby for maker games (CRITIQUE mustFix: the Rust
# lobby is broken in web mode — the BFF PlayerMovement payload has no `slot`, and
# late joiners never spawn). This node:
#   * connects HostBridge.movement_received,
#   * ignores echoes for HostBridge.local_character_id() (that's the Player),
#   * LAZY-spawns a distinct-coloured CharacterBody3D per unknown characterId
#     under the sibling Wizards node on first sight (fixes the late-join gap),
#   * drives each with the SAME action/pressed velocity core as player.gd (not
#     BFF x/y/z, which is keypress-quantized), and
#   * tracks each avatar's current room from the ENTER_ROOM:<uuid> control the
#     game_bridge emits on every transition, hiding avatars in other rooms.
extends Node

const AvatarConfig := preload("res://scripts/avatar_config.gd")
const AvatarFactory := preload("res://scripts/avatar_factory.gd")
const AvatarPoser := preload("res://scripts/avatar_poser.gd")

const SPEED := 5.0
const GRAVITY := 14.0

var _host_bridge: Node = null
var _local_cid := -1
var _avatars := {}          # characterId -> CharacterBody3D
var _rooms := {}            # characterId -> room uuid
var _local_room := ""

func _ready() -> void:
    _host_bridge = get_parent().get_node_or_null("HostBridge")
    if _host_bridge != null:
        _host_bridge.movement_received.connect(_on_movement)
        _local_cid = int(_host_bridge.local_character_id())
    # HostBridge only learns the local character id from register_player_slot,
    # which game_bridge calls ONLY when the launch URL carries ?slot_index=
    # (multiplayer). A single-player launch therefore leaves it at -1, every
    # local echo looks "remote", and we spawn a second body ON TOP of the
    # Player — two identical capsules interpenetrating, which Godot resolves
    # by firing the local hero hundreds of metres into the sky. Resolve the
    # id the same way game_bridge does (?character_id=, default 1).
    if _local_cid < 0:
        _local_cid = _local_cid_from_url()

func _local_cid_from_url() -> int:
    if not OS.has_feature("web"):
        return -1
    var raw: Variant = JavaScriptBridge.eval(
        "new URLSearchParams(window.location.search).get('character_id') || '1'")
    var s := str(raw) if raw != null else ""
    return int(s) if s.is_valid_int() else 1

# world.gd calls this whenever the LOCAL player changes room, so we know which
# remote avatars to show.
func set_local_room(uuid: String) -> void:
    _local_room = uuid
    _refresh_visibility()

func _on_movement(payload_json: String) -> void:
    var parsed: Variant = JSON.parse_string(payload_json)
    if not (parsed is Dictionary):
        return
    var state: Dictionary = parsed
    if not state.has("characterId"):
        return
    var cid := int(state.get("characterId", -1))
    if cid < 0 or cid == _local_cid:
        return
    var action := str(state.get("action", ""))
    var pressed: bool = state.get("pressed", false)
    if action.begins_with("ENTER_ROOM:"):
        _rooms[cid] = action.substr("ENTER_ROOM:".length())
        _refresh_visibility()
        return
    var avatar := _ensure_avatar(cid)
    match action:
        "MOVE_FORWARD":  avatar.set_meta("dz", -1.0 if pressed else 0.0)
        "MOVE_BACKWARD": avatar.set_meta("dz",  1.0 if pressed else 0.0)
        "MOVE_LEFT":     avatar.set_meta("dx", -1.0 if pressed else 0.0)
        "MOVE_RIGHT":    avatar.set_meta("dx",  1.0 if pressed else 0.0)

var _posers := {}

func _ensure_avatar(cid: int) -> CharacterBody3D:
    if _avatars.has(cid) and is_instance_valid(_avatars[cid]):
        return _avatars[cid]
    var body := CharacterBody3D.new()
    body.name = "Remote_%d" % cid
    body.set_meta("dx", 0.0)
    body.set_meta("dz", 0.0)
    var col := CollisionShape3D.new()
    var cap := CapsuleShape3D.new()
    cap.radius = 0.4
    cap.height = 1.6
    col.shape = cap
    col.position = Vector3(0, 0.9, 0)
    body.add_child(col)
    # Remote avatars are PRESENCE, not obstacles: layer 0 means nothing else
    # (the local Player, the exit/NPC/shop Area3D triggers) ever detects or
    # collides with them, while mask 1 keeps them standing on the world's
    # floor. Without this, an avatar spawning at the origin — where a player
    # legitimately stands — ejects that player from the world, and remote
    # players could body-block each other through doorways.
    body.collision_layer = 0
    body.collision_mask = 1
    # Same configured avatar as the local hero, hue-rotated per
    # character id (fposmod(cid * 0.37, 1.0) — the historical per-cid
    # offset) so players still tell each other apart.
    var avatar := AvatarFactory.build(AvatarConfig.AVATAR, fposmod(cid * 0.37, 1.0))
    body.add_child(avatar)
    # One poser per remote body, kept beside the body rather than on it so a
    # pruned avatar takes its poser with it.
    var poser = AvatarPoser.new()
    poser.setup(avatar)
    _posers[cid] = poser
    var wiz := get_parent().get_node_or_null("Wizards")
    if wiz != null:
        wiz.add_child(body)
    else:
        add_child(body)
    _avatars[cid] = body
    _refresh_visibility()
    return body

func _physics_process(delta: float) -> void:
    for cid in _avatars.keys():
        var a: CharacterBody3D = _avatars[cid]
        if not is_instance_valid(a):
            continue
        if not a.is_on_floor():
            a.velocity.y -= GRAVITY * delta
        a.velocity.x = float(a.get_meta("dx", 0.0)) * SPEED
        a.velocity.z = float(a.get_meta("dz", 0.0)) * SPEED
        a.move_and_slide()
        var poser = _posers.get(cid)
        if poser != null:
            poser.update(delta, a.velocity)

func _refresh_visibility() -> void:
    for cid in _avatars.keys():
        var a: CharacterBody3D = _avatars[cid]
        if not is_instance_valid(a):
            continue
        a.visible = (str(_rooms.get(cid, "")) == _local_room)
