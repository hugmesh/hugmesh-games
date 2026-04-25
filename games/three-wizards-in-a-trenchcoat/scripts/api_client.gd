extends Node
## API client for loading level metadata, scene configs, and user-registered
## assets (textures/plugins) from the Spring Boot backend.
##
## Works in both runtime modes:
##   • Web export  — base URL inferred from window.location (same origin as host)
##   • Editor / native — falls back to API_BASE_DEV (override via env if needed)
##
## Usage from scene_loader.gd or a level main scene:
##     var api := ApiClient.new()
##     add_child(api)
##     api.fetch_scene(1, func(scene_dto): _apply(scene_dto))
##     api.fetch_level_assets(1, func(assets): ...)
##     api.resolve_asset(1, "oak-bark-texture", func(url): ...)

class_name ApiClient

const API_BASE_DEV := "http://localhost:8080"

signal scene_received(scene: Dictionary)
signal level_assets_received(assets: Array)
signal error(message: String)


func _base_url() -> String:
	if OS.has_feature("web"):
		# Same-origin: Angular → nginx → api. Leave empty so paths stay relative.
		return ""
	return API_BASE_DEV


## GET /api/levels/{id}/scene
func fetch_scene(level_id: int, on_done: Callable = Callable()) -> void:
	_get("/api/levels/%d/scene" % level_id, func(body):
		var parsed = JSON.parse_string(body)
		if parsed is Dictionary:
			scene_received.emit(parsed)
			if on_done.is_valid():
				on_done.call(parsed)
		else:
			error.emit("scene: malformed JSON")
	)


## GET /api/levels/{id}/assets — list of registered textures/plugins for this level
func fetch_level_assets(level_id: int, on_done: Callable = Callable()) -> void:
	_get("/api/levels/%d/assets" % level_id, func(body):
		var parsed = JSON.parse_string(body)
		if parsed is Array:
			level_assets_received.emit(parsed)
			if on_done.is_valid():
				on_done.call(parsed)
		else:
			error.emit("level-assets: malformed JSON")
	)


## GET /api/levels/{id}/assets/by-label/{label} → full asset URL for loading
func resolve_asset(level_id: int, label: String, on_done: Callable) -> void:
	_get("/api/levels/%d/assets/by-label/%s" % [level_id, label.uri_encode()], func(body):
		var parsed = JSON.parse_string(body)
		if parsed is Dictionary and parsed.has("filePath"):
			# filePath is relative to classpath assets/levels/{slug}/;
			# AssetController serves it back at /api/assets/levels/{slug}/{file}.
			# If slug isn't known here, callers should resolve via the scene DTO first.
			on_done.call(_base_url() + str(parsed["filePath"]))
		else:
			error.emit("resolve: label not found")
	)


## Generic GET helper with JSON Accept. Swallows transport errors onto the error signal.
func _get(path: String, on_body: Callable) -> void:
	var http := HTTPRequest.new()
	add_child(http)
	http.request_completed.connect(func(result, code, _headers, body):
		http.queue_free()
		if result != HTTPRequest.RESULT_SUCCESS or code >= 400:
			error.emit("http %s %d" % [path, code])
			return
		on_body.call(body.get_string_from_utf8())
	)
	var err := http.request(_base_url() + path, ["Accept: application/json"])
	if err != OK:
		http.queue_free()
		error.emit("request init failed for %s" % path)
