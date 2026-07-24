extends Node

# XR bootstrap (autoload "XrBoot") — one project, three targets:
#
#   * Web export  → WebXRInterface. 'immersive-vr', reference space
#     'local-floor' with fallbacks ('bounded-floor, local-floor, local').
#     WebXR REQUIRES the session request to happen inside a browser
#     user-gesture handler, so enter_vr() is only ever called from
#     (a) the in-scene "Enter VR" button press (a real gesture inside the
#     iframe) or (b) the host page's Chat3D.enterVR() bridge call — which
#     only carries activation if the embedding page forwarded a gesture;
#     when it doesn't, session_failed fires and we report vr state honestly.
#   * Native (Linux/Windows PCVR) → OpenXRInterface, initialized by the
#     engine at startup (project setting xr/openxr/enabled). SteamVR /
#     Monado / WiVRn / Meta Link are all standard OpenXR runtimes.
#   * No XR available → signal never reports active; the room stays on the
#     mouse/WASD desktop camera (local_controller.gd).
#
# Emits xr_state_changed(active) on every transition so the room controller
# can swap cameras and the bridges can emit vrState(active) outward.

signal xr_state_changed(active: bool)

## True while an immersive session is running.
var xr_active := false
## True when a WebXR immersive-vr session could be offered (web only).
var web_xr_supported := false

var _webxr: WebXRInterface = null
var _openxr: XRInterface = null


func _ready() -> void:
	if OS.has_feature("web"):
		_setup_webxr()
	else:
		_setup_openxr()


# ── WebXR (browser) ──────────────────────────────────────────────────────────

func _setup_webxr() -> void:
	_webxr = XRServer.find_interface("WebXR") as WebXRInterface
	if _webxr == null:
		print("[sotn] WebXR interface not found — desktop fallback only")
		return
	_webxr.session_supported.connect(_on_webxr_session_supported)
	_webxr.session_started.connect(_on_webxr_session_started)
	_webxr.session_ended.connect(_on_webxr_session_ended)
	_webxr.session_failed.connect(_on_webxr_session_failed)
	# Async capability probe; result lands in _on_webxr_session_supported.
	_webxr.is_session_supported("immersive-vr")


func _on_webxr_session_supported(session_mode: String, supported: bool) -> void:
	if session_mode != "immersive-vr":
		return
	web_xr_supported = supported
	print("[sotn] WebXR immersive-vr supported: %s" % supported)


## MUST be reached synchronously from a user gesture (button press in-scene,
## or a host-page call that still holds transient activation). Returns false
## when the request could not even be issued.
func enter_vr() -> bool:
	if _webxr == null or not OS.has_feature("web"):
		return false
	_webxr.session_mode = "immersive-vr"
	# Prefer a floor-referenced space; Quest browser historically mishandled
	# bounded-floor (godotengine/godot#95039, fixed Meta-side ~OS v69), so
	# local-floor is required and bounded-floor only optional.
	_webxr.requested_reference_space_types = "bounded-floor, local-floor, local"
	_webxr.required_features = "local-floor"
	_webxr.optional_features = "bounded-floor"
	return _webxr.initialize()


func exit_vr() -> void:
	if _webxr != null and xr_active:
		_webxr.uninitialize()


func _on_webxr_session_started() -> void:
	get_viewport().use_xr = true
	xr_active = true
	xr_state_changed.emit(true)
	print("[sotn] WebXR session started — reference space: %s" % _webxr.reference_space_type)


func _on_webxr_session_ended() -> void:
	get_viewport().use_xr = false
	xr_active = false
	xr_state_changed.emit(false)


func _on_webxr_session_failed(message: String) -> void:
	xr_active = false
	xr_state_changed.emit(false)
	# Honest capability note — the usual cause in an iframe is a missing
	# allow="xr-spatial-tracking" or a request outside a user gesture.
	push_warning("[sotn] WebXR session failed: %s" % message)


# ── OpenXR (native PCVR) ─────────────────────────────────────────────────────

func _setup_openxr() -> void:
	_openxr = XRServer.find_interface("OpenXR")
	if _openxr != null and _openxr.is_initialized():
		get_viewport().use_xr = true
		xr_active = true
		# Recommended for XR: uncap from vsync, raise the physics tick rate.
		DisplayServer.window_set_vsync_mode(DisplayServer.VSYNC_DISABLED)
		Engine.physics_ticks_per_second = 90
		# Defer so listeners connected in _ready order still hear it.
		xr_state_changed.emit.call_deferred(true)
		print("[sotn] OpenXR initialized — native PCVR mode")
	else:
		print("[sotn] no OpenXR runtime — desktop (non-XR) mode")
