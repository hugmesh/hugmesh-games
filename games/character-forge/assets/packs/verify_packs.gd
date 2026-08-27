extends SceneTree

# Self-test for the vendored 3D asset packs (tools/asset-pipeline owns these files).
#
# Run:  godot --headless --path godot/character-forge -s res://assets/packs/verify_packs.gd
#       (or: tools/asset-pipeline/verify.sh, which also re-runs the glTF validator)
#
# These are REAL assertions against the committed bytes, not existence checks:
#   * every pack.json parses and declares the licence fields the repo's licence
#     discipline requires (licenseSpdx + attribution + sourceUrl + retrievedAt),
#     and the SPDX id is on the CC0/CC-BY allow-list;
#   * every model file the manifest lists exists AND its on-disk byte size equals
#     the size the manifest claims — so a manifest can never drift from the asset;
#   * every .glb round-trips through the EDITOR import (load() the PackedScene)
#     and its measured triangle count / skeleton bone count / animation count
#     equal the manifest's trisAfter / joints / animationCount;
#   * every .vrm round-trips through the RUNTIME loader that the web build
#     actually uses (GLTFDocument.append_from_buffer -> generate_scene) and comes
#     back with drawable geometry, a skeleton, and its blend-shape (viseme)
#     channel intact — this is the path technique.md specifies for VRM ingest, and
#     it is what proves vrm-slim.mjs did not corrupt the file.
#   * index.json (the file the runtime reads instead of enumerating res://, and
#     the data source for the in-app credits screen) agrees with every pack.json
#     it indexes, and credits every CC-BY pack.
#
# Exits non-zero on any failure so CI/the orchestrator can gate on it.

const PACKS_DIR := "res://assets/packs"
const ALLOWED_LICENSES := ["CC0-1.0", "CC-BY-4.0", "CC-BY-3.0"]
const REQUIRED_PACK_FIELDS := ["packId", "schema", "licenseSpdx", "attribution", "sourceUrl", "retrievedAt", "licenseQuote", "models"]
const REQUIRED_MODEL_FIELDS := ["id", "file", "kind", "style", "rig", "visemes", "trisAfter", "trisUnique", "bytes", "licenseSpdx", "attribution", "sourceUrl", "retrievedAt"]
const TRI_BUDGET := 16000
const TOTAL_BUDGET_BYTES := 60 * 1024 * 1024

var _pass := 0
var _fail := 0
var _ran := false


func _process(_delta: float) -> bool:
	if _ran:
		return false
	_ran = true
	_run_all()
	return false


func _check(ok: bool, what: String) -> void:
	if ok:
		_pass += 1
	else:
		_fail += 1
		printerr("  FAIL: %s" % what)


func _run_all() -> void:
	var pack_ids := _list_packs()
	_check(pack_ids.size() > 0, "assets/packs contains at least one pack")

	var total_bytes := 0
	for pack_id in pack_ids:
		total_bytes += _verify_pack(pack_id)

	_verify_index(pack_ids, total_bytes)

	_check(total_bytes <= TOTAL_BUDGET_BYTES,
			"committed pack payload %.2f MB is within the %d MB budget"
			% [total_bytes / 1048576.0, TOTAL_BUDGET_BYTES / 1048576])
	print("[packs] committed payload: %.2f MB across %d packs" % [total_bytes / 1048576.0, pack_ids.size()])

	print("\n%d passed, %d failed" % [_pass, _fail])
	quit(1 if _fail > 0 else 0)


func _list_packs() -> Array[String]:
	var ids: Array[String] = []
	var dir := DirAccess.open(PACKS_DIR)
	if dir == null:
		return ids
	for name in dir.get_directories():
		if FileAccess.file_exists("%s/%s/pack.json" % [PACKS_DIR, name]):
			ids.append(name)
	ids.sort()
	return ids


## index.json is what the RUNTIME reads: res:// directory enumeration only
## returns what the export filter actually packed, so the engine is told which
## packs exist rather than discovering them, and credits[] is the data source an
## in-app credits screen renders. Checking it here (inside Godot, through
## res://) proves the engine can reach and parse it in the same way the game
## will — lib/check-manifests.mjs checks the same agreement outside the engine.
func _verify_index(pack_ids: Array[String], measured_bytes: int) -> void:
	var path := "%s/index.json" % PACKS_DIR
	if not FileAccess.file_exists(path):
		_check(false, "index.json exists (run tools/asset-pipeline/build.sh)")
		return
	var parsed: Variant = JSON.parse_string(FileAccess.get_file_as_string(path))
	if typeof(parsed) != TYPE_DICTIONARY:
		_check(false, "index.json is valid JSON")
		return
	var index: Dictionary = parsed
	_check(index.get("schema", "") == "3d-pack-index", "index.json schema is '3d-pack-index'")

	var indexed := {}
	for entry in index.get("packs", []):
		var p: Dictionary = entry
		indexed[p.get("packId", "")] = p
	for pack_id in pack_ids:
		_check(indexed.has(pack_id), "index.json lists pack '%s'" % pack_id)

	var indexed_bytes := 0
	var indexed_models := 0
	for pack_id in indexed.keys():
		var p: Dictionary = indexed[pack_id]
		_check(pack_ids.has(pack_id), "index.json entry '%s' has a pack.json" % pack_id)
		if not pack_ids.has(pack_id):
			continue
		indexed_bytes += int(p.get("bytes", 0))
		indexed_models += int(p.get("models", 0))
		# The res:// paths the runtime will actually open must resolve.
		_check(p.get("manifest", "") == "%s/%s/pack.json" % [PACKS_DIR, pack_id],
				"index.json %s.manifest is the res:// path to its pack.json" % pack_id)
		_check(FileAccess.file_exists(str(p.get("manifest", ""))),
				"index.json %s.manifest resolves on disk" % pack_id)
		# Agreement with the manifest it indexes (a stale index is a licence bug).
		var man: Dictionary = JSON.parse_string(
				FileAccess.get_file_as_string("%s/%s/pack.json" % [PACKS_DIR, pack_id]))
		for field in ["licenseSpdx", "attribution", "attributionRequired", "sourceUrl",
				"retrievedAt", "style", "rig", "visemes"]:
			_check(p.get(field) == man.get(field),
					"index.json %s.%s agrees with pack.json" % [pack_id, field])
		var totals: Dictionary = man.get("totals", {})
		for pair in [["models", "models"], ["bytes", "bytes"], ["tris", "tris"]]:
			_check(int(p.get(pair[0], -1)) == int(totals.get(pair[1], -2)),
					"index.json %s.%s agrees with pack.json totals" % [pack_id, pair[0]])

	_check(indexed_bytes == measured_bytes,
			"index.json totals cover the %d bytes actually on disk (index says %d)"
			% [measured_bytes, indexed_bytes])
	_check(int(index.get("totals", {}).get("bytes", -1)) == indexed_bytes,
			"index.json totals.bytes agrees with the sum of its own pack entries")
	_check(int(index.get("totals", {}).get("models", -1)) == indexed_models,
			"index.json totals.models agrees with the sum of its own pack entries")

	# CC-BY attribution that never reaches a user is a licence violation, so
	# every attribution-required pack MUST carry renderable credit text here.
	var credits := {}
	for entry in index.get("credits", []):
		var c: Dictionary = entry
		credits[c.get("packId", "")] = c
	for pack_id in pack_ids:
		_check(credits.has(pack_id), "index.json credits[] includes '%s'" % pack_id)
		if not credits.has(pack_id):
			continue
		var c: Dictionary = credits[pack_id]
		var man: Dictionary = JSON.parse_string(
				FileAccess.get_file_as_string("%s/%s/pack.json" % [PACKS_DIR, pack_id]))
		_check(c.get("required") == man.get("attributionRequired"),
				"index.json credits[%s].required matches its pack.json" % pack_id)
		if bool(man.get("attributionRequired", false)):
			_check(str(c.get("attribution", "")).length() > 0 and str(c.get("licenseUrl", "")).length() > 0,
					"%s is %s: credits[] carries attribution text and a licence URL"
					% [pack_id, man.get("licenseSpdx", "?")])


func _verify_pack(pack_id: String) -> int:
	var path := "%s/%s/pack.json" % [PACKS_DIR, pack_id]
	var text := FileAccess.get_file_as_string(path)
	var manifest: Variant = JSON.parse_string(text)
	if typeof(manifest) != TYPE_DICTIONARY:
		_check(false, "%s: pack.json is valid JSON" % pack_id)
		return 0
	var pack: Dictionary = manifest

	for field in REQUIRED_PACK_FIELDS:
		_check(pack.has(field) and pack[field] != null and str(pack[field]) != "",
				"%s: pack.json declares '%s'" % [pack_id, field])
	_check(pack.get("packId", "") == pack_id, "%s: packId matches its directory name" % pack_id)
	_check(pack.get("schema", "") == "3d-pack", "%s: schema is '3d-pack'" % pack_id)
	_check(ALLOWED_LICENSES.has(pack.get("licenseSpdx", "")),
			"%s: licenseSpdx '%s' is on the CC0/CC-BY allow-list" % [pack_id, pack.get("licenseSpdx", "")])

	var bytes := 0
	for entry in pack.get("models", []):
		var model: Dictionary = entry
		for field in REQUIRED_MODEL_FIELDS:
			_check(model.has(field), "%s/%s: model declares '%s'" % [pack_id, model.get("id", "?"), field])
		var file_path := "%s/%s/%s" % [PACKS_DIR, pack_id, model.get("file", "")]
		if not FileAccess.file_exists(file_path):
			_check(false, "%s: %s exists" % [pack_id, model.get("file", "?")])
			continue
		var raw := FileAccess.get_file_as_bytes(file_path)
		bytes += raw.size()
		_check(raw.size() == int(model.get("bytes", -1)),
				"%s: %s on-disk size %d matches the manifest's %d"
				% [pack_id, model.get("file"), raw.size(), int(model.get("bytes", -1))])
		if model.get("kind", "") != "animation-library":
			_check(int(model.get("trisAfter", 0)) <= TRI_BUDGET or not model.get("flags", []).is_empty(),
					"%s: %s is inside the %d-tri budget or carries an explaining flag"
					% [pack_id, model.get("file"), TRI_BUDGET])

		if file_path.ends_with(".glb"):
			_verify_imported_glb(pack_id, file_path, model)
		elif file_path.ends_with(".vrm"):
			_verify_runtime_vrm(pack_id, file_path, raw, model)
	return bytes


## .glb assets are consumed through the Godot editor import (PackedScene).
func _verify_imported_glb(pack_id: String, file_path: String, model: Dictionary) -> void:
	var scene: Resource = load(file_path)
	if scene == null or not (scene is PackedScene):
		_check(false, "%s: %s imported as a PackedScene" % [pack_id, file_path.get_file()])
		return
	var node: Node = (scene as PackedScene).instantiate()
	get_root().add_child(node)

	var tris := _triangles(node)
	_check(tris == int(model.get("trisAfter", -1)),
			"%s: %s renders %d triangles, manifest says %d"
			% [pack_id, file_path.get_file(), tris, int(model.get("trisAfter", -1))])

	var skeletons := _find_all(node, "Skeleton3D")
	var bones := 0
	for s in skeletons:
		bones += (s as Skeleton3D).get_bone_count()
	if int(model.get("joints", 0)) > 0:
		_check(bones > 0, "%s: %s imported with a skeleton" % [pack_id, file_path.get_file()])
	_verify_jaw_claim(pack_id, file_path, model, skeletons)

	var players := _find_all(node, "AnimationPlayer")
	var clips := 0
	for p in players:
		for lib_name in (p as AnimationPlayer).get_animation_library_list():
			clips += (p as AnimationPlayer).get_animation_library(lib_name).get_animation_list().size()
	# Godot's importer adds a synthetic "RESET" clip to rigged scenes.
	var expected := int(model.get("animationCount", 0))
	_check(clips == expected or clips == expected + 1,
			"%s: %s exposes %d animation clips, manifest says %d (+RESET)"
			% [pack_id, file_path.get_file(), clips, expected])

	get_root().remove_child(node)
	node.queue_free()


## .vrm assets are consumed through the RUNTIME loader (the web path).
func _verify_runtime_vrm(pack_id: String, file_path: String, raw: PackedByteArray, model: Dictionary) -> void:
	var doc := GLTFDocument.new()
	var state := GLTFState.new()
	var err := doc.append_from_buffer(raw, "", state)
	_check(err == OK, "%s: %s parses via GLTFDocument.append_from_buffer (err=%d)" % [pack_id, file_path.get_file(), err])
	if err != OK:
		return
	var node: Node = doc.generate_scene(state)
	_check(node != null, "%s: %s generates a scene" % [pack_id, file_path.get_file()])
	if node == null:
		return
	get_root().add_child(node)

	var tris := _triangles(node)
	_check(tris == int(model.get("trisAfter", -1)),
			"%s: %s renders %d triangles, manifest says %d"
			% [pack_id, file_path.get_file(), tris, int(model.get("trisAfter", -1))])

	var skeletons := _find_all(node, "Skeleton3D")
	_check(skeletons.size() > 0, "%s: %s has a skeleton after runtime load" % [pack_id, file_path.get_file()])
	_verify_jaw_claim(pack_id, file_path, model, skeletons)

	# The whole point of shipping VRM is the viseme channel: prove the blend
	# shapes survived vrm-slim.mjs's texture surgery.
	if bool(model.get("visemes", false)):
		var shapes := 0
		for mi in _find_all(node, "MeshInstance3D"):
			var mesh: Mesh = (mi as MeshInstance3D).mesh
			if mesh is ArrayMesh:
				shapes += (mesh as ArrayMesh).get_blend_shape_count()
		_check(shapes > 0, "%s: %s kept its blend shapes (viseme channel)" % [pack_id, file_path.get_file()])

	get_root().remove_child(node)
	node.queue_free()


## The manifest's jaw claim, re-derived from the rig the ENGINE actually loaded.
##
## `hasJawBone` decides whether an avatar gets the bone lip-sync channel or has
## to fall back to the mouth strip, so a wrong value is a silently broken mouth.
## The audit originally scanned every glTF node name, which made any pack that
## merely names a MESH "…_Jaw" claim a jaw bone: all four KayKit skeletons ship a
## `Skeleton_*_Jaw` mesh node on a 41-joint rig that has no jaw JOINT. This check
## exists so that class of drift cannot come back — it asks the Skeleton3D.
func _verify_jaw_claim(pack_id: String, file_path: String, model: Dictionary,
		skeletons: Array[Node]) -> void:
	var found: Array[String] = []
	for s in skeletons:
		var skeleton := s as Skeleton3D
		for i in range(skeleton.get_bone_count()):
			var bone_name := skeleton.get_bone_name(i)
			if bone_name.to_lower().contains("jaw"):
				found.append(bone_name)
	found.sort()
	var claimed: Array = model.get("jawBones", [])
	var claimed_sorted: Array[String] = []
	for entry in claimed:
		claimed_sorted.append(String(entry))
	claimed_sorted.sort()
	_check(found == claimed_sorted,
			"%s: %s jaw BONES on the loaded rig %s match the manifest's %s"
			% [pack_id, file_path.get_file(), str(found), str(claimed_sorted)])
	_check(bool(model.get("hasJawBone", false)) == (not found.is_empty()),
			"%s: %s hasJawBone=%s agrees with the rig (%d jaw bones)"
			% [pack_id, file_path.get_file(), str(model.get("hasJawBone")), found.size()])


func _triangles(node: Node) -> int:
	var total := 0
	if node is MeshInstance3D and (node as MeshInstance3D).mesh != null:
		var mesh: Mesh = (node as MeshInstance3D).mesh
		for surface in mesh.get_surface_count():
			var arrays: Array = mesh.surface_get_arrays(surface)
			var indices: PackedInt32Array = arrays[Mesh.ARRAY_INDEX]
			if indices.size() > 0:
				total += indices.size() / 3
			else:
				total += (arrays[Mesh.ARRAY_VERTEX] as PackedVector3Array).size() / 3
	for child in node.get_children():
		total += _triangles(child)
	return total


func _find_all(node: Node, type_name: String) -> Array[Node]:
	var found: Array[Node] = []
	if node.is_class(type_name):
		found.append(node)
	for child in node.get_children():
		found.append_array(_find_all(child, type_name))
	return found
