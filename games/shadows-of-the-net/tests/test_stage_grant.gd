extends "res://tests/test_case.gd"

# REGRESSION — the session-mode stage grant shipped with NO producer.
#
# `SOTN_STAGE|<0|1>` is documented in the room_controller header, in README.md
# ("stage request/release — host arbitrates, one at a time") and in migration
# 056's changeset comment; `_host_set_stage` implements the arbitration and
# `_broadcast_world` carries a `stage` field. But nothing in the game ever
# EMITTED one: `grep -rn SOTN_STAGE scripts/` matched only the constant, the
# receiver and the docs. The scene's only Ui children are ModeLabel and
# EnterVrButton, the StagePlatform mesh was referenced by no script, and
# local_controller's click handler resolved a floor click to a media surface,
# a seat, or a walk target — never to the stage.
#
# Consequence on the only shipped launch path (migration 056-4 grants
# game_access scope='all', so mode=session is what every portal user gets):
# `_stage_cid` was permanently -1, every world broadcast carried "stage": -1,
# `set_stage(key, true)` was never reached, and the StageSpot spotlight could
# never light. DESIGN §5 names "stage grants" as half of the session-mode
# host-authoritative world state.
#
# Fixed by making the stage platform clickable (local_controller.stage_clicked
# → room_controller._on_local_stage_clicked), which is the same request-then-
# wait-for-the-echo shape a seat claim already uses.


func suite_name() -> String:
	return "stage grant (session-mode producer + arbitration)"


## See test_case.plan() — pinned so an aborted suite cannot report green.
func plan() -> int:
	return 29


func run() -> void:
	await _the_stage_platform_is_clickable()
	await _a_guest_requests_the_stage_over_the_movement_channel()
	await _the_host_arbitrates_one_holder_at_a_time()
	await _the_authoritative_echo_lights_the_spot_and_moves_the_rig()
	await _a_guest_never_self_grants()


## Records what the room asks the transport to send.
class RecordingBridge extends Node:
	var character_id := 1
	var slot_index := 3
	var sent: Array[String] = []

	func send_control(action: String) -> void:
		sent.append(action)

	func send_world(_payload: Dictionary) -> void:
		pass


func _with_recorder(room: Node3D) -> RecordingBridge:
	var rec := RecordingBridge.new()
	rec.character_id = int((room.get("_session") as Node).get("character_id"))
	room.add_child(rec)
	room.set("_session", rec)
	return rec


func _stage_actions(rec: RecordingBridge) -> Array:
	var out: Array = []
	for action in rec.sent:
		if action.begins_with("SOTN_STAGE|"):
			out.append(action)
	return out


## The click path itself: a floor ray landing on the StagePlatform disc must
## reach room_controller as a stage request, not as a walk target.
func _the_stage_platform_is_clickable() -> void:
	var room := _guest_room()
	var local: Node3D = room.get_node("DesktopRig")
	var platform: Node3D = room.get_node("Chamber/StagePlatform")

	ok(local.get("stage_platform") != null,
			"room_controller injects the StagePlatform into the desktop rig")
	eq(local.get("stage_platform"), platform, "…and it is the real scene node")

	var fired := [false]
	local.connect("stage_clicked", func() -> void: fired[0] = true)

	# _hit_stage is the geometry half of _handle_click (the ray itself needs a
	# real viewport/camera projection, which a headless run has no mouse for).
	var centre: Vector3 = platform.position
	ok(bool(local.call("_hit_stage", Vector3(centre.x, 0.0, centre.z))),
			"a click at the platform centre is a stage hit")
	ok(bool(local.call("_hit_stage", Vector3(centre.x + 1.4, 0.0, centre.z))),
			"…and one near its rim")
	not_ok(bool(local.call("_hit_stage", Vector3(0.0, 0.0, 0.0))),
			"a click on the table is NOT a stage hit")
	not_ok(bool(local.call("_hit_stage", Vector3(centre.x + 3.0, 0.0, centre.z))),
			"a click well outside the disc is NOT a stage hit")

	local.emit_signal("stage_clicked")
	ok(fired[0], "the stage_clicked signal is live")
	free_room(room)


func _a_guest_requests_the_stage_over_the_movement_channel() -> void:
	var room := _guest_room()
	var rec := _with_recorder(room)

	room.call("_on_local_stage_clicked")
	eq(_stage_actions(rec), ["SOTN_STAGE|1"], "a guest ASKS for the stage")

	# The authoritative echo grants it; clicking again releases.
	room.call("_on_world", {"type": "sotn.room", "seats": {},
			"placements": {}, "stage": rec.character_id, "tick": 1})
	await tree.process_frame
	room.call("_on_local_stage_clicked")
	eq(_stage_actions(rec), ["SOTN_STAGE|1", "SOTN_STAGE|0"],
			"clicking while holding the stage releases it")
	free_room(room)


func _the_host_arbitrates_one_holder_at_a_time() -> void:
	var room := _host_room()          # character_id 1, slot 0

	room.call("_on_movement", {"characterId": 7, "action": "SOTN_STAGE|1"})
	eq(int(room.get("_stage_cid")), 7, "first requester takes the stage")

	room.call("_on_movement", {"characterId": 42, "action": "SOTN_STAGE|1"})
	eq(int(room.get("_stage_cid")), 7, "a second requester is refused")

	# Only the holder may release it.
	room.call("_on_movement", {"characterId": 42, "action": "SOTN_STAGE|0"})
	eq(int(room.get("_stage_cid")), 7, "a non-holder cannot revoke the grant")

	room.call("_on_movement", {"characterId": 7, "action": "SOTN_STAGE|0"})
	eq(int(room.get("_stage_cid")), -1, "the holder releases it")

	# The host's own click goes through the same arbitration, in-process.
	room.call("_on_local_stage_clicked")
	eq(int(room.get("_stage_cid")), 1, "the host can take the freed stage")
	room.call("_on_local_stage_clicked")
	eq(int(room.get("_stage_cid")), -1, "…and release it again")

	# It is in the authoritative payload, which is what guests apply.
	room.call("_on_movement", {"characterId": 7, "action": "SOTN_STAGE|1"})
	eq(int(room.get("_stage_cid")), 7, "stage re-taken for the broadcast check")
	free_room(room)


func _the_authoritative_echo_lights_the_spot_and_moves_the_rig() -> void:
	var room := _guest_room()          # character_id 1, slot 3
	var avatars: Node3D = room.get_node("Avatars")
	var spot: SpotLight3D = room.get_node("Lights/StageSpot")
	var local: Node3D = room.get_node("DesktopRig")
	var anchor: Node3D = room.get_node("StageAnchor")

	not_ok(spot.visible, "the spotlight starts dark")

	room.call("_on_world", {"type": "sotn.room", "seats": {},
			"placements": {}, "stage": 7, "tick": 1})
	await tree.process_frame
	ok(spot.visible, "a granted stage lights the spot")
	ok(bool(avatars.call("is_on_stage", "7")), "the holder is placed on the stage")
	not_ok(bool(avatars.call("is_on_stage", "1")), "…and nobody else is")

	# Granted to US: the desktop rig follows so the camera does not stay behind.
	room.call("_on_world", {"type": "sotn.room", "seats": {},
			"placements": {}, "stage": 1, "tick": 2})
	await tree.process_frame
	almost(float(local.get("pos").x), anchor.position.x, "local rig moved onto the stage (x)")
	almost(float(local.get("pos").z), anchor.position.z, "local rig moved onto the stage (z)")
	eq(int(local.get("seat")), -1, "taking the stage stands us off our seat")

	room.call("_on_world", {"type": "sotn.room", "seats": {},
			"placements": {}, "stage": -1, "tick": 3})
	await tree.process_frame
	not_ok(spot.visible, "releasing the stage darkens the spot")
	not_ok(bool(avatars.call("is_on_stage", "1")), "the grant is revoked")
	free_room(room)


## A guest must not paint the grant on itself before the host agrees, and must
## never act on another member's request.
func _a_guest_never_self_grants() -> void:
	var room := _guest_room()
	var rec := _with_recorder(room)
	var spot: SpotLight3D = room.get_node("Lights/StageSpot")

	room.call("_on_local_stage_clicked")
	await tree.process_frame
	eq(int(room.get("_stage_cid")), -1, "the click does not self-grant")
	not_ok(spot.visible, "…and does not light the spot")
	eq(_stage_actions(rec).size(), 1, "it only asks")

	room.call("_on_movement", {"characterId": 9, "action": "SOTN_STAGE|1"})
	eq(int(room.get("_stage_cid")), -1, "a guest never arbitrates a peer's request")
	free_room(room)


## Boot through the real `_setup_session()` wiring; slot 0 is the creator slot
## the BFF pins (and the only member Gate 0b lets post kind:'world').
func _session_room(slot_index: int) -> Node3D:
	var room := make_room()
	room.set("mode", 0)              # Mode.SESSION
	var bridge: Node = room.get_node("SessionBridge")
	bridge.set("character_id", 1)
	bridge.set("slot_index", slot_index)
	room.call("_setup_session")
	return room


func _guest_room() -> Node3D:
	return _session_room(3)


func _host_room() -> Node3D:
	return _session_room(0)
