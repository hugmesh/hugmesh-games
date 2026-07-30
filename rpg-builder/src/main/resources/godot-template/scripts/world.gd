# Generated entry-point for an RPG bundle produced by hugmesh-rpg-builder.
#
# `World` is the root Node3D the WASM iframe loads. Node order (pinned by the
# generated main.tscn): GameBridge{BridgeGlue}, HostBridge, Wizards,
# RemoteAvatars, Player, ShopUI. Rooms are instanced as direct World children.
#
# This is a REWRITE (RESOLUTIONS 3 + 4): no key navigation (game_bridge owns
# keys), room transitions fire when the LOCAL Player body enters an exit portal
# Area3D (verified body == Player, call_deferred out of the physics flush), and
# the booted area is parsed from the URL path (…/<area>/index.html), falling
# back to the lexicographically smallest area's start room off-web.
extends Node3D

const RoomIndex := preload("res://scripts/room_index.gd")

var current_room: Node3D = null
var _player: Node = null
var _remote: Node = null
var _game_bridge: Node = null

func _ready() -> void:
    _player = get_node_or_null("Player")
    _remote = get_node_or_null("RemoteAvatars")
    _game_bridge = get_node_or_null("GameBridge")
    if get_node_or_null("HostBridge") == null:
        _warn_onscreen("HostBridge unavailable — movement + multiplayer disabled")
    var start := _boot_scene()
    if start == "":
        _warn_onscreen("No rooms in this bundle")
        return
    _enter_scene(start, "")

func _boot_scene() -> String:
    var area := _area_from_url()
    if area != "" and RoomIndex.AREA_START.has(area):
        return RoomIndex.AREA_START[area]
    var keys: Array = RoomIndex.AREA_START.keys()
    keys.sort()
    if keys.size() > 0:
        return RoomIndex.AREA_START[keys[0]]
    return ""

func _area_from_url() -> String:
    if not OS.has_feature("web"):
        return ""
    var raw: Variant = JavaScriptBridge.eval("window.location.pathname")
    if raw == null:
        return ""
    # Second-to-last path segment: …/<area>/index.html
    var parts: PackedStringArray = str(raw).split("/", false)
    if parts.size() >= 2:
        return parts[parts.size() - 2]
    return ""

# Called (deferred) from an exit portal's body_entered once it has verified the
# entering body is the local Player.
func enter_room_by_uuid(target_uuid: String, arrival_dir: String) -> void:
    var scene_path: String = RoomIndex.ROOM_SCENES.get(target_uuid, "")
    if scene_path == "":
        push_warning("[world] no scene for room %s" % target_uuid)
        return
    _enter_scene(scene_path, arrival_dir)

func _enter_scene(scene_path: String, arrival_dir: String) -> void:
    # Console breadcrumb — room transitions are the load-bearing moment of the
    # whole movement-through-levels feature; a silent failure here renders void.
    print("[world] enter_scene -> %s (dir=%s)" % [scene_path, arrival_dir])
    if current_room != null:
        current_room.queue_free()
        current_room = null
    var packed: PackedScene = load(scene_path) as PackedScene
    if packed == null:
        push_error("[world] failed to load %s" % scene_path)
        return
    current_room = packed.instantiate()
    add_child(current_room)
    _reposition_player(arrival_dir)
    var uuid := str(current_room.get_meta("room_uuid", ""))
    if uuid != "":
        _announce_room(uuid)

func _reposition_player(arrival_dir: String) -> void:
    if _player == null or current_room == null:
        return
    var spawn := Vector3(0, 1, 0)
    if current_room.has_meta("default_spawn"):
        spawn = current_room.get_meta("default_spawn")
    if arrival_dir != "" and current_room.has_meta("entry_spawns"):
        var es: Dictionary = current_room.get_meta("entry_spawns")
        spawn = es.get(arrival_dir, spawn)
    if _player.has_method("teleport_to"):
        _player.teleport_to(spawn)
    elif _player is Node3D:
        (_player as Node3D).global_position = spawn

func _announce_room(uuid: String) -> void:
    if _remote != null and _remote.has_method("set_local_room"):
        _remote.set_local_room(uuid)
    # ENTER_ROOM control: server-observed room transition + drives remote room
    # scoping for the OTHER clients.
    if _game_bridge != null and _game_bridge.has_method("send_control"):
        _game_bridge.send_control("ENTER_ROOM:" + uuid, true)

func _warn_onscreen(msg: String) -> void:
    push_warning("[world] " + msg)
    var label := Label3D.new()
    label.name = "BuildWarning"
    label.text = msg
    label.modulate = Color(1.0, 0.4, 0.3)
    label.font_size = 64
    label.position = Vector3(0, 2.0, -4.0)
    label.billboard = BaseMaterial3D.BILLBOARD_ENABLED
    add_child(label)
