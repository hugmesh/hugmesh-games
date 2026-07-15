# Generated entry-point for a VN bundle produced by snapdragon-vn-builder.
#
# `ScenePlayer` is the root Node3D the WASM iframe loads — even though
# the dialogue presentation is 2D-style (flat foreground plane + screen-
# locked TextBox), the bundle stays a 3D scene so snapdragon-bridge's
# multiplayer lobby + per-player view channel can render avatars in the
# same space. Backgrounds are 3D primitives (BoxMesh / QuadMesh) the
# runtime parameterises at load time.
#
# On _ready: parse scenes_data.json (per-act, written by the renderer),
# pick the entry-point scene, drive the TextBox.
#
# Branching: when a line carries choices, the player taps a labeled
# button; the targeted scene becomes the next active scene. Tapping
# anywhere else advances the line index.
#
# ART LOADING (this is real, not aspirational). The renderer copies each
# referenced background / portrait asset's bytes into res://assets/<id>.<ext>
# and writes the resolvable res:// path into scenes_data.json
# (backgrounds[<id>].asset_path, characters[<id>].portrait_path). On scene
# entry we load the scene's background onto the BackgroundCube mesh, and on
# each line we load the speaking character's portrait onto the Foreground
# plane. See `_load_texture` for WHY we decode raw bytes via
# Image.load_*_from_buffer rather than ResourceLoader.load.

extends Node3D

const DATA_PATH := "res://acts/{act_slug}/scenes_data.json"

# SVG is decoded at ~2x so the resolution-independent house-style art
# stays crisp when the camera dollies in. Bitmap formats ignore this.
const SVG_RENDER_SCALE := 2.0

var scenes_by_id: Dictionary = {}
var characters: Dictionary = {}
var backgrounds: Dictionary = {}
var current_scene_id: String = ""
var current_line_index: int = 0

# res:// asset path -> decoded ImageTexture, so a portrait/background that
# recurs across lines/scenes is only decoded once.
var _texture_cache: Dictionary = {}

@onready var text_box: Panel = $UI/TextBox
@onready var background_node: MeshInstance3D = $BackgroundCube
@onready var foreground_node: MeshInstance3D = $Foreground

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
    _apply_background()
    _render_current_line()

func _apply_background() -> void:
    var scene: Dictionary = scenes_by_id.get(current_scene_id, {})
    var bg_id = scene.get("background_id", null)
    var res_path := ""
    if bg_id != null:
        var bg: Dictionary = backgrounds.get(bg_id, {})
        res_path = _string_or_empty(bg.get("asset_path", ""))
    var tex := _load_texture(res_path)
    if tex == null:
        background_node.visible = false
        return
    if background_node.mesh == null:
        # A wide, thin slab behind the dialogue plane; the node transform
        # in main.tscn already scales/pushes it into the backdrop position.
        background_node.mesh = BoxMesh.new()
    background_node.material_override = _unshaded_material(tex, false)
    background_node.visible = true

func _render_current_line() -> void:
    var scene: Dictionary = scenes_by_id.get(current_scene_id, {})
    var lines: Array = scene.get("lines", [])
    if current_line_index >= lines.size():
        return
    var line: Dictionary = lines[current_line_index]
    var character_id = _string_or_empty(line.get("character_id", ""))
    var char_data: Dictionary = characters.get(character_id, {})
    var speaker: String = char_data.get("display_name", "")
    var text: String = line.get("text", "")
    _apply_portrait(_string_or_empty(char_data.get("portrait_path", "")))
    if text_box.has_method("show_line"):
        text_box.show_line(speaker, text, line.get("choices", []))

func _apply_portrait(res_path: String) -> void:
    var tex := _load_texture(res_path)
    if tex == null:
        # Narrator lines (no character) or a character with no portrait
        # simply show no foreground art — the dialogue still plays.
        foreground_node.visible = false
        return
    if foreground_node.mesh == null:
        foreground_node.mesh = QuadMesh.new()
    foreground_node.material_override = _unshaded_material(tex, true)
    foreground_node.visible = true

# Decode a runtime-loaded, NON-imported asset into an ImageTexture.
#
# WHY raw-buffer decode instead of ResourceLoader.load(): the renderer
# copies asset bytes into res://assets/ at build time WITHOUT running
# Godot's editor import pass, so there is no .import sidecar / .ctex for
# ResourceLoader to resolve. Instead we read the packed source bytes with
# FileAccess (the same mechanism that already loads scenes_data.json) and
# hand them to the format-specific Image buffer decoder. This is the one
# approach that works for non-imported images inside a Godot 4 Web export's
# virtual .pck filesystem, and it keeps generated/uploaded SVG resolution-
# independent via Image.load_svg_from_buffer. The export preset's
# include_filter packs the raw *.svg/*.png/*.jpg/*.webp sources so
# FileAccess sees the original bytes (import remapping only affects
# ResourceLoader, not FileAccess).
#
# NOTE: not export-verified in CI (no Godot toolchain / export templates in
# the build env). Needs an export smoke on a machine with Godot 4.6 + Web
# templates.
func _load_texture(res_path: String) -> Texture2D:
    if res_path == "":
        return null
    if _texture_cache.has(res_path):
        return _texture_cache[res_path]
    if not FileAccess.file_exists(res_path):
        push_warning("[snapdragon-vn] asset not packed at %s" % res_path)
        return null
    var bytes := FileAccess.get_file_as_bytes(res_path)
    if bytes.is_empty():
        push_warning("[snapdragon-vn] asset bytes empty at %s" % res_path)
        return null
    var img := Image.new()
    var err := ERR_UNAVAILABLE
    var ext := res_path.get_extension().to_lower()
    match ext:
        "svg":
            err = img.load_svg_from_buffer(bytes, SVG_RENDER_SCALE)
        "png":
            err = img.load_png_from_buffer(bytes)
        "jpg", "jpeg":
            err = img.load_jpg_from_buffer(bytes)
        "webp":
            err = img.load_webp_from_buffer(bytes)
        _:
            push_warning("[snapdragon-vn] unknown asset extension '%s' at %s" % [ext, res_path])
            return null
    if err != OK:
        push_warning("[snapdragon-vn] failed to decode %s (err %d)" % [res_path, err])
        return null
    var tex := ImageTexture.create_from_image(img)
    _texture_cache[res_path] = tex
    return tex

# Flat, unshaded material so the art reads as painted 2D on the 3D plane;
# portraits get alpha transparency so cut-out busts composite cleanly.
func _unshaded_material(tex: Texture2D, transparent: bool) -> StandardMaterial3D:
    var mat := StandardMaterial3D.new()
    mat.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
    mat.albedo_texture = tex
    mat.texture_filter = BaseMaterial3D.TEXTURE_FILTER_LINEAR_WITH_MIPMAPS
    if transparent:
        mat.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
    return mat

# JSON nulls arrive as <null> for narrator lines / unset assets; coerce to
# "" so callers can treat "no value" uniformly.
func _string_or_empty(value) -> String:
    if value == null:
        return ""
    return str(value)

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
