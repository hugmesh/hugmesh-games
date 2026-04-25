extends Node

# Manages the intro loading overlay, polls window._twSceneReady (set by
# game_bridge.gd or the HTML prefetch snippet), routes to the correct scene
# based on sceneType, spawns scene elements, then triggers the cinematic intro.

var _done := false
var _t := 0.0
var _sun_node: DirectionalLight3D = null

var _overlay: CanvasLayer = null
var _overlay_root: Control = null
var _status_label: Label = null

func _ready() -> void:
	_build_loading_overlay()
	if not OS.has_feature("web"):
		call_deferred("_finish_loading")

func _build_loading_overlay() -> void:
	_overlay = CanvasLayer.new()
	_overlay.layer = 100
	_overlay.name = "LoadingOverlay"

	_overlay_root = Control.new()
	_overlay_root.name = "OverlayRoot"
	_overlay_root.set_anchors_preset(Control.PRESET_FULL_RECT)
	_overlay.add_child(_overlay_root)

	var bg := ColorRect.new()
	bg.color = Color(0.02, 0.02, 0.06, 1.0)
	bg.set_anchors_preset(Control.PRESET_FULL_RECT)
	_overlay_root.add_child(bg)

	var center := CenterContainer.new()
	center.set_anchors_preset(Control.PRESET_FULL_RECT)
	_overlay_root.add_child(center)

	var vbox := VBoxContainer.new()
	vbox.alignment = BoxContainer.ALIGNMENT_CENTER
	center.add_child(vbox)

	var title := Label.new()
	title.text = "Three Wizards In A Trenchcoat"
	title.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	title.add_theme_font_size_override("font_size", 38)
	title.add_theme_color_override("font_color", Color(0.75, 0.85, 1.0))
	vbox.add_child(title)

	var gap := Control.new()
	gap.custom_minimum_size = Vector2(0, 28)
	vbox.add_child(gap)

	_status_label = Label.new()
	_status_label.text = "Loading"
	_status_label.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	_status_label.add_theme_font_size_override("font_size", 18)
	_status_label.add_theme_color_override("font_color", Color(0.5, 0.6, 0.85))
	vbox.add_child(_status_label)

	# Deferred: scene_loader._ready() runs while siblings are still _ready-ing,
	# so adding a CanvasLayer to the tree root synchronously fails.
	get_tree().root.add_child.call_deferred(_overlay)

func _process(delta: float) -> void:
	if _done:
		return

	if is_instance_valid(_status_label):
		@warning_ignore("integer_division")
		var n := int(Time.get_ticks_msec() / 450) % 4
		_status_label.text = "Loading" + ".".repeat(n)

	if not OS.has_feature("web"):
		return

	_t += delta
	if _t < 0.1:
		return
	_t = 0.0

	# JavaScriptBridge.eval returns Variant; bool() is the safe coercion.
	# `!!window._twSceneReady` returns a true JS boolean which marshals cleanly.
	var js_ready := bool(JavaScriptBridge.eval("!!window._twSceneReady"))
	if not js_ready:
		return
	_done = true

	var raw: Variant = JavaScriptBridge.eval("JSON.stringify(window._twSceneConfig || null)")
	var raw_str := str(raw) if raw != null else "null"
	print("[scene_loader] _twSceneReady=true, raw len=", raw_str.length())

	if raw_str != "" and raw_str != "null":
		var config = JSON.parse_string(raw_str)
		if config is Dictionary:
			print("[scene_loader] config dict, ", config.get("elements", []).size(), " elements")
			var scene_type := str(config.get("sceneType", "cave"))
			if scene_type == "field" and get_parent().name != "Field":
				get_tree().change_scene_to_file("res://scenes/field.tscn")
				return
			# Defer: _process() runs while the scene tree is still propagating _ready();
			# add_child from here fails with "Parent node is busy setting up children".
			_apply.call_deferred(config)
		else:
			push_warning("[scene_loader] config is not a Dictionary: ", typeof(config))
	else:
		push_warning("[scene_loader] _twSceneConfig empty/null after _twSceneReady=true")

	_finish_loading()

func _finish_loading() -> void:
	_done = true

	var bridge: Node = get_parent().get_node_or_null("GameBridge")
	if bridge and bridge.has_signal("time_updated"):
		bridge.time_updated.connect(_on_time_updated)

	var cam := get_parent().get_node_or_null("Player/CameraPivot")
	if cam and cam.has_method("play_intro"):
		cam.play_intro()

	var player := get_parent().get_node_or_null("Player")
	if player and player.has_method("start_drop"):
		player.start_drop()

	if is_instance_valid(_overlay_root):
		var ov := _overlay
		var tween := create_tween()
		tween.tween_property(_overlay_root, "modulate:a", 0.0, 1.4)
		tween.tween_callback(func(): if is_instance_valid(ov): ov.queue_free())

func _on_time_updated(t: Dictionary) -> void:
	if not is_instance_valid(_sun_node):
		return
	var hour   := int(t.get("hour",   12))
	var minute := int(t.get("minute",  0))
	var frac   := (hour * 60.0 + minute) / (24.0 * 60.0)
	# rotation_x: 0° at dawn (6 AM), -90° at noon, -180° at dusk
	_sun_node.rotation.x = deg_to_rad(-frac * 360.0 + 90.0)
	# energy follows a sine curve, positive only while sun is above horizon
	_sun_node.light_energy = maxf(0.0, sin((frac - 0.25) * TAU)) * 2.8

func _apply(config: Dictionary) -> void:
	for el in config.get("elements", []):
		_spawn(el)

func _spawn(el: Dictionary) -> void:
	match el.get("elementType", ""):
		"world_env":     _world_env(el)
		"sky_env":       _sky_env(el)
		"ambient_light": _ambient(el)
		"sun_light":     _sun_light(el)
		"torch":         _torch(el)
		"fill_light":    _fill_light(el)
		"fairy":         _fairy(el)
		"stalagmite":    _stalagmite(el)
		"stalactite":    _stalactite(el)
		"tree":          _tree(el)
		"cottage":       _cottage(el)
		"boulder":       _boulder(el)
		"water_pool":    _water_pool(el)
		"water_drip":    _water_drip(el)
		"light_shaft":   _light_shaft(el)

# ── Texture helpers ────────────────────────────────────────────────────────────

func _props(el: Dictionary) -> Dictionary:
	var raw = el.get("properties", "{}")
	var p = JSON.parse_string(str(raw))
	return p if p is Dictionary else {}

func _pos(el: Dictionary) -> Vector3:
	return Vector3(
		float(el.get("positionX", 0)),
		float(el.get("positionY", 0)),
		float(el.get("positionZ", 0))
	)

func _rot(el: Dictionary) -> Vector3:
	return Vector3(
		float(el.get("rotationX", 0)),
		float(el.get("rotationY", 0)),
		float(el.get("rotationZ", 0))
	)

# Fetch a PNG from url and apply it as albedo on mat. Falls back to the
# material's default colour if the fetch fails or the platform is not web.
func _load_texture_async(mat: StandardMaterial3D, url: String) -> void:
	if url == "" or not OS.has_feature("web"):
		return
	var http := HTTPRequest.new()
	add_child(http)
	http.request_completed.connect(func(result, _code, _headers, body):
		if result == HTTPRequest.RESULT_SUCCESS:
			var img := Image.new()
			if img.load_png_from_buffer(body) == OK:
				mat.albedo_texture = ImageTexture.create_from_image(img)
		http.queue_free()
	)
	if http.request(url) != OK:
		http.queue_free()

func _fairy_color(color_name: String) -> Color:
	match color_name:
		"green":  return Color(0.3, 1.0, 0.5)
		"blue":   return Color(0.2, 0.6, 1.0)
		_:        return Color(0.8, 0.3, 1.0)

# ── Environment spawners ───────────────────────────────────────────────────────

func _world_env(el: Dictionary) -> void:
	var p := _props(el)
	var env := Environment.new()
	env.background_mode = Environment.BG_COLOR
	env.background_color = Color(0.01, 0.01, 0.03)
	env.ambient_light_source = Environment.AMBIENT_SOURCE_COLOR
	env.ambient_light_color = Color(
		float(p.get("ambientColorR", 0.55)),
		float(p.get("ambientColorG", 0.65)),
		float(p.get("ambientColorB", 0.85))
	)
	env.ambient_light_energy = float(p.get("ambientEnergy", 1.8))
	env.fog_enabled = true
	env.fog_light_color = Color(
		float(p.get("fogR", 0.3)),
		float(p.get("fogG", 0.4)),
		float(p.get("fogB", 0.6))
	)
	env.fog_density = float(p.get("fogDensity", 0.003))
	_set_world_env(env)

func _sky_env(el: Dictionary) -> void:
	var p := _props(el)
	var sky_mat := ProceduralSkyMaterial.new()
	sky_mat.sky_top_color = Color(
		float(p.get("skyTopR", 0.18)),
		float(p.get("skyTopG", 0.42)),
		float(p.get("skyTopB", 0.88))
	)
	sky_mat.sky_horizon_color = Color(
		float(p.get("horizonR", 0.72)),
		float(p.get("horizonG", 0.87)),
		float(p.get("horizonB", 1.0))
	)
	sky_mat.ground_horizon_color = Color(
		float(p.get("groundHR", 0.32)),
		float(p.get("groundHG", 0.28)),
		float(p.get("groundHB", 0.22))
	)
	var sky := Sky.new()
	sky.sky_material = sky_mat
	var env := Environment.new()
	env.background_mode = Environment.BG_SKY
	env.sky = sky
	env.ambient_light_source = Environment.AMBIENT_SOURCE_SKY
	env.ambient_light_energy = float(p.get("ambientEnergy", 1.1))
	env.fog_enabled = false
	_set_world_env(env)

func _set_world_env(env: Environment) -> void:
	var existing: WorldEnvironment = get_parent().get_node_or_null("WorldEnvironment")
	if existing:
		existing.environment = env
	else:
		var we := WorldEnvironment.new()
		we.name = "WorldEnvironment"
		we.environment = env
		get_parent().add_child(we)

# ── Light spawners ─────────────────────────────────────────────────────────────

func _ambient(el: Dictionary) -> void:
	var p := _props(el)
	var light := DirectionalLight3D.new()
	light.name = el.get("name", "AmbientLight")
	light.position = _pos(el)
	light.rotation = _rot(el)
	light.light_color = Color(
		float(p.get("colorR", 0.55)),
		float(p.get("colorG", 0.65)),
		float(p.get("colorB", 0.85))
	)
	light.light_energy = float(p.get("energy", 1.2))
	light.shadow_enabled = false
	get_parent().add_child(light)

func _sun_light(el: Dictionary) -> void:
	var p := _props(el)
	var light := DirectionalLight3D.new()
	light.name = el.get("name", "SunLight")
	light.rotation = _rot(el)
	light.light_color = Color(
		float(p.get("colorR", 1.0)),
		float(p.get("colorG", 0.95)),
		float(p.get("colorB", 0.82))
	)
	light.light_energy = float(p.get("energy", 2.8))
	light.shadow_enabled = true
	get_parent().add_child(light)
	_sun_node = light

func _torch(el: Dictionary) -> void:
	var p := _props(el)
	var light := OmniLight3D.new()
	light.name = el.get("name", "Torch")
	light.position = _pos(el)
	light.light_color = Color(
		float(p.get("colorR", 1.0)),
		float(p.get("colorG", 0.5)),
		float(p.get("colorB", 0.1))
	)
	light.light_energy = float(p.get("energy", 3.0))
	light.omni_range = float(p.get("range", 18.0))
	get_parent().add_child(light)

func _fill_light(el: Dictionary) -> void:
	var p := _props(el)
	var light := OmniLight3D.new()
	light.name = el.get("name", "FillLight")
	light.position = _pos(el)
	light.light_color = Color(
		float(p.get("colorR", 0.5)),
		float(p.get("colorG", 0.65)),
		float(p.get("colorB", 0.95))
	)
	light.light_energy = float(p.get("energy", 2.5))
	light.omni_range = float(p.get("range", 45.0))
	get_parent().add_child(light)

# ── Particle / character spawners ──────────────────────────────────────────────

func _fairy(el: Dictionary) -> void:
	var p := _props(el)
	var color := _fairy_color(str(p.get("color", "green")))

	var fairy := Node3D.new()
	fairy.name = el.get("name", "Fairy")
	fairy.position = _pos(el)
	fairy.set_script(load("res://scripts/fairy.gd"))

	var glow := OmniLight3D.new()
	glow.light_color = color
	glow.light_energy = 1.5
	glow.omni_range = 6.0
	fairy.add_child(glow)

	var particles := GPUParticles3D.new()
	var proc := ParticleProcessMaterial.new()
	proc.emission_shape = ParticleProcessMaterial.EMISSION_SHAPE_SPHERE
	proc.emission_sphere_radius = 0.15
	proc.gravity = Vector3.ZERO
	proc.spread = 180.0
	proc.initial_velocity_min = 0.05
	proc.initial_velocity_max = 0.35
	proc.scale_min = 1.0
	proc.scale_max = 3.0
	proc.color = color
	proc.turbulence_enabled = true
	proc.turbulence_noise_strength = 1.0
	proc.turbulence_noise_scale = 2.5
	var spark_mesh := SphereMesh.new()
	spark_mesh.radius = 0.04
	spark_mesh.height = 0.08
	var spark_mat := StandardMaterial3D.new()
	spark_mat.flags_unshaded = true
	spark_mat.vertex_color_use_as_albedo = true
	spark_mat.emission_enabled = true
	spark_mat.emission = color
	spark_mat.emission_energy_multiplier = 6.0
	spark_mesh.surface_set_material(0, spark_mat)
	particles.draw_pass_1 = spark_mesh
	particles.process_material = proc
	particles.amount = 24
	particles.lifetime = 1.8
	particles.randomness = 0.5
	fairy.add_child(particles)

	get_parent().add_child(fairy)

# ── Geometry spawners ──────────────────────────────────────────────────────────

func _stalagmite(el: Dictionary) -> void:
	var p := _props(el)
	var csg := CSGCylinder3D.new()
	csg.name = el.get("name", "Stalagmite")
	csg.position = _pos(el)
	csg.radius = float(p.get("radius", 0.4))
	csg.height = float(p.get("height", 3.5))
	var mat := StandardMaterial3D.new()
	mat.albedo_color = Color(0.18, 0.14, 0.12)
	mat.roughness = 0.95
	csg.material_override = mat
	get_parent().add_child(csg)

func _stalactite(el: Dictionary) -> void:
	var p := _props(el)
	var csg := CSGCylinder3D.new()
	csg.name = el.get("name", "Stalactite")
	csg.position = _pos(el)
	csg.rotation.z = PI
	csg.radius = float(p.get("radius", 0.4))
	csg.height = float(p.get("height", 5.0))
	var mat := StandardMaterial3D.new()
	mat.albedo_color = Color(0.18, 0.14, 0.12)
	mat.roughness = 0.95
	csg.material_override = mat
	get_parent().add_child(csg)

func _tree(el: Dictionary) -> void:
	var p := _props(el)
	var trunk_h  := float(p.get("trunkHeight", 3.8))
	var foliage_r := float(p.get("foliageRadius", 2.4))

	var tree := Node3D.new()
	tree.name = el.get("name", "Tree")
	tree.position = _pos(el)
	tree.rotation = _rot(el)

	var trunk := CSGCylinder3D.new()
	trunk.radius = float(p.get("trunkRadius", 0.28))
	trunk.height = trunk_h
	trunk.position.y = trunk_h * 0.5
	var trunk_mat := StandardMaterial3D.new()
	trunk_mat.albedo_color = Color(0.35, 0.22, 0.10)
	trunk_mat.roughness = 1.0
	trunk.material_override = trunk_mat
	_load_texture_async(trunk_mat, str(p.get("textureUrl", "")))
	tree.add_child(trunk)

	var foliage := CSGSphere3D.new()
	foliage.radius = foliage_r
	foliage.position.y = trunk_h + foliage_r * 0.65
	var foliage_mat := StandardMaterial3D.new()
	foliage_mat.albedo_color = Color(0.20, 0.55, 0.15)
	foliage_mat.roughness = 0.88
	foliage.material_override = foliage_mat
	tree.add_child(foliage)

	get_parent().add_child(tree)

func _cottage(el: Dictionary) -> void:
	var p := _props(el)
	var w    := float(p.get("width",      7.0))
	var d    := float(p.get("depth",      9.0))
	var wall_h := float(p.get("wallHeight", 4.5))

	var cottage := Node3D.new()
	cottage.name = el.get("name", "Cottage")
	cottage.position = _pos(el)
	cottage.rotation = _rot(el)

	# Walls
	var walls := CSGBox3D.new()
	walls.size = Vector3(w, wall_h, d)
	walls.position.y = wall_h * 0.5
	walls.use_collision = true
	var wall_mat := StandardMaterial3D.new()
	wall_mat.albedo_color = Color(0.72, 0.62, 0.48)
	wall_mat.roughness = 0.88
	walls.material_override = wall_mat
	_load_texture_async(wall_mat, str(p.get("textureUrl", "")))
	cottage.add_child(walls)

	# Roof — wider box sitting on top of walls
	var roof := CSGBox3D.new()
	roof.size = Vector3(w + 1.2, 2.2, d + 1.2)
	roof.position.y = wall_h + 1.1
	var roof_mat := StandardMaterial3D.new()
	roof_mat.albedo_color = Color(0.42, 0.24, 0.14)
	roof_mat.roughness = 0.82
	roof.material_override = roof_mat
	cottage.add_child(roof)

	# Door — dark inset box (non-collidable, visual only)
	var door := CSGBox3D.new()
	door.size = Vector3(1.2, 2.5, 0.3)
	door.position = Vector3(0, 1.25, -(d * 0.5 + 0.05))
	var door_mat := StandardMaterial3D.new()
	door_mat.albedo_color = Color(0.22, 0.14, 0.08)
	door_mat.roughness = 0.9
	door.material_override = door_mat
	cottage.add_child(door)

	get_parent().add_child(cottage)

# ── Cave atmosphere (boulders, pools, drips, light shafts) ────────────────────

# Irregular dark rock — squashed sphere with random-ish per-instance scaling
# so identical properties still produce visually distinct boulders.
func _boulder(el: Dictionary) -> void:
	var p := _props(el)
	var radius := float(p.get("radius", 1.0))
	var height := float(p.get("height", 0.8))
	var seed_id := int(el.get("id", 0))

	var b := CSGSphere3D.new()
	b.name = el.get("name", "Boulder")
	b.radius = radius
	# Squash vertically and randomise to give an uneven rocky look.
	var rng := RandomNumberGenerator.new()
	rng.seed = seed_id * 1009 + 7
	var sx := 1.0 + rng.randf_range(-0.25, 0.35)
	var sz := 1.0 + rng.randf_range(-0.25, 0.35)
	var sy := clampf(height / max(radius, 0.001), 0.4, 1.4)
	b.scale = Vector3(sx, sy, sz)
	b.position = _pos(el)
	# Sit it half-buried so it reads as a boulder embedded in the floor.
	b.position.y += radius * sy * 0.55
	b.rotation = _rot(el)
	b.use_collision = true

	var mat := StandardMaterial3D.new()
	mat.albedo_color = Color(
		float(p.get("colorR", 0.20)),
		float(p.get("colorG", 0.18)),
		float(p.get("colorB", 0.16))
	)
	mat.roughness = 0.96
	mat.metallic = 0.05
	b.material_override = mat
	get_parent().add_child(b)

# Translucent still-water pool — flat plane with subtle emission so it catches
# light from torches and shafts.
func _water_pool(el: Dictionary) -> void:
	var p := _props(el)
	var width := float(p.get("width", 4.0))
	var depth := float(p.get("depth", 4.0))

	var pool := CSGBox3D.new()
	pool.name = el.get("name", "WaterPool")
	pool.size = Vector3(width, 0.08, depth)
	pool.position = _pos(el)

	var mat := StandardMaterial3D.new()
	var col := Color(
		float(p.get("colorR", 0.12)),
		float(p.get("colorG", 0.32)),
		float(p.get("colorB", 0.55)),
		0.65,
	)
	mat.albedo_color = col
	mat.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
	mat.metallic = 0.6
	mat.roughness = 0.18
	mat.emission_enabled = true
	mat.emission = col
	mat.emission_energy_multiplier = float(p.get("emission", 0.4))
	pool.material_override = mat
	get_parent().add_child(pool)

# Falling water droplet stream from the ceiling. Uses GPU particles with
# downward gravity; each drop is a tiny emissive sphere that fades on landing.
func _water_drip(el: Dictionary) -> void:
	var p := _props(el)
	var rate := float(p.get("rate", 3.0))
	var color := Color(
		float(p.get("colorR", 0.45)),
		float(p.get("colorG", 0.65)),
		float(p.get("colorB", 0.95))
	)

	var drip := GPUParticles3D.new()
	drip.name = el.get("name", "Drip")
	drip.position = _pos(el)
	drip.amount = max(8, int(rate * 4))
	drip.lifetime = 1.6
	drip.preprocess = 0.5

	var proc := ParticleProcessMaterial.new()
	proc.emission_shape = ParticleProcessMaterial.EMISSION_SHAPE_BOX
	proc.emission_box_extents = Vector3(0.05, 0.0, 0.05)
	proc.gravity = Vector3(0, -9.0, 0)
	proc.initial_velocity_min = 0.2
	proc.initial_velocity_max = 0.5
	proc.scale_min = 0.6
	proc.scale_max = 1.0
	proc.color = color
	drip.process_material = proc

	var drop_mesh := SphereMesh.new()
	drop_mesh.radius = 0.06
	drop_mesh.height = 0.18  # elongated droplet
	var drop_mat := StandardMaterial3D.new()
	drop_mat.albedo_color = color
	drop_mat.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
	drop_mat.emission_enabled = true
	drop_mat.emission = color
	drop_mat.emission_energy_multiplier = 1.4
	drop_mesh.surface_set_material(0, drop_mat)
	drip.draw_pass_1 = drop_mesh

	get_parent().add_child(drip)

# Bright pillar of light from a hole in the ceiling. SpotLight pointing
# straight down + a faint cone mesh so the shaft is visible against fog.
func _light_shaft(el: Dictionary) -> void:
	var p := _props(el)
	var radius := float(p.get("radius", 2.5))
	var height := float(p.get("height", 18.0))
	var energy := float(p.get("energy", 4.0))
	var color := Color(
		float(p.get("colorR", 1.0)),
		float(p.get("colorG", 0.95)),
		float(p.get("colorB", 0.78))
	)

	var shaft := Node3D.new()
	shaft.name = el.get("name", "LightShaft")
	shaft.position = _pos(el)

	# The actual light. SpotLight aimed straight down; shadows on so boulders
	# break up the beam on the floor.
	var light := SpotLight3D.new()
	light.light_color = color
	light.light_energy = energy
	light.spot_range = height + 4.0
	light.spot_angle = rad_to_deg(atan(radius / max(height, 0.001)))
	light.shadow_enabled = true
	light.rotation = Vector3(deg_to_rad(-90.0), 0.0, 0.0)
	shaft.add_child(light)

	# Visible cone — a stretched cylinder with additive blending so the beam
	# reads through the cave's fog instead of looking like an abstract dim spot.
	var cone := MeshInstance3D.new()
	var cyl := CylinderMesh.new()
	cyl.top_radius = 0.4
	cyl.bottom_radius = radius
	cyl.height = height
	cone.mesh = cyl
	cone.position.y = -height * 0.5
	var cone_mat := StandardMaterial3D.new()
	cone_mat.albedo_color = Color(color.r, color.g, color.b, 0.18)
	cone_mat.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
	cone_mat.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	cone_mat.blend_mode = BaseMaterial3D.BLEND_MODE_ADD
	cone_mat.cull_mode = BaseMaterial3D.CULL_DISABLED
	cone.material_override = cone_mat
	shaft.add_child(cone)

	get_parent().add_child(shaft)
