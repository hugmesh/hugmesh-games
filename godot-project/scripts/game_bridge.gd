extends Node

signal state_updated(state: Dictionary)
signal movement_updated(data: Dictionary)
signal world_updated(data: Dictionary)
signal character_updated(data: Dictionary)
signal time_updated(data: Dictionary)
signal scene_updated(data: Dictionary)

const KEY_ACTIONS := {
	KEY_W: "MOVE_FORWARD",  KEY_UP:    "MOVE_FORWARD",
	KEY_S: "MOVE_BACKWARD", KEY_DOWN:  "MOVE_BACKWARD",
	KEY_A: "MOVE_LEFT",     KEY_LEFT:  "MOVE_LEFT",
	KEY_D: "MOVE_RIGHT",    KEY_RIGHT: "MOVE_RIGHT",
	KEY_SPACE: "JUMP",
	KEY_E: "INTERACT",
	KEY_1: "CAST_SPELL_1",
	KEY_2: "CAST_SPELL_2",
	KEY_3: "CAST_SPELL_3",
}

func _ready() -> void:
	if OS.has_feature("web"):
		_fetch_game_config()
		_connect_sse_web()
	else:
		push_warning("GameBridge: SSE not available outside web export")

func _fetch_game_config() -> void:
	JavaScriptBridge.eval("""
(function() {
	if (window._twSceneReady === true) {
		console.log('[GameBridge] scene already prefetched, skipping fetch');
		return;
	}
	var params = new URLSearchParams(window.location.search);
	var gameId = params.get('game_id') || '1';
	var base = (window.location.host === 'localhost:4200') ? 'http://localhost:8080' : '';
	window._twSceneReady = false;
	fetch(base + '/api/games/' + gameId + '/scene')
		.then(function(r) { return r.json(); })
		.then(function(scene) {
			window._twSceneConfig = scene;
			window._twSceneReady = true;
			console.log('[GameBridge] scene loaded:', scene.name, '—', (scene.elements || []).length, 'elements');
		})
		.catch(function(e) { console.warn('[GameBridge] scene fetch failed', e); });
})();
""")

func _connect_sse_web() -> void:
	JavaScriptBridge.eval("""
(function() {
	if (window._twGameSSE) { window._twGameSSE.close(); }
	window._twLatestMovement  = null;
	window._twLatestWorld     = null;
	window._twLatestCharacter = null;
	window._twLatestTime      = null;
	window._twLatestScene     = null;
	var base = (window.location.host === 'localhost:4200') ? 'http://localhost:8080' : '';
	var src = new EventSource(base + '/api/game/stream');
	window._twGameSSE = src;
	src.addEventListener('movement',  function(e) { try { window._twLatestMovement  = JSON.parse(e.data); } catch(ex) {} });
	src.addEventListener('world',     function(e) { try { window._twLatestWorld     = JSON.parse(e.data); } catch(ex) {} });
	src.addEventListener('character', function(e) { try { window._twLatestCharacter = JSON.parse(e.data); } catch(ex) {} });
	src.addEventListener('time',      function(e) { try { window._twLatestTime      = JSON.parse(e.data); } catch(ex) {} });
	src.addEventListener('scene',     function(e) { try { window._twLatestScene     = JSON.parse(e.data); } catch(ex) {} });
	src.onerror = function() { console.warn('[GameBridge] SSE error'); };
})();
""")

func _poll(js_var: String) -> Dictionary:
	var raw: Variant = JavaScriptBridge.eval("JSON.stringify(%s)" % js_var)
	if not raw or raw == "null":
		return {}
	var parsed = JSON.parse_string(str(raw))
	JavaScriptBridge.eval("%s = null" % js_var)
	return parsed if parsed is Dictionary else {}

func _process(_delta: float) -> void:
	if not OS.has_feature("web"):
		return
	var m := _poll("window._twLatestMovement")
	if not m.is_empty():
		movement_updated.emit(m)
		state_updated.emit(m)
	var w := _poll("window._twLatestWorld")
	if not w.is_empty():
		world_updated.emit(w)
	var c := _poll("window._twLatestCharacter")
	if not c.is_empty():
		character_updated.emit(c)
	var t := _poll("window._twLatestTime")
	if not t.is_empty():
		time_updated.emit(t)
	var s := _poll("window._twLatestScene")
	if not s.is_empty():
		scene_updated.emit(s)

func _unhandled_input(event: InputEvent) -> void:
	if not OS.has_feature("web"):
		return
	if event is InputEventKey and not event.is_echo():
		var action: String = KEY_ACTIONS.get(event.keycode, "")
		if action != "":
			send_control(action, event.pressed)
			get_viewport().set_input_as_handled()

func send_control(action: String, pressed: bool) -> void:
	if not OS.has_feature("web"):
		return
	var ts: int = int(Time.get_unix_time_from_system() * 1000)
	var body := '{"action":"%s","pressed":%s,"timestamp":%d}' % [
		action,
		"true" if pressed else "false",
		ts
	]
	JavaScriptBridge.eval("""
(function(){
	var base = (window.location.host === 'localhost:4200') ? 'http://localhost:8080' : '';
	fetch(base + '/api/controls/1', {
		method: 'POST',
		headers: {'Content-Type': 'application/json'},
		body: '%s'
	}).catch(function(){});
})();
""" % body)


## Request the Angular host to unload this WASM and mount the next level's bundle.
## Called by in-game transition triggers (portal touched, quest complete, etc).
## The postMessage is received by game-canvas.component.ts which swaps the iframe src.
func request_level_transition(level_slug: String, level_id: int = -1) -> void:
	if not OS.has_feature("web"):
		print("[bridge] Level transition requested but not running in web mode: %s" % level_slug)
		return
	var payload := '{"type":"TW_LEVEL_TRANSITION","slug":"%s","levelId":%d}' % [level_slug, level_id]
	JavaScriptBridge.eval("""
(function(){
	try {
		window.parent.postMessage(%s, '*');
	} catch (e) { console.warn('level transition post failed', e); }
})();
""" % payload)
