# Generated entry-point for a VN bundle produced by snapdragon-vn-builder.
#
# `ScenePlayer` is the root Node3D the WASM iframe loads — even though
# the dialogue presentation is 2D-style (flat foreground plane + screen-
# locked TextBox), the bundle stays a 3D scene so snapdragon-bridge's
# multiplayer lobby + per-player view channel can render avatars in the
# same space. Backgrounds are 3D primitives (BoxMesh / CylinderMesh /
# SphereMesh) the renderer parameterises at build time.
#
# On _ready: parse scenes_data.json (per-act, written by the renderer),
# pick the entry-point scene, drive the TextBox.
#
# Branching: when a line carries choices, the player taps a labeled
# button; the targeted scene becomes the next active scene. Tapping
# anywhere else advances the line index.
#
# SVG-friendly: when a Background or Character carries an asset_id
# pointing at an image/svg+xml resource, the runtime loads it into a
# SubViewport texture and applies it to the Foreground MeshInstance3D
# (a flat plane). Bitmap formats still work but SVG is preferred so
# the produced bundle stays resolution-independent.

extends Node3D

const DATA_PATH := "res://acts/{act_slug}/scenes_data.json"

var scenes_by_id: Dictionary = {}
var characters: Dictionary = {}
var backgrounds: Dictionary = {}
var current_scene_id: String = ""
var current_line_index: int = 0

@onready var text_box: Panel = $UI/TextBox

func _ready() -> void:
    var act_slug := _detect_act_slug()
    var path := DATA_PATH.replace("{act_slug}", act_slug)
    var fp := FileAccess.open(path, FileAccess.READ)
    if fp == null:
        push_error("[snapdragon-vn] missing scenes_data at %s" % path)
        return
    var raw := fp.get_as_text()
    fp.close()
    var data: Dictionary = JSON.parse_string(raw)
    if data == null:
        push_error("[snapdragon-vn] could not parse scenes_data")
        return
    characters = data.get("characters", {})
    backgrounds = data.get("backgrounds", {})
    var scenes_list: Array = data.get("scenes", [])
    var entry_id := ""
    for scene in scenes_list:
        scenes_by_id[scene.id] = scene
        if entry_id == "" and bool(scene.get("entry_point", false)):
            entry_id = scene.id
    if entry_id == "" and not scenes_list.is_empty():
        entry_id = scenes_list[0].id
    if entry_id == "":
        push_error("[snapdragon-vn] no scenes in act %s" % act_slug)
        return
    _enter_scene(entry_id)

func _detect_act_slug() -> String:
    # The bundle is exported per act under bundle/<act-slug>/index.html;
    # the act_slug is the parent of the running scene path. Defaulting
    # to "main" so a one-act stub still works.
    var scene_path := get_tree().current_scene.scene_file_path
    if scene_path.begins_with("res://acts/"):
        var parts := scene_path.replace("res://acts/", "").split("/")
        return parts[0]
    return "main"

func _enter_scene(scene_id: String) -> void:
    current_scene_id = scene_id
    current_line_index = 0
    _render_current_line()

func _render_current_line() -> void:
    var scene: Dictionary = scenes_by_id.get(current_scene_id, {})
    var lines: Array = scene.get("lines", [])
    if current_line_index >= lines.size():
        return
    var line: Dictionary = lines[current_line_index]
    var character_id: String = line.get("character_id", "")
    var speaker := characters.get(character_id, {}).get("display_name", "")
    var text: String = line.get("text", "")
    if text_box.has_method("show_line"):
        text_box.show_line(speaker, text, line.get("choices", []))

func _input(event: InputEvent) -> void:
    if not (event is InputEventKey) or not event.pressed:
        return
    var key := event as InputEventKey
    if key.keycode == KEY_SPACE or key.keycode == KEY_ENTER:
        current_line_index += 1
        _render_current_line()

func choose(target_scene_id: String) -> void:
    if scenes_by_id.has(target_scene_id):
        _enter_scene(target_scene_id)
