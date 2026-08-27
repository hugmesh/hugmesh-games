class_name CharForgeRegistry
extends RefCounted

# =============================================================================
# ASSEMBLER V2 — the basis registry.
#
# Every head family and every body archetype resolves, through this registry,
# to a BASIS:
#
#   "procedural"  build it from the parametric primitives / lofted meshes in
#                 character_assembler.gd (the default for all 29 families, and
#                 the ONLY basis shipped today).
#   "mesh"        instantiate a GLB from the runtime loader instead, using the
#                 declared rig type and material-slot map.
#
# The indirection means an asset pack can replace a family's geometry without a
# single edit to the assembly logic, and — critically — that no procedural
# output changes when nothing is registered: default_registry() answers
# "procedural" for every family, so the shipped behaviour is byte-identical to
# the pre-registry assembler.
#
# LICENSE DISCIPLINE IS ENFORCED IN CODE, not by convention. A "mesh" entry is
# REJECTED (and the family stays procedural) unless it carries a complete
# provenance record — licenseSpdx / attribution / sourceUrl / retrievedAt —
# with an SPDX id on the allow-list. Only the CC0 / CC-BY / MIT verdicts from
# the research reports are admissible; there is deliberately no way to register
# a mesh basis without the manifest fields that THIRD_PARTY_ASSETS.md
# aggregates.
#
# A DEFAULT-CONSTRUCTED registry is still empty of mesh entries: procedural is
# and stays the fallback for every family. The populated registry now comes from
# CharForgePacks (scripts/pack_library.gd), which resolves the vendored packs'
# lane mapping into entries carrying the provenance this class demands. Nothing
# reaches a rig without a THIRD_PARTY_ASSETS.md entry behind it, and
# legal-inventory.md's verdict still stands where it hurts: there is no CC0
# rigged+animated anthro biped anywhere, so the kemono lane's primary basis is
# procedural and its mesh option is a CC0 BODY under a procedural beast head.
# =============================================================================

const BASIS_PROCEDURAL := "procedural"
const BASIS_MESH := "mesh"
const BASES := [BASIS_PROCEDURAL, BASIS_MESH]

## Rig types a mesh basis may declare.
##   humanoid  bones already renamed to SkeletonProfileHumanoid names (the
##             canonical rig; retargeting is baked at authoring/CI time because
##             runtime GLB loading performs NO BoneMap retargeting).
##   vrm       a VRM file — humanoid rig + A/I/U/E/O visemes + in-file licence
##             metadata; ingested through scripts/vrm_ingest.gd.
##   static    unrigged prop geometry (attachment pieces only).
const RIG_HUMANOID := "humanoid"
const RIG_VRM := "vrm"
const RIG_STATIC := "static"
const RIGS := [RIG_HUMANOID, RIG_VRM, RIG_STATIC]

## SPDX ids a vendored mesh basis may carry (the research verdicts, and
## nothing else). NC / ND / SA and "all rights reserved" are absent on purpose.
const ALLOWED_LICENSES := ["CC0-1.0", "CC-BY-3.0", "CC-BY-4.0", "MIT"]

## The GLB material-slot ingest CONTRACT (technique.md option B): a third-party
## GLB declares its regions by naming its material slots, and the loader's
## fixup pass swaps each one for the shared house material of that region.
const DEFAULT_SLOT_MAP := {
	"hm_fur": "fur",
	"hm_accent": "accent",
	"hm_detail": "detail",
	"hm_line": "line",
	"hm_iris": "iris",
	"hm_skin": "skin",
	"hm_hair": "hair",
	"hm_innermuzzle": "innerMuzzle",
	"hm_socks": "socks",
	"hm_innerear": "innerEar",
	"hm_tailtip": "tailTip",
}

## Bone names an attachment binds to, most-specific first.
##
## SkeletonProfileHumanoid / VRM names come first because that is the rig we
## conform to — but runtime GLB loading performs NO BoneMap retargeting, so what
## arrives is whatever the vendor authored, and the vendors disagree completely.
## Every name below was READ OUT of a rig we actually ship (the skin joint lists
## of the committed .glb/.vrm files), not guessed:
##
##   Quaternius UBC / outfits  root, pelvis, spine_01..03, neck_01, Head
##   Quaternius monsters       Root, Body, Hips, Abdomen, Torso, Neck, Head
##                             (the 4-bone chibis have only Body + Head*)
##   Quaternius animals        Body, Torso..Torso3, Neck1..3, Head
##   KayKit adventurers/undead root, hips, spine, chest, head   (NO neck bone)
##   VRoid VRM                 Root, J_Bip_C_Hips/Spine/Chest/UpperChest/Neck/Head
##   Godette VRM               pelvis_/tummy_/chest_/neck_/head_Armature
##   100Avatars VRM            mixamorig:Hips/Spine*/Neck/Head
##   Khronos Fox               b_Hip_01, b_Spine01_02, b_Neck_04, b_Head_05
##
## Godot sanitizes ':' out of imported bone names, so the mixamorig rigs are
## listed both ways. tests/run_tests.gd asserts that EVERY shipped character
## model resolves all four attachment slots against these lists — if a future
## pack lands with a rig nobody here knows, that test fails instead of the
## avatar silently losing its tail.
const HEAD_BONE_CANDIDATES := [
	"Head", "head", "J_Bip_C_Head", "head_Armature",
	"mixamorig:Head", "mixamorig_Head", "b_Head_05",
]
const SLOT_BONE_CANDIDATES := {
	"ears": HEAD_BONE_CANDIDATES,
	"hair": HEAD_BONE_CANDIDATES,
	"feature": HEAD_BONE_CANDIDATES,
	"tail": [
		"Hips", "hips", "J_Bip_C_Hips", "pelvis", "pelvis_Armature",
		"mixamorig:Hips", "mixamorig_Hips", "b_Hip_01",
		"Spine", "spine", "Body", "Root", "root",
	],
	"wings": [
		"UpperChest", "J_Bip_C_UpperChest", "Chest", "chest", "chest_Armature",
		"mixamorig:Spine2", "mixamorig_Spine2", "spine_03", "Torso3", "Torso",
		"b_Spine02_03", "tummy_Armature", "Spine", "spine", "Body", "Root", "root",
	],
	"accessory": [
		"Neck", "neck", "neck_01", "J_Bip_C_Neck", "neck_Armature",
		"mixamorig:Neck", "mixamorig_Neck", "Neck3", "b_Neck_04",
		"Chest", "chest", "chest_Armature", "spine_03", "Torso", "Spine", "spine",
		"Body", "Root", "root",
	],
}

## The jaw bone the mandatory lip-sync channel drives (SkeletonProfileHumanoid
## includes Jaw among its 56 bones, so this is profile-native).
##
## HONEST STATE OF THE ART: not one model in assets/packs has a jaw BONE — the
## audit's `jawBones` field is empty for all 75 files, and the four KayKit
## skeletons that used to claim one were a false positive off a mesh named
## "…_Jaw". Every vendored avatar therefore lip-syncs through the mouth-strip
## channel (or VRM visemes where present), and this list only matters for rigs a
## user imports or a future Blender-equipped pipeline produces.
const JAW_BONE_CANDIDATES := ["Jaw", "jaw", "J_Bip_C_Jaw", "jaw_Armature"]

var _heads: Dictionary = {}
var _bodies: Dictionary = {}
## Human-readable reasons the last register_* call rejected an entry.
var last_errors: PackedStringArray = PackedStringArray()


## A registry in which every family resolves to the procedural basis — i.e.
## exactly the pre-v2 behaviour.
static func default_registry() -> CharForgeRegistry:
	return CharForgeRegistry.new()


## The canonical procedural answer (also what an unknown family resolves to).
static func procedural_entry() -> Dictionary:
	return {"basis": BASIS_PROCEDURAL}


## Registers a head-family basis. Returns false and populates last_errors when
## the entry is malformed or its provenance is incomplete — in which case the
## family keeps resolving to "procedural" rather than half-loading an asset.
func register_head(family: String, entry: Dictionary) -> bool:
	return _register(_heads, family, entry, Char3DConfig.FAMILIES)


## Registers a body-archetype basis (same validation as register_head).
func register_body(archetype: String, entry: Dictionary) -> bool:
	return _register(_bodies, archetype, entry, Char3DConfig.FAMILIES)


## Head basis for a family. Never returns null: unknown/unregistered families
## resolve to procedural_entry().
func resolve_head(family: String) -> Dictionary:
	return _heads.get(family, procedural_entry()).duplicate(true)


## Body basis for an archetype (same contract as resolve_head).
func resolve_body(archetype: String) -> Dictionary:
	return _bodies.get(archetype, procedural_entry()).duplicate(true)


## True when the family/archetype pair needs the runtime GLB loader at all.
func needs_mesh(family: String, archetype: String) -> bool:
	return resolve_head(family).basis == BASIS_MESH \
			or resolve_body(archetype).basis == BASIS_MESH


## Every mesh entry currently registered, for THIRD_PARTY_ASSETS.md
## cross-checking and for a credits screen (CC-BY REQUIRES the attribution to
## be user-visible in the app).
func attributions() -> Array:
	var out: Array = []
	for source in [_heads, _bodies]:
		for key in source:
			var entry: Dictionary = source[key]
			if entry.get("basis") != BASIS_MESH:
				continue
			out.append({
				"key": key,
				"licenseSpdx": entry.get("licenseSpdx", ""),
				"attribution": entry.get("attribution", ""),
				"sourceUrl": entry.get("sourceUrl", ""),
				"retrievedAt": entry.get("retrievedAt", ""),
				"packId": entry.get("packId", ""),
				"modelId": entry.get("modelId", ""),
				"displayName": entry.get("displayName", ""),
			})
	out.sort_custom(func(a, b): return String(a.key) < String(b.key))
	return out


func _register(target: Dictionary, key: String, entry: Dictionary, allowed_keys: Array) -> bool:
	last_errors = PackedStringArray()
	if not (key in allowed_keys):
		last_errors.append("unknown family/archetype '%s'" % key)
		return false
	var basis := String(entry.get("basis", BASIS_PROCEDURAL))
	if not (basis in BASES):
		last_errors.append("basis must be one of %s" % str(BASES))
		return false
	if basis == BASIS_PROCEDURAL:
		target[key] = {"basis": BASIS_PROCEDURAL}
		return true

	var clean := {"basis": BASIS_MESH}
	var glb := String(entry.get("glb", ""))
	if not (glb.begins_with("res://") or glb.begins_with("user://")):
		last_errors.append("glb must be a res:// or user:// path")
	if not (glb.ends_with(".glb") or glb.ends_with(".gltf") or glb.ends_with(".vrm")):
		last_errors.append("glb must be a .glb/.gltf/.vrm file")
	clean["glb"] = glb

	var rig := String(entry.get("rig", ""))
	if not (rig in RIGS):
		last_errors.append("rig must be one of %s" % str(RIGS))
	clean["rig"] = rig

	# Single-atlas packs (KayKit, 100Avatars, the Khronos Fox) cannot be
	# region-recoloured at all: one material carries the whole character, so
	# binding it to a flat house material would paint the avatar one colour.
	# Those entries declare preserveMaterials and are exempt from the slot-map
	# requirement — they keep the vendor material and give up recolouring, which
	# is the honest trade rather than a silently wrong render.
	var preserve := bool(entry.get("preserveMaterials", false))
	clean["preserveMaterials"] = preserve

	# ATLAS REGIONS — how a single-atlas character recolours: {meshNodeName:
	# region}. Such an entry legitimately has NO slot map (there are no named
	# material slots to map) and legitimately preserves its vendor material,
	# which is the atlas the recolour reads its shading from.
	var atlas_regions := {}
	var atlas_raw: Variant = entry.get("atlasRegions", {})
	if atlas_raw is Dictionary:
		for node_name in (atlas_raw as Dictionary).keys():
			var atlas_region := String((atlas_raw as Dictionary)[node_name])
			if atlas_region in Char3DConfig.ALL_MATERIAL_REGIONS:
				atlas_regions[String(node_name)] = atlas_region
			else:
				last_errors.append("atlasRegions['%s'] names unknown region '%s'"
						% [node_name, atlas_region])
	clean["atlasRegions"] = atlas_regions

	# ATLAS CLUSTERS — the boundary-free path: {regionOrder: [...], clusters: n,
	# margin: f}. Every named region is validated here so a typo cannot reach the
	# shader as a silently wrong palette cell.
	var clusters := {}
	var clusters_raw: Variant = entry.get("atlasClusters", {})
	if clusters_raw is Dictionary and not (clusters_raw as Dictionary).is_empty():
		var spec: Dictionary = clusters_raw
		var order: Array = []
		var order_raw: Variant = spec.get("regionOrder", [])
		if order_raw is Array:
			for region_name in order_raw:
				if String(region_name) in Char3DConfig.ALL_MATERIAL_REGIONS:
					order.append(String(region_name))
				else:
					last_errors.append("atlasClusters.regionOrder names unknown region '%s'"
							% String(region_name))
		if order.is_empty():
			last_errors.append("atlasClusters needs a non-empty regionOrder")
		clusters = {
			"regionOrder": order,
			"clusters": int(spec.get("clusters", 4)),
			"margin": float(spec.get("margin", 0.06)),
		}
	clean["atlasClusters"] = clusters

	var slots_raw: Variant = entry.get("slots", DEFAULT_SLOT_MAP)
	var slots := {}
	if slots_raw is Dictionary:
		for slot_name in slots_raw.keys():
			var region := String(slots_raw[slot_name])
			if not (region in Char3DConfig.ALL_MATERIAL_REGIONS):
				last_errors.append("slot '%s' maps to unknown region '%s'" % [slot_name, region])
				continue
			slots[String(slot_name).to_lower()] = region
	else:
		last_errors.append("slots must be a Dictionary of materialName -> region")
	if slots.is_empty() and not preserve and atlas_regions.is_empty() and clusters.is_empty():
		last_errors.append("slots map is empty — a mesh basis must declare its regions")
	clean["slots"] = slots

	clean["visemes"] = bool(entry.get("visemes", false))
	clean["jawBone"] = String(entry.get("jawBone", ""))
	# TRUE when the mesh IS the avatar (the whole procedural rig is hidden);
	# FALSE for the mesh-body mount, where the procedural head survives and is
	# grafted onto the loaded rig's head bone.
	clean["wholeAvatar"] = bool(entry.get("wholeAvatar", false))
	# Catalogue provenance — carried so a credits screen can name the pack and
	# model a given avatar came from, not merely its licence string.
	for label in ["packId", "modelId", "key", "displayName", "lane"]:
		clean[label] = String(entry.get(label, ""))

	# HEAD DONOR — a SECOND vendored model whose head is split off by bone weight
	# at mount time and grafted onto this body's head bone (scripts/mesh_surgery.gd).
	# It is a separate third-party asset, so it carries its own licence fields and
	# is held to the same allow-list: an avatar that puts two vendors' geometry on
	# screen has to be able to credit both. A malformed donor REJECTS the whole
	# entry rather than silently mounting a bare body.
	var donor_raw: Variant = entry.get("headDonor", null)
	if donor_raw is Dictionary and not (donor_raw as Dictionary).is_empty():
		var donor: Dictionary = donor_raw
		var donor_glb := String(donor.get("glb", ""))
		if not donor_glb.begins_with("res://") or not donor_glb.ends_with(".glb"):
			last_errors.append("headDonor.glb must be a res:// .glb path")
		var donor_license := String(donor.get("licenseSpdx", ""))
		if not (donor_license in ALLOWED_LICENSES):
			last_errors.append("headDonor.licenseSpdx '%s' is not on the allow-list %s"
					% [donor_license, str(ALLOWED_LICENSES)])
		for field in ["attribution", "sourceUrl"]:
			if String(donor.get(field, "")).strip_edges().is_empty():
				last_errors.append("headDonor.%s is required" % field)
		if not _is_iso_date(String(donor.get("retrievedAt", ""))):
			last_errors.append("headDonor.retrievedAt must be an ISO yyyy-mm-dd date")
		var donor_slots := {}
		var donor_slots_raw: Variant = donor.get("slots", {})
		if donor_slots_raw is Dictionary:
			for slot_name in (donor_slots_raw as Dictionary).keys():
				var donor_region := String((donor_slots_raw as Dictionary)[slot_name])
				if donor_region in Char3DConfig.ALL_MATERIAL_REGIONS:
					donor_slots[String(slot_name).to_lower()] = donor_region
				else:
					last_errors.append("headDonor slot '%s' maps to unknown region '%s'"
							% [slot_name, donor_region])
		clean["headDonor"] = {
			"glb": donor_glb,
			"packId": String(donor.get("packId", "")),
			"modelId": String(donor.get("modelId", "")),
			"displayName": String(donor.get("displayName", "")),
			"licenseSpdx": donor_license,
			"attribution": String(donor.get("attribution", "")),
			"sourceUrl": String(donor.get("sourceUrl", "")),
			"retrievedAt": String(donor.get("retrievedAt", "")),
			"slots": donor_slots,
			"preserveMaterials": bool(donor.get("preserveMaterials", false)),
			"keepSeam": bool(donor.get("keepSeam", true)),
			"headJoints": donor.get("headJoints", []),
		}

	# --- provenance (license discipline, enforced) ---------------------------
	var license := String(entry.get("licenseSpdx", ""))
	if not (license in ALLOWED_LICENSES):
		last_errors.append("licenseSpdx '%s' is not on the allow-list %s"
				% [license, str(ALLOWED_LICENSES)])
	clean["licenseSpdx"] = license
	for field in ["attribution", "sourceUrl"]:
		var value := String(entry.get(field, "")).strip_edges()
		if value.is_empty():
			last_errors.append("%s is required for a mesh basis" % field)
		clean[field] = value
	var retrieved := String(entry.get("retrievedAt", ""))
	if not _is_iso_date(retrieved):
		last_errors.append("retrievedAt must be an ISO yyyy-mm-dd date")
	clean["retrievedAt"] = retrieved

	if not last_errors.is_empty():
		push_warning("CharForgeRegistry: rejected mesh basis for '%s': %s"
				% [key, ", ".join(last_errors)])
		return false
	target[key] = clean
	return true


static var _iso_re: RegEx = null


## `retrievedAt` must be a real calendar-shaped date, not merely ten digits:
## the field is legal evidence of WHEN a licence was observed, so "2026-99-99"
## has to be rejected as loudly as "yesterday".
static func _is_iso_date(text: String) -> bool:
	if _iso_re == null:
		_iso_re = RegEx.new()
		_iso_re.compile("^([0-9]{4})-([0-9]{2})-([0-9]{2})$")
	var match_result := _iso_re.search(text)
	if match_result == null:
		return false
	var month := int(match_result.get_string(2))
	var day := int(match_result.get_string(3))
	return month >= 1 and month <= 12 and day >= 1 and day <= 31
