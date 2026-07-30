extends Node3D

# Media surfaces — the room's shareable display planes:
#   table-1..6  six plates around the round table
#   screen-1..4 four wall screens
#   stage       the stage backdrop
#
# placeMedia(surfaceId, meta, imageBytes) textures the plane with the decoded
# image (bytes arrive via the Chat3D bridge as base64 — Matrix media is fetched
# and authenticated by the EMBEDDING page, never from inside the wasm) and shows
# the moderated label. clearMedia() restores the idle noir look. Activation
# (click / XR select toward a surface) is resolved mathematically against the
# plane quads — no physics bodies needed.
#
# THE BYTES ARE NOT NECESSARILY PNG. The host forwards the UNTRANSCODED original
# Matrix attachment — it has no image decoder of its own, so making it transcode
# would mean shipping one — and the channel's argument is named `pngBase64` only
# for historical reasons. Every JPEG or WebP attachment in the channel therefore
# used to hit `load_png_from_buffer`, fail, and show a red error plate with a
# warning that blamed the bytes. DECODE_FORMATS is the fix on this side, which
# is the cheap side. The warning path stays for genuinely undecodable bytes.
#
# Inbound size is capped upstream at chat3d_bridge.MAX_MEDIA_BYTES (the single
# inbound boundary), so nothing here has to bound the buffer again.

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


## Every image container the room can texture a surface with.
const DECODE_FORMATS: PackedStringArray = ["png", "jpg", "webp"]


## The container `bytes` declares itself to be, by magic number ("" = none of
## ours). Used only to try the RIGHT decoder first: Godot's loaders log an
## engine error on a mismatch, and the games shim republishes console output to
## the embedding page, so probing blindly would spam every JPEG placement.
static func sniff_format(b: PackedByteArray) -> String:
	if b.size() >= 8 and b[0] == 0x89 and b[1] == 0x50 and b[2] == 0x4E and b[3] == 0x47 \
			and b[4] == 0x0D and b[5] == 0x0A and b[6] == 0x1A and b[7] == 0x0A:
		return "png"
	if b.size() >= 3 and b[0] == 0xFF and b[1] == 0xD8 and b[2] == 0xFF:
		return "jpg"
	if b.size() >= 12 and b[0] == 0x52 and b[1] == 0x49 and b[2] == 0x46 and b[3] == 0x46 \
			and b[8] == 0x57 and b[9] == 0x45 and b[10] == 0x42 and b[11] == 0x50:
		return "webp"
	return ""


## Decode `bytes` as any format the room accepts. Returns null only when EVERY
## decoder refused — that, and only that, is a genuinely undecodable payload.
##
## The sniffed container is tried first and the others after it, so a mislabeled
## or magic-less buffer that some decoder can still read is not thrown away.
static func decode_image(bytes: PackedByteArray) -> Image:
	if bytes.is_empty():
		return null
	var order: Array = []
	# The sniff only REORDERS the admitted formats — it can never admit one
	# DECODE_FORMATS does not list, or this constant would be decoration.
	var sniffed := sniff_format(bytes)
	if sniffed != "" and DECODE_FORMATS.has(sniffed):
		order.append(sniffed)
	for fmt in DECODE_FORMATS:
		if fmt != sniffed:
			order.append(fmt)
	for fmt in order:
		var img := Image.new()
		var err := ERR_UNAVAILABLE
		match fmt:
			"png":
				err = img.load_png_from_buffer(bytes)
			"jpg":
				err = img.load_jpg_from_buffer(bytes)
			"webp":
				err = img.load_webp_from_buffer(bytes)
		if err == OK:
			return img
	return null


func place(surface_id: String, meta: Dictionary, image_bytes: PackedByteArray) -> void:
	var mesh := _mesh_for(surface_id)
	if mesh == null:
		return
	var mat := StandardMaterial3D.new()
	mat.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	if image_bytes.size() > 0:
		var img := decode_image(image_bytes)
		if img != null:
			mat.albedo_texture = ImageTexture.create_from_image(img)
			mat.albedo_color = Color.WHITE
		else:
			push_warning(("[sotn] undecodable image for %s (not PNG/JPEG/WebP)"
					+ " — placeholder shown") % surface_id)
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


## Surface-id allow-list. Callers that accept a surface id off the wire (the
## session-mode SOTN_MEDIA action, the authoritative placement map) MUST filter
## through this — SURFACE_NODES is the only set of ids that map to real nodes.
func has_surface(surface_id: String) -> bool:
	return SURFACE_NODES.has(surface_id)


func surface_ids() -> Array:
	return SURFACE_NODES.keys()


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
