## Bridge-routed API client — preferred surface for any Godot → BFF call
## from a Snapdragon-hosted game. Wraps HostBridge.request_api with a
## Callable-based callback API; tracks pending requests by id and
## dispatches the matching response.
##
## Usage:
##     var api := BridgeApiClient.new()
##     add_child(api)
##     api.get("/api/chapters/1/scene", func(status, body):
##         if status == 200: _apply_scene(body))
##     api.post("/api/view", {"slot":0, "x":1.0, ...}, func(status, _b): pass)
##
## In streaming mode (web export), the request goes out via
## HostBridge.api_request_egress → host shell → HttpClient (with auth
## interceptor + X-Session-Id header) → SD_API_RESPONSE postMessage →
## HostBridge.api_response_received → us.
##
## In standalone mode (editor F5), HostBridge.is_streaming() is false
## and we fall back to direct HTTPRequest against API_BASE_DEV. This
## lets game devs press Play in the editor without the host shell.

class_name BridgeApiClient
extends Node

const API_BASE_DEV := "http://localhost:8080"

var _host_bridge: Node = null
var _pending: Dictionary = {} # request_id (int) -> Callable


func _ready() -> void:
	_host_bridge = _find_host_bridge()
	if _host_bridge != null:
		_host_bridge.api_response_received.connect(_on_api_response)


# ── Public API ───────────────────────────────────────────────────────

## GET /path. on_done = Callable(status: int, body: Variant).
func get(path: String, on_done: Callable = Callable()) -> void:
	_request("GET", path, null, on_done)

## POST /path with a Variant body (encoded as JSON).
func post(path: String, body: Variant = null, on_done: Callable = Callable()) -> void:
	_request("POST", path, body, on_done)

## PUT /path with a Variant body (encoded as JSON).
func put(path: String, body: Variant = null, on_done: Callable = Callable()) -> void:
	_request("PUT", path, body, on_done)

## DELETE /path.
func delete(path: String, on_done: Callable = Callable()) -> void:
	_request("DELETE", path, null, on_done)


# ── Internals ────────────────────────────────────────────────────────

func _find_host_bridge() -> Node:
	var sibling := get_parent().get_node_or_null("HostBridge")
	if sibling != null:
		return sibling
	if get_tree() and get_tree().current_scene:
		return get_tree().current_scene.get_node_or_null("HostBridge")
	return null


func _request(method: String, path: String, body: Variant, on_done: Callable) -> void:
	# Streaming mode: route through the bridge so the host shell does
	# the actual HTTP with the auth interceptor + session header.
	if _host_bridge != null and _host_bridge.is_streaming():
		var body_json := "null" if body == null else JSON.stringify(body)
		var id: int = _host_bridge.request_api(method, path, body_json)
		if on_done.is_valid():
			_pending[id] = on_done
		return

	# Standalone mode: direct HTTPRequest fallback for editor F5.
	var http := HTTPRequest.new()
	add_child(http)
	http.request_completed.connect(func(_r, code, _h, raw_body):
		http.queue_free()
		var parsed: Variant = null
		if raw_body and raw_body.size() > 0:
			parsed = JSON.parse_string(raw_body.get_string_from_utf8())
		if on_done.is_valid():
			on_done.call(code, parsed)
	)
	var headers := PackedStringArray(["Accept: application/json", "Content-Type: application/json"])
	var http_method: int = _http_method(method)
	var body_str := "" if body == null else JSON.stringify(body)
	var err := http.request(API_BASE_DEV + path, headers, http_method, body_str)
	if err != OK:
		http.queue_free()
		if on_done.is_valid():
			on_done.call(0, null)


static func _http_method(verb: String) -> int:
	match verb.to_upper():
		"GET":    return HTTPClient.METHOD_GET
		"POST":   return HTTPClient.METHOD_POST
		"PUT":    return HTTPClient.METHOD_PUT
		"DELETE": return HTTPClient.METHOD_DELETE
		"PATCH":  return HTTPClient.METHOD_PATCH
		_:        return HTTPClient.METHOD_GET


# Inbound response from HostBridge. Payload JSON shape:
#   {"requestId": int, "status": int, "body": <any JSON>}
func _on_api_response(payload_json: String) -> void:
	var parsed = JSON.parse_string(payload_json)
	if not (parsed is Dictionary):
		return
	var rid: int = int(parsed.get("requestId", -1))
	var cb: Variant = _pending.get(rid)
	if cb is Callable:
		_pending.erase(rid)
		var status: int = int(parsed.get("status", 0))
		var body: Variant = parsed.get("body", null)
		(cb as Callable).call(status, body)
