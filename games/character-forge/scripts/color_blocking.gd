class_name CharForgeBlocking
extends RefCounted

# =============================================================================
# PROCEDURAL V2 — COLOUR BLOCKING (the last of the numeric style tables).
#
# jrpg-bases.md, verbatim: "60/30/10 color blocking (fur/accent/detail), skin
# 15-25% visible, line = darkest + outline color".
# kemono.md, verbatim:     "color blocks: base coat, lighter inner-muzzle +
# chest/belly, socks/gloves (forearm/paw + shin/foot), inner-ear, tail tip,
# hair tuft, iris (7-8 masks)".
#
# Those are rules about how much of the avatar each palette region COVERS, so
# unlike the other proportion tables they can only be checked against real
# geometry. measure() therefore does exactly that: it walks an assembled rig,
# sums true triangle area per palette region (vertices transformed by the
# accumulated node basis, so non-uniform scale is handled exactly) and returns
# the share each region occupies. check_*() then grades those shares against
# the bands below.
#
# WHY THIS EXISTS AS A MODULE RATHER THAN A COMMENT: before it, a default
# humanoid measured skin=0.85 / hair=0.11 with accent and detail at EXACTLY
# ZERO — three of the seven palette regions the whole recolor system exposes
# were invisible on the shipped avatar, and no test could see it. The bands
# here are the executable form of the two research rules, and the assembler's
# v2 blocking pass is written against them.
#
# HONEST LIMIT: area share is a proxy for what a viewer actually sees. It
# ignores occlusion (a block hidden inside the torso still counts) and camera
# framing. It is the right proxy for "is this region present at all and roughly
# in proportion", which is what the rules are about, and it is deterministic —
# but it is not a pixel measurement, and nothing here should be read as one.
# =============================================================================

## jrpg-bases.md 60/30/10, over the CLOTHED surface (the three garment regions
## normalised against each other). Skin/hair/iris/line are graded separately —
## the source states them as separate rules, so they are kept separate here.
const CLOTH_REGIONS := ["fur", "accent", "detail"]
const CLOTH_TARGETS := {"fur": 0.60, "accent": 0.30, "detail": 0.10}

## How far a measured cloth share may sit from its target. Primitive geometry
## is discrete — a rig cannot hit 0.600 exactly by adding whole capsules — so
## the rule is graded as a band. Chosen so that the ordering (dominant >
## secondary > trim) can never satisfy the check by accident: the bands around
## 0.60/0.30/0.10 do not overlap at +-0.10.
const CLOTH_TOLERANCE := 0.10

## jrpg-bases.md "skin 15-25% visible".
##
## SCOPE — this is graded over the avatar BELOW THE NECK, and check_jrpg must be
## fed shares measured with the head subtree skipped (CharacterAssembler
## .blocking_report does exactly that). The rule is a COSTUME rule: how much of
## the body the outfit leaves bare. Graded over the whole avatar instead, the
## answer would be dominated by head size — the same source puts a chibi's head
## at 38-42% of the figure, so a 2.5-head chibi's face alone blows past 25%
## while wearing the identical outfit as a 6.5-head hero. Head size is a
## PROPORTION rule, and CharForgeProportions already grades it.
const SKIN_VISIBLE_BAND := Vector2(0.15, 0.25)

## kemono.md's colour blocks that must actually exist on a kemono avatar.
## (`hair tuft` and `iris` are the other two masks it names; both are already
## unconditional parts of the head build, so they are asserted as present
## rather than banded.)
const KEMONO_BLOCKS := ["innerMuzzle", "socks", "innerEar"]
## A block counts as "present" above this share of the whole avatar — small,
## because these are accents (a sock is not meant to rival the coat), but far
## above zero so a region that is merely declared and never built still fails.
const KEMONO_BLOCK_FLOOR := 0.004
## kemono.md's "base coat" must remain the dominant read.
const KEMONO_COAT_FLOOR := 0.40


## Measured area share per palette region for an assembled rig.
## Returns {region: share} summing to 1.0 (empty when the rig has no geometry).
## Regions are read off the material's resource_name ("charforge_<region>",
## the name CharForgeMaterials stamps); anything else lands under "_other".
##
## `skip` is a list of Nodes whose whole subtree is excluded — the scoping knob
## the SKIN_VISIBLE_BAND doc explains (pass the head to grade the body alone).
##
## Works on a rig that is NOT inside the SceneTree — the transform is
## accumulated by hand rather than read from global_transform, which returns
## an identity (and pushes an error) outside the tree.
static func measure(root: Node3D, skip: Array = []) -> Dictionary:
	var by_region := {}
	var total := 0.0
	var stack: Array = [[root, Transform3D.IDENTITY]]
	while not stack.is_empty():
		var item: Array = stack.pop_back()
		var node: Node = item[0]
		var xform: Transform3D = item[1]
		if node in skip:
			continue
		if node is Node3D:
			xform = xform * (node as Node3D).transform
		if node is MeshInstance3D:
			var mi := node as MeshInstance3D
			if mi.mesh != null:
				var region := region_of(mi)
				var area := mesh_area(mi.mesh, xform.basis)
				by_region[region] = float(by_region.get(region, 0.0)) + area
				total += area
		for child in node.get_children():
			stack.append([child, xform])
	if total <= 0.0:
		return {}
	var shares := {}
	for region in by_region:
		shares[region] = float(by_region[region]) / total
	return shares


## The palette region a MeshInstance3D renders as. material_override wins (the
## assembler always sets it); surface materials are the fallback so a
## fixed-up GLB measures too.
static func region_of(mi: MeshInstance3D) -> String:
	var mat: Material = mi.material_override
	if mat == null:
		mat = mi.get_surface_override_material(0)
	if mat == null and mi.mesh != null and mi.mesh.get_surface_count() > 0:
		mat = mi.mesh.surface_get_material(0)
	if mat == null:
		return "_other"
	var name := String(mat.resource_name)
	if not name.begins_with("charforge_"):
		return "_other"
	var region := name.substr("charforge_".length())
	return region if region in Char3DConfig.ALL_MATERIAL_REGIONS else "_other"


## True surface area of a mesh under a basis (each triangle's edges are
## transformed before the cross product, so shear/non-uniform scale is exact
## rather than approximated by an average scale factor).
static func mesh_area(mesh: Mesh, basis: Basis) -> float:
	var total := 0.0
	for surface in range(mesh.get_surface_count()):
		var arrays: Array = mesh.surface_get_arrays(surface)
		if arrays.is_empty():
			continue
		var verts: PackedVector3Array = arrays[Mesh.ARRAY_VERTEX]
		if verts.is_empty():
			continue
		var indices := PackedInt32Array()
		if arrays[Mesh.ARRAY_INDEX] != null:
			indices = arrays[Mesh.ARRAY_INDEX]
		if indices.is_empty():
			for i in range(0, verts.size() - 2, 3):
				total += _tri_area(basis * verts[i], basis * verts[i + 1], basis * verts[i + 2])
		else:
			for i in range(0, indices.size() - 2, 3):
				total += _tri_area(basis * verts[indices[i]], basis * verts[indices[i + 1]],
						basis * verts[indices[i + 2]])
	return total


## The three cloth regions renormalised against each other — the form the
## 60/30/10 rule is actually stated in. {} when no cloth region has any area.
static func cloth_shares(shares: Dictionary) -> Dictionary:
	var sum := 0.0
	for region in CLOTH_REGIONS:
		sum += float(shares.get(region, 0.0))
	if sum <= 0.0:
		return {}
	var out := {}
	for region in CLOTH_REGIONS:
		out[region] = float(shares.get(region, 0.0)) / sum
	return out


## Grades measured shares against the jrpg-bases.md rules. `shares` must be
## BODY-SCOPED (head subtree skipped) — see SKIN_VISIBLE_BAND.
## -> {"ok": bool, "reasons": PackedStringArray, "cloth": Dictionary}
static func check_jrpg(shares: Dictionary) -> Dictionary:
	var reasons := PackedStringArray()
	var cloth := cloth_shares(shares)
	if cloth.is_empty():
		reasons.append("no cloth region (fur/accent/detail) has any area at all")
	else:
		for region in CLOTH_REGIONS:
			var target: float = CLOTH_TARGETS[region]
			var got: float = cloth[region]
			if absf(got - target) > CLOTH_TOLERANCE:
				reasons.append("%s share %.3f is outside %.2f +- %.2f"
						% [region, got, target, CLOTH_TOLERANCE])
	var skin: float = float(shares.get("skin", 0.0))
	if skin < SKIN_VISIBLE_BAND.x or skin > SKIN_VISIBLE_BAND.y:
		reasons.append("skin share %.3f is outside the visible band %.2f-%.2f"
				% [skin, SKIN_VISIBLE_BAND.x, SKIN_VISIBLE_BAND.y])
	return {"ok": reasons.is_empty(), "reasons": reasons, "cloth": cloth}


## Grades measured shares against the kemono.md colour-block list.
static func check_kemono(shares: Dictionary) -> Dictionary:
	var reasons := PackedStringArray()
	var coat: float = float(shares.get("fur", 0.0))
	if coat < KEMONO_COAT_FLOOR:
		reasons.append("base coat (fur) share %.3f is below the %.2f dominance floor"
				% [coat, KEMONO_COAT_FLOOR])
	for block in KEMONO_BLOCKS:
		var got: float = float(shares.get(block, 0.0))
		if got < KEMONO_BLOCK_FLOOR:
			reasons.append("colour block '%s' share %.4f is below the %.4f presence floor"
					% [block, got, KEMONO_BLOCK_FLOOR])
	return {"ok": reasons.is_empty(), "reasons": reasons}


# =============================================================================
# "line = darkest" (jrpg-bases.md)
# =============================================================================

## Every region whose colour the line has to beat. `line` itself is excluded,
## and so are regions that are not surface colours of the avatar body.
const LINE_COMPARED_REGIONS := ["fur", "accent", "detail", "skin", "hair", "iris",
		"innerMuzzle", "socks", "innerEar", "tailTip"]

## How much darker than the darkest region the line is pushed when it has to be
## corrected — enough to read as linework rather than as another coat block.
const LINE_DARKEN_MARGIN := 0.55


## True when the `line` region is the darkest colour in a materials dictionary
## (by perceptual luminance). Regions the dictionary omits are ignored: absent
## extended regions derive from present ones, and the derivations only ever
## lighten, so they cannot beat a line that already beats their source.
static func is_line_darkest(materials: Dictionary) -> bool:
	return _offending_region(materials) == ""


## The materials dictionary with `line` guaranteed darkest: unchanged when the
## rule already holds, otherwise `line` is re-derived by darkening the darkest
## offending colour (keeping its HUE, so the linework still belongs to the
## palette instead of going flat black).
static func enforce_line_darkest(materials: Dictionary) -> Dictionary:
	var out: Dictionary = materials.duplicate(true)
	var offender := _offending_region(out)
	if offender == "":
		return out
	var darkest := Color.from_string(String(out[offender]), Color.BLACK)
	var fixed := darkest.darkened(LINE_DARKEN_MARGIN)
	fixed.a = 1.0
	out["line"] = "#" + fixed.to_html(false)
	return out


## The region that is darker than `line`, or "" when none is. Picks the
## DARKEST such region so one pass of enforce_line_darkest always suffices.
static func _offending_region(materials: Dictionary) -> String:
	if not (materials.get("line") is String and Char3DConfig.valid_hex(materials["line"])):
		return ""
	var line_lum := Color.from_string(String(materials["line"]), Color.BLACK).get_luminance()
	var worst := ""
	var worst_lum := line_lum
	for region in LINE_COMPARED_REGIONS:
		if not (materials.get(region) is String and Char3DConfig.valid_hex(materials[region])):
			continue
		var lum := Color.from_string(String(materials[region]), Color.WHITE).get_luminance()
		if lum < worst_lum:
			worst = region
			worst_lum = lum
	return worst


static func _tri_area(a: Vector3, b: Vector3, c: Vector3) -> float:
	return (b - a).cross(c - a).length() * 0.5
