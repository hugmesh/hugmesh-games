extends Node3D

# Self-contained spinning regular dodecahedron. Used by the standalone
# "Dodecahedron Demo" game (no character, no HUD, no SSE) — the scene
# boots directly without going through scene_loader once we're in the
# dodecahedron WASM bundle.

const PHI: float = 1.618033988749894
const INV_PHI: float = 0.6180339887498948

@export var rotation_speed_y: float = 0.55
@export var rotation_speed_x: float = 0.32

var _spinner: MeshInstance3D


func _ready() -> void:
	_setup_camera_and_light()
	_spinner = _make_dodecahedron()
	add_child(_spinner)


func _process(delta: float) -> void:
	if _spinner:
		_spinner.rotate_y(rotation_speed_y * delta)
		_spinner.rotate_x(rotation_speed_x * delta)


func _setup_camera_and_light() -> void:
	var cam := Camera3D.new()
	cam.name = "Camera"
	cam.position = Vector3(0, 0, 6.5)
	add_child(cam)

	var env := Environment.new()
	env.background_mode = Environment.BG_COLOR
	env.background_color = Color(0.04, 0.05, 0.10)
	env.ambient_light_source = Environment.AMBIENT_SOURCE_COLOR
	env.ambient_light_color = Color(0.45, 0.55, 0.78)
	env.ambient_light_energy = 0.55
	env.glow_enabled = true
	env.glow_intensity = 0.4

	var we := WorldEnvironment.new()
	we.environment = env
	add_child(we)

	var key := DirectionalLight3D.new()
	key.name = "KeyLight"
	key.rotation_degrees = Vector3(-45, 35, 0)
	key.light_energy = 1.4
	key.light_color = Color(1.0, 0.92, 0.78)
	add_child(key)

	var fill := DirectionalLight3D.new()
	fill.name = "FillLight"
	fill.rotation_degrees = Vector3(-15, -130, 0)
	fill.light_energy = 0.45
	fill.light_color = Color(0.55, 0.7, 1.0)
	add_child(fill)


func _make_dodecahedron() -> MeshInstance3D:
	var mi := MeshInstance3D.new()
	mi.name = "Dodecahedron"
	mi.mesh = _build_dodecahedron_mesh()

	var mat := StandardMaterial3D.new()
	mat.albedo_color = Color(0.95, 0.45, 0.18)  # snapdragon orange
	mat.metallic = 0.5
	mat.roughness = 0.32
	mat.emission_enabled = true
	mat.emission = Color(0.45, 0.18, 0.05)
	mat.emission_energy_multiplier = 0.35
	mi.set_surface_override_material(0, mat)

	return mi


# Builds a regular dodecahedron from the 20 canonical (φ, 1, 1/φ) vertices
# and 12 pentagonal faces. Triangle winding is auto-corrected per face: for
# a convex shape centred on the origin, the outward normal must point in
# roughly the same direction as the face centroid.
func _build_dodecahedron_mesh() -> ArrayMesh:
	var v: Array[Vector3] = [
		Vector3( 1,  1,  1), Vector3( 1,  1, -1),
		Vector3( 1, -1,  1), Vector3( 1, -1, -1),
		Vector3(-1,  1,  1), Vector3(-1,  1, -1),
		Vector3(-1, -1,  1), Vector3(-1, -1, -1),
		Vector3( 0,  INV_PHI,  PHI), Vector3( 0,  INV_PHI, -PHI),
		Vector3( 0, -INV_PHI,  PHI), Vector3( 0, -INV_PHI, -PHI),
		Vector3( PHI, 0,  INV_PHI), Vector3( PHI, 0, -INV_PHI),
		Vector3(-PHI, 0,  INV_PHI), Vector3(-PHI, 0, -INV_PHI),
		Vector3( INV_PHI,  PHI, 0), Vector3( INV_PHI, -PHI, 0),
		Vector3(-INV_PHI,  PHI, 0), Vector3(-INV_PHI, -PHI, 0),
	]
	var faces := [
		[0, 8, 10, 2, 12],
		[0, 12, 13, 1, 16],
		[0, 16, 18, 4, 8],
		[1, 13, 3, 11, 9],
		[1, 9, 5, 18, 16],
		[2, 10, 6, 19, 17],
		[2, 17, 3, 13, 12],
		[3, 17, 19, 7, 11],
		[4, 18, 5, 15, 14],
		[4, 14, 6, 10, 8],
		[5, 9, 11, 7, 15],
		[6, 14, 15, 7, 19],
	]

	var st := SurfaceTool.new()
	st.begin(Mesh.PRIMITIVE_TRIANGLES)
	for f in faces:
		var centroid := Vector3.ZERO
		for i in f:
			centroid += v[i]
		centroid /= float(f.size())

		var idx: Array = f.duplicate()
		var probe := (v[idx[1]] - v[idx[0]]).cross(v[idx[2]] - v[idx[0]])
		if probe.dot(centroid) < 0.0:
			idx.reverse()

		var n := (v[idx[1]] - v[idx[0]]).cross(v[idx[2]] - v[idx[0]]).normalized()
		st.set_normal(n)

		# Triangle fan from idx[0].
		for j in range(1, idx.size() - 1):
			st.add_vertex(v[idx[0]])
			st.add_vertex(v[idx[j]])
			st.add_vertex(v[idx[j + 1]])

	return st.commit()
