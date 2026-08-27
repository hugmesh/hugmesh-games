class_name Char3DConfig
extends RefCounted

# Char3DConfig v1 — parse + clamp for the SVG->3D mapping surface (DESIGN §2).
#
# This is the CLIENT-SIDE mirror of the character-forge BFF's server-side
# validation (§3): the server REJECTS invalid definitions; this mirror CLAMPS
# them so the renderer never crashes on hostile/stale input (matrix-ui
# fail-soft parse precedent). Keep the enums and bounds in lockstep with
# net.hugmesh.characterforgebff's validator:
#   * unknown keys dropped
#   * numerics clamped to their documented ranges
#   * hex colours must match ^#[0-9a-fA-F]{3,8}$ (SVG studio SAFE_HEX twin)
#   * families/styles/expressions enum-checked
#   * definition JSON capped at 16 KB
#
# Mapping keys ride the STABLE VN parameterization (packId, partId,
# headArchetype, palette regions) — never SVG geometry.

const SCHEMA_VERSION := 1
const MAX_DEFINITION_BYTES := 16384
const MAX_NAME_LEN := 120
const MAX_VARIANT_LEN := 40
const MAX_PART_ID_LEN := 64
const MAX_SOURCE_SLOTS := 24
const MAX_SOURCE_REGIONS := 16

# The 29 head families: the 27+1 menagerie headArchetypes + humanoid (DESIGN §2).
const FAMILIES := [
	"canid", "felid", "avian", "reptile", "dragon", "dino", "kobold",
	"gryphon", "equine", "cervine", "bovine", "caprine", "suid", "hyena",
	"lapine", "rodent", "macropod", "moth", "insect", "bat", "ursine",
	"mustelid", "procyonid", "amphibian", "aquatic", "slime", "robot",
	"synth", "humanoid",
]

const STYLES := ["anime", "toon", "classic"]

# The canonical 10-expression vocabulary (StickerExpression twin).
const EXPRESSIONS := [
	"neutral", "happy", "sad", "angry", "surprised",
	"embarrassed", "thinking", "wink", "sleepy", "smug",
]

# Material regions — 1:1 with the SVG palette region names
# (menagerie: fur/accent/detail/line/iris; anime adds skin/hair).
const MATERIAL_REGIONS := ["fur", "accent", "detail", "line", "iris", "skin", "hair"]

# EXTENDED coat-block regions (kemono.md: "base coat, lighter inner-muzzle +
# chest/belly, socks/gloves, inner-ear, tail tip, hair tuft, iris" = 7-8 masks).
# These are OPTIONAL in a definition and are NOT part of defaults(): when a
# config carries no explicit hex for one, CharForgePalette DERIVES it from the
# canonical seven (innerMuzzle<-fur lightened, socks<-accent, innerEar<-skin,
# tailTip<-accent), which reproduces the pre-extension appearance exactly.
# Supplied values are clamp-validated by the same hex regex as the canonical
# regions, so a hostile/stale definition can never inject a bad colour.
const EXTENDED_MATERIAL_REGIONS := ["innerMuzzle", "socks", "innerEar", "tailTip"]

# Every region the palette strip has a cell for (canonical + extended).
const ALL_MATERIAL_REGIONS := [
	"fur", "accent", "detail", "line", "iris", "skin", "hair",
	"innerMuzzle", "socks", "innerEar", "tailTip",
]

# Attachment-assembly slots (partId passthrough from the VN slots).
const PART_SLOTS := ["tail", "wings", "ears", "feature", "accessory", "markings", "hair"]

const SOURCE_PACKS := ["menagerie-v1", "anime-v1", "base-anthro-v1", "srd-taxonomy-v1"]

## Rig joints a user may nudge. Each is an assembler `_refs` key that exists on the
## procedural head; a mesh-basis head that lacks one simply ignores that entry.
const RIG_JOINTS := ["head", "jaw", "eye_l", "eye_r", "brow_l", "brow_r", "ear_l", "ear_r"]
## Offset clamp, metres in the joint's parent space (a head is ~0.5 m tall).
const RIG_OFFSET_MAX := 0.12
const RIG_SCALE_MIN := 0.5
const RIG_SCALE_MAX := 1.8

# The ONE canonical unset-region palette (Char3DConfigValidator.DEFAULT_MATERIALS
# / char3d-config.ts DEFAULT_MATERIALS / this copy — all three pinned equal by the
# shared fixture tests/fixtures/server-contract.fixtures.json (vendored from hugmesh-character-forge-bff-api frontend/src/app/character-forge/contract/)).
# A stored definition may carry `null` for a region it never set; the renderer
# resolves that here, so the three copies MUST agree or the same character shows
# different colours in the Angular editor, the in-engine maker and the XR room.
const DEFAULT_MATERIALS := {
	"fur": "#8b5a2b",
	"accent": "#d2b48c",
	"detail": "#4a3728",
	"line": "#2f2f2f",
	"iris": "#00bcd4",
	"skin": "#ffdbac",
	"hair": "#563d2d",
}

# The server's bounded-value patterns (Char3DConfigValidator). The mirror must
# never CLAMP a value into a shape the BFF then REJECTS with 400 — studio.gd
# emits JSON.stringify(assembler.config) straight to the host, which POSTs it to
# /bff/character-forge/characters verbatim.
const SHORT_STR_PATTERN := "^[A-Za-z0-9 _-]{1,40}$"   # variant / earStyle / hornStyle
const PART_ID_PATTERN := "^[A-Za-z0-9_-]{1,64}$"      # parts.* / source slot values
const KEY_PATTERN := "^[a-z0-9_-]{1,40}$"             # source slot / palette KEY names

static var _hex_re: RegEx = null
static var _short_str_re: RegEx = null
static var _part_id_re: RegEx = null
static var _key_re: RegEx = null


static func _compile(pattern: String) -> RegEx:
	var re := RegEx.new()
	re.compile(pattern)
	return re


## FULL match (java.util.regex Matcher#matches semantics). RegEx.search() finds a
## SUBSTRING, and PCRE2's `$` also matches before a trailing newline — so a bare
## search() would call "ok\n" valid where the server's matches() rejects it. The
## mirror must never be more permissive than the validator.
static func _full_match(re: RegEx, value: String) -> bool:
	var m := re.search(value)
	return m != null and m.get_start(0) == 0 and m.get_end(0) == value.length()


## True when value matches the server's bounded short-descriptor pattern.
static func valid_short_str(value: String) -> bool:
	if _short_str_re == null:
		_short_str_re = _compile(SHORT_STR_PATTERN)
	return _full_match(_short_str_re, value)


## True when value matches the server's bounded part/asset id pattern.
static func valid_part_id(value: String) -> bool:
	if _part_id_re == null:
		_part_id_re = _compile(PART_ID_PATTERN)
	return _full_match(_part_id_re, value)


## True when value matches the server's lower-slug source key pattern.
static func valid_key(value: String) -> bool:
	if _key_re == null:
		_key_re = _compile(KEY_PATTERN)
	return _full_match(_key_re, value)


## A complete, valid default config (a plain brown toon canid).
static func defaults() -> Dictionary:
	var parts := {}
	for slot in PART_SLOTS:
		parts[slot] = null
	return {
		"schemaVersion": SCHEMA_VERSION,
		"name": "Untitled",
		# Every shape axis starts at its centred stop — the default body is
		# deliberately androgynous, and is a set of PROPORTIONS rather than a preset.
		"body": {
			"archetype": "canid", "build": 0.5, "height": 1.0,
			"curve": 0.5, "top": 0.5, "legs": 0.5, "arms": 0.5, "limb": 0.5,
		},
		"head": {
			"family": "canid",
			"variant": "",
			"snoutLength": 0.5,
			"earStyle": null,
			"hornStyle": null,
		},
		"parts": parts,
		"materials": DEFAULT_MATERIALS.duplicate(),
		"style": "toon",
	}


## Parses a JSON definition string. Returns null on HARD failure (oversize,
## bad JSON, non-object root) so the caller can decide; any parseable object
## is clamped into a fully valid config via sanitize().
static func try_parse(text: String) -> Variant:
	if text.to_utf8_buffer().size() > MAX_DEFINITION_BYTES:
		push_warning("Char3DConfig: definition exceeds %d bytes — rejected" % MAX_DEFINITION_BYTES)
		return null
	var parsed: Variant = JSON.parse_string(text)
	if not (parsed is Dictionary):
		return null
	return sanitize(parsed)


## Fail-soft variant: any invalid input degrades to defaults().
static func parse_or_default(text: String) -> Dictionary:
	var cfg: Variant = try_parse(text)
	if cfg is Dictionary:
		return cfg
	return defaults()


## Clamps an arbitrary Dictionary into a valid Char3DConfig v1. Unknown keys
## are dropped; every documented field is coerced into range.
static func sanitize(raw: Dictionary) -> Dictionary:
	var out := defaults()

	out["name"] = _display_name(raw.get("name"))

	var body_raw: Variant = raw.get("body")
	if body_raw is Dictionary:
		var arch := _clean_str(body_raw.get("archetype"), MAX_VARIANT_LEN, "")
		if arch in FAMILIES:
			out.body["archetype"] = arch
		out.body["build"] = _clamp_num(body_raw.get("build"), 0.0, 1.0, 0.5)
		out.body["height"] = _clamp_num(body_raw.get("height"), 0.5, 1.5, 1.0)
		# Shape axes, orthogonal to `build` (overall mass): `curve` is the
		# hip-to-waist ratio, `top` is chest volume. sanitize() starts from
		# defaults() and overwrites key by key, so an axis missing from BOTH
		# places is dropped on the floor — the engine would then render the
		# centred silhouette while the editor's slider says otherwise.
		out.body["curve"] = _clamp_num(body_raw.get("curve"), 0.0, 1.0, 0.5)
		out.body["top"] = _clamp_num(body_raw.get("top"), 0.0, 1.0, 0.5)
		# PROPORTION axes: `legs`/`arms` are limb LENGTH, `limb` is limb GIRTH.
		# Same centring contract as curve/top — 0.5 rebuilds today's silhouette
		# exactly, so a stored definition that predates them is unchanged.
		out.body["legs"] = _clamp_num(body_raw.get("legs"), 0.0, 1.0, 0.5)
		out.body["arms"] = _clamp_num(body_raw.get("arms"), 0.0, 1.0, 0.5)
		out.body["limb"] = _clamp_num(body_raw.get("limb"), 0.0, 1.0, 0.5)

	var head_raw: Variant = raw.get("head")
	if head_raw is Dictionary:
		var fam := _clean_str(head_raw.get("family"), MAX_VARIANT_LEN, "")
		if fam in FAMILIES:
			out.head["family"] = fam
		out.head["variant"] = _opt_short_str(head_raw.get("variant"))
		if out.head["variant"] == null:
			out.head["variant"] = ""       # "" is the mirror's UNSET encoding (server: absent)
		out.head["snoutLength"] = _clamp_num(head_raw.get("snoutLength"), 0.0, 1.0, 0.5)
		out.head["earStyle"] = _opt_short_str(head_raw.get("earStyle"))
		out.head["hornStyle"] = _opt_short_str(head_raw.get("hornStyle"))

	# Keep body.archetype coherent when only head.family was supplied.
	if not (body_raw is Dictionary) and head_raw is Dictionary:
		out.body["archetype"] = out.head["family"]

	var parts_raw: Variant = raw.get("parts")
	if parts_raw is Dictionary:
		for slot in PART_SLOTS:
			if parts_raw.has(slot):
				out.parts[slot] = _opt_part_id(parts_raw.get(slot))

	var mats_raw: Variant = raw.get("materials")
	if mats_raw is Dictionary:
		for region in MATERIAL_REGIONS:
			var v: Variant = mats_raw.get(region)
			if v is String and valid_hex(v):
				out.materials[region] = v
		# Extended kemono coat blocks: only ever ADDED when explicitly present
		# and valid — an absent one stays absent so CharForgePalette derives it.
		for region in EXTENDED_MATERIAL_REGIONS:
			var ev: Variant = mats_raw.get(region)
			if ev is String and valid_hex(ev):
				out.materials[region] = ev

	var style := _clean_str(raw.get("style"), 12, "")
	if style in STYLES:
		out["style"] = style

	var rig: Dictionary = sanitize_rig(raw.get("rig"))
	if not rig.is_empty():
		out["rig"] = rig
	var source: Variant = _sanitize_source(raw.get("source"))
	if source is Dictionary:
		out["source"] = source

	return out


## Godot Color for a region hex, tolerating the 3..8-digit range the regex
## admits (Color.from_string never errors — falls back on odd lengths).
## The per-joint rig adjustments, SPARSE: only joints that carry an offset or a
## scale survive, every number clamped, everything else dropped. `{}` means
## "no adjustments" and is NOT emitted into the canonical config.
static func sanitize_rig(raw: Variant) -> Dictionary:
	var out := {}
	if not (raw is Dictionary):
		return out
	for joint in RIG_JOINTS:
		var entry: Variant = raw.get(joint)
		if not (entry is Dictionary):
			continue
		var clean := {}
		var off: Variant = entry.get("offset")
		if off is Array and off.size() == 3:
			var o := []
			for i in 3:
				o.append(_clamp_num(off[i], -RIG_OFFSET_MAX, RIG_OFFSET_MAX, 0.0))
			if o != [0.0, 0.0, 0.0]:
				clean["offset"] = o
		var scl: Variant = entry.get("scale")
		if scl is Array and scl.size() == 3:
			var sc := []
			for i in 3:
				sc.append(_clamp_num(scl[i], RIG_SCALE_MIN, RIG_SCALE_MAX, 1.0))
			if sc != [1.0, 1.0, 1.0]:
				clean["scale"] = sc
		if not clean.is_empty():
			out[joint] = clean
	return out


static func region_color(config: Dictionary, region: String) -> Color:
	var mats: Variant = config.get("materials", {})
	var hex_value: String = DEFAULT_MATERIALS.get(region, "#ffffff")
	if mats is Dictionary and mats.get(region) is String:
		hex_value = mats[region]
	return Color.from_string(hex_value, Color.from_string(DEFAULT_MATERIALS.get(region, "#ffffff"), Color.WHITE))


static func valid_hex(value: String) -> bool:
	if _hex_re == null:
		_hex_re = RegEx.new()
		_hex_re.compile("^#[0-9a-fA-F]{3,8}$")
	return _hex_re.search(value) != null


## Optional echo of the VN character this config was mapped from (§2 source).
##
## Returns null (= NO source echo) when packId is not one of the four shipped
## packs: the server REQUIRES source.packId to be one of them, so emitting a
## source without a usable packId would make the whole definition unsaveable
## ("definition.source.packId must be one of [...]" 400). Slot/palette KEY names
## must match the server's lower-slug KEY_PATTERN and slot values its
## PART_ID_PATTERN — anything else is dropped rather than echoed.
static func _sanitize_source(raw: Variant) -> Variant:
	if not (raw is Dictionary):
		return null
	var pack := _clean_str(raw.get("packId"), MAX_VARIANT_LEN, "")
	if not (pack in SOURCE_PACKS):
		return null
	var out := {"packId": pack}
	var slots_raw: Variant = raw.get("slots")
	if slots_raw is Dictionary:
		var slots := {}
		for k in slots_raw.keys():
			if slots.size() >= MAX_SOURCE_SLOTS:
				break
			if not (k is String) or not valid_key(k):
				continue
			var raw_value: Variant = slots_raw[k]
			if raw_value == null:
				slots[k] = null                # explicit "slot cleared" — server keeps it
				continue
			var part_id: Variant = _opt_part_id(raw_value)
			if part_id != null:
				slots[k] = part_id
		out["slots"] = slots
	var palette_raw: Variant = raw.get("palette")
	if palette_raw is Dictionary:
		var palette := {}
		for k in palette_raw.keys():
			if palette.size() >= MAX_SOURCE_REGIONS:
				break
			var v: Variant = palette_raw[k]
			if not (k is String) or not valid_key(k) or not (v is String) or not valid_hex(v):
				continue
			palette[k] = v
		out["palette"] = palette
	var expr := _clean_str(raw.get("defaultExpression"), 20, "neutral")
	out["defaultExpression"] = expr if expr in EXPRESSIONS else "neutral"
	return out


## True for a code point the BFF refuses in a display name: C0/C1 controls plus
## every Unicode FORMAT (Cf) / LINE+PARAGRAPH SEPARATOR code point. Those are the
## invisible + bidi-override characters (U+202E RIGHT-TO-LEFT OVERRIDE, the
## U+2066..U+2069 isolates, U+200B..U+200F, U+FEFF, …) that let one participant's
## name visually rewrite a neighbouring participant's handle in a shared roster.
## Kept in lockstep with Char3DConfigValidator#isForbiddenNameCodePoint and the
## Angular mirror's FORBIDDEN_NAME_CHARS.
static func is_forbidden_name_char(c: int) -> bool:
	if c < 0x20 or (c >= 0x7F and c <= 0x9F):
		return true                                   # Cc
	if c == 0x00AD or c == 0x061C or c == 0x06DD or c == 0x070F or c == 0x08E2:
		return true
	if c >= 0x0600 and c <= 0x0605:
		return true
	if c == 0x180E or c == 0xFEFF:
		return true
	if c >= 0x200B and c <= 0x200F:
		return true
	if c >= 0x202A and c <= 0x202E:
		return true
	if c == 0x2028 or c == 0x2029:                    # Zl / Zp
		return true
	if c >= 0x2060 and c <= 0x2064:
		return true
	if c >= 0x2066 and c <= 0x206F:
		return true
	if c >= 0xFFF9 and c <= 0xFFFB:
		return true
	if c >= 0x1D173 and c <= 0x1D17A:
		return true
	if c >= 0xE0000 and c <= 0xE0FFF:
		return true
	return false


## Display name clamped exactly the way the BFF validates it: forbidden code
## points STRIPPED (the server 400s on them), edges trimmed, capped at 120.
static func _display_name(v: Variant) -> String:
	if not (v is String):
		return "Untitled"
	var cleaned := ""
	for c in (v as String):
		if not is_forbidden_name_char(c.unicode_at(0)):
			cleaned += c
	cleaned = cleaned.strip_edges()
	if cleaned.is_empty():
		return "Untitled"
	return cleaned.substr(0, MAX_NAME_LEN)


static func _clean_str(v: Variant, max_len: int, fallback: String) -> String:
	if not (v is String):
		return fallback
	var s: String = v.strip_edges()
	if s.is_empty():
		return fallback
	return s.substr(0, max_len)


## String clamped to max_len, or null (both "absent" and non-string map to null).
static func _opt_str(v: Variant, max_len: int) -> Variant:
	if not (v is String):
		return null
	var s: String = v.strip_edges()
	if s.is_empty():
		return null
	return s.substr(0, max_len)


## Optional SHORT descriptor (head.variant / earStyle / hornStyle) clamped the way
## the BFF validates it: stripped, truncated to 40, and DROPPED (null) when the
## remainder does not match SHORT_STR_PATTERN. Truncate-then-check, never
## check-then-truncate: the server rejects >40, so the mirror must shorten first.
## A value the server would 400 on can NEVER survive this — studio.gd posts
## assembler.config verbatim.
static func _opt_short_str(v: Variant) -> Variant:
	var s: Variant = _opt_str(v, MAX_VARIANT_LEN)
	if s == null or not valid_short_str(s):
		return null
	return s


## Optional part/asset id (parts.* and source slot values) clamped to the server's
## PART_ID_PATTERN; anything else drops to null.
static func _opt_part_id(v: Variant) -> Variant:
	var s: Variant = _opt_str(v, MAX_PART_ID_LEN)
	if s == null or not valid_part_id(s):
		return null
	return s


static func _clamp_num(v: Variant, lo: float, hi: float, fallback: float) -> float:
	if v is float or v is int:
		return clampf(float(v), lo, hi)
	return fallback
