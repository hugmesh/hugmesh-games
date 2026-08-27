class_name CharForgePalette
extends RefCounted

# =============================================================================
# Palette-strip recolor — THE 7(+4)-region mechanism (technique.md verdict).
#
# One tiny 8x8 ImageTexture is generated at runtime from the avatar's region
# colours and shared by every material of that avatar. Recolouring is a
# 64-pixel image rebuild; imported GLBs opt in by mapping their UV islands
# onto the cells below (or by material-slot name via the hm_<region> ingest
# contract, converted at fixup time); procedural primitives sample a fixed
# cell through the house shader's `palette_cell` uniform.
#
# NEAREST FILTERING is enforced at the SAMPLER, not the texture: the house
# toon shader declares `uniform sampler2D palette_tex : filter_nearest,
# repeat_disable`, so cell colours never bleed regardless of texture import
# settings. (Per-instance shader uniforms are unsupported in the
# Compatibility renderer, which is exactly why the per-avatar palette rides
# in a texture instead of instance uniforms.)
#
# FIXED CELL LAYOUT (col, row) in the 8x8 grid — THIS IS A CONTRACT.
# UV of a cell's centre = ((col + 0.5) / 8, (row + 0.5) / 8). Row 0 is the
# TOP of the image (Image y=0), matching UV v=0 at the top in Godot.
#
#   row 0: the 7 canonical regions (1:1 with the SVG palette / BFF schema)
#     (0,0) fur   (1,0) accent   (2,0) detail   (3,0) line
#     (4,0) iris  (5,0) skin     (6,0) hair     (7,0) UNUSED
#   row 1: the kemono.md extended coat-block regions
#     (0,1) innerMuzzle  (1,1) socks  (2,1) innerEar  (3,1) tailTip
#     (4..7,1) UNUSED
#   rows 2-7: RESERVED for future part libraries.
#
# Every UNUSED/RESERVED cell is filled with the avatar's `line` colour on
# purpose: a mis-mapped UV island reads as linework instead of a random
# colour, which makes mapping bugs visible without being garish.
#
# EXTENDED REGION DEFAULTS (kemono.md colour blocks) — when a config does not
# carry an explicit hex for an extended region, its colour is DERIVED from
# the 7 canonical regions. The derivations preserve the pre-palette visual
# defaults of the procedural assembler exactly:
#   innerMuzzle -> fur lightened by INNER_MUZZLE_LIGHTEN ("lighter
#                  inner-muzzle + chest/belly" rule)
#   innerEar    -> skin   (inner-ear used the skin material before)
#   socks       -> accent (sock markings used the accent material before)
#   tailTip     -> accent (tail tips used the accent material before)
# =============================================================================

const GRID_SIZE := 8

## fur lighten factor for the derived innerMuzzle block (Color.lightened arg).
const INNER_MUZZLE_LIGHTEN := 0.45

## Fixed (col,row) cell per region — see the layout contract above.
const CELLS := {
	"fur": Vector2i(0, 0),
	"accent": Vector2i(1, 0),
	"detail": Vector2i(2, 0),
	"line": Vector2i(3, 0),
	"iris": Vector2i(4, 0),
	"skin": Vector2i(5, 0),
	"hair": Vector2i(6, 0),
	"innerMuzzle": Vector2i(0, 1),
	"socks": Vector2i(1, 1),
	"innerEar": Vector2i(2, 1),
	"tailTip": Vector2i(3, 1),
}

## Extended region -> the canonical region its default derives from.
const DERIVED_FROM := {
	"innerMuzzle": "fur",
	"socks": "accent",
	"innerEar": "skin",
	"tailTip": "accent",
}


## UV of a region cell's centre (the value procedural meshes/fixed materials
## sample the palette at).
static func region_uv(region: String) -> Vector2:
	var cell: Vector2i = CELLS.get(region, CELLS["fur"])
	return Vector2((float(cell.x) + 0.5) / float(GRID_SIZE), (float(cell.y) + 0.5) / float(GRID_SIZE))


## Effective colour of ANY region (canonical or extended) for a config:
## explicit valid hex wins; absent extended regions derive per DERIVED_FROM.
static func region_color(config: Dictionary, region: String) -> Color:
	var mats: Variant = config.get("materials", {})
	if mats is Dictionary and mats.get(region) is String and Char3DConfig.valid_hex(mats[region]):
		return Color.from_string(mats[region], Color.WHITE)
	if DERIVED_FROM.has(region):
		var base := region_color(config, DERIVED_FROM[region])
		if region == "innerMuzzle":
			return base.lightened(INNER_MUZZLE_LIGHTEN)
		return base
	return Char3DConfig.region_color(config, region)


## Builds the 8x8 palette Image for a config (all 11 region cells filled,
## unused cells = line colour).
static func build_image(config: Dictionary) -> Image:
	# create_empty(), not create(): the deprecated alias was REMOVED in Godot
	# 4.7, and because this is a parse-time symbol lookup its absence does not
	# degrade — palette.gd fails to compile, and every script that depends on it
	# (character_assembler, house_materials, glb_loader, studio …) fails with
	# it, i.e. the whole maker refuses to load. create_empty has existed since
	# 4.3, so this spelling is also correct for the 4.6 the project declares.
	var img := Image.create_empty(GRID_SIZE, GRID_SIZE, false, Image.FORMAT_RGBA8)
	img.fill(region_color(config, "line"))
	for region in CELLS:
		var cell: Vector2i = CELLS[region]
		img.set_pixel(cell.x, cell.y, region_color(config, region))
	return img


## Fresh ImageTexture for a config (filtering is sampler-side — see header).
static func build_texture(config: Dictionary) -> ImageTexture:
	return ImageTexture.create_from_image(build_image(config))


## Recolour-only fast path: rewrites an existing palette texture in place
## (64-pixel update; every material sharing the texture updates for free).
##
## Returns the Image that was uploaded. Callers should KEEP it as the CPU-side
## source of truth: ImageTexture.get_image() is a RenderingServer readback —
## it costs a GPU round trip in a real build and returns stale data under the
## dummy rasterizer (i.e. in every headless test), so it must never be used to
## ask "what colour is this avatar?".
static func update_texture(tex: ImageTexture, config: Dictionary) -> Image:
	var img := build_image(config)
	tex.update(img)
	return img
