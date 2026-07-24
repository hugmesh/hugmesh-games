extends Node3D

# Media surfaces — the room's shareable display planes:
#   table-1..6  six plates around the round table
#   screen-1..4 four wall screens
#   stage       the stage backdrop
#
# placeMedia(surfaceId, meta, pngBytes) textures the plane with a decoded PNG
# (bytes arrive via the Chat3D bridge as base64 — Matrix media is fetched and
# authenticated by the EMBEDDING page, never from inside the wasm) and shows
# the moderated label. clearMedia() restores the idle noir look. Activation
# (click / XR select toward a surface) is resolved mathematically against the
# plane quads — no physics bodies needed.

signal surface_activated(surface_id: String)

const MAX_LABEL := 80
const IDLE_COLOR := Color(0.06, 0.07, 0.13)
const IDLE_EMISSION := Color(0.13, 0.32, 0.36)

## surfaceId → scene child name.
const SURFACE_NODES := {
	"table-1": "Table1", "table-2": "Table2", "table-3": "Table3",
	"table-4": "Table4", "table-5": "Table5", "table-6": "Table6",
	"screen-1": "Screen1", "screen-2": "Screen2", "screen-3": "Screen3",
	"screen-4": "Screen4", "stage": "StageBackdrop",
}

var _labels: Dictionary = {}   # surfaceId → Label3D


func _ready() -> void:
	for surface_id in SURFACE_NODES:
		var mesh := _mesh_for(surface_id)
		if mesh == null:
			push_error("[sotn] missing surface node for %s" % surface_id)
			continue
		mesh.material_override = _idle_material()
		var label := Label3D.new()
		label.name = "MediaLabel"
		label.pixel_size = 0.004
		label.position = Vector3(0, -_half_extents(mesh).y - 0.12, 0.01)
		label.modulate = Color(0.66, 0.68, 0.77)
		mesh.add_child(label)
		_labels[surface_id] = label


func place(surface_id: String, meta: Dictionary, png_bytes: PackedByteArray) -> void:
	var mesh := _mesh_for(surface_id)
	if mesh == null:
		return
	var mat := StandardMaterial3D.new()
	mat.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	if png_bytes.size() > 0:
		var img := Image.new()
		if img.load_png_from_buffer(png_bytes) == OK:
			mat.albedo_texture = ImageTexture.create_from_image(img)
			mat.albedo_color = Color.WHITE
		else:
			push_warning("[sotn] undecodable PNG for %s — placeholder shown" % surface_id)
			mat.albedo_color = Color(0.25, 0.1, 0.12)
	else:
		# Metadata-only placement (file / video poster pending / thread):
		# a dim slate with the label carrying the meaning.
		mat.albedo_color = Color(0.1, 0.12, 0.2)
	mesh.material_override = mat
	var label: Label3D = _labels.get(surface_id)
	if label != null:
		label.text = str(meta.get("label", "")).substr(0, MAX_LABEL)


func clear(surface_id: String) -> void:
	var mesh := _mesh_for(surface_id)
	if mesh == null:
		return
	mesh.material_override = _idle_material()
	var label: Label3D = _labels.get(surface_id)
	if label != null:
		label.text = ""


func clear_all() -> void:
	for surface_id in SURFACE_NODES:
		clear(surface_id)


## Ray → surface hit test (plane intersection inside the quad's extents).
## Returns the surface id, or "" when nothing is hit within max_dist.
func surface_at_ray(origin: Vector3, dir: Vector3, max_dist := 30.0) -> String:
	var best_id := ""
	var best_t := max_dist
	for surface_id in SURFACE_NODES:
		var mesh := _mesh_for(surface_id)
		if mesh == null:
			continue
		var normal := mesh.global_transform.basis.z.normalized()
		var denom := dir.dot(normal)
		if absf(denom) < 0.0001:
			continue
		var t := (mesh.global_position - origin).dot(normal) / denom
		if t <= 0.0 or t >= best_t:
			continue
		var hit := origin + dir * t
		var local := mesh.global_transform.affine_inverse() * hit
		var half := _half_extents(mesh)
		if absf(local.x) <= half.x and absf(local.y) <= half.y:
			best_id = surface_id
			best_t = t
	return best_id


func activate(surface_id: String) -> void:
	if SURFACE_NODES.has(surface_id):
		surface_activated.emit(surface_id)


func _mesh_for(surface_id: String) -> MeshInstance3D:
	var node_name: String = SURFACE_NODES.get(surface_id, "")
	if node_name == "":
		return null
	return get_node_or_null(NodePath(node_name)) as MeshInstance3D


func _half_extents(mesh: MeshInstance3D) -> Vector2:
	var quad := mesh.mesh as QuadMesh
	if quad != null:
		return quad.size * 0.5
	return Vector2(0.5, 0.5)


func _idle_material() -> StandardMaterial3D:
	var mat := StandardMaterial3D.new()
	mat.albedo_color = IDLE_COLOR
	mat.emission_enabled = true
	mat.emission = IDLE_EMISSION
	mat.emission_energy_multiplier = 0.35
	mat.roughness = 0.2
	return mat
