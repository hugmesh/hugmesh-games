class_name CharForgeMaterials
extends RefCounted

# =============================================================================
# The material layer — one house toon ShaderMaterial per palette region, all
# sharing ONE runtime 8x8 palette ImageTexture per avatar (technique.md
# verdict: 1 draw call per avatar, recolor = a 64-pixel image rebuild, no
# dependence on per-instance uniforms which the Compatibility renderer does
# not support).
#
# Region -> material, NOT colour -> material: the colour rides in the shared
# palette texture and each material only carries the fixed `palette_cell` UV
# from CharForgePalette.CELLS. Recolouring an avatar therefore never rebuilds
# or reassigns a single material — see recolor().
#
# OUTLINE QUALITY TOGGLE (technique.md): the inverted-hull outline is a
# next_pass on every house material. It DEFAULTS OFF because it doubles vertex
# work and draw calls, and Meta prices a shader switch at +175% draw-call cost
# on Quest; when it is on, every material fleet-wide shares the ONE
# ShaderMaterial returned by outline_material() so no shader switch ever
# happens between avatars. On Quest browser the graphic look comes from the
# fresnel rim plus the `line` palette cell instead.
# =============================================================================

const HOUSE_SHADER: Shader = preload("res://shaders/house_toon.gdshader")
const HOUSE_TRANS_SHADER: Shader = preload("res://shaders/house_toon_trans.gdshader")
const OUTLINE_SHADER: Shader = preload("res://shaders/outline_hull.gdshader")

## Per-style ramp/rim parameters. kemono.md asks for a "flat toon 2-step ramp
## + rim" (band_cuts 1 = 2 bands); anime gets a 3-band ramp for the extra
## cheek/jaw tone; classic is the flattest, rim-free "SRD taxonomy" look.
const STYLE_PARAMS := {
	"toon": {"band_cuts": 1.0, "shadow_tone": 0.55, "rim_strength": 0.35, "rim_power": 3.0},
	"anime": {"band_cuts": 2.0, "shadow_tone": 0.66, "rim_strength": 0.55, "rim_power": 2.4},
	"classic": {"band_cuts": 1.0, "shadow_tone": 0.72, "rim_strength": 0.0, "rim_power": 3.0},
}

## Quality toggle — inverted-hull outlines. DEFAULT OFF (see header).
static var outline_enabled: bool = false
## Outline inflation in world units, tuned for the ~1.6-unit-tall avatar rigs.
static var outline_width: float = 0.008

static var _outline_mat: ShaderMaterial = null

## The avatar's shared palette texture (every material below samples it).
var palette_texture: ImageTexture = null
## CPU-side mirror of exactly what was uploaded to `palette_texture`. Read this
## rather than palette_texture.get_image(): the latter is a RenderingServer
## readback (a GPU round trip in a real build, stale under the headless dummy
## rasterizer).
var palette_image: Image = null
var style: String = "toon"

var _by_region: Dictionary = {}
var _config: Dictionary = {}


## Builds the whole material set for a sanitized Char3DConfig.
static func for_config(config: Dictionary) -> CharForgeMaterials:
	var set_obj := CharForgeMaterials.new()
	set_obj._config = config
	set_obj.style = String(config.get("style", "toon"))
	if not STYLE_PARAMS.has(set_obj.style):
		set_obj.style = "toon"
	set_obj.palette_image = CharForgePalette.build_image(config)
	set_obj.palette_texture = ImageTexture.create_from_image(set_obj.palette_image)
	for region in Char3DConfig.ALL_MATERIAL_REGIONS:
		set_obj._by_region[region] = set_obj._make(region, false)
	return set_obj


## The shared fleet-wide inverted-hull outline material (ONE instance ever, so
## enabling outlines never introduces a per-avatar shader switch).
static func outline_material() -> ShaderMaterial:
	if _outline_mat == null:
		_outline_mat = ShaderMaterial.new()
		_outline_mat.shader = OUTLINE_SHADER
		_outline_mat.resource_name = "charforge_outline_shared"
	_outline_mat.set_shader_parameter("outline_width", outline_width)
	return _outline_mat


## The opaque house material for a palette region (shared — never mutate it;
## use variant() when a piece needs different uniforms).
func region(region_name: String) -> ShaderMaterial:
	if _by_region.has(region_name):
		return _by_region[region_name]
	return _by_region["fur"]


## A one-off material for a region with overridden uniforms. `opts` keys:
##   alpha        0..1  -> switches to the alpha-blended house shader
##   emission     float -> emission_boost (iris glow / synth cores)
##   band_cuts, shadow_tone, rim_strength, rim_power, matcap_strength
##   tint         Color -> albedo_tint multiplier
func variant(region_name: String, opts: Dictionary) -> ShaderMaterial:
	var alpha: float = float(opts.get("alpha", 1.0))
	var mat := _make(region_name, alpha < 1.0)
	if alpha < 1.0:
		mat.set_shader_parameter("alpha_mul", clampf(alpha, 0.0, 1.0))
	for key in ["emission", "band_cuts", "shadow_tone", "rim_strength", "rim_power",
			"matcap_strength"]:
		if not opts.has(key):
			continue
		var uniform_name: String = "emission_boost" if key == "emission" else key
		mat.set_shader_parameter(uniform_name, float(opts[key]))
	if opts.has("tint"):
		mat.set_shader_parameter("albedo_tint", opts["tint"])
	return mat


## A material that keeps a VENDOR ATLAS as its shading source but takes its hue
## from a palette region.
##
## This is what makes the single-atlas packs (KayKit Adventurers/Skeletons)
## region-recolourable at all. They render from one baked atlas rather than from
## named material slots, so the usual swap — bind the surface to a flat house
## colour — would paint the entire character one colour. That is why they
## shipped with recolour switched off.
##
## They are palette-indexed art, though: every body part's texels are a
## luminance ramp of ONE hue. So the part keeps its own shading and only the hue
## is replaced, per MESH NODE (the vendor names them: Knight_Head, Knight_Cape,
## Knight_Body...). `reference_luma` is the luminance of that node's dominant
## atlas colour, measured at mount time, so the region colour lands on the
## part's base tone instead of darkening the whole character.
##
## Recolouring stays free: the colour rides in the shared palette texture like
## every other region, so recolor() updates these materials too without touching
## one of them.
func atlas_region(region_name: String, atlas: Texture2D,
		reference_luma: float = 0.5, dominant_hue: float = 0.0) -> ShaderMaterial:
	var mat := _make(region_name, false)
	mat.set_shader_parameter("atlas_tex", atlas)
	mat.set_shader_parameter("atlas_strength", 1.0)
	mat.set_shader_parameter("atlas_reference_luma", clampf(reference_luma, 0.001, 1.0))
	mat.set_shader_parameter("atlas_hue", clampf(dominant_hue, 0.0, 1.0))
	mat.set_shader_parameter("atlas_hue_tolerance", CharForgeGlbLoader.HUE_TOLERANCE)
	mat.resource_name = "charforge_atlas_" + region_name
	return mat


## A material that recolours a vendor atlas with NO per-part boundary, by
## classifying each texel to the nearest of up to four measured base colours.
##
## This is what makes the 100Avatars line recolourable. Those avatars are one
## mesh node with one material and no annotation of any kind, so there is
## nothing to bind a region TO — but their texture only holds 2-4 base colours,
## and that is a material partition. `clusters` comes from
## CharForgeGlbLoader.cluster_atlas and carries {color, share, luma} largest
## first; `regions` is the house region each one takes.
##
## The uniforms are packed into vec4s rather than arrays deliberately: the
## Compatibility renderer is the target, and this keeps the shader to plain
## vec4 uniforms. Recolouring stays free — the region colours ride the shared
## palette texture, so recolor() updates this material without touching it.
func atlas_clusters(regions: Array, atlas: Texture2D, clusters: Array,
		margin: float = 0.06) -> ShaderMaterial:
	var mat := _make(String(regions[0]) if not regions.is_empty() else "fur", false)
	mat.set_shader_parameter("atlas_tex", atlas)
	# The single-node path must stay off, or both branches would write ALBEDO.
	mat.set_shader_parameter("atlas_strength", 0.0)
	var r := Color(0, 0, 0, 0)
	var g := Color(0, 0, 0, 0)
	var b := Color(0, 0, 0, 0)
	var cx := Color(0, 0, 0, 0)
	var cy := Color(0, 0, 0, 0)
	var luma := Color(0.5, 0.5, 0.5, 0.5)
	var used := mini(clusters.size(), 4)
	for i in range(used):
		var entry: Dictionary = clusters[i]
		var color: Color = entry.color
		var region := String(regions[mini(i, regions.size() - 1)])
		var cell := CharForgePalette.region_uv(region)
		r[i] = color.r
		g[i] = color.g
		b[i] = color.b
		cx[i] = cell.x
		cy[i] = cell.y
		luma[i] = maxf(float(entry.get("luma", 0.5)), 0.001)
	mat.set_shader_parameter("atlas_base_r", Vector4(r.r, r.g, r.b, r.a))
	mat.set_shader_parameter("atlas_base_g", Vector4(g.r, g.g, g.b, g.a))
	mat.set_shader_parameter("atlas_base_b", Vector4(b.r, b.g, b.b, b.a))
	mat.set_shader_parameter("atlas_cell_x", Vector4(cx.r, cx.g, cx.b, cx.a))
	mat.set_shader_parameter("atlas_cell_y", Vector4(cy.r, cy.g, cy.b, cy.a))
	mat.set_shader_parameter("atlas_cluster_luma", Vector4(luma.r, luma.g, luma.b, luma.a))
	mat.set_shader_parameter("atlas_cluster_count", float(used))
	mat.set_shader_parameter("atlas_cluster_margin", clampf(margin, 0.0, 0.5))
	mat.resource_name = "charforge_atlas_clusters_%d" % used
	return mat


## A flat colour that is NOT a palette region (sclera white, blush pink, ...).
## The palette sampler's hint_default_white makes an unassigned palette texture
## read as white, so albedo_tint alone carries the colour.
func flat(color: Color, alpha: float = 1.0) -> ShaderMaterial:
	var mat := ShaderMaterial.new()
	mat.shader = HOUSE_TRANS_SHADER if alpha < 1.0 else HOUSE_SHADER
	mat.set_shader_parameter("palette_cell", Vector2(-1.0, -1.0))
	mat.set_shader_parameter("albedo_tint", color)
	if alpha < 1.0:
		mat.set_shader_parameter("alpha_mul", clampf(alpha, 0.0, 1.0))
	_apply_style(mat)
	_apply_outline(mat)
	mat.resource_name = "charforge_flat"
	return mat


## Recolour-only fast path: rewrites the shared 8x8 palette in place. Every
## material built by this set updates for free — no material is touched.
func recolor(config: Dictionary) -> void:
	_config = config
	palette_image = CharForgePalette.update_texture(palette_texture, config)


func _make(region_name: String, transparent: bool) -> ShaderMaterial:
	var mat := ShaderMaterial.new()
	mat.shader = HOUSE_TRANS_SHADER if transparent else HOUSE_SHADER
	mat.set_shader_parameter("palette_tex", palette_texture)
	mat.set_shader_parameter("palette_cell", CharForgePalette.region_uv(region_name))
	_apply_style(mat)
	_apply_outline(mat)
	mat.resource_name = "charforge_" + region_name
	return mat


func _apply_style(mat: ShaderMaterial) -> void:
	var params: Dictionary = STYLE_PARAMS.get(style, STYLE_PARAMS["toon"])
	for key in params:
		mat.set_shader_parameter(key, params[key])
	# The rim picks up the avatar's own accent so it reads as stylized light
	# rather than a white halo.
	var rim: Color = CharForgePalette.region_color(_config, "accent").lightened(0.35)
	rim.a = 1.0
	mat.set_shader_parameter("rim_color", rim)


func _apply_outline(mat: ShaderMaterial) -> void:
	mat.next_pass = outline_material() if outline_enabled else null
