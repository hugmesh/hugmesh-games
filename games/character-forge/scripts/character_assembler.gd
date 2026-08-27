class_name CharacterAssembler
extends Node3D

# =============================================================================
# CANONICAL COPY — hugmesh-character-forge-bff-api
#   godot/character-forge/scripts/character_assembler.gd
#
# This file is THE shared 3D character assembler for the HugMesh platform
# (DESIGN §2). Other repos (e.g. hugmesh.games games/shadows-of-the-net)
# vendor byte-copies of it with a provenance header pointing back here.
# Change HERE first, then re-sync the vendored copies.
# =============================================================================
#
# Builds a stylized Node3D rig from a Char3DConfig v1 dictionary using ONLY
# parameterized primitive meshes (Capsule/Sphere/Cylinder/Box/Prism/Torus) —
# fleet precedent: zero glTF assets exist; all existing 3D is primitives.
#
# Rig shape:   root -> Rig -> Body -> Chest -> Neck -> Head -> Jaw
# with attachment points EarL/EarR, HornMount, FeatureMount, HairMount on the
# head and TailMount / WingMount / AccessoryMount on the body. Each of the 29
# head families produces a VISUALLY DISTINCT head via its `_head_spec` row;
# the 7 part slots (tail/wings/ears/feature/accessory/markings/hair) become
# attachment assemblies keyword-matched off the VN partId.
#
# Expressions are the canonical 10-label vocabulary applied as pose/scale
# presets; mouth `openness` 0..1 (the platform amplitude contract) drives the
# jaw through CharForgeLipSync.
#
# =============================== ASSEMBLER V2 ================================
# Four indirections were added on top of the original primitive assembler, all
# of them ADDITIVE — with nothing registered and the v2 proportion presets not
# selected, every family renders exactly as it did before:
#
#  1. MATERIALS ride CharForgeMaterials: one house-toon ShaderMaterial per
#     palette region, all sharing ONE runtime 8x8 palette texture per avatar
#     (technique.md's palette-strip verdict). Recolouring is a 64-pixel image
#     rebuild that touches no material at all. The inverted-hull outline is a
#     shared next_pass behind a quality toggle that DEFAULTS OFF.
#  2. BASIS registry (CharForgeRegistry): every head family / body archetype
#     resolves to basis "procedural" (the code below) or "mesh" (a GLB loaded
#     at runtime through CharForgeGlbLoader with a declared rig type and
#     material-slot map). The shipped registry is all-procedural; a mesh basis
#     is REJECTED unless it carries complete CC0/CC-BY/MIT provenance.
#  3. PROPORTIONS (CharForgeProportions): the kemono.md / jrpg-bases.md numeric
#     tables. Kemono families and humanoids get lofted, correctly-proportioned
#     heads; every other family stays on the `legacy` preset whose numbers
#     reproduce the pre-v2 output exactly.
#  4. LIP-SYNC (CharForgeLipSync): jaw as the mandatory channel, with the
#     mouth-strip and VRM-viseme channels available for imported rigs.
#  5. COLOUR BLOCKING (CharForgeBlocking): the jrpg-bases.md 60/30/10 garment
#     split + 15-25% bare skin, and the kemono.md coat/sock/countershade colour
#     blocks, realized by re-regioning body parts per lane. Legacy families have
#     no plan and keep _body_region() everywhere. blocking_report() grades the
#     assembled rig against its own lane.
#
# register_part_builder() additionally lets a part library override any single
# slot's builder without touching the assembly logic.

signal rebuilt(config: Dictionary)

## Emitted when the resolved basis for the head or body is "mesh": the caller
## owns fetching the bytes and driving CharForgeGlbLoader (the loader is async
## and this class is synchronous). The procedural rig is built and shown in the
## meantime, so a slow/failed fetch degrades to the parametric avatar rather
## than to nothing. `kind` is "head" or "body".
signal mesh_basis_required(kind: String, entry: Dictionary)

## Sanitized Char3DConfig currently realized (never assign directly — use apply_config).
var config: Dictionary = Char3DConfig.defaults()
var expression: String = "neutral"
var openness: float = 0.0

## The resolved proportion row for `config` (CharForgeProportions.for_config).
var proportions: Dictionary = CharForgeProportions.PRESETS["legacy"].duplicate()

# =============================================================================
# PROPORTION AXES — Char3DConfig body.legs / body.arms / body.limb.
#
# `legs` and `arms` are limb LENGTH; `limb` is limb GIRTH. With the existing
# height/build/curve/top that is the whole shape surface, and every axis is
# centred at 0.5 — so the DEFAULT body is androgynous by construction rather
# than by picking a neutral preset, and there is no gendered starting point.
#
# MIGRATION CONTRACT (identical to curve/top): every band below is centred on
# the pre-axis literal, so 0.5 rebuilds today's silhouette EXACTLY — lerpf(lo,
# hi, 0.5) lands on 1.0 and _hip_lift lands on 0.0 to the last float32 bit.
# Every stored definition predates these keys and the BFF has no migration
# mechanism (Liquibase 001, definition is jsonb), so absent -> 0.5 MUST be a
# no-op. run_tests.gd asserts it family by family.
#
# WHY THE BANDS DIFFER BY FAMILY — "reasonable constraints for humanoid or
# furry type" is not decoration. A plantigrade leg is ONE capsule and tolerates
# a wide stretch. A digitigrade leg (kemono.md) is a THREE-segment chain of
# raked thigh/shin/ankle whose angles only read as digitigrade near their
# authored proportions; stretching it as hard as a humanoid leg straightens the
# rake into a stilt. So the furry lane gets the tighter band, and it is tighter
# by measurement of what the pose survives, not by taste.
const LEG_LEN_BAND := Vector2(0.80, 1.20)              # plantigrade / humanoid
const LEG_LEN_BAND_DIGITIGRADE := Vector2(0.88, 1.12)  # kemono three-segment leg
const ARM_LEN_BAND := Vector2(0.85, 1.15)
const LIMB_GIRTH_BAND := Vector2(0.75, 1.25)
## The plantigrade leg capsule the default branch builds, at scale 1. Used to
## convert a leg-length multiplier into the hip lift that keeps feet on the floor.
const NOMINAL_LEG_HEIGHT := 0.5
## The digitigrade chain at scale 1, paw (0.035) to thigh top (0.48 + 0.13).
const NOMINAL_DIGITIGRADE_LEG_HEIGHT := 0.6
## Families whose legs end in a hoof — never digitigrade, so they keep the wide band.
const HOOFED_FAMILIES := ["equine", "cervine", "bovine", "caprine", "suid"]

## Resolved per rebuild by _resolve_proportion_axes(). 1.0 / 0.0 == the
## pre-axis silhouette.
var _leg_len := 1.0
var _arm_len := 1.0
var _limb_girth := 1.0
## How far the hips (and EVERYTHING above them) rise so a longer leg makes a
## taller character instead of one sunk through the floor.
var _hip_lift := 0.0
## Basis registry consulted on every rebuild. Swap in a populated one to route
## families through the GLB loader.
var registry: CharForgeRegistry = CharForgeRegistry.default_registry()
## The avatar's material set (palette texture + per-region house materials).
var materials: CharForgeMaterials = null
## Mouth driver — bound to the procedural Jaw node on every rebuild.
var lip_sync: CharForgeLipSync = CharForgeLipSync.new()

var _mats: Dictionary = {}
var _refs: Dictionary = {}
## Each rig joint's position/scale as BUILT (before expression + rig), so a rig
## adjustment composes on top of the family's placement instead of drifting.
var _rig_base: Dictionary = {}
var _extra_builders: Array = []
var _white_mat: Material = null
var _blush_mat: Material = null

# Expression presets: pose/scale deltas applied to the named rig refs.
#   jaw_base   0..1 extra jaw drop baked into the expression
#   eye_open   eyelid scale (1 = wide open), eye_l/eye_r override per side
#   brow_tilt  degrees, + = inner ends up (sad), - = inner ends down (angry)
#   brow_raise vertical offset in head-local units
#   ear_pitch  degrees, + = drooped back, - = perked forward
#   head_tilt  degrees around z
#   blush      show the cheek blush discs
#   brow_asym  raise only the right brow (thinking/smug)
#   lid_delta  degrees ADDED to the neutral upper-lid droop (kemono.md calls
#              the upper-lid angle "THE expression driver"; + = heavier lid,
#              - = wider-open eye). Only v2 heads have real lid nodes; on
#              legacy heads this key is inert.
const EXPRESSION_PRESETS := {
	"neutral": {},
	"happy": {"jaw_base": 0.25, "brow_raise": 0.012, "ear_pitch": -14.0, "lid_delta": 6.0},
	"sad": {"brow_tilt": 14.0, "ear_pitch": 24.0, "eye_open": 0.7, "head_tilt": -4.0, "lid_delta": 9.0},
	"angry": {"brow_tilt": -16.0, "eye_open": 0.8, "jaw_base": 0.15, "ear_pitch": 12.0, "lid_delta": 14.0},
	"surprised": {"brow_raise": 0.028, "eye_open": 1.35, "jaw_base": 0.55, "ear_pitch": -20.0, "lid_delta": -12.0},
	"embarrassed": {"blush": true, "eye_open": 0.85, "head_tilt": 6.0, "brow_tilt": 8.0, "jaw_base": 0.1, "lid_delta": 4.0},
	"thinking": {"brow_asym": true, "eye_open": 0.9, "head_tilt": 8.0, "jaw_base": 0.05, "lid_delta": 7.0},
	"wink": {"eye_l": 0.08, "jaw_base": 0.2, "ear_pitch": -10.0, "lid_delta": 3.0},
	"sleepy": {"eye_open": 0.22, "head_tilt": -6.0, "jaw_base": 0.1, "ear_pitch": 20.0, "lid_delta": 26.0},
	"smug": {"brow_asym": true, "eye_open": 0.65, "head_tilt": -5.0, "jaw_base": 0.12, "lid_delta": 11.0},
}


func _ready() -> void:
	if get_child_count() == 0:
		rebuild()


## Sanitizes and realizes a Char3DConfig (any Dictionary is safe to pass).
func apply_config(cfg: Dictionary) -> void:
	config = Char3DConfig.sanitize(cfg)
	rebuild()


func set_expression(label: String) -> void:
	if label in Char3DConfig.EXPRESSIONS:
		expression = label
		_apply_expression()


## Mouth openness 0..1 (amplitude contract) -> jaw pitch.
func set_openness(v: float) -> void:
	openness = clampf(v, 0.0, 1.0)
	_apply_jaw()


## Replace the rig adjustments and re-pose — NO rebuild, so a slider drag is a
## per-frame-cheap edit the host sees live. The stored config carries the
## sanitized rig (sparse; `{}` drops the key), exactly what a save persists.
func set_rig(rig: Dictionary) -> void:
	var clean: Dictionary = Char3DConfig.sanitize_rig(rig)
	if clean.is_empty():
		config.erase("rig")
	else:
		config["rig"] = clean
	_apply_expression()


## A rig fitted to the head this family actually built: eyes on the upper third
## and a fixed span apart, brows a step above them, the jaw on the lower third,
## ears at the top corners — each expressed as an OFFSET from the family's own
## placement, so "auto" is a starting point the user can still nudge. Nothing
## is rebuilt: the head's mesh bounds are measured and the config updated.
func auto_adjust_rig() -> Dictionary:
	var head: Node3D = _refs.get("head")
	if head == null:
		return {}
	var box: AABB = _head_bounds(head)
	if box.size == Vector3.ZERO:
		return {}
	var c: Vector3 = box.get_center()
	var h: float = box.size.y
	var w: float = box.size.x
	var front: float = box.position.z + box.size.z
	var targets: Dictionary = {
		"eye_l": Vector3(c.x + 0.24 * w, c.y + 0.12 * h, front - 0.08 * box.size.z),
		"eye_r": Vector3(c.x - 0.24 * w, c.y + 0.12 * h, front - 0.08 * box.size.z),
		"brow_l": Vector3(c.x + 0.24 * w, c.y + 0.26 * h, front - 0.10 * box.size.z),
		"brow_r": Vector3(c.x - 0.24 * w, c.y + 0.26 * h, front - 0.10 * box.size.z),
		"jaw": Vector3(c.x, c.y - 0.22 * h, c.z + 0.04 * box.size.z),
		"ear_l": Vector3(c.x + 0.42 * w, c.y + 0.40 * h, c.z),
		"ear_r": Vector3(c.x - 0.42 * w, c.y + 0.40 * h, c.z),
	}
	var rig: Dictionary = {}
	for joint in targets:
		var base: Variant = _rig_base.get(joint)
		if base == null:
			continue
		var delta: Vector3 = targets[joint] - base.pos
		delta = delta.clamp(-Vector3.ONE * Char3DConfig.RIG_OFFSET_MAX,
				Vector3.ONE * Char3DConfig.RIG_OFFSET_MAX)
		if delta.length() < 0.0005:
			continue
		rig[joint] = {"offset": [snappedf(delta.x, 0.001), snappedf(delta.y, 0.001), snappedf(delta.z, 0.001)]}
	set_rig(rig)
	return config.get("rig", {})


## Union AABB of the head's own meshes in the HEAD's space (mounted parts such as
## ears/hair hang off anchors and are excluded, so a hat cannot move the eyes).
func _head_bounds(head: Node3D) -> AABB:
	var box := AABB()
	var first := true
	for child in head.get_children():
		if child is MeshInstance3D and child.mesh != null:
			var mi: MeshInstance3D = child
			var local: AABB = mi.transform * mi.mesh.get_aabb()
			if first:
				box = local
				first = false
			else:
				box = box.merge(local)
	return box


func _remember_rig_bases() -> void:
	_rig_base.clear()
	for joint in Char3DConfig.RIG_JOINTS:
		var n: Node3D = _refs.get(joint)
		if n != null:
			_rig_base[joint] = {"pos": n.position, "scale": n.scale}


## Fold config.rig onto the posed joints. Runs LAST in _apply_expression, so an
## expression writes its absolute pose first and the user's nudge rides on top.
func _apply_rig() -> void:
	var rig: Variant = config.get("rig")
	if not (rig is Dictionary):
		return
	for joint in rig:
		var n: Node3D = _refs.get(joint)
		var base: Variant = _rig_base.get(joint)
		if n == null or base == null:
			continue
		var entry: Dictionary = rig[joint]
		var off: Variant = entry.get("offset")
		if off is Array and off.size() == 3:
			n.position += Vector3(float(off[0]), float(off[1]), float(off[2]))
		var scl: Variant = entry.get("scale")
		if scl is Array and scl.size() == 3:
			n.scale *= Vector3(float(scl[0]), float(scl[1]), float(scl[2]))


## Recolour without rebuilding geometry: rewrites the shared 64-pixel palette
## in place (technique.md's whole point — no material is reassigned). Returns
## false when the change is structural and a full rebuild is required.
func recolor(new_materials: Dictionary) -> bool:
	if materials == null:
		return false
	var next: Dictionary = config.duplicate(true)
	for region in new_materials:
		if region in Char3DConfig.ALL_MATERIAL_REGIONS \
				and new_materials[region] is String \
				and Char3DConfig.valid_hex(new_materials[region]):
			next.materials[region] = new_materials[region]
	config = next
	materials.recolor(config)
	return true


## Inverted-hull outline quality toggle (fleet-wide; DEFAULTS OFF). Rebuilds so
## the shared next_pass is applied/removed on every material at once.
func set_outline_enabled(enabled: bool) -> void:
	if CharForgeMaterials.outline_enabled == enabled:
		return
	CharForgeMaterials.outline_enabled = enabled
	rebuild()


## GLB-library indirection: builders registered here take priority over the
## built-in procedural ones. matcher: func(part_id: String) -> bool;
## builder: func(assembler: CharacterAssembler, part_id: String) -> Node3D.
func register_part_builder(slot: String, matcher: Callable, builder: Callable) -> void:
	_extra_builders.push_front({"slot": slot, "matcher": matcher, "builder": builder})


## Tears down and re-assembles the whole rig from `config`.
func rebuild() -> void:
	for child in get_children():
		remove_child(child)
		child.free()
	_refs.clear()
	proportions = CharForgeProportions.for_config(config)
	_mats = _make_materials()

	var family: String = config.head.family
	var archetype: String = config.body.archetype
	var rig := Node3D.new()
	rig.name = "Rig"
	add_child(rig)
	var width := lerpf(0.78, 1.32, config.body.build)
	rig.scale = Vector3(width, config.body.height, width)
	_refs["rig"] = rig

	_build_body(rig, family)
	_build_head(_refs["neck_top"], family)
	_build_slot_parts(family)
	_build_v2_blocking()
	_remember_rig_bases()
	_apply_expression()

	# Basis indirection: the procedural rig above is ALWAYS built; when a
	# family is registered as a mesh basis the caller is told to fetch it and
	# swap it in on top (the loader is asynchronous — see the signal docs).
	var head_entry := registry.resolve_head(family)
	if head_entry.basis == CharForgeRegistry.BASIS_MESH:
		mesh_basis_required.emit("head", head_entry)
	var body_entry := registry.resolve_body(archetype)
	if body_entry.basis == CharForgeRegistry.BASIS_MESH:
		mesh_basis_required.emit("body", body_entry)

	rebuilt.emit(config)


## The resolved basis for the current config: {"head": ..., "body": ...}.
func resolved_bases() -> Dictionary:
	return {
		"head": registry.resolve_head(config.head.family),
		"body": registry.resolve_body(config.body.archetype),
	}


## A named node of the rig just built ("rig", "body", "head", "jaw",
## "neck_top", the *_mount anchors …), or null when this config has none.
##
## Read-only accessor for the mesh-basis mount (scripts/pack_mount.gd), which
## has to hide the procedural rig and re-parent the procedural head onto a
## loaded skeleton. Every ref is rebuilt by rebuild(), so a caller must never
## cache what it gets back across a config change.
func rig_ref(ref_name: String) -> Node3D:
	var node: Variant = _refs.get(ref_name)
	return node as Node3D if node is Node3D else null


# =============================================================================
# Materials — the 11 palette regions -> house toon ShaderMaterials sharing one
# runtime palette texture (CharForgeMaterials). Slime/robot/synth express
# their look through shader-uniform variants rather than a different material
# class, so every avatar surface stays on the one shared shader.
# =============================================================================

func _make_materials() -> Dictionary:
	var family: String = config.head.family
	materials = CharForgeMaterials.for_config(config)
	var mats := {}
	for region in Char3DConfig.ALL_MATERIAL_REGIONS:
		mats[region] = materials.region(region)
	if family == "slime":
		mats["fur"] = materials.variant("fur", {"alpha": 0.78, "rim_strength": 0.8})
	elif family == "robot" or family == "synth":
		# Hard 3-band ramp + a strong rim reads as a machined surface under the
		# toon shader; there is no metallic term to reach for (and PBR metal
		# would look wrong next to the flat coat blocks anyway).
		for region in ["fur", "detail"]:
			mats[region] = materials.variant(region, {"band_cuts": 2.0, "shadow_tone": 0.4,
					"rim_strength": 0.9, "rim_power": 2.0})
		mats["iris"] = materials.variant("iris", {"emission": 1.4})
	_white_mat = materials.flat(Color(0.97, 0.96, 0.98))
	_blush_mat = materials.flat(Color(1.0, 0.45, 0.55), 0.85)
	return mats


func _mat(region: String) -> Material:
	if _mats.has(region):
		return _mats[region]
	return _mats["fur"]


## The body-surface region: humanoid maps to skin (anime pack precedent),
## everything else to fur.
func _body_region() -> String:
	return "skin" if config.head.family == "humanoid" else "fur"


# =============================================================================
# PROCEDURAL V2 — COLOUR BLOCKING
#
# jrpg-bases.md: "60/30/10 color blocking (fur/accent/detail), skin 15-25%
# visible, line = darkest + outline color".
# kemono.md: "color blocks: base coat, lighter inner-muzzle+chest/belly,
# socks/gloves (forearm/paw + shin/foot), inner-ear, tail tip, hair tuft, iris".
#
# Realized by RE-REGIONING body parts, not by layering garments over a bare
# body. Layering could not work: the skin under an added tunic stays in the
# mesh (and in the measurement — CharForgeBlocking.measure ignores occlusion by
# design), so the skin share could only ever grow. Low-poly avatars are built
# the same way for the same reason — you do not model a torso under a shirt.
#
# Only the two v2 lanes are blocked. Every `legacy`-preset family keeps
# _body_region() everywhere, so this stays additive for them exactly like the
# rest of v2. CharForgeBlocking.check_jrpg / check_kemono grade the result and
# tests/run_tests.gd asserts each lane passes its own grader.
# =============================================================================

## Body part -> palette region, JRPG lane: a sleeved tunic over hips and torso
## (fur, the 60), trousers and sleeves (accent, the 30), boots plus the belt and
## collar trim built by _build_v2_blocking (detail, the 10). Head, neck and
## hands are absent on purpose — they fall through to _body_region() and are the
## bare skin the 15-25% rule is about.
const JRPG_BLOCK_PLAN := {
	"hips": "fur", "belly": "fur", "chest": "fur",
	"leg": "accent", "arm": "accent", "foot": "detail",
}

## kemono lane: the base coat stays _body_region() over the whole body; only the
## sock/glove extremities are their own block ("forearm/paw + shin/foot").
const KEMONO_BLOCK_PLAN := {
	"ankle": "socks", "paw": "socks", "hand": "socks", "foot": "socks",
	"fetlock": "socks",
}


## The palette region a named body part renders as under the active blocking
## plan (falls through to _body_region() for unblocked parts and legacy rigs).
func _block_region(part: String) -> String:
	if _is_v2():
		var preset := String(proportions.get("preset", "legacy"))
		var plan: Dictionary = JRPG_BLOCK_PLAN if preset.ends_with("jrpg") else KEMONO_BLOCK_PLAN
		if plan.has(part):
			return plan[part]
	return _body_region()


func _block_mat(part: String) -> Material:
	return _mat(_block_region(part))


## True when the current rig takes the JRPG (clothed hero) blocking lane rather
## than the kemono (coat + socks) one.
func _is_jrpg_lane() -> bool:
	return _is_v2() and String(proportions.get("preset", "legacy")).ends_with("jrpg")


## The colour blocks that are geometry of their own rather than a re-regioned
## body part: the JRPG detail trim, and the kemono chest/belly countershading.
## Runs after the slot parts so a marking can still layer on top.
func _build_v2_blocking() -> void:
	if not _is_v2():
		return
	var body: Node3D = _refs["body"]
	if _is_jrpg_lane():
		# The "10" of 60/30/10 is trim, not a fourth garment: a belt at the
		# tunic waist and a collar at the neck, both in `detail`.
		_m(body, "Belt", _torus(0.185, 0.215), _mat("detail"), Vector3(0, 0.86, 0),
				Vector3(4.0, 0, 0))
		_m(body, "Collar", _torus(0.1, 0.128), _mat("detail"), Vector3(0, 1.235, 0.005),
				Vector3(8.0, 0, 0))
	else:
		# kemono.md's lighter block is "inner-muzzle + chest/belly" — the same
		# region on both, which is why the muzzle and this share a palette cell.
		_m(body, "Countershade", _sphere(0.16), _mat("innerMuzzle"),
				Vector3(0, 0.93, 0.16), Vector3.ZERO, Vector3(0.8, 1.35, 0.42))


## Grades the CURRENTLY ASSEMBLED rig against its lane's colour-blocking rules.
## The scoping the two rule sets need is applied here, where the head node is
## known: the JRPG costume rules are measured over the body alone, the kemono
## coat/colour-block rules over the whole avatar (see CharForgeBlocking's
## SKIN_VISIBLE_BAND note). Returns {} for legacy families, which the research
## states no blocking rule for.
##   -> {"lane", "shares", "bodyShares", "check": {ok, reasons, ...}, "ok"}
func blocking_report() -> Dictionary:
	if not _is_v2() or not _refs.has("head"):
		return {}
	var shares := CharForgeBlocking.measure(self)
	var body_shares := CharForgeBlocking.measure(self, [_refs["head"]])
	var lane := "jrpg" if _is_jrpg_lane() else "kemono"
	var check: Dictionary = CharForgeBlocking.check_jrpg(body_shares) if lane == "jrpg" \
			else CharForgeBlocking.check_kemono(shares)
	return {"lane": lane, "shares": shares, "bodyShares": body_shares,
			"check": check, "ok": bool(check.ok)}


# =============================================================================
# Mesh helpers
# =============================================================================

func _m(parent: Node3D, mesh_name: String, mesh: Mesh, mat: Material, pos: Vector3,
		rot_deg: Vector3 = Vector3.ZERO, scl: Vector3 = Vector3.ONE) -> MeshInstance3D:
	var mi := MeshInstance3D.new()
	mi.name = mesh_name
	mi.mesh = mesh
	mi.material_override = mat
	mi.position = pos
	mi.rotation_degrees = rot_deg
	mi.scale = scl
	parent.add_child(mi)
	return mi


# --- LOW-POLY TESSELLATION ---------------------------------------------------
# Godot's primitive meshes default to 64 radial segments x 32 rings, which is
# ~4k triangles for a single SphereMesh. An avatar is 40-60 primitives, so the
# defaults put a fully-loaded character near 150k triangles — an order of
# magnitude over technique.md's ~5-15k Quest-3-browser budget for a WHOLE
# avatar, on a single-threaded WASM renderer with no LOD.
#
# These counts are chosen for a stylized toon look where the silhouette is
# carried by shape and the shading is banded (a 12-segment sphere reads as
# round under a 2-band ramp; it would not under PBR). tests/run_tests.gd
# asserts the resulting budget so this cannot silently regress.
const SPHERE_RADIAL := 12
const SPHERE_RINGS := 6
const CAPSULE_RADIAL := 12
const CAPSULE_RINGS := 4
const CYLINDER_RADIAL := 12
const TORUS_RINGS := 12
const TORUS_RING_SEGMENTS := 6


func _capsule(radius: float, height: float) -> CapsuleMesh:
	var c := CapsuleMesh.new()
	c.radius = radius
	c.height = height
	c.radial_segments = CAPSULE_RADIAL
	c.rings = CAPSULE_RINGS
	return c


func _sphere(radius: float) -> SphereMesh:
	var s := SphereMesh.new()
	s.radius = radius
	s.height = radius * 2.0
	s.radial_segments = SPHERE_RADIAL
	s.rings = SPHERE_RINGS
	return s


func _cyl(top: float, bottom: float, height: float) -> CylinderMesh:
	var c := CylinderMesh.new()
	c.top_radius = top
	c.bottom_radius = bottom
	c.height = height
	c.radial_segments = CYLINDER_RADIAL
	c.rings = 1
	return c


func _box(size: Vector3) -> BoxMesh:
	var b := BoxMesh.new()
	b.size = size
	return b


func _prism(size: Vector3) -> PrismMesh:
	var p := PrismMesh.new()
	p.size = size
	return p


func _torus(inner: float, outer: float) -> TorusMesh:
	var t := TorusMesh.new()
	t.inner_radius = inner
	t.outer_radius = outer
	t.rings = TORUS_RINGS
	t.ring_segments = TORUS_RING_SEGMENTS
	return t


func _anchor(parent: Node3D, anchor_name: String, pos: Vector3) -> Node3D:
	var n := Node3D.new()
	n.name = anchor_name
	n.position = pos
	parent.add_child(n)
	return n


## Deterministic 0..1 pseudo-random derived from a part id + salt, so the same
## partId always yields the same shape variance.
func _hash01(text: String, salt: int = 0) -> float:
	var h: int = abs((text + str(salt)).hash())
	return float(h % 10000) / 10000.0


# =============================================================================
# Body
# =============================================================================

## Resolve the three proportion axes into the multipliers the builders use.
## MUST run before _build_body, and must be re-run per rebuild because the band
## depends on the resolved preset (digitigrade or not), which depends on family.
##
## `slime` gets NO hip lift: it has no legs to lengthen, so lifting its body
## would just detach the blob from the floor. Its length axes are inert by
## anatomy rather than by oversight, and the editor disables them for it — the
## curve/top round shipped two silently-inert families and that is the mistake
## being avoided here.
func _resolve_proportion_axes(family: String) -> void:
	var body_cfg: Variant = config.get("body")
	var b: Dictionary = body_cfg if body_cfg is Dictionary else {}
	var legs := clampf(_axis(b, "legs"), 0.0, 1.0)
	var arms := clampf(_axis(b, "arms"), 0.0, 1.0)
	var limb := clampf(_axis(b, "limb"), 0.0, 1.0)

	var digitigrade: bool = bool(proportions.get("digitigrade", false)) \
			and family not in HOOFED_FAMILIES
	var band := LEG_LEN_BAND_DIGITIGRADE if digitigrade else LEG_LEN_BAND

	_leg_len = lerpf(band.x, band.y, legs)
	_arm_len = lerpf(ARM_LEN_BAND.x, ARM_LEN_BAND.y, arms)
	_limb_girth = lerpf(LIMB_GIRTH_BAND.x, LIMB_GIRTH_BAND.y, limb)
	# The lift must match the leg the family ACTUALLY builds, not one nominal for
	# everyone: the digitigrade chain stands ~0.60 tall (paw 0.035 to thigh top
	# 0.61) against the plantigrade capsule's 0.50. Using 0.50 for both
	# under-lifts a stretched digitigrade leg and floats the paws off the floor —
	# the exact bug the fixed-foot construction exists to prevent.
	var nominal := NOMINAL_DIGITIGRADE_LEG_HEIGHT if digitigrade else NOMINAL_LEG_HEIGHT
	_hip_lift = 0.0 if family == "slime" else nominal * (_leg_len - 1.0)


## One shape axis, tolerating a config that predates it (absent -> the centred
## 0.5, which every band maps to the pre-axis value).
static func _axis(body: Dictionary, key: String) -> float:
	var v: Variant = body.get(key)
	if v is float or v is int:
		return float(v)
	return 0.5


func _build_body(rig: Node3D, family: String) -> void:
	_resolve_proportion_axes(family)
	var body := _anchor(rig, "Body", Vector3.ZERO)
	_refs["body"] = body
	var bm := _mat(_body_region())

	# SHAPE AXES (Char3DConfig body.curve / body.top). Deliberately not mesh
	# morphs: a scoping pass found ZERO body morph targets across all 75
	# vendored models (the 11 that have any are .vrm and all FACIAL —
	# base_a.vrm carries 56, every one of them on "Face.baked", while
	# "Body.baked" has none), so a morph slider would need asset authoring. The
	# procedural assembler is the one route that always exists, and it already
	# scales its primitives.
	#
	# Read HERE, above the match, because EVERY branch owes the sliders an
	# answer. They first landed inside the default branch only, which left the
	# two families that build their own primitives — slime and robot — silently
	# inert: 2 of the 29 selectable families where dragging Curve or Top did
	# nothing at all, with no UI affordance saying so. Both have a plain reading
	# of the axes (below), so they are DRIVEN rather than disabled; a slider that
	# is present and does nothing is the worse of the two honest options.
	#
	# Orthogonal to `build`: build scales the WHOLE rig (rebuild() writes
	# rig.scale from it), while these re-proportion the torso blocks that are
	# already here. No primitive is added in any branch, so the colour-blocking
	# region map and the Quest-3 triangle budget are untouched, and
	# proportions.gd's shoulder widths stay build's job.
	#
	# Every band is CENTRED on the pre-axis literal, so 0.5 rebuilds today's
	# silhouette EXACTLY — in all three branches. That is the whole migration
	# story: every stored definition predates these keys, the BFF has no
	# migration mechanism (Liquibase 001, definition is jsonb), so absent -> 0.5
	# must be a no-op. It is byte-identical, not merely close — each midpoint
	# below rounds to the same float32 the old literal did, and run_tests.gd
	# asserts it family by family.
	var curve: float = config.body.curve
	var top: float = config.body.top

	match family:
		"slime":
			# A slime is TWO blobs, so each axis owns one: the Base is its hips,
			# the Torso is its top, and what reads on screen is the ratio between
			# their widths. Nothing narrows a waist here because there is no
			# third block to narrow — the Torso is waist and chest at once, and
			# driving it from both axes would make each slider's effect depend on
			# where the other one is parked.
			_m(body, "Base", _sphere(0.34), bm, Vector3(0, 0.3, 0), Vector3.ZERO,
					Vector3(lerpf(1.00, 1.30, curve), 0.75, lerpf(0.95, 1.15, curve)))
			_m(body, "Torso", _sphere(0.26), bm, Vector3(0, 0.75, 0), Vector3.ZERO,
					Vector3(lerpf(0.90, 1.10, top), 1.1, lerpf(0.87, 1.03, top)))
			_m(body, "Core", _sphere(0.09), _mat("accent"), Vector3(0, 0.75, 0.05))
		"robot":
			# A robot is a chassis: `curve` widens the pelvis block, `top` widens
			# the torso block, both in X *and* Z for the same reason the default
			# branch does — a shape that only changes head-on reads as a bug from
			# the side. These two primitives carried NO scale argument before the
			# axes existed, so their bands are centred on 1.0 rather than on a
			# literal, and the centred stop must come out exactly Vector3.ONE.
			var chassis_scale := Vector3(lerpf(0.85, 1.15, curve), 1.0, lerpf(0.92, 1.08, curve))
			var torso_scale := Vector3(lerpf(0.88, 1.12, top), 1.0, lerpf(0.92, 1.08, top))
			var torso_z := lerpf(-0.012, 0.012, top)
			# The robot owes the proportion axes an answer too: its legs are a
			# strut pair, so length stretches the strut from the fixed foot and
			# girth thickens it, exactly as the default branch does. Leaving this
			# branch out is how curve/top shipped two silently-inert families.
			_m(body, "Hips", _box(Vector3(0.34, 0.18, 0.24)), bm, Vector3(0, 0.6 + _hip_lift, 0),
					Vector3.ZERO, chassis_scale)
			_m(body, "LegL", _cyl(0.07 * _limb_girth, 0.08 * _limb_girth, 0.5 * _leg_len),
					_mat("detail"), Vector3(0.12, 0.05 + 0.25 * _leg_len, 0))
			_m(body, "LegR", _cyl(0.07 * _limb_girth, 0.08 * _limb_girth, 0.5 * _leg_len),
					_mat("detail"), Vector3(-0.12, 0.05 + 0.25 * _leg_len, 0))
			_m(body, "FootL", _box(Vector3(0.14, 0.07, 0.2)), bm, Vector3(0.12, 0.045, 0.03))
			_m(body, "FootR", _box(Vector3(0.14, 0.07, 0.2)), bm, Vector3(-0.12, 0.045, 0.03))
			_m(body, "Torso", _box(Vector3(0.42, 0.4, 0.28)), bm, Vector3(0, 0.98 + _hip_lift, torso_z),
					Vector3.ZERO, torso_scale)
			# The panel is surface DETAIL, so it rides on the chassis face instead
			# of sitting at a fixed z: half the torso depth (0.28 / 2), scaled,
			# plus the 0.005 it always stood proud. A fixed z would sink into the
			# chest at top 1.0 and float off it at top 0.0. At the centred stop
			# that expression is the original 0.145 to the last float32 bit.
			_m(body, "Panel", _box(Vector3(0.2, 0.16, 0.02)), _mat("accent"),
					Vector3(0, 1.0 + _hip_lift, torso_z + 0.14 * torso_scale.z + 0.005))
			_arm(body, 1.0, family)
			_arm(body, -1.0, family)
		_:
			# Hips flare in X *and* Z — a curvy hip has to read from the side as
			# well as the front — and the waist narrows in step, which is what
			# makes this a RATIO rather than just "bigger hips".
			var hips_scale := Vector3(lerpf(1.00, 1.30, curve), 0.8, lerpf(0.90, 1.10, curve))
			var belly_r := lerpf(0.225, 0.185, curve)
			# Chest volume grows in X/Z with a small forward bias: scaling a
			# sphere about its own centre also grows it BACKWARD into the spine,
			# which reads as a thicker ribcage instead of a fuller chest.
			var chest_scale := Vector3(lerpf(0.95, 1.15, top), 0.9, lerpf(0.80, 0.96, top))
			var chest_z := lerpf(-0.015, 0.015, top)
			# Everything from the hips up rides on _hip_lift, so a longer leg
			# raises the whole torso instead of burying the feet.
			_m(body, "Hips", _sphere(0.2), _block_mat("hips"), Vector3(0, 0.62 + _hip_lift, 0), Vector3.ZERO, hips_scale)
			_leg(body, 1.0, family)
			_leg(body, -1.0, family)
			_m(body, "Belly", _capsule(belly_r, 0.5), _block_mat("belly"), Vector3(0, 0.85 + _hip_lift, 0.01))
			_m(body, "Chest", _sphere(0.23), _block_mat("chest"), Vector3(0, 1.08 + _hip_lift, chest_z), Vector3.ZERO, chest_scale)
			_arm(body, 1.0, family)
			_arm(body, -1.0, family)

	# Every mount above the hips rides the same lift as the torso, or a longer
	# leg would leave the head, tail, wings and accessory floating at the old
	# height while the body moved out from under them.
	var neck_y := 1.28 + _hip_lift
	if family == "slime":
		neck_y = 0.95
	else:
		_m(body, "Neck", _cyl(0.085, 0.11, 0.18), bm, Vector3(0, neck_y, 0.01))
	_refs["neck_top"] = _anchor(body, "NeckTop", Vector3(0, neck_y + 0.14, 0.02))
	_refs["tail_mount"] = _anchor(body, "TailMount", Vector3(0, 0.62 + _hip_lift, -0.17))
	_refs["wing_mount"] = _anchor(body, "WingMount", Vector3(0, 1.1 + _hip_lift, -0.17))
	_refs["accessory_mount"] = _anchor(body, "AccessoryMount", Vector3(0, neck_y, 0))
	_refs["chest_front"] = _anchor(body, "ChestFront", Vector3(0, 1.02 + _hip_lift, 0.19))


func _leg(body: Node3D, side: float, family: String) -> void:
	var suffix := "L" if side > 0.0 else "R"
	var bm := _mat(_body_region())
	if bool(proportions.get("digitigrade", false)) and family not in \
			["equine", "cervine", "bovine", "caprine", "suid"]:
		_build_digitigrade_leg(body, side, suffix, bm)
		return
	# The leg grows from a FIXED FOOT upward: height 0.5*len, centre at
	# 0.07 + 0.25*len (its bottom stays at 0.07). At len 1.0 that is the original
	# capsule(0.09, 0.5) at y 0.32 exactly. Girth scales the radius only, so
	# length and thickness stay independent knobs.
	_m(body, "Leg" + suffix, _capsule(0.09 * _limb_girth, NOMINAL_LEG_HEIGHT * _leg_len),
			_block_mat("leg"), Vector3(side * 0.13, 0.07 + 0.25 * _leg_len, 0))
	match family:
		"equine", "cervine", "bovine", "caprine", "suid":
			# A hoof is never a sock, so a hoofed kemono gets its sock block as a
			# real fetlock cuff above the hoof rather than by recolouring it.
			if _is_v2() and not _is_jrpg_lane():
				_m(body, "Fetlock" + suffix, _cyl(0.088, 0.094, 0.12), _block_mat("fetlock"),
						Vector3(side * 0.13, 0.145, 0.005))
			_m(body, "Hoof" + suffix, _cyl(0.075, 0.085, 0.09), _mat("line"), Vector3(side * 0.13, 0.045, 0.015))
		_:
			_m(body, "Foot" + suffix, _sphere(0.085), _block_mat("foot"), Vector3(side * 0.13, 0.06, 0.05), Vector3.ZERO, Vector3(1.0, 0.6, 1.5))


## DIGITIGRADE leg (kemono.md: "limbs taper, mitt hands, DIGITIGRADE legs").
## Three segments — a forward-raked thigh, a back-raked shin and a raised
## ankle standing on the toes — which is what actually reads as digitigrade;
## a single angled capsule does not.
func _build_digitigrade_leg(body: Node3D, side: float, suffix: String, bm: Material) -> void:
	var taper: float = clampf(float(proportions.get("limbTaper", 0.7)), 0.3, 1.0)
	var x := side * 0.13
	# Three segments, all stretched by the SAME leg-length factor and each
	# re-centred about the ankle so the raked thigh/shin angles hold their shape.
	# Scaling only the thigh (or only the heights, without moving the centres)
	# pulls the chain apart and the pose stops reading as digitigrade. At
	# _leg_len 1.0 every literal below is the original one.
	var l := _leg_len
	_m(body, "Thigh" + suffix, _capsule(0.095 * _limb_girth, 0.26 * l), bm,
			Vector3(x, 0.11 + 0.37 * l, 0.02), Vector3(16.0, 0, side * -4.0))
	_m(body, "Shin" + suffix, _capsule(0.095 * taper * _limb_girth, 0.26 * l), bm,
			Vector3(x, 0.11 + 0.16 * l, -0.05), Vector3(-22.0, 0, 0))
	# Ankle + paw carry the "shin/foot" half of the kemono sock block.
	_m(body, "Ankle" + suffix, _capsule(0.075 * taper * _limb_girth, 0.17 * l), _block_mat("ankle"),
			Vector3(x, 0.11 * l, 0.01), Vector3(16.0, 0, 0))
	# The paw: a short pad standing on the toes rather than a flat foot.
	_m(body, "Paw" + suffix, _sphere(0.075), _block_mat("paw"),
			Vector3(x, 0.035, 0.07), Vector3.ZERO, Vector3(1.0, 0.55, 1.6))


func _arm(body: Node3D, side: float, family: String) -> void:
	var suffix := "L" if side > 0.0 else "R"
	var arm_mat := _block_mat("arm")
	if family == "robot":
		arm_mat = _mat("detail")
	# jrpg-bases.md: shoulders 1.5-1.7 head-widths on heroic builds; the arm
	# root rides outward with them. `legacy` reports 1.0 => the original x.
	var shoulder: float = clampf(float(proportions.get("shoulderHeadWidths", 1.0)) , 0.8, 1.8)
	var taper: float = clampf(float(proportions.get("limbTaper", 1.0)), 0.3, 1.0)
	var arm_x := 0.3 * lerpf(1.0, shoulder, 0.35)
	# Girth thickens the arm; length hangs it from a FIXED SHOULDER so a longer
	# arm reaches further down rather than lifting out of the shoulder socket.
	# The shoulder pivots are the pre-axis tops: 1.14 (single-capsule arm) and
	# 1.12 (the JRPG sleeve/forearm split). Every expression below is the
	# original literal at _arm_len 1.0.
	var arm_r := 0.07 * lerpf(1.0, taper, 0.4) * _limb_girth
	var a := _arm_len
	# The whole arm rides the torso lift too — the shoulder is part of the chest.
	var shoulder_y := 1.14 + _hip_lift
	var jrpg_shoulder_y := 1.12 + _hip_lift
	if _is_jrpg_lane():
		# Short sleeve + bare forearm. The split exists for the blocking rule,
		# not for detail: with the whole arm sleeved a clothed hero shows only
		# neck and hands, which lands UNDER jrpg-bases.md's 15-25% skin band —
		# a bare forearm is the standard way that band is met.
		_m(body, "Arm" + suffix, _capsule(arm_r, 0.26 * a), arm_mat,
				Vector3(side * arm_x, jrpg_shoulder_y - 0.13 * a, 0.01), Vector3(0, 0, side * -14.0))
		_m(body, "Forearm" + suffix, _capsule(arm_r * 0.9, 0.24 * a), _mat(_body_region()),
				Vector3(side * (arm_x + 0.03), jrpg_shoulder_y - 0.34 * a, 0.015), Vector3(0, 0, side * -14.0))
	else:
		_m(body, "Arm" + suffix, _capsule(arm_r, 0.44 * a), arm_mat,
				Vector3(side * arm_x, shoulder_y - 0.22 * a, 0.01), Vector3(0, 0, side * -14.0))
	# jrpg-bases.md hands 1.4-1.6x; kemono "mitt hands" land on the same knob.
	# The glove half of the kemono sock block rides here; the JRPG hand has no
	# entry in the plan, so it stays the bare skin the 15-25% rule counts.
	var hand_mat := _block_mat("hand")
	var hand_scale: float = clampf(float(proportions.get("handScale", 1.0)), 0.9, 1.6)
	# The hand follows the wrist down as the arm lengthens. Both lanes put the
	# wrist at shoulder - 0.44*len, which is 0.68 at len 1.0 either way. The hand
	# keeps its OWN size knob (handScale) — limb girth is arms and legs, not
	# extremities, so a thick-limbed character does not get balloon hands.
	# NOTE the two different coefficients: the JRPG wrist sits flush at the
	# forearm's end (1.12 - 0.44) while the single-capsule arm's hand hangs 0.02
	# BELOW the capsule bottom (1.14 - 0.44 - 0.02). Both evaluate to the
	# original 0.68 at len 1.0; using one coefficient for both silently moves the
	# hand 2 cm on every non-JRPG character.
	var wrist_y := (jrpg_shoulder_y - 0.44 * a) if _is_jrpg_lane() else (shoulder_y - 0.46 * a)
	_m(body, "Hand" + suffix, _sphere(0.07 * hand_scale), hand_mat,
			Vector3(side * (arm_x + 0.06), wrist_y, 0.02))


# =============================================================================
# Head — per-family construction (all 29 families visually distinct)
# =============================================================================

# Spec keys: skull ("sphere"/"box"/"dome"/"blob"), skull_s Vector3,
# muzzle ("box"/"cyl"/"cone"/"beak"/"flat"/"wide"/"none"), mlen/mwide base
# multipliers, ears (default style), eyes ("front"/"side"), eye_s, horns
# (default style), extras (Array of tokens realized by _build_extra).
func _head_spec(family: String) -> Dictionary:
	match family:
		"canid":
			return {"skull": "sphere", "skull_s": Vector3(1, 0.96, 1.02), "muzzle": "box", "mlen": 1.0, "mwide": 0.9, "ears": "pointy", "eyes": "front", "eye_s": 1.0, "horns": "none", "extras": ["nose"]}
		"felid":
			return {"skull": "sphere", "skull_s": Vector3(1.04, 0.98, 0.98), "muzzle": "box", "mlen": 0.45, "mwide": 1.0, "ears": "pointy_wide", "eyes": "front", "eye_s": 1.15, "horns": "none", "extras": ["nose", "whiskers"]}
		"avian":
			return {"skull": "dome", "skull_s": Vector3(0.95, 1.0, 0.95), "muzzle": "beak", "mlen": 0.9, "mwide": 0.7, "ears": "none", "eyes": "side", "eye_s": 1.0, "horns": "none", "extras": ["crest"]}
		"reptile":
			return {"skull": "sphere", "skull_s": Vector3(0.98, 0.8, 1.1), "muzzle": "cyl", "mlen": 1.2, "mwide": 0.85, "ears": "none", "eyes": "side", "eye_s": 0.95, "horns": "none", "extras": ["brow_ridge", "spikes"]}
		"dragon":
			return {"skull": "sphere", "skull_s": Vector3(1.05, 0.92, 1.12), "muzzle": "box", "mlen": 1.25, "mwide": 1.0, "ears": "fin", "eyes": "front", "eye_s": 1.0, "horns": "curved", "extras": ["nose", "brow_ridge", "spikes"]}
		"dino":
			return {"skull": "box", "skull_s": Vector3(0.95, 0.9, 1.15), "muzzle": "wide", "mlen": 1.35, "mwide": 1.15, "ears": "none", "eyes": "front", "eye_s": 0.8, "horns": "none", "extras": ["brow_ridge", "teeth"]}
		"kobold":
			return {"skull": "sphere", "skull_s": Vector3(0.88, 0.85, 0.9), "muzzle": "box", "mlen": 0.65, "mwide": 0.85, "ears": "big", "eyes": "front", "eye_s": 1.3, "horns": "stubs", "extras": ["nose"]}
		"gryphon":
			return {"skull": "dome", "skull_s": Vector3(1.0, 1.0, 1.0), "muzzle": "beak", "mlen": 1.0, "mwide": 0.8, "ears": "tuft", "eyes": "front", "eye_s": 1.05, "horns": "none", "extras": ["crest"]}
		"equine":
			return {"skull": "sphere", "skull_s": Vector3(0.92, 1.0, 1.1), "muzzle": "cyl", "mlen": 1.45, "mwide": 1.0, "ears": "pointy", "eyes": "side", "eye_s": 0.95, "horns": "none", "extras": ["nose", "forelock"]}
		"cervine":
			return {"skull": "sphere", "skull_s": Vector3(0.9, 0.98, 1.05), "muzzle": "cyl", "mlen": 1.2, "mwide": 0.85, "ears": "round_big", "eyes": "side", "eye_s": 1.0, "horns": "antler", "extras": ["nose"]}
		"bovine":
			return {"skull": "sphere", "skull_s": Vector3(1.08, 0.95, 1.05), "muzzle": "wide", "mlen": 1.0, "mwide": 1.3, "ears": "droop", "eyes": "side", "eye_s": 0.9, "horns": "straight_out", "extras": ["nose_plate"]}
		"caprine":
			return {"skull": "sphere", "skull_s": Vector3(0.94, 0.96, 1.02), "muzzle": "cyl", "mlen": 0.95, "mwide": 0.8, "ears": "droop", "eyes": "side", "eye_s": 0.95, "horns": "ram", "extras": ["nose", "goatee"]}
		"suid":
			return {"skull": "sphere", "skull_s": Vector3(1.05, 0.95, 1.0), "muzzle": "flat", "mlen": 0.7, "mwide": 1.1, "ears": "droop_big", "eyes": "front", "eye_s": 0.85, "horns": "none", "extras": ["tusks", "snout_disc"]}
		"hyena":
			return {"skull": "sphere", "skull_s": Vector3(1.02, 1.0, 1.04), "muzzle": "box", "mlen": 0.9, "mwide": 1.05, "ears": "round_big", "eyes": "front", "eye_s": 1.0, "horns": "none", "extras": ["nose", "teeth"]}
		"lapine":
			return {"skull": "sphere", "skull_s": Vector3(0.98, 1.02, 0.95), "muzzle": "box", "mlen": 0.3, "mwide": 0.8, "ears": "long", "eyes": "front", "eye_s": 1.2, "horns": "none", "extras": ["nose", "buck_teeth"]}
		"rodent":
			return {"skull": "sphere", "skull_s": Vector3(0.95, 0.95, 1.0), "muzzle": "cone", "mlen": 0.6, "mwide": 0.7, "ears": "round", "eyes": "front", "eye_s": 1.15, "horns": "none", "extras": ["nose", "whiskers", "buck_teeth"]}
		"macropod":
			return {"skull": "sphere", "skull_s": Vector3(0.9, 0.98, 1.08), "muzzle": "cyl", "mlen": 1.1, "mwide": 0.8, "ears": "long_angled", "eyes": "side", "eye_s": 1.0, "horns": "none", "extras": ["nose"]}
		"moth":
			return {"skull": "sphere", "skull_s": Vector3(1.05, 1.0, 0.95), "muzzle": "none", "mlen": 0.0, "mwide": 0.0, "ears": "none", "eyes": "front", "eye_s": 1.5, "horns": "none", "extras": ["antennae_feather", "fuzz_ruff"]}
		"insect":
			return {"skull": "dome", "skull_s": Vector3(1.0, 0.95, 0.95), "muzzle": "none", "mlen": 0.0, "mwide": 0.0, "ears": "none", "eyes": "compound", "eye_s": 1.4, "horns": "none", "extras": ["antennae", "mandibles"]}
		"bat":
			return {"skull": "sphere", "skull_s": Vector3(0.98, 0.98, 0.95), "muzzle": "box", "mlen": 0.4, "mwide": 0.85, "ears": "huge", "eyes": "front", "eye_s": 1.2, "horns": "none", "extras": ["nose", "fangs"]}
		"ursine":
			return {"skull": "sphere", "skull_s": Vector3(1.1, 1.0, 1.05), "muzzle": "box", "mlen": 0.75, "mwide": 1.1, "ears": "round", "eyes": "front", "eye_s": 0.9, "horns": "none", "extras": ["nose"]}
		"mustelid":
			return {"skull": "sphere", "skull_s": Vector3(0.9, 0.88, 1.05), "muzzle": "box", "mlen": 0.55, "mwide": 0.8, "ears": "round", "eyes": "front", "eye_s": 1.05, "horns": "none", "extras": ["nose", "whiskers"]}
		"procyonid":
			return {"skull": "sphere", "skull_s": Vector3(1.0, 0.96, 1.0), "muzzle": "box", "mlen": 0.7, "mwide": 0.9, "ears": "round", "eyes": "front", "eye_s": 1.05, "horns": "none", "extras": ["nose", "eye_mask"]}
		"amphibian":
			return {"skull": "sphere", "skull_s": Vector3(1.2, 0.75, 1.05), "muzzle": "none", "mlen": 0.0, "mwide": 0.0, "ears": "none", "eyes": "top", "eye_s": 1.3, "horns": "none", "extras": ["wide_mouth"]}
		"aquatic":
			return {"skull": "sphere", "skull_s": Vector3(0.95, 0.9, 1.15), "muzzle": "cyl", "mlen": 1.0, "mwide": 0.95, "ears": "fin", "eyes": "side", "eye_s": 0.95, "horns": "none", "extras": ["dorsal_fin"]}
		"slime":
			return {"skull": "blob", "skull_s": Vector3(1.1, 0.95, 1.05), "muzzle": "none", "mlen": 0.0, "mwide": 0.0, "ears": "none", "eyes": "front", "eye_s": 1.1, "horns": "none", "extras": ["drips"]}
		"robot":
			return {"skull": "box", "skull_s": Vector3(1.0, 0.95, 0.95), "muzzle": "grille", "mlen": 0.5, "mwide": 1.0, "ears": "none", "eyes": "visor_dots", "eye_s": 1.0, "horns": "none", "extras": ["antenna_bolt"]}
		"synth":
			return {"skull": "dome", "skull_s": Vector3(1.02, 1.0, 1.0), "muzzle": "box", "mlen": 0.6, "mwide": 0.9, "ears": "fin", "eyes": "visor", "eye_s": 1.0, "horns": "none", "extras": ["seams"]}
		_:
			# humanoid
			return {"skull": "sphere", "skull_s": Vector3(0.98, 1.05, 0.98), "muzzle": "none", "mlen": 0.0, "mwide": 0.0, "ears": "human", "eyes": "front", "eye_s": 1.1, "horns": "none", "extras": ["nose_small"]}


## Head-local geometry constants the proportion ratios are expressed against
## (the primitive skull is a 0.17-radius sphere centred at y = 0.08).
const SKULL_RADIUS := 0.17
const HEAD_DEPTH := SKULL_RADIUS * 2.0
const HEAD_CROWN_Y := 0.25


## True when this config takes the v2 (kemono / JRPG) proportion treatment
## rather than the pre-v2 primitives.
func _is_v2() -> bool:
	return String(proportions.get("preset", "legacy")) != "legacy"


func _build_head(neck_top: Node3D, family: String) -> void:
	var spec := _head_spec(family)
	var head := _anchor(neck_top, "Head", Vector3.ZERO)
	_refs["head"] = head
	# V2: the head is sized from the preset's heads-tall figure (chibi kemono
	# 2.5-3, standard kemono 4.5-5.5, heroic JRPG 6-7). The `legacy` preset's
	# headScale is exactly 1.0, so untouched families keep the old anime bump.
	var head_scale: float = float(proportions.get("headScale", 1.0))
	if not _is_v2() and config.style == "anime":
		head_scale *= 1.12
	head.scale = Vector3.ONE * head_scale

	# Deterministic per-variant jitter so two variants of one family differ.
	var jitter := _hash01(config.head.variant, 7)
	var skull_s: Vector3 = spec.skull_s * (0.95 + 0.1 * jitter)
	var head_mat := _mat(_body_region())

	match spec.skull:
		"box":
			_m(head, "Skull", _box(Vector3(0.32, 0.28, 0.3)), head_mat, Vector3(0, 0.08, 0), Vector3.ZERO, skull_s)
		"dome":
			_m(head, "Skull", _sphere(0.17), head_mat, Vector3(0, 0.08, 0), Vector3.ZERO, skull_s * Vector3(1.0, 1.15, 1.0))
		"blob":
			var blob := _m(head, "Skull", _sphere(0.19), head_mat, Vector3(0, 0.07, 0), Vector3.ZERO, skull_s)
			_m(head, "Drip", _sphere(0.05), head_mat, Vector3(0.1, -0.04, 0.1))
			blob.scale.y *= 0.9
		_:
			_m(head, "Skull", _sphere(0.17), head_mat, Vector3(0, 0.08, 0), Vector3.ZERO, skull_s)

	_build_muzzle(head, spec, family)
	_build_jaw(head, spec, family)
	_build_eyes(head, spec)
	_build_brows(head, spec)

	# Ears: config earStyle / parts.ears override the family default.
	var ear_style := _ear_style_for(spec)
	if ear_style != "none":
		_build_ear(head, ear_style, 1.0)
		_build_ear(head, ear_style, -1.0)

	# Horns: config hornStyle overrides the family default.
	var horn_style: String = spec.horns
	if config.head.hornStyle != null:
		horn_style = _keyword_horn_style(String(config.head.hornStyle))
	if horn_style != "none":
		_build_horn(head, horn_style)

	for extra in spec.extras:
		_build_extra(head, extra, spec)

	# Blush discs — hidden except for the embarrassed expression.
	var blush := _anchor(head, "Blush", Vector3.ZERO)
	_m(blush, "BlushL", _sphere(0.035), _blush_mat, Vector3(0.1, -0.01, 0.13), Vector3.ZERO, Vector3(1.0, 0.6, 0.5))
	_m(blush, "BlushR", _sphere(0.035), _blush_mat, Vector3(-0.1, -0.01, 0.13), Vector3.ZERO, Vector3(1.0, 0.6, 0.5))
	blush.visible = false
	_refs["blush"] = blush

	_refs["feature_mount"] = _anchor(head, "FeatureMount", Vector3(0, 0.14, -0.02))
	_refs["hair_mount"] = _anchor(head, "HairMount", Vector3(0, 0.16, -0.01))


func _muzzle_len(spec: Dictionary) -> float:
	var l: float = spec.mlen * lerpf(0.45, 1.6, config.head.snoutLength)
	if config.style == "anime":
		l *= 0.7
	return l


## V2 muzzle depth in head-local units.
##
## kemono.md gives the muzzle as 15-25% of head depth; that band is the row's
## `muzzleDepthRatio` and describes a family whose head-spec length multiplier
## (`mlen`) is 1.0. Species with genuinely longer or shorter faces (equine 1.45,
## lapine 0.3) scale that base by their own multiplier, clamped so no kemono
## face degenerates into either a pug or a crocodile.
func _v2_muzzle_depth(spec: Dictionary) -> float:
	var base: float = HEAD_DEPTH * float(proportions.get("muzzleDepthRatio", 0.19))
	var family_scale: float = clampf(float(spec.mlen), 0.45, 1.6)
	# snoutLength already swept the ratio across its band inside
	# CharForgeProportions; here it only nudges the family multiplier so the
	# slider still reads as "longer snout" within a species.
	return base * family_scale * lerpf(0.85, 1.15, config.head.snoutLength)


## The plush lofted muzzle: a wide-rounded ring-loft that tapers into the
## cheeks, never a cone (CharForgeLoft.MIN_TIP_FRACTION floors the tip ring),
## with a lighter inner-muzzle block underneath — the kemono.md colour rule
## "lighter inner-muzzle + chest/belly", which is why `innerMuzzle` derives
## from `fur` lightened.
func _build_v2_muzzle(head: Node3D, spec: Dictionary) -> void:
	var depth := _v2_muzzle_depth(spec)
	var width_ratio: float = float(proportions.get("muzzleWidthRatio", 0.56))
	var half_width: float = SKULL_RADIUS * width_ratio * float(spec.mwide)
	var half_height: float = half_width * 0.74
	var taper: float = float(proportions.get("muzzleTaper", 0.4))
	var region := _body_region()

	var mesh := CharForgeLoft.muzzle(depth, half_width, half_height, taper,
			CharForgePalette.region_uv(region), CharForgePalette.region_color(config, region))
	var muzzle := _m(head, "Muzzle", mesh, _mat(region), Vector3(0, -0.012, SKULL_RADIUS * 0.72))
	_refs["muzzle"] = muzzle

	var inner := CharForgeLoft.muzzle(depth * 0.92, half_width * 0.62, half_height * 0.42,
			taper, CharForgePalette.region_uv("innerMuzzle"),
			CharForgePalette.region_color(config, "innerMuzzle"))
	_m(head, "InnerMuzzle", inner, _mat("innerMuzzle"),
			Vector3(0, -0.012 - half_height * 0.42, SKULL_RADIUS * 0.72 + 0.002))


func _build_muzzle(head: Node3D, spec: Dictionary, family: String) -> void:
	var bm := _mat(_body_region())
	var length := _muzzle_len(spec)
	var wide: float = spec.mwide
	# V2 replaces the crude box/cylinder/cone muzzles with a lofted one. Beaks,
	# grilles and muzzle-less faces keep their bespoke geometry.
	if _is_v2() and spec.muzzle in ["box", "cyl", "cone", "wide", "flat"]:
		_build_v2_muzzle(head, spec)
		return
	match spec.muzzle:
		"box":
			_m(head, "Muzzle", _box(Vector3(0.16 * wide, 0.1, 0.16 * length)), bm, Vector3(0, -0.01, 0.14 + 0.07 * length))
		"cyl":
			_m(head, "Muzzle", _cyl(0.055 * wide, 0.08 * wide, 0.2 * length), bm,
					Vector3(0, -0.005, 0.13 + 0.09 * length), Vector3(97, 0, 0))
		"cone":
			_m(head, "Muzzle", _cyl(0.015, 0.08 * wide, 0.2 * length), bm,
					Vector3(0, -0.01, 0.13 + 0.09 * length), Vector3(95, 0, 0))
		"wide":
			_m(head, "Muzzle", _box(Vector3(0.2 * wide, 0.12, 0.16 * length)), bm, Vector3(0, -0.03, 0.13 + 0.07 * length))
		"flat":
			_m(head, "Muzzle", _box(Vector3(0.15 * wide, 0.1, 0.1 * length)), bm, Vector3(0, -0.02, 0.14))
		"beak":
			_m(head, "Beak", _prism(Vector3(0.09 * wide, 0.16 * length, 0.08)), _mat("detail"),
					Vector3(0, -0.005, 0.16 + 0.05 * length), Vector3(90, 0, 0))
		"grille":
			_m(head, "Grille", _box(Vector3(0.16, 0.07, 0.05)), _mat("line"), Vector3(0, -0.06, 0.15))
		_:
			pass


func _build_jaw(head: Node3D, spec: Dictionary, family: String) -> void:
	var jaw := _anchor(head, "Jaw", Vector3(0, -0.07, 0.02))
	_refs["jaw"] = jaw
	var bm := _mat(_body_region())
	var length := maxf(_muzzle_len(spec), 0.3)
	match spec.muzzle:
		"beak":
			_m(jaw, "LowerBeak", _prism(Vector3(0.07, 0.1 * length, 0.05)), _mat("detail"),
					Vector3(0, -0.015, 0.13), Vector3(-90, 0, 180))
		"none":
			_m(jaw, "LowerJaw", _box(Vector3(0.1, 0.035, 0.09)), bm, Vector3(0, -0.015, 0.1))
		_:
			_m(jaw, "LowerJaw", _box(Vector3(0.12 * spec.mwide, 0.04, 0.14 * length)), bm,
					Vector3(0, -0.02, 0.06 + 0.06 * length))
	# Mouth interior — revealed as the jaw opens.
	_m(jaw, "Mouth", _box(Vector3(0.08, 0.025, 0.06)), _mat("line"), Vector3(0, 0.005, 0.08))


## V2 eyes: sized and placed from the proportion row rather than from a fixed
## radius. kemono.md — each eye 0.28-0.35 of face width, 0.8-1.2 eye-widths
## apart, LOW on the face, slight inward tilt, iris fills the eye. The upper
## lid is a real node with a neutral droop of 10-15 degrees, because kemono.md
## calls the UPPER-LID ANGLE "THE expression driver".
func _build_v2_eyes(head: Node3D, spec: Dictionary) -> void:
	var layout := CharForgeProportions.eye_layout(proportions, SKULL_RADIUS)
	var half_width: float = float(layout.halfWidth)
	var center_x: float = float(layout.centerX)
	var eye_y: float = HEAD_CROWN_Y - float(proportions.get("eyeLineRatio", 0.56)) * HEAD_DEPTH
	# Sit the eye on the skull surface at that height (never floating in front).
	var offset := clampf(eye_y - 0.08, -SKULL_RADIUS, SKULL_RADIUS)
	var eye_z: float = sqrt(maxf(SKULL_RADIUS * SKULL_RADIUS - offset * offset, 0.0)) * 0.86
	var tilt: float = float(proportions.get("eyeTiltDeg", 6.0))
	var iris_fill: float = float(proportions.get("irisFill", 0.85))
	var lid_angle: float = float(proportions.get("upperLidAngleDeg", 12.0))

	for side in [1.0, -1.0]:
		var suffix := "L" if side > 0.0 else "R"
		var eye := _anchor(head, "Eye" + suffix, Vector3(side * center_x, eye_y, eye_z))
		# Inward tilt: the outer corner rides higher than the inner one.
		eye.rotation_degrees = Vector3(0, side * 6.0, side * tilt)
		_m(eye, "Sclera", _sphere(half_width), _white_mat, Vector3.ZERO,
				Vector3.ZERO, Vector3(1.0, 0.86, 0.7))
		# Iris FILLS the eye (kemono) / 60-80% of the aperture (JRPG).
		_m(eye, "Iris", _sphere(half_width * iris_fill), _mat("iris"),
				Vector3(0, 0, half_width * 0.5), Vector3.ZERO, Vector3(1.0, 0.9, 0.4))
		_m(eye, "Pupil", _sphere(half_width * iris_fill * 0.42), _mat("line"),
				Vector3(0, 0, half_width * 0.72), Vector3.ZERO, Vector3(1.0, 1.0, 0.35))
		# The big highlight kemono.md asks for.
		_m(eye, "Highlight", _sphere(half_width * 0.26), _white_mat,
				Vector3(half_width * 0.3 * side, half_width * 0.3, half_width * 0.78),
				Vector3.ZERO, Vector3(1.0, 1.0, 0.3))
		var lid := _anchor(eye, "UpperLid", Vector3(0, half_width * 0.62, half_width * 0.2))
		lid.rotation_degrees.x = lid_angle
		_m(lid, "Mesh", _box(Vector3(half_width * 2.1, half_width * 0.9, half_width * 0.5)),
				_mat(_body_region()), Vector3(0, half_width * 0.42, 0))
		_refs["eye_" + suffix.to_lower()] = eye
		_refs["lid_" + suffix.to_lower()] = lid
	_refs["lid_neutral"] = lid_angle


func _build_eyes(head: Node3D, spec: Dictionary) -> void:
	# V2 faces get proportion-driven eyes; non-face eye kinds (visors, compound
	# clusters) keep their bespoke geometry even under a v2 preset.
	#
	# DELIBERATE: a v2 head places eyes frontally even for families whose
	# head-spec says "side" (equine, cervine, bovine, caprine, macropod). That
	# is the kemono convention — kemono.md's whole eye rule set (0.28-0.35 face
	# width, low-set, inward tilt, iris filling the eye, upper-lid droop as the
	# expression driver) presupposes a forward-facing face, and side-mounted
	# prey eyes cannot carry expression. Those families keep their side eyes on
	# the `legacy` preset.
	if _is_v2() and spec.eyes in ["front", "side", "top"]:
		_build_v2_eyes(head, spec)
		return
	var eye_scale: float = spec.eye_s
	if config.style == "anime":
		eye_scale *= 1.35
	match spec.eyes:
		"visor":
			var visor := _m(head, "Visor", _box(Vector3(0.26, 0.06, 0.04)), _mat("iris"), Vector3(0, 0.05, 0.15))
			_refs["eye_l"] = visor
			_refs["eye_r"] = visor
		"visor_dots":
			_refs["eye_l"] = _m(head, "EyeL", _box(Vector3(0.05, 0.05, 0.02)), _mat("iris"), Vector3(0.08, 0.05, 0.16))
			_refs["eye_r"] = _m(head, "EyeR", _box(Vector3(0.05, 0.05, 0.02)), _mat("iris"), Vector3(-0.08, 0.05, 0.16))
		"compound":
			_refs["eye_l"] = _m(head, "EyeL", _sphere(0.07 * eye_scale), _mat("iris"), Vector3(0.11, 0.06, 0.09), Vector3.ZERO, Vector3(0.9, 1.1, 0.9))
			_refs["eye_r"] = _m(head, "EyeR", _sphere(0.07 * eye_scale), _mat("iris"), Vector3(-0.11, 0.06, 0.09), Vector3.ZERO, Vector3(0.9, 1.1, 0.9))
		"top":
			_refs["eye_l"] = _eyeball(head, "EyeL", Vector3(0.09, 0.16, 0.05), eye_scale, Vector3(-30, 0, 0))
			_refs["eye_r"] = _eyeball(head, "EyeR", Vector3(-0.09, 0.16, 0.05), eye_scale, Vector3(-30, 0, 0))
		"side":
			_refs["eye_l"] = _eyeball(head, "EyeL", Vector3(0.13, 0.05, 0.07), eye_scale, Vector3(0, 42, 0))
			_refs["eye_r"] = _eyeball(head, "EyeR", Vector3(-0.13, 0.05, 0.07), eye_scale, Vector3(0, -42, 0))
		_:
			_refs["eye_l"] = _eyeball(head, "EyeL", Vector3(0.085, 0.05, 0.125), eye_scale, Vector3.ZERO)
			_refs["eye_r"] = _eyeball(head, "EyeR", Vector3(-0.085, 0.05, 0.125), eye_scale, Vector3.ZERO)


func _eyeball(head: Node3D, eye_name: String, pos: Vector3, eye_scale: float, rot_deg: Vector3) -> Node3D:
	var eye := _anchor(head, eye_name, pos)
	eye.rotation_degrees = rot_deg
	_m(eye, "Sclera", _sphere(0.045 * eye_scale), _white_mat, Vector3.ZERO)
	_m(eye, "Iris", _sphere(0.03 * eye_scale), _mat("iris"), Vector3(0, 0, 0.028 * eye_scale), Vector3.ZERO, Vector3(1.0, 1.0, 0.5))
	_m(eye, "Pupil", _sphere(0.014 * eye_scale), _mat("line"), Vector3(0, 0, 0.042 * eye_scale), Vector3.ZERO, Vector3(1.0, 1.0, 0.4))
	return eye


func _build_brows(head: Node3D, spec: Dictionary) -> void:
	if spec.eyes == "visor" or spec.eyes == "compound":
		# Visored/compound faces express through the visor scale instead.
		_refs["brow_l"] = _anchor(head, "BrowL", Vector3(0.085, 0.11, 0.13))
		_refs["brow_r"] = _anchor(head, "BrowR", Vector3(-0.085, 0.11, 0.13))
		return
	var brow_l := _anchor(head, "BrowL", Vector3(0.085, 0.115, 0.13))
	var brow_r := _anchor(head, "BrowR", Vector3(-0.085, 0.115, 0.13))
	_m(brow_l, "Mesh", _box(Vector3(0.07, 0.016, 0.024)), _mat("line"), Vector3.ZERO)
	_m(brow_r, "Mesh", _box(Vector3(0.07, 0.016, 0.024)), _mat("line"), Vector3.ZERO)
	_refs["brow_l"] = brow_l
	_refs["brow_r"] = brow_r


func _ear_style_for(spec: Dictionary) -> String:
	if config.head.earStyle != null:
		return _keyword_ear_style(String(config.head.earStyle))
	var part: Variant = config.parts.get("ears")
	if part != null:
		return _keyword_ear_style(String(part))
	# V2 kemono heads take the large, high, rounded-triangular ear as the
	# family default — kemono.md is explicit that ears carry species identity
	# and that the inner ear is its own colour block. An explicit earStyle or
	# ears partId (handled above) still wins.
	if _is_v2() and String(proportions.get("earStyleHint", "")) == "rounded_tri" \
			and spec.ears != "none":
		return "rounded_tri"
	return spec.ears


func _keyword_ear_style(id_text: String) -> String:
	var t := id_text.to_lower()
	if t.contains("lop") or t.contains("droop"):
		return "droop"
	if t.contains("goo") or t.contains("slime"):
		return "goo"
	if t.contains("long") or t.contains("rabbit") or t.contains("bunny"):
		return "long"
	if t.contains("fin"):
		return "fin"
	if t.contains("big") or t.contains("bat") or t.contains("fennec"):
		return "huge"
	if t.contains("round"):
		return "round"
	if t.contains("tuft"):
		return "tuft"
	if t.contains("human"):
		return "human"
	if t.contains("none"):
		return "none"
	return "pointy"


func _keyword_horn_style(id_text: String) -> String:
	var t := id_text.to_lower()
	if t.contains("ram") or t.contains("curl"):
		return "ram"
	if t.contains("antler"):
		return "antler"
	if t.contains("unicorn"):
		return "unicorn"
	if t.contains("bull") or t.contains("out"):
		return "straight_out"
	if t.contains("stub"):
		return "stubs"
	if t.contains("none"):
		return "none"
	return "curved"


func _build_ear(head: Node3D, style: String, side: float) -> void:
	var suffix := "L" if side > 0.0 else "R"
	var bm := _mat(_body_region())
	# Inner ear is its OWN palette region (kemono.md colour block). It derives
	# from `skin` when a config supplies no explicit hex, so every pre-existing
	# character keeps exactly the colour it had before the region existed.
	var inner := _mat("innerEar")
	var ear_scale: float = float(proportions.get("earScale", 1.0))
	var mount_y: float = 0.17
	if _is_v2():
		mount_y = lerpf(0.17, HEAD_CROWN_Y - 0.02, clampf(
				float(proportions.get("earHeightRatio", 0.9)), 0.0, 1.0))
	var mount := _anchor(head, "Ear" + suffix, Vector3(side * 0.11, mount_y, 0.0))
	_refs["ear_" + suffix.to_lower()] = mount
	match style:
		"rounded_tri":
			# Large, high, rounded-triangular — lofted, not a prism, so the
			# silhouette curves the way a plush kemono ear does.
			var height := 0.17 * ear_scale
			var half_width := 0.055 * ear_scale
			var outer := CharForgeLoft.rounded_tri_ear(height, half_width, 0.018,
					CharForgePalette.region_uv(_body_region()),
					CharForgePalette.region_color(config, _body_region()))
			_m(mount, "Ear", outer, bm, Vector3(0, 0.01, 0), Vector3(-8.0, 0, side * -13.0))
			var inner_mesh := CharForgeLoft.rounded_tri_ear(height * 0.74, half_width * 0.6,
					0.010, CharForgePalette.region_uv("innerEar"),
					CharForgePalette.region_color(config, "innerEar"))
			_m(mount, "Inner", inner_mesh, inner,
					Vector3(0, 0.012, 0.012), Vector3(-8.0, 0, side * -13.0))
		"pointy":
			_m(mount, "Ear", _prism(Vector3(0.09, 0.14, 0.04)), bm, Vector3(0, 0.06, 0), Vector3(0, 0, side * -8.0))
			_m(mount, "Inner", _prism(Vector3(0.045, 0.08, 0.02)), inner, Vector3(0, 0.05, 0.015), Vector3(0, 0, side * -8.0))
		"pointy_wide":
			_m(mount, "Ear", _prism(Vector3(0.13, 0.13, 0.04)), bm, Vector3(0, 0.055, 0), Vector3(0, 0, side * -14.0))
			_m(mount, "Inner", _prism(Vector3(0.07, 0.075, 0.02)), inner, Vector3(0, 0.045, 0.015), Vector3(0, 0, side * -14.0))
		"long":
			_m(mount, "Ear", _capsule(0.035, 0.3), bm, Vector3(0, 0.16, -0.01), Vector3(side * -4.0, 0, side * -7.0))
			_m(mount, "Inner", _capsule(0.018, 0.22), inner, Vector3(0, 0.15, 0.012), Vector3(side * -4.0, 0, side * -7.0))
		"long_angled":
			_m(mount, "Ear", _capsule(0.04, 0.24), bm, Vector3(0, 0.13, -0.03), Vector3(-24.0, 0, side * -16.0))
		"droop":
			_m(mount, "Ear", _capsule(0.035, 0.17), bm, Vector3(side * 0.045, -0.03, 0), Vector3(0, 0, side * 118.0))
		"droop_big":
			_m(mount, "Ear", _prism(Vector3(0.1, 0.15, 0.03)), bm, Vector3(side * 0.05, -0.02, 0), Vector3(0, 0, side * 145.0))
		"round":
			_m(mount, "Ear", _sphere(0.055), bm, Vector3(0, 0.03, 0), Vector3.ZERO, Vector3(1.0, 1.0, 0.5))
			_m(mount, "Inner", _sphere(0.032), inner, Vector3(0, 0.03, 0.02), Vector3.ZERO, Vector3(1.0, 1.0, 0.4))
		"round_big":
			_m(mount, "Ear", _sphere(0.075), bm, Vector3(side * 0.02, 0.045, -0.01), Vector3.ZERO, Vector3(0.9, 1.15, 0.45))
			_m(mount, "Inner", _sphere(0.045), inner, Vector3(side * 0.02, 0.045, 0.015), Vector3.ZERO, Vector3(0.85, 1.1, 0.4))
		"huge":
			_m(mount, "Ear", _prism(Vector3(0.13, 0.2, 0.035)), bm, Vector3(side * 0.01, 0.1, -0.01), Vector3(-6.0, 0, side * -16.0))
			_m(mount, "Inner", _prism(Vector3(0.08, 0.13, 0.018)), inner, Vector3(side * 0.01, 0.09, 0.006), Vector3(-6.0, 0, side * -16.0))
		"big":
			_m(mount, "Ear", _prism(Vector3(0.11, 0.16, 0.035)), bm, Vector3(0, 0.08, -0.005), Vector3(0, 0, side * -20.0))
		"tuft":
			_m(mount, "Ear", _prism(Vector3(0.09, 0.12, 0.04)), bm, Vector3(0, 0.055, 0), Vector3(0, 0, side * -10.0))
			_m(mount, "Tuft", _prism(Vector3(0.03, 0.07, 0.02)), _mat("accent"), Vector3(0, 0.13, 0), Vector3(0, 0, side * -14.0))
		"fin":
			_m(mount, "Fin", _prism(Vector3(0.05, 0.12, 0.09)), _mat("accent"), Vector3(0, 0.045, -0.02), Vector3(30.0, side * 20.0, side * -18.0))
		"goo":
			var goo := _m(mount, "Ear", _sphere(0.05), bm, Vector3(0, 0.04, 0), Vector3.ZERO, Vector3(0.8, 1.3, 0.7))
			goo.rotation_degrees.z = side * -12.0
		"human":
			_m(mount, "Ear", _sphere(0.035), inner, Vector3(side * 0.05, -0.1, 0.0), Vector3.ZERO, Vector3(0.5, 1.0, 0.8))
		_:
			pass


func _build_horn(head: Node3D, style: String) -> void:
	var hm := _mat("detail")
	var mount := _anchor(head, "HornMount", Vector3(0, 0.16, 0.02))
	match style:
		"curved":
			for side in [1.0, -1.0]:
				var suffix := "L" if side > 0.0 else "R"
				_m(mount, "Horn" + suffix + "a", _cyl(0.02, 0.035, 0.12), hm, Vector3(side * 0.08, 0.04, -0.02), Vector3(-35.0, 0, side * -18.0))
				_m(mount, "Horn" + suffix + "b", _cyl(0.008, 0.02, 0.1), hm, Vector3(side * 0.11, 0.1, -0.08), Vector3(-65.0, 0, side * -14.0))
		"straight_out":
			for side in [1.0, -1.0]:
				var suffix := "L" if side > 0.0 else "R"
				_m(mount, "Horn" + suffix, _cyl(0.012, 0.03, 0.16), hm, Vector3(side * 0.14, 0.0, 0), Vector3(0, 0, side * -75.0))
		"ram":
			for side in [1.0, -1.0]:
				var suffix := "L" if side > 0.0 else "R"
				_m(mount, "Horn" + suffix, _torus(0.022, 0.07), hm, Vector3(side * 0.11, 0.0, -0.01), Vector3(0, side * 12.0, 90.0))
		"antler":
			for side in [1.0, -1.0]:
				var suffix := "L" if side > 0.0 else "R"
				_m(mount, "Antler" + suffix + "a", _cyl(0.012, 0.018, 0.18), hm, Vector3(side * 0.07, 0.08, 0), Vector3(-15.0, 0, side * -25.0))
				_m(mount, "Antler" + suffix + "b", _cyl(0.006, 0.011, 0.1), hm, Vector3(side * 0.1, 0.14, 0.02), Vector3(25.0, 0, side * -50.0))
				_m(mount, "Antler" + suffix + "c", _cyl(0.005, 0.01, 0.08), hm, Vector3(side * 0.13, 0.16, -0.03), Vector3(-45.0, 0, side * -65.0))
		"unicorn":
			_m(mount, "Horn", _cyl(0.004, 0.028, 0.17), _mat("accent"), Vector3(0, 0.07, 0.05), Vector3(-20.0, 0, 0))
		"stubs":
			for side in [1.0, -1.0]:
				var suffix := "L" if side > 0.0 else "R"
				_m(mount, "Stub" + suffix, _cyl(0.012, 0.026, 0.05), hm, Vector3(side * 0.07, 0.03, 0), Vector3(-15.0, 0, side * -20.0))
		_:
			pass


func _build_extra(head: Node3D, extra: String, spec: Dictionary) -> void:
	var length := _muzzle_len(spec)
	match extra:
		"nose":
			_m(head, "Nose", _sphere(0.028), _mat("line"), Vector3(0, 0.015, 0.15 + 0.14 * length))
		"nose_small":
			_m(head, "Nose", _sphere(0.02), _mat("skin"), Vector3(0, 0.0, 0.165))
		"nose_plate":
			_m(head, "NosePlate", _box(Vector3(0.14, 0.07, 0.03)), _mat("skin"), Vector3(0, -0.02, 0.15 + 0.1 * length))
		"snout_disc":
			_m(head, "SnoutDisc", _cyl(0.05, 0.05, 0.02), _mat("skin"), Vector3(0, -0.01, 0.2), Vector3(90, 0, 0))
		"whiskers":
			for side in [1.0, -1.0]:
				var suffix := "L" if side > 0.0 else "R"
				_m(head, "Whisker" + suffix + "a", _cyl(0.002, 0.002, 0.14), _white_mat, Vector3(side * 0.12, -0.02, 0.12), Vector3(0, 0, side * -80.0))
				_m(head, "Whisker" + suffix + "b", _cyl(0.002, 0.002, 0.12), _white_mat, Vector3(side * 0.115, -0.045, 0.12), Vector3(0, 0, side * -95.0))
		"crest":
			_m(head, "Crest", _prism(Vector3(0.05, 0.14, 0.16)), _mat("accent"), Vector3(0, 0.18, -0.03), Vector3(-25.0, 0, 0))
		"forelock":
			_m(head, "Forelock", _box(Vector3(0.07, 0.1, 0.05)), _mat("hair"), Vector3(0, 0.17, 0.07), Vector3(-15.0, 0, 0))
		"brow_ridge":
			_m(head, "BrowRidge", _box(Vector3(0.24, 0.03, 0.08)), _mat("detail"), Vector3(0, 0.1, 0.1))
		"spikes":
			for i in range(3):
				_m(head, "Spike%d" % i, _prism(Vector3(0.04, 0.06, 0.03)), _mat("detail"),
						Vector3(0, 0.19 - 0.05 * i, -0.05 - 0.06 * i), Vector3(-30.0 - 12.0 * i, 0, 0))
		"teeth":
			for side in [1.0, -1.0]:
				var suffix := "L" if side > 0.0 else "R"
				_m(head, "Tooth" + suffix, _prism(Vector3(0.02, 0.03, 0.015)), _white_mat,
						Vector3(side * 0.06, -0.075, 0.12 + 0.1 * length), Vector3(180.0, 0, 0))
		"fangs":
			for side in [1.0, -1.0]:
				var suffix := "L" if side > 0.0 else "R"
				_m(head, "Fang" + suffix, _prism(Vector3(0.014, 0.03, 0.012)), _white_mat,
						Vector3(side * 0.04, -0.06, 0.14), Vector3(180.0, 0, 0))
		"buck_teeth":
			_m(head, "BuckTeeth", _box(Vector3(0.035, 0.035, 0.015)), _white_mat, Vector3(0, -0.075, 0.13 + 0.08 * length))
		"tusks":
			for side in [1.0, -1.0]:
				var suffix := "L" if side > 0.0 else "R"
				_m(head, "Tusk" + suffix, _cyl(0.008, 0.018, 0.07), _white_mat,
						Vector3(side * 0.08, -0.05, 0.14), Vector3(-25.0, 0, side * 20.0))
		"goatee":
			_m(head, "Goatee", _prism(Vector3(0.04, 0.08, 0.03)), _mat("hair"), Vector3(0, -0.13, 0.1), Vector3(180.0, 0, 0))
		"eye_mask":
			_m(head, "EyeMask", _box(Vector3(0.26, 0.075, 0.02)), _mat("accent"), Vector3(0, 0.05, 0.135))
		"antennae":
			for side in [1.0, -1.0]:
				var suffix := "L" if side > 0.0 else "R"
				_m(head, "Antenna" + suffix, _cyl(0.005, 0.008, 0.2), _mat("line"), Vector3(side * 0.07, 0.24, 0.02), Vector3(-20.0, 0, side * -18.0))
				_m(head, "AntennaTip" + suffix, _sphere(0.016), _mat("accent"), Vector3(side * 0.1, 0.33, 0.06))
		"antennae_feather":
			for side in [1.0, -1.0]:
				var suffix := "L" if side > 0.0 else "R"
				_m(head, "Antenna" + suffix, _prism(Vector3(0.045, 0.2, 0.012)), _mat("accent"),
						Vector3(side * 0.07, 0.26, 0.0), Vector3(-15.0, 0, side * -22.0))
		"fuzz_ruff":
			_m(head, "Ruff", _torus(0.045, 0.16), _mat("detail"), Vector3(0, -0.1, 0), Vector3(12.0, 0, 0))
		"mandibles":
			for side in [1.0, -1.0]:
				var suffix := "L" if side > 0.0 else "R"
				_m(head, "Mandible" + suffix, _prism(Vector3(0.025, 0.06, 0.02)), _mat("line"),
						Vector3(side * 0.05, -0.08, 0.13), Vector3(160.0, 0, side * 25.0))
		"wide_mouth":
			_m(head, "MouthLine", _box(Vector3(0.26, 0.012, 0.05)), _mat("line"), Vector3(0, -0.055, 0.12))
		"dorsal_fin":
			_m(head, "HeadFin", _prism(Vector3(0.04, 0.1, 0.14)), _mat("accent"), Vector3(0, 0.2, -0.04), Vector3(-30.0, 0, 0))
		"drips":
			for i in range(3):
				var t := _hash01(config.name, i)
				_m(head, "Drip%d" % i, _sphere(0.025 + 0.015 * t), _mat("fur"),
						Vector3(lerpf(-0.12, 0.12, t), -0.02 - 0.05 * t, lerpf(-0.08, 0.1, _hash01(config.name, i + 9))))
		"antenna_bolt":
			_m(head, "Antenna", _cyl(0.008, 0.008, 0.12), _mat("line"), Vector3(0.09, 0.24, 0))
			_m(head, "AntennaBall", _sphere(0.02), _mat("accent"), Vector3(0.09, 0.31, 0))
			for side in [1.0, -1.0]:
				var suffix := "L" if side > 0.0 else "R"
				_m(head, "Bolt" + suffix, _cyl(0.025, 0.025, 0.04), _mat("line"), Vector3(side * 0.17, 0.02, 0), Vector3(0, 0, 90.0))
		"seams":
			_m(head, "Seam", _box(Vector3(0.005, 0.22, 0.24)), _mat("accent"), Vector3(0, 0.08, 0))
		_:
			pass


# =============================================================================
# Slot parts (tail / wings / ears / feature / accessory / markings / hair)
# =============================================================================

func _build_slot_parts(family: String) -> void:
	# ears are consumed inside _build_head (they replace the family default).
	for slot in ["tail", "wings", "feature", "accessory", "markings", "hair"]:
		var part: Variant = config.parts.get(slot)
		var part_id := "" if part == null else String(part)
		if part_id.is_empty():
			# Default-on assemblies even with no explicit part chosen.
			if slot == "tail" and _default_tail_for(family) != "":
				_build_tail(_default_tail_for(family), family)
			elif slot == "hair" and family == "humanoid":
				_build_hair("hair_default", family)
			continue
		var custom := _custom_builder_node(slot, part_id)
		if custom != null:
			continue
		match slot:
			"tail":
				_build_tail(part_id, family)
			"wings":
				_build_wings(part_id)
			"feature":
				_build_feature(part_id)
			"accessory":
				_build_accessory(part_id)
			"markings":
				_build_markings(part_id)
			"hair":
				_build_hair(part_id, family)


## Consults the GLB/override registry first. Returns the produced node or null.
func _custom_builder_node(slot: String, part_id: String) -> Node3D:
	for entry in _extra_builders:
		if entry.slot != slot:
			continue
		if not entry.matcher.call(part_id):
			continue
		var node: Variant = entry.builder.call(self, part_id)
		if node is Node3D:
			return node
	return null


func _default_tail_for(family: String) -> String:
	match family:
		"canid":
			return "tail_wolf_bushy_01"
		"felid":
			return "tail_cat_01"
		"dragon", "kobold", "dino", "reptile":
			return "tail_thick_lizard_01"
		"equine":
			return "tail_horse_01"
		"lapine":
			return "tail_bunny_puff_01"
		"rodent":
			return "tail_rat_thin_01"
		"aquatic":
			return "tail_cetacean_01"
		"macropod":
			return "tail_macropod_01"
		"hyena":
			return "tail_hyena_01"
		"mustelid", "procyonid":
			return "tail_ringed_01"
		"ursine", "cervine":
			return "tail_deer_01"
		"slime":
			return "tail_goo_01"
		"humanoid", "moth", "insect", "robot", "synth", "avian", "amphibian", "bat", "gryphon", "bovine", "caprine", "suid":
			return ""
		_:
			return "tail_cat_01"


func _tail_root() -> Node3D:
	var mount: Node3D = _refs["tail_mount"]
	var root := _anchor(mount, "Tail", Vector3.ZERO)
	return root


func _build_tail(part_id: String, family: String) -> void:
	var t := part_id.to_lower()
	var root := _tail_root()
	var fur := _mat("fur")
	var accent := _mat("accent")
	# Tail tips are their own kemono colour block; `tailTip` derives from
	# `accent` when a config supplies no explicit hex, so existing tails keep
	# exactly the colour they had before the region existed.
	var tip_mat := _mat("tailTip")
	var var_len := 0.85 + 0.4 * _hash01(part_id, 1)
	if t.contains("fox") or t.contains("wolf") or t.contains("husky") or t.contains("bushy") or t.contains("squirrel"):
		var up := 22.0 if t.contains("squirrel") else -18.0
		_m(root, "Base", _sphere(0.075), fur, Vector3(0, 0.02, -0.1 * var_len), Vector3(up, 0, 0))
		_m(root, "Mid", _sphere(0.09), fur, Vector3(0, 0.04 if up > 0.0 else -0.02, -0.24 * var_len))
		_m(root, "Tip", _sphere(0.06), tip_mat, Vector3(0, 0.1 if up > 0.0 else -0.05, -0.36 * var_len))
	elif t.contains("cat") or t.contains("thin") or t.contains("rat"):
		_m(root, "Tail", _capsule(0.028, 0.42 * var_len), fur, Vector3(0, 0.05, -0.16), Vector3(-55.0, 0, 0))
		if not t.contains("rat"):
			_m(root, "Tip", _sphere(0.03), tip_mat, Vector3(0, 0.22, -0.3))
	elif t.contains("lion") or t.contains("cow") or t.contains("tassel"):
		_m(root, "Tail", _capsule(0.02, 0.4), fur, Vector3(0, -0.06, -0.12), Vector3(-115.0, 0, 0))
		_m(root, "Tuft", _sphere(0.045), tip_mat, Vector3(0, -0.24, -0.22))
	elif t.contains("shark") or t.contains("cetacean") or t.contains("orca"):
		_m(root, "Stem", _cyl(0.05, 0.08, 0.22), fur, Vector3(0, -0.02, -0.14), Vector3(-70.0, 0, 0))
		_m(root, "Fluke", _prism(Vector3(0.22, 0.1, 0.03)), accent, Vector3(0, -0.02, -0.3), Vector3(-90.0, 0, 0))
	elif t.contains("feather") or t.contains("phoenix") or t.contains("fan") or t.contains("gryphon"):
		for i in range(5):
			var spread := (float(i) - 2.0) * 14.0
			_m(root, "Feather%d" % i, _box(Vector3(0.05, 0.008, 0.3)), accent if i % 2 == 0 else fur,
					Vector3(0, 0.0, -0.18), Vector3(-18.0, spread, 0))
	elif t.contains("spade") or t.contains("kobold") or t.contains("lizard") or t.contains("dragon") or t.contains("dino") or t.contains("thick"):
		_m(root, "Base", _cyl(0.05, 0.09, 0.3), fur, Vector3(0, -0.04, -0.18), Vector3(-65.0, 0, 0))
		_m(root, "Tip", _cyl(0.008, 0.05, 0.24), fur, Vector3(0, -0.1, -0.42), Vector3(-80.0, 0, 0))
		if t.contains("spade"):
			_m(root, "Spade", _prism(Vector3(0.09, 0.09, 0.02)), accent, Vector3(0, -0.13, -0.55), Vector3(-90.0, 0, 0))
	elif t.contains("eastern") or t.contains("serpent"):
		for i in range(4):
			_m(root, "Seg%d" % i, _capsule(0.04 - 0.006 * i, 0.18), fur,
					Vector3(0.05 * sin(float(i) * 1.7), 0.02 + 0.05 * i, -0.16 - 0.11 * i), Vector3(-80.0, 10.0 * i, 0))
	elif t.contains("puff") or t.contains("bunny") or t.contains("deer") or t.contains("stub") or t.contains("bear"):
		_m(root, "Puff", _sphere(0.055), tip_mat if t.contains("deer") else fur, Vector3(0, 0.02, -0.06))
	elif t.contains("horse"):
		_m(root, "Dock", _capsule(0.03, 0.1), fur, Vector3(0, 0.0, -0.08), Vector3(-30.0, 0, 0))
		_m(root, "Hair", _box(Vector3(0.08, 0.42, 0.05)), _mat("hair"), Vector3(0, -0.22, -0.12))
	elif t.contains("ringed") or t.contains("redpanda"):
		for i in range(4):
			_m(root, "Ring%d" % i, _capsule(0.045 - 0.004 * i, 0.1), fur if i % 2 == 0 else accent,
					Vector3(0, 0.03 + 0.02 * i, -0.12 - 0.09 * i), Vector3(-70.0, 0, 0))
	elif t.contains("goo"):
		var goo := _m(root, "Goo", _sphere(0.07), fur, Vector3(0, -0.01, -0.12), Vector3.ZERO, Vector3(0.9, 0.75, 1.3))
		goo.rotation_degrees.x = -20.0
	elif t.contains("synth"):
		for i in range(3):
			_m(root, "Seg%d" % i, _box(Vector3(0.05, 0.05, 0.09)), accent if i == 2 else fur,
					Vector3(0, 0.02 * i, -0.1 - 0.11 * i), Vector3(-12.0 * i, 0, 0))
	elif t.contains("macropod") or t.contains("kangaroo"):
		_m(root, "Tail", _cyl(0.035, 0.09, 0.5), fur, Vector3(0, -0.18, -0.22), Vector3(-125.0, 0, 0))
	elif t.contains("pig") or t.contains("curl"):
		_m(root, "Curl", _torus(0.012, 0.045), fur, Vector3(0, 0.02, -0.08), Vector3(0, 90.0, 0))
	elif t.contains("otter") or t.contains("tadpole"):
		_m(root, "Tail", _cyl(0.02, 0.07, 0.36), fur, Vector3(0, -0.08, -0.2), Vector3(-105.0, 0, 0))
	else:
		# Unknown id: family default silhouette, hash-varied.
		var fallback := _default_tail_for(family)
		if fallback != "" and fallback.to_lower() != t:
			_build_tail_into(root, fallback, family)
		else:
			_m(root, "Tail", _capsule(0.04, 0.3 * var_len), fur, Vector3(0, 0.0, -0.16), Vector3(-60.0, 0, 0))


## Re-dispatch helper used by the unknown-id fallback (keeps one Tail root).
func _build_tail_into(root: Node3D, part_id: String, family: String) -> void:
	var saved: Node3D = _refs["tail_mount"]
	_refs["tail_mount"] = root.get_parent()
	root.get_parent().remove_child(root)
	root.free()
	_build_tail(part_id, family)
	_refs["tail_mount"] = saved


func _build_wings(part_id: String) -> void:
	var t := part_id.to_lower()
	var mount: Node3D = _refs["wing_mount"]
	for side in [1.0, -1.0]:
		var suffix := "L" if side > 0.0 else "R"
		var wing := _anchor(mount, "Wing" + suffix, Vector3(side * 0.12, 0.02, 0))
		wing.rotation_degrees = Vector3(8.0, side * -30.0, side * 20.0)
		if t.contains("feather") or t.contains("small"):
			var span := 0.5 if t.contains("feather") else 0.28
			for i in range(3):
				_m(wing, "Layer%d" % i, _box(Vector3(span - 0.1 * i, 0.02, 0.1)), _white_mat if i == 2 else _mat("accent"),
						Vector3(side * (0.16 + 0.08 * i), 0.03 * i, -0.02 * i), Vector3(0, 0, side * (6.0 + 4.0 * i)))
		elif t.contains("bat") or t.contains("dragon"):
			_m(wing, "Arm", _cyl(0.015, 0.02, 0.3), _mat("fur"), Vector3(side * 0.14, 0.06, 0), Vector3(0, 0, side * -70.0))
			_m(wing, "Membrane", _prism(Vector3(0.4, 0.26, 0.015)), _mat("accent"),
					Vector3(side * 0.22, -0.06, 0), Vector3(180.0, 0, side * 8.0))
		elif t.contains("insect") or t.contains("butterfly") or t.contains("moth"):
			var wm := materials.variant("accent", {"alpha": 0.6, "rim_strength": 0.7})
			_m(wing, "Fore", _sphere(0.16), wm, Vector3(side * 0.14, 0.06, 0), Vector3.ZERO, Vector3(1.4, 0.9, 0.1))
			_m(wing, "Hind", _sphere(0.11), wm, Vector3(side * 0.1, -0.08, 0), Vector3.ZERO, Vector3(1.2, 0.8, 0.1))
		elif t.contains("crystal"):
			_m(wing, "Shard1", _prism(Vector3(0.08, 0.3, 0.03)), _mat("accent"), Vector3(side * 0.12, 0.1, 0), Vector3(0, 0, side * -35.0))
			_m(wing, "Shard2", _prism(Vector3(0.06, 0.2, 0.025)), _mat("detail"), Vector3(side * 0.2, 0.0, 0), Vector3(0, 0, side * -60.0))
		elif t.contains("goo"):
			_m(wing, "Blob", _sphere(0.1), _mat("fur"), Vector3(side * 0.1, 0.02, 0), Vector3.ZERO, Vector3(1.3, 0.8, 0.4))
		else:
			_m(wing, "Wing", _prism(Vector3(0.3, 0.2, 0.02)), _mat("accent"), Vector3(side * 0.16, 0.02, 0), Vector3(180.0, 0, side * 15.0))


func _build_feature(part_id: String) -> void:
	var t := part_id.to_lower()
	var head: Node3D = _refs["head"]
	var mount: Node3D = _refs["feature_mount"]
	if t.contains("mane") and not t.contains("fin"):
		var ring := _m(_refs["accessory_mount"], "Mane", _torus(0.07, 0.2), _mat("hair"), Vector3(0, 0.04, 0), Vector3(14.0, 0, 0))
		ring.scale = Vector3(1.0, 1.25, 1.0)
	elif t.contains("antler"):
		_build_horn(head, "antler")
	elif t.contains("unicorn"):
		_build_horn(head, "unicorn")
	elif t.contains("ram") or t.contains("goat"):
		_build_horn(head, "ram")
	elif t.contains("bull"):
		_build_horn(head, "straight_out")
	elif t.contains("horn"):
		_build_horn(head, "curved")
	elif t.contains("dorsal"):
		_m(_refs["wing_mount"], "DorsalFin", _prism(Vector3(0.06, 0.18, 0.2)), _mat("accent"), Vector3(0, 0.1, -0.02), Vector3(-25.0, 0, 0))
	elif t.contains("head_fin") or t.contains("fin_mane") or t.contains("synth_fin") or t.contains("fins"):
		for i in range(3):
			_m(mount, "Fin%d" % i, _prism(Vector3(0.04, 0.09 - 0.02 * i, 0.1)), _mat("accent"),
					Vector3(0, 0.03 - 0.045 * i, -0.04 - 0.07 * i), Vector3(-35.0 - 10.0 * i, 0, 0))
	elif t.contains("crest"):
		_build_extra(head, "crest", _head_spec(config.head.family))
	elif t.contains("frill"):
		var frill := _m(mount, "Frill", _torus(0.03, 0.17), _mat("accent"), Vector3(0, -0.02, -0.04), Vector3(75.0, 0, 0))
		frill.scale = Vector3(1.2, 1.0, 1.2)
	elif t.contains("tusk"):
		_build_extra(head, "tusks", _head_spec(config.head.family))
	elif t.contains("antennae_ball"):
		_build_extra(head, "antennae", _head_spec(config.head.family))
	elif t.contains("antenna"):
		_build_extra(head, "antennae_feather", _head_spec(config.head.family))
	elif t.contains("halo"):
		_m(mount, "Halo", _torus(0.012, 0.1), materials.variant("accent", {"emission": 1.6}),
				Vector3(0, 0.18, 0))
	elif t.contains("core_orb"):
		_m(_refs["chest_front"], "CoreOrb", _sphere(0.05),
				materials.variant("accent", {"emission": 1.6}), Vector3.ZERO)
	elif t.contains("shell"):
		_m(_refs["wing_mount"], "Shell", _sphere(0.19), _mat("detail"), Vector3(0, 0.02, -0.1), Vector3.ZERO, Vector3(1.0, 0.95, 0.8))
		_m(_refs["wing_mount"], "ShellCurl", _torus(0.04, 0.13), _mat("line"), Vector3(0, 0.04, -0.16), Vector3(0, 0, 90.0))
	elif t.contains("spine"):
		for i in range(4):
			_m(_refs["wing_mount"], "Spine%d" % i, _prism(Vector3(0.035, 0.06, 0.03)), _mat("detail"),
					Vector3(0, 0.16 - 0.09 * i, -0.05), Vector3(-25.0, 0, 0))
	elif t.contains("goo_crown"):
		for i in range(4):
			_m(mount, "GooSpike%d" % i, _sphere(0.03), _mat("fur"),
					Vector3(lerpf(-0.09, 0.09, float(i) / 3.0), 0.06, 0), Vector3.ZERO, Vector3(0.8, 1.6, 0.8))
	else:
		_build_extra(head, "crest", _head_spec(config.head.family))


func _build_accessory(part_id: String) -> void:
	var t := part_id.to_lower()
	var neck: Node3D = _refs["accessory_mount"]
	var head: Node3D = _refs["head"]
	if t.contains("bandana"):
		var band := _m(neck, "Bandana", _torus(0.035, 0.13), _mat("accent"), Vector3(0, 0.0, 0), Vector3(8.0, 0, 0))
		band.scale = Vector3(1.0, 0.8, 1.0)
		_m(neck, "Knot", _prism(Vector3(0.09, 0.11, 0.02)), _mat("accent"), Vector3(0, -0.09, 0.12), Vector3(180.0, 0, 0))
	elif t.contains("collar"):
		_m(neck, "Collar", _torus(0.02, 0.125), _mat("line"), Vector3.ZERO, Vector3(8.0, 0, 0))
		_m(neck, "Tag", _sphere(0.022), _mat("accent"), Vector3(0, -0.045, 0.12))
	elif t.contains("glasses"):
		for side in [1.0, -1.0]:
			var suffix := "L" if side > 0.0 else "R"
			_m(head, "Lens" + suffix, _torus(0.008, 0.05), _mat("line"), Vector3(side * 0.085, 0.05, 0.15), Vector3(90.0, 0, 0))
		_m(head, "Bridge", _cyl(0.006, 0.006, 0.05), _mat("line"), Vector3(0, 0.05, 0.15), Vector3(0, 0, 90.0))
	elif t.contains("mushroom"):
		_m(_refs["hair_mount"], "Cap", _sphere(0.14), _mat("accent"), Vector3(0, 0.06, 0), Vector3.ZERO, Vector3(1.1, 0.55, 1.1))
		for i in range(3):
			_m(_refs["hair_mount"], "Dot%d" % i, _sphere(0.025), _white_mat,
					Vector3(lerpf(-0.08, 0.08, float(i) / 2.0), 0.1, 0.06 - 0.05 * i))
	elif t.contains("piercing"):
		_m(head, "Piercing", _torus(0.005, 0.018), _mat("accent"), Vector3(-0.13, 0.14, 0.0), Vector3(0, 90.0, 0))
	elif t.contains("slime_pet") or t.contains("pet"):
		var pet_mat := materials.variant("accent", {"alpha": 0.75})
		var pet := _m(_refs["body"], "SlimePet", _sphere(0.07), pet_mat, Vector3(0.3, 1.22, 0.02), Vector3.ZERO, Vector3(1.0, 0.8, 1.0))
		_m(pet, "EyeL", _sphere(0.012), _mat("line"), Vector3(0.02, 0.02, 0.06))
		_m(pet, "EyeR", _sphere(0.012), _mat("line"), Vector3(-0.02, 0.02, 0.06))
	else:
		_m(neck, "Charm", _sphere(0.03), _mat("accent"), Vector3(0, -0.05, 0.12))


func _build_markings(part_id: String) -> void:
	var t := part_id.to_lower()
	var body: Node3D = _refs["body"]
	var head: Node3D = _refs["head"]
	var accent := _mat("accent")
	var detail := _mat("detail")
	if t.contains("mask"):
		_m(head, "MaskMark", _box(Vector3(0.27, 0.08, 0.02)), accent, Vector3(0, 0.05, 0.132))
	elif t.contains("countershade") or t.contains("belly"):
		_m(body, "BellyMark", _capsule(0.16, 0.42), detail, Vector3(0, 0.9, 0.09), Vector3.ZERO, Vector3(1.0, 1.0, 0.55))
	elif t.contains("sock"):
		for side in [1.0, -1.0]:
			var suffix := "L" if side > 0.0 else "R"
			# Socks/gloves are their own kemono colour block (derives from accent).
			_m(body, "SockLeg" + suffix, _cyl(0.095, 0.1, 0.14), _mat("socks"), Vector3(side * 0.13, 0.14, 0))
			_m(body, "SockArm" + suffix, _cyl(0.075, 0.078, 0.1), _mat("socks"), Vector3(side * 0.35, 0.72, 0.02))
	elif t.contains("blaze") or t.contains("muzzle"):
		_m(head, "Blaze", _box(Vector3(0.05, 0.02, 0.22)), detail, Vector3(0, 0.11, 0.1), Vector3(-14.0, 0, 0))
	elif t.contains("stripe") and t.contains("bee"):
		for i in range(3):
			_m(body, "BeeStripe%d" % i, _torus(0.035, 0.19), _mat("line"), Vector3(0, 0.78 + 0.14 * i, 0), Vector3(4.0, 0, 0))
	elif t.contains("stripe") or t.contains("coil"):
		for i in range(4):
			_m(body, "Stripe%d" % i, _box(Vector3(0.3, 0.03, 0.03)), accent,
					Vector3(0, 0.75 + 0.13 * i, -0.16 + 0.01 * i), Vector3(12.0 - 4.0 * i, 0, 0))
	elif t.contains("spot") or t.contains("fawn") or t.contains("patch"):
		for i in range(6):
			var tx := _hash01(part_id, i)
			var ty := _hash01(part_id, i + 20)
			var big := t.contains("holstein") or t.contains("patch")
			_m(body, "Spot%d" % i, _sphere(0.055 if big else 0.03), accent if not big else detail,
					Vector3(lerpf(-0.18, 0.18, tx), lerpf(0.7, 1.15, ty), lerpf(0.12, 0.19, _hash01(part_id, i + 40))),
					Vector3.ZERO, Vector3(1.0, 1.0, 0.25))
	elif t.contains("scute"):
		for i in range(3):
			_m(body, "Scute%d" % i, _box(Vector3(0.16 - 0.02 * i, 0.08, 0.03)), detail, Vector3(0, 0.72 + 0.15 * i, 0.17))
	elif t.contains("crack") or t.contains("growth") or t.contains("crystal"):
		for i in range(4):
			_m(body, "Shard%d" % i, _prism(Vector3(0.03, 0.06, 0.02)), accent,
					Vector3(lerpf(-0.15, 0.15, _hash01(part_id, i)), lerpf(0.75, 1.1, _hash01(part_id, i + 7)), 0.16),
					Vector3(0, 0, lerpf(-40.0, 40.0, _hash01(part_id, i + 13))))
	elif t.contains("sheen") or t.contains("metallic"):
		_m(body, "Sheen", _box(Vector3(0.06, 0.4, 0.02)), _white_mat, Vector3(0.12, 0.92, 0.17), Vector3(0, 0, 15.0))
	elif t.contains("drip") or t.contains("goo"):
		for i in range(4):
			_m(body, "DripMark%d" % i, _sphere(0.03), accent,
					Vector3(lerpf(-0.16, 0.16, _hash01(part_id, i)), lerpf(0.6, 1.05, _hash01(part_id, i + 5)), 0.15),
					Vector3.ZERO, Vector3(0.8, 1.5, 0.5))
	else:
		_m(body, "GenericMark", _capsule(0.14, 0.3), detail, Vector3(0, 0.92, 0.1), Vector3.ZERO, Vector3(1.0, 1.0, 0.5))


func _build_hair(part_id: String, family: String) -> void:
	var t := part_id.to_lower()
	var mount: Node3D = _refs["hair_mount"]
	var hair := _mat("hair")
	if t.contains("flower"):
		_m(mount, "Stem", _cyl(0.006, 0.006, 0.05), _mat("detail"), Vector3(0.08, 0.03, 0))
		for i in range(5):
			var ang := float(i) * TAU / 5.0
			_m(mount, "Petal%d" % i, _sphere(0.022), _mat("accent"),
					Vector3(0.08 + 0.028 * cos(ang), 0.065 + 0.028 * sin(ang), 0), Vector3.ZERO, Vector3(1.0, 1.0, 0.4))
		_m(mount, "FlowerCore", _sphere(0.016), _white_mat, Vector3(0.08, 0.065, 0.012))
	elif t.contains("leaf"):
		for i in range(3):
			_m(mount, "Leaf%d" % i, _prism(Vector3(0.05, 0.09, 0.01)), _mat("accent"),
					Vector3(lerpf(-0.06, 0.06, float(i) / 2.0), 0.05, 0), Vector3(-20.0, 0, lerpf(-25.0, 25.0, float(i) / 2.0)))
	elif t.contains("goo"):
		_m(mount, "GooCap", _sphere(0.13), _mat("fur"), Vector3(0, 0.02, 0), Vector3.ZERO, Vector3(1.15, 0.6, 1.1))
	elif int(proportions.get("hairClusters", 0)) > 0:
		_build_spike_cluster_hair(mount, part_id, hair)
	else:
		# Standard cap + bangs + back, hash-varied length (anime hairstyles).
		var length := 0.7 + 0.8 * _hash01(part_id, 3)
		_m(mount, "Cap", _sphere(0.155), hair, Vector3(0, 0.01, -0.01), Vector3.ZERO, Vector3(1.08, 0.85, 1.05))
		for i in range(3):
			_m(mount, "Bang%d" % i, _box(Vector3(0.055, 0.09, 0.03)), hair,
					Vector3(lerpf(-0.08, 0.08, float(i) / 2.0), -0.02, 0.12), Vector3(lerpf(-8.0, 8.0, _hash01(part_id, i)), 0, 0))
		_m(mount, "Back", _box(Vector3(0.2, 0.28 * length, 0.06)), hair, Vector3(0, -0.12 * length, -0.12))


## JRPG hair (jrpg-bases.md): "5-9 asymmetric spike clusters, 1.2-1.4x the
## cranium, black-silhouette-readable at 64px". Asymmetry is the point — the
## clusters are laid out around the crown with a deterministic per-partId
## offset so no two hairstyles line up, and every cluster gets its own tilt and
## length. Silhouette readability comes from the clusters overshooting the
## cranium radius rather than hugging it.
func _build_spike_cluster_hair(mount: Node3D, part_id: String, hair: Material) -> void:
	var count: int = clampi(int(proportions.get("hairClusters", 7)),
			CharForgeProportions.HAIR_CLUSTER_BAND.x, CharForgeProportions.HAIR_CLUSTER_BAND.y)
	var scale_ratio: float = clampf(float(proportions.get("hairScale", 1.3)), 1.0, 1.5)
	var radius := SKULL_RADIUS * 0.92
	_m(mount, "Cap", _sphere(radius), hair, Vector3(0, 0.0, -0.005),
			Vector3.ZERO, Vector3(1.06, 0.88, 1.04))
	# The rotational phase is hashed off the partId, so "spiky_01" and
	# "spiky_02" are visibly different heads rather than the same one twice.
	var phase := _hash01(part_id, 11) * TAU
	for i in range(count):
		var t := float(i) / float(count)
		var angle := phase + t * TAU
		# Asymmetric: the per-cluster jitter is intentionally NOT mirrored.
		var jitter := _hash01(part_id, 40 + i)
		var length := radius * scale_ratio * lerpf(0.55, 1.0, jitter)
		var lean := lerpf(-26.0, 26.0, _hash01(part_id, 70 + i))
		var pos := Vector3(cos(angle) * radius * 0.72, radius * 0.36 + 0.02 * jitter,
				sin(angle) * radius * 0.72)
		var spike := _m(mount, "Spike%d" % i,
				_prism(Vector3(radius * lerpf(0.34, 0.52, jitter), length, radius * 0.34)),
				hair, pos)
		spike.rotation_degrees = Vector3(
				-rad_to_deg(atan2(sin(angle), 1.6)) * 0.5,
				rad_to_deg(angle),
				lean)


# =============================================================================
# Expressions + jaw
# =============================================================================

func _expr(key: String, fallback: Variant) -> Variant:
	var preset: Dictionary = EXPRESSION_PRESETS.get(expression, {})
	return preset.get(key, fallback)


func _apply_expression() -> void:
	# Reset every rig joint to its built pose first: expression + rig are both
	# applied as deltas from that base, so repeated calls never accumulate.
	for joint in _rig_base:
		var n: Node3D = _refs.get(joint)
		if n != null:
			n.position = _rig_base[joint].pos
			n.scale = _rig_base[joint].scale
	var eye_open: float = _expr("eye_open", 1.0)
	var eye_l_open: float = _expr("eye_l", eye_open)
	var eye_r_open: float = _expr("eye_r", eye_open)
	var brow_tilt: float = _expr("brow_tilt", 0.0)
	var brow_raise: float = _expr("brow_raise", 0.0)
	var brow_asym: bool = _expr("brow_asym", false)
	var ear_pitch: float = _expr("ear_pitch", 0.0)
	var head_tilt: float = _expr("head_tilt", 0.0)

	var eye_l: Node3D = _refs.get("eye_l")
	var eye_r: Node3D = _refs.get("eye_r")
	if eye_l != null and eye_l == eye_r:
		# Visor: one node expresses for both eyes.
		eye_l.scale.y = clampf(eye_open, 0.05, 1.6)
	else:
		if eye_l != null:
			eye_l.scale.y = clampf(eye_l_open, 0.05, 1.6)
		if eye_r != null:
			eye_r.scale.y = clampf(eye_r_open, 0.05, 1.6)

	var brow_l: Node3D = _refs.get("brow_l")
	var brow_r: Node3D = _refs.get("brow_r")
	if brow_l != null:
		brow_l.rotation_degrees.z = brow_tilt
		brow_l.position.y = 0.115 + brow_raise
	if brow_r != null:
		brow_r.rotation_degrees.z = -brow_tilt
		brow_r.position.y = 0.115 + brow_raise + (0.02 if brow_asym else 0.0)
		if brow_asym:
			brow_r.rotation_degrees.z = -brow_tilt - 10.0

	for ear_key in ["ear_l", "ear_r"]:
		var ear: Node3D = _refs.get(ear_key)
		if ear != null:
			ear.rotation_degrees.x = ear_pitch

	var head: Node3D = _refs.get("head")
	if head != null:
		head.rotation_degrees.z = head_tilt

	var blush: Node3D = _refs.get("blush")
	if blush != null:
		blush.visible = _expr("blush", false)

	# Upper-lid angle — the kemono expression driver. Neutral droop comes from
	# the proportion row; the preset adds a delta on top.
	var lid_neutral: float = float(_refs.get("lid_neutral", 0.0))
	var lid_delta: float = _expr("lid_delta", 0.0)
	for lid_key in ["lid_l", "lid_r"]:
		var lid: Node3D = _refs.get(lid_key)
		if lid != null:
			lid.rotation_degrees.x = clampf(lid_neutral + lid_delta, -25.0, 62.0)

	_apply_rig()
	_apply_jaw()


func _apply_jaw() -> void:
	var jaw: Node3D = _refs.get("jaw")
	if jaw == null:
		return
	# The jaw is the MANDATORY lip-sync channel; the angle math lives in
	# CharForgeLipSync so the procedural rig and imported skeletons cannot
	# drift apart.
	lip_sync.bind_jaw_node(jaw)
	lip_sync.expression_jaw_base = clampf(float(_expr("jaw_base", 0.0)), 0.0, 1.0)
	lip_sync.set_openness(openness)
