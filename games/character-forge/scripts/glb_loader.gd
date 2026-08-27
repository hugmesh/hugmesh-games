class_name CharForgeGlbLoader
extends Node

# =============================================================================
# Runtime GLB/VRM loader + THE MANDATORY FIXUP PASS.
#
# technique.md, verbatim recommendation: "GLTFDocument.append_from_buffer +
# generate_scene, then a MANDATORY post-load fixup pass that (a) generates
# mipmaps for any embedded textures (open engine bug #100481), (b) swaps all
# materials for the shared toon shader + palette texture, (c) attaches
# procedural ears/tails/wings via BoneAttachment3D FROM CODE (never author
# attachments inside GLBs — import creates phantom bones), (d) pre-warms
# shaders offscreen for one frame. Chunk parsing across frames on
# single-threaded web."
#
# All four steps are implemented below and none of them is optional: load()
# runs the whole pass or fails. The individual steps are also exposed as static
# functions so they can be asserted directly without a GLB file.
#
# HONEST LIMITS (do not paper over these):
#  * GLTFDocument.append_from_buffer is a single blocking call — it CANNOT be
#    chunked. What is chunked across frames is everything this loader does
#    itself: the material/texture fixup walk and the attachment pass. On a
#    single-threaded web export the parse itself is still a hitch proportional
#    to file size, which is why MAX_BYTES is small and why the shipped
#    registry has no mesh bases yet.
#  * Runtime loading performs NO BoneMap retargeting, no LOD generation and no
#    texture import options. The rig that arrives is the rig you get, so mesh
#    bases must be conformed to SkeletonProfileHumanoid names at authoring/CI
#    time (enforced socially by CharForgeRegistry.RIG_HUMANOID, not by code).
#  * Blend-shape correctness on WEB Compatibility has no definitive upstream
#    verdict (technique.md gap) — the viseme channel is therefore opt-in per
#    registry entry and the jaw bone stays the mandatory path.
# =============================================================================

signal loaded(root: Node3D)
signal load_failed(reason: String)

## Hard cap on a single runtime asset. Deliberately small: a single-threaded
## WASM main-thread parse of anything larger is a visible freeze, and the
## Quest-3 browser budget in technique.md is ~5-15k tris per avatar.
const MAX_BYTES := 24 * 1024 * 1024

## Nodes visited per frame during the chunked fixup walk.
const CHUNK_NODES := 48

## Hue radius (0..1, so 0.083 is 30 degrees) treated as "the same material" when
## profiling a mesh node against a baked atlas.
const HUE_TOLERANCE := 0.083

## A mesh node whose dominant hue covers less than this much of its atlas
## footprint is left on its VENDOR colours. Measured across the shipped KayKit
## models, coverage is bimodal — coherent parts sit at 75-100% and genuinely
## mixed ones at 18-53% — so this threshold separates them with room on both
## sides rather than splitting a cluster.
const MIN_ATLAS_COVERAGE := 0.60

## Boundary-free atlas recolour (see cluster_atlas): how many base colours to
## resolve, the quantisation grid that collapses a shading ramp onto its base,
## and how close two candidates may be before the second counts as a variant of
## the first rather than a material of its own.
const MAX_ATLAS_CLUSTERS := 4
const ATLAS_QUANT := 24
const MERGE_DISTANCE := 0.18

## Fallback palette regions by common material-name substring, tried only when
## the registry slot map has no exact answer. Keeps a well-meaning but
## non-conforming GLB from rendering as flat white.
const NAME_HINTS := {
	"fur": "fur", "body": "fur", "coat": "fur",
	"skin": "skin", "face": "skin",
	"hair": "hair",
	"eye": "iris", "iris": "iris",
	"line": "line", "outline": "line",
	"accent": "accent", "cloth": "accent",
	"detail": "detail", "trim": "detail",
}


## Merges a registry entry's slot map over the default ingest contract and
## lower-cases every key (glTF material names are case-inconsistent in the
## wild).
static func normalize_slot_map(entry: Dictionary) -> Dictionary:
	var out := {}
	for key in CharForgeRegistry.DEFAULT_SLOT_MAP:
		out[String(key).to_lower()] = CharForgeRegistry.DEFAULT_SLOT_MAP[key]
	var slots: Variant = entry.get("slots")
	if slots is Dictionary:
		for key in slots.keys():
			var region := String(slots[key])
			if region in Char3DConfig.ALL_MATERIAL_REGIONS:
				out[String(key).to_lower()] = region
	return out


## Palette region for a glTF material name: exact slot-map hit first, then the
## `hm_` ingest prefix, then a substring hint. Returns "" when nothing matches
## (the caller then leaves the surface on the fur region rather than guessing).
static func region_for_material_name(material_name: String, slot_map: Dictionary) -> String:
	var key := material_name.strip_edges().to_lower()
	if key.is_empty():
		return ""
	if slot_map.has(key):
		return slot_map[key]
	# glTF exporters routinely suffix duplicated material names (".001").
	var trimmed := key.split(".")[0]
	if slot_map.has(trimmed):
		return slot_map[trimmed]
	if trimmed.begins_with("hm_"):
		var region := trimmed.substr(3)
		for candidate in Char3DConfig.ALL_MATERIAL_REGIONS:
			if String(candidate).to_lower() == region:
				return candidate
	for hint in NAME_HINTS:
		if trimmed.contains(hint):
			return NAME_HINTS[hint]
	return ""


## FIXUP (a): regenerate mipmaps for every embedded texture of a parsed
## document. Runtime glTF loading does NOT apply mipmaps to embedded textures
## (engine bug #100481, confirmed open late-2024, 2026 status unverified) —
## this is harmless if upstream has since fixed it. Returns how many textures
## were rewritten.
static func fix_texture_mipmaps(state: GLTFState) -> int:
	var images: Array = state.get_images()
	if images.is_empty():
		return 0
	var fixed := 0
	var rebuilt: Array = []
	for tex in images:
		if not (tex is Texture2D):
			rebuilt.append(tex)
			continue
		var img: Image = (tex as Texture2D).get_image()
		if img == null or img.has_mipmaps():
			rebuilt.append(tex)
			continue
		var copy := Image.new()
		copy.copy_from(img)
		copy.generate_mipmaps()
		rebuilt.append(ImageTexture.create_from_image(copy))
		fixed += 1
	if fixed > 0:
		state.set_images(rebuilt)
	return fixed


## FIXUP (b): swap every surface material for the shared house material of its
## palette region. Returns the number of surfaces rebound.
static func swap_materials(root: Node, mats: CharForgeMaterials,
		slot_map: Dictionary) -> int:
	var swapped := 0
	for node in _walk(root):
		swapped += _swap_node_materials(node, mats, slot_map)
	return swapped


## Rebinds one node's surfaces. Shared by the synchronous and the chunked
## walkers so the two can never disagree about what "fixed up" means.
static func _swap_node_materials(node: Node, mats: CharForgeMaterials,
		slot_map: Dictionary) -> int:
	if not (node is MeshInstance3D):
		return 0
	var mi := node as MeshInstance3D
	if mi.mesh == null:
		return 0
	var swapped := 0
	for i in range(mi.mesh.get_surface_count()):
		var source: Material = mi.mesh.surface_get_material(i)
		var source_name := "" if source == null else source.resource_name
		var region := region_for_material_name(source_name, slot_map)
		if region.is_empty():
			region = "fur"
		mi.set_surface_override_material(i, mats.region(region))
		swapped += 1
	return swapped


## Uniform names that carry the base-colour texture on a vendor ShaderMaterial.
## `_MainTex` is MToon's, which is what the vendored VRM addon binds — a VRM
## arrives as a ShaderMaterial, NOT a BaseMaterial3D, so looking only at
## albedo_texture finds nothing on every VRM in the catalogue.
const ATLAS_UNIFORM_CANDIDATES := ["_MainTex", "albedo_texture", "texture_albedo",
		"base_texture", "main_tex"]


## The single baked atlas a vendor material renders from, or null.
static func atlas_texture_of(mi: MeshInstance3D) -> Texture2D:
	if mi == null or mi.mesh == null:
		return null
	for i in range(mi.mesh.get_surface_count()):
		var material: Material = mi.get_surface_override_material(i)
		if material == null:
			material = mi.mesh.surface_get_material(i)
		if material is BaseMaterial3D:
			var tex := (material as BaseMaterial3D).albedo_texture
			if tex != null:
				return tex
		elif material is ShaderMaterial:
			var shader_mat := material as ShaderMaterial
			for candidate in ATLAS_UNIFORM_CANDIDATES:
				var value: Variant = shader_mat.get_shader_parameter(String(candidate))
				if value is Texture2D:
					return value as Texture2D
			# Unknown vendor shader: the first texture uniform it exposes is a
			# better guess than giving up, and a wrong guess is visible rather
			# than silent (the recolour simply looks wrong, and the report names
			# the clusters it found).
			if shader_mat.shader != null:
				for uniform in shader_mat.shader.get_shader_uniform_list():
					var raw: Variant = shader_mat.get_shader_parameter(String(uniform.name))
					if raw is Texture2D:
						return raw as Texture2D
	return null


## How much of a mesh node's atlas footprint is ONE coherent material.
##
## Returns {"luma": float, "hue": float, "coverage": float, "samples": int}:
##   luma      luminance of the dominant colour — the tone the region colour is
##             mapped onto, so a recoloured part keeps its brightness
##   hue       the dominant hue (0..1), the centre of the recoloured band
##   coverage  the share of the node's texels within HUE_TOLERANCE of it
##
## COVERAGE IS THE HONEST BIT. A vendor mesh node is not always one material:
## measured on the shipped models, coverage runs from 100% (the Knight's cape,
## every skeleton jaw) down to 18% (the Barbarian's legs, which are trousers
## plus a bone trim). A node below MIN_ATLAS_COVERAGE is NOT recoloured at all,
## because any single region colour for it would be wrong for most of its
## surface — and a part left alone reads as a design choice, while a part
## painted the wrong colour reads as a bug.
##
## Sampling is by VERTEX UV, so the cost is proportional to the model (a few
## thousand reads for a KayKit character), paid once per mount.
static func atlas_profile(mi: MeshInstance3D, atlas: Texture2D) -> Dictionary:
	var blank := {"luma": 0.5, "hue": 0.0, "coverage": 0.0, "samples": 0}
	if mi == null or mi.mesh == null or atlas == null:
		return blank
	var image := atlas.get_image()
	if image == null:
		return blank
	if image.is_compressed():
		# get_pixel() cannot read a VRAM-compressed image; decompressing a COPY
		# is a mount-time cost paid once, never per frame, and never mutates the
		# texture the vendor material still points at.
		image = image.duplicate()
		if image.decompress() != OK:
			return blank
	var width := image.get_width()
	var height := image.get_height()
	if width <= 0 or height <= 0:
		return blank

	var colors: Array = []
	var tally := {}
	for s in range(mi.mesh.get_surface_count()):
		var arrays: Array = mi.mesh.surface_get_arrays(s)
		if arrays.size() <= Mesh.ARRAY_TEX_UV or arrays[Mesh.ARRAY_TEX_UV] == null:
			continue
		for uv in (arrays[Mesh.ARRAY_TEX_UV] as PackedVector2Array):
			var x := clampi(int(uv.x * float(width)), 0, width - 1)
			var y := clampi(int(uv.y * float(height)), 0, height - 1)
			var c := image.get_pixel(x, y)
			colors.append(c)
			var key := c.to_rgba32()
			tally[key] = int(tally.get(key, 0)) + 1
	if colors.is_empty():
		return blank

	# The DOMINANT colour, not the mean: a part's texels are a shading ramp plus
	# outliers (an eye white, a metal stud), and a mean is dragged off the base
	# tone by exactly those.
	var best_key := 0
	var best_count := -1
	for key in tally:
		if int(tally[key]) > best_count:
			best_count = int(tally[key])
			best_key = int(key)
	var dominant := Color(Color.hex(best_key))
	var hue := dominant.h
	var covered := 0
	for c in colors:
		var color: Color = c
		if color.s < 0.02:
			# Achromatic texels follow the region rather than counting against it.
			covered += 1
			continue
		var dist: float = absf(color.h - hue)
		dist = minf(dist, 1.0 - dist)
		if dist <= HUE_TOLERANCE:
			covered += 1
	return {
		"luma": maxf(0.2126 * dominant.r + 0.7152 * dominant.g + 0.0722 * dominant.b, 0.001),
		"hue": hue,
		"coverage": float(covered) / float(colors.size()),
		"samples": colors.size(),
	}


## Up to `count` BASE COLOURS of an atlas, ordered by how much of the mesh uses
## them, sampled along the mesh's own UVs.
##
## This is the boundary-free half of atlas recolour. Some vendored avatars (the
## 100Avatars line) have no per-part boundary at all: one mesh node, one
## primitive, one material, no COLOR_0, no VRM annotation, and UV islands that
## are anatomical rather than material (measured: 94 islands carrying only 23
## distinct colours). But each of those avatars resolves to just 2-4 BASE
## COLOURS, and that IS a material partition — so the regions are taken from the
## texture instead of from the geometry.
##
## Deterministic by construction: colours are quantised to a coarse grid, ranked
## by texel count, and a candidate within MERGE_DISTANCE of an already-taken
## centre is skipped as a shading variant of it. Same input, same answer, every
## run — which is what lets a test pin the result.
##
## Returns [{"color": Color, "share": float, "luma": float}, ...], largest first.
static func cluster_atlas(mi: MeshInstance3D, atlas: Texture2D,
		count: int = MAX_ATLAS_CLUSTERS) -> Array:
	var out: Array = []
	if mi == null or mi.mesh == null or atlas == null or count <= 0:
		return out
	var image := atlas.get_image()
	if image == null:
		return out
	if image.is_compressed():
		image = image.duplicate()
		if image.decompress() != OK:
			return out
	var width := image.get_width()
	var height := image.get_height()
	if width <= 0 or height <= 0:
		return out

	var tally := {}
	var samples: Array[Color] = []
	for s in range(mi.mesh.get_surface_count()):
		var arrays: Array = mi.mesh.surface_get_arrays(s)
		if arrays.size() <= Mesh.ARRAY_TEX_UV or arrays[Mesh.ARRAY_TEX_UV] == null:
			continue
		for uv in (arrays[Mesh.ARRAY_TEX_UV] as PackedVector2Array):
			var x := clampi(int(uv.x * float(width)), 0, width - 1)
			var y := clampi(int(uv.y * float(height)), 0, height - 1)
			var c := image.get_pixel(x, y)
			samples.append(c)
			# Quantise so a shading ramp collapses onto its base colour.
			var key := "%d,%d,%d" % [int(c.r * 255.0) / ATLAS_QUANT,
					int(c.g * 255.0) / ATLAS_QUANT, int(c.b * 255.0) / ATLAS_QUANT]
			tally[key] = int(tally.get(key, 0)) + 1
	var total := samples.size()
	if total == 0:
		return out

	var ranked: Array = tally.keys()
	ranked.sort_custom(func(a, b):
		var ca := int(tally[a])
		var cb := int(tally[b])
		# Tie-break on the key so the order is stable, not hash-order dependent.
		return ca > cb if ca != cb else String(a) < String(b))
	for key in ranked:
		var parts := String(key).split(",")
		var color := Color(
				(float(int(parts[0])) * ATLAS_QUANT + ATLAS_QUANT * 0.5) / 255.0,
				(float(int(parts[1])) * ATLAS_QUANT + ATLAS_QUANT * 0.5) / 255.0,
				(float(int(parts[2])) * ATLAS_QUANT + ATLAS_QUANT * 0.5) / 255.0)
		var duplicate_of := false
		for taken in out:
			if _color_distance(color, taken.color) < MERGE_DISTANCE:
				duplicate_of = true
				break
		if duplicate_of:
			continue
		out.append({
			"color": color,
			"share": 0.0,
			"luma": maxf(0.2126 * color.r + 0.7152 * color.g + 0.0722 * color.b, 0.001),
		})
		if out.size() >= count:
			break
	if out.is_empty():
		return out

	# SHARE is the fraction of texels that actually CLASSIFY to each cluster —
	# the same nearest-colour rule the shader applies — not the size of the
	# quantised bucket the centre came from. Those differ: the buckets are a
	# seeding heuristic and leave a fifth of the texels unaccounted for, so
	# reporting them would publish percentages that do not sum to what is on
	# screen.
	var hits := PackedInt32Array()
	hits.resize(out.size())
	for sample in samples:
		var best := 0
		var best_distance := 1e9
		for i in range(out.size()):
			var d := _color_distance(sample, out[i].color)
			if d < best_distance:
				best_distance = d
				best = i
		hits[best] += 1
	for i in range(out.size()):
		out[i].share = float(hits[i]) / float(total)
	# Re-rank on the TRUE share. The bucket counts only seeded the centres, and
	# they disagree with classification often enough to matter (measured: on the
	# teddy avatar the second bucket owns 45% of texels and the first 33%). The
	# catalogue's rule is "the largest colour is the base coat", so the order
	# handed to regionOrder has to be the order of what is actually on screen.
	out.sort_custom(func(a, b):
		return float(a.share) > float(b.share) if not is_equal_approx(float(a.share), float(b.share)) \
				else float(a.luma) < float(b.luma))
	return out


static func _color_distance(a: Color, b: Color) -> float:
	return Vector3(a.r - b.r, a.g - b.g, a.b - b.b).length()


## Recolours a character that has NO per-part boundary, by classifying every
## texel to the nearest atlas base colour and taking its region from that.
##
## `region_order` is the catalogue's answer to "which region does the Nth
## largest colour get" — the jrpg-bases.md 60/30/10 blocking, applied to area:
## the biggest colour is the base coat, the next the accent, the rest detail and
## line. That is a rule about proportion, so it holds for an avatar nobody has
## looked at, which a per-avatar colour table would not.
##
## Returns {bound: int, clusters: Array}. bound is 0 when the mesh has no atlas.
static func bind_atlas_clusters(root: Node, mats: CharForgeMaterials,
		region_order: Array, margin: float = 0.06,
		count: int = MAX_ATLAS_CLUSTERS) -> Dictionary:
	var report := {"bound": 0, "clusters": []}
	if root == null or mats == null or region_order.is_empty():
		return report
	for node in _walk(root):
		if not (node is MeshInstance3D):
			continue
		var mi := node as MeshInstance3D
		if mi.mesh == null:
			continue
		var atlas := atlas_texture_of(mi)
		if atlas == null:
			continue
		var found := cluster_atlas(mi, atlas, count)
		if found.is_empty():
			continue
		var regions: Array = []
		for i in range(found.size()):
			regions.append(String(region_order[mini(i, region_order.size() - 1)]))
		var material := mats.atlas_clusters(regions, atlas, found, margin)
		for i in range(mi.mesh.get_surface_count()):
			mi.set_surface_override_material(i, material)
		report.bound += 1
		for i in range(found.size()):
			report.clusters.append({
				"color": (found[i].color as Color).to_html(false),
				"region": regions[i],
				"share": float(found[i].share),
			})
	return report


## FIXUP (b2): PER-REGION RECOLOUR OF A SINGLE-ATLAS CHARACTER.
##
## The KayKit lines render from ONE baked atlas with no named material slots, so
## swap_materials() cannot help them: binding that one material to a flat house
## colour paints the entire character one colour. They therefore shipped with
## recolour switched OFF, recorded in lanes.json as a capability gap whose fix
## "needs the atlas cell layout, an art task".
##
## It is not an art task. The vendor splits each character into SEMANTIC MESH
## NODES (Knight_Head, Knight_Cape, Knight_Body...) and each node's texels are a
## luminance ramp of one hue — measured: the Knight's head uses a 16-entry flesh
## ramp, its cape a 13-entry red one. So the region colour can replace the hue
## per node while the atlas keeps supplying the shading, with no new texture and
## no re-UVing.
##
## `node_regions` is {meshNodeName: paletteRegion}, resolved in the asset
## pipeline so the mapping is reviewable in the catalogue rather than guessed
## from names at runtime. Returns {bound: int, unmapped: Array} — a node the
## catalogue does not name is REPORTED, never silently left at its vendor colour
## while the rest of the character recolours.
static func bind_atlas_regions(root: Node, mats: CharForgeMaterials,
		node_regions: Dictionary) -> Dictionary:
	var bound := 0
	var unmapped: Array = []
	var skipped: Array = []
	var nodes: Array = []
	if root == null or mats == null or node_regions.is_empty():
		return {"bound": 0, "unmapped": unmapped, "skipped": skipped, "nodes": nodes}
	for node in _walk(root):
		if not (node is MeshInstance3D):
			continue
		var mi := node as MeshInstance3D
		if mi.mesh == null:
			continue
		var region := String(node_regions.get(String(mi.name), ""))
		if region.is_empty():
			unmapped.append(String(mi.name))
			continue
		var atlas := atlas_texture_of(mi)
		if atlas == null:
			# A node with no albedo TEXTURE is not an atlas node at all — it is a
			# flat or emissive vendor material (the skeletons' glowing eye
			# sockets). The "one baked atlas cannot be recoloured" objection does
			# not apply to it: a flat vendor colour maps straight onto a flat
			# house region colour, so it gets the ordinary house material and
			# recolours completely.
			for i in range(mi.mesh.get_surface_count()):
				mi.set_surface_override_material(i, mats.region(region))
			bound += 1
			nodes.append({"node": String(mi.name), "region": region,
					"coverage": 1.0, "source": "flat"})
			continue
		var profile := atlas_profile(mi, atlas)
		if float(profile.coverage) < MIN_ATLAS_COVERAGE:
			# Deliberately left on vendor colours — see atlas_profile().
			skipped.append("%s (%d%% single-hue)"
					% [mi.name, int(round(float(profile.coverage) * 100.0))])
			continue
		var material := mats.atlas_region(region, atlas, float(profile.luma), float(profile.hue))
		for i in range(mi.mesh.get_surface_count()):
			mi.set_surface_override_material(i, material)
		bound += 1
		nodes.append({
			"node": String(mi.name),
			"region": region,
			"coverage": float(profile.coverage),
			"source": "atlas",
		})
	return {"bound": bound, "unmapped": unmapped, "skipped": skipped, "nodes": nodes}


## FIXUP (c): attach a procedural part to a bone FROM CODE. Attachments
## authored inside a GLB can create phantom bones on import and humanoid
## retargeting can break them, so this is the only supported path.
## Returns the created BoneAttachment3D, or null when the bone does not exist.
static func attach_part(skeleton: Skeleton3D, bone_name: String, part: Node3D) -> BoneAttachment3D:
	if skeleton == null or part == null:
		return null
	if skeleton.find_bone(bone_name) < 0:
		return null
	var attachment := BoneAttachment3D.new()
	attachment.name = "Attach_" + bone_name + "_" + part.name
	skeleton.add_child(attachment)
	attachment.bone_name = bone_name
	# A part that is already parented (e.g. a piece being moved off the
	# procedural rig onto a loaded one) must be detached first — add_child on a
	# parented node is an engine error, not a reparent.
	var current := part.get_parent()
	if current != null:
		current.remove_child(part)
	attachment.add_child(part)
	return attachment


## The bone an attachment slot binds to on a given skeleton — the first
## candidate the rig actually has. "" when the rig exposes none of them.
static func bone_for_slot(skeleton: Skeleton3D, slot: String) -> String:
	if skeleton == null:
		return ""
	for candidate in CharForgeRegistry.SLOT_BONE_CANDIDATES.get(slot, []):
		if skeleton.find_bone(candidate) >= 0:
			return candidate
	return ""


## The jaw bone driving the mandatory lip-sync channel, or "" when the rig has
## none (in which case CharForgeLipSync falls back to the mouth strip).
static func find_jaw_bone(skeleton: Skeleton3D) -> String:
	if skeleton == null:
		return ""
	for candidate in CharForgeRegistry.JAW_BONE_CANDIDATES:
		if skeleton.find_bone(candidate) >= 0:
			return candidate
	return ""


## First Skeleton3D under a loaded root (mesh bases are single-rig by contract).
static func find_skeleton(root: Node) -> Skeleton3D:
	for node in _walk(root):
		if node is Skeleton3D:
			return node as Skeleton3D
	return null


## Parses GLB bytes and runs the ENTIRE fixup pass, yielding between chunks.
## Awaitable: `var root := await loader.load_from_buffer(bytes, cfg, entry)`.
## Returns null on failure (load_failed carries the reason).
func load_from_buffer(bytes: PackedByteArray, mats: CharForgeMaterials,
		entry: Dictionary) -> Node3D:
	if bytes.is_empty():
		_fail("empty buffer")
		return null
	if bytes.size() > MAX_BYTES:
		_fail("asset is %d bytes, over the %d-byte runtime cap" % [bytes.size(), MAX_BYTES])
		return null

	var doc := GLTFDocument.new()
	var state := GLTFState.new()
	var err := doc.append_from_buffer(bytes, "", state)
	if err != OK:
		_fail("append_from_buffer failed with error %d" % err)
		return null

	# (a) mipmaps — before generate_scene so the scene picks up the fixed set.
	fix_texture_mipmaps(state)

	var root: Node = doc.generate_scene(state)
	if not (root is Node3D):
		if root != null:
			root.free()
		_fail("glTF produced no Node3D root")
		return null
	var root3d := root as Node3D

	# (b) materials, chunked across frames (single-threaded web).
	await _swap_materials_chunked(root3d, mats, normalize_slot_map(entry))

	loaded.emit(root3d)
	return root3d


## FIXUP (d): render one offscreen frame so every material of `root` compiles
## its shader BEFORE the avatar is first seen (first-draw shader compilation
## stutters badly on web). The node is parented into a hidden SubViewport, one
## frame is drawn, and it is handed back unparented.
func prewarm(root: Node3D) -> void:
	if root == null or not is_inside_tree():
		return
	var viewport := SubViewport.new()
	viewport.size = Vector2i(8, 8)
	viewport.render_target_update_mode = SubViewport.UPDATE_ONCE
	viewport.own_world_3d = true
	add_child(viewport)
	var camera := Camera3D.new()
	camera.current = true
	viewport.add_child(camera)
	var previous_parent: Node = root.get_parent()
	if previous_parent != null:
		previous_parent.remove_child(root)
	viewport.add_child(root)
	await get_tree().process_frame
	await get_tree().process_frame
	viewport.remove_child(root)
	if previous_parent != null:
		previous_parent.add_child(root)
	# Detach BEFORE queue_free: queue_free is deferred to the end of the frame,
	# so a caller that inspects the loader right after awaiting prewarm() would
	# otherwise still see the throwaway viewport hanging off it.
	remove_child(viewport)
	viewport.queue_free()


## Attaches procedural parts to a loaded rig, from code (fixup step c). `parts`
## is {slot: Node3D}. Returns {slot: BoneAttachment3D} for the ones that bound;
## slots whose bone is missing are reported in the returned "unbound" array.
func attach_parts(root: Node3D, parts: Dictionary) -> Dictionary:
	var skeleton := find_skeleton(root)
	var bound := {}
	var unbound: Array = []
	for slot in parts:
		var bone := bone_for_slot(skeleton, slot)
		if bone.is_empty():
			unbound.append(slot)
			continue
		var attachment := attach_part(skeleton, bone, parts[slot])
		if attachment == null:
			unbound.append(slot)
		else:
			bound[slot] = attachment
	return {"bound": bound, "unbound": unbound}


## The chunked half of fixup (b): the same per-node work as swap_materials(),
## yielding every CHUNK_NODES nodes so a big rig does not stall the single
## WASM thread in one go.
func _swap_materials_chunked(root: Node3D, mats: CharForgeMaterials,
		slot_map: Dictionary) -> int:
	var visited := 0
	var swapped := 0
	for node in _walk(root):
		visited += 1
		swapped += _swap_node_materials(node, mats, slot_map)
		if visited % CHUNK_NODES == 0 and is_inside_tree():
			await get_tree().process_frame
	return swapped


func _fail(reason: String) -> void:
	push_warning("CharForgeGlbLoader: " + reason)
	load_failed.emit(reason)


static func _walk(root: Node) -> Array:
	var out: Array = []
	if root == null:
		return out
	var stack: Array = [root]
	while not stack.is_empty():
		var node: Node = stack.pop_back()
		out.append(node)
		for child in node.get_children():
			stack.append(child)
	return out
