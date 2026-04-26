extends Node

# Sibling HostBridge node (Rust GDExtension class). Resolved on _ready.
# All TWiaT scenes (cave + field) wire one up; null = scene not migrated,
# events drop silently.
var _host_bridge: Node = null

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
	# Sibling HostBridge resolves only after the scene tree is built.
	_host_bridge = get_parent().get_node_or_null("HostBridge")

	if OS.has_feature("web"):
		_fetch_game_config()
		_connect_sse_web()
	else:
		# Local-play mode (Godot editor F5, headless export). No JS bridge,
		# no parent module, no API. Fake a scene event so scene_loader
		# runs its bootstrap, and let _unhandled_input below echo movement
		# events through HostBridge so the player still drives from the
		# keyboard. Mirrors HostBridge's Standalone mode.
		print("[GameBridge] local-play mode (no API; keyboard echoes through HostBridge)")
		var stub_scene := {
			"sceneType": "cave",
			"name": "local-play",
			"elements": [],
		}
		_dispatch("scene", stub_scene)


# Dispatch a typed event into the sibling HostBridge node. Drops the
# event silently if no HostBridge is present (non-migrated scene) — TWiaT
# scenes always wire one up, so in practice this never silently drops.
func _dispatch(event_type: String, payload: Dictionary) -> void:
	if _host_bridge != null:
		_host_bridge.dispatch_event(event_type, JSON.stringify(payload))

func _fetch_game_config() -> void:
	JavaScriptBridge.eval("""
(function() {
	if (window._twSceneReady === true) {
		console.log('[GameBridge] scene already prefetched, skipping fetch');
		return;
	}
	var params = new URLSearchParams(window.location.search);
	var levelId = params.get('level_id') || params.get('game_id') || '1';
	window._twSceneReady = false;
	fetch('/api/levels/' + levelId + '/scene')
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
	// Movement queued (not latest-wins) so a rapid keydown→keyup pair doesn't
	// collapse into just the keyup — spells only fire on pressed=true and get
	// lost between Godot's ~16ms polls otherwise.
	window._twMovementQueue   = [];
	window._twLatestWorld     = null;
	window._twLatestCharacter = null;
	window._twLatestTime      = null;
	window._twLatestScene     = null;
	var base = (window.location.host === 'localhost:4200') ? 'http://localhost:8080' : '';
	var src = new EventSource(base + '/api/game/stream');
	window._twGameSSE = src;
	src.addEventListener('movement',  function(e) {
		try {
			window._twMovementQueue.push(JSON.parse(e.data));
			if (window._twMovementQueue.length > 32) window._twMovementQueue.shift();
		} catch(ex) {}
	});
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

# Pull every queued movement event in order so spells (which only fire on
# pressed==true) and controller-role changes aren't swallowed when keydown
# and keyup land in the same poll window.
func _drain_movement_queue() -> void:
	var raw: Variant = JavaScriptBridge.eval(
		"JSON.stringify((window._twMovementQueue && window._twMovementQueue.splice(0, window._twMovementQueue.length)) || [])"
	)
	if not raw or raw == "null":
		return
	var parsed = JSON.parse_string(str(raw))
	if not (parsed is Array):
		return
	for item in parsed:
		if item is Dictionary:
			_dispatch("movement", item)

func _process(_delta: float) -> void:
	if not OS.has_feature("web"):
		return
	_drain_movement_queue()
	var w := _poll("window._twLatestWorld")
	if not w.is_empty():
		_dispatch("world", w)
	var c := _poll("window._twLatestCharacter")
	if not c.is_empty():
		_dispatch("character", c)
	var t := _poll("window._twLatestTime")
	if not t.is_empty():
		_dispatch("time", t)
	var s := _poll("window._twLatestScene")
	if not s.is_empty():
		_dispatch("scene", s)

func _unhandled_input(event: InputEvent) -> void:
	if event is InputEventKey and not event.is_echo():
		var key_ev := event as InputEventKey
		var action: String = KEY_ACTIONS.get(key_ev.keycode, "")
		if action == "":
			return
		print("[bridge] KEY keycode=", key_ev.keycode,
			" pressed=", key_ev.pressed, " action='", action, "'")

		# Spells fire instantly client-side so they don't depend on the
		# SSE round-trip. The control event still fires so the server can
		# apply side effects (MP cost, XP, etc.) when we wire those up.
		if key_ev.pressed and action.begins_with("CAST_SPELL_"):
			var slot := int(action.substr(11))
			var player := get_parent().get_node_or_null("Player")
			if player and player.has_method("_cast_spell"):
				print("[bridge] direct _cast_spell(", slot, ") on ", player.name)
				player._cast_spell(slot)
			else:
				push_warning("[bridge] Player node missing or no _cast_spell")

		if OS.has_feature("web"):
			send_control(action, key_ev.pressed)
		else:
			# Local-play: synthesize the movement event the SSE side would
			# have produced. Player controllers consume movement_received;
			# they don't care whether the source is the BFF or local input.
			var ts: int = int(Time.get_unix_time_from_system() * 1000)
			var local := {"action": action, "pressed": key_ev.pressed, "timestamp": ts}
			_dispatch("movement", local)
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
	# Read the parent SPA's stored JWT (AuthService.STORAGE_KEY = 'tw_auth')
	# and attach it as `Authorization: Bearer <token>` so the BFF can
	# verify the caller via JWT instead of the legacy X-Casdoor-Id
	# header (which any client could spoof). Same-origin iframe →
	# window.parent.sessionStorage is reachable; cross-origin would
	# raise SecurityError and we fall through to an unauthenticated
	# request — the BFF's controller-role check then has no identity
	# and skips, matching the previous Godot bridge behaviour.
	JavaScriptBridge.eval("""
(function(){
	var token = '';
	try {
		var raw = window.parent.sessionStorage.getItem('tw_auth');
		if (raw) {
			var stored = JSON.parse(raw);
			token = stored.accessToken || '';
		}
	} catch (e) {}
	var base = (window.location.host === 'localhost:4200') ? 'http://localhost:8080' : '';
	var headers = {'Content-Type': 'application/json'};
	if (token) headers['Authorization'] = 'Bearer ' + token;
	fetch(base + '/api/controls/1', {
		method: 'POST',
		headers: headers,
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
