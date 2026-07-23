extends Node
## Three-Wizards-specific API helpers — typed wrappers around the
## generic `BridgeApiClient` from the hugmesh_bridge addon.
##
## All networking flows through HostBridge → host shell → BFF; this
## class never calls HTTPRequest directly. The wrappers exist so
## scene_loader.gd / chapter scripts can keep their named-method API
## (fetch_scene / fetch_chapter_assets / resolve_asset) and the bridge
## handles auth + session routing transparently.

class_name ApiClient

# BridgeApiClient is a Rust class registered by the hugmesh_bridge
# GDExtension; instantiated by name (no preload needed).

signal scene_received(scene: Dictionary)
signal chapter_assets_received(assets: Array)
signal error(message: String)

var _api: Node = null


func _ready() -> void:
	_api = BridgeApiClient.new()
	add_child(_api)


## GET /api/chapters/{id}/scene
func fetch_scene(chapter_id: int, on_done: Callable = Callable()) -> void:
	_api.http_get("/api/chapters/%d/scene" % chapter_id, func(status, body):
		if status != 200 or not (body is Dictionary):
			error.emit("scene: status %d" % status)
			return
		scene_received.emit(body)
		if on_done.is_valid():
			on_done.call(body)
	)


## GET /api/chapters/{id}/assets — list of registered textures/plugins for this chapter.
func fetch_chapter_assets(chapter_id: int, on_done: Callable = Callable()) -> void:
	_api.http_get("/api/chapters/%d/assets" % chapter_id, func(status, body):
		if status != 200 or not (body is Array):
			error.emit("chapter-assets: status %d" % status)
			return
		chapter_assets_received.emit(body)
		if on_done.is_valid():
			on_done.call(body)
	)


## GET /api/chapters/{id}/assets/by-label/{label} → resolved asset URL.
func resolve_asset(chapter_id: int, label: String, on_done: Callable) -> void:
	var path := "/api/chapters/%d/assets/by-label/%s" % [chapter_id, label.uri_encode()]
	_api.http_get(path, func(status, body):
		if status != 200 or not (body is Dictionary) or not body.has("filePath"):
			error.emit("resolve: %s -> status %d" % [label, status])
			return
		on_done.call(str(body["filePath"]))
	)
