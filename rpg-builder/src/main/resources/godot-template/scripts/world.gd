# Generated entry-point for an RPG bundle produced by snapdragon-rpg-builder.
#
# `World` is the root Node3D the WASM iframe loads. It picks the first
# room of the chapter (registered by register.sql at upload time), then
# delegates direction-key handling to scripts/exits.gd.

extends Node3D

const Exits := preload("res://scripts/exits.gd")

@onready var current_room: Node3D = null

func _ready() -> void:
    var initial_path := "res://scenes/rooms/"
    var dir := DirAccess.open(initial_path)
    if dir == null:
        push_error("[snapdragon-rpg] no scenes/rooms/ in bundle")
        return
    dir.list_dir_begin()
    var first_area := dir.get_next()
    if first_area == "":
        push_error("[snapdragon-rpg] no area dirs in scenes/rooms/")
        return
    var area_path := initial_path + first_area
    var area_dir := DirAccess.open(area_path)
    if area_dir == null:
        return
    area_dir.list_dir_begin()
    var first_room := area_dir.get_next()
    while first_room != "" and not first_room.ends_with(".tscn"):
        first_room = area_dir.get_next()
    if first_room == "":
        push_error("[snapdragon-rpg] no .tscn rooms inside %s" % area_path)
        return
    _enter_room(area_path + "/" + first_room)

func _enter_room(scene_path: String) -> void:
    if current_room != null:
        current_room.queue_free()
    var packed: PackedScene = load(scene_path) as PackedScene
    if packed == null:
        push_error("[snapdragon-rpg] failed to load %s" % scene_path)
        return
    current_room = packed.instantiate()
    add_child(current_room)

func _input(event: InputEvent) -> void:
    if not (event is InputEventKey) or not event.pressed or current_room == null:
        return
    var key := event as InputEventKey
    var direction := Exits.direction_for_keycode(key.keycode)
    if direction == "":
        return
    if not current_room.has_meta("exits"):
        return
    var exits: Dictionary = current_room.exits
    if exits.has(direction):
        var target_uuid: String = exits[direction]
        # In the rendered tree each room slug = filename; future: DB lookup.
        # For now the worker writes UUIDs as targets; the runtime can't
        # resolve those without the lookup so we no-op until the rendered
        # graph adds a `slug_by_uuid` map (TODO).
        push_warning("[snapdragon-rpg] direction %s -> %s (resolution NYI)" % [direction, target_uuid])
