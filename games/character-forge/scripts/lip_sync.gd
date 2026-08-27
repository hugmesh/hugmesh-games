class_name CharForgeLipSync
extends RefCounted

# =============================================================================
# Lip-sync — three channels, one amplitude contract.
#
# The platform contract is a single `openness` float 0..1 (matrix-ui's
# AmplitudeMouthService / the Chat3D openness channel). This module turns that
# one number into whichever channel the avatar actually supports:
#
#   1. JAW BONE — THE MANDATORY PATH (technique.md: "make the jaw BONE the
#      mandatory manifest channel ... works on every rig, zero renderer risk,
#      profile-native"; SkeletonProfileHumanoid's 56 bones include Jaw). Also
#      the path the procedural rig uses, where the "bone" is the Jaw Node3D.
#
#   2. MOUTH STRIP — the fallback for meshes with NO jaw bone
#      (jrpg-bases.md: "no facial bones anywhere" on the CC0 lane; the
#      5-frame mouth texture-swap strip is "cheapest, JRPG-authentic, NO
#      Blender needed"). openness picks one of 5 frames; shaders/
#      mouth_strip.gdshader does the UV window.
#
#   3. VISEME BLENDSHAPES — VRM aa/ih/ou/ee/oh, driven ONLY when the registry
#      entry flags visemes AND the shapes actually resolve on the mesh.
#      technique.md is explicit that blend-shape correctness on WEB
#      Compatibility is unverified, so this never displaces the jaw channel;
#      it rides alongside it.
#
# Channel selection is a pure function (resolve_channels) so the priority rules
# are asserted directly rather than inferred from rendering.
#
# TODO (BLENDER-GATED, NOT DONE HERE): legal-inventory.md's remedy for the CC0
# packs that have no facial rig at all (Quaternius / KayKit / Kenney — "NO
# Quaternius/KayKit/Kenney character has a jaw bone or face morphs") is a
# Blender batch job that adds a Jaw bone to each modular head before the GLB is
# conformed. Blender is not installed in this environment, so no such pass
# exists and none is faked: channel 2 (the mouth strip) is precisely why a
# jaw-less CC0 base still lip-syncs today, and CharForgeRegistry.jawBone stays
# empty for any entry that has not been through that job. When the asset
# pipeline gains Blender, the job belongs there and the registry entry gets a
# real jawBone name — nothing in this file changes.
# =============================================================================

# ── jaw ──────────────────────────────────────────────────────────────────────
## Resting pitch of a closed jaw, in degrees (the procedural rig's neutral).
const JAW_REST_DEG := 3.0
## Extra drop an expression preset's `jaw_base` 0..1 contributes.
const JAW_EXPRESSION_RANGE_DEG := 10.0
## Drop contributed by openness 0..1 — the amplitude contract's full swing.
const JAW_OPENNESS_RANGE_DEG := 24.0

# ── mouth strip ──────────────────────────────────────────────────────────────
const MOUTH_FRAMES := 5
## Lower bound of frames 1..4; frame 0 is everything below the first entry.
## Chosen so a quiet room stays visibly closed (0.08) and the two loud frames
## split the top half evenly.
const FRAME_THRESHOLDS := [0.08, 0.28, 0.52, 0.76]
## Hysteresis applied by frame_with_hysteresis so amplitude noise around a
## threshold does not flicker the mouth between two frames.
const FRAME_HYSTERESIS := 0.04

# ── visemes ──────────────────────────────────────────────────────────────────
## VRM's five mouth expressions, in the canonical order.
const VISEMES := ["aa", "ih", "ou", "ee", "oh"]
## Name variants seen in the wild for each viseme: VRM 1.0 expression names
## (aa/ih/ou/ee/oh), VRM 0.x blendshape-group names (A/I/U/E/O) and the VRoid
## mesh blendshape names (Fcl_MTH_A ...). Matched case-insensitively.
const VISEME_ALIASES := {
	"aa": ["aa", "a", "vrc.v_aa", "fcl_mth_a", "mth_a"],
	"ih": ["ih", "i", "vrc.v_ih", "fcl_mth_i", "mth_i"],
	"ou": ["ou", "u", "vrc.v_ou", "fcl_mth_u", "mth_u"],
	"ee": ["ee", "e", "vrc.v_ee", "fcl_mth_e", "mth_e"],
	"oh": ["oh", "o", "vrc.v_oh", "fcl_mth_o", "mth_o"],
}

const CHANNEL_JAW := "jaw"
const CHANNEL_STRIP := "strip"
const CHANNEL_VISEMES := "visemes"

const MOUTH_STRIP_SHADER: Shader = preload("res://shaders/mouth_strip.gdshader")

var openness: float = 0.0
var expression_jaw_base: float = 0.0
## Vowel the viseme channel shapes towards; "aa" is the amplitude-only default.
var vowel: String = "aa"

var _jaw_node: Node3D = null
var _jaw_skeleton: Skeleton3D = null
var _jaw_bone_idx: int = -1
var _jaw_rest: Basis = Basis()
var _strip_material: ShaderMaterial = null
var _strip_frame: int = 0
var _viseme_mesh: MeshInstance3D = null
var _viseme_indices: Dictionary = {}
var _visemes_flagged: bool = false


# =============================================================================
# Pure math (asserted directly by the tests)
# =============================================================================

## Jaw pitch in degrees for an openness + expression base. This is THE formula
## the procedural rig has always used; it lives here so the skeletal path and
## the Node3D path can never drift.
static func jaw_angle_deg(openness_value: float, expression_base: float = 0.0) -> float:
	return JAW_REST_DEG \
			+ clampf(expression_base, 0.0, 1.0) * JAW_EXPRESSION_RANGE_DEG \
			+ clampf(openness_value, 0.0, 1.0) * JAW_OPENNESS_RANGE_DEG


## Mouth-strip frame 0..4 for an openness. Monotonic and total: every input in
## [0,1] (and outside it) yields a legal frame index.
static func mouth_frame(openness_value: float) -> int:
	var v := clampf(openness_value, 0.0, 1.0)
	var frame := 0
	for i in range(FRAME_THRESHOLDS.size()):
		if v >= float(FRAME_THRESHOLDS[i]):
			frame = i + 1
	return clampi(frame, 0, MOUTH_FRAMES - 1)


## Frame selection with hysteresis around the thresholds: the frame only
## changes when openness has moved FRAME_HYSTERESIS past the boundary in the
## direction of travel. `previous` is the frame currently displayed.
static func frame_with_hysteresis(previous: int, openness_value: float) -> int:
	var raw := mouth_frame(openness_value)
	var prev := clampi(previous, 0, MOUTH_FRAMES - 1)
	if raw == prev:
		return prev
	var v := clampf(openness_value, 0.0, 1.0)
	if raw > prev:
		# Rising: require the threshold ENTERING frame prev+1 plus the margin.
		var up: float = float(FRAME_THRESHOLDS[prev])
		return raw if v >= up + FRAME_HYSTERESIS else prev
	# Falling: require the threshold entering the CURRENT frame minus margin.
	var down: float = float(FRAME_THRESHOLDS[prev - 1])
	return raw if v <= down - FRAME_HYSTERESIS else prev


## Viseme weights for an openness + vowel. The chosen vowel carries the whole
## amplitude; every other viseme is driven to 0 so a previously-held shape is
## always released (a partial dictionary would leave stale weights on the mesh).
static func viseme_weights(openness_value: float, vowel_name: String = "aa") -> Dictionary:
	var chosen := vowel_name.to_lower()
	if not (chosen in VISEMES):
		chosen = "aa"
	var amount := clampf(openness_value, 0.0, 1.0)
	var out := {}
	for name in VISEMES:
		out[name] = amount if name == chosen else 0.0
	return out


## Which channels drive the mouth, given what the avatar actually has.
## Rules: the jaw is used whenever it exists; the strip is the FALLBACK and is
## used only when there is no jaw; visemes ride alongside, and only when the
## manifest flags them AND the shapes resolved.
static func resolve_channels(has_jaw: bool, has_strip: bool, manifest_visemes: bool,
		visemes_resolved: bool) -> Array:
	var out: Array = []
	if has_jaw:
		out.append(CHANNEL_JAW)
	elif has_strip:
		out.append(CHANNEL_STRIP)
	if manifest_visemes and visemes_resolved:
		out.append(CHANNEL_VISEMES)
	return out


## Blend-shape index of a viseme on a mesh, or -1. Matches the VRM 1.0, VRM 0.x
## and VRoid naming variants case-insensitively.
static func resolve_viseme_index(mesh_inst: MeshInstance3D, viseme: String) -> int:
	if mesh_inst == null or mesh_inst.mesh == null:
		return -1
	var aliases: Array = VISEME_ALIASES.get(viseme.to_lower(), [])
	for i in range(mesh_inst.mesh.get_blend_shape_count()):
		var shape_name := String(mesh_inst.mesh.get_blend_shape_name(i)).to_lower()
		if shape_name in aliases:
			return i
	return -1


## Builds the mouth-strip quad for a jaw-less mesh: a flat plane whose UVs the
## strip shader windows onto one of 5 frames. `strip_texture` is the 5-frame
## horizontal strip (5*N x N, nearest-filtered); pass null to get a working
## quad with the shader's white default while art is pending.
##
## The caller parents this to the head (via BoneAttachment3D on an imported rig)
## and hands the returned node's material to bind_mouth_strip().
static func make_mouth_quad(width: float, height: float,
		strip_texture: Texture2D = null) -> MeshInstance3D:
	var quad := QuadMesh.new()
	quad.size = Vector2(maxf(width, 0.001), maxf(height, 0.001))
	var mat := ShaderMaterial.new()
	mat.shader = MOUTH_STRIP_SHADER
	mat.set_shader_parameter("frame_index", 0)
	if strip_texture != null:
		mat.set_shader_parameter("strip_tex", strip_texture)
	var mi := MeshInstance3D.new()
	mi.name = "MouthStrip"
	mi.mesh = quad
	mi.material_override = mat
	return mi


## The ShaderMaterial of a quad produced by make_mouth_quad (or null).
static func mouth_quad_material(quad: MeshInstance3D) -> ShaderMaterial:
	if quad == null:
		return null
	return quad.material_override as ShaderMaterial


# =============================================================================
# Binding + driving
# =============================================================================

## Procedural rig: the Jaw anchor Node3D whose X rotation is the channel.
func bind_jaw_node(node: Node3D) -> void:
	_jaw_node = node
	_jaw_skeleton = null
	_jaw_bone_idx = -1


## Imported rig: the skeleton + jaw bone. Records the bone's rest basis so the
## drive is a DELTA on the authored pose rather than an absolute overwrite.
func bind_jaw_bone(skeleton: Skeleton3D, bone_name: String) -> bool:
	if skeleton == null:
		return false
	var idx := skeleton.find_bone(bone_name)
	if idx < 0:
		return false
	_jaw_skeleton = skeleton
	_jaw_bone_idx = idx
	_jaw_rest = skeleton.get_bone_rest(idx).basis
	_jaw_node = null
	return true


## Fallback channel: the mouth quad's ShaderMaterial (mouth_strip.gdshader).
func bind_mouth_strip(material: ShaderMaterial) -> void:
	_strip_material = material
	_strip_frame = 0


## Optional channel: VRM visemes. `flagged` is the registry entry's `visemes`
## bit — the shapes are only driven when BOTH it and the resolution succeed.
## Returns the number of visemes that resolved.
func bind_visemes(mesh_inst: MeshInstance3D, flagged: bool) -> int:
	_viseme_mesh = null
	_viseme_indices.clear()
	_visemes_flagged = flagged
	if not flagged or mesh_inst == null:
		return 0
	for viseme in VISEMES:
		var idx := resolve_viseme_index(mesh_inst, viseme)
		if idx >= 0:
			_viseme_indices[viseme] = idx
	if _viseme_indices.is_empty():
		return 0
	_viseme_mesh = mesh_inst
	return _viseme_indices.size()


## The channels currently live, in priority order.
func active_channels() -> Array:
	return resolve_channels(_jaw_node != null or _jaw_bone_idx >= 0,
			_strip_material != null, _visemes_flagged, not _viseme_indices.is_empty())


## Drives every bound channel from the amplitude contract.
func set_openness(value: float) -> void:
	openness = clampf(value, 0.0, 1.0)
	_apply()


## Expression presets contribute a baseline jaw drop on top of openness.
func set_expression_jaw_base(base: float) -> void:
	expression_jaw_base = clampf(base, 0.0, 1.0)
	_apply()


## The frame currently shown on the mouth strip (test/debug hook).
func strip_frame() -> int:
	return _strip_frame


func _apply() -> void:
	var channels := active_channels()
	var angle := jaw_angle_deg(openness, expression_jaw_base)
	if CHANNEL_JAW in channels:
		if _jaw_node != null:
			_jaw_node.rotation_degrees.x = angle
		elif _jaw_skeleton != null and _jaw_bone_idx >= 0:
			# Delta on the rest basis: never clobber the authored jaw pose.
			var pose := Transform3D(_jaw_rest.rotated(Vector3.RIGHT, deg_to_rad(angle)),
					_jaw_skeleton.get_bone_rest(_jaw_bone_idx).origin)
			_jaw_skeleton.set_bone_pose_rotation(_jaw_bone_idx, pose.basis.get_rotation_quaternion())
	if CHANNEL_STRIP in channels and _strip_material != null:
		_strip_frame = frame_with_hysteresis(_strip_frame, openness)
		_strip_material.set_shader_parameter("frame_index", _strip_frame)
	if CHANNEL_VISEMES in channels and _viseme_mesh != null:
		var weights := viseme_weights(openness, vowel)
		for viseme in _viseme_indices:
			_viseme_mesh.set_blend_shape_value(_viseme_indices[viseme], weights[viseme])
