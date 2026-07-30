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
#
# NONE of them announces a member DEPARTURE, so membership is READ instead of
# pushed — see request_roster(), which polls GET /api/sessions/{id} over the
# same SD_API_REQUEST channel and emits `roster_received`.

signal movement_received(payload: Dictionary)
signal world_received(payload: Dictionary)
signal session_control_received(action: String)
## The BFF rejected an authoritative kind:'world' POST (Gate 0b — we are not
## the session creator). Distinct from a stale controller claim: reconnecting
## can never fix it, so the room must stand down as host instead.
signal world_forbidden()
## The session's CURRENT membership, as characterIds — see request_roster().
## Emitted only for a 200; a failed read means "no fresh answer", not "empty".
signal roster_received(character_ids: Array)

const GAME_SLUG := "shadows-of-the-net"
## Outstanding world POSTs we still expect a response for. Bounded — a lost
## response must never let this grow without limit.
const MAX_PENDING_WORLD := 64
## Same bookkeeping for the membership read. One request is in flight at a
## time in practice; the cap only exists so lost responses cannot leak.
const MAX_PENDING_ROSTER := 8
## Seconds between membership reads. Two orders of magnitude below the pose
## relay this transport already sustains (every member posts SOTN_POSE at up
## to 10 Hz), so it is not a meaningful addition to session traffic.
const ROSTER_POLL_INTERVAL := 5.0

## _classify_response verdicts.
const VERDICT_WORLD_FORBIDDEN := "world-forbidden"
const VERDICT_EXPIRED := "expired"
const VERDICT_ROSTER := "roster"

## The shape the BFF's own mapping accepts
## (MultiplayerSessionController `@GetMapping("/{id:[A-Za-z0-9_\\-]{16,86}}")`).
## See _is_safe_session_id — request_roster is the first path this bridge
## builds out of a launch-URL value, so the value gets pinned to that shape.
const SESSION_ID_PATTERN := "^[A-Za-z0-9_\\-]{16,86}$"

var session_id := ""
var character_id := -1
var slot_index := -1
var chapter_id := -1
## Session multiplayer is only live when embedded with a session_id.
var session_live := false

var _req_id := 0
var _polling := false
## requestId(int) → true for in-flight kind:'world' POSTs (see _drain_api).
var _pending_world: Dictionary = {}
## requestId(int) → true for in-flight membership GETs (see request_roster).
var _pending_roster: Dictionary = {}
var _roster_accum := 0.0
var _session_id_re: RegEx = null


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


func _process(delta: float) -> void:
	if not _polling:
		return
	_drain_sse()
	_drain_api()
	_poll_roster(delta)


## Membership is the one thing this transport has NO push channel for — see
## request_roster(). Polled from here rather than from the room so the cadence
## lives with the transport and a non-web / non-session boot never fires it.
func _poll_roster(delta: float) -> void:
	if not session_live:
		return
	_roster_accum += delta
	if _roster_accum < ROSTER_POLL_INTERVAL:
		return
	_roster_accum = 0.0
	request_roster()


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
		var verdict := _classify_response(resp)
		if verdict == VERDICT_WORLD_FORBIDDEN:
			world_forbidden.emit()
		elif verdict == VERDICT_EXPIRED:
			_post_parent({"type": "SD_SESSION_EXPIRED"})
		elif verdict == VERDICT_ROSTER:
			roster_received.emit(_parse_roster(resp.get("body")))


## Classify one drained SD_API_RESPONSE and retire its pending-world entry.
##
## The two 403s the BFF can hand us mean OPPOSITE things and the old code
## conflated them:
##   * 403 on POST /api/input/{cid} (kind:'world') is Gate 0b — only the
##     session CREATOR may broadcast authoritative state. That is a permanent
##     authority verdict; reconnecting cannot change it, and re-running
##     connect/claimControl just 403s again on the next broadcast (once per
##     seat click, per stage toggle, and on the deferred initial seed).
##   * 403 on POST /api/controls/{cid} is a stale controller claim after a BFF
##     restart, which connect/claimControl DOES fix (the documented
##     SD_SESSION_EXPIRED recovery every other game in this repo uses).
##
## The membership GET is a third case and MUST be split out before either 403
## branch: it is a passive read, so a 403/404/0 on it means "no fresh answer
## this tick", never "our controller claim went stale". Left in the shared
## bucket it would post SD_SESSION_EXPIRED — and therefore re-run the host
## shell's connect/claimControl chain — every ROSTER_POLL_INTERVAL forever.
##
## Kept as a pure-ish function so the routing is unit-testable without a
## browser: `_drain_api` only owns the JS drain, this owns the decision.
func _classify_response(resp: Dictionary) -> String:
	var rid := int(resp.get("requestId", -1))
	var was_world := _pending_world.erase(rid)
	var was_roster := _pending_roster.erase(rid)
	var status := int(resp.get("status", 0))
	if was_roster:
		return VERDICT_ROSTER if status == 200 else ""
	if status != 403:
		return ""
	return VERDICT_WORLD_FORBIDDEN if was_world else VERDICT_EXPIRED


## Pull the live characterIds out of a GameSessionDto body.
##
## `members` is `session_members WHERE left_at IS NULL` (GameSessionService
## .toDtoWithMembers), i.e. exactly the room's current occupants. `characterId`
## is nullable on the wire — a member row exists from the moment someone joins,
## the character is attached on slot-claim — so a null is skipped rather than
## folded to 0, which would invent a phantom "character 0" in the roster.
static func _parse_roster(body: Variant) -> Array:
	var doc: Variant = body
	if doc is String:
		doc = JSON.parse_string(doc)
	if not (doc is Dictionary):
		return []
	var members: Variant = (doc as Dictionary).get("members")
	if not (members is Array):
		return []
	var ids: Array = []
	for member in members:
		if not (member is Dictionary):
			continue
		var cid: Variant = (member as Dictionary).get("characterId")
		if cid == null:
			continue
		var as_int := int(cid)
		if as_int >= 0 and not ids.has(as_int):
			ids.append(as_int)
	return ids


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


## Ask the BFF who is CURRENTLY in the session.
##
## There is no push signal for this. The six SSE channels the host shell
## forwards into the iframe (ApiBridgeService.SSE_CHANNELS = movement / world /
## character / time / scene / session-control) carry no per-member departure
## event: `session-control` only has SESSION_PAUSED|RESUMED, PLAYER_PAUSED|
## RESUMED (a tab takeover, not a departure) and SESSION_CLOSED (the whole room
## dying), `GameSessionService.leave` and `ModerationService.kickPlayer` stamp
## `left_at` and emit NOTHING per member, and the typed `WorldState` that shares
## the `world` event name is a per-CHAPTER roster built from a pod-lifetime map
## that is never evicted on leave — it only grows, and it pools characters from
## every session in that chapter. So the authoritative answer is a read:
## `GET /api/sessions/{id}` → `members` = `left_at IS NULL`.
##
## It rides the SAME SD_API_REQUEST channel as controls and world posts (the
## host shell attaches the JWT + X-Session-Id and hands the body back on
## SD_API_RESPONSE) — no new transport, no BFF change.
func request_roster() -> void:
	if not session_live or not _is_safe_session_id(session_id):
		return
	_req_id += 1
	if _pending_roster.size() >= MAX_PENDING_ROSTER:
		_pending_roster.clear()   # responses went missing; don't leak the map
	_pending_roster[_req_id] = true
	_post_parent({"type": "SD_API_REQUEST", "payload": {
		"requestId": _req_id, "method": "GET",
		"path": "/api/sessions/%s" % session_id,
		"sessionId": session_id, "body": {},
	}})


## `session_id` is read straight off the launch URL and this is the only path
## this bridge builds out of it (controls / world / view carry it in the BODY).
## The host shell forwards ANY path on SD_API_REQUEST through its authenticated
## HttpClient, so a crafted id with a `/` or `..` in it would let the embedding
## page steer that client at an unrelated endpoint. Pin the id to the shape the
## BFF's own mapping accepts instead of trusting it.
func _is_safe_session_id(id: String) -> bool:
	if _session_id_re == null:
		_session_id_re = RegEx.new()
		_session_id_re.compile(SESSION_ID_PATTERN)
	return _session_id_re.search(id) != null


## HOST-ONLY authoritative room-state broadcast (kind:'world' → the BFF's
## worldNative sink → the session's 'world' SSE channel; Gate 0b rejects
## non-creators server-side).
func send_world(payload: Dictionary) -> void:
	if not session_live or character_id < 0:
		return
	_req_id += 1
	if _pending_world.size() >= MAX_PENDING_WORLD:
		_pending_world.clear()   # responses went missing; don't leak the map
	_pending_world[_req_id] = true
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
