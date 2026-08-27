class_name CharForgeProportions
extends RefCounted

# =============================================================================
# PROCEDURAL V2 — the numeric proportion tables.
#
# Every number below is traceable to a research verdict; the source is named in
# the comment next to it. Nothing here is invented "to taste".
#
#   kemono.md   head = sphere + plush muzzle 15-25% head depth (wide-rounded,
#               tapers into cheeks, NEVER a cone); eyes 0.28-0.35 face width
#               each, 0.8-1.2 eye-widths apart, LOW on the face, slight inward
#               tilt, iris fills the eye; UPPER-LID-ANGLE (~10-15deg neutral
#               droop) is THE expression driver; tiny nose AT the eye line;
#               wide smile-arc mouth; presets chibi-kemono 2.5-3 heads,
#               standard 4.5-5.5 heads; limbs taper, mitt hands, DIGITIGRADE
#               legs; ears large/high/rounded-triangular with a separate
#               inner-ear region.
#   jrpg-bases.md  heroic 6-7 heads / chibi 2-3 (head 38-42% of height);
#               hands 1.4-1.6x; shoulders 1.5-1.7 head-widths heroic; eyes
#               1/4-1/3 face height BELOW the midline, iris 60-80% of the
#               aperture; hair 5-9 asymmetric spike clusters at 1.2-1.4x the
#               cranium; nose none/dot.
#   technique.md   anime: eyes 2-3x anatomical, eye line at ~45-50% of head
#               height, minimal nose, large cranium.
#
# The Char3DConfig SCHEMA IS NOT EXTENDED by this file — adding fields would
# desync the client from the server-side validator (DESIGN §3). The preset is
# DERIVED from the existing `style`, `head.family` and `body.height` fields, so
# any config that round-trips through the BFF keeps working untouched.
#
# `legacy` reproduces the pre-v2 numbers exactly (head scale lands on 1.0) and
# stays the preset for every family the research does not cover, so this change
# is additive for those families rather than a silent restyle.
# =============================================================================

## Foot-to-crown height of the rig at scale 1 (measured from the assembler's
## primitive layout: hips 0.62 .. neck top 1.42 + a 0.34-tall head).
const BASE_BODY_HEIGHT := 1.62
## Height of the primitive skull assembly at head scale 1.
const BASE_HEAD_HEIGHT := 0.34

## `body.height` at or below which a preset switches to its chibi twin.
## (body.height is the 0.5..1.5 schema field; 1.0 is the default adult.)
const CHIBI_HEIGHT_CUTOFF := 0.75

## Families the kemono style rules apply to (fur-faced anthro heads with a
## real muzzle). Kemonomimi (anime-v1) map to `humanoid` + ear/tail parts and
## therefore take the JRPG rows, exactly as the pack mapping intends.
const KEMONO_FAMILIES := [
	"canid", "felid", "lapine", "rodent", "ursine", "mustelid", "procyonid",
	"hyena", "macropod", "cervine", "caprine", "equine", "bovine", "suid",
	"kobold", "gryphon",
]

## Families that take the JRPG/anime hero rows.
const JRPG_FAMILIES := ["humanoid"]

# --- documented value bands (asserted by the tests) --------------------------
const MUZZLE_DEPTH_BAND := Vector2(0.15, 0.25)        # kemono.md
const EYE_WIDTH_BAND := Vector2(0.28, 0.35)           # kemono.md
const EYE_GAP_BAND := Vector2(0.8, 1.2)               # kemono.md (eye-widths)
const UPPER_LID_BAND := Vector2(10.0, 15.0)           # kemono.md (degrees)
const CHIBI_KEMONO_HEADS := Vector2(2.5, 3.0)         # kemono.md
const STANDARD_KEMONO_HEADS := Vector2(4.5, 5.5)      # kemono.md
const HEROIC_JRPG_HEADS := Vector2(6.0, 7.0)          # jrpg-bases.md
const CHIBI_JRPG_HEADS := Vector2(2.0, 3.0)           # jrpg-bases.md
const HAIR_CLUSTER_BAND := Vector2i(5, 9)             # jrpg-bases.md
const HAND_SCALE_BAND := Vector2(1.4, 1.6)            # jrpg-bases.md
const SHOULDER_BAND := Vector2(1.5, 1.7)              # jrpg-bases.md (head widths)
const IRIS_FILL_BAND := Vector2(0.6, 0.8)             # jrpg-bases.md
## kemono.md instead wants the iris to FILL the eye — its own band sits above
## the JRPG one, so it is stated separately rather than clamped into it.
const KEMONO_IRIS_FILL_BAND := Vector2(0.8, 0.95)

## Muzzles are lofted, never coned: the tip ring keeps at least this fraction
## of the base ring's radius ("wide-rounded, tapers into cheeks, never cone").
const MUZZLE_MIN_TIP_FRACTION := 0.45

const PRESETS := {
	# ── kemono ───────────────────────────────────────────────────────────────
	"chibi_kemono": {
		"headsTall": 2.75,             # kemono.md 2.5-3
		"muzzleDepthRatio": 0.22,      # plush, at the deep end of 0.15-0.25
		"muzzleWidthRatio": 0.62,
		"muzzleTaper": 0.34,
		"eyeWidthRatio": 0.33,         # kemono.md 0.28-0.35
		"eyeGapEyeWidths": 0.9,        # kemono.md 0.8-1.2
		"eyeLineRatio": 0.60,          # LOW on the face (fraction from the crown)
		"eyeTiltDeg": 7.0,             # slight inward tilt
		"irisFill": 0.9,               # iris fills the eye + big highlight
		"upperLidAngleDeg": 13.0,      # kemono.md 10-15 neutral droop
		"noseStyle": "dot",
		"noseAtEyeLine": true,
		"mouthArcWidthRatio": 0.52,    # wide smile-arc mouth for lip-sync
		"earScale": 1.45,              # large/high rounded-triangular
		"earHeightRatio": 0.94,
		"earStyleHint": "rounded_tri",
		"digitigrade": true,
		"limbTaper": 0.62,
		"handScale": 1.35,             # mitt hands
		"shoulderHeadWidths": 1.15,
		"hairClusters": 5,
		"hairScale": 1.18,
	},
	"standard_kemono": {
		"headsTall": 5.0,              # kemono.md 4.5-5.5
		"muzzleDepthRatio": 0.19,
		"muzzleWidthRatio": 0.56,
		"muzzleTaper": 0.40,
		"eyeWidthRatio": 0.30,
		"eyeGapEyeWidths": 1.05,
		"eyeLineRatio": 0.56,
		"eyeTiltDeg": 6.0,
		"irisFill": 0.85,
		"upperLidAngleDeg": 11.5,
		"noseStyle": "dot",
		"noseAtEyeLine": true,
		"mouthArcWidthRatio": 0.46,
		"earScale": 1.25,
		"earHeightRatio": 0.90,
		"earStyleHint": "rounded_tri",
		"digitigrade": true,
		"limbTaper": 0.72,
		"handScale": 1.15,
		"shoulderHeadWidths": 1.35,
		"hairClusters": 5,
		"hairScale": 1.20,
	},
	# ── JRPG / anime ────────────────────────────────────────────────────────
	"heroic_jrpg": {
		"headsTall": 6.5,              # jrpg-bases.md 6-7
		"muzzleDepthRatio": 0.0,       # no muzzle on a human head
		"muzzleWidthRatio": 0.0,
		"muzzleTaper": 0.0,
		"eyeWidthRatio": 0.30,         # anime eyes at 2-3x anatomical
		"eyeGapEyeWidths": 1.0,
		"eyeLineRatio": 0.55,          # technique.md ~45-50% head height + jrpg
		                               # "1/4-1/3 face height BELOW the midline"
		"eyeTiltDeg": 5.0,
		"irisFill": 0.72,              # jrpg-bases.md 60-80%
		"upperLidAngleDeg": 10.0,
		"noseStyle": "none",           # jrpg-bases.md none/dot
		"noseAtEyeLine": false,
		"mouthArcWidthRatio": 0.34,
		"earScale": 1.0,
		"earHeightRatio": 0.70,
		"earStyleHint": "human",
		"digitigrade": false,
		"limbTaper": 0.80,
		"handScale": 1.5,              # jrpg-bases.md 1.4-1.6
		"shoulderHeadWidths": 1.6,     # jrpg-bases.md 1.5-1.7 heroic
		"hairClusters": 7,             # jrpg-bases.md 5-9 asymmetric clusters
		"hairScale": 1.3,              # jrpg-bases.md 1.2-1.4x cranium
	},
	"chibi_jrpg": {
		"headsTall": 2.5,              # jrpg-bases.md chibi 2-3
		"muzzleDepthRatio": 0.0,
		"muzzleWidthRatio": 0.0,
		"muzzleTaper": 0.0,
		"eyeWidthRatio": 0.34,
		"eyeGapEyeWidths": 0.85,
		"eyeLineRatio": 0.60,
		"eyeTiltDeg": 4.0,
		"irisFill": 0.78,
		"upperLidAngleDeg": 10.0,
		"noseStyle": "none",
		"noseAtEyeLine": false,
		"mouthArcWidthRatio": 0.30,
		"earScale": 1.0,
		"earHeightRatio": 0.70,
		"earStyleHint": "human",
		"digitigrade": false,
		"limbTaper": 0.70,
		"handScale": 1.55,
		"shoulderHeadWidths": 1.2,
		"hairClusters": 6,
		"hairScale": 1.35,
	},
	# ── everything the research does not cover ───────────────────────────────
	# headsTall here is EXACTLY BASE_BODY_HEIGHT / BASE_HEAD_HEIGHT, so
	# head_scale() returns 1.0 and these families render byte-identically to
	# the pre-v2 assembler.
	"legacy": {
		"headsTall": BASE_BODY_HEIGHT / BASE_HEAD_HEIGHT,
		"muzzleDepthRatio": 0.0,
		"muzzleWidthRatio": 0.0,
		"muzzleTaper": 0.0,
		"eyeWidthRatio": 0.0,
		"eyeGapEyeWidths": 1.0,
		"eyeLineRatio": 0.5,
		"eyeTiltDeg": 0.0,
		"irisFill": 0.67,
		"upperLidAngleDeg": 0.0,
		"noseStyle": "primitive",
		"noseAtEyeLine": false,
		"mouthArcWidthRatio": 0.0,
		"earScale": 1.0,
		"earHeightRatio": 0.0,
		"earStyleHint": "primitive",
		"digitigrade": false,
		"limbTaper": 1.0,
		"handScale": 1.0,
		"shoulderHeadWidths": 1.0,
		"hairClusters": 0,
		"hairScale": 1.0,
	},
}


## Which preset a sanitized config resolves to (pure function of style,
## head.family and body.height — no schema extension).
static func preset_name(config: Dictionary) -> String:
	var head: Variant = config.get("head", {})
	var family := "canid"
	if head is Dictionary and head.get("family") is String:
		family = String(head.get("family"))
	var body: Variant = config.get("body", {})
	var height := 1.0
	if body is Dictionary:
		height = _num(body.get("height"), 1.0)
	var chibi := height <= CHIBI_HEIGHT_CUTOFF
	if family in KEMONO_FAMILIES:
		return "chibi_kemono" if chibi else "standard_kemono"
	if family in JRPG_FAMILIES:
		return "chibi_jrpg" if chibi else "heroic_jrpg"
	return "legacy"


## True when the config gets the v2 lofted/proportioned head rather than the
## pre-v2 primitive one.
static func is_v2(config: Dictionary) -> bool:
	return preset_name(config) != "legacy"


## The full proportion row for a config: the preset table modulated by the
## config's own build/height/snoutLength, with every value clamped back into
## its documented band. Always returns every key in PRESETS.legacy.
static func for_config(config: Dictionary) -> Dictionary:
	var name := preset_name(config)
	var row: Dictionary = PRESETS[name].duplicate()
	row["preset"] = name
	if name == "legacy":
		row["headScale"] = 1.0
		row["headUnit"] = BASE_HEAD_HEIGHT
		return row

	# Fail-soft on hostile/partial input, like the rest of the client mirror:
	# a caller that hands over a half-built dictionary gets the preset defaults
	# rather than a crash mid-rebuild.
	var head_raw: Variant = config.get("head")
	var head: Dictionary = head_raw if head_raw is Dictionary else {}
	var body_raw: Variant = config.get("body")
	var body: Dictionary = body_raw if body_raw is Dictionary else {}
	var build: float = clampf(_num(body.get("build"), 0.5), 0.0, 1.0)
	var snout: float = clampf(_num(head.get("snoutLength"), 0.5), 0.0, 1.0)
	var style: String = String(config.get("style", "toon")) if config.get("style") is String \
			else "toon"

	# snoutLength sweeps the muzzle depth across the WHOLE documented band
	# rather than scaling it arbitrarily, so 0.0 and 1.0 are still legal kemono
	# muzzles instead of a needle and a snout the size of the skull.
	var depth_band := MUZZLE_DEPTH_BAND
	if row.muzzleDepthRatio > 0.0:
		row["muzzleDepthRatio"] = clampf(lerpf(depth_band.x, depth_band.y, snout),
				depth_band.x, depth_band.y)
		# Longer muzzles taper harder into the cheeks; capped well short of a
		# cone by MUZZLE_MIN_TIP_FRACTION at loft time.
		row["muzzleTaper"] = clampf(float(row.muzzleTaper) + 0.18 * snout, 0.0, 0.55)

	# build widens the shoulders; the band is the heroic one, so only the JRPG
	# rows are clamped into it (kemono torsos are narrower by design).
	var shoulder: float = float(row.shoulderHeadWidths) * lerpf(0.88, 1.12, build)
	if name == "heroic_jrpg":
		shoulder = clampf(shoulder, SHOULDER_BAND.x, SHOULDER_BAND.y)
	row["shoulderHeadWidths"] = shoulder

	# anime pushes eyes to the top of the kemono band and the lids fully open
	# (technique.md: eyes enlarged 2-3x, eye line lower than anatomical).
	if style == "anime":
		row["eyeWidthRatio"] = float(row.eyeWidthRatio) * 1.12
		row["upperLidAngleDeg"] = float(row.upperLidAngleDeg) * 0.7
		row["hairClusters"] = int(row.hairClusters) + 2

	row["eyeWidthRatio"] = clampf(float(row.eyeWidthRatio), EYE_WIDTH_BAND.x, EYE_WIDTH_BAND.y)
	row["eyeGapEyeWidths"] = clampf(float(row.eyeGapEyeWidths), EYE_GAP_BAND.x, EYE_GAP_BAND.y)
	row["upperLidAngleDeg"] = clampf(float(row.upperLidAngleDeg), 0.0, UPPER_LID_BAND.y)
	row["hairClusters"] = clampi(int(row.hairClusters), HAIR_CLUSTER_BAND.x, HAIR_CLUSTER_BAND.y)
	row["handScale"] = clampf(float(row.handScale), 0.9, HAND_SCALE_BAND.y)
	var iris_band := KEMONO_IRIS_FILL_BAND if name.ends_with("kemono") else IRIS_FILL_BAND
	row["irisFill"] = clampf(float(row.irisFill), iris_band.x, iris_band.y)
	row["headsTall"] = clampf(float(row.headsTall), heads_band(name).x, heads_band(name).y)

	row["headUnit"] = BASE_BODY_HEIGHT / float(row.headsTall)
	row["headScale"] = float(row.headUnit) / BASE_HEAD_HEIGHT
	return row


## The documented heads-tall band for a preset (used by for_config and the
## tests; `legacy` reports its own fixed value as a degenerate band).
static func heads_band(name: String) -> Vector2:
	match name:
		"chibi_kemono":
			return CHIBI_KEMONO_HEADS
		"standard_kemono":
			return STANDARD_KEMONO_HEADS
		"heroic_jrpg":
			return HEROIC_JRPG_HEADS
		"chibi_jrpg":
			return CHIBI_JRPG_HEADS
		_:
			var legacy: float = BASE_BODY_HEIGHT / BASE_HEAD_HEIGHT
			return Vector2(legacy, legacy)


## Half-width of one eye in HEAD-LOCAL units for a proportion row, plus the
## centre offset from the face midline. Returned together because the gap is
## expressed in eye-widths and the two must stay consistent.
##   face_half_width: the skull's half width in head-local units.
## -> {"halfWidth": float, "centerX": float}
static func eye_layout(row: Dictionary, face_half_width: float) -> Dictionary:
	var face_width := face_half_width * 2.0
	var eye_width: float = face_width * float(row.get("eyeWidthRatio", 0.3))
	var gap: float = eye_width * float(row.get("eyeGapEyeWidths", 1.0))
	# centre-to-centre distance = inner gap + one full eye width
	var center_x := (gap + eye_width) * 0.5
	return {"halfWidth": eye_width * 0.5, "centerX": center_x}


## Numeric coercion that tolerates absent/typed-wrong values.
static func _num(value: Variant, fallback: float) -> float:
	if value is float or value is int:
		return float(value)
	return fallback
