extends Node

# Session-mode transport — the standard wasm-game SD_* postMessage contract,
# implemented in PURE GDScript via JavaScriptBridge (this game deliberately
# ships without the Rust hugmesh_bridge GDExtension: it has no rules core
# to reuse, and the room must stay self-contained per the XR-chat design).
#
# Wire shapes match games/_shared/sse-host-client.js + the host shell's
# ApiBridgeService exactly:
#
#   OUT (iframe → host shell):
#     SD_SSE_SUBSCRIBE   {sessionId}                → host opens the EventSource
#     SD_SSE_UNSUBSCRIBE {sessionId}                → teardown
#     SD_API_REQUEST     {requestId, method, path, sessionId, body}
#     SD_SESSION_EXPIRED {}                         → host re-runs connect/claim
#   IN (host shell → iframe):
#     SD_SSE_EVENT       {payload:{type:<channel>, payload:<json>}}
#     SD_API_RESPONSE    {payload:{requestId, status, body}}
#
# Inbound messages are stashed on window-level queues by a tiny JS listener
# (installed once here) and DRAINED each frame from GDScript — the same
# polling pattern hugmesh-shim.js uses for _sdInputQueue, which avoids
# JavaScriptBridge.create_callback lifetime pitfalls.
#
# The host shell forwards these SSE channels into iframes: movement, world,
# character, time, scene, session-control. The room uses:
#   movement → seat/pose relay (SOTN_* control-action strings, see
#              room_controller.gd), world → host-authoritative room state
#              (kind:'world' type:'state' payload {type:'sotn.room', …}),
#   session-control → pause/resume/close.

signal movement_received(payload: Dictionary)
signal world_received(payload: Dictionary)
signal session_control_received(action: String)

const GAME_SLUG := "shadows-of-the-net"

var session_id := ""
var character_id := -1
var slot_index := -1
var chapter_id := -1
## Session multiplayer is only live when embedded with a session_id.
var session_live := false

var _req_id := 0
var _polling := false


func _ready() -> void:
	if not OS.has_feature("web"):
		return
	_read_url_params()
	_install_js_listener()
	if session_id != "":
		session_live = true
		_post_parent({"type": "SD_SSE_SUBSCRIBE", "payload": {"sessionId": session_id}})
	_polling = true


func _exit_tree() -> void:
	if session_live:
		_post_parent({"type": "SD_SSE_UNSUBSCRIBE", "payload": {"sessionId": session_id}})


func _read_url_params() -> void:
	var raw: Variant = JavaScriptBridge.eval("""
(function() {
	var p = new URLSearchParams(window.location.search);
	return JSON.stringify({
		sessionId:   p.get('session_id')   || '',
		characterId: parseInt(p.get('character_id') || '-1', 10),
		slotIndex:   parseInt(p.get('slot_index')   || '-1', 10),
		chapterId:   parseInt(p.get('chapter_id')   || p.get('level_id') || '-1', 10)
	});
})();
	""")
	if raw == null or str(raw) == "null":
		return
	var parsed: Variant = JSON.parse_string(str(raw))
	if not (parsed is Dictionary):
		return
	session_id = str(parsed.get("sessionId", ""))
	character_id = int(parsed.get("characterId", -1))
	slot_index = int(parsed.get("slotIndex", -1))
	chapter_id = int(parsed.get("chapterId", -1))
	print("[sotn] session bridge — session=%s character=%d slot=%d" %
			[session_id, character_id, slot_index])


func _install_js_listener() -> void:
	# Idempotent; capped queues so a runaway producer can't OOM the WASM heap
	# (mirrors hugmesh-shim.js's 1000-entry cap on _sdInputQueue).
	JavaScriptBridge.eval("""
(function() {
	if (window.__SOTN_SESSION_LISTENER__) return;
	window.__SOTN_SESSION_LISTENER__ = true;
	window._sotnSseQueue = [];
	window._sotnApiQueue = [];
	window.addEventListener('message', function(e) {
		var d = e && e.data;
		if (!d || typeof d !== 'object') return;
		if (d.type === 'SD_SSE_EVENT' && d.payload) {
			if (window._sotnSseQueue.length > 1000) window._sotnSseQueue.length = 0;
			window._sotnSseQueue.push(d.payload);
		} else if (d.type === 'SD_API_RESPONSE' && d.payload) {
			if (window._sotnApiQueue.length > 200) window._sotnApiQueue.length = 0;
			window._sotnApiQueue.push(d.payload);
		}
	});
	window._sotnDrainSse = function() {
		var q = window._sotnSseQueue; window._sotnSseQueue = [];
		return JSON.stringify(q);
	};
	window._sotnDrainApi = function() {
		var q = window._sotnApiQueue; window._sotnApiQueue = [];
		return JSON.stringify(q);
	};
})();
	""")


func _process(_delta: float) -> void:
	if not _polling:
		return
	_drain_sse()
	_drain_api()


func _drain_sse() -> void:
	var raw: Variant = JavaScriptBridge.eval("window._sotnDrainSse ? window._sotnDrainSse() : '[]'")
	if raw == null:
		return
	var events: Variant = JSON.parse_string(str(raw))
	if not (events is Array):
		return
	for ev in events:
		if not (ev is Dictionary):
			continue
		var channel := str(ev.get("type", ""))
		var payload: Variant = ev.get("payload")
		match channel:
			"movement":
				if payload is Dictionary:
					movement_received.emit(payload)
			"world":
				if payload is Dictionary:
					world_received.emit(payload)
			"session-control":
				if payload is Dictionary:
					session_control_received.emit(str(payload.get("action", "")))


func _drain_api() -> void:
	var raw: Variant = JavaScriptBridge.eval("window._sotnDrainApi ? window._sotnDrainApi() : '[]'")
	if raw == null:
		return
	var responses: Variant = JSON.parse_string(str(raw))
	if not (responses is Array):
		return
	for resp in responses:
		if not (resp is Dictionary):
			continue
		# 403 = stale controller claim after a BFF restart. Mirror the Rust
		# game_bridge auto-recovery: ask the host to re-run connect/claim.
		if int(resp.get("status", 0)) == 403:
			_post_parent({"type": "SD_SESSION_EXPIRED"})


# ── outbound ─────────────────────────────────────────────────────────────────

## Relay a control action (the movement-channel path). Every session member
## may post controls; the BFF echoes them to the whole session as PlayerMovement
## events whose `action` field carries the string verbatim.
func send_control(action: String) -> void:
	if not session_live or character_id < 0:
		return
	_req_id += 1
	var body := {"action": action, "pressed": true,
			"timestamp": int(Time.get_unix_time_from_system() * 1000.0)}
	if chapter_id > 0:
		body["chapterId"] = chapter_id
	_post_parent({"type": "SD_API_REQUEST", "payload": {
		"requestId": _req_id, "method": "POST",
		"path": "/api/controls/%d" % character_id,
		"sessionId": session_id, "body": body,
	}})


## HOST-ONLY authoritative room-state broadcast (kind:'world' → the BFF's
## worldNative sink → the session's 'world' SSE channel; Gate 0b rejects
## non-creators server-side).
func send_world(payload: Dictionary) -> void:
	if not session_live or character_id < 0:
		return
	_req_id += 1
	_post_parent({"type": "SD_API_REQUEST", "payload": {
		"requestId": _req_id, "method": "POST",
		"path": "/api/input/%d" % character_id,
		"sessionId": session_id,
		"body": {"gameSlug": GAME_SLUG, "sessionId": session_id,
				"kind": "world", "type": "state", "payload": payload},
	}})


func _post_parent(msg: Dictionary) -> void:
	if not OS.has_feature("web"):
		return
	JavaScriptBridge.eval(
		"window.parent.postMessage(%s, '*');" % JSON.stringify(msg))
