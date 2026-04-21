extends Node

# Polls window._twSceneConfig (set by game_bridge.gd) then spawns all scene
# elements (lights, fairies, world environment) from the API response.

var _done := false
var _t := 0.0

func _process(delta: float) -> void:
	if _done or not OS.has_feature("web"):
		return
	_t += delta
	if _t < 0.1:
		return
	_t = 0.0
	var ready: Variant = JavaScriptBridge.eval("window._twSceneReady === true")
	if ready != true:
		return
	_done = true
	var raw: Variant = JavaScriptBridge.eval("JSON.stringify(window._twSceneConfig)")
	if not raw or raw == "null":
		return
	var config = JSON.parse_string(str(raw))
	if config is Dictionary:
		_apply(config)

func _apply(config: Dictionary) -> void:
	for el in config.get("elements", []):
		_spawn(el)

func _spawn(el: Dictionary) -> void:
	match el.get("elementType", ""):
		"world_env":    _world_env(el)
		"ambient_light": _ambient(el)
		"torch":        _torch(el)
		"fill_light":   _fill_light(el)
		"fairy":        _fairy(el)

# ── Helpers ───────────────────────────────────────────────────────────────────

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

func _fairy_color(name: String) -> Color:
	match name:
		"green":  return Color(0.3, 1.0, 0.5)
		"blue":   return Color(0.2, 0.6, 1.0)
		_:        return Color(0.8, 0.3, 1.0)

# ── Spawners ──────────────────────────────────────────────────────────────────

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
	var we := WorldEnvironment.new()
	we.name = el.get("name", "WorldEnvironment")
	we.environment = env
	get_parent().add_child(we)

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
	proc.scale_min = 0.03
	proc.scale_max = 0.09
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
