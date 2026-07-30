# Parametric avatar assembly for a produced RPG bundle (hugmesh-rpg-builder).
#
# This is DELIBERATELY a dumb instantiator. Every dimension, position, colour
# and taper comes from the RIG baked into scripts/avatar_config.gd by
# GdRigBaker, which computes it from the same HumanoidRig that produces the 2D
# SVG and the glTF export. There is not one hard-coded measurement in this file,
# and that is the point: the previous version hard-coded its own mesh sizes next
# to a canvas preview that hard-coded different ones, and the two could drift
# with nothing to catch it (decision 028).
#
# hue_shift == 0.0  -> the exact configured colours (the local hero).
# hue_shift != 0.0  -> OUTFIT parts rotated in HSV so remote avatars stay
#                      per-characterId distinct. Skin and items are left alone,
#                      so a shifted avatar still reads as the same character
#                      rather than turning into a different species.
#
# Joint nodes are plain Node3D and keep their rig names, so avatar_poser.gd can
# find and rotate them. Mesh instances are prefixed "M_" so a part and the joint
# that owns it can share a name without colliding in the scene tree.
extends RefCounted

const AvatarConfig = preload("res://scripts/avatar_config.gd")

# Segment counts. lowpoly is N64 mode: few enough facets that the silhouette
# reads as authentically faceted rather than as a smooth mesh with a filter.
const LOWPOLY_RADIAL := 6
const LOWPOLY_RINGS := 3
const SMOOTH_RADIAL := 16
const SMOOTH_RINGS := 8


# Back-compat entry point: player.gd and remote_avatars.gd call this. The cfg
# dictionary is accepted (and ignored) so those call sites did not have to
# change when the rig moved into the baked data.
static func build(cfg: Dictionary, hue_shift: float) -> Node3D:
    return build_rig(AvatarConfig.RIG, str(AvatarConfig.STYLE), hue_shift)


static func build_rig(rig: Dictionary, style: String, hue_shift: float) -> Node3D:
    var holder := Node3D.new()
    holder.name = "Avatar"
    # The rig faces +Z; Godot's convention is -Z forward. Rotating the whole
    # holder keeps the character's own left hand its left hand.
    holder.rotate_y(PI)
    holder.add_child(_build_joint(rig, style, hue_shift))
    return holder


static func _build_joint(joint: Dictionary, style: String, hue_shift: float) -> Node3D:
    var node := Node3D.new()
    node.name = str(joint.get("name", "Joint"))
    node.position = joint.get("pos", Vector3.ZERO)
    for part in joint.get("parts", []):
        var mesh_node := _build_part(part, style, hue_shift)
        if mesh_node != null:
            node.add_child(mesh_node)
    for child in joint.get("children", []):
        node.add_child(_build_joint(child, style, hue_shift))
    return node


static func _build_part(part: Dictionary, style: String, hue_shift: float) -> MeshInstance3D:
    var size: Vector3 = part.get("size", Vector3.ONE)
    if size.x <= 0.0 or size.y <= 0.0 or size.z <= 0.0:
        return null

    var low := style == "lowpoly"
    var radial := LOWPOLY_RADIAL if low else SMOOTH_RADIAL
    var rings := LOWPOLY_RINGS if low else SMOOTH_RINGS

    var mi := MeshInstance3D.new()
    mi.name = "M_" + str(part.get("name", "Part"))
    var shape := str(part.get("shape", "box"))

    match shape:
        "sphere":
            var sm := SphereMesh.new()
            sm.radius = size.x * 0.5
            sm.height = size.y
            sm.radial_segments = radial
            sm.rings = rings
            mi.mesh = sm
            # SphereMesh has a single radius for x and z, so a non-circular
            # cross-section has to come from the node scale.
            if size.x > 0.0:
                mi.scale = Vector3(1.0, 1.0, size.z / size.x)
        "cyl":
            var cm := CylinderMesh.new()
            cm.bottom_radius = size.x * 0.5
            cm.top_radius = size.x * 0.5 * float(part.get("taper", 1.0))
            cm.height = size.y
            cm.radial_segments = radial
            cm.rings = 1
            mi.mesh = cm
            if size.x > 0.0:
                mi.scale = Vector3(1.0, 1.0, size.z / size.x)
        "quad":
            var qm := QuadMesh.new()
            qm.size = Vector2(size.x, size.y)
            mi.mesh = qm
        _:
            var bm := BoxMesh.new()
            bm.size = size
            mi.mesh = bm

    mi.position = part.get("pos", Vector3.ZERO)
    mi.rotation = part.get("rot", Vector3.ZERO)

    var color: Color = part.get("color", Color(1, 1, 1))
    if hue_shift != 0.0 and str(part.get("tex", "none")) == "outfit":
        color = Color.from_hsv(fposmod(color.h + hue_shift, 1.0), maxf(color.s, 0.5), color.v)
    mi.material_override = _material(color, low)
    return mi


static func _material(color: Color, low_poly: bool) -> StandardMaterial3D:
    var mat := StandardMaterial3D.new()
    mat.albedo_color = color
    if low_poly:
        # Per-vertex shading over few segments is what makes the facets read;
        # nearest filtering keeps any uploaded texture appropriately crunchy.
        mat.shading_mode = BaseMaterial3D.SHADING_MODE_PER_VERTEX
        mat.texture_filter = BaseMaterial3D.TEXTURE_FILTER_NEAREST
        mat.specular_mode = BaseMaterial3D.SPECULAR_DISABLED
        mat.roughness = 1.0
    return mat
