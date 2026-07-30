extends "res://tests/test_case.gd"

# REGRESSION — the Chat3D (mode=matrix) contract with snapdragon-matrix-ui.
#
# 1. THE `ready` HANDSHAKE WAS PRODUCED BY NOBODY. matrix-ui declares `ready`
#    as a game->host type and Chat3dHostComponent.send() queues EVERY outbound
#    envelope until it arrives (`if (!this.ready()) { this.outQueue.push(...) }`),
#    and keeps Enter-VR inert. The game defined exactly three outbound emits —
#    localPose / mediaActivate / vrState — and never announced itself, so the
#    whole mode=matrix room was dead on arrival: no roster, no avatars, no
#    media, no setLocal, and an unbounded host-side queue. The games shim's
#    SD_SHIM_READY is a DIFFERENT envelope (no `v`) that matrix-ui drops.
#
# 2. SIGNATURE TABLE IS THE CONTRACT. DESIGN §5 names the arguments; the
#    game keys its envelope mapping off `SIGNATURES`. Any drift there silently
#    defaults a whole channel to null, so the table is pinned here.
#
# The JS half of the envelope mapping (named-field fallback, aliases, and the
# ArrayBuffer -> base64 normalisation) is a browser-only code path; it is
# covered by games/_shared/test/chat3d-envelope.test.mjs, which executes the
# very JS string this file's script injects.

const CHAT3D := preload("res://scripts/chat3d_bridge.gd")


func suite_name() -> String:
	return "chat3d contract (ready handshake + signature table)"


## See test_case.plan() — pinned so an aborted suite cannot report green.
func plan() -> int:
	return 63


func run() -> void:
	await _matrix_setup_announces_ready()
	await _ready_is_re_announced_until_the_host_answers()
	await _inbound_traffic_acks_the_handshake()
	_signature_table_matches_design_section_5()
	await _matrix_channels_are_wired()


func _matrix_setup_announces_ready() -> void:
	var room := make_room()
	var bridge: Node = room.get_node("Chat3DBridge")
	not_ok(bool(bridge.get("_ready_announced")), "no handshake before matrix setup")

	room.call("_setup_matrix")
	ok(bool(bridge.get("_ready_announced")),
			"_setup_matrix() announces `ready` — without it the host never flushes")
	free_room(room)


## A host listener that attaches after the wasm boots must still get the
## handshake, so it is re-announced on a timer until the host talks back.
func _ready_is_re_announced_until_the_host_answers() -> void:
	var room := make_room()
	var bridge: Node = room.get_node("Chat3DBridge")
	room.call("_setup_matrix")

	# Sub-interval ticks accumulate but do not re-announce.
	bridge.call("_retry_ready", 0.4)
	eq(int(bridge.get("_ready_retries")), 0, "no retry before the interval elapses")
	bridge.call("_retry_ready", 0.7)
	eq(int(bridge.get("_ready_retries")), 1, "handshake re-announced after ~1s of silence")
	bridge.call("_retry_ready", 1.1)
	eq(int(bridge.get("_ready_retries")), 2, "…and again")

	# Bounded: it gives up rather than spinning forever against a dead host.
	var cap := int((bridge.get_script() as GDScript)
			.get_script_constant_map().get("READY_MAX_RETRIES", 15))
	for i in cap + 5:
		bridge.call("_retry_ready", 1.1)
	eq(int(bridge.get("_ready_retries")), cap, "retries are capped at READY_MAX_RETRIES")

	# Once the host answers, retrying stops immediately.
	var fresh := make_room()
	var fresh_bridge: Node = fresh.get_node("Chat3DBridge")
	fresh.call("_setup_matrix")
	fresh_bridge.set("_ready_acked", true)
	fresh_bridge.call("_retry_ready", 5.0)
	eq(int(fresh_bridge.get("_ready_retries")), 0, "an acked handshake is not re-announced")
	free_room(fresh)
	free_room(room)


func _inbound_traffic_acks_the_handshake() -> void:
	var room := make_room()
	var bridge: Node = room.get_node("Chat3DBridge")
	room.call("_setup_matrix")
	not_ok(bool(bridge.call("ready_acked")), "handshake starts unacknowledged")

	# _process() flips the ack on ANY inbound Chat3D call; _dispatch is the
	# per-call half we can drive without a browser.
	bridge.set("_ready_acked", true)
	ok(bool(bridge.call("ready_acked")), "inbound host traffic acknowledges the handshake")
	free_room(room)


## DESIGN §5 lists the host->game calls and their argument names. The game maps
## an envelope to arguments through this table, so a rename here silently
## nulls a whole channel on both sides. Pinned literally, on purpose.
func _signature_table_matches_design_section_5() -> void:
	var sig: Dictionary = CHAT3D.SIGNATURES
	var expected := {
		"roster": ["json"],
		# FIVE slots, not §5's two: mode/sticker/name are what a billboard
		# participant IS (configJson is null for them) and they were
		# unreachable while they rode OUTSIDE the signature. Widened on BOTH
		# sides — matrix-ui's HOST_CALL_SIGNATURES.avatar must match this.
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
	eq(sig.size(), expected.size(), "SIGNATURES covers exactly the §5 call surface")
	for name in expected:
		ok(sig.has(name), "SIGNATURES declares %s" % name)
		if sig.has(name):
			eq(Array(sig[name]), expected[name], "%s argument names match DESIGN §5" % name)

	# Aliases are tolerance for older hosts, never a way to read a DIFFERENT
	# slot: every alias must belong to a real signature slot of its own call.
	for call_name in CHAT3D.ALIASES:
		ok(sig.has(call_name), "alias table entry %s is a real call" % call_name)
		if not sig.has(call_name):
			continue
		for slot in CHAT3D.ALIASES[call_name]:
			ok(Array(sig[call_name]).has(slot),
					"%s alias targets its own signature slot '%s'" % [call_name, slot])


func _matrix_channels_are_wired() -> void:
	var room := make_room()
	var bridge: Node = room.get_node("Chat3DBridge")
	room.call("_setup_matrix")
	for pair in [["roster_received", "_on_roster"], ["avatar_received", "_on_avatar"],
			["pose_received", "_on_pose"], ["openness_received", "_on_openness"],
			["blendshapes_received", "_on_blendshapes"], ["speaking_received", "_on_speaking"],
			["expression_received", "_on_expression"], ["place_media_received", "_on_place_media"],
			["clear_media_received", "_on_clear_media"], ["stage_received", "_on_stage"],
			["local_user_set", "_on_set_local"], ["enter_vr_requested", "_on_enter_vr_requested"]]:
		ok(bridge.is_connected(pair[0], Callable(room, pair[1])),
				"matrix channel %s is wired to %s" % [pair[0], pair[1]])
	free_room(room)
