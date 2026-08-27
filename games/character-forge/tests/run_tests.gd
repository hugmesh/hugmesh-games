extends SceneTree

# Headless behavior tests for the Character Forge Godot project.
#
# Run:  godot --headless --path godot/character-forge -s res://tests/run_tests.gd
# (or `make test` from godot/). Exits non-zero on any failure so CI can gate.
#
# These are REAL assertions on behavior (sanitize clamping, per-family rig
# distinctness, expression/jaw math, seeded-randomizer determinism), not
# existence checks. The tests/ dir is excluded from the Web export via the
# preset's exclude_filter, so none of this ships in the bundle.

var _pass := 0
var _fail := 0
var _ran := false


## Tests run on the FIRST frame, not in _initialize(): during _initialize the
## root viewport has not entered the tree yet, so add_child() would never fire
## _ready on scene instances (the scene-smoke test depends on it).
## _run_all is a COROUTINE (the GLB round-trip test awaits the loader's chunked
## fixup, which yields on process_frame). It therefore has to keep the main loop
## alive — returning true here would quit the tree before the coroutine resumed
## — so _process always returns false and _run_all calls quit() itself.
func _process(_delta: float) -> bool:
	if _ran:
		return false
	_ran = true
	_run_all()
	return false


func _run_all() -> void:
	_test_sanitize_clamps()
	_test_sanitize_drops_unknown_keys()
	_test_try_parse_hard_failures()
	_test_region_color()
	_test_mirror_never_emits_what_the_server_rejects()
	_test_source_echo_matches_the_server_contract()
	_test_display_name_strips_bidi_and_control_chars()
	_test_default_materials_match_the_shared_contract_fixture()
	_test_all_families_build_distinct_heads()
	_test_expressions_change_the_rig()
	_test_openness_drives_jaw()
	_test_randomizer_deterministic_and_coherent()
	_test_studio_scene_instantiates()

	# ── v2 ──────────────────────────────────────────────────────────────────
	_test_extended_region_clamps()
	_test_palette_cell_math()
	_test_palette_derivations_preserve_defaults()
	_test_palette_image_contents()
	_test_material_set_shares_one_palette()
	_test_outline_toggle()
	_test_proportion_bands()
	_test_proportion_preset_resolution()
	_test_proportion_eye_layout()
	_test_loft_muzzle_sections()
	_test_loft_geometry()
	_test_registry_resolution()
	_test_registry_license_discipline()
	_test_glb_slot_map_and_regions()
	_test_glb_mipmap_fixup()
	_test_glb_material_swap()
	_test_glb_bone_attachment()
	_test_lipsync_frame_selection()
	_test_lipsync_channel_resolution()
	_test_lipsync_visemes()
	_test_lipsync_drives_a_skeleton()
	_test_v2_head_geometry()
	_test_v2_body_geometry()
	_test_body_shape_axes()
	_test_body_proportion_axes()
	_test_shape_axes_reach_every_family()
	_test_vrm_addon_vendored()
	_test_mouth_quad_factory()
	_test_shader_contract()
	_test_studio_v2_controls()
	_test_rig_sanitize_clamps_and_is_sparse()
	_test_rig_applies_live_and_composes_with_expressions()
	_test_rig_auto_adjust_is_deterministic_and_bounded()
	_test_blocking_measurement()
	_test_line_is_darkest()
	_test_blocked_avatars_pass_their_lane()
	_test_triangle_budget()

	# ── vendored packs -> registry -> mounted avatar ─────────────────────────
	_test_pack_catalogue_loads()
	_test_pack_entries_all_register()
	_test_pack_registry_routing()
	_test_pack_hidden_meshes_exist()
	_test_pack_mount_avatar()
	_test_pack_mount_preserves_atlas_materials()
	_test_pack_mount_body_graft()
	_test_pack_mount_composed_outfit()
	_test_pack_mount_vrm_and_license()
	_test_pack_mount_failure_is_soft()
	_test_byo_import_round_trip()
	_test_bridge_pack_surface()
	_test_studio_pack_controls()

	# ── single-atlas per-region recolour (the retired 'preserve' verdict) ────
	_test_atlas_recolour_reaches_every_single_atlas_model()
	_test_atlas_recolour_rides_the_shared_palette()
	_test_boundary_free_recolour_reaches_every_100avatar()
	_test_atlas_clusters_are_deterministic_and_ranked()

	# ── runtime bone-weight mesh surgery (the retired "NOT POSSIBLE" verdict) ─
	_test_mesh_surgery_matches_the_pipeline_count()
	_test_mesh_surgery_lands_the_face_forward()
	_test_mesh_surgery_refuses_what_it_cannot_split()
	_test_pack_mount_donor_head_graft()
	_test_registry_holds_a_donor_to_the_same_licence_bar()

	await _test_glb_round_trip()

	print("")
	print("tests: %d passed, %d failed" % [_pass, _fail])
	quit(1 if _fail > 0 else 0)


func _check(cond: bool, label: String) -> void:
	if cond:
		_pass += 1
	else:
		_fail += 1
		push_error("FAIL: " + label)
		print("FAIL: " + label)


# ── Char3DConfig ─────────────────────────────────────────────────────────────

func _test_sanitize_clamps() -> void:
	var cfg := Char3DConfig.sanitize({
		"name": " Zeta ",
		"body": {"archetype": "dragon", "build": 5.0, "height": 0.1},
		"head": {"family": "dragon", "snoutLength": -2.0, "variant": "x".repeat(200)},
		"materials": {"fur": "#abc", "accent": "not-a-color", "iris": "#00FF00aa"},
		"style": "anime",
	})
	_check(cfg.name == "Zeta", "sanitize trims name edges")
	_check(cfg.body.build == 1.0, "build clamped high to 1.0")
	_check(cfg.body.height == 0.5, "height clamped low to 0.5")
	# The shape axes were absent from the payload above — the DEFAULT is the
	# contract here, not a convenience: every definition stored before these
	# keys existed arrives exactly like this.
	_check(cfg.body.curve == 0.5 and cfg.body.top == 0.5,
			"absent curve/top default to the centred 0.5")
	var axes := Char3DConfig.sanitize({"body": {"curve": 9.0, "top": -3.0}})
	_check(axes.body.curve == 1.0 and axes.body.top == 0.0,
			"out-of-range curve/top clamp into 0..1")
	_check(cfg.head.snoutLength == 0.0, "snoutLength clamped low to 0.0")
	_check(cfg.head.variant.length() == Char3DConfig.MAX_VARIANT_LEN,
			"variant truncated to MAX_VARIANT_LEN")
	_check(cfg.materials.fur == "#abc", "3-digit hex accepted")
	_check(cfg.materials.accent == Char3DConfig.DEFAULT_MATERIALS.accent,
			"invalid hex rejected -> default kept")
	_check(cfg.materials.iris == "#00FF00aa", "8-digit hex accepted")
	_check(cfg.style == "anime", "valid style kept")

	var bad := Char3DConfig.sanitize({
		"body": {"archetype": "chimera"},
		"head": {"family": "chimera"},
		"style": "photoreal",
	})
	_check(bad.head.family == "canid", "unknown family falls back to default")
	_check(bad.style == "toon", "unknown style falls back to default")


func _test_sanitize_drops_unknown_keys() -> void:
	var cfg := Char3DConfig.sanitize({"evil": true, "parts": {"tail": "tail_fox_01", "hat": "x"}})
	_check(not cfg.has("evil"), "unknown top-level key dropped")
	_check(not cfg.parts.has("hat"), "unknown part slot dropped")
	_check(cfg.parts.tail == "tail_fox_01", "known part slot kept")


func _test_try_parse_hard_failures() -> void:
	_check(Char3DConfig.try_parse("[1,2,3]") == null, "non-object JSON root -> null")
	_check(Char3DConfig.try_parse("{nope") == null, "malformed JSON -> null")
	var huge := '{"name":"' + "a".repeat(Char3DConfig.MAX_DEFINITION_BYTES) + '"}'
	_check(Char3DConfig.try_parse(huge) == null, "oversize definition -> null")
	var ok: Variant = Char3DConfig.try_parse('{"head":{"family":"felid"}}')
	_check(ok is Dictionary and ok.head.family == "felid", "valid JSON parses + sanitizes")
	_check(Char3DConfig.parse_or_default("garbage").head.family == "canid",
			"parse_or_default degrades to defaults")


func _test_region_color() -> void:
	var cfg := Char3DConfig.defaults()
	cfg.materials.fur = "#ff0000"
	var c := Char3DConfig.region_color(cfg, "fur")
	_check(c.is_equal_approx(Color(1, 0, 0)), "region_color decodes #ff0000")
	_check(Char3DConfig.region_color({}, "iris") ==
			Color.from_string(Char3DConfig.DEFAULT_MATERIALS.iris, Color.WHITE),
			"region_color falls back to the region default")


# ── mirror-vs-server contract ────────────────────────────────────────────────
#
# REGRESSION: studio.gd:193 posts JSON.stringify(assembler.config) straight at the
# host, which POSTs it verbatim to /bff/character-forge/characters. So a value this
# CLAMP mirror lets through that the server's Char3DConfigValidator REJECTS is an
# unconditional 400 on save — the character can never be stored, activated or
# looked up, and the XR room can never dress that participant. Every assertion
# below names the exact server rule it mirrors.

func _test_mirror_never_emits_what_the_server_rejects() -> void:
	# SHORT_STR_PATTERN ^[A-Za-z0-9 _-]{1,40}$ — punctuation/markup must DROP to
	# null, not survive truncated (the old _opt_str kept "Lop-ears!" verbatim).
	var cfg := Char3DConfig.sanitize({
		"head": {
			"family": "lapine",
			"variant": "<script>alert(1)</script>",
			"earStyle": "Lop-ears!",
			"hornStyle": "long lop ears",
		},
	})
	_check(cfg.head.variant == "", "hostile variant drops to the '' unset encoding")
	_check(cfg.head.earStyle == null, "earStyle failing SHORT_STR_PATTERN drops to null")
	_check(cfg.head.hornStyle == "long lop ears", "a legal short descriptor survives")

	# A >40 value is TRUNCATED (server rejects >40), and what is left must still
	# match — truncate-then-check, never check-then-truncate.
	var long_ok := Char3DConfig.sanitize({"head": {"variant": "a".repeat(60)}})
	_check(long_ok.head.variant.length() == 40 and Char3DConfig.valid_short_str(long_ok.head.variant),
			"over-long variant truncated to a value the server still accepts")

	# PART_ID_PATTERN ^[A-Za-z0-9_-]{1,64}$ for parts.*
	var parts := Char3DConfig.sanitize({
		"parts": {"tail": "tail fox 01", "wings": "wings_dragon_01", "ears": "a".repeat(80)},
	})
	_check(parts.parts.tail == null, "part id with spaces drops to null (server 400s on it)")
	_check(parts.parts.wings == "wings_dragon_01", "a legal part id survives")
	_check(parts.parts.ears == null or Char3DConfig.valid_part_id(parts.parts.ears),
			"over-long part id never survives in a shape the server rejects")

	# Full-match semantics: PCRE2 `$` also matches before a trailing newline, but
	# java.util.regex matches() does not.
	_check(not Char3DConfig.valid_part_id("tail_fox_01\n"),
			"trailing newline is NOT a valid part id (Matcher#matches parity)")
	_check(not Char3DConfig.valid_short_str("ok\nevil"), "embedded newline rejected")
	_check(not Char3DConfig.valid_key("Head"), "KEY_PATTERN is lower-slug only")
	_check(Char3DConfig.valid_key("head"), "a lower-slug key is valid")


func _test_source_echo_matches_the_server_contract() -> void:
	# packId is an ENUM server-side. A source echo whose packId is not one of the
	# four shipped packs used to be emitted WITHOUT a packId at all -> the server
	# answered 400 "definition.source.packId must be one of [...]" and the whole
	# save failed. Now the unusable echo is dropped instead.
	var foreign := Char3DConfig.sanitize({"source": {"packId": "custom-pack-v1", "slots": {}}})
	_check(not foreign.has("source"), "source echo with a non-enum packId is dropped entirely")

	var cfg := Char3DConfig.sanitize({
		"source": {
			"packId": "menagerie-v1",
			"slots": {
				"head": "head_wolf_01",
				"Ears": "ears_lop_01",        # uppercase KEY -> server 400
				"body": "body canid",         # invalid part id VALUE -> server 400
				"tail": null,                 # explicit clear -> kept as null
			},
			"palette": {"fur": "#8b5a2b", "Accent": "#d2b48c", "detail": "nope"},
			"defaultExpression": "rage",
		},
	})
	_check(cfg.has("source"), "a valid source echo is kept")
	_check(cfg.source.packId == "menagerie-v1", "packId echoed")
	_check(cfg.source.slots.get("head") == "head_wolf_01", "valid slot kept")
	_check(not cfg.source.slots.has("Ears"), "uppercase slot key dropped (KEY_PATTERN)")
	_check(not cfg.source.slots.has("body"), "slot value failing PART_ID_PATTERN dropped")
	_check(cfg.source.slots.has("tail") and cfg.source.slots["tail"] == null,
			"an explicit null slot is preserved (server accepts null)")
	_check(cfg.source.palette.get("fur") == "#8b5a2b", "valid palette region kept")
	_check(not cfg.source.palette.has("Accent"), "uppercase palette key dropped (KEY_PATTERN)")
	_check(not cfg.source.palette.has("detail"), "non-hex palette value dropped")
	_check(cfg.source.defaultExpression == "neutral", "non-canonical expression clamps to neutral")


func _test_display_name_strips_bidi_and_control_chars() -> void:
	# The server REJECTS these outright in a display name (they are the invisible /
	# bidi-override spoofing set), so the mirror must strip them before the config
	# is posted.
	var rlo := Char3DConfig.sanitize({"name": "Alice" + String.chr(0x202E) + "eciloB"})
	_check(rlo.name == "AliceeciloB", "U+202E RIGHT-TO-LEFT OVERRIDE stripped from the name")
	# NOTE 0x0000 is absent on purpose: a GDScript String cannot hold a NUL — the
	# engine substitutes U+FFFD at construction — so it can never reach the BFF from
	# here. Every OTHER forbidden code point is a real, reachable value.
	for cp in [0x0009, 0x001B, 0x007F, 0x00AD, 0x200B, 0x200F, 0x2066, 0x2069, 0xFEFF]:
		var probe := Char3DConfig.sanitize({"name": "a" + String.chr(cp) + "b"})
		_check(probe.name == "ab", "code point 0x%X stripped from the name" % cp)
	_check(Char3DConfig.sanitize({"name": String.chr(0x202E)}).name == "Untitled",
			"a name made only of forbidden characters falls back to Untitled")
	_check(Char3DConfig.sanitize({"name": "Zed"}).name == "Zed", "an ordinary name is untouched")


func _test_default_materials_match_the_shared_contract_fixture() -> void:
	# The unset-region palette exists in THREE copies (this one, the Angular
	# DEFAULT_MATERIALS, Char3DConfigValidator.DEFAULT_MATERIALS). They disagreed on
	# accent/detail/iris, so one stored character rendered in three different colour
	# schemes depending on which surface drew it. The shared fixture is the pin.
	var path := ProjectSettings.globalize_path("res://").path_join(
			"../../frontend/src/app/character-forge/contract/server-contract.fixtures.json")
	var text := FileAccess.get_file_as_string(path)
	_check(text != "", "shared contract fixture readable at %s" % path)
	if text == "":
		return
	var parsed: Variant = JSON.parse_string(text)
	_check(parsed is Dictionary, "shared contract fixture parses")
	if not (parsed is Dictionary):
		return
	var expected: Variant = parsed.get("serverContract", {}).get("defaultMaterials")
	_check(expected is Dictionary, "fixture carries serverContract.defaultMaterials")
	if not (expected is Dictionary):
		return
	for region in Char3DConfig.MATERIAL_REGIONS:
		_check(Char3DConfig.DEFAULT_MATERIALS.get(region) == expected.get(region),
				"DEFAULT_MATERIALS.%s == the shared contract (%s vs %s)"
				% [region, Char3DConfig.DEFAULT_MATERIALS.get(region), expected.get(region)])
	# The other bounded vocabularies must not drift from the server either.
	var contract: Dictionary = parsed["serverContract"]
	_check(Char3DConfig.SHORT_STR_PATTERN == contract.get("shortStrPattern"),
			"SHORT_STR_PATTERN == the server's")
	_check(Char3DConfig.PART_ID_PATTERN == contract.get("partIdPattern"),
			"PART_ID_PATTERN == the server's")
	_check(Char3DConfig.KEY_PATTERN == contract.get("keyPattern"), "KEY_PATTERN == the server's")
	_check(Char3DConfig.FAMILIES == Array(contract.get("families", [])),
			"FAMILIES == the server's vocabulary")
	_check(Char3DConfig.EXPRESSIONS == Array(contract.get("expressions", [])),
			"EXPRESSIONS == the server's vocabulary")
	_check(Char3DConfig.SOURCE_PACKS == Array(contract.get("packIds", [])),
			"SOURCE_PACKS == the server's pack enum")
	_check(Char3DConfig.MAX_NAME_LEN == int(contract.get("maxNameLength", -1)),
			"MAX_NAME_LEN == the server's cap")
	_check(Char3DConfig.MAX_DEFINITION_BYTES == int(contract.get("maxDefinitionBytes", -1)),
			"MAX_DEFINITION_BYTES == the server's cap")
	_check(Char3DConfig.MAX_SOURCE_SLOTS == int(contract.get("maxSourceSlots", -1)),
			"MAX_SOURCE_SLOTS == the server's cap")
	_check(Char3DConfig.MAX_SOURCE_REGIONS == int(contract.get("maxSourcePalette", -1)),
			"MAX_SOURCE_REGIONS == the server's cap")


# ── CharacterAssembler ───────────────────────────────────────────────────────

## Every one of the 29 families must build AND produce a visually distinct
## head: we fingerprint the head subtree (node names + mesh classes + scales)
## and assert all 29 fingerprints are unique.
func _test_all_families_build_distinct_heads() -> void:
	var signatures := {}
	for family in Char3DConfig.FAMILIES:
		var a := CharacterAssembler.new()
		get_root().add_child(a)
		var cfg := Char3DConfig.defaults()
		cfg.head.family = family
		cfg.body.archetype = family
		cfg.head.variant = ""  # no per-variant jitter: distinctness must come from the family spec
		a.apply_config(cfg)
		var head: Node3D = a.get_node_or_null("Rig/Body/NeckTop/Head")
		_check(head != null, "family %s builds a head" % family)
		var jaw_ok: bool = a._refs.get("jaw") != null
		_check(jaw_ok, "family %s has a jaw" % family)
		if head != null:
			var sig := _head_signature(head)
			_check(not signatures.has(sig),
					"family %s head distinct from %s" % [family, signatures.get(sig, "-")])
			signatures[sig] = family
		get_root().remove_child(a)
		a.free()
	_check(signatures.size() == Char3DConfig.FAMILIES.size(),
			"all %d families produced unique head signatures" % Char3DConfig.FAMILIES.size())


func _head_signature(node: Node) -> String:
	var parts := []
	_collect_sig(node, "", parts)
	parts.sort()
	return ";".join(parts)


func _collect_sig(node: Node, prefix: String, out: Array) -> void:
	for child in node.get_children():
		var entry: String = prefix + "/" + child.name
		if child is MeshInstance3D:
			var mi := child as MeshInstance3D
			# The mesh AABB is part of the fingerprint: two lofted v2 muzzles
			# sit at the same node position and scale, so without it the whole
			# procedural-v2 geometry would be invisible to this test.
			entry += ":" + mi.mesh.get_class() + ":" + str(mi.scale.snappedf(0.001)) \
					+ ":" + str(mi.position.snappedf(0.001)) \
					+ ":" + str(mi.mesh.get_aabb().size.snappedf(0.0005))
		out.append(entry)
		_collect_sig(child, entry, out)


func _test_expressions_change_the_rig() -> void:
	var a := CharacterAssembler.new()
	get_root().add_child(a)
	a.apply_config(Char3DConfig.defaults())

	_check(a.EXPRESSION_PRESETS.size() == 10 and Char3DConfig.EXPRESSIONS.size() == 10,
			"exactly 10 canonical expression presets")
	for label in Char3DConfig.EXPRESSIONS:
		_check(a.EXPRESSION_PRESETS.has(label), "preset exists for '%s'" % label)

	var eye_l: Node3D = a._refs["eye_l"]
	var eye_r: Node3D = a._refs["eye_r"]
	a.set_expression("wink")
	_check(eye_l.scale.y < 0.2 and eye_r.scale.y > 0.8,
			"wink closes the left eye only")
	a.set_expression("surprised")
	_check(eye_l.scale.y > 1.2, "surprised widens the eyes")
	var jaw: Node3D = a._refs["jaw"]
	var surprised_jaw := jaw.rotation_degrees.x
	a.set_expression("neutral")
	_check(jaw.rotation_degrees.x < surprised_jaw, "surprised drops the jaw vs neutral")
	a.set_expression("not-a-label")
	_check(a.expression == "neutral", "unknown expression label ignored")
	get_root().remove_child(a)
	a.free()


func _test_openness_drives_jaw() -> void:
	var a := CharacterAssembler.new()
	get_root().add_child(a)
	a.apply_config(Char3DConfig.defaults())
	var jaw: Node3D = a._refs["jaw"]
	a.set_openness(0.0)
	var closed := jaw.rotation_degrees.x
	a.set_openness(1.0)
	var open := jaw.rotation_degrees.x
	_check(open > closed + 10.0, "openness 0->1 pitches the jaw open (amplitude contract)")
	a.set_openness(7.5)
	_check(a.openness == 1.0, "openness clamped to 1.0")
	a.set_openness(-3.0)
	_check(a.openness == 0.0, "openness clamped to 0.0")
	get_root().remove_child(a)
	a.free()


# ── CharRandomizer ───────────────────────────────────────────────────────────

func _test_randomizer_deterministic_and_coherent() -> void:
	var a := CharRandomizer.randomize_config(1234)
	var b := CharRandomizer.randomize_config(1234)
	_check(JSON.stringify(a) == JSON.stringify(b), "same seed -> identical config")

	var differs := false
	for seed_value in range(10):
		if JSON.stringify(CharRandomizer.randomize_config(seed_value)) != JSON.stringify(a):
			differs = true
			break
	_check(differs, "different seeds -> different configs")

	# Archetype coherence: a chosen tail/wings part must come from the pool of
	# the ESTABLISHED family (randomize.ts ordering: archetype first).
	var coherent := true
	for seed_value in range(60):
		var cfg := CharRandomizer.randomize_config(seed_value)
		_check(cfg.head.family in Char3DConfig.FAMILIES, "seed %d family valid" % seed_value)
		_check(cfg.body.archetype == cfg.head.family, "seed %d body matches head" % seed_value)
		if cfg.parts.tail != null \
				and not (cfg.parts.tail in CharRandomizer.FAMILY_TAILS.get(cfg.head.family, [])):
			coherent = false
		if cfg.parts.wings != null \
				and not (cfg.parts.wings in CharRandomizer.FAMILY_WINGS.get(cfg.head.family, [])):
			coherent = false
	_check(coherent, "tails/wings always drawn from the established family's pool")


# ── Scene smoke ──────────────────────────────────────────────────────────────

## studio.tscn must instantiate + _ready without errors in headless mode
## (builds the UI, the assembler and the snapshot viewport).
func _test_studio_scene_instantiates() -> void:
	var scene: PackedScene = load("res://scenes/studio.tscn")
	_check(scene != null, "studio.tscn loads")
	if scene == null:
		return
	var studio := scene.instantiate()
	get_root().add_child(studio)
	var assembler: Node = studio.get("assembler")
	_check(assembler is CharacterAssembler, "studio owns a CharacterAssembler")
	_check(studio.get_node_or_null("UI") == null,
			"no in-canvas UI: every control lives in the Angular host over the bridge")
	if assembler is CharacterAssembler:
		_check(assembler.config.head.family in Char3DConfig.FAMILIES,
				"studio boots with a valid default config")
	get_root().remove_child(studio)
	studio.free()


# ═════════════════════════════════════════════════════════════════════════════
# ASSEMBLER V2 — palette, materials, proportions, loft, registry, GLB loader,
# lip-sync. Every assertion below checks a VALUE the production code computed,
# never that a symbol exists.
# ═════════════════════════════════════════════════════════════════════════════

func _approx(a: float, b: float, eps: float = 0.0005) -> bool:
	return absf(a - b) <= eps


# ── Char3DConfig: the extended kemono regions ────────────────────────────────

func _test_extended_region_clamps() -> void:
	_check(Char3DConfig.ALL_MATERIAL_REGIONS.size()
			== Char3DConfig.MATERIAL_REGIONS.size() + Char3DConfig.EXTENDED_MATERIAL_REGIONS.size(),
			"ALL_MATERIAL_REGIONS = canonical + extended, no duplicates")
	for region in Char3DConfig.ALL_MATERIAL_REGIONS:
		_check(CharForgePalette.CELLS.has(region), "region '%s' has a palette cell" % region)

	var cfg := Char3DConfig.sanitize({"materials": {
		"innerMuzzle": "#ff8800",
		"socks": "nope",
		"innerEar": "#0f0",
		"tailTip": 42,
		"bogusRegion": "#ffffff",
	}})
	_check(cfg.materials.get("innerMuzzle") == "#ff8800", "valid extended hex kept")
	_check(cfg.materials.get("innerEar") == "#0f0", "3-digit extended hex kept")
	_check(not cfg.materials.has("socks"), "invalid extended hex NOT stored (stays derived)")
	_check(not cfg.materials.has("tailTip"), "non-string extended value NOT stored")
	_check(not cfg.materials.has("bogusRegion"), "unknown region key dropped")

	# defaults() must not pre-fill the extended regions, or derivation dies.
	for region in Char3DConfig.EXTENDED_MATERIAL_REGIONS:
		_check(not Char3DConfig.defaults().materials.has(region),
				"defaults() leaves '%s' absent so it derives" % region)


# ── CharForgePalette ─────────────────────────────────────────────────────────

func _test_palette_cell_math() -> void:
	# The documented contract: UV of cell (col,row) is ((col+.5)/8,(row+.5)/8).
	var seen := {}
	for region in CharForgePalette.CELLS:
		var cell: Vector2i = CharForgePalette.CELLS[region]
		var key := "%d,%d" % [cell.x, cell.y]
		_check(not seen.has(key), "cell %s used by only one region (%s vs %s)"
				% [key, region, seen.get(key, "-")])
		seen[key] = region
		_check(cell.x >= 0 and cell.x < CharForgePalette.GRID_SIZE
				and cell.y >= 0 and cell.y < CharForgePalette.GRID_SIZE,
				"cell for '%s' is inside the 8x8 grid" % region)
		var uv := CharForgePalette.region_uv(region)
		_check(_approx(uv.x, (float(cell.x) + 0.5) / 8.0)
				and _approx(uv.y, (float(cell.y) + 0.5) / 8.0),
				"region_uv('%s') is the cell centre" % region)
	_check(CharForgePalette.region_uv("no-such-region")
			== CharForgePalette.region_uv("fur"),
			"unknown region falls back to the fur cell")
	# Row 0 holds the 7 canonical regions, row 1 the extended ones.
	for region in Char3DConfig.MATERIAL_REGIONS:
		_check(CharForgePalette.CELLS[region].y == 0, "'%s' lives on row 0" % region)
	for region in Char3DConfig.EXTENDED_MATERIAL_REGIONS:
		_check(CharForgePalette.CELLS[region].y == 1, "'%s' lives on row 1" % region)


## The derivations exist so that adding the extended regions changed NOTHING
## about how an old config looks. Assert the exact previous colours.
func _test_palette_derivations_preserve_defaults() -> void:
	var cfg := Char3DConfig.defaults()
	cfg.materials.fur = "#804020"
	cfg.materials.accent = "#00bcd4"
	cfg.materials.skin = "#ffdbac"

	_check(CharForgePalette.region_color(cfg, "innerEar")
			== Color.from_string("#ffdbac", Color.WHITE),
			"innerEar derives from skin (the material the inner ear used before)")
	_check(CharForgePalette.region_color(cfg, "socks")
			== Color.from_string("#00bcd4", Color.WHITE),
			"socks derives from accent (sock markings used accent before)")
	_check(CharForgePalette.region_color(cfg, "tailTip")
			== Color.from_string("#00bcd4", Color.WHITE),
			"tailTip derives from accent (tail tips used accent before)")
	var expected_inner := Color.from_string("#804020", Color.WHITE) \
			.lightened(CharForgePalette.INNER_MUZZLE_LIGHTEN)
	_check(CharForgePalette.region_color(cfg, "innerMuzzle").is_equal_approx(expected_inner),
			"innerMuzzle derives from fur lightened (kemono lighter inner muzzle)")

	# An explicit value always beats the derivation; an INVALID one never does.
	cfg.materials["socks"] = "#123456"
	_check(CharForgePalette.region_color(cfg, "socks")
			== Color.from_string("#123456", Color.WHITE), "explicit extended hex wins")
	cfg.materials["socks"] = "garbage"
	_check(CharForgePalette.region_color(cfg, "socks")
			== Color.from_string("#00bcd4", Color.WHITE),
			"invalid extended hex falls back to the derivation, not to white")


func _test_palette_image_contents() -> void:
	var cfg := Char3DConfig.defaults()
	cfg.materials.fur = "#ff0000"
	cfg.materials.line = "#0000ff"
	cfg.materials.iris = "#00ff00"
	var img := CharForgePalette.build_image(cfg)
	_check(img.get_width() == 8 and img.get_height() == 8, "palette image is 8x8")
	_check(img.get_pixel(0, 0).is_equal_approx(Color(1, 0, 0)), "fur cell holds the fur colour")
	_check(img.get_pixel(4, 0).is_equal_approx(Color(0, 1, 0)), "iris cell holds the iris colour")
	# Unused/reserved cells are deliberately the LINE colour so a mis-mapped UV
	# island reads as linework instead of a random colour.
	_check(img.get_pixel(7, 0).is_equal_approx(Color(0, 0, 1)), "unused row-0 cell = line colour")
	_check(img.get_pixel(5, 5).is_equal_approx(Color(0, 0, 1)), "reserved cell = line colour")

	# update_texture must be an in-place rewrite of the SAME texture object.
	# NOTE: ImageTexture.get_image() is a RenderingServer readback and returns
	# stale data under the headless dummy rasterizer, so the uploaded bytes are
	# asserted through the Image update_texture RETURNS (which is byte-for-byte
	# what was handed to the GPU), and the texture identity through its RID.
	var tex := CharForgePalette.build_texture(cfg)
	var before := tex.get_rid()
	cfg.materials.fur = "#00ffff"
	var uploaded := CharForgePalette.update_texture(tex, cfg)
	_check(tex.get_rid() == before, "recolor updates the texture in place (same RID)")
	_check(uploaded.get_pixel(0, 0).is_equal_approx(Color(0, 1, 1)),
			"in-place update actually changed the fur cell")
	_check(uploaded.get_width() == 8 and uploaded.get_height() == 8,
			"the in-place update keeps the 8x8 layout (no reallocation)")


# ── CharForgeMaterials ───────────────────────────────────────────────────────

func _test_material_set_shares_one_palette() -> void:
	var cfg := Char3DConfig.defaults()
	cfg.style = "anime"
	var mats := CharForgeMaterials.for_config(cfg)
	var texture_ids := {}
	for region in Char3DConfig.ALL_MATERIAL_REGIONS:
		var mat := mats.region(region)
		_check(mat != null and mat.shader == CharForgeMaterials.HOUSE_SHADER,
				"'%s' uses the house toon shader" % region)
		texture_ids[mat.get_shader_parameter("palette_tex")] = true
		var cell: Vector2 = mat.get_shader_parameter("palette_cell")
		_check(cell == CharForgePalette.region_uv(region),
				"'%s' material samples its own palette cell" % region)
	_check(texture_ids.size() == 1,
			"all %d region materials share ONE palette texture" % Char3DConfig.ALL_MATERIAL_REGIONS.size())
	_check(mats.region("fur").get_shader_parameter("band_cuts")
			== CharForgeMaterials.STYLE_PARAMS["anime"]["band_cuts"],
			"style params applied (anime = 3-band ramp)")
	_check(mats.region("no-such-region") == mats.region("fur"),
			"unknown region falls back to fur rather than null")

	# Recolour touches the palette only — the materials must be untouched.
	var fur_mat := mats.region("fur")
	cfg.materials.fur = "#123456"
	mats.recolor(cfg)
	_check(mats.region("fur") == fur_mat, "recolor does not replace materials")
	_check(mats.palette_image.get_pixel(0, 0)
			.is_equal_approx(Color.from_string("#123456", Color.WHITE)),
			"recolor rewrote the fur cell of the shared palette")

	var trans := mats.variant("fur", {"alpha": 0.5})
	_check(trans.shader == CharForgeMaterials.HOUSE_TRANS_SHADER,
			"alpha variant switches to the transparent house shader")
	_check(_approx(trans.get_shader_parameter("alpha_mul"), 0.5), "alpha uniform set")
	_check(mats.variant("iris", {"emission": 1.4}).get_shader_parameter("emission_boost") == 1.4,
			"emission variant sets emission_boost")


func _test_outline_toggle() -> void:
	var was := CharForgeMaterials.outline_enabled
	CharForgeMaterials.outline_enabled = false
	var off := CharForgeMaterials.for_config(Char3DConfig.defaults())
	_check(off.region("fur").next_pass == null,
			"outline quality toggle DEFAULTS OFF (no next_pass)")

	CharForgeMaterials.outline_enabled = true
	var on := CharForgeMaterials.for_config(Char3DConfig.defaults())
	var other := CharForgeMaterials.for_config(Char3DConfig.defaults())
	_check(on.region("fur").next_pass != null, "enabling the toggle adds the outline next_pass")
	var shared: Array = []
	for region in ["fur", "accent", "line", "iris"]:
		shared.append(on.region(region).next_pass)
	shared.append(other.region("fur").next_pass)
	var unique := {}
	for m in shared:
		unique[m] = true
	_check(unique.size() == 1,
			"every material fleet-wide shares ONE outline instance (no shader switch)")
	_check(shared[0].shader == CharForgeMaterials.OUTLINE_SHADER,
			"the shared outline material uses the inverted-hull shader")
	CharForgeMaterials.outline_enabled = was


# ── CharForgeProportions ─────────────────────────────────────────────────────

func _test_proportion_bands() -> void:
	# Every preset must report values inside the band its source document
	# specifies, for EVERY legal build/height/snout/style combination.
	var checked := 0
	for family in ["canid", "felid", "humanoid", "moth"]:
		for height in [0.5, 0.75, 0.76, 1.0, 1.5]:
			for build in [0.0, 0.5, 1.0]:
				for snout in [0.0, 0.5, 1.0]:
					for style in Char3DConfig.STYLES:
						var cfg := Char3DConfig.defaults()
						cfg.head.family = family
						cfg.body.archetype = family
						cfg.body.height = height
						cfg.body.build = build
						cfg.head.snoutLength = snout
						cfg.style = style
						var row := CharForgeProportions.for_config(cfg)
						checked += 1
						var band := CharForgeProportions.heads_band(row.preset)
						if float(row.headsTall) < band.x - 0.0001 \
								or float(row.headsTall) > band.y + 0.0001:
							_check(false, "%s/%s headsTall %.2f outside %s"
									% [family, row.preset, row.headsTall, str(band)])
							return
						if row.preset != "legacy":
							var eye: float = row.eyeWidthRatio
							if eye < CharForgeProportions.EYE_WIDTH_BAND.x - 0.0001 \
									or eye > CharForgeProportions.EYE_WIDTH_BAND.y + 0.0001:
								_check(false, "eyeWidthRatio %.3f outside the 0.28-0.35 band" % eye)
								return
							var lid: float = row.upperLidAngleDeg
							if lid > CharForgeProportions.UPPER_LID_BAND.y + 0.0001 or lid < 0.0:
								_check(false, "upperLidAngleDeg %.2f outside 0..15" % lid)
								return
							var clusters: int = row.hairClusters
							if clusters < 5 or clusters > 9:
								_check(false, "hairClusters %d outside 5..9" % clusters)
								return
							if row.muzzleDepthRatio > 0.0:
								var d: float = row.muzzleDepthRatio
								if d < CharForgeProportions.MUZZLE_DEPTH_BAND.x - 0.0001 \
										or d > CharForgeProportions.MUZZLE_DEPTH_BAND.y + 0.0001:
									_check(false, "muzzleDepthRatio %.3f outside 0.15-0.25" % d)
									return
	_check(true, "all %d proportion rows stay inside their documented bands" % checked)

	# snoutLength must sweep the muzzle band monotonically, end to end.
	var low := Char3DConfig.defaults()
	low.head.snoutLength = 0.0
	var high := Char3DConfig.defaults()
	high.head.snoutLength = 1.0
	var low_row := CharForgeProportions.for_config(low)
	var high_row := CharForgeProportions.for_config(high)
	_check(_approx(low_row.muzzleDepthRatio, CharForgeProportions.MUZZLE_DEPTH_BAND.x),
			"snoutLength 0 -> the shallow end of the muzzle band")
	_check(_approx(high_row.muzzleDepthRatio, CharForgeProportions.MUZZLE_DEPTH_BAND.y),
			"snoutLength 1 -> the deep end of the muzzle band")
	_check(float(high_row.muzzleTaper) > float(low_row.muzzleTaper),
			"a longer muzzle tapers harder into the cheeks")
	_check(float(high_row.muzzleTaper) <= 0.55, "taper capped short of a cone")


func _test_proportion_preset_resolution() -> void:
	var cases := [
		["canid", 1.0, "standard_kemono"],
		["canid", 0.75, "chibi_kemono"],
		["felid", 0.5, "chibi_kemono"],
		["humanoid", 1.0, "heroic_jrpg"],
		["humanoid", 0.6, "chibi_jrpg"],
		["moth", 1.0, "legacy"],
		["slime", 0.5, "legacy"],
		["robot", 1.0, "legacy"],
	]
	for case in cases:
		var cfg := Char3DConfig.defaults()
		cfg.head.family = case[0]
		cfg.body.archetype = case[0]
		cfg.body.height = case[1]
		var row := CharForgeProportions.for_config(cfg)
		_check(row.preset == case[2],
				"%s at height %.2f -> %s (got %s)" % [case[0], case[1], case[2], row.preset])

	# The legacy preset MUST leave the head at exactly its pre-v2 size, or this
	# change silently restyles 13 families.
	var legacy := Char3DConfig.defaults()
	legacy.head.family = "moth"
	legacy.body.archetype = "moth"
	var legacy_row := CharForgeProportions.for_config(legacy)
	_check(_approx(legacy_row.headScale, 1.0), "legacy headScale is exactly 1.0")
	_check(not CharForgeProportions.is_v2(legacy), "legacy families are not v2")

	# Chibi heads are BIGGER than heroic ones — the whole point of the tables.
	var chibi := Char3DConfig.defaults()
	chibi.head.family = "humanoid"
	chibi.body.archetype = "humanoid"
	chibi.body.height = 0.6
	var heroic := Char3DConfig.defaults()
	heroic.head.family = "humanoid"
	heroic.body.archetype = "humanoid"
	_check(float(CharForgeProportions.for_config(chibi).headScale)
			> float(CharForgeProportions.for_config(heroic).headScale) * 1.5,
			"a 2.5-head chibi has a far larger head than a 6.5-head hero")
	_check(bool(CharForgeProportions.for_config(Char3DConfig.defaults()).digitigrade),
			"kemono presets are digitigrade")
	_check(not bool(CharForgeProportions.for_config(heroic).digitigrade),
			"JRPG presets are plantigrade")


func _test_proportion_eye_layout() -> void:
	var cfg := Char3DConfig.defaults()
	var row := CharForgeProportions.for_config(cfg)
	var face_half := 0.17
	var layout := CharForgeProportions.eye_layout(row, face_half)
	var eye_width: float = float(layout.halfWidth) * 2.0
	_check(_approx(eye_width / (face_half * 2.0), float(row.eyeWidthRatio)),
			"one eye spans exactly eyeWidthRatio of the face width")
	# The inner gap must be the documented multiple of the eye width.
	var inner_gap: float = (float(layout.centerX) * 2.0) - eye_width
	_check(_approx(inner_gap / eye_width, float(row.eyeGapEyeWidths)),
			"inner-corner gap is eyeGapEyeWidths eye-widths")
	_check(float(layout.centerX) + float(layout.halfWidth) < face_half * 1.05,
			"eyes stay on the face rather than wrapping around the skull")
	# Low-set: eyes below the head's vertical midline.
	_check(float(row.eyeLineRatio) > 0.5, "kemono eyes sit LOW on the face")


# ── CharForgeLoft ────────────────────────────────────────────────────────────

func _test_loft_muzzle_sections() -> void:
	for taper in [0.0, 0.4, 1.0]:
		var sections := CharForgeLoft.muzzle_sections(0.08, 0.06, 0.045, taper)
		_check(sections.size() == 5, "muzzle has 5 sections (taper %.1f)" % taper)
		var base_rx: float = float(sections[1].rx)
		var previous_z := -1.0
		for section in sections:
			_check(float(section.z) >= previous_z, "sections advance monotonically along +Z")
			previous_z = float(section.z)
			# NEVER A CONE: the narrowest ring keeps a real radius.
			_check(float(section.rx) >= base_rx * CharForgeLoft.MIN_TIP_FRACTION * 0.85,
					"ring radius stays >= the min-tip fraction (taper %.1f)" % taper)
		# "tapers into the cheeks": section 0 is WIDER than the base ring.
		_check(float(sections[0].rx) > base_rx,
				"the cheek ring is wider than the muzzle base (taper %.1f)" % taper)
		_check(float(sections[4].rx) < base_rx, "the tip is narrower than the base")
		_check(_approx(float(sections[4].z), 0.08), "the last ring sits at the full depth")
	# A max-taper muzzle must still be plush, not a spike.
	var sharp := CharForgeLoft.muzzle_sections(0.08, 0.06, 0.045, 1.0)
	_check(float(sharp[4].rx) / float(sharp[1].rx) > 0.3,
			"even maximum taper leaves a plush tip (never a cone)")


func _test_loft_geometry() -> void:
	var uv := CharForgePalette.region_uv("fur")
	var mesh := CharForgeLoft.muzzle(0.08, 0.06, 0.045, 0.4, uv, Color(1, 0, 0))
	_check(mesh is ArrayMesh and mesh.get_surface_count() == 1, "muzzle bakes to one ArrayMesh surface")
	var tris := CharForgeLoft.triangle_count(mesh)
	# 4 quad bands * 12 segments * 2 tris + 12 cap tris.
	_check(tris == 4 * CharForgeLoft.DEFAULT_SEGMENTS * 2 + CharForgeLoft.DEFAULT_SEGMENTS,
			"muzzle triangle count matches the loft topology (got %d)" % tris)
	var arrays: Array = mesh.surface_get_arrays(0)
	var uvs: PackedVector2Array = arrays[Mesh.ARRAY_TEX_UV]
	var colors: PackedColorArray = arrays[Mesh.ARRAY_COLOR]
	var normals: PackedVector3Array = arrays[Mesh.ARRAY_NORMAL]
	_check(uvs.size() > 0 and colors.size() == uvs.size() and normals.size() == uvs.size(),
			"every vertex carries a UV, a colour and a generated normal")
	var all_cell := true
	for value in uvs:
		if not value.is_equal_approx(uv):
			all_cell = false
			break
	_check(all_cell, "every lofted vertex samples the SAME palette cell (1 draw call)")
	_check(colors[0].is_equal_approx(Color(1, 0, 0)), "region colour written as a vertex colour")

	var aabb := mesh.get_aabb()
	_check(_approx(aabb.size.z, 0.08, 0.002), "the mesh is exactly as deep as requested")
	_check(aabb.size.x > aabb.size.z, "the muzzle is WIDER than it is deep (plush, not a snout)")

	# The ear grows along +Y after the Z->Y re-commit.
	var ear := CharForgeLoft.rounded_tri_ear(0.17, 0.055, 0.018, uv, Color(0, 1, 0))
	var ear_aabb := ear.get_aabb()
	_check(_approx(ear_aabb.size.y, 0.17, 0.002), "rounded-tri ear is as tall as requested")
	_check(ear_aabb.size.x > ear_aabb.size.z, "the ear is a wide blade, not a cone")
	_check(CharForgeLoft.triangle_count(ear) > 0, "the ear bakes real triangles")

	# Degenerate input must not crash or emit garbage.
	_check(CharForgeLoft.triangle_count(CharForgeLoft.ring_loft([], uv, Color.WHITE)) == 0,
			"an empty section list yields an empty mesh")
	_check(CharForgeLoft.triangle_count(
			CharForgeLoft.ring_loft([{"z": 0.0}], uv, Color.WHITE)) == 0,
			"a single section cannot be lofted")

	# lathe(): a revolved profile, the other curved-geometry primitive the
	# technique report names (CSGPolygon3D SPIN's SurfaceTool equivalent).
	var profile := PackedVector2Array([Vector2(0.02, 0.0), Vector2(0.03, 0.05),
			Vector2(0.018, 0.10), Vector2(0.004, 0.14)])
	var horn := CharForgeLoft.lathe(profile, uv, Color(0, 0, 1))
	var horn_aabb := horn.get_aabb()
	_check(CharForgeLoft.triangle_count(horn) > 0, "lathe bakes real triangles")
	_check(_approx(horn_aabb.size.y, 0.14, 0.002),
			"the lathed profile is as tall as its last (radius, height) pair")
	_check(_approx(horn_aabb.size.x, 0.06, 0.004),
			"the lathed profile is as wide as twice its widest radius")
	_check(horn_aabb.size.x > horn_aabb.size.z * 0.9
			and horn_aabb.size.x < horn_aabb.size.z * 1.1,
			"a revolved profile is radially symmetric in X and Z")
	var horn_arrays: Array = horn.surface_get_arrays(0)
	var horn_uvs: PackedVector2Array = horn_arrays[Mesh.ARRAY_TEX_UV]
	_check(horn_uvs.size() > 0 and horn_uvs[0].is_equal_approx(uv),
			"lathed vertices carry the palette cell UV too")
	_check(CharForgeLoft.triangle_count(
			CharForgeLoft.lathe(PackedVector2Array([Vector2(0.01, 0.0)]), uv, Color.WHITE)) == 0,
			"a one-point profile cannot be revolved")


# ── CharForgeRegistry ────────────────────────────────────────────────────────

func _test_registry_resolution() -> void:
	var registry := CharForgeRegistry.default_registry()
	for family in Char3DConfig.FAMILIES:
		_check(registry.resolve_head(family).basis == CharForgeRegistry.BASIS_PROCEDURAL,
				"'%s' defaults to the procedural basis" % family)
	_check(registry.resolve_head("not-a-family").basis == CharForgeRegistry.BASIS_PROCEDURAL,
			"an unknown family resolves to procedural, never null")
	_check(not registry.needs_mesh("canid", "canid"), "an empty registry needs no GLB loader")

	# The proportion tables are fail-soft on malformed input, like the rest of
	# the client-side mirror: a half-built dictionary must not crash a rebuild.
	for hostile in [{}, {"head": 5, "body": "x"}, {"head": {"family": 7},
			"body": {"height": "tall"}}, {"style": 9}]:
		var row := CharForgeProportions.for_config(hostile)
		_check(row.has("headsTall") and row.has("headScale"),
				"a hostile config still yields a complete proportion row")
		_check(float(row.headScale) > 0.0, "a hostile config yields a usable head scale")

	var good := {
		"basis": "mesh",
		"glb": "res://assets/heads/wolf.glb",
		"rig": CharForgeRegistry.RIG_HUMANOID,
		"slots": {"HM_Fur": "fur", "hm_iris": "iris"},
		"visemes": true,
		"licenseSpdx": "CC0-1.0",
		"attribution": "Quaternius",
		"sourceUrl": "https://quaternius.com/packs/universalbasecharacters.html",
		"retrievedAt": "2026-07-29",
	}
	_check(registry.register_head("canid", good), "a fully-provenanced mesh basis registers")
	var resolved := registry.resolve_head("canid")
	_check(resolved.basis == CharForgeRegistry.BASIS_MESH, "canid now resolves to the mesh basis")
	_check(resolved.slots.has("hm_fur") and resolved.slots["hm_fur"] == "fur",
			"slot names are lower-cased on the way in")
	_check(resolved.visemes == true, "the viseme capability flag survives")
	_check(registry.needs_mesh("canid", "moth"), "needs_mesh sees the head basis")
	_check(registry.resolve_head("felid").basis == CharForgeRegistry.BASIS_PROCEDURAL,
			"registering one family does not affect the others")
	_check(registry.attributions().size() == 1
			and registry.attributions()[0].licenseSpdx == "CC0-1.0",
			"mesh entries are enumerable for a CC-BY credits screen")

	# resolve_* must hand back a COPY: a caller mutating it cannot poison the
	# registry for the next avatar.
	var copy := registry.resolve_head("canid")
	copy["basis"] = "procedural"
	copy.slots.clear()
	_check(registry.resolve_head("canid").basis == CharForgeRegistry.BASIS_MESH
			and not registry.resolve_head("canid").slots.is_empty(),
			"resolve_head returns a deep copy")


func _test_registry_license_discipline() -> void:
	var base := {
		"basis": "mesh",
		"glb": "res://a.glb",
		"rig": CharForgeRegistry.RIG_HUMANOID,
		"slots": {"hm_fur": "fur"},
		"licenseSpdx": "CC0-1.0",
		"attribution": "Someone",
		"sourceUrl": "https://example.invalid/a",
		"retrievedAt": "2026-07-29",
	}
	var registry := CharForgeRegistry.default_registry()
	_check(registry.register_head("canid", base), "the control entry registers")

	# Each rejection below is a licence/provenance rule enforced IN CODE.
	var rejections := {
		"licenseSpdx": ["CC-BY-NC-4.0", "proprietary", ""],
		"attribution": [""],
		"sourceUrl": [""],
		"retrievedAt": ["", "2026-7-29", "yesterday", "2026-99-99", "2026-00-10", "2026-07-00"],
		"glb": ["/etc/passwd", "http://evil.invalid/a.glb", "res://a.txt"],
		"rig": ["", "quadruped"],
	}
	for field in rejections:
		for value in rejections[field]:
			var entry := base.duplicate(true)
			entry[field] = value
			var fresh := CharForgeRegistry.default_registry()
			_check(not fresh.register_head("felid", entry),
					"mesh basis REJECTED for %s = '%s'" % [field, str(value)])
			_check(fresh.resolve_head("felid").basis == CharForgeRegistry.BASIS_PROCEDURAL,
					"a rejected entry leaves felid procedural (%s)" % field)
			_check(not fresh.last_errors.is_empty(), "the rejection reports a reason (%s)" % field)

	var no_slots := base.duplicate(true)
	no_slots["slots"] = {"hm_fur": "not-a-region"}
	var fresh2 := CharForgeRegistry.default_registry()
	_check(not fresh2.register_head("felid", no_slots),
			"a slot map pointing at an unknown region is rejected")
	_check(not CharForgeRegistry.default_registry().register_head("dragonoid", base),
			"an unknown family cannot be registered at all")
	# A procedural entry needs no provenance — it ships no bytes.
	_check(CharForgeRegistry.default_registry().register_head("felid", {"basis": "procedural"}),
			"a procedural entry needs no licence fields")
	for license in CharForgeRegistry.ALLOWED_LICENSES:
		_check(license in ["CC0-1.0", "CC-BY-3.0", "CC-BY-4.0", "MIT"],
				"the licence allow-list holds only the research-approved ids (%s)" % license)


# ── CharForgeGlbLoader ───────────────────────────────────────────────────────

func _test_glb_slot_map_and_regions() -> void:
	var map := CharForgeGlbLoader.normalize_slot_map({})
	for slot in CharForgeRegistry.DEFAULT_SLOT_MAP:
		_check(map.has(slot), "the default ingest contract exposes '%s'" % slot)
	var custom := CharForgeGlbLoader.normalize_slot_map(
			{"slots": {"WolfCoat": "fur", "Bogus": "not-a-region"}})
	_check(custom.get("wolfcoat") == "fur", "a registry slot map is merged in, lower-cased")
	_check(not custom.has("bogus"), "a slot pointing at an unknown region is dropped")
	_check(custom.has("hm_iris"), "the default contract still applies alongside a custom map")

	var cases := {
		"hm_fur": "fur",
		"HM_Fur": "fur",
		"hm_innerEar": "innerEar",
		"hm_fur.001": "fur",
		"WolfCoat": "fur",
		"Eye_L": "iris",
		"body_mesh": "fur",
		"MyHairMat": "hair",
		"": "",
		"zzz_unknown": "",
	}
	for name in cases:
		_check(CharForgeGlbLoader.region_for_material_name(name, custom) == cases[name],
				"material '%s' -> region '%s'" % [name, cases[name]])


func _test_glb_mipmap_fixup() -> void:
	var state := GLTFState.new()
	var img := Image.create_empty(16, 16, false, Image.FORMAT_RGBA8)
	img.fill(Color.RED)
	_check(not img.has_mipmaps(), "the fixture texture starts WITHOUT mipmaps")
	var with_mips := Image.create_empty(16, 16, true, Image.FORMAT_RGBA8)
	with_mips.fill(Color.BLUE)
	state.set_images([ImageTexture.create_from_image(img),
			ImageTexture.create_from_image(with_mips)])

	var fixed := CharForgeGlbLoader.fix_texture_mipmaps(state)
	_check(fixed == 1, "exactly the one un-mipmapped texture was rebuilt (got %d)" % fixed)
	var images: Array = state.get_images()
	_check(images.size() == 2, "the image list keeps its length and order")
	_check(images[0].get_image().has_mipmaps(), "engine bug #100481 worked around: mipmaps generated")
	_check(images[0].get_image().get_pixel(0, 0).is_equal_approx(Color.RED),
			"the rebuilt texture keeps its pixels")
	_check(images[1].get_image().get_pixel(0, 0).is_equal_approx(Color.BLUE),
			"an already-mipmapped texture is left alone")
	_check(CharForgeGlbLoader.fix_texture_mipmaps(state) == 0,
			"the fixup is idempotent (second pass rebuilds nothing)")
	_check(CharForgeGlbLoader.fix_texture_mipmaps(GLTFState.new()) == 0,
			"a document with no images is handled")


func _test_glb_material_swap() -> void:
	var mats := CharForgeMaterials.for_config(Char3DConfig.defaults())
	var root := Node3D.new()
	var mesh := BoxMesh.new()
	var authored := StandardMaterial3D.new()
	authored.resource_name = "hm_iris"
	mesh.surface_set_material(0, authored)
	var mi := MeshInstance3D.new()
	mi.name = "Body"
	mi.mesh = mesh
	root.add_child(mi)

	var unknown_mesh := BoxMesh.new()
	var unknown_mat := StandardMaterial3D.new()
	unknown_mat.resource_name = "zzz_mystery"
	unknown_mesh.surface_set_material(0, unknown_mat)
	var mi2 := MeshInstance3D.new()
	mi2.name = "Nested"
	mi2.mesh = unknown_mesh
	mi.add_child(mi2)

	var swapped := CharForgeGlbLoader.swap_materials(root, mats,
			CharForgeGlbLoader.normalize_slot_map({}))
	_check(swapped == 2, "both surfaces were rebound, including the nested one (got %d)" % swapped)
	_check(mi.get_surface_override_material(0) == mats.region("iris"),
			"the hm_iris slot picked up the shared IRIS material")
	_check(mi2.get_surface_override_material(0) == mats.region("fur"),
			"an unrecognised slot falls back to fur, never to null")
	_check(mi.get_surface_override_material(0).get_shader_parameter("palette_cell")
			== CharForgePalette.region_uv("iris"),
			"the swapped material samples the iris palette cell")
	root.free()


func _test_glb_bone_attachment() -> void:
	var skeleton := Skeleton3D.new()
	for bone in ["Hips", "Spine", "Chest", "Neck", "Head", "Jaw"]:
		skeleton.add_bone(bone)
	get_root().add_child(skeleton)

	_check(CharForgeGlbLoader.bone_for_slot(skeleton, "ears") == "Head", "ears bind to Head")
	_check(CharForgeGlbLoader.bone_for_slot(skeleton, "tail") == "Hips", "tail binds to Hips")
	# UpperChest is absent, so the wings candidate list must fall through.
	_check(CharForgeGlbLoader.bone_for_slot(skeleton, "wings") == "Chest",
			"wings fall through UpperChest to Chest")
	_check(CharForgeGlbLoader.bone_for_slot(skeleton, "no-such-slot") == "",
			"an unknown slot binds to nothing")
	_check(CharForgeGlbLoader.bone_for_slot(null, "ears") == "", "a null skeleton is tolerated")
	_check(CharForgeGlbLoader.find_jaw_bone(skeleton) == "Jaw", "the jaw bone is discovered")

	var part := Node3D.new()
	part.name = "TailPart"
	var attachment := CharForgeGlbLoader.attach_part(skeleton, "Hips", part)
	_check(attachment != null, "attach_part created an attachment")
	_check(attachment.bone_idx == skeleton.find_bone("Hips"),
			"the BoneAttachment3D bound to the RIGHT bone index")
	_check(attachment.get_parent() == skeleton and part.get_parent() == attachment,
			"the part hangs off the attachment, which hangs off the skeleton")
	_check(CharForgeGlbLoader.attach_part(skeleton, "Tail", Node3D.new()) == null,
			"attaching to a missing bone returns null instead of a phantom bone")

	var loader := CharForgeGlbLoader.new()
	get_root().add_child(loader)
	var rig := Node3D.new()
	rig.add_child(skeleton.duplicate())
	get_root().add_child(rig)
	var ear := Node3D.new()
	ear.name = "Ear"
	var wing := Node3D.new()
	wing.name = "Wing"
	var result := loader.attach_parts(rig, {"ears": ear, "no-such-slot": wing})
	_check(result.bound.has("ears"), "attach_parts bound the ear to the rig's Head bone")
	_check(result.unbound == ["no-such-slot"], "unbindable slots are reported, not silently lost")
	_check(CharForgeGlbLoader.find_skeleton(rig) != null, "the skeleton is found under the root")
	_check(CharForgeGlbLoader.find_skeleton(Node3D.new()) == null,
			"a rig-less root yields no skeleton")

	get_root().remove_child(rig)
	rig.free()
	get_root().remove_child(loader)
	loader.free()
	get_root().remove_child(skeleton)
	skeleton.free()


# ── CharForgeLipSync ─────────────────────────────────────────────────────────

func _test_lipsync_frame_selection() -> void:
	# The 5-frame strip: frame 0 below the first threshold, then one frame per
	# threshold crossed, and never out of range.
	_check(CharForgeLipSync.mouth_frame(0.0) == 0, "silence shows the closed frame")
	_check(CharForgeLipSync.mouth_frame(0.079) == 0, "just under the first threshold: frame 0")
	_check(CharForgeLipSync.mouth_frame(0.08) == 1, "the first threshold enters frame 1")
	_check(CharForgeLipSync.mouth_frame(0.28) == 2, "second threshold -> frame 2")
	_check(CharForgeLipSync.mouth_frame(0.52) == 3, "third threshold -> frame 3")
	_check(CharForgeLipSync.mouth_frame(0.76) == 4, "fourth threshold -> frame 4")
	_check(CharForgeLipSync.mouth_frame(1.0) == 4, "full openness stays on the last frame")
	_check(CharForgeLipSync.mouth_frame(9.0) == 4 and CharForgeLipSync.mouth_frame(-9.0) == 0,
			"out-of-range openness is clamped, never out of frame range")
	var previous := -1
	for i in range(101):
		var frame := CharForgeLipSync.mouth_frame(float(i) / 100.0)
		_check(frame >= previous, "frame selection is monotonic at openness %.2f" % (float(i) / 100.0))
		_check(frame >= 0 and frame < CharForgeLipSync.MOUTH_FRAMES, "frame in range")
		previous = frame

	# Hysteresis: a value sitting just past a boundary must NOT flip the frame.
	_check(CharForgeLipSync.frame_with_hysteresis(0, 0.09) == 0,
			"a hair over the threshold does not flip the frame up")
	_check(CharForgeLipSync.frame_with_hysteresis(0, 0.13) == 1,
			"clearing the threshold by the margin does flip up")
	_check(CharForgeLipSync.frame_with_hysteresis(1, 0.07) == 1,
			"a hair under the threshold does not flip the frame down")
	_check(CharForgeLipSync.frame_with_hysteresis(1, 0.03) == 0,
			"dropping below the threshold by the margin does flip down")
	_check(CharForgeLipSync.frame_with_hysteresis(2, 0.30) == 2, "staying put is stable")

	# The jaw formula is the pre-existing one, exactly.
	_check(_approx(CharForgeLipSync.jaw_angle_deg(0.0), 3.0), "a closed jaw rests at 3 degrees")
	_check(_approx(CharForgeLipSync.jaw_angle_deg(1.0), 27.0), "full openness = 27 degrees")
	_check(_approx(CharForgeLipSync.jaw_angle_deg(0.0, 1.0), 13.0),
			"an expression's jaw_base adds its own 10-degree range")
	_check(_approx(CharForgeLipSync.jaw_angle_deg(5.0, 5.0), 37.0),
			"both inputs are clamped before they are applied")


func _test_lipsync_channel_resolution() -> void:
	var J := CharForgeLipSync.CHANNEL_JAW
	var S := CharForgeLipSync.CHANNEL_STRIP
	var V := CharForgeLipSync.CHANNEL_VISEMES
	_check(CharForgeLipSync.resolve_channels(true, true, false, false) == [J],
			"the jaw is the mandatory channel and wins over the strip")
	_check(CharForgeLipSync.resolve_channels(false, true, false, false) == [S],
			"no jaw bone -> the mouth strip is the fallback")
	_check(CharForgeLipSync.resolve_channels(false, false, false, false) == [],
			"a mesh with neither channel drives nothing")
	_check(CharForgeLipSync.resolve_channels(true, false, true, true) == [J, V],
			"flagged+resolved visemes ride ALONGSIDE the jaw")
	_check(CharForgeLipSync.resolve_channels(true, false, true, false) == [J],
			"a viseme flag with no resolved shapes is ignored")
	_check(CharForgeLipSync.resolve_channels(true, false, false, true) == [J],
			"resolved shapes the manifest never flagged are ignored")
	_check(CharForgeLipSync.resolve_channels(false, true, true, true) == [S, V],
			"strip + visemes coexist when there is no jaw")


func _test_lipsync_visemes() -> void:
	var weights := CharForgeLipSync.viseme_weights(0.6, "ih")
	_check(weights.size() == 5, "every VRM viseme is present in the output")
	_check(_approx(weights["ih"], 0.6), "the chosen vowel carries the amplitude")
	for name in ["aa", "ou", "ee", "oh"]:
		_check(_approx(weights[name], 0.0),
				"'%s' is driven to zero so a held shape is released" % name)
	_check(_approx(CharForgeLipSync.viseme_weights(0.5, "NOT-A-VOWEL")["aa"], 0.5),
			"an unknown vowel falls back to 'aa'")
	_check(_approx(CharForgeLipSync.viseme_weights(3.0, "aa")["aa"], 1.0),
			"viseme weights are clamped to 1")

	# Blend-shape resolution across the VRM 1.0 / VRM 0.x / VRoid namings.
	var mi := MeshInstance3D.new()
	mi.mesh = _blend_shape_mesh(["Fcl_MTH_A", "I", "ou", "Blink", "Fcl_MTH_E"])
	_check(CharForgeLipSync.resolve_viseme_index(mi, "aa") == 0, "VRoid 'Fcl_MTH_A' resolves to aa")
	_check(CharForgeLipSync.resolve_viseme_index(mi, "ih") == 1, "VRM 0.x 'I' resolves to ih")
	_check(CharForgeLipSync.resolve_viseme_index(mi, "ou") == 2, "VRM 1.0 'ou' resolves directly")
	_check(CharForgeLipSync.resolve_viseme_index(mi, "ee") == 4, "'Fcl_MTH_E' resolves to ee")
	_check(CharForgeLipSync.resolve_viseme_index(mi, "oh") == -1, "an absent viseme reports -1")
	_check(CharForgeLipSync.resolve_viseme_index(null, "aa") == -1, "a null mesh reports -1")

	var sync := CharForgeLipSync.new()
	_check(sync.bind_visemes(mi, false) == 0, "an unflagged manifest binds nothing")
	_check(sync.bind_visemes(mi, true) == 4, "4 of the 5 visemes resolved on this mesh")
	sync.vowel = "aa"
	sync.set_openness(0.75)
	_check(CharForgeLipSync.CHANNEL_VISEMES in sync.active_channels(),
			"the viseme channel is live once bound")
	_check(_approx(mi.get_blend_shape_value(0), 0.75), "the aa shape was actually driven")
	_check(_approx(mi.get_blend_shape_value(1), 0.0), "the ih shape was driven to zero")
	mi.free()


func _test_lipsync_drives_a_skeleton() -> void:
	var skeleton := Skeleton3D.new()
	skeleton.add_bone("Head")
	skeleton.add_bone("Jaw")
	get_root().add_child(skeleton)
	var sync := CharForgeLipSync.new()
	_check(not sync.bind_jaw_bone(skeleton, "NoSuchBone"), "binding a missing jaw bone fails")
	_check(sync.bind_jaw_bone(skeleton, "Jaw"), "the jaw bone binds")
	_check(sync.active_channels() == [CharForgeLipSync.CHANNEL_JAW],
			"a rig with a jaw uses the jaw channel")

	var jaw_idx := skeleton.find_bone("Jaw")
	sync.set_openness(0.0)
	var closed := skeleton.get_bone_pose_rotation(jaw_idx)
	sync.set_openness(1.0)
	var open := skeleton.get_bone_pose_rotation(jaw_idx)
	_check(not closed.is_equal_approx(open), "openness rotates the jaw BONE, not just a node")
	var swing := rad_to_deg(closed.angle_to(open))
	_check(swing > 20.0 and swing < 30.0,
			"the bone swings the documented ~24 degrees (got %.1f)" % swing)

	# Mouth strip: the material's frame_index uniform must actually move.
	var strip := ShaderMaterial.new()
	strip.shader = load("res://shaders/mouth_strip.gdshader")
	var strip_sync := CharForgeLipSync.new()
	strip_sync.bind_mouth_strip(strip)
	_check(strip_sync.active_channels() == [CharForgeLipSync.CHANNEL_STRIP],
			"with no jaw the strip is the active channel")
	strip_sync.set_openness(0.9)
	_check(strip.get_shader_parameter("frame_index") == 4,
			"a loud frame is pushed to the strip shader")
	_check(strip_sync.strip_frame() == 4, "the driver reports the frame it displayed")
	strip_sync.set_openness(0.0)
	_check(strip.get_shader_parameter("frame_index") == 0, "silence returns the mouth to frame 0")

	get_root().remove_child(skeleton)
	skeleton.free()


# ── Assembler v2 integration ─────────────────────────────────────────────────

func _test_v2_head_geometry() -> void:
	var cfg := Char3DConfig.defaults()          # canid, height 1.0 -> kemono
	var a := CharacterAssembler.new()
	get_root().add_child(a)
	a.apply_config(cfg)

	_check(a.proportions.preset == "standard_kemono", "a default canid takes the kemono preset")
	var head: Node3D = a.get_node_or_null("Rig/Body/NeckTop/Head")
	var muzzle: MeshInstance3D = head.get_node_or_null("Muzzle")
	_check(muzzle != null and muzzle.mesh is ArrayMesh,
			"the crude capsule/box muzzle was replaced by a lofted ArrayMesh")
	_check(head.get_node_or_null("InnerMuzzle") != null,
			"the lighter inner-muzzle colour block is built")
	var inner: MeshInstance3D = head.get_node_or_null("InnerMuzzle")
	_check(inner.material_override.get_shader_parameter("palette_cell")
			== CharForgePalette.region_uv("innerMuzzle"),
			"the inner muzzle samples the innerMuzzle palette cell")

	# A deeper snout must produce a physically deeper mesh.
	var deep := cfg.duplicate(true)
	deep.head.snoutLength = 1.0
	var shallow := cfg.duplicate(true)
	shallow.head.snoutLength = 0.0
	a.apply_config(shallow)
	var shallow_depth: float = (a.get_node("Rig/Body/NeckTop/Head/Muzzle") as MeshInstance3D) \
			.mesh.get_aabb().size.z
	a.apply_config(deep)
	var deep_depth: float = (a.get_node("Rig/Body/NeckTop/Head/Muzzle") as MeshInstance3D) \
			.mesh.get_aabb().size.z
	_check(deep_depth > shallow_depth * 1.5,
			"snoutLength 0->1 measurably deepens the lofted muzzle (%.4f -> %.4f)"
			% [shallow_depth, deep_depth])

	# Eyes + the upper-lid expression channel.
	a.apply_config(cfg)
	_check(a._refs.has("lid_l") and a._refs.has("lid_r"), "v2 heads build real upper-lid nodes")
	var lid_l: Node3D = a._refs["lid_l"]
	var neutral: float = float(a._refs["lid_neutral"])
	_check(neutral >= CharForgeProportions.UPPER_LID_BAND.x
			and neutral <= CharForgeProportions.UPPER_LID_BAND.y,
			"the neutral lid droop is inside the documented 10-15 degree band")
	a.set_expression("neutral")
	var lid_neutral := lid_l.rotation_degrees.x
	a.set_expression("sleepy")
	var lid_sleepy := lid_l.rotation_degrees.x
	a.set_expression("surprised")
	var lid_surprised := lid_l.rotation_degrees.x
	_check(lid_sleepy > lid_neutral + 10.0, "sleepy drops the upper lid heavily")
	_check(lid_surprised < lid_neutral, "surprised lifts the upper lid")

	# Rounded-triangular ears with their own inner-ear region.
	var ear_l: Node3D = a._refs["ear_l"]
	var ear_mesh: MeshInstance3D = ear_l.get_node_or_null("Ear")
	var ear_inner: MeshInstance3D = ear_l.get_node_or_null("Inner")
	_check(ear_mesh != null and ear_mesh.mesh is ArrayMesh,
			"kemono ears are lofted rounded triangles, not prisms")
	_check(ear_inner != null and ear_inner.material_override.get_shader_parameter("palette_cell")
			== CharForgePalette.region_uv("innerEar"),
			"the inner ear samples the innerEar palette cell")

	# A legacy family must be untouched by all of the above.
	var legacy := Char3DConfig.defaults()
	legacy.head.family = "moth"
	legacy.body.archetype = "moth"
	a.apply_config(legacy)
	_check(a.proportions.preset == "legacy", "moth stays on the legacy preset")
	_check(not a._refs.has("lid_l"), "legacy heads build no lid nodes")
	_check(a.get_node("Rig/Body/NeckTop/Head").scale.is_equal_approx(Vector3.ONE),
			"a legacy head is at exactly its pre-v2 scale")

	get_root().remove_child(a)
	a.free()


func _test_v2_body_geometry() -> void:
	var a := CharacterAssembler.new()
	get_root().add_child(a)

	# Digitigrade legs for kemono.
	a.apply_config(Char3DConfig.defaults())
	var body: Node3D = a.get_node("Rig/Body")
	for part in ["ThighL", "ShinL", "AnkleL", "PawL", "ThighR", "PawR"]:
		_check(body.get_node_or_null(part) != null, "digitigrade leg part '%s' built" % part)
	_check(body.get_node_or_null("LegL") == null, "the plantigrade capsule leg is gone")
	var ankle: MeshInstance3D = body.get_node("AnkleL")
	var paw: MeshInstance3D = body.get_node("PawL")
	_check(ankle.position.y > paw.position.y,
			"the ankle is RAISED above the paw — the thing that makes a leg digitigrade")

	# Plantigrade for a JRPG humanoid, with spike-cluster hair.
	var hero := Char3DConfig.defaults()
	hero.head.family = "humanoid"
	hero.body.archetype = "humanoid"
	a.apply_config(hero)
	body = a.get_node("Rig/Body")
	_check(body.get_node_or_null("LegL") != null and body.get_node_or_null("ThighL") == null,
			"a JRPG hero keeps plantigrade legs")
	var hair_mount: Node3D = a._refs["hair_mount"]
	var spikes := 0
	for child in hair_mount.get_children():
		if String(child.name).begins_with("Spike"):
			spikes += 1
	_check(spikes >= 5 and spikes <= 9,
			"JRPG hair is 5-9 asymmetric spike clusters (got %d)" % spikes)
	_check(hair_mount.get_node_or_null("Cap") != null, "the spike clusters sit on a cranium cap")

	# Asymmetry is the documented requirement: mirrored clusters would read wrong.
	var lengths := {}
	for child in hair_mount.get_children():
		if child is MeshInstance3D and String(child.name).begins_with("Spike"):
			lengths[snappedf((child as MeshInstance3D).mesh.get_aabb().size.y, 0.0001)] = true
	_check(lengths.size() > 1, "the spike clusters are asymmetric (varied lengths)")

	# Hand scale actually moves with the proportion table. The size is read
	# BEFORE the next apply_config, which frees the whole previous rig.
	var hero_hand: float = (body.get_node("HandL") as MeshInstance3D).mesh.get_aabb().size.x
	a.apply_config(Char3DConfig.defaults())
	var kemono_hand: float = (a.get_node("Rig/Body/HandL") as MeshInstance3D).mesh.get_aabb().size.x
	_check(hero_hand > kemono_hand,
			"the JRPG 1.4-1.6x hand is larger than the kemono mitt (%.4f > %.4f)"
			% [hero_hand, kemono_hand])

	# Basis indirection: registering a mesh basis must SIGNAL, keeping the
	# procedural rig on screen in the meantime.
	var seen: Array = []
	a.mesh_basis_required.connect(func(kind, entry): seen.append([kind, entry.glb]))
	var registry := CharForgeRegistry.default_registry()
	_check(registry.register_head("canid", {
		"basis": "mesh", "glb": "res://x.glb", "rig": CharForgeRegistry.RIG_HUMANOID,
		"slots": {"hm_fur": "fur"}, "licenseSpdx": "CC0-1.0", "attribution": "Q",
		"sourceUrl": "https://example.invalid", "retrievedAt": "2026-07-29"}),
		"the test mesh basis registered")
	a.registry = registry
	a.apply_config(Char3DConfig.defaults())
	_check(seen == [["head", "res://x.glb"]], "a mesh basis emits mesh_basis_required once")
	_check(a.get_node_or_null("Rig/Body/NeckTop/Head") != null,
			"the procedural rig is STILL built while the GLB is fetched")
	_check(a.resolved_bases().head.basis == CharForgeRegistry.BASIS_MESH,
			"resolved_bases reports the mesh basis")

	# Recolour without a rebuild.
	var head_node: Node = a.get_node("Rig/Body/NeckTop/Head")
	_check(a.recolor({"fur": "#010203", "socks": "#040506", "bogus": "#ffffff"}),
			"recolor accepted the change")
	_check(a.get_node("Rig/Body/NeckTop/Head") == head_node,
			"recolor did NOT rebuild the rig")
	_check(a.materials.palette_image.get_pixel(0, 0)
			.is_equal_approx(Color.from_string("#010203", Color.WHITE)),
			"recolor rewrote the fur cell")
	_check(a.materials.palette_image
			.get_pixel(CharForgePalette.CELLS["socks"].x, CharForgePalette.CELLS["socks"].y)
			.is_equal_approx(Color.from_string("#040506", Color.WHITE)),
			"recolor rewrote the extended socks cell")
	_check(not a.config.materials.has("bogus"), "recolor ignores unknown regions")

	get_root().remove_child(a)
	a.free()


## body.curve (hip-to-waist ratio) / body.top (chest volume) — the two shape
## axes. They are PROCEDURAL on purpose: a scoping pass found zero body morph
## targets on any of the 75 vendored models, so re-proportioning the assembler's
## own torso primitives is the only route that works without asset authoring.
##
## Three claims, and only the first is "the slider does something":
##   1. each axis measurably moves the torso at 0.0 / 0.5 / 1.0,
##   2. 0.5 reproduces the pre-axis literals EXACTLY, and
##   3. a definition that OMITS both keys is indistinguishable from 0.5 / 0.5.
##
## (3) is the migration guard, and it is the one that must never go red. Every
## character stored before these keys existed has no curve/top in its jsonb
## definition and the BFF has no migration mechanism (Liquibase 001 only), so
## "absent" has to stay a no-op forever — the day it stops being one, every
## saved avatar silently reshapes with nothing to roll back to.
func _test_body_shape_axes() -> void:
	var a := CharacterAssembler.new()
	get_root().add_child(a)

	# 1. Each axis is VISIBLE: three settings, three different torso boxes.
	for axis in ["curve", "top"]:
		var seen := {}
		for value in [0.0, 0.5, 1.0]:
			var cfg := Char3DConfig.defaults()
			cfg.body[axis] = value
			a.apply_config(cfg)
			var size := _torso_aabb(a).size.snappedf(0.0005)
			_check(not seen.has(size),
					"body.%s %.1f gives torso extents distinct from %s"
					% [axis, value, seen.get(size, "-")])
			seen[size] = "%.1f" % value
		_check(seen.size() == 3, "all three body.%s stops are visually distinct" % axis)

	# 2. The centred stop is EXACT, not approximate. These four literals are
	#    what _build_body hardcoded before the axes existed; the bands are
	#    centred on them and float32 scale/radius storage makes the midpoints
	#    land on the identical bits.
	a.apply_config(Char3DConfig.defaults())
	var body: Node3D = a.get_node("Rig/Body")
	var hips: MeshInstance3D = body.get_node("Hips")
	var belly: MeshInstance3D = body.get_node("Belly")
	var chest: MeshInstance3D = body.get_node("Chest")
	_check(hips.scale == Vector3(1.15, 0.8, 1.0), "curve 0.5 keeps the original Hips scale")
	# CapsuleMesh.radius is float32 storage while a GDScript literal is a double,
	# so the reference literal has to make the same round trip before equality
	# means anything — comparing straight against 0.205 fails on the last bits
	# even when the stored value is bit-for-bit what it always was.
	var original_belly := CapsuleMesh.new()
	original_belly.radius = 0.205
	_check((belly.mesh as CapsuleMesh).radius == original_belly.radius,
			"curve 0.5 keeps the original Belly radius")
	_check(chest.scale == Vector3(1.05, 0.9, 0.88), "top 0.5 keeps the original Chest scale")
	_check(chest.position == Vector3(0, 1.08, 0), "top 0.5 keeps the original Chest position")

	# 3. THE MIGRATION GUARD: omitted == centred, node for node.
	var explicit := Char3DConfig.defaults()
	explicit.body.curve = 0.5
	explicit.body.top = 0.5
	a.apply_config(explicit)
	var centred_aabb := _torso_aabb(a)
	var centred_sig := _head_signature(a.get_node("Rig/Body"))

	var legacy := Char3DConfig.defaults()
	legacy.body.erase("curve")
	legacy.body.erase("top")
	_check(not legacy.body.has("curve") and not legacy.body.has("top"),
			"the legacy fixture really omits both keys")
	a.apply_config(legacy)
	_check(_torso_aabb(a) == centred_aabb,
			"a definition omitting curve/top renders the SAME extents as 0.5/0.5")
	_check(_head_signature(a.get_node("Rig/Body")) == centred_sig,
			"...and the whole body subtree matches node for node (no silent reshape)")

	# ORTHOGONALITY. `build` owns overall mass — it writes rig.scale and drives
	# proportions.gd's shoulder width. If curve/top leaked into either, the two
	# sliders would fight and neither would mean anything on its own.
	var arm_roots := {}
	var rig_scales := {}
	var tri_counts := {}
	for value in [0.0, 0.5, 1.0]:
		var cfg := Char3DConfig.defaults()
		cfg.body.curve = value
		cfg.body.top = 1.0 - value          # push both axes, in opposite directions
		a.apply_config(cfg)
		arm_roots[(a.get_node("Rig/Body/ArmL") as Node3D).position.snappedf(0.0001)] = true
		rig_scales[(a.get_node("Rig") as Node3D).scale.snappedf(0.0001)] = true
		tri_counts[_triangles(a)] = true
	_check(arm_roots.size() == 1, "the arm root never moves with curve/top (that is build's knob)")
	_check(rig_scales.size() == 1, "rig.scale never moves with curve/top (that is build/height)")
	# No primitive is added at any setting: colour blocking assigns regions off
	# this exact node set, and the Quest-3 budget is counted off these triangles.
	_check(tri_counts.size() == 1, "the shape axes add no geometry at any setting")

	get_root().remove_child(a)
	a.free()


## Merged extents of the three torso blocks the shape axes drive, in Body-local
## space. Deliberately NOT the whole-rig AABB: the arms and hands sit further
## out in X than any hip flare reaches, so a whole-rig box is dominated by
## geometry these axes never touch and would report "nothing moved" for a change
## that is plainly visible on screen.
## body.legs / body.arms / body.limb — limb LENGTH (x2) and limb GIRTH.
##
## Same three claims as _test_body_shape_axes, plus the two this round adds:
##   4. a longer leg keeps the FOOT ON THE FLOOR and lifts the hips instead —
##      the whole reason _hip_lift exists, and the thing that silently breaks if
##      a Y literal above the hips is ever added without the lift, and
##   5. the furry lane's leg band is TIGHTER than the humanoid one. That is the
##      "reasonable constraints for humanoid or furry type" requirement, and it
##      is asserted rather than merely commented because a digitigrade leg is a
##      three-segment raked chain that stops reading as digitigrade when it is
##      stretched as hard as a single humanoid capsule.
##
## Claim 1 is checked on THREE families deliberately: canid (digitigrade),
## humanoid (plantigrade) and robot (its own strut primitives). The curve/top
## round shipped inert on slime and robot precisely because only the default
## branch was covered, so "every branch answers the slider" is now a test.
func _test_body_proportion_axes() -> void:
	var a := CharacterAssembler.new()
	get_root().add_child(a)

	# 1. Each axis is VISIBLE, on every family that has limbs to move.
	for family in ["canid", "humanoid", "robot"]:
		for axis in ["legs", "arms", "limb"]:
			var seen := {}
			for value in [0.0, 0.5, 1.0]:
				var cfg := Char3DConfig.defaults()
				cfg.body.archetype = family
				cfg.head.family = family
				cfg.body[axis] = value
				a.apply_config(cfg)
				var key := snappedf(_limb_signature(a), 0.0005)
				_check(not seen.has(key),
						"%s body.%s %.1f moves the limbs (distinct from %s)"
						% [family, axis, value, seen.get(key, "-")])
				seen[key] = "%.1f" % value
			_check(seen.size() == 3,
					"all three %s body.%s stops are visually distinct" % [family, axis])

	# 2. The centred stop is EXACT — these are the literals _build_body, _leg and
	#    _arm hardcoded before the axes existed.
	var mid := Char3DConfig.defaults()
	mid.body.archetype = "humanoid"
	mid.head.family = "humanoid"
	a.apply_config(mid)
	var body: Node3D = a.get_node("Rig/Body")
	var leg: MeshInstance3D = body.get_node("LegL")
	_check(is_equal_approx(leg.position.y, 0.32), "legs 0.5 keeps the original Leg y (0.32)")
	_check(is_equal_approx((leg.mesh as CapsuleMesh).height, 0.5),
			"legs 0.5 keeps the original Leg height (0.5)")
	_check(is_equal_approx((leg.mesh as CapsuleMesh).radius, 0.09),
			"limb 0.5 keeps the original Leg radius (0.09)")
	var hips: MeshInstance3D = body.get_node("Hips")
	_check(is_equal_approx(hips.position.y, 0.62), "legs 0.5 keeps the original Hips y (0.62)")
	var hand: MeshInstance3D = body.get_node("HandL")
	_check(is_equal_approx(hand.position.y, 0.68), "arms 0.5 keeps the original Hand y (0.68)")

	# 3. MIGRATION GUARD — a definition written before these keys existed must be
	#    indistinguishable from the centred stop, forever.
	var centred := _limb_signature(a)
	var legacy := Char3DConfig.defaults()
	legacy.body.archetype = "humanoid"
	legacy.head.family = "humanoid"
	legacy.body.erase("legs")
	legacy.body.erase("arms")
	legacy.body.erase("limb")
	_check(not legacy.body.has("legs") and not legacy.body.has("limb"),
			"the legacy fixture really is missing the proportion keys")
	a.apply_config(legacy)
	_check(is_equal_approx(_limb_signature(a), centred),
			"a definition omitting legs/arms/limb renders the SAME as 0.5")

	# 4. FEET STAY ON THE FLOOR. A longer leg must raise the hips, not bury the
	#    foot — measured as the lowest point of the leg, which may not move.
	var floors := []
	for value in [0.0, 0.5, 1.0]:
		var cfg := Char3DConfig.defaults()
		cfg.body.archetype = "humanoid"
		cfg.head.family = "humanoid"
		cfg.body.legs = value
		a.apply_config(cfg)
		var l: MeshInstance3D = a.get_node("Rig/Body/LegL")
		var box: AABB = l.transform * l.mesh.get_aabb()
		floors.append(snappedf(box.position.y, 0.0005))
	_check(floors[0] == floors[1] and floors[1] == floors[2],
			"the leg's bottom is fixed at every legs stop (feet stay on the floor): %s" % [floors])

	# 5. The furry lane is CONSTRAINED TIGHTER than the humanoid one. Compare the
	#    full sweep of each: the digitigrade leg must travel LESS.
	var travel := {}
	for family in ["humanoid", "canid"]:
		var span := []
		for value in [0.0, 1.0]:
			var cfg := Char3DConfig.defaults()
			cfg.body.archetype = family
			cfg.head.family = family
			cfg.body.legs = value
			a.apply_config(cfg)
			span.append(a.get_node("Rig/Body/Hips").position.y)
		travel[family] = absf(span[1] - span[0])
	_check(travel["canid"] < travel["humanoid"],
			"the digitigrade (furry) leg band is tighter than the plantigrade one: %.4f < %.4f"
			% [travel["canid"], travel["humanoid"]])

	a.queue_free()


## One number that changes whenever any limb moves or thickens: the summed
## y-position and radius of the limb primitives that exist on this rig. A whole
## -rig AABB is useless here because the head dominates it and never moves with
## these axes.
func _limb_signature(a: CharacterAssembler) -> float:
	var body: Node3D = a.get_node("Rig/Body")
	var total := 0.0
	for name in ["LegL", "LegR", "ArmL", "ArmR", "HandL", "ThighL", "ShinL", "AnkleL", "ForearmL"]:
		if not body.has_node(name):
			continue
		var mi: MeshInstance3D = body.get_node(name)
		total += mi.position.y * 3.0
		var m: Mesh = mi.mesh
		if m is CapsuleMesh:
			total += (m as CapsuleMesh).height + (m as CapsuleMesh).radius * 7.0
		elif m is CylinderMesh:
			total += (m as CylinderMesh).height + (m as CylinderMesh).top_radius * 7.0
		elif m is SphereMesh:
			total += (m as SphereMesh).radius * 7.0
	return total


func _torso_aabb(a: CharacterAssembler) -> AABB:
	var body: Node3D = a.get_node("Rig/Body")
	var out := AABB()
	var seeded := false
	for part in ["Hips", "Belly", "Chest"]:
		var mi: MeshInstance3D = body.get_node(part)
		var box: AABB = mi.transform * mi.mesh.get_aabb()
		out = out.merge(box) if seeded else box
		seeded = true
	return out


## THE INERT-FAMILY GUARD, and the reason it exists: the shape axes first
## shipped inside _build_body's DEFAULT branch only, so "slime" and "robot" —
## the two families that assemble their own primitives — ignored both sliders
## completely. For 2 of the 29 selectable families Curve and Top were pure
## decoration, with nothing in the UI admitting it. Every other test in this
## file happens to drive a default-branch family, so nothing went red.
##
## Hence a sweep of ALL of Char3DConfig.FAMILIES rather than a sample: the day
## some future family earns its own branch and forgets the axes, this fails on
## the commit that does it. That is the only durable fix for the class of bug —
## the per-branch assertions below just pin today's two special cases.
func _test_shape_axes_reach_every_family() -> void:
	var a := CharacterAssembler.new()
	get_root().add_child(a)

	for fam in Char3DConfig.FAMILIES:
		for axis in ["curve", "top"]:
			var sigs := {}
			var tris := {}
			for value in [0.0, 0.5, 1.0]:
				var cfg := Char3DConfig.defaults()
				cfg.head.family = fam
				cfg.body[axis] = value
				a.apply_config(cfg)
				sigs[_head_signature(a.get_node("Rig/Body"))] = true
				tris[_triangles(a)] = true
			_check(sigs.size() == 3,
					"family '%s': body.%s reshapes the body at all three stops" % [fam, axis])
			# Same budget contract the default branch carries: an axis
			# re-proportions what is already there, it never adds a primitive.
			_check(tris.size() == 1,
					"family '%s': body.%s adds no geometry at any setting" % [fam, axis])

	# The centred stop is still the PRE-AXIS silhouette in the two hand-built
	# branches, exactly as it is in the default one. Same literals _build_body
	# hardcoded before the axes reached them — float32 scale/position storage is
	# what makes the centred midpoints land on the identical bits.
	var slime := Char3DConfig.defaults()
	slime.head.family = "slime"
	a.apply_config(slime)
	var slime_body: Node3D = a.get_node("Rig/Body")
	_check((slime_body.get_node("Base") as MeshInstance3D).scale == Vector3(1.15, 0.75, 1.05),
			"slime curve 0.5 keeps the original Base scale")
	_check((slime_body.get_node("Torso") as MeshInstance3D).scale == Vector3(1.0, 1.1, 0.95),
			"slime top 0.5 keeps the original Torso scale")

	var robot := Char3DConfig.defaults()
	robot.head.family = "robot"
	a.apply_config(robot)
	var robot_body: Node3D = a.get_node("Rig/Body")
	# The robot's blocks carried NO scale argument before the axes, so "unchanged"
	# here means literally Vector3.ONE, not a preserved literal.
	_check((robot_body.get_node("Hips") as MeshInstance3D).scale == Vector3.ONE,
			"robot curve 0.5 leaves the Hips chassis unscaled, as it was")
	var robot_torso: MeshInstance3D = robot_body.get_node("Torso")
	_check(robot_torso.scale == Vector3.ONE, "robot top 0.5 leaves the Torso chassis unscaled")
	_check(robot_torso.position == Vector3(0, 0.98, 0),
			"robot top 0.5 keeps the original Torso position")
	# The panel's z is now derived from the torso's depth instead of hardcoded,
	# so the centred stop has to land back on the literal it replaced, to the bit.
	_check((robot_body.get_node("Panel") as MeshInstance3D).position == Vector3(0, 1.0, 0.145),
			"robot top 0.5 keeps the original Panel position")
	# ...and it really does ride the face at the extremes, which is the whole
	# reason it is derived: a fixed z sinks into the chest at top 1.0.
	for value in [0.0, 1.0]:
		var cfg := Char3DConfig.defaults()
		cfg.head.family = "robot"
		cfg.body.top = value
		a.apply_config(cfg)
		var b: Node3D = a.get_node("Rig/Body")
		var torso: MeshInstance3D = b.get_node("Torso")
		var panel: MeshInstance3D = b.get_node("Panel")
		var face_z: float = torso.position.z + 0.14 * torso.scale.z
		_check(panel.position.z > face_z and panel.position.z < face_z + 0.02,
				"robot top %.1f keeps the Panel proud of the chest face" % value)

	# THE MIGRATION GUARD, per branch. Every slime/robot stored before today has
	# no curve/top in its jsonb definition and the BFF has no migration
	# mechanism, so "absent" has to keep rendering as 0.5/0.5 forever.
	for fam in ["slime", "robot"]:
		var centred := Char3DConfig.defaults()
		centred.head.family = fam
		centred.body.curve = 0.5
		centred.body.top = 0.5
		a.apply_config(centred)
		var centred_sig := _head_signature(a.get_node("Rig/Body"))
		var legacy := Char3DConfig.defaults()
		legacy.head.family = fam
		legacy.body.erase("curve")
		legacy.body.erase("top")
		_check(not legacy.body.has("curve") and not legacy.body.has("top"),
				"the legacy '%s' fixture really omits both keys" % fam)
		a.apply_config(legacy)
		_check(_head_signature(a.get_node("Rig/Body")) == centred_sig,
				"a '%s' definition omitting curve/top renders exactly as 0.5/0.5" % fam)

	get_root().remove_child(a)
	a.free()


# ── VRM ingest ───────────────────────────────────────────────────────────────

func _test_vrm_addon_vendored() -> void:
	_check(CharForgeVrmIngest.available(), "the vendored godot-vrm addon is present")
	_check(FileAccess.file_exists("res://addons/vrm/LICENSE"),
			"the addon's MIT LICENSE is kept in-tree")
	_check(FileAccess.file_exists("res://addons/Godot-MToon-Shader/LICENSE"),
			"the MToon shader's LICENSE is kept in-tree")
	# The manifest is the machine-readable half of the licence discipline.
	var manifest_text := FileAccess.get_file_as_string("res://addons/THIRD_PARTY_MANIFEST.json")
	var manifest: Variant = JSON.parse_string(manifest_text)
	_check(manifest is Dictionary and manifest.entries.size() >= 2,
			"THIRD_PARTY_MANIFEST.json lists the vendored trees")
	var paths := {}
	for entry in manifest.entries:
		paths[entry.path] = true
		for field in ["licenseSpdx", "attribution", "sourceUrl", "retrievedAt"]:
			_check(entry.has(field) and not String(entry[field]).is_empty(),
					"manifest entry '%s' carries %s" % [entry.path, field])
		_check(entry.licenseSpdx in CharForgeRegistry.ALLOWED_LICENSES,
				"'%s' is licensed under an allow-listed id (%s)" % [entry.path, entry.licenseSpdx])
	_check(paths.has("addons/vrm/") and paths.has("addons/Godot-MToon-Shader/"),
			"both vendored trees are declared")
	# The non-CC sample models must NOT have come along with the addon.
	_check(not DirAccess.dir_exists_absolute("res://addons/vrm/vrm_samples"),
			"the non-CC vrm_samples models were NOT vendored")

	# MToon detection drives the house-shader swap on web.
	var mtoon := ShaderMaterial.new()
	mtoon.resource_name = "Body_MToon"
	_check(CharForgeVrmIngest.is_mtoon(mtoon), "an MToon-named material is detected")
	var house := CharForgeMaterials.for_config(Char3DConfig.defaults()).region("fur")
	_check(not CharForgeVrmIngest.is_mtoon(house), "a house material is not mistaken for MToon")
	_check(not CharForgeVrmIngest.is_mtoon(null), "a null material is tolerated")

	var mats := CharForgeMaterials.for_config(Char3DConfig.defaults())
	var mesh := BoxMesh.new()
	var authored := ShaderMaterial.new()
	authored.resource_name = "hm_hair_MToon"
	mesh.surface_set_material(0, authored)
	var mi := MeshInstance3D.new()
	mi.mesh = mesh
	var swapped := CharForgeVrmIngest.swap_mtoon(mi, mats, CharForgeGlbLoader.normalize_slot_map({}))
	_check(swapped == 1, "the MToon surface was swapped for the house shader")
	_check(mi.get_surface_override_material(0) == mats.region("hair"),
			"the swapped surface picked up the hair region from its slot name")

	var plain := BoxMesh.new()
	var plain_mat := StandardMaterial3D.new()
	plain_mat.resource_name = "hm_fur"
	plain.surface_set_material(0, plain_mat)
	var mi2 := MeshInstance3D.new()
	mi2.mesh = plain
	_check(CharForgeVrmIngest.swap_mtoon(mi2, mats, CharForgeGlbLoader.normalize_slot_map({})) == 0,
			"a non-MToon surface is left for the normal loader fixup")
	# The swap policy: web ALWAYS gets the house shader (MToon on WebGL2 is
	# undocumented upstream — technique.md gap); desktop previews keep MToon so
	# the imported look stays comparable against ours.
	_check(CharForgeVrmIngest.should_swap_on_this_platform() == OS.has_feature("web"),
			"MToon is swapped for the house shader on web and only on web")
	mi.free()
	mi2.free()


## A minimal mesh carrying real blend shapes. ArrayMesh requires every surface
## to supply one array set PER declared blend shape, so SurfaceTool.commit()
## cannot be used against a mesh that already has shapes.
func _blend_shape_mesh(shape_names: Array) -> ArrayMesh:
	var st := SurfaceTool.new()
	st.begin(Mesh.PRIMITIVE_TRIANGLES)
	for v in [Vector3.ZERO, Vector3.RIGHT, Vector3.UP]:
		st.set_normal(Vector3.BACK)
		st.add_vertex(v)
	var base: ArrayMesh = st.commit()
	var arrays: Array = base.surface_get_arrays(0)
	var mesh := ArrayMesh.new()
	for name in shape_names:
		mesh.add_blend_shape(name)
	var blends: Array = []
	for i in range(shape_names.size()):
		var shape: Array = []
		shape.resize(Mesh.ARRAY_MAX)
		shape[Mesh.ARRAY_VERTEX] = arrays[Mesh.ARRAY_VERTEX]
		shape[Mesh.ARRAY_NORMAL] = arrays[Mesh.ARRAY_NORMAL]
		shape[Mesh.ARRAY_TANGENT] = arrays[Mesh.ARRAY_TANGENT]
		blends.append(shape)
	mesh.add_surface_from_arrays(Mesh.PRIMITIVE_TRIANGLES, arrays, blends)
	return mesh


# ── Mouth-strip fallback plumbing ────────────────────────────────────────────

func _test_mouth_quad_factory() -> void:
	var quad := CharForgeLipSync.make_mouth_quad(0.12, 0.05)
	_check(quad is MeshInstance3D and quad.mesh is QuadMesh, "the mouth quad is a QuadMesh")
	_check((quad.mesh as QuadMesh).size.is_equal_approx(Vector2(0.12, 0.05)),
			"the quad is sized as requested")
	var mat := CharForgeLipSync.mouth_quad_material(quad)
	_check(mat != null and mat.shader == CharForgeLipSync.MOUTH_STRIP_SHADER,
			"the quad carries the mouth-strip shader")
	_check(mat.get_shader_parameter("frame_index") == 0, "the quad starts on the closed frame")
	_check(CharForgeLipSync.mouth_quad_material(null) == null, "a null quad yields no material")

	# Driving it end to end: the quad the factory built is what the driver moves.
	var sync := CharForgeLipSync.new()
	sync.bind_mouth_strip(mat)
	sync.set_openness(0.6)
	_check(mat.get_shader_parameter("frame_index") == 3,
			"the factory quad is driven by the amplitude contract")
	# Degenerate size must not produce a zero-area quad.
	var tiny := CharForgeLipSync.make_mouth_quad(0.0, -1.0)
	_check((tiny.mesh as QuadMesh).size.x > 0.0 and (tiny.mesh as QuadMesh).size.y > 0.0,
			"a degenerate size is clamped to something drawable")
	quad.free()
	tiny.free()


# ── Runtime GLB loader, end to end ───────────────────────────────────────────

## Builds a real GLB in memory (GLTFDocument.append_from_scene +
## generate_buffer) and pushes it back through the loader, so the parse + the
## whole mandatory fixup pass are exercised against actual glTF bytes rather
## than against a hand-made node tree. No asset is vendored to do this, which
## matters: the licence discipline forbids committing third-party GLBs, so a
## synthesized one is the only honest way to test this path in-repo.
func _test_glb_round_trip() -> void:
	var source := Node3D.new()
	source.name = "Rig"
	var box := BoxMesh.new()
	var authored := StandardMaterial3D.new()
	authored.resource_name = "hm_iris"
	box.surface_set_material(0, authored)
	var mi := MeshInstance3D.new()
	mi.name = "Body"
	mi.mesh = box
	source.add_child(mi)
	mi.owner = source
	get_root().add_child(source)

	var doc := GLTFDocument.new()
	var state := GLTFState.new()
	var export_err := doc.append_from_scene(source, state)
	_check(export_err == OK, "the GLB fixture exported cleanly")
	var bytes := doc.generate_buffer(state)
	_check(bytes.size() > 0, "the GLB fixture has bytes (%d)" % bytes.size())
	get_root().remove_child(source)
	source.free()

	var loader := CharForgeGlbLoader.new()
	get_root().add_child(loader)
	var failures: Array = []
	loader.load_failed.connect(func(reason): failures.append(reason))
	var loaded_roots: Array = []
	loader.loaded.connect(func(root): loaded_roots.append(root))

	var mats := CharForgeMaterials.for_config(Char3DConfig.defaults())
	var entry := {"slots": {"hm_iris": "iris"}}
	var root: Node3D = await loader.load_from_buffer(bytes, mats, entry)
	_check(root != null, "real GLB bytes parsed into a Node3D")
	_check(failures.is_empty(), "a good load emits no failure")
	_check(loaded_roots.size() == 1 and loaded_roots[0] == root, "the loaded signal carried the root")
	if root != null:
		var rebuilt: MeshInstance3D = null
		for node in [root] + _descendants(root):
			if node is MeshInstance3D:
				rebuilt = node as MeshInstance3D
				break
		_check(rebuilt != null, "the mesh survived the round trip")
		if rebuilt != null:
			var applied := rebuilt.get_surface_override_material(0)
			_check(applied == mats.region("iris"),
					"fixup (b) rebound the hm_iris surface to the shared iris material")
			_check(applied.get_shader_parameter("palette_tex") == mats.palette_texture,
					"the rebound surface samples the avatar's shared palette texture")
		root.free()

	# Failure paths must FAIL, not half-load.
	failures.clear()
	_check(await loader.load_from_buffer(PackedByteArray(), mats, entry) == null,
			"an empty buffer is rejected")
	_check(failures.size() == 1 and failures[0].contains("empty"), "the empty buffer reports why")

	failures.clear()
	var garbage := PackedByteArray()
	garbage.resize(64)
	garbage.fill(0x41)
	_check(await loader.load_from_buffer(garbage, mats, entry) == null, "garbage bytes are rejected")
	_check(failures.size() == 1, "garbage reports exactly one failure")

	failures.clear()
	var oversize := PackedByteArray()
	oversize.resize(CharForgeGlbLoader.MAX_BYTES + 1)
	_check(await loader.load_from_buffer(oversize, mats, entry) == null,
			"an asset over the runtime cap is rejected BEFORE it is parsed")
	_check(failures.size() == 1 and failures[0].contains("cap"),
			"the oversize rejection names the cap")

	# FIXUP (d): the offscreen pre-warm frame must hand the node back to the
	# parent it came from, not strand it inside the throwaway SubViewport.
	var warm_host := Node3D.new()
	get_root().add_child(warm_host)
	var subject := Node3D.new()
	subject.name = "PrewarmSubject"
	warm_host.add_child(subject)
	await loader.prewarm(subject)
	_check(is_instance_valid(subject), "pre-warm did not destroy the node")
	_check(subject.get_parent() == warm_host,
			"pre-warm returned the node to its original parent")
	_check(loader.get_child_count() == 0, "the pre-warm SubViewport was cleaned up")
	get_root().remove_child(warm_host)
	warm_host.free()

	# VRM ingest: registration is PROCESS-GLOBAL, so a failed VRM parse must not
	# leave the extension installed and change how the next plain GLB parses.
	_check(CharForgeVrmIngest.parse(PackedByteArray()) == null, "an empty VRM buffer is rejected")
	_check(CharForgeVrmIngest.parse(garbage) == null, "VRM garbage is rejected")
	var after: Node3D = await loader.load_from_buffer(bytes, mats, entry)
	_check(after != null,
			"a plain GLB still parses after a failed VRM ingest (the extension was unregistered)")
	if after != null:
		after.free()

	get_root().remove_child(loader)
	loader.free()


func _descendants(node: Node) -> Array:
	var out: Array = []
	for child in node.get_children():
		out.append(child)
		out.append_array(_descendants(child))
	return out


# ── Shaders ──────────────────────────────────────────────────────────────────

## Godot runs the shader PREPROCESSOR and the ShaderLanguage parse when the
## resource loads, even under the headless dummy rasterizer — so the include
## graph, the transparent #define split and the uniform contract are all
## genuinely verifiable here. What is NOT verifiable locally is the GLSL
## compile on WebGL2/Quest; technique.md already flags that as an on-device
## smoke test, and no assertion here should be read as covering it.
func _test_shader_contract() -> void:
	var opaque: Shader = CharForgeMaterials.HOUSE_SHADER
	var trans: Shader = CharForgeMaterials.HOUSE_TRANS_SHADER
	var core := "res://shaders/house_toon_core.gdshaderinc"
	_check(FileAccess.file_exists(core), "the shared house-toon core include exists")

	var opaque_uniforms := {}
	for u in opaque.get_shader_uniform_list():
		opaque_uniforms[u.name] = true
	var trans_uniforms := {}
	for u in trans.get_shader_uniform_list():
		trans_uniforms[u.name] = true

	# If the #include failed to resolve, the uniform list would be empty — so
	# this is a real assertion that the preprocessor stitched the core in.
	for name in ["palette_tex", "palette_cell", "albedo_tint", "band_cuts", "band_softness",
			"shadow_tone", "rim_color", "rim_strength", "rim_power", "matcap_tex",
			"matcap_strength", "emission_boost"]:
		_check(opaque_uniforms.has(name), "the opaque house shader exposes '%s'" % name)
		_check(trans_uniforms.has(name), "the transparent variant exposes '%s' too" % name)
	_check(trans_uniforms.has("alpha_mul"),
			"HOUSE_TOON_TRANSPARENT adds the alpha uniform to the transparent variant")
	_check(not opaque_uniforms.has("alpha_mul"),
			"the opaque variant has NO alpha uniform — it must never render transparent")
	_check(opaque_uniforms.size() + 1 == trans_uniforms.size(),
			"alpha_mul is the ONLY difference between the two variants")
	_check(opaque.get_mode() == Shader.MODE_SPATIAL and trans.get_mode() == Shader.MODE_SPATIAL,
			"both house variants are spatial shaders")

	var outline_uniforms := {}
	for u in CharForgeMaterials.OUTLINE_SHADER.get_shader_uniform_list():
		outline_uniforms[u.name] = true
	_check(outline_uniforms.has("outline_width") and outline_uniforms.has("outline_color"),
			"the inverted-hull outline exposes its width/colour uniforms")
	var strip_uniforms := {}
	for u in CharForgeLipSync.MOUTH_STRIP_SHADER.get_shader_uniform_list():
		strip_uniforms[u.name] = true
	_check(strip_uniforms.has("frame_index") and strip_uniforms.has("strip_tex"),
			"the mouth strip exposes frame_index + strip_tex")

	# COMPATIBILITY DISCIPLINE, asserted on the source: DIFFUSE_LIGHT must only
	# ever be ADDED to. Negative values clamp to black in the Compatibility
	# renderer, so any subtraction is a silent breakage on the only web
	# renderer we have.
	var source := FileAccess.get_file_as_string(core)
	_check(not source.is_empty(), "the core include is readable")
	var accumulates := false
	for raw_line in source.split("\n"):
		var line: String = raw_line.strip_edges()
		if line.begins_with("//") or not line.contains("DIFFUSE_LIGHT"):
			continue
		_check(not line.contains("DIFFUSE_LIGHT -="),
				"DIFFUSE_LIGHT is never subtracted from (Compatibility clamps negatives)")
		if line.contains("DIFFUSE_LIGHT +="):
			accumulates = true
	_check(accumulates, "the custom light() actually accumulates into DIFFUSE_LIGHT")
	_check(source.contains("filter_nearest"),
			"the palette sampler enforces nearest filtering AT THE SAMPLER")
	_check(source.contains("repeat_disable"), "the palette sampler disables repeat")


# ── Studio GUI wiring for the v2 surfaces ────────────────────────────────────

func _test_studio_v2_controls() -> void:
	var scene: PackedScene = load("res://scenes/studio.tscn")
	var studio := scene.instantiate()
	get_root().add_child(studio)
	# Every palette region — canonical AND extended — is editable over the surface
	# the host uses: a recolour lands on the config and the shared palette WITHOUT
	# rebuilding the rig.
	var head_before: Node = studio.assembler.get_node("Rig/Body/NeckTop/Head")
	var picked := Color(0.2, 0.4, 0.6)
	var hex := "#" + picked.to_html(false)
	_check(studio.assembler.recolor({"socks": hex}), "recolor accepts an extended region")
	_check(studio.assembler.get_node("Rig/Body/NeckTop/Head") == head_before,
			"a colour edit does not rebuild the rig")
	_check(studio.assembler.config.materials.get("socks") == hex,
			"the colour edit was stored on the config as a hex string")
	var cell: Vector2i = CharForgePalette.CELLS["socks"]
	_check(studio.assembler.materials.palette_image.get_pixel(cell.x, cell.y)
			.is_equal_approx(picked),
			"the colour edit reached the shared palette")
	_check(not CharForgeMaterials.outline_enabled, "outline quality starts OFF")
	_check(studio.get("_auto_rotate") == true, "the turntable spins until the host says otherwise")
	studio._on_bridge_auto_rotate(false)
	_check(studio.get("_auto_rotate") == false, "setAutoRotate over the bridge stops the turntable")
	get_root().remove_child(studio)
	studio.free()


# ── Rig adjustments (host-driven, live, persisted on the config) ─────────────
func _test_rig_sanitize_clamps_and_is_sparse() -> void:
	var rig := Char3DConfig.sanitize_rig({
		"eye_l": {"offset": [0.5, -0.5, 0.01], "scale": [3.0, 0.1, 1.0]},
		"jaw": {"offset": [0.0, 0.0, 0.0], "scale": [1.0, 1.0, 1.0]},
		"nose": {"offset": [0.01, 0.0, 0.0]},
		"brow_l": "garbage",
	})
	_check(rig.keys() == ["eye_l"], "only a joint carrying a real adjustment survives, got %s" % [rig.keys()])
	_check(rig.eye_l.offset == [Char3DConfig.RIG_OFFSET_MAX, -Char3DConfig.RIG_OFFSET_MAX, 0.01],
			"offsets clamp to ±RIG_OFFSET_MAX")
	_check(rig.eye_l.scale == [Char3DConfig.RIG_SCALE_MAX, Char3DConfig.RIG_SCALE_MIN, 1.0],
			"scales clamp to the rig scale range")
	var cfg := Char3DConfig.sanitize({"rig": {"jaw": {"offset": [0, 0, 0]}}})
	_check(not cfg.has("rig"), "an all-identity rig is not emitted into the canonical config")
	var cfg2 := Char3DConfig.sanitize({"rig": {"jaw": {"offset": [0, -0.02, 0]}}})
	_check(cfg2.has("rig") and cfg2.rig.jaw.offset[1] == -0.02, "a real rig round-trips through sanitize")


func _test_rig_applies_live_and_composes_with_expressions() -> void:
	var a := CharacterAssembler.new()
	get_root().add_child(a)
	a.apply_config(Char3DConfig.defaults())
	var head_before: Node = a.get_node("Rig/Body/NeckTop/Head")
	var eye: Node3D = a.rig_ref("eye_l")
	_check(eye != null, "the procedural head exposes eye_l")
	var base_pos: Vector3 = eye.position
	var base_scale: Vector3 = eye.scale
	a.set_rig({"eye_l": {"offset": [0.0, 0.05, 0.0], "scale": [1.0, 1.5, 1.0]}})
	_check(a.get_node("Rig/Body/NeckTop/Head") == head_before, "set_rig never rebuilds")
	_check(_approx(eye.position.y, base_pos.y + 0.05), "the offset moved the joint")
	a.set_expression("happy")
	_check(_approx(eye.position.y, base_pos.y + 0.05),
			"an expression change keeps the rig offset (deltas from the built base, not accumulated)")
	a.set_expression("neutral")
	a.set_rig({})
	_check(_approx(eye.position.y, base_pos.y) and _approx(eye.scale.y, base_scale.y),
			"clearing the rig restores the built pose (got y=%.4f sy=%.4f)" % [eye.position.y, eye.scale.y])
	_check(not a.config.has("rig"), "an empty rig leaves no key on the config")
	get_root().remove_child(a)
	a.free()


func _test_rig_auto_adjust_is_deterministic_and_bounded() -> void:
	var a := CharacterAssembler.new()
	get_root().add_child(a)
	a.apply_config(Char3DConfig.defaults())
	var first: Dictionary = a.auto_adjust_rig()
	var second: Dictionary = a.auto_adjust_rig()
	_check(first == second, "auto-adjust is deterministic for the same head")
	_check(not first.is_empty(), "auto-adjust proposes at least one joint on the procedural head")
	for joint in first:
		_check(joint in Char3DConfig.RIG_JOINTS, "auto-adjust only touches rig joints (%s)" % joint)
		for v in first[joint].offset:
			_check(absf(float(v)) <= Char3DConfig.RIG_OFFSET_MAX + 0.0001,
					"auto-adjust offsets stay inside the clamp (%s)" % joint)
	_check(a.config.get("rig", {}) == first, "auto-adjust lands on the config the host will save")
	get_root().remove_child(a)
	a.free()

func _test_blocking_measurement() -> void:
	# Exact surface areas: a 1x1x1 box is 6, and the basis is applied to the
	# triangle EDGES (not as an average scale factor), so stretching x by 2
	# gives a 2x1x1 box = 10, not 6*2.
	var box := BoxMesh.new()
	box.size = Vector3.ONE
	_check(_approx(CharForgeBlocking.mesh_area(box, Basis.IDENTITY), 6.0, 0.001),
			"a unit box measures 6.0 surface area")
	_check(_approx(CharForgeBlocking.mesh_area(box, Basis.IDENTITY.scaled(Vector3(2, 1, 1))),
			10.0, 0.001),
			"a non-uniformly scaled box measures its true area (10.0), not area x scale")

	# region_of: the material's resource_name is the region channel.
	var mi := MeshInstance3D.new()
	mi.mesh = box
	_check(CharForgeBlocking.region_of(mi) == "_other", "an unmaterialed mesh is _other")
	var mat := StandardMaterial3D.new()
	mat.resource_name = "charforge_socks"
	mi.material_override = mat
	_check(CharForgeBlocking.region_of(mi) == "socks", "region_of reads the charforge_ prefix")
	mat.resource_name = "charforge_bogus"
	_check(CharForgeBlocking.region_of(mi) == "_other",
			"a charforge_ name that is not a real region is _other, not a new region")
	mat.resource_name = "some_imported_material"
	_check(CharForgeBlocking.region_of(mi) == "_other", "a foreign material name is _other")
	mi.free()

	# Shares over a hand-built two-mesh rig: 1 unit box (fur) + 1 box scaled 2x
	# in every axis (accent, area 24) => 6/30 and 24/30.
	var root := Node3D.new()
	var fur_mat := StandardMaterial3D.new()
	fur_mat.resource_name = "charforge_fur"
	var accent_mat := StandardMaterial3D.new()
	accent_mat.resource_name = "charforge_accent"
	var a_node := MeshInstance3D.new()
	a_node.mesh = box
	a_node.material_override = fur_mat
	root.add_child(a_node)
	var b_node := MeshInstance3D.new()
	b_node.mesh = box
	b_node.material_override = accent_mat
	b_node.scale = Vector3(2, 2, 2)
	root.add_child(b_node)
	var shares := CharForgeBlocking.measure(root)
	_check(_approx(float(shares.fur), 0.2, 0.001) and _approx(float(shares.accent), 0.8, 0.001),
			"measure returns true area shares (got fur=%.3f accent=%.3f)"
			% [shares.get("fur", -1.0), shares.get("accent", -1.0)])
	# The scoping knob the skin band depends on.
	var scoped := CharForgeBlocking.measure(root, [b_node])
	_check(_approx(float(scoped.fur), 1.0, 0.001) and not scoped.has("accent"),
			"measure(skip) excludes a whole subtree")
	_check(CharForgeBlocking.measure(Node3D.new()).is_empty(),
			"a rig with no geometry measures {} rather than dividing by zero")
	root.free()

	# cloth_shares renormalises the three garment regions against EACH OTHER.
	var cloth := CharForgeBlocking.cloth_shares(
			{"fur": 0.3, "accent": 0.15, "detail": 0.05, "skin": 0.5})
	_check(_approx(float(cloth.fur), 0.6) and _approx(float(cloth.accent), 0.3)
			and _approx(float(cloth.detail), 0.1),
			"cloth_shares renormalises fur/accent/detail against the cloth total")
	_check(CharForgeBlocking.cloth_shares({"skin": 1.0}).is_empty(),
			"cloth_shares is empty when nothing is clothed")

	# The graders must REJECT the exact shape of the bug they exist for: a rig
	# whose accent and detail regions have literally zero area.
	var bare := CharForgeBlocking.check_jrpg({"skin": 0.85, "hair": 0.15})
	_check(not bare.ok and String(bare.reasons[0]).contains("no cloth region"),
			"check_jrpg rejects an avatar with no garment regions at all")
	var overskin := CharForgeBlocking.check_jrpg({"fur": 0.3, "accent": 0.15, "detail": 0.05, "skin": 0.5})
	_check(not overskin.ok, "check_jrpg rejects a 50%-skin body")
	var no_socks := CharForgeBlocking.check_kemono(
			{"fur": 0.9, "innerMuzzle": 0.05, "innerEar": 0.05})
	_check(not no_socks.ok and String(", ".join(no_socks.reasons)).contains("socks"),
			"check_kemono names the missing colour block")
	_check(CharForgeBlocking.check_kemono({"fur": 0.2, "innerMuzzle": 0.3, "socks": 0.3,
			"innerEar": 0.2}).ok == false,
			"check_kemono rejects a coat that is no longer dominant")


## jrpg-bases.md: "line = darkest + outline color".
func _test_line_is_darkest() -> void:
	var ok := {"fur": "#8b5a2b", "accent": "#00bcd4", "detail": "#d2b48c",
			"line": "#090806", "iris": "#ff4f79", "skin": "#ffdbac", "hair": "#71635a"}
	_check(CharForgeBlocking.is_line_darkest(ok), "a palette with the darkest line passes")
	_check(CharForgeBlocking.enforce_line_darkest(ok).line == ok.line,
			"enforce_line_darkest leaves a compliant palette alone")

	# The real failure the randomizer produced: near-black hair under a brown line.
	var bad := ok.duplicate()
	bad.line = "#4a3728"
	bad.hair = "#090806"
	_check(not CharForgeBlocking.is_line_darkest(bad),
			"a palette whose hair is darker than the line fails")
	var fixed := CharForgeBlocking.enforce_line_darkest(bad)
	_check(CharForgeBlocking.is_line_darkest(fixed),
			"ONE pass of enforce_line_darkest fixes it (it picks the darkest offender)")
	_check(bad.line == "#4a3728", "enforce_line_darkest does not mutate its argument")
	var fixed_color := Color.from_string(String(fixed.line), Color.WHITE)
	_check(fixed_color.get_luminance() < Color.from_string("#090806", Color.WHITE).get_luminance(),
			"the corrected line is darker than the offender it was derived from")
	_check(fixed_color.s > 0.0 or fixed_color.v > 0.0,
			"the corrected line keeps a palette colour rather than collapsing to pure black")

	# Two offenders at once: the darkest must be the one that is beaten.
	var two := ok.duplicate()
	two.line = "#8b8b8b"
	two.detail = "#404040"
	two.hair = "#101010"
	_check(CharForgeBlocking.is_line_darkest(CharForgeBlocking.enforce_line_darkest(two)),
			"one pass suffices when several regions are darker than the line")

	# Regions the palette omits derive from present ones and only ever lighten,
	# so an absent extended region must not be able to fail the rule.
	var partial := {"fur": "#8b5a2b", "accent": "#00bcd4", "line": "#090806"}
	_check(CharForgeBlocking.is_line_darkest(partial),
			"absent extended regions do not fail the rule")
	_check(CharForgeBlocking.is_line_darkest({"fur": "#8b5a2b"}),
			"a palette with no line at all is vacuously compliant")
	_check(CharForgeBlocking.enforce_line_darkest({"fur": "#8b5a2b"}).has("line") == false,
			"enforce_line_darkest does not invent a line for a palette that has none")

	# END TO END: every seeded character the randomizer can produce. 65 of these
	# 400 violated the rule before the correction was wired in.
	var violations := 0
	var first := ""
	var curated := 0
	for seed_value in range(0, 400):
		var cfg := CharRandomizer.randomize_config(seed_value)
		if not CharForgeBlocking.is_line_darkest(cfg.materials):
			violations += 1
			if first.is_empty():
				first = "seed %d -> %s" % [seed_value, cfg.materials]
		if cfg.materials.line in ["#2f2f2f", "#090806", "#4a3728"]:
			curated += 1
	_check(violations == 0,
			"all 400 seeded randomizer palettes keep line darkest (%d violations, first: %s)"
			% [violations, first])
	# ...and the correction is TARGETED: the great majority of seeds draw a line
	# that already wins and must keep their curated colour untouched.
	_check(curated >= 300,
			"the line correction only fires where it is needed (%d/400 seeds kept a curated line)"
			% curated)


## The shipped avatars must actually satisfy the blocking rules of their lane —
## the point of the whole pass. Before it, a default humanoid measured
## skin=0.91 with accent and detail at EXACTLY zero.
func _test_blocked_avatars_pass_their_lane() -> void:
	var a := CharacterAssembler.new()
	get_root().add_child(a)

	for row in [["standard kemono", "canid", 1.0, "kemono"],
			["chibi kemono", "canid", 0.6, "kemono"],
			["hoofed kemono", "equine", 1.0, "kemono"],
			["heroic JRPG", "humanoid", 1.0, "jrpg"],
			["chibi JRPG", "humanoid", 0.6, "jrpg"]]:
		var cfg := Char3DConfig.defaults()
		cfg.head.family = row[1]
		cfg.body.archetype = row[1]
		cfg.body.height = row[2]
		a.apply_config(cfg)
		var report := a.blocking_report()
		_check(report.get("lane", "") == row[3],
				"%s takes the %s blocking lane" % [row[0], row[3]])
		_check(bool(report.get("ok", false)),
				"%s satisfies its lane's colour-blocking rules (%s)"
				% [row[0], ", ".join(report.check.reasons) if report.has("check") else "no report"])

	# The regression itself, stated directly: the three garment regions and the
	# kemono colour blocks are PRESENT, not merely declared in the palette.
	var hero := Char3DConfig.defaults()
	hero.head.family = "humanoid"
	hero.body.archetype = "humanoid"
	a.apply_config(hero)
	var body_shares: Dictionary = a.blocking_report().bodyShares
	for region in ["fur", "accent", "detail", "skin"]:
		_check(float(body_shares.get(region, 0.0)) > 0.0,
				"a default hero renders real geometry in the '%s' region (%.4f)"
				% [region, body_shares.get(region, 0.0)])
	# Re-regioning, not layering: the torso IS the garment.
	_check(CharForgeBlocking.region_of(a.get_node("Rig/Body/Belly") as MeshInstance3D) == "fur",
			"the hero's torso renders as the dominant garment region")
	_check(CharForgeBlocking.region_of(a.get_node("Rig/Body/HandL") as MeshInstance3D) == "skin",
			"the hero's hands stay bare skin")
	_check(CharForgeBlocking.region_of(a.get_node("Rig/Body/ForearmL") as MeshInstance3D) == "skin",
			"the short sleeve leaves the forearm bare")
	_check(a.get_node_or_null("Rig/Body/Belt") != null
			and a.get_node_or_null("Rig/Body/Collar") != null,
			"the JRPG detail trim is built")

	# kemono: socks/gloves and the countershade block exist WITHOUT any markings
	# part being chosen (they used to require marking_sock_01).
	a.apply_config(Char3DConfig.defaults())
	_check(a.config.parts.markings == null, "the kemono default config has no markings part")
	_check(CharForgeBlocking.region_of(a.get_node("Rig/Body/PawL") as MeshInstance3D) == "socks",
			"a default kemono has sock paws")
	_check(CharForgeBlocking.region_of(a.get_node("Rig/Body/HandL") as MeshInstance3D) == "socks",
			"a default kemono has glove hands")
	_check(CharForgeBlocking.region_of(a.get_node("Rig/Body/Countershade") as MeshInstance3D)
			== "innerMuzzle",
			"the chest/belly countershade shares the inner-muzzle block")
	# Hoofed kemono get the sock block as a fetlock cuff, never by recolouring
	# the hoof (which stays linework).
	var hoofed := Char3DConfig.defaults()
	hoofed.head.family = "equine"
	hoofed.body.archetype = "equine"
	a.apply_config(hoofed)
	_check(CharForgeBlocking.region_of(a.get_node("Rig/Body/FetlockL") as MeshInstance3D) == "socks",
			"a hoofed kemono wears its sock block as a fetlock cuff")
	_check(CharForgeBlocking.region_of(a.get_node("Rig/Body/HoofL") as MeshInstance3D) == "line",
			"the hoof itself stays linework")

	# LEGACY FAMILIES ARE UNTOUCHED — the additive discipline the whole of v2
	# is built on.
	var legacy := Char3DConfig.defaults()
	legacy.head.family = "moth"
	legacy.body.archetype = "moth"
	a.apply_config(legacy)
	_check(a.blocking_report().is_empty(),
			"a legacy family reports no blocking lane (the research states none)")
	_check(a.get_node_or_null("Rig/Body/Belt") == null
			and a.get_node_or_null("Rig/Body/Countershade") == null
			and a.get_node_or_null("Rig/Body/ForearmL") == null,
			"no blocking geometry is added to a legacy rig")
	for part in ["Hips", "Belly", "Chest", "ArmL", "LegL"]:
		_check(CharForgeBlocking.region_of(a.get_node("Rig/Body/" + part) as MeshInstance3D) == "fur",
				"legacy body part '%s' keeps the pre-v2 body region" % part)

	get_root().remove_child(a)
	a.free()


## technique.md budgets ~5-15k triangles per avatar for the Quest 3 browser.
## The procedural rig is far below that, and it must STAY below it: lofting the
## muzzles and ears added real geometry where there were primitives, so this is
## the guard that a future "just add more rings" change cannot quietly blow the
## budget on the only device class that cannot absorb it.
const AVATAR_TRIANGLE_BUDGET := 15000


func _test_triangle_budget() -> void:
	var worst := 0
	var worst_family := ""
	for family in Char3DConfig.FAMILIES:
		var cfg := Char3DConfig.defaults()
		cfg.head.family = family
		cfg.body.archetype = family
		# Load every optional slot so the count is the WORST case, not the bare rig.
		cfg.parts.tail = "tail_fox_01"
		cfg.parts.wings = "wings_dragon_01"
		cfg.parts.feature = "feature_antlers_01"
		cfg.parts.accessory = "accessory_glasses_01"
		cfg.parts.markings = "marking_sock_01"
		cfg.parts.hair = "hair_default_01"
		var a := CharacterAssembler.new()
		get_root().add_child(a)
		a.apply_config(cfg)
		var tris := _triangles(a)
		if tris > worst:
			worst = tris
			worst_family = family
		_check(tris > 0, "family %s produced drawable geometry" % family)
		get_root().remove_child(a)
		a.free()
	_check(worst <= AVATAR_TRIANGLE_BUDGET,
			"the heaviest fully-loaded avatar (%s, %d tris) fits the Quest-3 budget of %d"
			% [worst_family, worst, AVATAR_TRIANGLE_BUDGET])
	print("  [budget] heaviest fully-loaded avatar: %s at %d triangles" % [worst_family, worst])


func _triangles(node: Node) -> int:
	var total := 0
	if node is MeshInstance3D and (node as MeshInstance3D).mesh != null:
		total += (node as MeshInstance3D).mesh.get_faces().size() / 3
	for child in node.get_children():
		total += _triangles(child)
	return total


# ═════════════════════════════════════════════════════════════════════════════
# PACK WIRING — the vendored CC0/CC-BY catalogue -> the basis registry -> a
# mounted avatar. Everything below asserts against the COMMITTED bytes: paths
# that must exist, licences that must be on the allow-list, node names that
# must be in the models, geometry that must actually change on the rig.
# ═════════════════════════════════════════════════════════════════════════════

func _test_pack_catalogue_loads() -> void:
	CharForgePacks.reload()
	_check(CharForgePacks.load_error().is_empty(),
			"the generated lane catalogue loads: " + CharForgePacks.load_error())
	var lanes := CharForgePacks.lanes()
	_check(lanes.size() == 5, "5 style lanes (anime/jrpg/kemono/monsters/import), got %d" % lanes.size())
	var ids := PackedStringArray()
	for lane in lanes:
		ids.append(String(lane.id))
	_check(ids == PackedStringArray(CharForgePacks.LANE_ORDER),
			"lanes come back in the UI's tab order, got " + ", ".join(ids))

	var entries := CharForgePacks.entries()
	_check(entries.size() == 48, "48 catalogue entries, got %d" % entries.size())
	var seen := {}
	var recolorable := 0
	for e in entries:
		var key := String(e.key)
		_check(not seen.has(key), "catalogue key '%s' is unique" % key)
		seen[key] = true
		_check(String(e.licenseSpdx) in CharForgeRegistry.ALLOWED_LICENSES,
				"'%s' carries an allow-listed licence (%s)" % [key, e.licenseSpdx])
		_check(not String(e.attribution).strip_edges().is_empty(),
				"'%s' carries an attribution" % key)
		_check(String(e.sourceUrl).begins_with("http"), "'%s' carries a source URL" % key)
		_check(CharForgeRegistry._is_iso_date(String(e.retrievedAt)),
				"'%s' carries an ISO retrievedAt" % key)
		_check(FileAccess.file_exists(String(e.glb)),
				"'%s' points at a committed file (%s)" % [key, e.glb])
		_check(String(e.family) in Char3DConfig.FAMILIES, "'%s' names a real head family" % key)
		_check(String(e.archetype) in Char3DConfig.FAMILIES, "'%s' names a real archetype" % key)
		_check(String(e.style) in Char3DConfig.STYLES, "'%s' names a real style" % key)
		if bool(e.recolorable):
			recolorable += 1
	_check(recolorable == 48, "all 48 entries are region-recolourable, got %d" % recolorable)

	# The VRoid meshes must never become mixable parts (pixiv's guideline bars
	# any app that generates or outputs models from recombined VRoid meshes).
	for e in entries:
		if String(e.packId) == "vroid-cc0-v1":
			_check(not bool(e.partMixable),
					"the VRoid entry '%s' is flagged NOT part-mixable" % e.key)

	# The recorded capability gaps are carried through, not silently dropped.
	var blocked := CharForgePacks.blocked()
	_check(blocked.size() == 1, "1 recorded capability gap, got %d" % blocked.size())
	for b in blocked:
		_check(String(b.get("evidence", "")).length() > 40,
				"capability gap '%s' carries real evidence" % String(b.get("id", "")))
		# The animal-head graft used to be the third gap, recorded as "NOT
		# POSSIBLE AT RUNTIME". It is implemented now (CharForgeMeshSurgery), so
		# the entry has to be GONE — and, below, the capability has to be real.
		# Deleting a blocker without shipping the thing it blocked would just be
		# a quieter version of the same hole.
		_check(String(b.get("id", "")) != "quaternius-animal-head-graft",
				"the animal-head graft is no longer recorded as impossible")
	var with_donor := 0
	for e in entries:
		var d: Variant = e.get("headDonor", {})
		if d is Dictionary and not (d as Dictionary).is_empty():
			with_donor += 1
			_check(int((d as Dictionary).get("tris", 0)) > 0,
					"donor entry '%s' publishes a measured head triangle count" % String(e.key))
	_check(with_donor == 6,
			"six real body+donor-head entries replaced the blocked graft, got %d" % with_donor)

	# A vendored pack that reaches no lane must say why — otherwise 7 MB of
	# models sit in the repo that neither the engine nor the UI can ever show.
	var laned := {}
	for e in entries:
		laned[String(e.packId)] = true
	var unlaned := CharForgePacks.unlaned()
	_check(unlaned.size() == 2, "2 packs are vendored without a lane, got %d" % unlaned.size())
	for pack_id in unlaned:
		_check(not laned.has(pack_id), "'%s' really has no lane entry" % pack_id)
		_check(String(unlaned[pack_id]).length() > 40,
				"'%s' carries a real reason for being unlaned" % pack_id)
	_check(unlaned.has("quaternius-animals-v1") and unlaned.has("khronos-fox-v1"),
			"the donor quadrupeds and the loader test fixture are the two")


func _test_pack_entries_all_register() -> void:
	# The whole point of the registry's provenance gate is that a vendored pack
	# passes it. Every one of the 38 entries must convert into a basis the
	# registry ACCEPTS — a single missing licence field fails here.
	var accepted := 0
	for e in CharForgePacks.entries():
		var registry := CharForgeRegistry.default_registry()
		var basis := CharForgePacks.basis_entry(e)
		var ok := false
		if String(e.mount) == "body":
			ok = registry.register_body(String(e.archetype), basis)
		else:
			ok = registry.register_head(String(e.family), basis)
		_check(ok, "catalogue entry '%s' passes the licence gate: %s"
				% [e.key, ", ".join(registry.last_errors)])
		if ok:
			accepted += 1
	_check(accepted == 48, "all 48 entries register, got %d" % accepted)

	# ... and the gate still bites: strip the licence and the same entry is out.
	var stripped := CharForgePacks.basis_entry(CharForgePacks.entry("kemono-cat"))
	stripped["licenseSpdx"] = "CC-BY-NC-4.0"
	var gate := CharForgeRegistry.default_registry()
	_check(not gate.register_head("felid", stripped),
			"an NC licence is still rejected even coming from the catalogue")
	_check(gate.resolve_head("felid").basis == CharForgeRegistry.BASIS_PROCEDURAL,
			"a rejected entry leaves the family procedural")


func _test_pack_registry_routing() -> void:
	var registry := CharForgePacks.registry_for("kemono-cat")
	_check(registry.resolve_head("felid").basis == CharForgeRegistry.BASIS_MESH,
			"registry_for routes the entry's own family to the mesh basis")
	_check(String(registry.resolve_head("felid").glb).ends_with("cat.glb"),
			"the routed entry carries its committed path")
	_check(registry.resolve_head("canid").basis == CharForgeRegistry.BASIS_PROCEDURAL,
			"every OTHER family stays procedural")
	_check(registry.resolve_body("felid").basis == CharForgeRegistry.BASIS_PROCEDURAL,
			"an avatar-mount entry does not also claim the body basis")

	var body := CharForgePacks.registry_for("kemono-ubc-body-b")
	_check(body.resolve_body("humanoid").basis == CharForgeRegistry.BASIS_MESH,
			"a body-mount entry registers on the BODY archetype")
	_check(body.resolve_head("humanoid").basis == CharForgeRegistry.BASIS_PROCEDURAL,
			"a body-mount entry leaves the head procedural — that IS the hybrid")

	_check(CharForgePacks.registry_for("no-such-key").attributions().is_empty(),
			"an unknown key yields the all-procedural registry")
	_check(CharForgePacks.registry_for("").attributions().is_empty(),
			"an empty key yields the all-procedural registry")

	# The monsters lane occupies distinct families, so it can be wired whole.
	var bound: Array = []
	var monsters := CharForgePacks.registry_for_lane("monsters", bound)
	_check(bound.size() == 15, "all 15 monster entries bind, got %d" % bound.size())
	_check(monsters.resolve_head("dragon").basis == CharForgeRegistry.BASIS_MESH,
			"the monsters lane puts a mesh on the dragon family")
	_check(monsters.attributions().size() >= 2,
			"the lane registry enumerates its attributions for the credits screen")

	# CC-BY attribution has to be reachable for a user-visible credit.
	var credits := CharForgePacks.attributions()
	var required := 0
	for row in credits:
		if bool(row.required):
			required += 1
			_check(not String(row.attribution).is_empty(),
					"a CC-BY credit names its author")
	_check(required >= 2, "the CC-BY packs (Godette, 100Avatars) demand visible credit")


func _test_pack_hidden_meshes_exist() -> void:
	# hiddenMeshes are node names measured out of the committed GLBs. build-lanes
	# cannot check them (pack.json records materials, not nodes), so this is the
	# check that a typo does not silently leave a knight holding seven weapons.
	var checked := 0
	for e in CharForgePacks.entries():
		var names: Variant = e.get("hiddenMeshes", [])
		if not (names is Array) or names.is_empty():
			continue
		var root := CharForgeMount.instantiate_asset(String(e.glb))
		_check(root != null, "'%s' instantiates for the prop check" % e.key)
		if root == null:
			continue
		var present := {}
		for node in CharForgeMount._walk(root):
			if node is MeshInstance3D:
				present[String(node.name)] = true
		for wanted in names:
			_check(present.has(String(wanted)),
					"'%s' really has a mesh node named '%s'" % [e.key, wanted])
			checked += 1
		var hidden := CharForgeMount.hide_meshes(root, PackedStringArray(names))
		_check(hidden == names.size(),
				"hide_meshes hid all %d props of '%s' (hid %d)" % [names.size(), e.key, hidden])
		root.free()
	_check(checked == 26, "26 prop meshes are named across the KayKit entries, got %d" % checked)


func _test_pack_mount_avatar() -> void:
	var host := CharacterAssembler.new()
	get_root().add_child(host)
	host.apply_config(Char3DConfig.defaults())
	var procedural_height := CharForgeMount.measure_height(host)
	_check(procedural_height > 0.5, "the procedural avatar has a measurable height")

	var mount := CharForgeMount.new(host)
	get_root().add_child(mount)
	var entry := CharForgePacks.basis_entry(CharForgePacks.entry("kemono-cat"))
	var root := mount.mount("head", entry)
	_check(root != null, "the chibi cat mounts")
	if root == null:
		get_root().remove_child(mount)
		mount.free()
		get_root().remove_child(host)
		host.free()
		return

	_check(root.name == CharForgeMount.MOUNT_NODE_NAME, "the mesh mounts under PackMesh")
	_check(root.get_parent() == host, "the mesh is parented to the assembler")
	_check(not host.rig_ref("rig").visible, "the procedural rig is hidden behind a whole avatar")
	_check(CharForgeGlbLoader.find_skeleton(root) != null, "the mounted cat brought its rig")
	var mounted_height := CharForgeMount.measure_height(root)
	_check(absf(mounted_height - procedural_height) < procedural_height * 0.05,
			"the mounted avatar is scaled to the procedural height (%.2f vs %.2f)"
			% [mounted_height, procedural_height])

	var report := mount.mount_report()
	_check(report.mounted, "the mount reports itself mounted")
	_check(report.recolorable, "the flat-colour Quaternius cat is recolourable")
	_check(int(report.surfacesRebound) == 5,
			"all 5 cat surfaces were rebound to house materials, got %d" % report.surfacesRebound)
	_check(String(report.licenseSpdx) == "CC0-1.0", "the report carries the licence")
	_check(String(report.attribution).contains("Quaternius"), "the report carries the credit")

	# Every rebound surface must be a HOUSE material — that is what makes the
	# palette recolour reach an imported mesh at all.
	var house := 0
	var foreign := 0
	for node in CharForgeMount._walk(root):
		if not (node is MeshInstance3D):
			continue
		var mi := node as MeshInstance3D
		for i in range(mi.mesh.get_surface_count()):
			var mat: Material = mi.get_surface_override_material(i)
			if mat != null and mat == host.materials.region("fur"):
				house += 1
			elif mat != null:
				house += 1 if mat is ShaderMaterial and (mat as ShaderMaterial).shader \
						== CharForgeMaterials.HOUSE_SHADER else 0
				foreign += 0 if mat is ShaderMaterial and (mat as ShaderMaterial).shader \
						== CharForgeMaterials.HOUSE_SHADER else 1
	_check(house == 5 and foreign == 0,
			"all 5 surfaces run the house toon shader (house=%d foreign=%d)" % [house, foreign])

	# Recolour must still work THROUGH the mounted mesh: the palette is shared.
	var before: Color = host.materials.palette_image.get_pixel(
			CharForgePalette.CELLS["fur"].x, CharForgePalette.CELLS["fur"].y)
	_check(host.recolor({"fur": "#123456"}), "recolour applies with a mesh mounted")
	var after: Color = host.materials.palette_image.get_pixel(
			CharForgePalette.CELLS["fur"].x, CharForgePalette.CELLS["fur"].y)
	_check(not before.is_equal_approx(after), "the shared palette changed under the mounted mesh")

	# Clearing restores the procedural avatar rather than leaving an empty stage.
	mount.clear()
	_check(host.rig_ref("rig").visible, "clearing the mount un-hides the procedural rig")
	_check(mount.current_key.is_empty(), "clearing forgets the mounted key")

	get_root().remove_child(mount)
	mount.free()
	get_root().remove_child(host)
	host.free()


func _test_pack_mount_preserves_atlas_materials() -> void:
	var host := CharacterAssembler.new()
	get_root().add_child(host)
	host.apply_config(Char3DConfig.defaults())
	var mount := CharForgeMount.new(host)
	get_root().add_child(mount)

	var entry := CharForgePacks.basis_entry(CharForgePacks.entry("jrpg-knight"))
	_check(bool(entry.preserveMaterials), "the atlas-textured knight declares preserveMaterials")
	var root := mount.mount("head", entry)
	_check(root != null, "the knight mounts")
	var report := mount.mount_report()
	_check(int(report.surfacesRebound) == 0,
			"a preserved-material entry rebinds NOTHING (got %d)" % report.surfacesRebound)
	# THIS USED TO ASSERT THE KNIGHT COULD NOT BE RECOLOURED. He can now: the
	# vendor material is still preserved (it is the SHADING source), and the hue
	# comes from a house region per mesh node — see materialPolicy 'atlas'.
	_check(report.recolorable,
			"the knight recolours per mesh node even though his material is preserved")
	_check(int(report.atlasRegionsBound) > 0,
			"mesh nodes were bound to palette regions through the atlas (got %d)"
					% int(report.atlasRegionsBound))
	_check(int(report.meshesHidden) == 7,
			"the knight's 7 weapons/shields are hidden (got %d)" % report.meshesHidden)

	# The vendor atlas is still what supplies the shading: every bound node's
	# material must SAMPLE it, not replace it with a flat colour — that is
	# exactly the failure the whole mechanism exists to avoid.
	var sampling := 0
	var recoloured := 0
	for node in CharForgeMount._walk(root):
		if not (node is MeshInstance3D) or not (node as MeshInstance3D).visible:
			continue
		var mi := node as MeshInstance3D
		for i in range(mi.mesh.get_surface_count()):
			var override := mi.get_surface_override_material(i)
			if override == null:
				continue
			recoloured += 1
			var shader_mat := override as ShaderMaterial
			if shader_mat == null:
				continue
			if shader_mat.get_shader_parameter("atlas_tex") != null \
					and float(shader_mat.get_shader_parameter("atlas_strength")) > 0.0:
				sampling += 1
	_check(recoloured > 0 and sampling == recoloured,
			"every recoloured surface still samples the vendor atlas (%d of %d)"
					% [sampling, recoloured])

	# COVERAGE IS REPORTED, not assumed. A node that mixes two materials is left
	# on vendor colours on purpose, and the report has to say which.
	var regions: Array = report.atlasRegions
	_check(regions.size() == int(report.atlasRegionsBound),
			"the report lists every node it recoloured")
	for row in regions:
		_check(float((row as Dictionary).get("coverage", 0.0))
						>= CharForgeGlbLoader.MIN_ATLAS_COVERAGE,
				"'%s' cleared the coverage bar it was bound on"
						% String((row as Dictionary).get("node", "")))
		_check(String((row as Dictionary).get("region", "")) in Char3DConfig.ALL_MATERIAL_REGIONS,
				"'%s' was bound to a real palette region"
						% String((row as Dictionary).get("node", "")))
	_check(mount.last_error.is_empty(),
			"no knight mesh node was left unmapped by the catalogue: " + mount.last_error)

	get_root().remove_child(mount)
	mount.free()
	get_root().remove_child(host)
	host.free()


func _test_pack_mount_body_graft() -> void:
	# legal-inventory.md's hybrid: CC0 humanoid BODY + our procedural beast head,
	# grafted from code because no CC0 rigged anthro biped exists to buy.
	var host := CharacterAssembler.new()
	get_root().add_child(host)
	var cfg := Char3DConfig.defaults()
	cfg.head.family = "canid"
	cfg.body.archetype = "humanoid"
	host.apply_config(cfg)
	var head := host.rig_ref("head")
	_check(head != null, "the procedural canid head exists before the graft")

	var mount := CharForgeMount.new(host)
	get_root().add_child(mount)
	var entry := CharForgePacks.basis_entry(CharForgePacks.entry("kemono-ubc-body-b"))
	_check(not bool(entry.wholeAvatar), "a body mount is not a whole avatar")
	var root := mount.mount("body", entry)
	_check(root != null, "the CC0 body mounts")

	var attachment := head.get_parent()
	_check(attachment is BoneAttachment3D,
			"the procedural head now hangs off a BoneAttachment3D")
	if attachment is BoneAttachment3D:
		_check((attachment as BoneAttachment3D).bone_name == "Head",
				"the graft bound to the rig's Head bone, got '%s'"
				% (attachment as BoneAttachment3D).bone_name)
		_check(attachment.name == CharForgeMount.HEAD_GRAFT_NAME,
				"the graft node is named for what it is")
		_check(attachment.get_parent() is Skeleton3D,
				"the attachment was added to the loaded SKELETON, not to the scene root")
	_check(head.visible and head.is_inside_tree(), "the grafted head is live in the tree")
	_check(not host.rig_ref("rig").visible, "the procedural BODY is hidden under the mesh body")
	_check(mount.last_error.is_empty(), "the graft reported no fallback: " + mount.last_error)

	# The head must not inherit the body's fit-to-height scale, or a chibi body
	# would shrink our head with it.
	var report := mount.mount_report()
	var expected := 1.0 / float(report.heightScale)
	_check(absf(head.scale.x - expected) < 0.001,
			"the graft cancels the body's %.3fx fit scale (head scale %.3f)"
			% [report.heightScale, head.scale.x])

	get_root().remove_child(mount)
	mount.free()
	get_root().remove_child(host)
	host.free()


func _test_pack_mount_vrm_and_license() -> void:
	var host := CharacterAssembler.new()
	get_root().add_child(host)
	host.apply_config(Char3DConfig.defaults())
	var mount := CharForgeMount.new(host)
	get_root().add_child(mount)

	var entry := CharForgePacks.basis_entry(CharForgePacks.entry("anime-godette"))
	_check(String(entry.rig) == CharForgeRegistry.RIG_VRM, "Godette is registered as a VRM rig")
	_check(String(entry.licenseSpdx) == "CC-BY-3.0", "Godette carries her CC-BY-3.0 licence")
	var root := mount.mount("head", entry)
	_check(root != null, "the VRM mounts through the vendored godot-vrm addon")
	if root == null:
		get_root().remove_child(mount)
		mount.free()
		get_root().remove_child(host)
		host.free()
		return

	var license := CharForgeVrmIngest.read_license(root)
	_check(not license.is_empty(), "the VRM's in-file licence metadata is readable")
	_check(String(license.get("title", "")).to_lower().contains("godette"),
			"the in-file title is the model's own, got '%s'" % license.get("title", ""))
	_check(String(license.get("authors", "")).contains("SirRichard94"),
			"the in-file author survives, got '%s'" % license.get("authors", ""))
	_check(String(license.get("licenseName", "")).to_upper().contains("CC_BY"),
			"the in-file licence name is read, got '%s'" % license.get("licenseName", ""))
	_check(license.has("redistributionDisallowed") and license.has("creditRequired"),
			"the licence block answers the two questions a user actually asks")

	# MToon must not survive on a recolourable VRM — technique.md's verdict is
	# that MToon on WebGL2 is unverified, so the house shader replaces it.
	var mtoon := 0
	for node in CharForgeMount._walk(root):
		if not (node is MeshInstance3D):
			continue
		var mi := node as MeshInstance3D
		for i in range(mi.mesh.get_surface_count()):
			if CharForgeVrmIngest.is_mtoon(mi.get_active_material(i)):
				mtoon += 1
	_check(mtoon == 0, "no MToon material survived the swap (%d left)" % mtoon)

	get_root().remove_child(mount)
	mount.free()
	get_root().remove_child(host)
	host.free()


func _test_pack_mount_failure_is_soft() -> void:
	var host := CharacterAssembler.new()
	get_root().add_child(host)
	host.apply_config(Char3DConfig.defaults())
	var mount := CharForgeMount.new(host)
	get_root().add_child(mount)

	var failures: Array = []
	mount.mount_failed.connect(func(_e, reason): failures.append(reason))
	var broken := CharForgePacks.basis_entry(CharForgePacks.entry("kemono-cat"))
	broken["glb"] = "res://assets/packs/quaternius-monsters-v1/models/does-not-exist.glb"
	var root := mount.mount("head", broken)
	_check(root == null, "a missing asset mounts nothing")
	_check(failures.size() == 1, "the failure is announced, not swallowed")
	_check(host.rig_ref("rig").visible,
			"the PROCEDURAL avatar stays visible when the mesh cannot be loaded")
	_check(not mount.last_error.is_empty(), "the failure reason is recorded")

	get_root().remove_child(mount)
	mount.free()
	get_root().remove_child(host)
	host.free()


func _test_byo_import_round_trip() -> void:
	# The BYO slot, exercised exactly as the browser drives it: raw bytes in,
	# avatar on stage, licence block out. Nothing is written to disk.
	var host := CharacterAssembler.new()
	get_root().add_child(host)
	host.apply_config(Char3DConfig.defaults())
	var mount := CharForgeMount.new(host)
	get_root().add_child(mount)

	var bytes := FileAccess.get_file_as_bytes("res://assets/packs/godette-v1/models/godette.vrm")
	_check(bytes.size() > 1000, "test fixture bytes read")
	var report := mount.import_bytes(bytes, "my-avatar.vrm")
	_check(bool(report.ok), "user bytes import: " + String(report.get("reason", "")))
	_check(bool(report.imported), "the report marks the avatar as user-imported")
	_check(not bool(report.recolorable),
			"an imported model keeps ITS materials — we do not repaint someone else's work")
	_check(String(report.licenseSpdx).is_empty(),
			"an imported file gets NO SPDX id from us — we did not vet it")
	var license: Dictionary = report.license
	_check(String(license.get("authors", "")).contains("SirRichard94"),
			"the importer surfaces the file's own author")
	_check(String(license.get("licenseName", "")).to_upper().contains("CC_BY"),
			"the importer surfaces the file's own licence")
	_check(not host.rig_ref("rig").visible, "the imported avatar replaced the procedural rig")
	_check(int(report.bytes) == bytes.size(), "the report echoes the byte count")

	# Failure paths: empty, garbage, and over-cap all refuse WITHOUT mounting.
	var before := mount.current_key
	for bad in [
			{"bytes": PackedByteArray(), "name": "empty.vrm", "why": "an empty file"},
			{"bytes": "not a gltf at all".to_utf8_buffer(), "name": "junk.glb", "why": "garbage bytes"},
	]:
		var r: Dictionary = mount.import_bytes(bad.bytes, bad.name)
		_check(not bool(r.ok), "%s is refused" % bad.why)
		_check(not String(r.reason).is_empty(), "%s is refused with a reason" % bad.why)
	_check(mount.current_key == before,
			"a refused import leaves the previously mounted avatar alone")

	get_root().remove_child(mount)
	mount.free()
	get_root().remove_child(host)
	host.free()


func _test_bridge_pack_surface() -> void:
	# The host contract (DESIGN §4) as the Angular remote consumes it.
	var bridge: Node = get_root().get_node_or_null("/root/CharForgeBridge")
	_check(bridge != null, "the CharForgeBridge autoload is present")
	if bridge == null:
		return
	var parsed: Variant = JSON.parse_string(bridge.packs_json())
	_check(parsed is Dictionary, "listPacks() returns parseable JSON")
	if not (parsed is Dictionary):
		return
	var doc: Dictionary = parsed
	_check(bool(doc.available), "the host catalogue reports itself available")
	_check(doc.lanes.size() == 5, "the host catalogue carries all 5 lanes")
	var count := 0
	for lane in doc.lanes:
		for e in lane.entries:
			count += 1
			_check(e.has("licenseSpdx") and e.has("attribution") and e.has("sourceUrl"),
					"host entry '%s' carries its provenance" % e.key)
			_check(e.has("tris") and e.has("joints") and e.has("bytes"),
					"host entry '%s' carries its cost so the UI can warn" % e.key)
	_check(count == 48, "the host catalogue carries all 48 entries, got %d" % count)
	_check(doc.blocked.size() == 1, "the host catalogue carries the capability gap")


func _test_studio_pack_controls() -> void:
	var scene: PackedScene = load("res://scenes/studio.tscn")
	var studio := scene.instantiate()
	get_root().add_child(studio)
	# The lane/model choice now lives in the Angular host, fed by listPacks():
	# the catalogue the engine hands over must still describe procedural + 5 lanes
	# and the monsters lane's 15 models.
	var lanes: Array = CharForgePacks.lanes()
	_check(lanes.size() == 5, "listPacks describes the 5 style lanes, got %d" % lanes.size())
	var monsters: int = 0
	for e in CharForgePacks.entries():
		if String(e.get("lane", "")) == "monsters":
			monsters += 1
	_check(monsters == 15, "the monsters lane offers 15 models, got %d" % monsters)
	_check(not studio.mount.mount_report().mounted, "the studio starts procedural (nothing mounted)")

	# Mounting through the studio's own entry point must move the CONFIG too:
	# the basis registry is keyed by family, so the config has to follow.
	studio.select_pack("monster-dragon")
	_check(studio.assembler.config.head.family == "dragon",
			"selecting the dragon moved the config onto the dragon family")
	_check(studio.mount.mount_report().mounted, "the dragon actually mounted")
	_check(studio.assembler.resolved_bases().head.basis == CharForgeRegistry.BASIS_MESH,
			"the assembler resolves the dragon to a mesh basis")
	# The licence line the old in-canvas note printed is the host's job now; the
	# engine's catalogue hand-off (listPacks) must still carry it for the dragon's pack.
	_check(JSON.stringify(CharForgePacks.to_host_json()).contains("CC0"),
			"the catalogue the host receives carries the mounted model's licence")

	# Changing the family off a whole-avatar model drops back to procedural
	# rather than leaving a mesh that no longer matches the config.
	var moved: Dictionary = studio.assembler.config.duplicate(true)
	moved.head.family = "canid"
	moved.body.archetype = "canid"
	studio.select_pack("")
	studio._on_bridge_config(JSON.stringify(moved))
	_check(studio.assembler.config.head.family == "canid", "the family change applied")
	_check(studio.assembler.resolved_bases().head.basis == CharForgeRegistry.BASIS_PROCEDURAL,
			"the stale mesh basis was dropped with it")
	_check(studio.assembler.rig_ref("rig").visible, "the procedural rig is back on stage")

	# Back to procedural explicitly.
	studio.select_pack("")
	_check(not studio.mount.mount_report().mounted, "select_pack('') unmounts")

	# AN UNKNOWN KEY MUST FAIL LOUDLY. It used to `return` in silence, leaving
	# whatever was already mounted on stage — so the report the host received
	# described the PREVIOUS model and the page read it as success. That is how a
	# rename left 10 of 42 entries dead for a release with nobody noticing: every
	# dead card printed a cheerful licence line.
	studio.select_pack("monster-dragon")
	_check(studio.mount.mount_report().mounted, "a real key still mounts (control)")
	studio.select_pack("no-such-pack-key-v9")
	var bad: Dictionary = studio.mount.mount_report()
	_check(not bad.mounted,
			"an unknown key reports mounted:false, NOT the previously mounted model")
	_check(String(bad.error).contains("no-such-pack-key-v9"),
			"the failure report names the key that was refused, got %s" % [bad.error])
	_check(String(bad.key).is_empty(),
			"the refused mount does not claim the stale key")

	get_root().remove_child(studio)
	studio.free()


func _test_pack_mount_composed_outfit() -> void:
	# The Quaternius modular outfits: four separate GLBs skinned to the SAME
	# 65-bone Universal Base rig, assembled at runtime with no Blender step.
	var entry := CharForgePacks.entry("jrpg-outfit-peasant-b")
	_check(not entry.is_empty(), "the peasant outfit set is in the catalogue")
	if entry.is_empty():
		return
	_check(entry.pieces.size() == 3, "the peasant set carries 3 extra pieces, got %d"
			% entry.pieces.size())
	_check(int(entry.tris) == 12894,
			"the catalogue counts the WHOLE set's triangles (12894), got %d" % int(entry.tris))
	for path in entry.pieces:
		_check(FileAccess.file_exists(String(path)), "piece %s is committed" % path)

	var host := CharacterAssembler.new()
	get_root().add_child(host)
	var cfg := Char3DConfig.defaults()
	cfg.head.family = "humanoid"
	cfg.body.archetype = "humanoid"
	host.apply_config(cfg)
	var mount := CharForgeMount.new(host)
	get_root().add_child(mount)

	var basis := CharForgePacks.basis_entry(entry)
	_check(basis.pieces.size() == 3, "the basis entry carries the piece paths")
	var root := mount.mount("body", basis)
	_check(root != null, "the composed outfit mounts")
	if root == null:
		get_root().remove_child(mount)
		mount.free()
		get_root().remove_child(host)
		host.free()
		return

	var report := mount.mount_report()
	_check(int(report.piecesMerged) == 3,
			"the 3 extra garment meshes merged onto the primary's skeleton, got %d"
			% int(report.piecesMerged))
	_check(String(report.error).is_empty(),
			"no piece was dropped: " + String(report.error))

	# Every merged mesh must hang off THE skeleton and keep a fully resolvable
	# skin — a bind that does not resolve renders as a collapsed mesh.
	var skeleton := CharForgeGlbLoader.find_skeleton(root)
	_check(skeleton != null, "the set has exactly one skeleton")
	var skinned := 0
	var unresolved := 0
	for child in skeleton.get_children():
		if not (child is MeshInstance3D):
			continue
		var mi := child as MeshInstance3D
		if mi.skin == null:
			continue
		skinned += 1
		unresolved += CharForgeMount._unresolved_binds(mi, skeleton)
	_check(skinned == 4, "4 skinned garment meshes sit under the skeleton, got %d" % skinned)
	_check(unresolved == 0, "every skin bind resolves on the shared rig (%d did not)" % unresolved)

	# It is a BODY mount, so our procedural head is still the head.
	var head := host.rig_ref("head")
	_check(head != null and head.get_parent() is BoneAttachment3D,
			"the procedural head is grafted onto the outfit's rig")

	# The GARMENTS must render exactly what the catalogue promised, or the
	# selector's Quest-budget flag is lying. The grafted procedural head is the
	# only other geometry under the mount, so subtracting it is the whole
	# difference — asserting that pins both facts at once.
	var garment_tris := _triangles(root) - _triangles(head)
	_check(garment_tris == int(entry.tris),
			"the mounted set renders the catalogued %d garment triangles, got %d"
			% [int(entry.tris), garment_tris])

	# The garments are flat-colour slots, so recolouring reaches them.
	_check(report.recolorable, "the outfit set is region-recolourable")
	_check(int(report.surfacesRebound) > 0,
			"the primary's surfaces were rebound to house materials")

	# A piece list pointing at a rig that does NOT match is refused, not merged.
	var alien := CharForgeMount.merge_pieces(skeleton,
			PackedStringArray(["res://assets/packs/quaternius-monsters-v1/models/cat.glb"]))
	_check(int(alien.merged) == 0, "a piece from a different rig is not merged")
	_check(alien.skipped.size() > 0, "and the refusal is reported")

	get_root().remove_child(mount)
	mount.free()
	get_root().remove_child(host)
	host.free()


# ── runtime bone-weight mesh surgery ─────────────────────────────────────────
#
# lanes.json carried a blocked[] entry reading "NOT POSSIBLE AT RUNTIME —
# measured, not assumed" for grafting a CC0 animal head onto the humanoid rig.
# Its evidence was right (the donors are one skinned mesh with no head NODE) and
# its conclusion was wrong: the per-vertex bone weights that identify a head are
# already in the surface arrays. These tests are what let that verdict be
# deleted rather than merely disbelieved.

const DONOR_KEYS := [
	"kemono-wolf-head", "kemono-fox-head", "kemono-husky-head",
	"kemono-shiba-inu-head", "kemono-deer-head", "kemono-horse-head",
]

## Centroid of the first surface whose material name contains `want`.
func _surface_centroid(mesh: Mesh, want: String) -> Vector3:
	for s in range(mesh.get_surface_count()):
		var material := mesh.surface_get_material(s)
		if material == null or not material.resource_name.to_lower().contains(want):
			continue
		var verts: PackedVector3Array = mesh.surface_get_arrays(s)[Mesh.ARRAY_VERTEX]
		if verts.is_empty():
			return Vector3.INF
		var sum := Vector3.ZERO
		for v in verts:
			sum += v
		return sum / float(verts.size())
	return Vector3.INF


## THE PARITY GUARD. The triangle budget in the catalogue is computed by
## tools/asset-pipeline/glb-head-tris.mjs (Node), the split itself by
## CharForgeMeshSurgery (GDScript). Two implementations of one rule drift
## silently, so every shipped donor is required to produce EXACTLY the number
## the pipeline baked in — a change to either side that is not mirrored fails
## here, by donor name.
func _test_mesh_surgery_matches_the_pipeline_count() -> void:
	for key in DONOR_KEYS:
		var entry := CharForgePacks.entry(String(key))
		_check(not entry.is_empty(), "%s is in the catalogue" % key)
		if entry.is_empty():
			continue
		var donor: Dictionary = entry.get("headDonor", {})
		_check(not donor.is_empty(), "%s carries a headDonor" % key)
		if donor.is_empty():
			continue
		var scene := CharForgeMount.instantiate_asset(String(donor.glb))
		_check(scene != null, "%s: the donor GLB instantiates" % key)
		if scene == null:
			continue
		var joints := PackedStringArray()
		for pattern in donor.get("headJoints", []):
			joints.append(String(pattern))
		var report: Dictionary = CharForgeMeshSurgery.extract_head(
				scene, null, joints, CharForgeMeshSurgery.DEFAULT_THRESHOLD,
				bool(donor.get("keepSeam", true)))
		_check(report.node != null,
				"%s: the head splits off (%s)" % [key, String(report.get("error", ""))])
		if report.node == null:
			scene.free()
			continue
		_check(int(report.triangles) == int(donor.tris),
				"%s: engine split %d triangles, the pipeline baked %d"
						% [key, int(report.triangles), int(donor.tris)])
		_check(int(report.seamTriangles) == int(donor.seamTris),
				"%s: engine counted %d seam triangles, the pipeline %d"
						% [key, int(report.seamTriangles), int(donor.seamTris)])
		_check(int(report.vertices) == int(donor.vertices),
				"%s: engine kept %d vertices, the pipeline counted %d"
						% [key, int(report.vertices), int(donor.vertices)])
		# DE-SKINNED on purpose: the output becomes a plain node under a
		# BoneAttachment3D, so carrying bones/weights forward would be a second
		# skeleton nobody drives.
		var mi: MeshInstance3D = report.node
		var arrays: Array = mi.mesh.surface_get_arrays(0)
		_check(arrays[Mesh.ARRAY_BONES] == null and arrays[Mesh.ARRAY_WEIGHTS] == null,
				"%s: the extracted head is de-skinned" % key)
		_check(CharForgeMeshSurgery.mesh_height(mi.mesh) > 0.0,
				"%s: the extracted head has real extent" % key)
		mi.free()
		scene.free()


## The claim that makes a per-donor calibration table unnecessary: `side x up`
## is the same ANATOMICAL direction on a quadruped and on a biped, so aligning
## the two frames cannot land the head backwards. Both halves are probed here
## with independent evidence — the target's toes and the donor's face — because
## the runtime deliberately does no such probe.
func _test_mesh_surgery_lands_the_face_forward() -> void:
	var body := CharForgeMount.instantiate_asset(
			"res://assets/packs/quaternius-base-v1/models/ubc_superhero_a.glb")
	_check(body != null, "the UBC body loads")
	if body == null:
		return
	var target := CharForgeGlbLoader.find_skeleton(body)
	var head_bone := target.find_bone("Head")
	var lateral: Dictionary = CharForgeMeshSurgery.lateral_axis(target)
	_check(int(lateral.pairs) >= 20,
			"the UBC rig's left/right pairs are discovered from names, found %d"
					% int(lateral.pairs))
	var frame := CharForgeMeshSurgery.rig_frame(lateral.axis,
			CharForgeMeshSurgery.body_up(target, head_bone))
	# TARGET probe: the toes are forward of the ankle.
	var foot := target.get_bone_global_rest(target.find_bone("foot_l")).origin
	var ball := target.get_bone_global_rest(target.find_bone("ball_l")).origin
	_check(frame.z.dot((ball - foot).normalized()) > 0.5,
			"side x up is the direction the UBC rig FACES — the toes agree (dot %.3f)"
					% frame.z.dot((ball - foot).normalized()))

	# DONOR probe: after alignment the face lands on +Z, the target's forward.
	var landmarks := {
		"kemono-wolf-head": "nose",
		"kemono-fox-head": "black",
		"kemono-shiba-inu-head": "eyes_pupil",
		"kemono-husky-head": "material.003",
		"kemono-horse-head": "muzzle",
		"kemono-deer-head": "eye_black",
	}
	for key in landmarks:
		var donor: Dictionary = CharForgePacks.entry(String(key)).get("headDonor", {})
		if donor.is_empty():
			continue
		var scene := CharForgeMount.instantiate_asset(String(donor.glb))
		if scene == null:
			continue
		var report: Dictionary = CharForgeMeshSurgery.extract_head(scene, target)
		if report.node == null:
			_check(false, "%s: aligned extraction failed (%s)" % [key, String(report.error)])
			scene.free()
			continue
		var mi: MeshInstance3D = report.node
		var face := _surface_centroid(mi.mesh, String(landmarks[key]))
		_check(face != Vector3.INF, "%s: the '%s' surface survived the split"
				% [key, String(landmarks[key])])
		if face != Vector3.INF:
			_check(face.z > 0.0 and face.z > absf(face.x),
					"%s: '%s' faces the target's forward (+Z), got %v"
							% [key, String(landmarks[key]), face])
		_check(String(report.alignedTo) == "Head",
				"%s: reports which bone it aligned to" % key)
		mi.free()
		scene.free()
	body.free()


## A wrong joint set must FAIL, not quietly extract the whole animal. This is
## the failure mode that would put a complete wolf on a humanoid's shoulders.
func _test_mesh_surgery_refuses_what_it_cannot_split() -> void:
	var scene := CharForgeMount.instantiate_asset(
			"res://assets/packs/quaternius-animals-v1/models/wolf.glb")
	_check(scene != null, "the wolf donor loads")
	if scene == null:
		return
	var skeleton := CharForgeGlbLoader.find_skeleton(scene)
	var mi: MeshInstance3D = null
	for node in scene.find_children("*", "MeshInstance3D", true, false):
		if (node as MeshInstance3D).mesh != null:
			mi = node as MeshInstance3D
			break
	_check(mi != null, "the wolf has a skinned mesh")
	if mi == null:
		scene.free()
		return

	var empty: Dictionary = CharForgeMeshSurgery.split(mi, skeleton, PackedStringArray())
	_check(empty.mesh == null and String(empty.error).contains("empty joint set"),
			"an empty joint set refuses rather than extracting everything")
	var unknown: Dictionary = CharForgeMeshSurgery.split(
			mi, skeleton, PackedStringArray(["NoSuchBone"]))
	_check(unknown.mesh == null,
			"a joint set this rig lacks is an error, not an empty mesh")
	var rigless: Dictionary = CharForgeMeshSurgery.split(
			mi, null, PackedStringArray(["Head"]))
	_check(rigless.mesh == null and String(rigless.error).contains("skeleton"),
			"a bone-weight split without the rig refuses")

	# A single-bone joint set matches real geometry but nowhere near a head; the
	# MIN_TRIANGLES floor is what stops that shipping as "a head".
	var sliver: Dictionary = CharForgeMeshSurgery.split(
			mi, skeleton, PackedStringArray(["Ear4.L"]))
	_check(sliver.mesh == null or int(sliver.triangles) >= CharForgeMeshSurgery.MIN_TRIANGLES,
			"a joint set too small to be a head is refused, not shipped as a few polygons")

	var unaligned: Dictionary = CharForgeMeshSurgery.extract_head(scene, null)
	_check(String(unaligned.alignedTo).is_empty(),
			"an extraction with no target says it aligned to nothing")
	if unaligned.node != null:
		(unaligned.node as Node).free()
	scene.free()


func _test_pack_mount_donor_head_graft() -> void:
	var host := CharacterAssembler.new()
	get_root().add_child(host)
	var cfg := Char3DConfig.defaults()
	cfg.head.family = "canid"
	cfg.body.archetype = "humanoid"
	host.apply_config(cfg)
	var procedural_head := host.rig_ref("head")
	var procedural_height := CharForgeMount.measure_height(procedural_head)
	_check(procedural_head != null, "the procedural canid head exists before the graft")

	var mount := CharForgeMount.new(host)
	get_root().add_child(mount)
	var entry := CharForgePacks.basis_entry(CharForgePacks.entry("kemono-wolf-head"))
	var root := mount.mount("body", entry)
	_check(root != null, "the body + wolf-head entry mounts")
	if root == null:
		get_root().remove_child(mount)
		mount.free()
		get_root().remove_child(host)
		host.free()
		return
	_check(mount.last_error.is_empty(),
			"the donor graft needed no fallback: " + mount.last_error)

	var report := mount.mount_report()
	var donor: Dictionary = report.headDonor
	_check(not donor.is_empty(), "the mount report names the donor head")
	_check(int(donor.get("triangles", 0)) == 554,
			"the wolf head arrived at its measured 554 triangles, got %d"
					% int(donor.get("triangles", 0)))
	# The donor is a SECOND third-party asset on screen: crediting only the body
	# would under-report what is being drawn.
	_check(String(donor.get("licenseSpdx", "")) == "CC0-1.0",
			"the donor carries its own licence, not the body's")
	_check(not String(donor.get("attribution", "")).is_empty(),
			"the donor is attributed separately from the body")
	_check(String(donor.get("alignedTo", "")) == "Head",
			"the report says which target bone the head was aligned to")
	_check(int(donor.get("lateralPairs", 0)) > 0,
			"the alignment was measured off real left/right bone pairs")

	_check(not procedural_head.visible,
			"the PROCEDURAL head is hidden — the donor head replaced it")
	_check(not host.rig_ref("rig").visible, "the procedural body is hidden under the mesh body")

	var skeleton := CharForgeGlbLoader.find_skeleton(root)
	var graft := skeleton.find_child(CharForgeMount.HEAD_GRAFT_NAME, false, false)
	_check(graft is BoneAttachment3D, "the donor head hangs off a BoneAttachment3D")
	if graft is BoneAttachment3D:
		_check((graft as BoneAttachment3D).bone_name == "Head",
				"the donor head bound to the rig's Head bone")
		var grafted: MeshInstance3D = null
		for child in graft.get_children():
			if child is MeshInstance3D:
				grafted = child as MeshInstance3D
		_check(grafted != null, "the graft carries the extracted mesh")
		if grafted != null:
			# SIZE: the donor head arrives the size of the procedural head it
			# replaced, so the turntable camera does not need to know which is on.
			# WORLD height. measure_height() reports a node's own LOCAL aabb, so
			# measuring the MeshInstance3D itself would ignore the fit scale that
			# is the entire point of this assertion.
			var shown := (grafted.global_transform * grafted.mesh.get_aabb()).size.y
			_check(procedural_height > 0.0
					and absf(shown - procedural_height) / procedural_height < 0.15,
					"the wolf head was fitted to the procedural head's height (%.4f vs %.4f)"
							% [shown, procedural_height])
			_check(int(grafted.mesh.get_surface_count()) > 0, "the grafted mesh has surfaces")

	get_root().remove_child(mount)
	mount.free()
	get_root().remove_child(host)
	host.free()


## A donor is a separate vendor's asset, so it faces the SAME provenance bar as
## the body — an entry whose donor is uncredited or off-licence must be rejected
## whole, not mounted with a bare body.
func _test_registry_holds_a_donor_to_the_same_licence_bar() -> void:
	var base := CharForgePacks.basis_entry(CharForgePacks.entry("kemono-wolf-head"))
	_check(base.get("headDonor", {}) is Dictionary
			and not (base.get("headDonor", {}) as Dictionary).is_empty(),
			"the shipped entry carries its donor through basis_entry()")

	var good := CharForgeRegistry.new()
	_check(good.register_body("humanoid", base.duplicate(true)),
			"the shipped body+donor entry registers: " + ", ".join(good.last_errors))

	var off_licence := base.duplicate(true)
	(off_licence["headDonor"] as Dictionary)["licenseSpdx"] = "Proprietary"
	var r1 := CharForgeRegistry.new()
	_check(not r1.register_body("humanoid", off_licence),
			"a donor with an off-allow-list licence rejects the whole entry")

	var uncredited := base.duplicate(true)
	(uncredited["headDonor"] as Dictionary)["attribution"] = ""
	var r2 := CharForgeRegistry.new()
	_check(not r2.register_body("humanoid", uncredited),
			"an uncredited donor rejects the whole entry")

	var undated := base.duplicate(true)
	(undated["headDonor"] as Dictionary)["retrievedAt"] = "2026-99-99"
	var r3 := CharForgeRegistry.new()
	_check(not r3.register_body("humanoid", undated),
			"a donor with an impossible retrievedAt rejects the whole entry")


# ── single-atlas per-region recolour ─────────────────────────────────────────
#
# lanes.json recorded "per-region recolour on the KayKit Adventurers/Skeletons
# and 100Avatars lines" as NOT SHIPPED, because each character renders from one
# baked atlas and binding that to a flat house colour paints the whole character
# one colour. True — and it does not follow that nothing can be done, because
# the vendor splits every character into SEMANTIC MESH NODES and each node's
# texels are a luminance ramp of one hue.

const ATLAS_KEYS := [
	"jrpg-knight", "jrpg-barbarian", "jrpg-mage", "jrpg-rogue", "jrpg-rogue-hooded",
	"monster-skeleton-warrior", "monster-skeleton-mage", "monster-skeleton-rogue",
	"monster-skeleton-minion",
]

func _test_atlas_recolour_reaches_every_single_atlas_model() -> void:
	for key in ATLAS_KEYS:
		var host := CharacterAssembler.new()
		get_root().add_child(host)
		host.apply_config(Char3DConfig.defaults())
		var mount := CharForgeMount.new(host)
		get_root().add_child(mount)
		var entry := CharForgePacks.basis_entry(CharForgePacks.entry(String(key)))
		_check(bool(entry.preserveMaterials),
				"%s still PRESERVES its vendor material — it is the shading source" % key)
		var root := mount.mount("head", entry)
		_check(root != null, "%s mounts" % key)
		if root == null:
			get_root().remove_child(mount)
			mount.free()
			get_root().remove_child(host)
			host.free()
			continue

		var report := mount.mount_report()
		_check(int(report.atlasRegionsBound) > 0,
				"%s recolours at least one part (got %d)" % [key, int(report.atlasRegionsBound)])
		_check(report.recolorable, "%s reports itself recolourable" % key)
		_check(int(report.surfacesRebound) == 0,
				"%s rebound no surface to a FLAT house material (got %d) — that is the "
						% [key, int(report.surfacesRebound)]
						+ "failure mode this whole mechanism exists to avoid")
		# Every mesh node the model has must be ACCOUNTED FOR: recoloured, or
		# deliberately skipped with its measured coverage. Silence would mean the
		# catalogue had forgotten a part.
		_check(mount.last_error.is_empty(),
				"%s: every mesh node is named by the catalogue: %s" % [key, mount.last_error])
		for row in (report.atlasRegions as Array):
			var record: Dictionary = row
			_check(String(record.get("region", "")) in Char3DConfig.ALL_MATERIAL_REGIONS,
					"%s/%s bound to a real palette region" % [key, record.get("node", "")])
			# A node bound off the ATLAS had to clear the coverage bar; one bound
			# off a FLAT vendor material (the skeletons' glowing eye sockets) has
			# no atlas to measure and recolours completely.
			if String(record.get("source", "")) == "atlas":
				_check(float(record.get("coverage", 0.0)) >= CharForgeGlbLoader.MIN_ATLAS_COVERAGE,
						"%s/%s cleared the coverage bar" % [key, record.get("node", "")])
		for skipped in (report.atlasSkipped as Array):
			_check(String(skipped).contains("%"),
					"%s: a skipped part says how mixed it measured (%s)" % [key, skipped])

		get_root().remove_child(mount)
		mount.free()
		get_root().remove_child(host)
		host.free()


## Recolouring must stay a 64-pixel palette rewrite. Routing the atlas tint
## through the house materials — rather than baking a recoloured texture — is
## what keeps that true: the region colour rides in the SHARED palette image, so
## changing it touches no material and rebuilds no texture, on a single-atlas
## character exactly as on a procedural one.
func _test_atlas_recolour_rides_the_shared_palette() -> void:
	var mats := CharForgeMaterials.for_config(Char3DConfig.defaults())
	var image := Image.create_empty(4, 4, false, Image.FORMAT_RGBA8)
	image.fill(Color(0.4, 0.2, 0.1))
	var atlas := ImageTexture.create_from_image(image)

	var mat := mats.atlas_region("skin", atlas, 0.42, 0.1)
	_check(mat.get_shader_parameter("atlas_tex") == atlas,
			"the atlas material samples the VENDOR texture")
	_check(float(mat.get_shader_parameter("atlas_strength")) > 0.0,
			"the atlas path is switched on for this material")
	_check(absf(float(mat.get_shader_parameter("atlas_reference_luma")) - 0.42) < 0.0001,
			"the measured reference luminance reached the shader")
	_check(mat.get_shader_parameter("palette_tex") == mats.palette_texture,
			"an atlas material shares the ONE palette texture with every other region")

	# Every OTHER material must be unaffected: atlas_strength defaults to 0, so
	# the procedural avatar and the slot-mapped GLBs take the same code path they
	# always did.
	var plain := mats.region("fur")
	_check(float(plain.get_shader_parameter("atlas_strength")) == 0.0,
			"an ordinary house material leaves the atlas branch inert")

	var before := mats.palette_texture
	var cfg := Char3DConfig.defaults()
	cfg.materials.skin = "#ff0000"
	mats.recolor(cfg)
	_check(mats.palette_texture == before,
			"recolour rewrote the shared palette IN PLACE — no material was touched")
	_check(mat.get_shader_parameter("palette_tex") == before,
			"the atlas material still points at that same palette after a recolour")

# ── boundary-free atlas recolour (the 100Avatars line) ───────────────────────
#
# These avatars have NO per-part boundary: one mesh node, one primitive, one
# material, no COLOR_0, no VRM annotation, and UV islands that are anatomical
# rather than material. The regions therefore come from the TEXTURE's own base
# colours, which is the one partition that IS material.

const CLUSTER_KEYS := [
	"filler-jimmy", "filler-skull", "filler-cactusboy", "filler-froggy",
	"filler-teddy", "filler-mint", "filler-skelly", "filler-polybot",
]

func _test_boundary_free_recolour_reaches_every_100avatar() -> void:
	for key in CLUSTER_KEYS:
		var host := CharacterAssembler.new()
		get_root().add_child(host)
		host.apply_config(Char3DConfig.defaults())
		var mount := CharForgeMount.new(host)
		get_root().add_child(mount)
		var entry := CharForgePacks.basis_entry(CharForgePacks.entry(String(key)))
		var root := mount.mount("head", entry)
		_check(root != null, "%s mounts" % key)
		if root == null:
			get_root().remove_child(mount)
			mount.free()
			get_root().remove_child(host)
			host.free()
			continue

		var report := mount.mount_report()
		var found: Array = report.atlasClusters
		_check(report.recolorable, "%s reports itself recolourable" % key)
		_check(found.size() >= 2,
				"%s resolved at least two base colours (got %d)" % [key, found.size()])
		_check(mount.last_error.is_empty(), "%s bound cleanly: %s" % [key, mount.last_error])

		# The atlas is a ShaderMaterial (MToon) on a VRM, NOT a BaseMaterial3D —
		# reading only albedo_texture finds nothing on any of these, which is
		# exactly the bug this path had first time round.
		var shaded := 0
		for node in CharForgeMount._walk(root):
			if not (node is MeshInstance3D):
				continue
			var mi := node as MeshInstance3D
			if mi.mesh == null:
				continue
			for i in range(mi.mesh.get_surface_count()):
				var override := mi.get_surface_override_material(i) as ShaderMaterial
				if override == null:
					continue
				shaded += 1
				_check(float(override.get_shader_parameter("atlas_cluster_count")) >= 2.0,
						"%s: the material carries its measured clusters" % key)
				_check(override.get_shader_parameter("atlas_tex") != null,
						"%s: the material still samples the VENDOR atlas" % key)
				# The single-node path must be OFF or both branches write ALBEDO.
				_check(float(override.get_shader_parameter("atlas_strength")) == 0.0,
						"%s: the per-node tint path is disabled on a cluster material" % key)
		_check(shaded > 0, "%s had its surfaces rebound" % key)

		# SHARES ARE REAL PERCENTAGES: they are the fraction of texels that
		# classify to each cluster, so they sum to 1 — not the quantised bucket
		# counts the centres were seeded from, which do not.
		var total := 0.0
		var previous := 2.0
		for row in found:
			var record: Dictionary = row
			total += float(record.share)
			_check(String(record.region) in Char3DConfig.ALL_MATERIAL_REGIONS,
					"%s: cluster bound to a real palette region" % key)
			_check(float(record.share) <= previous + 0.0001,
					"%s: clusters are ranked by area, largest first" % key)
			previous = float(record.share)
		_check(absf(total - 1.0) < 0.02,
				"%s: cluster shares sum to 100%% (got %.1f%%)" % [key, total * 100.0])

		get_root().remove_child(mount)
		mount.free()
		get_root().remove_child(host)
		host.free()


## Clustering must be DETERMINISTIC — same bytes, same answer — or the region a
## colour gets could differ between two runs of the same avatar, and a test
## could never pin it. Dictionary key order is not stable in general, so the
## ranking is explicitly tie-broken rather than left to hash order.
func _test_atlas_clusters_are_deterministic_and_ranked() -> void:
	var root := CharForgeMount.instantiate_asset(
			"res://assets/packs/polygonalmind-100avatars-v1/models/100avatars_003_jimmy.vrm")
	_check(root != null, "the jimmy VRM parses")
	if root == null:
		return
	var mi: MeshInstance3D = null
	for node in root.find_children("*", "MeshInstance3D", true, false):
		if (node as MeshInstance3D).mesh != null:
			mi = node as MeshInstance3D
			break
	_check(mi != null, "jimmy has a mesh")
	if mi == null:
		root.free()
		return
	var atlas := CharForgeGlbLoader.atlas_texture_of(mi)
	_check(atlas != null, "the MToon _MainTex atlas is found on a VRM ShaderMaterial")

	var first := CharForgeGlbLoader.cluster_atlas(mi, atlas)
	var second := CharForgeGlbLoader.cluster_atlas(mi, atlas)
	_check(first.size() == second.size() and first.size() > 0,
			"clustering returns the same count twice (%d/%d)" % [first.size(), second.size()])
	var identical := true
	for i in range(mini(first.size(), second.size())):
		if not (first[i].color as Color).is_equal_approx(second[i].color) \
				or absf(float(first[i].share) - float(second[i].share)) > 0.0001:
			identical = false
	_check(identical, "clustering is deterministic across runs")
	_check(first.size() <= CharForgeGlbLoader.MAX_ATLAS_CLUSTERS,
			"clustering never exceeds the %d the shader can hold"
					% CharForgeGlbLoader.MAX_ATLAS_CLUSTERS)
	# Distinct enough to be different MATERIALS, not shades of one.
	for i in range(first.size()):
		for k in range(i + 1, first.size()):
			_check(CharForgeGlbLoader._color_distance(first[i].color, first[k].color)
							>= CharForgeGlbLoader.MERGE_DISTANCE,
					"clusters %d and %d are distinct colours, not one ramp" % [i, k])
	root.free()
