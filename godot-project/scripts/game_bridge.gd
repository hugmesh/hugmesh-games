extends Node

signal state_updated(state: Dictionary)

# Key → action name mapping for keyboard input routing through the API
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
	# In dev the game iframe is served from localhost:4200 (Angular dev server)
	# whose Vite proxy injects connection:close and kills SSE streams.
	# Connect directly to Spring Boot on :8080 in that case, exactly as
	# Angular's GameStateService does.  In production both are the same origin.
	JavaScriptBridge.eval("""
(function() {
	if (window._twGameSSE) { window._twGameSSE.close(); }
	window._twLatestState = null;
	var base = (window.location.host === 'localhost:4200') ? 'http://localhost:8080' : '';
	var src = new EventSource(base + '/api/game/stream');
	window._twGameSSE = src;
	src.addEventListener('game-state', function(e) {
		try { window._twLatestState = JSON.parse(e.data); } catch(ex) {}
	});
	src.onerror = function() { console.warn('[GameBridge] SSE error'); };
})();
""")

func _process(_delta: float) -> void:
	if not OS.has_feature("web"):
		return
	var raw: Variant = JavaScriptBridge.eval("JSON.stringify(window._twLatestState)")
	if raw and raw != "null":
		var parsed = JSON.parse_string(str(raw))
		if parsed is Dictionary:
			emit_signal("state_updated", parsed)
		JavaScriptBridge.eval("window._twLatestState = null")

# Catch keyboard input while the Godot canvas has focus and POST it to the API,
# so every keystroke travels the same Angular-keyboard → API → SSE path.
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
	# Build JSON manually to avoid any string-escaping issues when interpolating
	var ts: int = int(Time.get_unix_time_from_system() * 1000)
	var body := '{"action":"%s","pressed":%s,"timestamp":%d}' % [
		action,
		"true" if pressed else "false",
		ts
	]
	JavaScriptBridge.eval("""
(function(){
	var base = (window.location.host === 'localhost:4200') ? 'http://localhost:8080' : '';
	fetch(base + '/api/controls', {
		method: 'POST',
		headers: {'Content-Type': 'application/json'},
		body: '%s'
	}).catch(function(){});
})();
""" % body)
