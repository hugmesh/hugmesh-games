extends Node3D

# ─────────────────────────────────────────────────────────────────────────────
# ROOM-LOCAL ASSEMBLER — CharacterAssembler (Char3DConfig v1 → Node3D rig)
#
# PROVENANCE (read before "re-syncing" anything)
#   This is an INDEPENDENT implementation, NOT a byte-copy. A sibling assembler
#   exists in the character-forge repo at
#     hugmesh-character-forge-bff-api/godot/character-forge/scripts/character_assembler.gd
#   and both files carry each other's name, but they share no implementation:
#   ~750 lines here vs ~1200 there, and the public APIs are incompatible —
#   that file is `class_name CharacterAssembler` with `apply_config(cfg)` +
#   `rebuild()` and depends on the forge project's `Char3DConfig` autoload,
#   while this one is a plain `preload()`ed script with `assemble(raw)` +
#   static `parse_config()`/`random_config()` and no project-level
#   dependencies (deliberate: the room is a self-contained wasm bundle).
#   This copy also implements `set_blendshapes()` (52-weight MediaPipe vector),
#   which the forge copy does not have at all.
#
#   DO NOT "fix the canonical copy and re-vendor" — dropping the forge file in
#   here removes set_blendshapes, breaks every `assemble()` call site in
#   avatar_manager.gd, and pulls in an autoload this project doesn't have.
#
#   WHAT IS ACTUALLY SHARED, and must stay in lockstep, is the DATA CONTRACT,
#   not the code: the Char3DConfig v1 schema below, the 29-value family
#   vocabulary, the 7 material regions, the 7 part slots, and the canonical
#   10-label expression vocabulary. Change one of those upstream ⇒ mirror the
#   constant tables here (and vice versa); everything else is local.
#
# WHAT IT DOES
#   assemble(config) builds a stylized character rig from parameterized
#   primitive meshes (Capsule/Sphere/Cylinder/Box/Prism/Torus) — the fleet has
#   ZERO glTF assets, all existing 3D is procedural primitives, and this keeps
#   the web bundle tiny. Rig shape:
#
#     self (root Node3D)
#     └── Body (MeshInstance3D capsule)          ← materials.fur / skin
#         └── Chest (Node3D)                     ← attachment: Wings, Tail
#             └── Head (Node3D)                  ← per-family construction
#                 ├── Skull (MeshInstance3D)
#                 ├── Snout (MeshInstance3D)     ← scaled by head.snoutLength
#                 ├── Jaw (Node3D → mesh)        ← openness 0..1 = jaw pitch
#                 ├── EyeL / EyeR                ← materials.iris
#                 ├── BrowL / BrowR              ← expression posing
#                 ├── EarL / EarR / Horns        ← earStyle / hornStyle
#                 └── Hair                       ← parts.hair (anime styles)
#
#   Expressions = the canonical 10-label vocabulary (StickerExpression twin:
#   neutral happy sad angry surprised embarrassed thinking wink sleepy smug)
#   realized as pose/scale presets on eyes/brows/ears/jaw.
#   set_openness(0..1) drives the jaw (the platform's amplitude contract).
#
# PART REGISTRY INDIRECTION (GLB-ready)
#   Every attachable part goes through _build_part(slot, part_id, family) which
#   consults PART_BUILDERS. v1 entries are Callables returning primitive-mesh
#   nodes; a later GLB part library replaces entries with loaders that return
#   instantiated scenes — call sites never change.
#
# CONFIG SURFACE (Char3DConfig v1, DESIGN §2 — server-validated upstream, but
# parse_config() re-clamps everything so a hostile/hand-written JSON can never
# produce NaN transforms or unbounded values):
#   { schemaVersion:1, name, body:{archetype,build 0-1,height 0.5-1.5},
#     head:{family,variant,snoutLength 0-1,earStyle,hornStyle},
#     parts:{tail,wings,ears,feature,accessory,markings,hair},
#     materials:{fur,accent,detail,line,iris,skin,hair}, style }
# ─────────────────────────────────────────────────────────────────────────────

const SCHEMA_VERSION := 1

## head.family / body.archetype vocabulary — the 27 menagerie headArchetypes
## + 'humanoid' (kemonomimi / anime / SRD races map to humanoid + parts).
const FAMILIES: PackedStringArray = [
	"canid", "felid", "avian", "reptile", "dragon", "dino", "kobold",
	"gryphon", "equine", "cervine", "bovine", "caprine", "suid", "hyena",
	"lapine", "rodent", "macropod", "moth", "insect", "bat", "ursine",
	"mustelid", "procyonid", "amphibian", "aquatic", "slime", "robot",
	"synth", "humanoid",
]

const STYLES: PackedStringArray = ["anime", "toon", "classic"]

## Canonical 10-expression vocabulary (StickerExpression twin).
const EXPRESSIONS: PackedStringArray = [
	"neutral", "happy", "sad", "angry", "surprised",
	"embarrassed", "thinking", "wink", "sleepy", "smug",
]

## Material region names — 1:1 with the SVG palette regions (menagerie:
## fur/accent/detail/line/iris; anime adds skin/hair).
const MATERIAL_REGIONS: PackedStringArray = [
	"fur", "accent", "detail", "line", "iris", "skin", "hair",
]

const DEFAULT_MATERIALS := {
	"fur": "#8a6f4d", "accent": "#d9c7a0", "detail": "#4d3826",
	"line": "#241a12", "iris": "#3f7fbf", "skin": "#e8c39e", "hair": "#3a2f2a",
}

## Per-family construction traits. snout: default snout length bias;
## ears: default ear builder id; horns: default horn builder id ("" = none);
## beak: replace the snout with a beak cone; muzzle_flat: suid/ape-flat face.
const FAMILY_TRAITS := {
	"canid":     {"snout": 0.6, "ears": "pointed", "horns": ""},
	"felid":     {"snout": 0.35, "ears": "pointed", "horns": ""},
	"avian":     {"snout": 0.7, "ears": "", "horns": "", "beak": true},
	"reptile":   {"snout": 0.6, "ears": "", "horns": ""},
	"dragon":    {"snout": 0.65, "ears": "frill", "horns": "swept"},
	"dino":      {"snout": 0.75, "ears": "", "horns": ""},
	"kobold":    {"snout": 0.55, "ears": "frill", "horns": "stub"},
	"gryphon":   {"snout": 0.6, "ears": "tuft", "horns": "", "beak": true},
	"equine":    {"snout": 0.8, "ears": "pointed", "horns": ""},
	"cervine":   {"snout": 0.6, "ears": "round", "horns": "antler"},
	"bovine":    {"snout": 0.65, "ears": "round", "horns": "curved"},
	"caprine":   {"snout": 0.55, "ears": "droop", "horns": "curled"},
	"suid":      {"snout": 0.45, "ears": "droop", "horns": "", "muzzle_flat": true},
	"hyena":     {"snout": 0.55, "ears": "round", "horns": ""},
	"lapine":    {"snout": 0.3, "ears": "long", "horns": ""},
	"rodent":    {"snout": 0.4, "ears": "round", "horns": ""},
	"macropod":  {"snout": 0.55, "ears": "long", "horns": ""},
	"moth":      {"snout": 0.2, "ears": "feather", "horns": ""},
	"insect":    {"snout": 0.25, "ears": "feather", "horns": "stub"},
	"bat":       {"snout": 0.35, "ears": "long", "horns": ""},
	"ursine":    {"snout": 0.5, "ears": "round", "horns": ""},
	"mustelid":  {"snout": 0.45, "ears": "round", "horns": ""},
	"procyonid": {"snout": 0.4, "ears": "pointed", "horns": ""},
	"amphibian": {"snout": 0.3, "ears": "", "horns": "", "muzzle_flat": true},
	"aquatic":   {"snout": 0.5, "ears": "fin", "horns": ""},
	"slime":     {"snout": 0.1, "ears": "", "horns": ""},
	"robot":     {"snout": 0.3, "ears": "antenna", "horns": ""},
	"synth":     {"snout": 0.45, "ears": "antenna", "horns": ""},
	"humanoid":  {"snout": 0.0, "ears": "", "horns": "", "muzzle_flat": true},
}

## Expression presets: pose/scale multipliers applied to the face nodes.
##   eyes:  Y-scale of both eyes (0.1 = closed slit, 1 = open, 1.35 = wide)
##   wink:  when true the RIGHT eye closes independently
##   brows: pitch (radians; negative = raised, positive = frowning down)
##   jaw:   baseline jaw-open bias 0..1 added under set_openness
##   ears:  ear pitch (radians; positive = flattened back)
##   blush: show the embarrassed blush patches
const EXPRESSION_PRESETS := {
	"neutral":     {"eyes": 1.0, "brows": 0.0, "jaw": 0.0, "ears": 0.0},
	"happy":       {"eyes": 0.75, "brows": -0.15, "jaw": 0.25, "ears": -0.1},
	"sad":         {"eyes": 0.8, "brows": 0.35, "jaw": 0.05, "ears": 0.45},
	"angry":       {"eyes": 0.6, "brows": 0.55, "jaw": 0.15, "ears": 0.5},
	"surprised":   {"eyes": 1.35, "brows": -0.45, "jaw": 0.55, "ears": -0.25},
	"embarrassed": {"eyes": 0.7, "brows": 0.15, "jaw": 0.1, "ears": 0.35, "blush": true},
	"thinking":    {"eyes": 0.55, "brows": 0.25, "jaw": 0.0, "ears": 0.15},
	"wink":        {"eyes": 1.0, "brows": -0.1, "jaw": 0.2, "ears": 0.0, "wink": true},
	"sleepy":      {"eyes": 0.18, "brows": 0.1, "jaw": 0.08, "ears": 0.3},
	"smug":        {"eyes": 0.5, "brows": -0.25, "jaw": 0.12, "ears": -0.15},
}

const HEX_RE := "^#[0-9a-fA-F]{3}$|^#[0-9a-fA-F]{4}$|^#[0-9a-fA-F]{6}$|^#[0-9a-fA-F]{8}$"

const JAW_MAX_PITCH := 0.45   # radians — full mouth-open rotation
const BROW_BASE_Y := 0.085    # brow pivot rest height on the head
const GAZE_MAX_PITCH := 0.35  # radians — eye pivot up/down travel
const GAZE_MAX_YAW := 0.45    # radians — eye pivot left/right travel
const LID_MIN_OPEN := 0.05    # eyes never scale fully to zero (z-fighting)

## Blendshape vector contract (the Chat3D `blendshapes` channel): the RAW
## MediaPipe FaceLandmarker output — 52 categories in model-card order
## INCLUDING `_neutral` at index 0 — quantized to uint8 (0..255 ≙ 0.0..1.0).
## Indices below are 0-based positions in that order.
const BS_INDEX := {
	"browDownLeft": 1, "browDownRight": 2, "browInnerUp": 3,
	"browOuterUpLeft": 4, "browOuterUpRight": 5,
	"eyeBlinkLeft": 9, "eyeBlinkRight": 10,
	"eyeLookDownLeft": 11, "eyeLookDownRight": 12,
	"eyeLookInLeft": 13, "eyeLookInRight": 14,
	"eyeLookOutLeft": 15, "eyeLookOutRight": 16,
	"eyeLookUpLeft": 17, "eyeLookUpRight": 18,
	"jawOpen": 25,
}

# ── instance state ───────────────────────────────────────────────────────────

var config: Dictionary = {}
var _materials: Dictionary = {}          # region → StandardMaterial3D
var _jaw: Node3D = null
var _eye_l: Node3D = null
var _eye_r: Node3D = null
var _brow_l: Node3D = null
var _brow_r: Node3D = null
var _ear_l: Node3D = null
var _ear_r: Node3D = null
var _blush: Node3D = null
var _expression := "neutral"
var _openness := 0.0

# ═════════════════════════════════════════════════════════════════════════════
# Config parsing / clamping
# ═════════════════════════════════════════════════════════════════════════════

## Parse + clamp an untrusted Char3DConfig Dictionary (already JSON-decoded)
## into a fully-populated, bounded config. Never throws; unknown/invalid
## values fall back to defaults so a hostile payload degrades, not crashes.
static func parse_config(raw: Variant) -> Dictionary:
	var src: Dictionary = raw if raw is Dictionary else {}
	var body_src: Dictionary = src.get("body", {}) if src.get("body") is Dictionary else {}
	var head_src: Dictionary = src.get("head", {}) if src.get("head") is Dictionary else {}
	var parts_src: Dictionary = src.get("parts", {}) if src.get("parts") is Dictionary else {}
	var mats_src: Dictionary = src.get("materials", {}) if src.get("materials") is Dictionary else {}

	var family := _clamp_enum(str(head_src.get("family", "")), FAMILIES, "humanoid")
	var cfg := {
		"schemaVersion": SCHEMA_VERSION,
		"name": str(src.get("name", "")).substr(0, 120),
		"body": {
			"archetype": _clamp_enum(str(body_src.get("archetype", "")), FAMILIES, family),
			"build": _clamp_num(body_src.get("build"), 0.0, 1.0, 0.5),
			"height": _clamp_num(body_src.get("height"), 0.5, 1.5, 1.0),
		},
		"head": {
			"family": family,
			"variant": str(head_src.get("variant", "")).substr(0, 40),
			"snoutLength": _clamp_num(head_src.get("snoutLength"),
					0.0, 1.0, float(FAMILY_TRAITS[family].get("snout", 0.4))),
			"earStyle": _clamp_opt_str(head_src.get("earStyle"), 40),
			"hornStyle": _clamp_opt_str(head_src.get("hornStyle"), 40),
		},
		"parts": {},
		"materials": {},
		"style": _clamp_enum(str(src.get("style", "")), STYLES, "toon"),
	}
	for slot in ["tail", "wings", "ears", "feature", "accessory", "markings", "hair"]:
		cfg["parts"][slot] = _clamp_opt_str(parts_src.get(slot), 64)
	var re := RegEx.new()
	re.compile(HEX_RE)
	for region in MATERIAL_REGIONS:
		var v := str(mats_src.get(region, ""))
		cfg["materials"][region] = v if re.search(v) != null else DEFAULT_MATERIALS[region]
	return cfg


static func _clamp_num(v: Variant, lo: float, hi: float, fallback: float) -> float:
	if v is float or v is int:
		var f := float(v)
		if is_finite(f):
			return clampf(f, lo, hi)
	return fallback


static func _clamp_enum(v: String, allowed: PackedStringArray, fallback: String) -> String:
	return v if allowed.has(v) else fallback


static func _clamp_opt_str(v: Variant, max_len: int) -> Variant:
	if v == null:
		return null
	var s := str(v).substr(0, max_len)
	return s if s != "" else null


## Deterministic random config — same seed ⇒ same character on every client.
## Used for session-mode avatars (seeded by characterId) and guest fallback.
static func random_config(seed_value: int) -> Dictionary:
	var rng := RandomNumberGenerator.new()
	rng.seed = seed_value
	var family: String = FAMILIES[rng.randi_range(0, FAMILIES.size() - 1)]
	var mats := {}
	for region in MATERIAL_REGIONS:
		mats[region] = "#%02x%02x%02x" % [
			rng.randi_range(40, 220), rng.randi_range(40, 220), rng.randi_range(40, 220)]
	return parse_config({
		"schemaVersion": SCHEMA_VERSION,
		"name": "guest-%d" % (seed_value & 0xffff),
		"body": {"archetype": family, "build": rng.randf(),
				"height": rng.randf_range(0.8, 1.2)},
		"head": {"family": family, "variant": "",
				"snoutLength": rng.randf(),
				"earStyle": null, "hornStyle": null},
		"parts": {"tail": "plain" if rng.randf() < 0.7 else null,
				"wings": "plain" if rng.randf() < 0.2 else null,
				"ears": null, "feature": null, "accessory": null,
				"markings": null, "hair": "short" if family == "humanoid" else null},
		"materials": mats,
		"style": STYLES[rng.randi_range(0, STYLES.size() - 1)],
	})

# ═════════════════════════════════════════════════════════════════════════════
# Assembly
# ═════════════════════════════════════════════════════════════════════════════

## Build (or rebuild) the rig from a raw config Dictionary. Existing children
## are freed first so assemble() is safely re-entrant on config change.
func assemble(raw_config: Variant) -> void:
	config = parse_config(raw_config)
	for child in get_children():
		child.queue_free()
	_materials.clear()
	for region in MATERIAL_REGIONS:
		_materials[region] = _make_material(config["materials"][region])

	var height: float = config["body"]["height"]
	var build: float = config["body"]["build"]
	var family: String = config["head"]["family"]
	var traits: Dictionary = FAMILY_TRAITS[family]

	# Body: capsule torso scaled by height/build. humanoid/anime uses skin
	# for the head but keeps fur (= outfit tone) for the torso.
	var body := MeshInstance3D.new()
	body.name = "Body"
	var body_mesh := CapsuleMesh.new()
	body_mesh.radius = lerpf(0.20, 0.34, build)
	body_mesh.height = 0.95 * height
	body.mesh = body_mesh
	body.material_override = _materials["fur"]
	body.position = Vector3(0, 0.55 * height, 0)
	add_child(body)

	var chest := Node3D.new()
	chest.name = "Chest"
	chest.position = Vector3(0, 0.30 * height, 0)
	body.add_child(chest)

	var head := Node3D.new()
	head.name = "Head"
	head.position = Vector3(0, 0.28 * height, 0)
	chest.add_child(head)

	_build_head(head, family, traits)
	_build_attachments(chest, family)
	set_expression(_expression)
	set_openness(_openness)


func _build_head(head: Node3D, family: String, traits: Dictionary) -> void:
	var head_scale := 0.18
	var skin_like := family == "humanoid" or family == "slime"
	var head_mat: StandardMaterial3D = _materials["skin"] if skin_like else _materials["fur"]

	var skull := MeshInstance3D.new()
	skull.name = "Skull"
	var skull_mesh := SphereMesh.new()
	skull_mesh.radius = head_scale
	skull_mesh.height = head_scale * 2.0
	skull.mesh = skull_mesh
	skull.material_override = head_mat
	head.add_child(skull)

	# Snout / beak / flat muzzle — scaled by snoutLength.
	var snout_len: float = config["head"]["snoutLength"]
	if traits.get("beak", false):
		var beak := MeshInstance3D.new()
		beak.name = "Snout"
		var beak_mesh := CylinderMesh.new()
		beak_mesh.top_radius = 0.0
		beak_mesh.bottom_radius = 0.05
		beak_mesh.height = 0.10 + 0.14 * snout_len
		beak.mesh = beak_mesh
		beak.material_override = _materials["accent"]
		beak.position = Vector3(0, -0.02, -head_scale - beak_mesh.height * 0.35)
		beak.rotation.x = -PI / 2.0
		head.add_child(beak)
	elif not traits.get("muzzle_flat", false):
		var snout := MeshInstance3D.new()
		snout.name = "Snout"
		var snout_mesh := CapsuleMesh.new()
		snout_mesh.radius = 0.07
		snout_mesh.height = 0.16 + 0.18 * snout_len
		snout.mesh = snout_mesh
		snout.material_override = head_mat
		snout.position = Vector3(0, -0.04, -head_scale - 0.02 - 0.08 * snout_len)
		snout.rotation.x = PI / 2.0
		head.add_child(snout)

	# Jaw pivot: a lower-muzzle wedge that pitches open with openness.
	_jaw = Node3D.new()
	_jaw.name = "Jaw"
	_jaw.position = Vector3(0, -0.08, -head_scale * 0.3)
	head.add_child(_jaw)
	var jaw_mesh_i := MeshInstance3D.new()
	jaw_mesh_i.name = "JawMesh"
	var jaw_mesh := BoxMesh.new()
	var jaw_depth := 0.10 + 0.10 * snout_len if not traits.get("muzzle_flat", false) else 0.09
	jaw_mesh.size = Vector3(0.10, 0.035, jaw_depth)
	jaw_mesh_i.mesh = jaw_mesh
	jaw_mesh_i.material_override = _materials["detail"]
	jaw_mesh_i.position = Vector3(0, -0.02, -jaw_depth * 0.5)
	_jaw.add_child(jaw_mesh_i)

	# Eyes + brows (iris material; brows use line).
	_eye_l = _build_eye(head, -0.075, head_scale)
	_eye_r = _build_eye(head, 0.075, head_scale)
	_brow_l = _build_brow(head, -0.075, head_scale)
	_brow_r = _build_brow(head, 0.075, head_scale)

	# Blush patches (embarrassed preset only).
	_blush = Node3D.new()
	_blush.name = "Blush"
	_blush.visible = false
	head.add_child(_blush)
	for side in [-1.0, 1.0]:
		var patch := MeshInstance3D.new()
		var patch_mesh := SphereMesh.new()
		patch_mesh.radius = 0.035
		patch_mesh.height = 0.03
		patch.mesh = patch_mesh
		var blush_mat := StandardMaterial3D.new()
		blush_mat.albedo_color = Color(0.95, 0.45, 0.5)
		patch.material_override = blush_mat
		patch.position = Vector3(side * 0.11, -0.05, -head_scale * 0.85)
		_blush.add_child(patch)

	# Ears — explicit earStyle wins, else parts.ears id, else family default.
	var ear_style: String = str(config["head"]["earStyle"]) \
			if config["head"]["earStyle"] != null else ""
	if ear_style == "" and config["parts"]["ears"] != null:
		ear_style = str(config["parts"]["ears"])
	if ear_style == "":
		ear_style = str(traits.get("ears", ""))
	if ear_style != "":
		_ear_l = _build_part_at(head, "ears", ear_style, Vector3(-0.11, head_scale * 0.8, 0))
		_ear_r = _build_part_at(head, "ears", ear_style, Vector3(0.11, head_scale * 0.8, 0))

	# Horns — explicit hornStyle wins, else family default.
	var horn_style: String = str(config["head"]["hornStyle"]) \
			if config["head"]["hornStyle"] != null else str(traits.get("horns", ""))
	if horn_style != "":
		_build_part_at(head, "horns", horn_style, Vector3(-0.07, head_scale * 0.95, 0.02))
		_build_part_at(head, "horns", horn_style, Vector3(0.07, head_scale * 0.95, 0.02))

	# Hair (humanoid/anime mostly, but any family may carry it).
	if config["parts"]["hair"] != null:
		_build_part_at(head, "hair", str(config["parts"]["hair"]),
				Vector3(0, head_scale * 0.55, 0.02))


func _build_eye(head: Node3D, x: float, head_scale: float) -> Node3D:
	var pivot := Node3D.new()
	pivot.name = "EyeL" if x < 0 else "EyeR"
	pivot.position = Vector3(x, 0.03, -head_scale * 0.92)
	head.add_child(pivot)
	var white := MeshInstance3D.new()
	var white_mesh := SphereMesh.new()
	white_mesh.radius = 0.035
	white_mesh.height = 0.07
	white.mesh = white_mesh
	var white_mat := StandardMaterial3D.new()
	white_mat.albedo_color = Color(0.95, 0.95, 0.98)
	white.material_override = white_mat
	pivot.add_child(white)
	var iris := MeshInstance3D.new()
	var iris_mesh := SphereMesh.new()
	iris_mesh.radius = 0.018
	iris_mesh.height = 0.036
	iris.mesh = iris_mesh
	iris.material_override = _materials["iris"]
	iris.position = Vector3(0, 0, -0.024)
	pivot.add_child(iris)
	return pivot


func _build_brow(head: Node3D, x: float, head_scale: float) -> Node3D:
	var pivot := Node3D.new()
	pivot.name = "BrowL" if x < 0 else "BrowR"
	pivot.position = Vector3(x, BROW_BASE_Y, -head_scale * 0.9)
	head.add_child(pivot)
	var brow := MeshInstance3D.new()
	var brow_mesh := BoxMesh.new()
	brow_mesh.size = Vector3(0.06, 0.012, 0.015)
	brow.mesh = brow_mesh
	brow.material_override = _materials["line"]
	pivot.add_child(brow)
	return pivot


func _build_attachments(chest: Node3D, family: String) -> void:
	# Tail / Wings / Feature / Accessory hang off named attachment points so a
	# GLB part library can later re-target the same anchors.
	var tail_anchor := Node3D.new()
	tail_anchor.name = "TailAnchor"
	tail_anchor.position = Vector3(0, -0.45, 0.16)
	chest.add_child(tail_anchor)
	if config["parts"]["tail"] != null:
		_build_part_at(tail_anchor, "tail", str(config["parts"]["tail"]), Vector3.ZERO)

	var wing_anchor := Node3D.new()
	wing_anchor.name = "WingAnchor"
	wing_anchor.position = Vector3(0, 0.05, 0.14)
	chest.add_child(wing_anchor)
	if config["parts"]["wings"] != null:
		_build_part_at(wing_anchor, "wings", str(config["parts"]["wings"]),
				Vector3(-0.12, 0, 0))
		_build_part_at(wing_anchor, "wings", str(config["parts"]["wings"]),
				Vector3(0.12, 0, 0))

	var feature_anchor := Node3D.new()
	feature_anchor.name = "FeatureAnchor"
	feature_anchor.position = Vector3(0, 0.1, -0.15)
	chest.add_child(feature_anchor)
	if config["parts"]["feature"] != null:
		_build_part_at(feature_anchor, "feature", str(config["parts"]["feature"]), Vector3.ZERO)
	if config["parts"]["accessory"] != null:
		_build_part_at(feature_anchor, "accessory", str(config["parts"]["accessory"]),
				Vector3(0, -0.05, 0))

	# Markings: an accent-tinted torso band (v1 decal stand-in).
	if config["parts"]["markings"] != null:
		var band := MeshInstance3D.new()
		band.name = "Markings"
		var band_mesh := TorusMesh.new()
		band_mesh.inner_radius = lerpf(0.20, 0.34, float(config["body"]["build"])) + 0.005
		band_mesh.outer_radius = band_mesh.inner_radius + 0.03
		band.mesh = band_mesh
		band.material_override = _materials["accent"]
		band.position = Vector3(0, 0.1, 0)
		chest.add_child(band)

# ═════════════════════════════════════════════════════════════════════════════
# Part registry (primitive builders — GLB library replaces these later)
# ═════════════════════════════════════════════════════════════════════════════

## slot → { part-id → Callable(assembler) -> Node3D }. "plain" is every slot's
## default; unknown ids fall back to it so any partId renders SOMETHING.
static func _part_builders() -> Dictionary:
	return {
		"ears": {
			"pointed": func(a): return a._prim_cone(0.035, 0.11, "fur"),
			"round":   func(a): return a._prim_sphere(0.05, "fur"),
			"long":    func(a): return a._prim_cone(0.03, 0.22, "fur"),
			"droop":   func(a): return a._prim_capsule(0.03, 0.14, "fur", Vector3(0.3, 0, 0.5)),
			"tuft":    func(a): return a._prim_cone(0.04, 0.13, "accent"),
			"frill":   func(a): return a._prim_cone(0.05, 0.08, "accent"),
			"fin":     func(a): return a._prim_prism(0.08, 0.1, "accent"),
			"feather": func(a): return a._prim_capsule(0.015, 0.16, "accent", Vector3(-0.4, 0, 0)),
			"antenna": func(a): return a._prim_cylinder(0.008, 0.14, "detail"),
			"plain":   func(a): return a._prim_cone(0.035, 0.1, "fur"),
		},
		"horns": {
			"swept":  func(a): return a._prim_cone(0.025, 0.14, "detail", Vector3(-0.6, 0, 0)),
			"curved": func(a): return a._prim_cone(0.03, 0.12, "detail", Vector3(0, 0, 0.7)),
			"curled": func(a): return a._prim_torus(0.03, 0.05, "detail"),
			"antler": func(a): return a._prim_prism(0.1, 0.16, "detail"),
			"stub":   func(a): return a._prim_cone(0.02, 0.05, "detail"),
			"plain":  func(a): return a._prim_cone(0.025, 0.1, "detail"),
		},
		"tail": {
			"plain": func(a): return a._prim_capsule(0.05, 0.35, "fur", Vector3(0.9, 0, 0)),
			"fluff": func(a): return a._prim_capsule(0.08, 0.4, "accent", Vector3(0.9, 0, 0)),
			"whip":  func(a): return a._prim_cone(0.03, 0.45, "fur", Vector3(1.2, 0, 0)),
			"stub":  func(a): return a._prim_sphere(0.06, "fur"),
		},
		"wings": {
			"plain":   func(a): return a._prim_prism(0.28, 0.2, "accent"),
			"feather": func(a): return a._prim_prism(0.34, 0.22, "accent"),
			"bat":     func(a): return a._prim_prism(0.3, 0.24, "detail"),
			"fairy":   func(a): return a._prim_prism(0.22, 0.26, "iris"),
		},
		"feature": {
			"plain": func(a): return a._prim_sphere(0.05, "accent"),
			"visor": func(a): return a._prim_box(Vector3(0.2, 0.05, 0.02), "iris"),
		},
		"accessory": {
			"plain": func(a): return a._prim_torus(0.02, 0.09, "accent"),
			"scarf": func(a): return a._prim_torus(0.03, 0.1, "accent"),
		},
		"hair": {
			"short":  func(a): return a._prim_sphere(0.14, "hair"),
			"long":   func(a): return a._prim_capsule(0.12, 0.3, "hair", Vector3(0.2, 0, 0)),
			"spiky":  func(a): return a._prim_cone(0.1, 0.16, "hair"),
			"plain":  func(a): return a._prim_sphere(0.13, "hair"),
		},
	}


func _build_part_at(parent: Node3D, slot: String, part_id: String, pos: Vector3) -> Node3D:
	var node := _build_part(slot, part_id)
	if node == null:
		return null
	node.position = pos
	parent.add_child(node)
	return node


## The indirection point: consult the registry, fall back partId → "plain".
## A GLB part library later swaps the Callable for a scene loader; call sites
## and anchor names stay identical.
func _build_part(slot: String, part_id: String) -> Node3D:
	var builders: Dictionary = _part_builders()
	if not builders.has(slot):
		return null
	var slot_builders: Dictionary = builders[slot]
	var builder: Callable = slot_builders.get(part_id, slot_builders.get("plain"))
	if builder == null:
		return null
	return builder.call(self)


# ── primitive helpers (all return a fresh MeshInstance3D wrapper) ────────────

func _prim_wrap(mesh: Mesh, region: String, rot: Vector3 = Vector3.ZERO) -> Node3D:
	var pivot := Node3D.new()
	var mi := MeshInstance3D.new()
	mi.mesh = mesh
	mi.material_override = _materials.get(region, _materials["fur"])
	pivot.rotation = rot
	pivot.add_child(mi)
	return pivot


func _prim_cone(radius: float, height: float, region: String, rot: Vector3 = Vector3.ZERO) -> Node3D:
	var m := CylinderMesh.new()
	m.top_radius = 0.0
	m.bottom_radius = radius
	m.height = height
	var n := _prim_wrap(m, region, rot)
	(n.get_child(0) as Node3D).position.y = height * 0.5
	return n


func _prim_cylinder(radius: float, height: float, region: String) -> Node3D:
	var m := CylinderMesh.new()
	m.top_radius = radius
	m.bottom_radius = radius
	m.height = height
	var n := _prim_wrap(m, region)
	(n.get_child(0) as Node3D).position.y = height * 0.5
	return n


func _prim_sphere(radius: float, region: String) -> Node3D:
	var m := SphereMesh.new()
	m.radius = radius
	m.height = radius * 2.0
	return _prim_wrap(m, region)


func _prim_capsule(radius: float, height: float, region: String, rot: Vector3 = Vector3.ZERO) -> Node3D:
	var m := CapsuleMesh.new()
	m.radius = radius
	m.height = height
	return _prim_wrap(m, region, rot)


func _prim_box(size: Vector3, region: String) -> Node3D:
	var m := BoxMesh.new()
	m.size = size
	return _prim_wrap(m, region)


func _prim_prism(width: float, height: float, region: String) -> Node3D:
	var m := PrismMesh.new()
	m.size = Vector3(width, height, 0.02)
	return _prim_wrap(m, region)


func _prim_torus(inner: float, outer: float, region: String) -> Node3D:
	var m := TorusMesh.new()
	m.inner_radius = inner
	m.outer_radius = outer
	return _prim_wrap(m, region)


## Toon-ish look: low roughness + rim, per DESIGN §2. One material per region.
func _make_material(hex: String) -> StandardMaterial3D:
	var mat := StandardMaterial3D.new()
	mat.albedo_color = Color.from_string(hex, Color(0.5, 0.5, 0.5))
	mat.roughness = 0.35
	mat.metallic = 0.0
	mat.rim_enabled = true
	mat.rim = 0.4
	mat.rim_tint = 0.6
	return mat

# ═════════════════════════════════════════════════════════════════════════════
# Runtime drives: expression / openness
# ═════════════════════════════════════════════════════════════════════════════

## Apply one of the 10 canonical expression presets (pose/scale, no tweening —
## callers may tween the whole call if they want smooth transitions).
func set_expression(label: String) -> void:
	if not EXPRESSIONS.has(label):
		label = "neutral"
	_expression = label
	var preset: Dictionary = EXPRESSION_PRESETS[label]
	var eye_scale: float = preset.get("eyes", 1.0)
	if _eye_l != null:
		_eye_l.scale = Vector3(1, eye_scale, 1)
	if _eye_r != null:
		_eye_r.scale = Vector3(1, 0.12 if preset.get("wink", false) else eye_scale, 1)
	var brow_pitch: float = preset.get("brows", 0.0)
	if _brow_l != null:
		_brow_l.rotation = Vector3(0, 0, -brow_pitch)
	if _brow_r != null:
		_brow_r.rotation = Vector3(0, 0, brow_pitch)
	var ear_pitch: float = preset.get("ears", 0.0)
	if _ear_l != null:
		_ear_l.rotation.x = ear_pitch
	if _ear_r != null:
		_ear_r.rotation.x = ear_pitch
	if _blush != null:
		_blush.visible = preset.get("blush", false)
	set_openness(_openness)


func get_expression() -> String:
	return _expression


## Mouth openness 0..1 (the platform amplitude contract) → jaw pitch. The
## active expression contributes a baseline bias so e.g. 'surprised' keeps the
## mouth ajar at zero amplitude.
func set_openness(value: float) -> void:
	_openness = clampf(value, 0.0, 1.0)
	if _jaw == null:
		return
	var bias: float = EXPRESSION_PRESETS[_expression].get("jaw", 0.0)
	var open := clampf(bias * 0.35 + _openness, 0.0, 1.0)
	_jaw.rotation.x = open * JAW_MAX_PITCH


func get_openness() -> float:
	return _openness


## Drive the head rig from a live face-tracking vector (see BS_INDEX for the
## wire contract). DESKTOP-FIRST requirement (XR-chat DESIGN §6): the 52-float
## vector drives EYES as well as mouth — per-eye blink closes the lids (eye
## pivot Y-scale), eyeLook* rotates both eye pivots (conjugate gaze), brow*
## raises/lowers the brows, jawOpen drives the jaw via the shared openness
## path. The active expression's eye scale stays the blink baseline, so a
## 'happy' avatar blinks from its narrowed eyes, not from fully-open ones.
func set_blendshapes(weights: PackedByteArray) -> void:
	if weights.size() != 52:
		return
	var preset: Dictionary = EXPRESSION_PRESETS[_expression]
	var base_eyes: float = preset.get("eyes", 1.0)

	# Lids — per-eye blink scaled against the expression's eye openness.
	var open_l := maxf(base_eyes * (1.0 - _bs(weights, "eyeBlinkLeft")), LID_MIN_OPEN)
	var open_r := maxf(base_eyes * (1.0 - _bs(weights, "eyeBlinkRight")), LID_MIN_OPEN)

	# Conjugate gaze. The rig faces -Z; positive pivot rotation.x tilts the
	# gaze up, positive rotation.y toward the character's own left (-X).
	var pitch := ((_bs(weights, "eyeLookUpLeft") + _bs(weights, "eyeLookUpRight"))
			- (_bs(weights, "eyeLookDownLeft") + _bs(weights, "eyeLookDownRight"))) * 0.5
	var toward_left := ((_bs(weights, "eyeLookOutLeft") + _bs(weights, "eyeLookInRight"))
			- (_bs(weights, "eyeLookInLeft") + _bs(weights, "eyeLookOutRight"))) * 0.5
	for eye in [_eye_l, _eye_r]:
		if eye == null:
			continue
		eye.rotation = Vector3(pitch * GAZE_MAX_PITCH, toward_left * GAZE_MAX_YAW, 0.0)
	if _eye_l != null:
		_eye_l.scale = Vector3(1, open_l, 1)
	if _eye_r != null:
		_eye_r.scale = Vector3(1, open_r, 1)

	# Brows — vertical offset from the built position (rotation.z stays owned
	# by the expression preset, so the two drives compose instead of fighting).
	var raise := maxf(_bs(weights, "browInnerUp"),
			(_bs(weights, "browOuterUpLeft") + _bs(weights, "browOuterUpRight")) * 0.5)
	if _brow_l != null:
		_brow_l.position.y = BROW_BASE_Y + 0.014 * raise - 0.010 * _bs(weights, "browDownLeft")
	if _brow_r != null:
		_brow_r.position.y = BROW_BASE_Y + 0.014 * raise - 0.010 * _bs(weights, "browDownRight")

	# Jaw — through the shared openness path (one jaw authority).
	set_openness(_bs(weights, "jawOpen"))


static func _bs(weights: PackedByteArray, key: String) -> float:
	return float(weights[BS_INDEX[key]]) / 255.0
