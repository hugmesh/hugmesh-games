class_name CharForgeMeshSurgery
extends Object

# =============================================================================
# SPLITTING A SKINNED MESH BY BONE WEIGHT, AT RUNTIME.
#
# WHY THIS EXISTS — it retires a "NOT POSSIBLE AT RUNTIME" verdict.
#
# lanes.json used to carry a blocked[] entry saying the CC0 Quaternius animal
# heads could not be grafted onto the Universal Base humanoid rig, because
# "every quadruped is ONE skinned mesh with no separable head node.
# BoneAttachment3D attaches whole NODES, so there is nothing to attach;
# isolating the head means splitting the mesh by bone weight, which is offline
# mesh surgery" — and Blender is not installed here.
#
# The first half of that is true and the conclusion does not follow. A skinned
# surface already carries, per vertex, the exact information a splitter needs:
# ARRAY_BONES + ARRAY_WEIGHTS. Reading them and rebuilding an ArrayMesh from the
# triangles whose vertices are head-weighted is arithmetic over arrays we
# already have in memory. It needs no DCC tool, no import step and no new
# dependency. What Blender would buy is a nicer SEAM (re-meshing the neck ring),
# not the split itself.
#
# MEASURED, on the six shipped donors (threshold 0.5, joint set Head + Ear1-4.L/R):
#   wolf       1110/3994 verts head-dominant   522 pure-head tris   32 straddling
#   fox         884/3752                       419                  33
#   husky      1102/3900                       522                  38
#   shiba_inu  1132/3966                       536                  38
#   deer       1440/4276                       686                  26
#   horse      1262/4400                       610                  32
# Every donor separates. The straddling count is the neck ring, and it is small
# because these rigs weight the neck to Neck3, not to Head.
#
# THE SEAM IS KEPT ON PURPOSE (keep_seam). A triangle with at least one
# head-weighted vertex is kept, so the extracted head ends in a short downward
# skirt of neck geometry rather than a clean hole. That skirt is what hides the
# graft: it overlaps the humanoid's own neck instead of revealing the inside of
# the skull. Dropping to pure-head triangles is available and tested, because it
# is the right answer when the donor's neck is thicker than the target's.
#
# THE OUTPUT IS DE-SKINNED. The extracted head becomes a plain MeshInstance3D
# under a BoneAttachment3D: no bones, no weights, no second Skeleton3D. That is
# the whole point — BoneAttachment3D moves NODES, and a node is now what we have.
# The ears stop being individually animatable (they were ear-bone driven on the
# donor); they ride the head bone as one piece. That trade is stated in the mount
# report rather than hidden.
#
# ORIENTATION IS MEASURED, NOT TABULATED — and the first version of this file
# got it wrong, so the reasoning is written down.
#
# The tempting shortcut is "an animal carries its head level, so just preserve
# the donor's world orientation". Measured on the real rigs, that is false:
#
#   donor (wolf/fox/horse)  side +X   up +Z   forward -Y     <- Z-up
#   target (UBC humanoid)   side +X   up +Y   forward +Z     <- Y-up
#
# Preserving donor orientation lands the muzzle pointing at the floor. The two
# rig families genuinely differ by a quarter turn, so a correction is required.
#
# It is DERIVED, per model, from three measurements (see rig_frame()):
#   side  the mean of every automatically discovered left/right bone pair
#         (17 pairs on the quadrupeds, 29 on the UBC rig). No bone-name table —
#         the pairing falls out of the .L/.R, _l/_r, Left/Right spellings.
#   up    for the DONOR head, where the ear/horn joints sit relative to the head
#         bone (ears are on top of the skull). For the TARGET body, where the
#         head bone sits relative to the rig root (the standing axis).
#   fwd   side x up.
#
# The cross product is why no "which way does it face" probe is needed at
# runtime: `side` is anatomically the same direction on both rigs (character's
# right -> left) and so is `up`, therefore side x up is the same anatomical
# direction on both, and mapping one frame onto the other cannot land the head
# backwards. The probes that CONFIRM this (the donor's muzzle, the target's
# toes) live in the test suite, where they pin the answer for every shipped
# model instead of running on every mount.
# =============================================================================

## A vertex counts as belonging to the extracted part when this much of its
## total skin weight sits on the wanted joints. 0.5 = "more than half", i.e. the
## joint set wins the vertex outright.
const DEFAULT_THRESHOLD := 0.5

## Joint-name patterns that make up "the head" on the Quaternius quadruped rigs.
## `String.match()` globs, so "Ear*" catches Ear1.L … Ear4.R without naming all
## eight. Anything a future donor adds (horns, antlers) gets a pattern here.
const DEFAULT_HEAD_JOINTS := ["Head", "Ear*", "Horn*", "Antler*", "Jaw*"]

## Below this many triangles the extraction is treated as a failure rather than
## shipped as a few stray polygons — a wrong joint-name guess produces exactly
## that, and it must be loud.
const MIN_TRIANGLES := 24


## The joints on `skeleton` matching any of `patterns`, as real bone names.
## Patterns are String.match() globs; an exact name is just a glob with no
## wildcard. Returns [] when the skeleton has none of them, which the caller
## must treat as "this rig is not a donor" rather than "extract everything".
static func resolve_joints(skeleton: Skeleton3D,
		patterns: PackedStringArray = PackedStringArray(DEFAULT_HEAD_JOINTS)) -> PackedStringArray:
	var out := PackedStringArray()
	if skeleton == null:
		return out
	for i in range(skeleton.get_bone_count()):
		var bone := skeleton.get_bone_name(i)
		for pattern in patterns:
			if bone == String(pattern) or bone.match(String(pattern)):
				out.append(bone)
				break
	return out


## Maps a surface's ARRAY_BONES values onto skeleton bone indices.
##
## THIS INDIRECTION IS THE EASY BUG. ARRAY_BONES does NOT hold skeleton bone
## indices: it holds indices into the mesh's Skin bind list. On a rig where the
## two happen to coincide a naive reading looks correct and then silently
## extracts the wrong body part on the next model. Resolved by bone index when
## the bind carries one and by NAME otherwise (glTF binds by name).
## An unskinned mesh maps every bind to -1.
static func bind_bone_indices(mi: MeshInstance3D, skeleton: Skeleton3D) -> PackedInt32Array:
	var out := PackedInt32Array()
	if mi == null or skeleton == null or mi.skin == null:
		return out
	for i in range(mi.skin.get_bind_count()):
		var bone := mi.skin.get_bind_bone(i)
		if bone < 0:
			bone = skeleton.find_bone(mi.skin.get_bind_name(i))
		out.append(bone)
	return out


## Every left/right bone pair a rig exposes, averaged into one lateral axis
## pointing from the character's RIGHT to its LEFT.
##
## Discovered from bone-name spellings rather than from a per-rig table, so a
## new vendor works without an edit here. Averaging every pair (rather than
## trusting one) is what makes it immune to a single oddly placed bone: the
## shipped rigs contribute 17 pairs (quadrupeds) and 29 (UBC humanoid).
## Returns {"axis": Vector3, "pairs": int}; axis is ZERO when the rig has no
## discoverable pair, which the caller must treat as "cannot align".
static func lateral_axis(skeleton: Skeleton3D) -> Dictionary:
	if skeleton == null:
		return {"axis": Vector3.ZERO, "pairs": 0}
	var left := Vector3.ZERO
	var right := Vector3.ZERO
	var pairs := 0
	for i in range(skeleton.get_bone_count()):
		var mate := _mirror_name(skeleton.get_bone_name(i))
		if mate.is_empty():
			continue
		var j := skeleton.find_bone(mate)
		if j < 0:
			continue
		left += skeleton.get_bone_global_rest(i).origin
		right += skeleton.get_bone_global_rest(j).origin
		pairs += 1
	if pairs == 0:
		return {"axis": Vector3.ZERO, "pairs": 0}
	return {"axis": ((left - right) / float(pairs)).normalized(), "pairs": pairs}


## An orthonormal anatomical frame (columns: side, up, forward) for a rig.
##
## `up_hint` is measured by the caller and only has to be approximately right —
## it is orthogonalised against the lateral axis, so a neck that leans forwards
## does not tilt the frame.
static func rig_frame(side: Vector3, up_hint: Vector3) -> Basis:
	if side.length_squared() < 0.000001 or up_hint.length_squared() < 0.000001:
		return Basis.IDENTITY
	var s := side.normalized()
	var u := up_hint - s * s.dot(up_hint)
	if u.length_squared() < 0.000001:
		return Basis.IDENTITY
	u = u.normalized()
	return Basis(s, u, s.cross(u).normalized())


## Where the extracted PART's "up" is: the ear/horn joints sit on top of the
## skull, so the offset from the primary bone to their centroid is the head's up.
## Falls back to the primary bone's own direction when the joint set is a lone
## bone (reported by the caller as a weaker measurement, never silently).
static func part_up(skeleton: Skeleton3D, primary: int,
		joint_names: PackedStringArray) -> Vector3:
	if skeleton == null or primary < 0:
		return Vector3.ZERO
	var origin := skeleton.get_bone_global_rest(primary).origin
	var sum := Vector3.ZERO
	var n := 0
	for name in joint_names:
		var i := skeleton.find_bone(String(name))
		if i < 0 or i == primary:
			continue
		sum += skeleton.get_bone_global_rest(i).origin
		n += 1
	if n > 0:
		return (sum / float(n)) - origin
	var parent := skeleton.get_bone_parent(primary)
	if parent < 0:
		return Vector3.ZERO
	return origin - skeleton.get_bone_global_rest(parent).origin


## Where the TARGET body's "up" is: the standing axis, from the rig root to the
## bone the part will hang off. Robust on any upright biped without naming a
## single bone.
static func body_up(skeleton: Skeleton3D, primary: int) -> Vector3:
	if skeleton == null or primary < 0:
		return Vector3.ZERO
	var root := 0
	for i in range(skeleton.get_bone_count()):
		if skeleton.get_bone_parent(i) < 0:
			root = i
			break
	return skeleton.get_bone_global_rest(primary).origin \
			- skeleton.get_bone_global_rest(root).origin


## The rotation carrying the donor's anatomical frame onto the target's. Both
## frames are orthonormal, so the transpose is the inverse and the result is a
## proper rotation — it can tilt or turn the head, never mirror it.
static func alignment(donor: Basis, target: Basis) -> Basis:
	return target * donor.transposed()


## The transform that bakes donor mesh-space vertices into the space a
## BoneAttachment3D on the TARGET rig expects.
##
## Three measured pieces and no constants:
##   bind   the head bind pose (the inverse bind matrix): mesh space -> head-bone
##          space. This removes the donor's neck/spine chain from the vertices,
##          and it is why the result is independent of where the animal stood.
##   rest   the head bone's global REST basis, ROTATION ONLY — puts the head back
##          in the donor's own skeleton-space orientation with its origin on the
##          bone. The rest TRANSLATION is deliberately dropped: keeping it would
##          re-add the donor's neck height and float the head above the target's
##          shoulders.
##   align  the donor-frame -> target-frame rotation from alignment(). Identity
##          when the caller has no target to align to, which is the honest
##          answer for "extract this head on its own".
static func bake_transform(skeleton: Skeleton3D, bone_index: int, bind: Transform3D,
		align: Basis = Basis.IDENTITY) -> Transform3D:
	if skeleton == null or bone_index < 0:
		return Transform3D(align, Vector3.ZERO) * bind
	var rest := skeleton.get_bone_global_rest(bone_index)
	return Transform3D(align * rest.basis, Vector3.ZERO) * bind


## The mirrored spelling of a bone name, or "" when the name is not a LEFT-side
## bone. Only the left spellings map, so each pair is counted exactly once.
static func _mirror_name(bone: String) -> String:
	for pair in [[".L", ".R"], ["_L", "_R"], [".l", ".r"], ["_l", "_r"]]:
		if bone.ends_with(pair[0]):
			return bone.substr(0, bone.length() - pair[0].length()) + pair[1]
	for pair in [["mixamorig_Left", "mixamorig_Right"], ["Left", "Right"],
			["left", "right"], ["L_", "R_"]]:
		if bone.begins_with(pair[0]):
			return pair[1] + bone.substr(pair[0].length())
	# VRM's J_Bip_L_UpperArm spells the side in the middle.
	if bone.contains("_L_"):
		return bone.replace("_L_", "_R_")
	return ""


## Splits one skinned MeshInstance3D, keeping only the geometry weighted to
## `joint_names`, and returns a NEW de-skinned ArrayMesh in baked space.
##
## Returns a report Dictionary — never null, never a half-built mesh:
##   mesh             ArrayMesh or null
##   vertices/triangles  what came out
##   seamTriangles    kept triangles that straddle the boundary (0 when
##                    keep_seam is false — then they were dropped)
##   surfacesKept/surfacesDropped  a surface contributing nothing is DROPPED,
##                    not added empty (the horse's Hooves surface has exactly
##                    zero head vertices and an empty surface is a render error)
##   error            "" on success
static func split(mi: MeshInstance3D, skeleton: Skeleton3D,
		joint_names: PackedStringArray, threshold: float = DEFAULT_THRESHOLD,
		keep_seam: bool = true, align: Basis = Basis.IDENTITY) -> Dictionary:
	var report := {
		"mesh": null, "vertices": 0, "triangles": 0, "seamTriangles": 0,
		"surfacesKept": 0, "surfacesDropped": 0, "error": "",
	}
	if mi == null or mi.mesh == null:
		report.error = "no mesh to split"
		return report
	if skeleton == null:
		report.error = "no skeleton — a bone-weight split needs the rig"
		return report
	if joint_names.is_empty():
		report.error = "empty joint set — refusing to extract the whole mesh"
		return report

	var wanted_bones := {}
	for name in joint_names:
		var idx := skeleton.find_bone(String(name))
		if idx >= 0:
			wanted_bones[idx] = true
	if wanted_bones.is_empty():
		report.error = "none of %s exist on this rig" % str(joint_names)
		return report

	var binds := bind_bone_indices(mi, skeleton)
	var wanted_binds := {}
	if binds.is_empty():
		# Unskinned, or a skin Godot resolved directly against the skeleton:
		# ARRAY_BONES then indexes bones, so the wanted set is already correct.
		wanted_binds = wanted_bones
	else:
		for bind_index in range(binds.size()):
			if wanted_bones.has(binds[bind_index]):
				wanted_binds[bind_index] = true
	if wanted_binds.is_empty():
		report.error = "the rig has the joints but this mesh binds none of them"
		return report

	var bake := Transform3D(align, Vector3.ZERO)
	var head_bone := _primary_bone(skeleton, joint_names)
	if not binds.is_empty() and head_bone >= 0:
		for bind_index in range(binds.size()):
			if binds[bind_index] == head_bone:
				bake = bake_transform(skeleton, head_bone,
						mi.skin.get_bind_pose(bind_index), align)
				break

	var out := ArrayMesh.new()
	var source := mi.mesh
	for s in range(source.get_surface_count()):
		var built := _split_surface(source, s, wanted_binds, threshold, keep_seam, bake)
		if built.is_empty():
			report.surfacesDropped += 1
			continue
		out.add_surface_from_arrays(Mesh.PRIMITIVE_TRIANGLES, built.arrays)
		var material := source.surface_get_material(s)
		if material != null:
			out.surface_set_material(out.get_surface_count() - 1, material)
			out.surface_set_name(out.get_surface_count() - 1, material.resource_name)
		report.surfacesKept += 1
		report.vertices += int(built.vertices)
		report.triangles += int(built.triangles)
		report.seamTriangles += int(built.seam)

	if report.triangles < MIN_TRIANGLES:
		report.error = "extracted only %d triangles from %d surfaces — the joint set %s is wrong for this rig" \
				% [report.triangles, source.get_surface_count(), str(joint_names)]
		return report
	report.mesh = out
	return report


## Loads nothing and touches no files: extracts the head of an ALREADY
## instantiated donor scene and hands back a ready MeshInstance3D plus the
## report. The node is unparented — the caller attaches it.
##
## `target_skeleton` is the rig the head is destined for. Passing it is what
## turns the donor's Z-up quadruped frame into the target's Y-up biped one;
## passing null extracts the head in its own frame (used by the tests that
## measure the donor in isolation) and says so via alignedTo.
static func extract_head(donor_root: Node3D, target_skeleton: Skeleton3D = null,
		patterns: PackedStringArray = PackedStringArray(DEFAULT_HEAD_JOINTS),
		threshold: float = DEFAULT_THRESHOLD, keep_seam: bool = true) -> Dictionary:
	var report := {"node": null, "error": "no donor scene", "joints": PackedStringArray()}
	if donor_root == null:
		return report
	var skeleton := CharForgeGlbLoader.find_skeleton(donor_root)
	if skeleton == null:
		report.error = "donor has no Skeleton3D"
		return report
	var joints := resolve_joints(skeleton, patterns)
	report.joints = joints
	if joints.is_empty():
		report.error = "donor rig exposes none of %s" % str(patterns)
		return report

	# --- the measured donor-frame -> target-frame rotation --------------------
	var align := Basis.IDENTITY
	var primary := _primary_bone(skeleton, joints)
	var donor_side: Dictionary = lateral_axis(skeleton)
	report.lateralPairs = int(donor_side.pairs)
	report.alignedTo = ""
	if target_skeleton != null and primary >= 0 and int(donor_side.pairs) > 0:
		var target_bone := -1
		for candidate in CharForgeRegistry.HEAD_BONE_CANDIDATES:
			target_bone = target_skeleton.find_bone(String(candidate))
			if target_bone >= 0:
				break
		var target_side: Dictionary = lateral_axis(target_skeleton)
		if target_bone >= 0 and int(target_side.pairs) > 0:
			var donor_frame := rig_frame(donor_side.axis, part_up(skeleton, primary, joints))
			var target_frame := rig_frame(target_side.axis,
					body_up(target_skeleton, target_bone))
			align = alignment(donor_frame, target_frame)
			report.alignedTo = target_skeleton.get_bone_name(target_bone)
		else:
			report.error = "target rig exposes no head bone or no left/right pair"
			return report

	# The biggest skinned surface set is the character; a donor with several
	# MeshInstance3Ds (fur cards, eyes as a separate node) contributes each of
	# them, so every one is split and the results merged into one mesh.
	var pieces: Array = []
	var total := {"vertices": 0, "triangles": 0, "seam": 0, "kept": 0, "dropped": 0}
	var errors: Array = []
	for node in donor_root.find_children("*", "MeshInstance3D", true, false):
		var mi := node as MeshInstance3D
		if mi.mesh == null:
			continue
		var split_report := split(mi, skeleton, joints, threshold, keep_seam, align)
		if split_report.mesh == null:
			errors.append(String(split_report.error))
			continue
		pieces.append(split_report.mesh)
		total.vertices += int(split_report.vertices)
		total.triangles += int(split_report.triangles)
		total.seam += int(split_report.seamTriangles)
		total.kept += int(split_report.surfacesKept)
		total.dropped += int(split_report.surfacesDropped)
	if pieces.is_empty():
		report.error = "; ".join(PackedStringArray(errors)) if not errors.is_empty() \
				else "donor had no skinned mesh"
		return report

	var merged: ArrayMesh = pieces[0]
	for i in range(1, pieces.size()):
		var extra: ArrayMesh = pieces[i]
		for s in range(extra.get_surface_count()):
			merged.add_surface_from_arrays(Mesh.PRIMITIVE_TRIANGLES, extra.surface_get_arrays(s))
			var material := extra.surface_get_material(s)
			if material != null:
				merged.surface_set_material(merged.get_surface_count() - 1, material)

	var instance := MeshInstance3D.new()
	instance.name = "DonorHead"
	instance.mesh = merged
	report.node = instance
	report.error = ""
	report.vertices = total.vertices
	report.triangles = total.triangles
	report.seamTriangles = total.seam
	report.surfacesKept = total.kept
	report.surfacesDropped = total.dropped
	return report


## Local-space height of a mesh (Y extent of its AABB). The scale-to-fit input.
static func mesh_height(mesh: Mesh) -> float:
	if mesh == null:
		return 0.0
	var box := mesh.get_aabb()
	return maxf(box.size.y, 0.0)


# =============================================================================
# internals
# =============================================================================

## The one bone the extracted part hangs from: the first joint whose name is a
## known head bone, else the shallowest joint in the set (the others are its
## children, so the shallowest is the root of the sub-chain).
static func _primary_bone(skeleton: Skeleton3D, joint_names: PackedStringArray) -> int:
	for candidate in CharForgeRegistry.HEAD_BONE_CANDIDATES:
		var idx := skeleton.find_bone(String(candidate))
		if idx >= 0 and String(candidate) in joint_names:
			return idx
	var best := -1
	var best_depth := 1 << 30
	for name in joint_names:
		var idx := skeleton.find_bone(String(name))
		if idx < 0:
			continue
		var depth := 0
		var walk := idx
		while skeleton.get_bone_parent(walk) >= 0:
			walk = skeleton.get_bone_parent(walk)
			depth += 1
		if depth < best_depth:
			best_depth = depth
			best = idx
	return best


## Rebuilds one surface from the triangles the joint set owns. Returns {} when
## the surface contributes nothing, so the caller can drop it entirely.
static func _split_surface(source: Mesh, surface: int, wanted_binds: Dictionary,
		threshold: float, keep_seam: bool, bake: Transform3D) -> Dictionary:
	var arrays: Array = source.surface_get_arrays(surface)
	if arrays.size() <= Mesh.ARRAY_VERTEX or arrays[Mesh.ARRAY_VERTEX] == null:
		return {}
	var verts: PackedVector3Array = arrays[Mesh.ARRAY_VERTEX]
	if verts.is_empty():
		return {}
	if arrays[Mesh.ARRAY_BONES] == null or arrays[Mesh.ARRAY_WEIGHTS] == null:
		return {}
	var bones: PackedInt32Array = arrays[Mesh.ARRAY_BONES]
	var weights: PackedFloat32Array = arrays[Mesh.ARRAY_WEIGHTS]
	# 4 or 8 influences per vertex (ARRAY_FLAG_USE_8_BONE_WEIGHTS). Derived, not
	# assumed: a hard-coded 4 reads garbage off an 8-weight rig.
	var influences := int(bones.size() / verts.size()) if verts.size() > 0 else 0
	if influences <= 0 or weights.size() < verts.size() * influences:
		return {}

	var owned := PackedFloat32Array()
	owned.resize(verts.size())
	for v in range(verts.size()):
		var sum := 0.0
		var base := v * influences
		for k in range(influences):
			if wanted_binds.has(bones[base + k]):
				sum += weights[base + k]
		owned[v] = sum

	var indices: PackedInt32Array
	if arrays[Mesh.ARRAY_INDEX] != null:
		indices = arrays[Mesh.ARRAY_INDEX]
	else:
		indices = PackedInt32Array()
		indices.resize(verts.size())
		for i in range(verts.size()):
			indices[i] = i
	if indices.size() < 3:
		return {}

	var normals: Variant = arrays[Mesh.ARRAY_NORMAL]
	var tangents: Variant = arrays[Mesh.ARRAY_TANGENT]
	var colors: Variant = arrays[Mesh.ARRAY_COLOR]
	var uv1: Variant = arrays[Mesh.ARRAY_TEX_UV]
	var uv2: Variant = arrays[Mesh.ARRAY_TEX_UV2]

	var out_verts := PackedVector3Array()
	var out_normals := PackedVector3Array()
	var out_tangents := PackedFloat32Array()
	var out_colors := PackedColorArray()
	var out_uv1 := PackedVector2Array()
	var out_uv2 := PackedVector2Array()
	var out_indices := PackedInt32Array()
	var remap := {}
	var seam := 0
	var normal_basis := bake.basis.inverse().transposed()

	for t in range(0, indices.size() - 2, 3):
		var tri := [indices[t], indices[t + 1], indices[t + 2]]
		var hits := 0
		for corner in tri:
			if owned[corner] >= threshold:
				hits += 1
		if hits == 0:
			continue
		if hits < 3:
			if not keep_seam:
				continue
			seam += 1
		for corner in tri:
			if not remap.has(corner):
				remap[corner] = out_verts.size()
				out_verts.append(bake * verts[corner])
				if normals != null:
					out_normals.append((normal_basis * (normals as PackedVector3Array)[corner]).normalized())
				if tangents != null:
					var raw: PackedFloat32Array = tangents
					var tv := Vector3(raw[corner * 4], raw[corner * 4 + 1], raw[corner * 4 + 2])
					tv = (bake.basis * tv).normalized()
					out_tangents.append_array(PackedFloat32Array([tv.x, tv.y, tv.z, raw[corner * 4 + 3]]))
				if colors != null:
					out_colors.append((colors as PackedColorArray)[corner])
				if uv1 != null:
					out_uv1.append((uv1 as PackedVector2Array)[corner])
				if uv2 != null:
					out_uv2.append((uv2 as PackedVector2Array)[corner])
			out_indices.append(int(remap[corner]))

	if out_indices.size() < 3:
		return {}

	var built: Array = []
	built.resize(Mesh.ARRAY_MAX)
	built[Mesh.ARRAY_VERTEX] = out_verts
	if normals != null:
		built[Mesh.ARRAY_NORMAL] = out_normals
	if tangents != null:
		built[Mesh.ARRAY_TANGENT] = out_tangents
	if colors != null:
		built[Mesh.ARRAY_COLOR] = out_colors
	if uv1 != null:
		built[Mesh.ARRAY_TEX_UV] = out_uv1
	if uv2 != null:
		built[Mesh.ARRAY_TEX_UV2] = out_uv2
	built[Mesh.ARRAY_INDEX] = out_indices
	return {
		"arrays": built,
		"vertices": out_verts.size(),
		"triangles": int(out_indices.size() / 3),
		"seam": seam,
	}
