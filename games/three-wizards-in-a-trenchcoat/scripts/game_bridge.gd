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
	KEY_1: "CAST_INSTANT_1",
	KEY_2: "CAST_INSTANT_2",
	KEY_3: "CAST_INSTANT_3",
}

func _ready() -> void:
	# Sibling HostBridge resolves only after the scene tree is built.
	_host_bridge = get_parent().get_node_or_null("HostBridge")

	# Wire session-control pause / resume / close to the scene tree.
	# Game logic decides what "paused" means (we use Godot's built-in
	# `get_tree().paused = true` because that's the contract every
	# scene supports; per-game scripts can override via the same
	# signal). SESSION_CLOSED tears down the whole scene so the user
	# doesn't keep playing on a stale session.
	if _host_bridge != null:
		_host_bridge.session_control_received.connect(_on_session_control)

	if OS.has_feature("web"):
		_thread_multiplayer_url_params()
		_fetch_game_config()
		# SSE subscription now lives in the Angular wrapper — ask it
		# to open the EventSource and pipe typed events back as
		# SD_SSE_EVENT postMessages. BridgeGlue (Rust) drains the
		# resulting JS-side queue per frame and dispatches through
		# HostBridge.dispatch_event into the typed signals scene
		# scripts already consume.
		_request_sse_subscription()
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


# Pause / resume / close handler routed in from the BFF's
# `session-control` SSE channel. The Angular wrapper also posts
# SD_GAME_PAUSE / SD_GAME_RESUME to handle peer-presence + cross-tab
# takeover; that path lands on a separate window message listener
# (see _install_pause_listener). Both converge on get_tree().paused.
func _on_session_control(action: String, _payload: String) -> void:
	var tree := get_tree()
	if tree == null:
		return
	match action:
		"SESSION_PAUSED", "PLAYER_PAUSED":
			tree.paused = true
		"SESSION_RESUMED", "PLAYER_RESUMED":
			tree.paused = false
		"SESSION_CLOSED":
			# Wholesale tear-down. The host shell will collapse the
			# iframe anyway; freeing here drops any lingering audio /
			# physics so the closing animation isn't haunted.
			tree.quit()


# Dispatch a typed event into the sibling HostBridge node. Drops the
# event silently if no HostBridge is present (non-migrated scene) — TWiaT
# scenes always wire one up, so in practice this never silently drops.
func _dispatch(event_type: String, payload: Dictionary) -> void:
	if _host_bridge != null:
		_host_bridge.dispatch_event(event_type, JSON.stringify(payload))

# Thread multiplayer context from the iframe URL into HostBridge:
#   ?session_id=<uuid>     active multiplayer session
#   ?character_id=<long>   character this slot is driving
#   ?slot_index=<int>      slot 0..maxPlayers-1
# All three are optional — when absent the bridge stays in legacy
# single-player mode and send_input falls back to the global control
# path. The session id is what flips the BFF into per-session sink
# routing for every subsequent control event.
func _thread_multiplayer_url_params() -> void:
	if _host_bridge == null:
		return
	var raw: Variant = JavaScriptBridge.eval("""
(function() {
	var params = new URLSearchParams(window.location.search);
	return JSON.stringify({
		sessionId:   params.get('session_id')   || '',
		characterId: parseInt(params.get('character_id') || '-1', 10),
		slotIndex:   parseInt(params.get('slot_index')   || '-1', 10)
	});
})();
	""")
	if not raw or raw == "null":
		return
	var parsed = JSON.parse_string(str(raw))
	if not (parsed is Dictionary):
		return
	var sid: String  = parsed.get("sessionId", "")
	var cid: int     = int(parsed.get("characterId", -1))
	var slot: int    = int(parsed.get("slotIndex", -1))
	if sid != "":
		_host_bridge.set_session_id(sid)
	if slot >= 0 and cid >= 0:
		_host_bridge.register_player_slot(slot, cid, true)
		print("[GameBridge] multiplayer mode — slot=%d, characterId=%d, sessionId=%s" % [slot, cid, sid])

func _fetch_game_config() -> void:
	JavaScriptBridge.eval("""
(function() {
	if (window._twSceneReady === true) {
		console.log('[GameBridge] scene already prefetched, skipping fetch');
		return;
	}
	var params = new URLSearchParams(window.location.search);
	var chapterId = params.get('chapter_id') || params.get('game_id') || '1';
	window._twSceneReady = false;
	fetch('/api/chapters/' + chapterId + '/scene')
		.then(function(r) {
			if (!r.ok) throw new Error('HTTP ' + r.status);
			return r.json();
		})
		.then(function(scene) {
			window._twSceneConfig = scene;
			window._twSceneReady = true;
			console.log('[GameBridge] scene loaded:', scene.name, '—', (scene.elements || []).length, 'elements');
		})
		.catch(function(e) { console.warn('[GameBridge] scene fetch failed', e); });
})();
""")

## Ask the Angular wrapper to subscribe to /api/game/stream on this
## iframe's behalf and forward each event back as SD_SSE_EVENT. The
## wrapper owns the EventSource so the iframe stays a passive
## consumer; Rust BridgeGlue catches the postMessages and routes
## them through HostBridge.dispatch_event.
func _request_sse_subscription() -> void:
	var sid_raw: Variant = JavaScriptBridge.eval(
		"new URLSearchParams(window.location.search).get('session_id') || ''")
	var sid := str(sid_raw) if sid_raw != null else ""
	JavaScriptBridge.eval(
		"window.parent.postMessage({type:'SD_SSE_SUBSCRIBE', payload: {sessionId: '%s'}}, '*')" % sid
	)

# SSE polling moved to the Angular wrapper + Rust BridgeGlue. This
# script no longer needs a per-frame drain — the typed signals on
# HostBridge fire whenever the wrapper postMessages a SD_SSE_EVENT.

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
		if key_ev.pressed and action.begins_with("CAST_INSTANT_"):
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
	# Read chapter_id off the iframe URL so the BFF can scope the
	# (character, chapter) position cache. Liquibase 027 made
	# player_positions composite-keyed; without chapter_id the BFF
	# falls back to the character's current_chapter_id from PG, but
	# threading it through here keeps the read path inside one tier.
	var chapter_id_js: String = JavaScriptBridge.eval(
		"new URLSearchParams(window.location.search).get('chapter_id') || ''")
	var chapter_id_str := str(chapter_id_js)
	var body: String
	if chapter_id_str.is_empty() or not chapter_id_str.is_valid_int():
		body = '{"action":"%s","pressed":%s,"timestamp":%d}' % [
			action,
			"true" if pressed else "false",
			ts
		]
	else:
		body = '{"action":"%s","pressed":%s,"timestamp":%d,"chapterId":%d}' % [
			action,
			"true" if pressed else "false",
			ts,
			int(chapter_id_str)
		]
	# Read the parent SPA's stored JWT (AuthService.STORAGE_KEY = 'tw_auth')
	# and attach it as `Authorization: Bearer <token>` so the BFF can
	# verify the caller via JWT instead of the legacy X-Casdoor-Id
	# header (which any client could spoof). Same-origin iframe →
	# window.parent.localStorage is reachable (AuthService keeps the
	# session there so open-in-new-tab stays signed in; sessionStorage
	# is only a per-tab fallback); cross-origin would
	# raise SecurityError and we fall through to an unauthenticated
	# request — the BFF's controller-role check then has no identity
	# and skips, matching the previous Godot bridge behaviour.
	JavaScriptBridge.eval("""
(function(){
	var token = '';
	try {
		var raw = window.parent.localStorage.getItem('tw_auth')
				|| window.parent.sessionStorage.getItem('tw_auth');
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
	}).then(function(r) {
		if (r.status === 403) {
			try {
				window.parent.postMessage({ type: 'SD_SESSION_EXPIRED' }, '*');
			} catch (e) {}
		}
	}).catch(function(){});
})();
""" % body)


## Request the Angular host to unload this WASM and mount the next chapter's bundle.
## Called by in-game transition triggers (portal touched, quest complete, etc).
## The postMessage is received by game-canvas.component.ts which swaps the iframe src.
func request_chapter_transition(chapter_slug: String, chapter_id: int = -1) -> void:
	if not OS.has_feature("web"):
		print("[bridge] Chapter transition requested but not running in web mode: %s" % chapter_slug)
		return
	var payload := '{"type":"TW_CHAPTER_TRANSITION","slug":"%s","chapterId":%d}' % [chapter_slug, chapter_id]
	JavaScriptBridge.eval("""
(function(){
	try {
		window.parent.postMessage(%s, '*');
	} catch (e) { console.warn('chapter transition post failed', e); }
})();
""" % payload)
