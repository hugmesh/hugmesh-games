# Runtime art loader for a produced RPG bundle (hugmesh-rpg-builder).
#
# Gives a room its floor/background art so an exported RPG is not a blank
# primitive. Kept in the template (not per-room-generated) so every generated
# room script can `preload("res://scripts/room_visuals.gd")` and call into it.
#
# WHY `load()` and NOT a runtime FileAccess + Image.load_svg_from_buffer:
#   The renderer stages each referenced asset's bytes (SVG/PNG/…) into
#   res://assets/<asset-id>.<ext> and lists them in res://scripts/asset_index.gd.
#   Godot's export step imports EVERY project resource, so res://assets/x.svg is
#   turned into a CompressedTexture2D packed into the .pck. A plain
#   `load("res://assets/x.svg")` therefore returns a ready Texture2D in a Web
#   export with no filesystem access at runtime — which is exactly what works
#   under the emscripten web template. The *raw* source file is NOT packed (only
#   its imported form), so a FileAccess/load_svg_from_buffer path would fail in
#   an export; that is why we resolve through `load()` on the imported resource.
#
# Everything degrades gracefully: an empty/missing/failed path just means no
# floor is laid down (no crash, no error spam beyond a single warning).
extends RefCounted

const FLOOR_SIZE := 8.0

# Lay `texture_path`'s art down as a flat XZ plane centred under `room`.
# `texture_path` is a res:// path (typically resolved via AssetIndex.ASSETS);
# "" is a valid no-op.
static func apply_floor(room: Node3D, texture_path: String) -> void:
    var tex := _load_texture(texture_path)
    if tex == null:
        return
    var plane := PlaneMesh.new()
    plane.size = Vector2(FLOOR_SIZE, FLOOR_SIZE)
    var mat := StandardMaterial3D.new()
    mat.albedo_texture = tex
    mat.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
    # Nearest keeps seeded pixel/tile art crisp instead of muddy under a camera.
    mat.texture_filter = BaseMaterial3D.TEXTURE_FILTER_NEAREST
    plane.material = mat
    var mi := MeshInstance3D.new()
    mi.name = "Floor"
    mi.mesh = plane
    mi.position = Vector3(0, 0, 0)
    room.add_child(mi)

static func _load_texture(texture_path: String) -> Texture2D:
    if texture_path == "":
        return null
    if not ResourceLoader.exists(texture_path):
        push_warning("[hugmesh-rpg] art missing from bundle: %s" % texture_path)
        return null
    var res: Resource = load(texture_path)
    if res is Texture2D:
        return res as Texture2D
    push_warning("[hugmesh-rpg] art did not import as a texture: %s" % texture_path)
    return null
