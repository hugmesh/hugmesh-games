class_name CharForgeMount
extends Node

# =============================================================================
# MOUNTING a catalogue entry onto the assembler rig.
#
# CharacterAssembler always builds the procedural avatar and then EMITS
# mesh_basis_required — it never loads anything itself, because the loader is
# asynchronous and the assembler is not. This node is the "caller" that signal
# talks about: it resolves the entry to real geometry, runs the fixup pass and
# swaps the mesh in on top of the procedural rig.
#
# TWO MOUNTS (lanes.json `mounts`):
#   avatar  the GLB/VRM IS the character. The procedural rig is hidden and the
#           mesh takes its place.
#   body    the mesh is the BODY and the procedural head survives: the head
#           subtree is re-parented onto the loaded rig's head bone with a
#           BoneAttachment3D created FROM CODE. This is the hybrid
#           legal-inventory.md recommends for the kemono lane, because no CC0
#           rigged anthro biped exists to buy instead.
#
# COMPOSED SETS: an entry may carry `pieces` — extra GLBs skinned to the SAME
# rig as its primary (the Quaternius modular outfits: body + arms + legs +
# feet). Each piece's skinned mesh is re-parented onto the primary's skeleton,
# which works because every bind in its Skin resolves by NAME against an
# identical bone list. A piece whose binds do not all resolve is dropped and
# reported, never added as a collapsed mesh. No Blender step is involved.
#
# TWO SOURCES, deliberately different:
#   res:// .glb   loaded as the IMPORTED PackedScene (load()). The editor
#                 importer has already done the work — no runtime glTF parse, no
#                 mipmap bug, no main-thread hitch. This is why vendored packs
#                 cost far less than an imported file.
#   res:// .vrm   parsed from BYTES through the vendored godot-vrm addon: .vrm
#                 has no Godot importer registered in this project, so there is
#                 no PackedScene to load. The export preset therefore has to
#                 ship the raw .vrm files (include_filter) — they are the one
#                 asset class that pays the runtime-parse cost.
#   user bytes    the BYO import path (import_bytes) — same VRM/GLB runtime
#                 parse, never written to disk, never uploaded anywhere.
#
# MATERIALS: an entry whose pack policy is "map" gets every surface rebound to
# the shared house materials (recolour then works on it exactly as on the
# procedural avatar). An entry marked preserveMaterials keeps its vendor
# material — a single baked atlas cannot be region-recoloured, and binding it
# to a flat house colour would paint the whole character one colour. That is
# reported, not hidden: mount_report().recolorable says so.
# =============================================================================

## Emitted after a successful mount. `kind` is "head" (whole avatar) or "body".
signal mounted(kind: String, entry: Dictionary, root: Node3D)
## Emitted when nothing could be mounted; the procedural avatar stays visible.
signal mount_failed(entry: Dictionary, reason: String)

## Name of the node the loaded mesh is parented under (one per assembler).
const MOUNT_NODE_NAME := "PackMesh"

## Node name of the BoneAttachment3D carrying the procedural head on a body mount.
const HEAD_GRAFT_NAME := "ProceduralHeadGraft"

## The assembler whose rig this mount decorates.
var assembler: CharacterAssembler = null

## Catalogue key currently mounted ("" when the avatar is purely procedural).
var current_key := ""
## Last failure reason ("" when the last mount succeeded).
var last_error := ""

var _root: Node3D = null
var _entry: Dictionary = {}
var _kind := ""
var _channels: Array = []
var _swapped := 0
var _hidden := 0
var _merged := 0
## Mesh nodes bound to a house region through the vendor atlas (single-atlas
## packs). 0 for every ordinary entry.
var _atlas_bound := 0
## Per-node record of what recoloured, and the parts deliberately left on vendor
## colours because no single region colour would be right for them.
var _atlas_nodes: Array = []
## Base colours resolved out of a vendor atlas when the model has no per-part
## boundary at all ([] for every other entry).
var _atlas_clusters: Array = []
var _atlas_skipped: Array = []
var _height_scale := 1.0
## Provenance + measurements for a runtime-split donor head ({} when the mount
## carries the procedural head, which is the common case).
var _donor: Dictionary = {}


func _init(target: CharacterAssembler = null) -> void:
	name = "PackMount"
	assembler = target


# =============================================================================
# Static helpers — every one of them is assertable without a scene tree.
# =============================================================================

## Instantiates a res:// entry: imported PackedScene for .glb/.gltf, the
## godot-vrm byte path for .vrm. Returns null when the asset is missing or the
## parse fails (the caller then keeps the procedural avatar).
static func instantiate_asset(path: String) -> Node3D:
	if path.is_empty():
		return null
	if path.to_lower().ends_with(".vrm"):
		if not FileAccess.file_exists(path):
			push_warning("CharForgeMount: no VRM at " + path)
			return null
		return CharForgeVrmIngest.parse(FileAccess.get_file_as_bytes(path))
	if not ResourceLoader.exists(path):
		push_warning("CharForgeMount: no importable resource at " + path)
		return null
	var res: Variant = load(path)
	if not (res is PackedScene):
		push_warning("CharForgeMount: %s is a %s, not a PackedScene"
				% [path, "null" if res == null else res.get_class()])
		return null
	var node: Node = (res as PackedScene).instantiate()
	if node is Node3D:
		return node as Node3D
	if node != null:
		node.free()
	return null


## Hides mesh nodes named in `names` (the catalogue's hiddenMeshes: KayKit's
## characters carry every weapon and shield of the pack in one file, so a
## knight mounts holding seven of them unless they are hidden). Returns how
## many nodes were actually hidden — a name that matches nothing is reported by
## the difference, and tests/run_tests.gd fails on it.
static func hide_meshes(root: Node, names: PackedStringArray) -> int:
	if root == null or names.is_empty():
		return 0
	var wanted := {}
	for n in names:
		wanted[String(n)] = true
	var hidden := 0
	for node in _walk(root):
		if node is MeshInstance3D and wanted.has(String(node.name)):
			(node as MeshInstance3D).visible = false
			hidden += 1
	return hidden


## World-space height of every visible mesh under `root` (0.0 when it has none).
static func measure_height(root: Node3D) -> float:
	if root == null:
		return 0.0
	var has_any := false
	var lo := 0.0
	var hi := 0.0
	for node in _walk(root):
		if not (node is VisualInstance3D):
			continue
		var vi := node as VisualInstance3D
		if not vi.visible:
			continue
		var aabb := vi.get_aabb()
		if aabb.size == Vector3.ZERO:
			continue
		# Local AABB -> the mount root's space (the rig may scale its skeleton).
		var xform := root.global_transform.affine_inverse() * vi.global_transform
		var box := xform * aabb
		if not has_any:
			lo = box.position.y
			hi = box.position.y + box.size.y
			has_any = true
		else:
			lo = minf(lo, box.position.y)
			hi = maxf(hi, box.position.y + box.size.y)
	return maxf(hi - lo, 0.0) if has_any else 0.0


## Uniform scale that makes `root` exactly `target` units tall. 1.0 when either
## height is unusable — a model that cannot be measured is never blindly
## resized.
static func height_scale(current: float, target: float) -> float:
	if current <= 0.0001 or target <= 0.0001:
		return 1.0
	return target / current


## Merges a COMPOSED SET's extra pieces onto an already-loaded skeleton.
##
## The Quaternius modular outfits are separate GLBs skinned to the SAME 65-bone
## Universal Base rig, so a piece's MeshInstance3D can simply be re-parented
## under the target Skeleton3D: every bind in its Skin resolves by name against
## the identical bone list, which is what makes this work with no Blender step
## and no re-rigging. A piece whose binds do NOT all resolve is dropped rather
## than added as a collapsed mesh.
##
## Returns {"merged": int meshes added, "skipped": Array of reasons}.
static func merge_pieces(skeleton: Skeleton3D, piece_paths: PackedStringArray) -> Dictionary:
	var merged := 0
	var skipped: Array = []
	if skeleton == null:
		return {"merged": 0, "skipped": ["no skeleton to merge onto"]}
	for path in piece_paths:
		var piece := instantiate_asset(String(path))
		if piece == null:
			skipped.append("could not instantiate " + String(path))
			continue
		var taken := 0
		for node in _walk(piece):
			if not (node is MeshInstance3D):
				continue
			var mi := node as MeshInstance3D
			if mi.mesh == null:
				continue
			var unresolved := _unresolved_binds(mi, skeleton)
			if unresolved > 0:
				skipped.append("%s/%s: %d skin binds do not exist on the target rig"
						% [String(path).get_file(), mi.name, unresolved])
				continue
			mi.get_parent().remove_child(mi)
			skeleton.add_child(mi)
			mi.skeleton = NodePath("..")
			mi.transform = Transform3D.IDENTITY
			merged += 1
			taken += 1
		if taken == 0:
			skipped.append("%s contributed no usable mesh" % String(path).get_file())
		piece.free()
	return {"merged": merged, "skipped": skipped}


## How many of a skinned mesh's binds name a bone the target skeleton lacks.
## An unskinned mesh counts as 0 — it is a static prop, not a deformed one.
static func _unresolved_binds(mi: MeshInstance3D, skeleton: Skeleton3D) -> int:
	if mi.skin == null:
		return 0
	var missing := 0
	for i in range(mi.skin.get_bind_count()):
		var bone_name := mi.skin.get_bind_name(i)
		if bone_name.is_empty():
			continue
		if skeleton.find_bone(bone_name) < 0:
			missing += 1
	return missing


## Mesh carrying the most VRM viseme shapes under `root` (the face mesh), or
## null. VRoid splits the body across a dozen surfaces and only the face one
## has Fcl_MTH_*; picking by count is what makes this robust across vendors.
static func find_viseme_mesh(root: Node) -> MeshInstance3D:
	var best: MeshInstance3D = null
	var best_count := 0
	for node in _walk(root):
		if not (node is MeshInstance3D):
			continue
		var mi := node as MeshInstance3D
		if mi.mesh == null:
			continue
		var count := 0
		for viseme in CharForgeLipSync.VISEMES:
			if CharForgeLipSync.resolve_viseme_index(mi, viseme) >= 0:
				count += 1
		if count > best_count:
			best = mi
			best_count = count
	return best


# =============================================================================
# Mounting
# =============================================================================

## Removes any mounted mesh and un-hides the procedural rig. Safe to call when
## nothing is mounted.
func clear() -> void:
	if _root != null and is_instance_valid(_root):
		_root.get_parent().remove_child(_root)
		_root.queue_free()
	_root = null
	_entry = {}
	_kind = ""
	_channels = []
	_swapped = 0
	_hidden = 0
	_merged = 0
	_atlas_bound = 0
	_atlas_nodes = []
	_atlas_clusters = []
	_atlas_skipped = []
	_height_scale = 1.0
	_donor = {}
	current_key = ""
	if assembler != null:
		var rig := assembler.rig_ref("rig")
		if rig != null:
			rig.visible = true
		var head := assembler.rig_ref("head")
		if head != null:
			head.visible = true


## Mounts a registry entry (the payload of CharacterAssembler.mesh_basis_required).
## Returns the mounted root, or null on failure — in which case the procedural
## avatar is left untouched and VISIBLE: a missing or broken asset degrades to
## the parametric character, never to an empty stage.
##
## Synchronous on purpose. The vendored packs cost no runtime glTF parse (the
## .glb entries are imported PackedScenes) and the fixup walk is a few dozen
## nodes, so there is nothing to chunk across frames — the async, chunked path
## in CharForgeGlbLoader.load_from_buffer exists for arbitrary user bytes.
func mount(kind: String, entry: Dictionary) -> Node3D:
	last_error = ""
	if assembler == null:
		return _fail(entry, "no assembler")
	var root := instantiate_asset(String(entry.get("glb", "")))
	if root == null:
		return _fail(entry, "could not instantiate " + String(entry.get("glb", "")))

	clear()
	_entry = entry.duplicate(true)
	_kind = kind
	root.name = MOUNT_NODE_NAME

	_hidden = hide_meshes(root, _hidden_meshes(entry))

	# Composed sets: merge the remaining garment pieces onto the primary's
	# skeleton BEFORE measuring, so the fit-to-height scale weighs the whole
	# assembled character (boots and pauldrons included).
	var pieces := _piece_paths(entry)
	if not pieces.is_empty():
		var result := merge_pieces(CharForgeGlbLoader.find_skeleton(root), pieces)
		_merged = int(result.merged)
		if not result.skipped.is_empty():
			last_error = "; ".join(PackedStringArray(result.skipped))
			push_warning("CharForgeMount: " + last_error)

	# The procedural rig's height is the frame of reference for the turntable
	# camera, so a chibi and a superhero base both arrive filling the same box.
	var target := measure_height(assembler)
	assembler.add_child(root)
	var current := measure_height(root)
	_height_scale = height_scale(current, target)
	root.scale = Vector3.ONE * _height_scale

	if not bool(entry.get("preserveMaterials", false)) and assembler.materials != null:
		var slot_map := CharForgeGlbLoader.normalize_slot_map(entry)
		_swapped = CharForgeGlbLoader.swap_materials(root, assembler.materials, slot_map)
		if String(entry.get("rig", "")) == CharForgeRegistry.RIG_VRM:
			# Anything the slot map missed but MToon owns still has to leave the
			# unverified WebGL2 shader behind.
			_swapped += CharForgeVrmIngest.swap_mtoon(root, assembler.materials, slot_map)
	elif assembler.materials != null:
		# SINGLE-ATLAS CHARACTERS. The vendor material is preserved as the
		# SHADING source and a house region colour supplies the hue, per mesh
		# node — see CharForgeGlbLoader.bind_atlas_regions. Entries with no
		# atlasRegions map keep their vendor colours exactly as before.
		# BOUNDARY-FREE recolour first: an avatar with no per-part boundary at all
		# takes its regions from the TEXTURE's base colours instead.
		var cluster_spec: Variant = entry.get("atlasClusters", {})
		if cluster_spec is Dictionary and not (cluster_spec as Dictionary).is_empty():
			var spec: Dictionary = cluster_spec
			var order: Variant = spec.get("regionOrder", [])
			var clustered := CharForgeGlbLoader.bind_atlas_clusters(
					root, assembler.materials,
					order if order is Array else [],
					float(spec.get("margin", 0.06)),
					int(spec.get("clusters", CharForgeGlbLoader.MAX_ATLAS_CLUSTERS)))
			_atlas_bound = int(clustered.bound)
			_atlas_clusters = clustered.clusters.duplicate(true)
			if _atlas_bound == 0:
				last_error = "atlas cluster recolour found no atlas on %s" \
						% String(entry.get("key", ""))
				push_warning("CharForgeMount: " + last_error)

		var node_regions: Variant = entry.get("atlasRegions", {})
		if node_regions is Dictionary and not (node_regions as Dictionary).is_empty():
			var atlas := CharForgeGlbLoader.bind_atlas_regions(
					root, assembler.materials, node_regions)
			_atlas_bound = int(atlas.bound)
			_atlas_nodes = atlas.nodes.duplicate(true)
			# SKIPPED is not an error: those nodes mix two materials, and the
			# measured answer is that no single region colour is right for them
			# (CharForgeGlbLoader.atlas_profile). They are reported so the UI can
			# say how much of the character recolours.
			_atlas_skipped = atlas.skipped.duplicate()
			if not atlas.unmapped.is_empty():
				# UNMAPPED *is* a gap: the catalogue named no region for a node
				# that exists, so it would recolour inconsistently with the rest.
				last_error = "atlas recolour: the catalogue names no region for %s" \
						% ", ".join(PackedStringArray(atlas.unmapped))
				push_warning("CharForgeMount: " + last_error)

	if kind == "body":
		_mount_body(root)
	else:
		_mount_avatar(root)

	_root = root
	current_key = String(entry.get("key", ""))
	_bind_lip_sync(root, entry)
	mounted.emit(kind, _entry, root)
	return root


## Mounts USER-SUPPLIED bytes (the BYO-VRM/GLB import slot). The bytes are
## parsed in-process and never written to disk or sent anywhere: this method is
## the whole reason an imported avatar can stay client-side.
##
## `meta` is echoed back on the mount report so the UI can show the file's own
## in-file licence terms — for a VRM those are read out of the model itself.
func import_bytes(bytes: PackedByteArray, file_name: String) -> Dictionary:
	last_error = ""
	if assembler == null:
		return {"ok": false, "reason": "no assembler"}
	if bytes.is_empty():
		return {"ok": false, "reason": "empty file"}
	if bytes.size() > CharForgeGlbLoader.MAX_BYTES:
		return {"ok": false, "reason": "file is %d bytes, over the %d-byte runtime cap"
				% [bytes.size(), CharForgeGlbLoader.MAX_BYTES]}

	var lower := file_name.to_lower()
	var is_vrm := lower.ends_with(".vrm")
	var root: Node3D = null
	if is_vrm:
		root = CharForgeVrmIngest.parse(bytes)
	else:
		var doc := GLTFDocument.new()
		var state := GLTFState.new()
		if doc.append_from_buffer(bytes, "", state) == OK:
			CharForgeGlbLoader.fix_texture_mipmaps(state)
			var node: Node = doc.generate_scene(state)
			if node is Node3D:
				root = node as Node3D
			elif node != null:
				node.free()
	if root == null:
		return {"ok": false, "reason": "could not parse %s as %s"
				% [file_name, "VRM" if is_vrm else "glTF"]}

	var license := CharForgeVrmIngest.read_license(root)
	var entry := {
		"basis": CharForgeRegistry.BASIS_MESH,
		"glb": "user://" + file_name,
		"rig": CharForgeRegistry.RIG_VRM if is_vrm else CharForgeRegistry.RIG_HUMANOID,
		"slots": {},
		# An imported file is NOT ours to recolour: its materials are the
		# author's, its licence may forbid modification, and we have no slot map
		# for it. Keep what arrived.
		"preserveMaterials": true,
		"visemes": is_vrm,
		"wholeAvatar": true,
		"key": "import:" + file_name,
		"displayName": String(license.get("title", "")) if not String(license.get("title", "")).is_empty() else file_name,
		"lane": "import",
		"licenseSpdx": "",
		"attribution": String(license.get("authors", "")),
		"sourceUrl": "",
		"retrievedAt": "",
		"imported": true,
	}

	clear()
	_entry = entry
	_kind = "head"
	root.name = MOUNT_NODE_NAME
	var target := measure_height(assembler)
	assembler.add_child(root)
	_height_scale = height_scale(measure_height(root), target)
	root.scale = Vector3.ONE * _height_scale
	_mount_avatar(root)
	_root = root
	current_key = String(entry.key)
	_bind_lip_sync(root, entry)
	mounted.emit("head", _entry, root)

	var report := mount_report()
	report["ok"] = true
	report["license"] = license
	report["fileName"] = file_name
	report["bytes"] = bytes.size()
	return report


## What actually happened on the last mount — the honest capability note the UI
## shows next to the model (recolour available? which lip-sync channel? how
## many surfaces were rebound? was it scaled to fit?).
func mount_report() -> Dictionary:
	return {
		"key": current_key,
		"kind": _kind,
		"displayName": String(_entry.get("displayName", "")),
		"lane": String(_entry.get("lane", "")),
		"mounted": _root != null and is_instance_valid(_root),
		# A single-atlas character IS recolourable once its mesh nodes are bound
		# to regions through the atlas, even though its vendor material was
		# preserved — so the honest answer is not simply "not preserveMaterials".
		"recolorable": not bool(_entry.get("preserveMaterials", true)) or _atlas_bound > 0,
		"surfacesRebound": _swapped,
		"atlasRegionsBound": _atlas_bound,
		"atlasRegions": _atlas_nodes.duplicate(true),
		"atlasSkipped": _atlas_skipped.duplicate(),
		"atlasClusters": _atlas_clusters.duplicate(true),
		"meshesHidden": _hidden,
		"piecesMerged": _merged,
		"heightScale": _height_scale,
		"lipSyncChannels": _channels.duplicate(),
		"visemes": bool(_entry.get("visemes", false)),
		"licenseSpdx": String(_entry.get("licenseSpdx", "")),
		"attribution": String(_entry.get("attribution", "")),
		"sourceUrl": String(_entry.get("sourceUrl", "")),
		"imported": bool(_entry.get("imported", false)),
		# {} unless a real animal head was split off a CC0 quadruped and grafted
		# on. Carries that donor's OWN licence/attribution: it is a second
		# third-party asset in the same avatar, and a credits screen that named
		# only the body would under-report what is on screen.
		"headDonor": _donor.duplicate(true),
		"error": last_error,
	}


# =============================================================================
# internals
# =============================================================================

func _mount_avatar(root: Node3D) -> void:
	var rig := assembler.rig_ref("rig")
	if rig != null:
		rig.visible = false
	root.visible = true


func _mount_body(root: Node3D) -> void:
	# The loaded rig becomes the body; a head is grafted onto its head bone FROM
	# CODE (never authored into the GLB — imported attachment nodes create
	# phantom bones). Which head depends on the entry:
	#   no headDonor   the PROCEDURAL head (ears/horns/muzzle from the assembler)
	#   headDonor      a real animal head, split out of a CC0 quadruped by bone
	#                  weight at runtime (CharForgeMeshSurgery)
	var rig := assembler.rig_ref("rig")
	var head := assembler.rig_ref("head")
	var skeleton := CharForgeGlbLoader.find_skeleton(root)
	var bone := CharForgeGlbLoader.bone_for_slot(skeleton, "ears")  # head-bone list
	if head == null or skeleton == null or bone.is_empty():
		# No head bone to graft onto: fall back to the whole-avatar mount rather
		# than leaving a headless body on stage.
		last_error = "no head bone on %s — mounted as a whole avatar instead" \
				% String(_entry.get("key", ""))
		_mount_avatar(root)
		return

	# A donor head REPLACES the procedural one. Every failure below degrades to
	# the procedural head — a body wearing our own beast head is a worse result
	# than the wolf's, and a far better one than a decapitated body.
	var donor: Variant = _entry.get("headDonor", null)
	if donor is Dictionary and not String((donor as Dictionary).get("glb", "")).is_empty():
		if _graft_donor_head(skeleton, bone, donor as Dictionary):
			if rig != null:
				rig.visible = false
			head.visible = false
			return

	var attachment := CharForgeGlbLoader.attach_part(skeleton, bone, head)
	if attachment == null:
		last_error = "head graft failed on bone " + bone
		_mount_avatar(root)
		return
	attachment.name = HEAD_GRAFT_NAME
	# The graft rides the skeleton, which the mount scaled to the procedural
	# rig's height; undo that so the head keeps the size the assembler gave it.
	var inverse := 1.0 if _height_scale <= 0.0001 else 1.0 / _height_scale
	head.transform = Transform3D.IDENTITY.scaled(Vector3.ONE * inverse)
	if rig != null:
		rig.visible = false
	head.visible = true


## Splits a CC0 quadruped's head off by bone weight and attaches it to the
## target rig's head bone. Returns false (and leaves last_error set) when
## anything is missing, so the caller can fall back to the procedural head.
##
## RETIRES lanes.json's "NOT POSSIBLE AT RUNTIME" verdict on this graft. What
## the verdict got right: the donors really are one skinned mesh with no head
## NODE to attach. What it missed: the per-vertex bone weights that identify the
## head are already in the surface arrays, so the split is arithmetic, not DCC
## work. See scripts/mesh_surgery.gd for the measurements.
func _graft_donor_head(skeleton: Skeleton3D, bone: String, donor: Dictionary) -> bool:
	var donor_scene := instantiate_asset(String(donor.get("glb", "")))
	if donor_scene == null:
		last_error = "head donor %s could not be instantiated — kept the procedural head" \
				% String(donor.get("glb", ""))
		push_warning("CharForgeMount: " + last_error)
		return false

	var patterns := PackedStringArray(CharForgeMeshSurgery.DEFAULT_HEAD_JOINTS)
	var raw_joints: Variant = donor.get("headJoints", null)
	if raw_joints is Array and not (raw_joints as Array).is_empty():
		patterns = PackedStringArray()
		for pattern in raw_joints:
			patterns.append(String(pattern))
	var report: Dictionary = CharForgeMeshSurgery.extract_head(
			donor_scene, skeleton, patterns,
			CharForgeMeshSurgery.DEFAULT_THRESHOLD,
			bool(donor.get("keepSeam", true)))
	donor_scene.free()
	if report.get("node", null) == null:
		last_error = "head donor %s: %s — kept the procedural head" \
				% [String(donor.get("modelId", "")), String(report.get("error", "?"))]
		push_warning("CharForgeMount: " + last_error)
		return false

	var instance: MeshInstance3D = report.node
	# SIZE comes from the head the assembler already built, so a donor head
	# arrives the same size as the procedural one it replaces and the turntable
	# camera does not have to know which is on. Measured, not tabulated.
	var donor_height := CharForgeMeshSurgery.mesh_height(instance.mesh)
	var target_height := measure_height(assembler.rig_ref("head"))
	var fit := height_scale(donor_height, target_height)
	# ...then undo the skeleton's own fit-to-height, exactly as the procedural
	# graft does — the attachment rides a rig that was already scaled.
	if _height_scale > 0.0001:
		fit /= _height_scale

	var attachment := CharForgeGlbLoader.attach_part(skeleton, bone, instance)
	if attachment == null:
		instance.free()
		last_error = "head donor could not attach to bone " + bone
		push_warning("CharForgeMount: " + last_error)
		return false
	attachment.name = HEAD_GRAFT_NAME

	# The extracted vertices are in the TARGET RIG's frame (mesh_surgery aligned
	# them). A BoneAttachment3D hands its child the BONE's frame, so undo the
	# bone's rest rotation or the head inherits whatever tilt the neck has.
	var bone_index := skeleton.find_bone(bone)
	var rest_basis := Basis.IDENTITY
	if bone_index >= 0:
		rest_basis = skeleton.get_bone_global_rest(bone_index).basis.orthonormalized().inverse()
	instance.transform = Transform3D(rest_basis.scaled(Vector3.ONE * fit), Vector3.ZERO)

	# The donor pack has its own slot map (it is a different vendor from the
	# body), so the head recolours on the house palette independently.
	if not bool(donor.get("preserveMaterials", false)) and assembler.materials != null:
		var slots: Variant = donor.get("slots", {})
		var slot_map := CharForgeGlbLoader.normalize_slot_map(
				{"slots": slots if slots is Dictionary else {}})
		_swapped += CharForgeGlbLoader.swap_materials(instance, assembler.materials, slot_map)

	_donor = {
		"key": String(donor.get("modelId", "")),
		"displayName": String(donor.get("displayName", "")),
		"packId": String(donor.get("packId", "")),
		"triangles": int(report.get("triangles", 0)),
		"vertices": int(report.get("vertices", 0)),
		"seamTriangles": int(report.get("seamTriangles", 0)),
		"joints": Array(report.get("joints", PackedStringArray())),
		"alignedTo": String(report.get("alignedTo", "")),
		"lateralPairs": int(report.get("lateralPairs", 0)),
		"heightScale": fit,
		"licenseSpdx": String(donor.get("licenseSpdx", "")),
		"attribution": String(donor.get("attribution", "")),
		"sourceUrl": String(donor.get("sourceUrl", "")),
		"retrievedAt": String(donor.get("retrievedAt", "")),
	}
	return true


func _bind_lip_sync(root: Node3D, entry: Dictionary) -> void:
	if assembler == null or assembler.lip_sync == null:
		_channels = []
		return
	if _kind == "body":
		# The procedural jaw survived the graft: nothing to rebind.
		_channels = assembler.lip_sync.active_channels()
		return
	var skeleton := CharForgeGlbLoader.find_skeleton(root)
	var jaw := CharForgeGlbLoader.find_jaw_bone(skeleton)
	if not jaw.is_empty():
		assembler.lip_sync.bind_jaw_bone(skeleton, jaw)
	if bool(entry.get("visemes", false)):
		assembler.lip_sync.bind_visemes(find_viseme_mesh(root), true)
	_channels = assembler.lip_sync.active_channels()


## The composed set's extra GLB paths ([] for a single-file model).
func _piece_paths(entry: Dictionary) -> PackedStringArray:
	var raw: Variant = entry.get("pieces", [])
	if raw is PackedStringArray:
		return raw
	var out := PackedStringArray()
	if raw is Array:
		for v in raw:
			out.append(String(v))
	return out


func _hidden_meshes(entry: Dictionary) -> PackedStringArray:
	var raw: Variant = entry.get("hiddenMeshes", [])
	var out := PackedStringArray()
	if raw is PackedStringArray:
		return raw
	if raw is Array:
		for v in raw:
			out.append(String(v))
	return out


func _fail(entry: Dictionary, reason: String) -> Node3D:
	last_error = reason
	push_warning("CharForgeMount: " + reason)
	mount_failed.emit(entry, reason)
	return null


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
