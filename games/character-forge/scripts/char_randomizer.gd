class_name CharRandomizer
extends RefCounted

# Seeded, archetype-coherent "Surprise me" — the 3D twin of the SVG studio's
# randomizeCharacter (hugmesh-character-studio-ui
# src/app/character-studio/util/randomize.ts): the archetype-establishing
# choice (head family) is made FIRST, then every other slot draws only from
# candidates coherent with that family (with a generic fallback), optional
# slots fill with the same 0.6 probability, and tint regions are seeded from
# the same curated colour list.

# Mirror of CURATED_COLORS in randomize.ts (provenance: that file is the
# canonical list; groups are contiguous ranges — fur tones / vivid accents /
# skin tones / hair colours).
const FUR_TONES := [
	"#8b5a2b", "#a0522d", "#6b4423", "#d2b48c", "#b8860b",
	"#c0c0c0", "#f5f5dc", "#4a3728", "#2f2f2f",
]
const VIVID_ACCENTS := ["#ff4f79", "#00bcd4", "#7c4dff", "#76ff03", "#ff9100", "#00e5a0"]
const SKIN_TONES := ["#ffdbac", "#f1c27d", "#e0ac69", "#c68642", "#8d5524"]
const HAIR_COLORS := ["#090806", "#71635a", "#b7a69e", "#fff5e1", "#e6cea8", "#563d2d", "#dc4b3e"]

const OPTIONAL_PART_PROBABILITY := 0.6

# Family-coherent candidate pools (VN partId vocabulary — menagerie-v1 ids).
const FAMILY_TAILS := {
	"canid": ["tail_fox_01", "tail_wolf_bushy_01", "tail_husky_curl_01"],
	"felid": ["tail_cat_01", "tail_lion_01"],
	"avian": ["tail_feather_fan_01", "tail_phoenix_01"],
	"reptile": ["tail_thick_lizard_01", "tail_spade_01"],
	"dragon": ["tail_spade_01", "tail_thick_lizard_01", "tail_eastern_01"],
	"dino": ["tail_dino_01"],
	"kobold": ["tail_kobold_01", "tail_spade_01"],
	"gryphon": ["tail_gryphon_01", "tail_lion_01"],
	"equine": ["tail_horse_01"],
	"cervine": ["tail_deer_01"],
	"bovine": ["tail_cow_tassel_01"],
	"caprine": ["tail_deer_01"],
	"suid": ["tail_pig_curl_01"],
	"hyena": ["tail_hyena_01"],
	"lapine": ["tail_bunny_puff_01"],
	"rodent": ["tail_rat_thin_01", "tail_squirrel_01"],
	"macropod": ["tail_macropod_01"],
	"moth": [],
	"insect": [],
	"bat": [],
	"ursine": ["tail_bear_stub_01"],
	"mustelid": ["tail_otter_01", "tail_ringed_01"],
	"procyonid": ["tail_ringed_01", "tail_redpanda_01"],
	"amphibian": ["tail_tadpole_01"],
	"aquatic": ["tail_shark_01", "tail_cetacean_01"],
	"slime": ["tail_goo_01"],
	"robot": ["tail_synth_01"],
	"synth": ["tail_synth_01"],
	"humanoid": [],
}

const FAMILY_WINGS := {
	"avian": ["wings_feathered_01"],
	"gryphon": ["wings_feathered_01"],
	"dragon": ["wings_dragon_01"],
	"kobold": ["wings_dragon_01", "wings_small_01"],
	"bat": ["wings_bat_01"],
	"moth": ["wings_moth_01", "wings_butterfly_01"],
	"insect": ["wings_insect_01", "wings_butterfly_01"],
	"slime": ["wings_goo_01"],
	"synth": ["wings_crystal_01"],
	"robot": ["wings_crystal_01"],
	"humanoid": ["wings_small_01", "wings_feathered_01"],
}

const FAMILY_FEATURES := {
	"equine": ["feature_horse_mane_01", "feature_unicorn_horn_01"],
	"cervine": ["feature_antlers_01"],
	"bovine": ["feature_bull_horns_01"],
	"caprine": ["feature_goat_horns_01", "feature_ram_horns_01"],
	"dragon": ["feature_dragon_horns_01", "feature_dragon_spines_01", "feature_frill_01"],
	"kobold": ["feature_dragon_horns_01", "feature_frill_01"],
	"reptile": ["feature_frill_01", "feature_dragon_spines_01"],
	"dino": ["feature_head_crest_01", "feature_dragon_spines_01"],
	"aquatic": ["feature_dorsal_fin_01", "feature_head_fins_01", "feature_fin_mane_01"],
	"amphibian": ["feature_head_fins_01"],
	"avian": ["feature_head_crest_01"],
	"gryphon": ["feature_head_crest_01"],
	"suid": ["feature_tusks_01"],
	"hyena": ["feature_hyena_mane_01"],
	"felid": ["feature_mane_01"],
	"moth": ["feature_antennae_01"],
	"insect": ["feature_antennae_ball_01", "feature_antennae_01"],
	"slime": ["feature_goo_crown_01", "feature_core_orb_01"],
	"robot": ["feature_core_orb_01"],
	"synth": ["feature_synth_fins_01", "feature_core_orb_01"],
}

const GENERIC_MARKINGS := [
	"marking_mask_01", "marking_countershade_01", "marking_sock_01",
	"marking_muzzle_blaze_01", "marking_fawn_spots_01", "marking_tiger_stripes_01",
	"marking_belly_patch_01",
]

const FAMILY_MARKINGS := {
	"hyena": ["marking_hyena_spots_01"],
	"insect": ["marking_bee_stripes_01"],
	"dino": ["marking_dino_stripes_01"],
	"reptile": ["marking_belly_scutes_01"],
	"dragon": ["marking_belly_scutes_01", "marking_metallic_sheen_01"],
	"bovine": ["marking_holstein_patches_01"],
	"slime": ["marking_goo_drip_01"],
	"robot": ["marking_metallic_sheen_01"],
	"synth": ["marking_metallic_sheen_01"],
	"procyonid": ["marking_mask_01"],
}

const HAIR_PARTS := ["hair_flower_01", "hair_leafy_01"]
const ACCESSORIES := [
	"accessory_bandana_01", "accessory_collar_01", "accessory_glasses_01",
	"accessory_ear_piercing_01", "accessory_mushroom_cap_01", "accessory_slime_pet_01",
]


## Deterministic random Char3DConfig for a seed. Same seed -> same character.
static func randomize_config(seed_value: int) -> Dictionary:
	var rng := RandomNumberGenerator.new()
	rng.seed = seed_value

	# 1. Establish the archetype first (randomize.ts ordering).
	var family: String = Char3DConfig.FAMILIES[rng.randi_range(0, Char3DConfig.FAMILIES.size() - 1)]

	var cfg := Char3DConfig.defaults()
	cfg.name = "Untitled"
	cfg.body.archetype = family
	cfg.body.build = rng.randf_range(0.2, 0.8)
	cfg.body.height = rng.randf_range(0.85, 1.2)
	# The shape axes take the FULL 0..1 range rather than build's trimmed 0.2-0.8:
	# curve/top only reshape the torso blocks, so the extremes stay a readable
	# silhouette, whereas an extreme `build` scales the whole rig.
	cfg.body.curve = rng.randf()
	cfg.body.top = rng.randf()
	cfg.head.family = family
	cfg.head.variant = "seed-%d" % (seed_value % 1000)
	cfg.head.snoutLength = rng.randf_range(0.2, 0.9)

	# 2. Constrain the remaining slots to the established archetype.
	cfg.parts.tail = _pick_optional(FAMILY_TAILS.get(family, []), rng, 0.9)
	var wing_pool: Array = FAMILY_WINGS.get(family, [])
	var wing_p := 0.75 if not wing_pool.is_empty() else 0.0
	if family == "humanoid":
		wing_p = 0.2
	cfg.parts.wings = _pick_optional(wing_pool, rng, wing_p)
	cfg.parts.feature = _pick_optional(FAMILY_FEATURES.get(family, []), rng, OPTIONAL_PART_PROBABILITY)
	var markings_pool: Array = FAMILY_MARKINGS.get(family, GENERIC_MARKINGS)
	cfg.parts.markings = _pick_optional(markings_pool, rng, 0.5)
	cfg.parts.accessory = _pick_optional(ACCESSORIES, rng, 0.35)
	if family == "humanoid":
		cfg.parts.hair = "hair_default_%02d" % rng.randi_range(1, 12)
	else:
		cfg.parts.hair = _pick_optional(HAIR_PARTS, rng, 0.15)

	# 3. Palette from the curated colour groups (regions seeded like the SVG
	#    studio: first part naming a region wins; here all 7 always exist).
	cfg.materials.fur = _pick(FUR_TONES, rng)
	cfg.materials.accent = _pick(VIVID_ACCENTS, rng)
	cfg.materials.detail = _pick(FUR_TONES + VIVID_ACCENTS, rng)
	cfg.materials.line = _pick(["#2f2f2f", "#090806", "#4a3728"], rng)
	cfg.materials.iris = _pick(VIVID_ACCENTS, rng)
	cfg.materials.skin = _pick(SKIN_TONES, rng)
	cfg.materials.hair = _pick(HAIR_COLORS, rng)
	# jrpg-bases.md: "line = darkest + outline color". The curated groups can
	# independently draw a hair or detail tone darker than the drawn line (e.g.
	# hair #090806 against line #4a3728), which reads as a smudge instead of
	# linework, so the generated palette is corrected before it is returned.
	#
	# This is a GENERATION rule, not a validation rule: Char3DConfig.sanitize
	# mirrors the server-side validator field-for-field (DESIGN §3) and must
	# never silently rewrite colours a user saved, so the correction lives here
	# and nowhere else.
	cfg.materials = CharForgeBlocking.enforce_line_darkest(cfg.materials)

	# 4. Style: kemonomimi/humanoid leans anime (anime-v1 precedent), synthetic
	#    families stay toon, otherwise mostly toon with occasional variety.
	if family == "humanoid":
		cfg.style = "anime"
	elif family == "robot" or family == "synth" or family == "slime":
		cfg.style = "toon"
	else:
		var roll := rng.randf()
		if roll < 0.7:
			cfg.style = "toon"
		elif roll < 0.85:
			cfg.style = "anime"
		else:
			cfg.style = "classic"

	return Char3DConfig.sanitize(cfg)


static func _pick(pool: Array, rng: RandomNumberGenerator) -> String:
	return pool[rng.randi_range(0, pool.size() - 1)]


static func _pick_optional(pool: Array, rng: RandomNumberGenerator, probability: float) -> Variant:
	if pool.is_empty() or rng.randf() >= probability:
		return null
	return _pick(pool, rng)
