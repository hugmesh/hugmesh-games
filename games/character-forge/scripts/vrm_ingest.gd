class_name CharForgeVrmIngest
extends RefCounted

# =============================================================================
# VRM ingest — the vendored godot-vrm addon (MIT, V-Sekai) wired for RUNTIME
# loading, with MToon swapped for the house toon shader.
#
# Why VRM at all (technique.md): a VRM file carries a standardized humanoid rig
# that maps 1:1 onto SkeletonProfileHumanoid, the standard A/I/U/E/O viseme
# blendshapes, spring bones, AND machine-readable licence/usage metadata — the
# last of which maps directly onto this project's manifest discipline
# (licenseSpdx / attribution / sourceUrl / retrievedAt).
#
# Why MToon is NOT used on web: MToon is a plain gdshader and should compile
# under Compatibility, but the only upstream renderer-support data is stale
# (4.0.1-era Basis-unpack and Compatibility assertion errors) and WebGL2
# support is undocumented — technique.md's fallback plan is "use godot-vrm only
# as importer and swap MToon for the house toon shader on web", which is
# exactly what swap_mtoon() does. On desktop previews MToon is left alone so
# the imported look can be compared against ours.
#
# LICENSE NOTE: only the addons/vrm + addons/Godot-MToon-Shader trees are
# vendored (MIT, manifest entries in addons/THIRD_PARTY_MANIFEST.json). The
# upstream vrm_samples/ models are NOT vendored — Alicia Solid is under DWANGO
# terms and AvatarSample_A/B/C are conditions-of-use models. VRoid-authored
# meshes must never enter the part-mixer (pixiv guideline: no app may generate
# or output models from deformed/combined VRoid meshes), so a VRM ingested here
# is rendered AS A WHOLE AVATAR and never decomposed into mixable parts.
# =============================================================================

const VRM_EXTENSION_SCRIPT := "res://addons/vrm/vrm_extension.gd"

## Materials whose names/shaders identify them as MToon output.
const MTOON_MARKERS := ["mtoon", "vrm_mtoon"]


## True when the vendored addon is present in this build.
static func available() -> bool:
	return ResourceLoader.exists(VRM_EXTENSION_SCRIPT)


## Parses VRM bytes into a scene using the vendored importer, registering the
## GLTFDocumentExtension only for the duration of THIS parse (registration is
## process-global, so leaving it installed would change how every other GLB in
## the app is parsed). Returns null when the addon is absent or the parse fails.
static func parse(bytes: PackedByteArray) -> Node3D:
	if bytes.is_empty():
		return null
	if not available():
		push_warning("CharForgeVrmIngest: addons/vrm is not present in this build")
		return null
	var script: Variant = load(VRM_EXTENSION_SCRIPT)
	if script == null:
		push_warning("CharForgeVrmIngest: failed to load " + VRM_EXTENSION_SCRIPT)
		return null
	var extension: Variant = script.new()
	if not (extension is GLTFDocumentExtension):
		push_warning("CharForgeVrmIngest: vrm_extension.gd is not a GLTFDocumentExtension")
		return null

	GLTFDocument.register_gltf_document_extension(extension, true)
	var root: Node = null
	var doc := GLTFDocument.new()
	var state := GLTFState.new()
	var err := doc.append_from_buffer(bytes, "", state)
	if err == OK:
		CharForgeGlbLoader.fix_texture_mipmaps(state)
		root = doc.generate_scene(state)
	else:
		push_warning("CharForgeVrmIngest: append_from_buffer failed with error %d" % err)
	GLTFDocument.unregister_gltf_document_extension(extension)

	if root == null:
		return null
	if not (root is Node3D):
		root.free()
		return null
	return root as Node3D


## The VRM file's OWN licence terms, read out of the model (vrm_meta on the
## VRMTopLevel root the importer produces). VRM is the one avatar format that
## carries machine-readable usage permissions, and kemono.md is explicit that
## the importer UI must SHOW them: a user dropping in a model needs to see what
## its author allowed, and the platform needs the "we never uploaded it and we
## never redistributed it" position to be visibly true.
##
## Returns {} when the node carries no VRM metadata (a plain GLB import).
## Every value is a String/bool — JSON-safe for the host bridge.
static func read_license(root: Node) -> Dictionary:
	if root == null:
		return {}
	var meta: Variant = root.get("vrm_meta")
	if meta == null or not (meta is Resource):
		return {}
	var authors := ""
	var authors_raw: Variant = meta.get("authors")
	if authors_raw is PackedStringArray or authors_raw is Array:
		var parts := PackedStringArray()
		for a in authors_raw:
			parts.append(String(a))
		authors = ", ".join(parts)
	var out := {
		"title": _meta_str(meta, "title"),
		"version": _meta_str(meta, "version"),
		"authors": authors,
		"contactInformation": _meta_str(meta, "contact_information"),
		"licenseName": _meta_str(meta, "license_name"),
		"licenseUrl": _meta_str(meta, "license_url"),
		"otherLicenseUrl": _meta_str(meta, "other_license_url"),
		"otherPermissionUrl": _meta_str(meta, "other_permission_url"),
		"allowedUser": _meta_str(meta, "allowed_user_name"),
		"commercialUsage": _meta_str(meta, "commercial_usage_type"),
		"creditNotation": _meta_str(meta, "credit_notation"),
		"allowRedistribution": _meta_str(meta, "allow_redistribution"),
		"modification": _meta_str(meta, "modification"),
		"violentUsage": _meta_str(meta, "violent_usage"),
		"sexualUsage": _meta_str(meta, "sexual_usage"),
		"specVersion": _meta_str(meta, "spec_version"),
	}
	# A model whose author disallowed redistribution is still perfectly fine to
	# USE locally — which is all an import ever does. The flag exists so the UI
	# can say so out loud rather than leaving the user to guess.
	out["redistributionDisallowed"] = out.allowRedistribution.to_lower() == "disallow"
	out["creditRequired"] = out.creditNotation.to_lower() == "required"
	return out


static func _meta_str(meta: Object, field: String) -> String:
	var value: Variant = meta.get(field)
	return "" if value == null else String(value).strip_edges()


## True when a material came out of the MToon path (name or shader file).
static func is_mtoon(material: Material) -> bool:
	if material == null:
		return false
	var name := material.resource_name.to_lower()
	for marker in MTOON_MARKERS:
		if name.contains(marker):
			return true
	if material is ShaderMaterial:
		var shader: Shader = (material as ShaderMaterial).shader
		if shader != null and shader.resource_path.to_lower().contains("mtoon"):
			return true
	return false


## Replaces every MToon surface on `root` with the shared house material of the
## palette region its slot name maps to. Returns the number of surfaces
## swapped. Non-MToon surfaces are left alone, so a VRM that already ships
## conforming hm_* slots is untouched by this pass and picked up by the normal
## loader fixup instead.
static func swap_mtoon(root: Node, mats: CharForgeMaterials, slot_map: Dictionary) -> int:
	var swapped := 0
	for node in _walk(root):
		if not (node is MeshInstance3D):
			continue
		var mi := node as MeshInstance3D
		if mi.mesh == null:
			continue
		for i in range(mi.mesh.get_surface_count()):
			var source: Material = mi.get_active_material(i)
			if not is_mtoon(source):
				continue
			var source_name := "" if source == null else source.resource_name
			var region := CharForgeGlbLoader.region_for_material_name(source_name, slot_map)
			mi.set_surface_override_material(i, mats.region("fur" if region.is_empty() else region))
			swapped += 1
	return swapped


## Whether the house shader should replace MToon on this platform. Web is the
## unverified combination, so it is always swapped there; desktop keeps MToon
## unless the caller forces the house look.
static func should_swap_on_this_platform() -> bool:
	return OS.has_feature("web")


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
