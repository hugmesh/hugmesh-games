extends Node

# Matrix-mode transport — the versioned Chat3D postMessage interface
# (XR-chat DESIGN §5). NO games BFF is involved in this mode: all I/O goes
# through a JS global `Chat3D` installed on the iframe window, driven by the
# embedding page (snapdragon-matrix-ui's Chat3dHostComponent).
#
# IN — the host page calls (directly, or via the postMessage envelope below):
#   Chat3D.roster(json)                     full participant list (JSON array)
#   Chat3D.avatar(userId, configJson)       a user's Char3DConfig
#   Chat3D.pose(userId, x, z, yaw, seat)    pose update (seat -1 = free-roam)
#   Chat3D.openness(userId, f)              mouth openness 0..1
#   Chat3D.blendshapes(userId, b64)         base64 uint8[52] ARKit blendshapes
#   Chat3D.speaking(userId, bool)           speaking indicator
#   Chat3D.expression(userId, label)        one of the 10 canonical labels
#   Chat3D.placeMedia(surfaceId, metaJson, pngBase64|null)
#   Chat3D.clearMedia(surfaceId)
#   Chat3D.stage(userId, bool)              stage grant / revoke
#   Chat3D.setLocal(userId)                 which participant is ME
#   Chat3D.enterVR()                        request immersive-vr
# OUT — the game emits (onEmit callback + postMessage to the allowed origin):
#   localPose(x, z, yaw, seat) ~10Hz | mediaActivate(surfaceId) | vrState(active)
#
# ENVELOPE (postMessage, both directions): {v:1, t:<function-name>, args:[…]}
# — t is the Chat3D function name, args is the argument list in signature
# order. Inbound messages ALSO accept signature-named fields ({userId:…,x:…})
# for tolerance; outbound messages carry BOTH forms. Version v !== 1 ⇒ drop.
#
# ORIGIN ALLOW-LIST — checked on EVERY message, both ways:
#   * inbound: event.origin must equal the allowed origin;
#   * outbound: postMessage targets the allowed origin explicitly (never '*').
# The allowed origin comes from the ?origin= query param (set by the embedding
# page), falling back to document.referrer's origin. With neither, postMessage
# I/O is disabled (direct same-origin Chat3D.* calls still work) — honest
# degradation instead of a '*' hole.

signal roster_received(list: Array)
signal avatar_received(user_id: String, config: Dictionary)
signal pose_received(user_id: String, x: float, z: float, yaw: float, seat: int)
signal openness_received(user_id: String, value: float)
signal blendshapes_received(user_id: String, weights: PackedByteArray)
signal speaking_received(user_id: String, value: bool)
signal expression_received(user_id: String, label: String)
signal place_media_received(surface_id: String, meta: Dictionary, png_bytes: PackedByteArray)
signal clear_media_received(surface_id: String)
signal stage_received(user_id: String, value: bool)
signal local_user_set(user_id: String)
signal enter_vr_requested()

## surfaceId grammar (same regex the scene event clamps to server-side).
const SURFACE_RE := "^(table-[1-6]|screen-[1-4]|stage)$"
## Argument order per function — the single source of the envelope mapping.
const SIGNATURES := {
	"roster": ["json"],
	"avatar": ["userId", "configJson"],
	"pose": ["userId", "x", "z", "yaw", "seat"],
	"openness": ["userId", "f"],
	"blendshapes": ["userId", "b64"],
	"speaking": ["userId", "value"],
	"expression": ["userId", "label"],
	"placeMedia": ["surfaceId", "metaJson", "pngBase64"],
	"clearMedia": ["surfaceId"],
	"stage": ["userId", "value"],
	"setLocal": ["userId"],
	"enterVR": [],
}

var _polling := false
var _surface_re: RegEx = null


func _ready() -> void:
	_surface_re = RegEx.new()
	_surface_re.compile(SURFACE_RE)
	if not OS.has_feature("web"):
		return
	_install_js()
	_polling = true


func _install_js() -> void:
	# The signature table is injected so JS and GDScript can never drift.
	var sig_json := JSON.stringify(SIGNATURES)
	JavaScriptBridge.eval("""
(function() {
	if (window.Chat3D) return;
	var SIG = %s;
	var allowed = (function() {
		try {
			var p = new URLSearchParams(window.location.search);
			var o = p.get('origin');
			if (o) return new URL(o).origin;
			if (document.referrer) return new URL(document.referrer).origin;
		} catch (e) { /* fall through */ }
		return null;
	})();
	var warned = false;
	var q = [];
	function push(t, args) {
		if (q.length > 2048) q.length = 0;
		q.push({ t: t, args: args });
	}
	var api = { _allowedOrigin: allowed, onEmit: null };
	Object.keys(SIG).forEach(function(name) {
		api[name] = function() { push(name, Array.prototype.slice.call(arguments)); };
	});
	api._drain = function() { var d = q; q = []; return JSON.stringify(d); };
	api._emit = function(t, args, named) {
		try { if (api.onEmit) api.onEmit(t, args); } catch (e) { /* handler threw */ }
		var msg = { v: 1, t: t, args: args };
		if (named) Object.keys(named).forEach(function(k) { msg[k] = named[k]; });
		if (allowed) {
			try { window.parent.postMessage(msg, allowed); } catch (e) { /* parent gone */ }
		} else if (!warned) {
			warned = true;
			console.warn('[sotn] Chat3D: no allowed origin (?origin= or referrer) - postMessage emits disabled');
		}
	};
	window.addEventListener('message', function(e) {
		if (!allowed || e.origin !== allowed) return;
		var d = e && e.data;
		if (!d || typeof d !== 'object' || d.v !== 1 || typeof d.t !== 'string') return;
		var sig = SIG[d.t];
		if (!sig) return;
		var args = Array.isArray(d.args) ? d.args
			: sig.map(function(k) { return d[k] !== undefined ? d[k] : null; });
		push(d.t, args);
	});
	window.Chat3D = api;
})();
	""" % sig_json)


func _process(_delta: float) -> void:
	if not _polling:
		return
	var raw: Variant = JavaScriptBridge.eval(
		"window.Chat3D && window.Chat3D._drain ? window.Chat3D._drain() : '[]'")
	if raw == null:
		return
	var calls: Variant = JSON.parse_string(str(raw))
	if not (calls is Array):
		return
	for c in calls:
		if c is Dictionary:
			_dispatch(str(c.get("t", "")), c.get("args") if c.get("args") is Array else [])


func _dispatch(t: String, args: Array) -> void:
	match t:
		"roster":
			var parsed: Variant = _arg_json(args, 0)
			if parsed is Array:
				roster_received.emit(parsed)
		"avatar":
			var cfg: Variant = _arg_json(args, 1)
			if cfg is Dictionary:
				avatar_received.emit(_arg_str(args, 0), cfg)
		"pose":
			pose_received.emit(_arg_str(args, 0), _arg_f(args, 1), _arg_f(args, 2),
					_arg_f(args, 3), _arg_i(args, 4, -1))
		"openness":
			openness_received.emit(_arg_str(args, 0), clampf(_arg_f(args, 1), 0.0, 1.0))
		"blendshapes":
			var bytes := Marshalls.base64_to_raw(_arg_str(args, 1))
			if bytes.size() == 52:
				blendshapes_received.emit(_arg_str(args, 0), bytes)
		"speaking":
			speaking_received.emit(_arg_str(args, 0), _arg_b(args, 1))
		"expression":
			expression_received.emit(_arg_str(args, 0), _arg_str(args, 1))
		"placeMedia":
			var surface := _arg_str(args, 0)
			if _surface_re.search(surface) == null:
				return
			var meta: Variant = _arg_json(args, 1)
			var png := PackedByteArray()
			var b64 := _arg_str(args, 2)
			if b64 != "":
				png = Marshalls.base64_to_raw(b64)
			place_media_received.emit(surface,
					meta if meta is Dictionary else {}, png)
		"clearMedia":
			var surface2 := _arg_str(args, 0)
			if _surface_re.search(surface2) != null:
				clear_media_received.emit(surface2)
		"stage":
			stage_received.emit(_arg_str(args, 0), _arg_b(args, 1))
		"setLocal":
			local_user_set.emit(_arg_str(args, 0))
		"enterVR":
			enter_vr_requested.emit()


# ── outbound emits ───────────────────────────────────────────────────────────

func emit_local_pose(x: float, z: float, yaw: float, seat: int) -> void:
	_emit("localPose", [x, z, yaw, seat], {"x": x, "z": z, "yaw": yaw, "seat": seat})


func emit_media_activate(surface_id: String) -> void:
	_emit("mediaActivate", [surface_id], {"surfaceId": surface_id})


func emit_vr_state(active: bool) -> void:
	_emit("vrState", [active], {"active": active})


func _emit(t: String, args: Array, named: Dictionary) -> void:
	if not OS.has_feature("web"):
		return
	JavaScriptBridge.eval(
		"window.Chat3D && window.Chat3D._emit && window.Chat3D._emit(%s, %s, %s);" %
		[JSON.stringify(t), JSON.stringify(args), JSON.stringify(named)])


# ── tolerant argument coercion ───────────────────────────────────────────────

func _arg_str(args: Array, i: int) -> String:
	return str(args[i]) if i < args.size() and args[i] != null else ""


func _arg_f(args: Array, i: int) -> float:
	if i < args.size() and (args[i] is float or args[i] is int):
		var f := float(args[i])
		if is_finite(f):
			return f
	return 0.0


func _arg_i(args: Array, i: int, fallback: int) -> int:
	if i < args.size() and (args[i] is float or args[i] is int):
		return int(args[i])
	return fallback


func _arg_b(args: Array, i: int) -> bool:
	return i < args.size() and args[i] == true


## Accepts either a JSON string or an already-structured value at args[i].
func _arg_json(args: Array, i: int) -> Variant:
	if i >= args.size():
		return null
	var v: Variant = args[i]
	if v is String:
		return JSON.parse_string(v)
	return v
