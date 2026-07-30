extends Node

# Matrix-mode transport — the versioned Chat3D postMessage interface
# (XR-chat DESIGN §5). NO games BFF is involved in this mode: all I/O goes
# through a JS global `Chat3D` installed on the iframe window, driven by the
# embedding page (snapdragon-matrix-ui's Chat3dHostComponent).
#
# IN — the host page calls (directly, or via the postMessage envelope below):
#   Chat3D.roster(json)                     full participant list (JSON array)
#   Chat3D.avatar(userId, configJson, mode, sticker, name)
#                                           a user's Char3DConfig + how they
#                                           chose to appear (see AVATAR_MODES)
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
#   ready()  — the HANDSHAKE. The matrix-ui host queues every outbound envelope
#              until it arrives (Chat3dHostComponent.send → outQueue) and keeps
#              Enter-VR inert, so nothing at all happens in the room until the
#              game announces itself. Emitted by room_controller._setup_matrix
#              via announce_ready(), and re-announced until the host answers so
#              a listener that attaches late still gets it.
#   localPose(x, z, yaw, seat) ~10Hz | mediaActivate(surfaceId) | vrState(active)
#
# ENVELOPE (postMessage, both directions): {v:1, t:<function-name>, args:[…]}
# — t is the Chat3D function name, args is the argument list in signature
# order. Inbound messages ALSO accept signature-named fields ({userId:…,x:…})
# plus the documented ALIASES below for tolerance; outbound messages carry BOTH
# forms. Version v !== 1 ⇒ drop.
#
# BINARY ARGUMENTS — the inbound queue is drained as JSON (JavaScriptBridge
# cannot hand a live ArrayBuffer to GDScript), and JSON.stringify turns an
# ArrayBuffer into `{}`. Any ArrayBuffer / TypedArray argument is therefore
# base64-encoded AT PUSH TIME, before it can be flattened — which is exactly
# the `pngBase64` shape the decoder below expects, so a host that passes real
# image bytes (structured clone) and a host that passes base64 both work.
#
# ORIGIN + SOURCE-WINDOW PINNING — checked on EVERY message, both ways:
#   * inbound: event.origin must equal the allowed origin AND event.source must
#     be the very window we post back to (window.parent). Origin alone is not
#     enough — ANY window on the host origin (a second tab the host opened, a
#     popup, a nested frame) can postMessage into this frame and drive the whole
#     room. The host side pins both (origin allow-list + `ev.source !==
#     frameWindow`); this is the mirror of that check.
#   * outbound: postMessage targets the allowed origin explicitly (never '*').
# The allowed origin comes from the ?origin= query param (set by the embedding
# page), falling back to document.referrer's origin. With neither, postMessage
# I/O is disabled (direct same-origin Chat3D.* calls still work) — honest
# degradation instead of a '*' hole.

signal roster_received(list: Array)
## `config` is a Dictionary, or null when the host sent configJson=null (a
## billboard participant, or a metadata-only refresh) — the receiver keeps the
## rig it has. `meta` carries the §5 mode/sticker/name slots.
signal avatar_received(user_id: String, config: Variant, meta: Dictionary)
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

## How a participant chose to appear (matrix-ui `AVATAR_MODES`): a
## character-forge config, a seeded random guest, or a flat VN sticker
## billboard. "" = the host did not say (an older host) ⇒ treat as a 3D rig.
const AVATAR_MODES: PackedStringArray = ["forge", "billboard", "guest"]
## Cap on the sticker mxc URL carried in billboard mode (matrix-ui
## MAX_AVATAR_STICKER_URL_LENGTH).
const MAX_STICKER_URL := 512

## Argument order per function — the single source of the envelope mapping.
##
## `avatar` carries FIVE slots, not DESIGN §5's two. mode/sticker/name are what
## a billboard participant IS: the host sends configJson=null for them and the
## appearance lives entirely in those fields. They shipped as envelope fields
## OUTSIDE the signature, which made them unreachable — the decode path takes
## `args` verbatim when it is an array, so three fields the host computes on
## every poll were silently dropped and billboard avatars were unrepresentable.
## Widening the signature (on BOTH sides) is what makes the mode real.
const SIGNATURES := {
	"roster": ["json"],
	"avatar": ["userId", "configJson", "mode", "sticker", "name"],
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

## Accepted ALTERNATE field names per signature slot, for hosts that build the
## envelope from named fields rather than an `args` array. The canonical names
## above are DESIGN §5's argument names and are always tried first; these are
## the natural TypeScript-side spellings (matrix-ui's `hostMsg` builders use
## `users`/`config`/`value`/`values`/`speaking`/`meta`/`bytes`/`on`), so
## accepting them keeps the room working instead of silently defaulting every
## payload to null. Never widen this to accept a DIFFERENT slot's name.
const ALIASES := {
	"roster": {"json": ["users", "roster", "list"]},
	"avatar": {"configJson": ["config"]},
	"openness": {"f": ["value", "openness"]},
	"blendshapes": {"b64": ["values", "weights", "bytes"]},
	"speaking": {"value": ["speaking"]},
	"expression": {"label": ["expression"]},
	"placeMedia": {"metaJson": ["meta"], "pngBase64": ["bytes", "png"]},
	"stage": {"value": ["on", "stage"]},
}

## Bytes accepted for one placeMedia texture before we drop the payload and
## fall back to a metadata-only plaque (the base64 of this still has to survive
## a JSON round-trip through the drain).
##
## ENFORCED ON EVERY TRANSPORT, not just the binary one. The cap used to live
## only inside the ArrayBuffer/TypedArray normaliser, so on the base64-STRING
## transport that actually shipped it was unreachable — and each side's comments
## assumed the other one bounded it. It is now applied (a) in the JS shim to a
## base64 string as well as to raw bytes and (b) again in _dispatch after
## decoding, which is the check a headless run can actually execute. The host
## enforces the SAME numeric limit before sending.
const MAX_MEDIA_BYTES := 4 * 1024 * 1024

## The host queues everything until `ready`; re-announce until it talks back so
## a host listener that attaches after the wasm boots still completes the
## handshake. ~1 s apart, then give up (the host is simply not there).
const READY_RETRY_INTERVAL := 1.0
const READY_MAX_RETRIES := 15

var _polling := false
var _surface_re: RegEx = null
var _ready_announced := false
var _ready_acked := false
var _ready_retries := 0
var _ready_accum := 0.0


func _ready() -> void:
	_surface_re = RegEx.new()
	_surface_re.compile(SURFACE_RE)
	if not OS.has_feature("web"):
		return
	_install_js()
	_polling = true


func _install_js() -> void:
	# The signature + alias tables are injected so JS and GDScript can never
	# drift apart on the envelope mapping.
	var sig_json := JSON.stringify(SIGNATURES)
	var alias_json := JSON.stringify(ALIASES)
	JavaScriptBridge.eval("""
(function() {
	if (window.Chat3D) return;
	var SIG = %s;
	var ALIAS = %s;
	var MAX_MEDIA = %d;
	// ArrayBuffer / TypedArray -> base64. The drain JSON.stringifies the queue,
	// which would otherwise flatten binary payloads to '{}' and silently lose
	// every placeMedia texture the host transfers as a structured clone.
	function toB64(view) {
		var bytes = view instanceof ArrayBuffer
			? new Uint8Array(view)
			: new Uint8Array(view.buffer, view.byteOffset, view.byteLength);
		if (bytes.length > MAX_MEDIA) return null;
		var s = '';
		for (var i = 0; i < bytes.length; i += 0x8000) {
			s += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
		}
		try { return btoa(s); } catch (e) { return null; }
	}
	function normArg(v) {
		if (v instanceof ArrayBuffer) return toB64(v);
		if (ArrayBuffer.isView(v) && !(v instanceof DataView)) return toB64(v);
		return v;
	}
	// Decoded byte length of a base64 string (no allocation).
	function b64Bytes(s) {
		var n = s.length;
		if (n === 0) return 0;
		var pad = s.charAt(n - 1) === '=' ? (s.charAt(n - 2) === '=' ? 2 : 1) : 0;
		return Math.floor(n * 3 / 4) - pad;
	}
	// The media cap on the transport that actually ships: a base64 STRING. The
	// binary path is capped in toB64; without this one an oversized texture
	// crossed the bridge unbounded because it never touched that path.
	function capMedia(t, args) {
		if (t !== 'placeMedia') return args;
		var v = args[2];
		if (typeof v === 'string' && b64Bytes(v) > MAX_MEDIA) {
			args = args.slice();
			args[2] = null;
			console.warn('[sotn] placeMedia texture over ' + MAX_MEDIA
				+ ' bytes - dropped, metadata plaque only');
		}
		return args;
	}
	// Canonical signature name first, then the documented aliases.
	function pick(d, t, key) {
		if (d[key] !== undefined) return d[key];
		var alts = (ALIAS[t] || {})[key];
		if (alts) {
			for (var i = 0; i < alts.length; i++) {
				if (d[alts[i]] !== undefined) return d[alts[i]];
			}
		}
		return null;
	}
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
		q.push({ t: t, args: capMedia(t, args.map(normArg)) });
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
		// SOURCE-WINDOW PIN. Origin alone lets ANY window on the host origin
		// drive this room; window.parent is the one window we also post back
		// to, so it is the only one whose messages can be part of a
		// conversation. Mirrors the host's `ev.source !== frameWindow` check.
		if (e.source !== window.parent) return;
		var d = e && e.data;
		if (!d || typeof d !== 'object' || d.v !== 1 || typeof d.t !== 'string') return;
		var sig = SIG[d.t];
		if (!sig) return;
		// An `args` array wins slot-for-slot, but a SHORT one is backfilled from
		// the signature-named fields instead of decoding to undefined: a host
		// that has not yet widened its own signature table still sends the extra
		// slots as named fields, and dropping them is how avatar mode/sticker/
		// name went missing in the first place.
		var args = [];
		var supplied = Array.isArray(d.args) ? d.args : [];
		for (var i = 0; i < sig.length; i++) {
			args.push(i < supplied.length ? supplied[i] : pick(d, d.t, sig[i]));
		}
		push(d.t, args);
	});
	window.Chat3D = api;
})();
	""" % [sig_json, alias_json, MAX_MEDIA_BYTES])


func _process(delta: float) -> void:
	if not _polling:
		return
	_retry_ready(delta)
	var raw: Variant = JavaScriptBridge.eval(
		"window.Chat3D && window.Chat3D._drain ? window.Chat3D._drain() : '[]'")
	if raw == null:
		return
	var calls: Variant = JSON.parse_string(str(raw))
	if not (calls is Array):
		return
	for c in calls:
		if c is Dictionary:
			# Any inbound call proves the host heard our handshake.
			_ready_acked = true
			_dispatch(str(c.get("t", "")), c.get("args") if c.get("args") is Array else [])


func _retry_ready(delta: float) -> void:
	if not _ready_announced or _ready_acked or _ready_retries >= READY_MAX_RETRIES:
		return
	_ready_accum += delta
	if _ready_accum < READY_RETRY_INTERVAL:
		return
	_ready_accum = 0.0
	_ready_retries += 1
	_emit("ready", [], {})


func _dispatch(t: String, args: Array) -> void:
	match t:
		"roster":
			var parsed: Variant = _arg_json(args, 0)
			if parsed is Array:
				roster_received.emit(parsed)
		"avatar":
			var uid := _arg_str(args, 0)
			if uid == "":
				return
			var cfg: Variant = _arg_json(args, 1)
			var meta := {
				"mode": _arg_enum(args, 2, AVATAR_MODES),
				"sticker": _arg_sticker(args, 3),
				"name": _arg_str(args, 4).substr(0, 120),
			}
			# A null config is NOT noise: billboard participants ship exactly
			# that, and so does a metadata-only refresh. Passing null through
			# (rather than dropping the whole call) lets the receiver keep the
			# rig it has and still apply the new mode/name.
			if cfg is Dictionary or meta["mode"] != "":
				avatar_received.emit(uid, cfg if cfg is Dictionary else null, meta)
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
			var media_meta: Variant = _arg_json(args, 1)
			var png := PackedByteArray()
			var b64 := _arg_str(args, 2)
			if b64 != "":
				png = Marshalls.base64_to_raw(b64)
				# INBOUND CAP (MAX_MEDIA_BYTES, same number the host refuses to
				# send above). Degrade to the metadata plaque instead of handing
				# a multi-megabyte buffer to the image decoders.
				if png.size() > MAX_MEDIA_BYTES:
					push_warning(("[sotn] placeMedia texture for %s is %d bytes"
							+ " (cap %d) — metadata plaque only")
							% [surface, png.size(), MAX_MEDIA_BYTES])
					png = PackedByteArray()
			place_media_received.emit(surface,
					media_meta if media_meta is Dictionary else {}, png)
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

## The Chat3D handshake. Until the host receives this it holds every envelope
## it wants to send us (roster, avatar, setLocal, placements…) in a queue and
## refuses Enter-VR requests, so the room stays empty. Call it once the inbound
## signals are wired; _retry_ready re-announces until the host answers.
func announce_ready() -> void:
	_ready_announced = true
	_ready_accum = 0.0
	_emit("ready", [], {})


## Whether the host has sent us anything since the handshake (test/diagnostic
## hook — the room is only truly live once this flips).
func ready_acked() -> bool:
	return _ready_acked


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


## An enum-checked string slot — anything outside `allowed` reads as "" (unset)
## rather than being passed through to a `match` somewhere downstream.
func _arg_enum(args: Array, i: int, allowed: PackedStringArray) -> String:
	var v := _arg_str(args, i)
	return v if allowed.has(v) else ""


## The billboard sticker slot: an `mxc://` URL, length-capped. Anything else is
## dropped — the room can only ever hand this back to the host, so a non-mxc
## string is either a mistake or an attempt to smuggle a URL somewhere.
func _arg_sticker(args: Array, i: int) -> String:
	var v := _arg_str(args, i)
	if not v.begins_with("mxc://") or v.length() > MAX_STICKER_URL:
		return ""
	return v


## Accepts either a JSON string or an already-structured value at args[i].
func _arg_json(args: Array, i: int) -> Variant:
	if i >= args.size():
		return null
	var v: Variant = args[i]
	if v is String:
		return JSON.parse_string(v)
	return v
