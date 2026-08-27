extends Node3D

# Character Forge studio — turntable scene controller.
#
# Owns the CharacterAssembler instance, the orbit camera, the turntable spin,
# the transparent snapshot SubViewport (for CharForge.snapshot avatar/sticker
# reuse), and the built-in editor UI. The built-in UI makes the export a
# playable game in the hugmesh.games portal on its own; when the Angular
# character-forge remote embeds the engine it drives everything through the
# CharForge JavaScriptBridge singleton instead (and may hide the built-in UI
# via CharForge.setUiVisible(false)).

const ORBIT_SENSITIVITY := 0.42
const ZOOM_STEP := 0.18
const MIN_DISTANCE := 1.4
const MAX_DISTANCE := 6.0
const SNAP_SIZE := 1024

# Built-in UI cycle lists per part slot (VN partId vocabulary; the assembler
# keyword-matches unknown ids so free-form values from /map still render).
const UI_PART_CHOICES := {
	"tail": [null, "tail_fox_01", "tail_wolf_bushy_01", "tail_cat_01", "tail_lion_01",
			"tail_feather_fan_01", "tail_spade_01", "tail_thick_lizard_01", "tail_eastern_01",
			"tail_horse_01", "tail_deer_01", "tail_bunny_puff_01", "tail_rat_thin_01",
			"tail_squirrel_01", "tail_ringed_01", "tail_shark_01", "tail_cetacean_01",
			"tail_hyena_01", "tail_pig_curl_01", "tail_macropod_01", "tail_goo_01",
			"tail_synth_01", "tail_bear_stub_01", "tail_cow_tassel_01"],
	"wings": [null, "wings_feathered_01", "wings_dragon_01", "wings_bat_01",
			"wings_butterfly_01", "wings_moth_01", "wings_insect_01", "wings_crystal_01",
			"wings_goo_01", "wings_small_01"],
	"ears": [null, "ears_lop_01", "ears_pointy", "ears_round", "ears_long",
			"ears_huge", "ears_fin", "ears_tuft", "ears_goo_01", "ears_none"],
	"feature": [null, "feature_mane_01", "feature_antlers_01", "feature_unicorn_horn_01",
			"feature_dragon_horns_01", "feature_ram_horns_01", "feature_bull_horns_01",
			"feature_goat_horns_01", "feature_frill_01", "feature_head_crest_01",
			"feature_dorsal_fin_01", "feature_head_fins_01", "feature_tusks_01",
			"feature_antennae_01", "feature_antennae_ball_01", "feature_halo",
			"feature_core_orb_01", "feature_snail_shell_01", "feature_goo_crown_01",
			"feature_hyena_mane_01", "feature_dragon_spines_01", "feature_synth_fins_01"],
	"accessory": [null, "accessory_bandana_01", "accessory_collar_01", "accessory_glasses_01",
			"accessory_ear_piercing_01", "accessory_mushroom_cap_01", "accessory_slime_pet_01"],
	"markings": [null, "marking_mask_01", "marking_countershade_01", "marking_sock_01",
			"marking_muzzle_blaze_01", "marking_fawn_spots_01", "marking_tiger_stripes_01",
			"marking_bee_stripes_01", "marking_belly_scutes_01", "marking_holstein_patches_01",
			"marking_metallic_sheen_01", "marking_crystal_growths_01", "marking_goo_drip_01"],
	"hair": [null, "hair_default_01", "hair_flower_01", "hair_leafy_01", "hair_goo_01"],
}

var assembler: CharacterAssembler = null
## Mounts vendored-pack meshes (and BYO imports) onto the assembler rig.
var mount: CharForgeMount = null

var _bridge: Node = null
## Catalogue key currently selected ("" = the purely procedural avatar).
var _pack_key := ""
var _auto_rotate := true
var _orbiting := false
var _cam_distance := 2.9
var _snap_viewport: SubViewport = null
var _snap_camera: Camera3D = null

@onready var _turntable: Node3D = $Turntable
@onready var _char_root: Node3D = $Turntable/CharacterRoot
@onready var _gimbal: Node3D = $CameraGimbal
@onready var _pitch: Node3D = $CameraGimbal/Pitch
@onready var _camera: Camera3D = $CameraGimbal/Pitch/Camera3D


func _ready() -> void:
	_setup_stage()
	assembler = CharacterAssembler.new()
	assembler.name = "Character"
	_char_root.add_child(assembler)

	# The assembler never loads anything itself — it announces that the resolved
	# basis is a mesh and this mount fetches it (DESIGN §2 part-registry
	# indirection). Connected BEFORE the first apply_config so a pack selected
	# from a saved config mounts on the very first rebuild.
	mount = CharForgeMount.new(assembler)
	add_child(mount)
	assembler.mesh_basis_required.connect(_on_mesh_basis_required)
	assembler.apply_config(Char3DConfig.defaults())

	_setup_snapshot_viewport()

	_bridge = get_node_or_null("/root/CharForgeBridge")
	if _bridge != null:
		_bridge.config_received.connect(_on_bridge_config)
		_bridge.expression_received.connect(_on_bridge_expression)
		_bridge.openness_received.connect(_on_bridge_openness)
		_bridge.randomize_received.connect(_on_bridge_randomize)
		_bridge.rig_received.connect(_on_bridge_rig)
		_bridge.auto_adjust_requested.connect(_on_bridge_auto_adjust)
		_bridge.auto_rotate_received.connect(_on_bridge_auto_rotate)
		_bridge.pack_received.connect(_on_bridge_pack)
		_bridge.import_received.connect(_on_bridge_import)
		_bridge.snapshot_provider = _snapshot


func _process(delta: float) -> void:
	if _auto_rotate and not _orbiting:
		_turntable.rotation.y = wrapf(_turntable.rotation.y + delta * 0.45, -TAU, TAU)
	if _snap_camera != null:
		_snap_camera.global_transform = _camera.global_transform
		_snap_camera.fov = _camera.fov


func _unhandled_input(event: InputEvent) -> void:
	if event is InputEventMouseButton:
		var mb := event as InputEventMouseButton
		match mb.button_index:
			MOUSE_BUTTON_LEFT:
				_orbiting = mb.pressed
			MOUSE_BUTTON_WHEEL_UP:
				if mb.pressed:
					_cam_distance = clampf(_cam_distance - ZOOM_STEP, MIN_DISTANCE, MAX_DISTANCE)
					_camera.position.z = _cam_distance
			MOUSE_BUTTON_WHEEL_DOWN:
				if mb.pressed:
					_cam_distance = clampf(_cam_distance + ZOOM_STEP, MIN_DISTANCE, MAX_DISTANCE)
					_camera.position.z = _cam_distance
	elif event is InputEventMouseMotion and _orbiting:
		var mm := event as InputEventMouseMotion
		_gimbal.rotation_degrees.y -= mm.relative.x * ORBIT_SENSITIVITY
		_pitch.rotation_degrees.x = clampf(
				_pitch.rotation_degrees.x - mm.relative.y * ORBIT_SENSITIVITY, -55.0, 25.0)


# =============================================================================
# Stage / camera / snapshot
# =============================================================================

func _setup_stage() -> void:
	$KeyLight.rotation_degrees = Vector3(-42.0, -32.0, 0.0)
	$FillLight.position = Vector3(-1.8, 1.4, 2.2)
	$RimLight.position = Vector3(0.6, 2.0, -2.4)
	_gimbal.position = Vector3(0, 0.95, 0)
	_camera.position = Vector3(0, 0, _cam_distance)
	_pitch.rotation_degrees.x = -6.0


func _setup_snapshot_viewport() -> void:
	# A transparent SubViewport sharing the main World3D renders ONLY the 3D
	# stage (never the UI), so CharForge.snapshot returns clean alpha PNGs.
	_snap_viewport = SubViewport.new()
	_snap_viewport.name = "SnapshotViewport"
	_snap_viewport.size = Vector2i(SNAP_SIZE, SNAP_SIZE)
	_snap_viewport.transparent_bg = true
	_snap_viewport.own_world_3d = false
	_snap_viewport.render_target_update_mode = SubViewport.UPDATE_ALWAYS
	add_child(_snap_viewport)
	_snap_camera = Camera3D.new()
	_snap_camera.name = "SnapshotCamera"
	_snap_viewport.add_child(_snap_camera)
	_snap_camera.current = true


func _snapshot(w: int, h: int) -> String:
	if _snap_viewport == null:
		return ""
	var img := _snap_viewport.get_texture().get_image()
	if img == null:
		return ""
	img.convert(Image.FORMAT_RGBA8)
	img.resize(clampi(w, 16, 2048), clampi(h, 16, 2048), Image.INTERPOLATE_LANCZOS)
	return Marshalls.raw_to_base64(img.save_png_to_buffer())


# =============================================================================
# Bridge handlers (host -> game)
# =============================================================================

func _on_bridge_config(json: String) -> void:
	var cfg: Variant = Char3DConfig.try_parse(json)
	if cfg is Dictionary:
		assembler.apply_config(cfg)


func _on_bridge_expression(label: String) -> void:
	assembler.set_expression(label)


func _on_bridge_openness(value: float) -> void:
	assembler.set_openness(value)


func _on_bridge_randomize(seed_value: int) -> void:
	assembler.apply_config(CharRandomizer.randomize_config(seed_value))
	_emit_config_changed()


func _on_bridge_rig(json: String) -> void:
	var parsed: Variant = JSON.parse_string(json)
	assembler.set_rig(parsed if parsed is Dictionary else {})
	_emit_config_changed()


func _on_bridge_auto_adjust() -> void:
	assembler.auto_adjust_rig()
	_emit_config_changed()


func _on_bridge_auto_rotate(on: bool) -> void:
	_auto_rotate = on


func _on_bridge_pack(key: String) -> void:
	select_pack(key)
	if _bridge != null:
		_bridge.emit_pack_changed(JSON.stringify(mount.mount_report()))


func _on_bridge_import(bytes: PackedByteArray, file_name: String) -> void:
	var report := import_model(bytes, file_name)
	if _bridge != null:
		_bridge.emit_import_result(JSON.stringify(report))


# =============================================================================
# Pack mounting (the vendored CC0/CC-BY catalogue) + BYO import
# =============================================================================

## Mounts a catalogue entry by key ("" drops back to the procedural avatar).
##
## Selecting a model also moves the CONFIG onto it, because the basis registry
## is keyed by family/archetype: an "avatar" entry takes over its head family
## and body archetype, while a "body" entry (the kemono hybrid) takes only the
## archetype so the beast head the user picked survives on top of it.
func select_pack(key: String) -> void:
	_pack_key = key
	var cfg := _edited_config()
	if key.is_empty():
		assembler.registry = CharForgeRegistry.default_registry()
		mount.clear()
		assembler.apply_config(cfg)
		_emit_config_changed()
		return
	var entry := CharForgePacks.entry(key)
	if entry.is_empty():
		# An unknown key used to `return` here in silence, leaving the previous
		# model mounted — so _on_bridge_pack still emitted mount_report() and the
		# page received a report describing the model already on stage. The UI
		# read that as success and wrote an optimistic status line, which is how
		# TEN dead catalogue entries survived unnoticed after a rename moved the
		# lane keys in the frontend but not in the engine's own lanes.json.
		#
		# Fail LOUDLY and specifically instead: drop what is on stage so no stale
		# model can be mistaken for the requested one, and record the key that
		# was asked for. mount_report() then answers mounted:false with a reason,
		# which the selector renders.
		_pack_key = ""
		# ORDER MATTERS, twice over:
		#  * the registry must go back to procedural FIRST. Leaving the previous
		#    model's registry in place means apply_config() below still resolves a
		#    mesh basis for this family and re-mounts the very model we are trying
		#    to drop — the stale-model-reported-as-success bug, one layer down.
		#  * last_error must be set AFTER apply_config(), because mount() opens
		#    with `last_error = ""`; setting it first lets any re-entrant mount
		#    silently erase the reason.
		assembler.registry = CharForgeRegistry.default_registry()
		mount.clear()
		assembler.apply_config(cfg)
		var reason := "unknown pack key '%s' — not in this engine's catalogue" % key
		mount.last_error = reason
		push_warning("CharForgeStudio: %s" % reason)
		_emit_config_changed()
		return
	assembler.registry = CharForgePacks.registry_for(key)
	if String(entry.get("mount", "")) == "body":
		cfg.body.archetype = String(entry.get("archetype", "humanoid"))
	else:
		cfg.head.family = String(entry.get("family", "humanoid"))
		cfg.body.archetype = String(entry.get("archetype", "humanoid"))
	assembler.apply_config(cfg)
	_emit_config_changed()


## Loads USER bytes as the avatar. The bytes are parsed in-process and are
## never written to disk or uploaded — the host page reads the file locally and
## hands them straight over the bridge.
func import_model(bytes: PackedByteArray, file_name: String) -> Dictionary:
	_pack_key = ""
	assembler.registry = CharForgeRegistry.default_registry()
	var report := mount.import_bytes(bytes, file_name)
	return report


func _on_mesh_basis_required(kind: String, entry: Dictionary) -> void:
	mount.mount(kind, entry)


func _emit_config_changed() -> void:
	if _bridge != null:
		_bridge.emit_config_changed(JSON.stringify(assembler.config))


# =============================================================================
# Built-in editor UI


func _edited_config() -> Dictionary:
	return assembler.config.duplicate(true)


func _commit(cfg: Dictionary) -> void:
	assembler.apply_config(cfg)
	_emit_config_changed()
