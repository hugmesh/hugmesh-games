extends Node

# CharForge JavaScriptBridge singleton (autoload "CharForgeBridge").
#
# Exposes the DESIGN §4 host contract on `window.CharForge` when running as a
# web export (guarded by OS.has_feature("web") — a no-op everywhere else):
#
#   IN  (host -> game):
#     CharForge.setConfig(json)        apply a Char3DConfig definition
#     CharForge.setExpression(label)   one of the 10 canonical labels
#     CharForge.setOpenness(f)         mouth openness 0..1 (amplitude contract)
#     CharForge.randomize(seed)        seeded archetype-coherent randomize
#     CharForge.snapshot(w, h)         -> base64 PNG of the current turntable
#                                      view (avatar/sticker reuse). The PNG is
#                                      ALSO stored on CharForge.lastSnapshot
#                                      and delivered to CharForge.onSnapshot
#                                      (if assigned) for hosts that cannot use
#                                      the direct return value.
#     CharForge.listPacks()            -> JSON catalogue of the vendored packs
#                                      (lanes, entries, licences, tri/joint
#                                      budgets, recorded capability gaps).
#     CharForge.setPack(key)           mount a catalogue entry ("" = back to the
#                                      purely procedural avatar).
#     CharForge.importModel(b64, name) BYO avatar: base64 VRM/GLB bytes handed
#                                      straight to the engine. The bytes are
#                                      parsed in WASM memory — never written to
#                                      disk, never sent to the BFF. Returns an
#                                      ACK; the outcome (including the VRM's own
#                                      in-file licence terms) arrives on
#                                      onImportResult.
#   OUT (game -> host):
#     CharForge.onConfigChanged(json)  assigned BY the host; called with the
#                                      full sanitized config JSON whenever the
#                                      in-game UI edits/randomizes the
#                                      character. The latest JSON is mirrored
#                                      on CharForge.lastConfig for late hosts.
#     CharForge.onPackChanged(json)    the mount report after setPack (which
#                                      model, recolourable?, lip-sync channels,
#                                      licence + attribution). Mirrored on
#                                      CharForge.lastPack.
#     CharForge.onImportResult(json)   the same report for an imported file,
#                                      plus its `license` block. Mirrored on
#                                      CharForge.lastImport.
#
#   Convenience (non-contractual): CharForge.setUiVisible(bool) hides the
#   built-in editor panels when an external parameter editor drives the game.
#
# A `charforge-ready` CustomEvent is dispatched on window once the interface
# is installed, so a host embedding the Engine can attach callbacks without
# polling.

signal config_received(json: String)
signal expression_received(label: String)
signal openness_received(value: float)
signal randomize_received(seed_value: int)
signal rig_received(json: String)
signal auto_adjust_requested()
signal auto_rotate_received(on: bool)
## Host asked to mount a catalogue entry ("" = drop back to procedural).
signal pack_received(key: String)
## Host handed us avatar BYTES to load locally. They never leave the process.
signal import_received(bytes: PackedByteArray, file_name: String)

## Assigned by studio.gd: func(w: int, h: int) -> String (base64 PNG).
var snapshot_provider: Callable = Callable()

var _iface: JavaScriptObject = null
# JavaScriptBridge.create_callback references MUST be kept alive or the JS
# side silently stops calling them (documented Godot gotcha).
var _keepalive: Array = []


func _ready() -> void:
	if not OS.has_feature("web"):
		return
	_iface = JavaScriptBridge.create_object("Object")
	_bind("setConfig", _js_set_config)
	_bind("setExpression", _js_set_expression)
	_bind("setOpenness", _js_set_openness)
	_bind("randomize", _js_randomize)
	_bind("snapshot", _js_snapshot)
	_bind("setRig", _js_set_rig)
	_bind("autoAdjustRig", _js_auto_adjust_rig)
	_bind("setAutoRotate", _js_set_auto_rotate)
	_bind("listPacks", _js_list_packs)
	_bind("setPack", _js_set_pack)
	_bind("importModel", _js_import_model)
	var window: JavaScriptObject = JavaScriptBridge.get_interface("window")
	window.CharForge = _iface
	# Announce readiness WITHOUT JavaScriptBridge.eval().
	#
	# eval() compiles a string as JavaScript, which a nonce-CSP refuses unless
	# it carries 'unsafe-eval'. Every HugMesh UI ships a strict nonce policy and
	# the host shell's applies to any remote mounted into it, so the old
	#   JavaScriptBridge.eval("window.dispatchEvent(new CustomEvent(...))")
	# threw on every boot:
	#   EvalError: Evaluating a string as JavaScript violates the following
	#   Content Security Policy directive ... 'unsafe-eval' is not an allowed
	#   source of script
	# It was survivable — the interface above is already installed by then, so
	# the page's poller still found window.CharForge — but it put a hard error in
	# every operator's console on every load and made the boot look broken.
	#
	# create_object() calls the real constructor through the bridge instead of
	# compiling source, so it needs no CSP concession at all. 'wasm-unsafe-eval'
	# (which the shell DOES grant, for WebAssembly) would never have covered
	# eval() — that is a separate token, and granting 'unsafe-eval' to make one
	# announcement work would weaken the policy for the whole shell.
	var ready_event: JavaScriptObject = JavaScriptBridge.create_object(
			"CustomEvent", "charforge-ready")
	if ready_event != null:
		window.dispatchEvent(ready_event)


## True when running under a web host (interface installed).
func is_web() -> bool:
	return _iface != null


## Game -> host: the character definition changed inside the game.
func emit_config_changed(json: String) -> void:
	if _iface == null:
		return
	_iface.lastConfig = json
	var cb: Variant = _iface.onConfigChanged
	if cb is JavaScriptObject:
		# Function.prototype.call(thisArg, json)
		cb.call("call", _iface, json)


## Game -> host: the mount report after a setPack (see CharForgeMount).
func emit_pack_changed(json: String) -> void:
	_emit_out("lastPack", "onPackChanged", json)


## Game -> host: the outcome of an importModel, including the file's own
## licence block. Called even on failure (report.ok == false).
func emit_import_result(json: String) -> void:
	_emit_out("lastImport", "onImportResult", json)


## The catalogue JSON a host would get from listPacks(), also usable natively.
func packs_json() -> String:
	return JSON.stringify(CharForgePacks.to_host_json())


func _emit_out(mirror_prop: String, callback_prop: String, json: String) -> void:
	if _iface == null:
		return
	_iface.set(mirror_prop, json)
	var cb: Variant = _iface.get(callback_prop)
	if cb is JavaScriptObject:
		cb.call("call", _iface, json)


func _bind(prop: String, method: Callable) -> void:
	var cb := JavaScriptBridge.create_callback(method)
	_keepalive.append(cb)
	_iface.set(prop, cb)


func _js_set_config(args: Array) -> Variant:
	if args.is_empty() or not (args[0] is String):
		return false
	config_received.emit(String(args[0]))
	return true


func _js_set_expression(args: Array) -> Variant:
	if args.is_empty() or not (args[0] is String):
		return false
	expression_received.emit(String(args[0]))
	return true


func _js_set_openness(args: Array) -> Variant:
	if args.is_empty() or not (args[0] is float or args[0] is int):
		return false
	openness_received.emit(clampf(float(args[0]), 0.0, 1.0))
	return true


func _js_set_rig(args: Array) -> Variant:
	if args.is_empty() or not (args[0] is String):
		return false
	rig_received.emit(String(args[0]))
	return true


func _js_auto_adjust_rig(_args: Array) -> Variant:
	auto_adjust_requested.emit()
	return true


func _js_set_auto_rotate(args: Array) -> Variant:
	if args.is_empty():
		return false
	auto_rotate_received.emit(bool(args[0]))
	return true


func _js_randomize(args: Array) -> Variant:
	var seed_value: int = 0
	if not args.is_empty():
		var a: Variant = args[0]
		if a is float or a is int:
			seed_value = int(a)
		elif a is String:
			seed_value = String(a).hash()
	else:
		seed_value = int(Time.get_ticks_msec())
	randomize_received.emit(seed_value)
	return true


func _js_snapshot(args: Array) -> Variant:
	var w := 512
	var h := 512
	if args.size() >= 1 and (args[0] is float or args[0] is int):
		w = clampi(int(args[0]), 16, 2048)
	if args.size() >= 2 and (args[1] is float or args[1] is int):
		h = clampi(int(args[1]), 16, 2048)
	if not snapshot_provider.is_valid():
		return ""
	var b64: String = snapshot_provider.call(w, h)
	if _iface != null:
		_iface.lastSnapshot = b64
		var cb: Variant = _iface.onSnapshot
		if cb is JavaScriptObject:
			cb.call("call", _iface, b64)
	return b64




func _js_list_packs(_args: Array) -> Variant:
	return packs_json()


func _js_set_pack(args: Array) -> Variant:
	if args.is_empty():
		pack_received.emit("")
		return true
	if not (args[0] is String):
		return false
	pack_received.emit(String(args[0]))
	return true


## Base64 in, avatar on stage. The ACK is synchronous (the parse is not), and
## the size is rejected HERE rather than after decoding a 100 MB string into
## WASM memory: 4/3 of the byte cap is the largest base64 that can be in-cap.
func _js_import_model(args: Array) -> Variant:
	if args.size() < 1 or not (args[0] is String):
		return JSON.stringify({"ok": false, "reason": "importModel(base64, fileName)"})
	var b64: String = String(args[0])
	var file_name := "avatar.vrm"
	if args.size() >= 2 and args[1] is String and not String(args[1]).strip_edges().is_empty():
		file_name = String(args[1]).strip_edges().substr(0, 120)
	var max_b64 := int(ceil(CharForgeGlbLoader.MAX_BYTES * 4.0 / 3.0)) + 4
	if b64.length() > max_b64:
		var reason := "file is over the %d-byte runtime cap" % CharForgeGlbLoader.MAX_BYTES
		emit_import_result(JSON.stringify({"ok": false, "reason": reason, "fileName": file_name}))
		return JSON.stringify({"ok": false, "reason": reason})
	var bytes := Marshalls.base64_to_raw(b64)
	if bytes.is_empty():
		var empty_reason := "could not decode the file bytes"
		emit_import_result(JSON.stringify({"ok": false, "reason": empty_reason, "fileName": file_name}))
		return JSON.stringify({"ok": false, "reason": empty_reason})
	import_received.emit(bytes, file_name)
	return JSON.stringify({"ok": true, "queued": true, "fileName": file_name,
			"bytes": bytes.size()})
