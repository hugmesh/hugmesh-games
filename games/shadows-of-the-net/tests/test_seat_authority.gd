extends "res://tests/test_case.gd"

# REGRESSION — the documented host-authoritative seat map must actually be
# authoritative.
#
# README/`room_controller` header state the contract:
#     SOTN_SEAT|<seat>   seat request -> host validates + rebroadcasts
# and `_host_assign_seat` genuinely refuses a taken seat. But the POSE branch
# used to pass `parts[4].to_int()` — the SENDER's own byte — straight into
# `AvatarManager.set_pose`, which snaps to `_seat_anchor(seat)` for any in-range
# index. The BFF relays a member's action string VERBATIM to the whole session,
# so any member could self-assign any seat 10x/second, overriding the host's
# arbitration on every peer within one tick and outrunning the authoritative
# echo (which only fires when the host's state CHANGES).
#
# The fix: for a REMOTE peer the seat is read from the mirrored authoritative
# map, never from the peer's own action.


func suite_name() -> String:
	return "seat authority (host map wins over peer-declared seat)"


## See test_case.plan() — pinned so an aborted suite cannot report green.
func plan() -> int:
	return 37


func run() -> void:
	await _the_pose_carries_no_seat_field()
	await _peer_cannot_self_assign_a_seat()
	await _peer_cannot_squat_someone_elses_seat()
	await _peer_cannot_unseat_itself_off_the_host_map()
	await _host_arbitration_still_refuses_a_taken_seat()
	await _authoritative_echo_unseats_a_peer_it_omits()
	await _walking_off_a_seat_reaches_the_host()
	await _the_host_releases_its_own_seat_when_it_walks()
	await _a_host_driven_sit_does_not_feed_back()
	await _forged_action_bursts_cannot_drive_the_broadcast_rate()


## Mirrors room_controller.WORLD_BROADCAST_INTERVAL (a `const` on a script that
## is not `class_name`d, so it cannot be referenced from here directly).
const WORLD_INTERVAL := 0.1


## Records what the room asks the transport to send. Swapped in AFTER
## `_setup_session()` so the real bridge still drives the wiring + election.
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


func _seat_actions(rec: RecordingBridge) -> Array:
	var out: Array = []
	for action in rec.sent:
		if action.begins_with("SOTN_SEAT|"):
			out.append(action)
	return out


func _seat_pos(room: Node3D, index: int) -> Vector3:
	return (room.get_node("Seats").get_child(index) as Node3D).position


## …and therefore the pose must not ADVERTISE one.
##
## SOTN_POSE used to ship a fifth `<seat>` field: every sender populated it and
## no receiver ever read it — the branch below is exactly why it must never be
## read. A field that looks load-bearing and isn't is the mirror image of a verb
## with no producer (how the stage grant shipped unreachable): the next reader
## has to re-derive from the code that it was decorative, and the wire claims an
## authority the format does not have. It is gone. Parsing stays length-TOLERANT
## so a browser holding a cached older bundle keeps working — its trailing seat
## lands past the end of what `_parse_pose` reads.
func _the_pose_carries_no_seat_field() -> void:
	var room := _guest_room()
	var rec := _with_recorder(room)
	var avatars: Node3D = room.get_node("Avatars")

	room.call("_on_local_pose", 1.25, -2.5, 0.75, 4)
	var poses: Array = []
	for action in rec.sent:
		if action.begins_with("SOTN_POSE|"):
			poses.append(action)
	eq(poses, ["SOTN_POSE|1.25|-2.50|0.75"], "the pose is x/z/yaw and nothing else")
	# The seat it no longer carries still reached the host — on its own channel.
	eq(_seat_actions(rec), ["SOTN_SEAT|4"],
			"the seat travels the arbitrated channel instead")

	# A legacy five-field pose from a stale bundle is still applied…
	room.call("_on_movement", {"characterId": 42, "action": "SOTN_POSE|3.00|1.00|0.50|9"})
	await tree.process_frame
	ok(bool(avatars.call("has_avatar", "42")), "a legacy five-field pose is still applied")
	eq(int(avatars.call("seat_of", "42")), -1, "…and its trailing seat is still ignored")

	# …but one too short to carry a position is dropped outright.
	room.call("_on_movement", {"characterId": 43, "action": "SOTN_POSE|3.00|1.00"})
	await tree.process_frame
	not_ok(bool(avatars.call("has_avatar", "43")), "a truncated pose creates no avatar")
	free_room(room)


func _peer_cannot_self_assign_a_seat() -> void:
	var room := _guest_room()
	var avatars: Node3D = room.get_node("Avatars")

	# No authoritative broadcast has seated anyone. Mallory declares seat 3.
	room.call("_on_movement", {"characterId": 42, "action": "SOTN_POSE|0|0|0|3"})
	await tree.process_frame

	eq(int(avatars.call("seat_of", "42")), -1, "peer-declared seat is ignored")
	var target: Vector3 = avatars.get("_avatars")["42"]["target_pos"]
	ne(target, _seat_pos(room, 3), "peer was NOT snapped onto the seat-3 anchor")
	free_room(room)


func _peer_cannot_squat_someone_elses_seat() -> void:
	var room := _guest_room()
	var avatars: Node3D = room.get_node("Avatars")

	# The host has legitimately seated Alice (cid 7) at seat 3.
	room.call("_on_world", {"type": "sotn.room", "seats": {"7": 3}, "stage": -1, "tick": 1})
	await tree.process_frame
	eq(int(avatars.call("seat_of", "7")), 3, "authoritative echo seated Alice at 3")

	# Mallory (cid 42) claims the same seat in her own 10 Hz pose.
	for i in 3:
		room.call("_on_movement", {"characterId": 42, "action": "SOTN_POSE|0|0|0|3"})
		await tree.process_frame

	eq(int(avatars.call("seat_of", "42")), -1, "squatter never acquires the seat")
	eq(int(avatars.call("seat_of", "7")), 3, "the real holder keeps the seat")
	ne(avatars.get("_avatars")["42"]["target_pos"], _seat_pos(room, 3),
			"squatter is not stacked on the seat-3 anchor")
	free_room(room)


## The mirror is symmetric: a member cannot silently LEAVE a seat the host
## still holds for them either, or the two sides drift apart.
func _peer_cannot_unseat_itself_off_the_host_map() -> void:
	var room := _guest_room()
	var avatars: Node3D = room.get_node("Avatars")
	room.call("_on_world", {"type": "sotn.room", "seats": {"7": 5}, "stage": -1, "tick": 1})
	await tree.process_frame

	room.call("_on_movement", {"characterId": 7, "action": "SOTN_POSE|8|8|0|-1"})
	await tree.process_frame
	eq(int(avatars.call("seat_of", "7")), 5, "peer cannot drop its host-assigned seat by pose")
	free_room(room)


func _host_arbitration_still_refuses_a_taken_seat() -> void:
	var room := _host_room()
	room.call("_host_assign_seat", 7, 4)
	eq(int(room.get("_seat_by_cid").get(7, -1)), 4, "first claimant gets the seat")

	room.call("_host_assign_seat", 42, 4)
	eq(int(room.get("_seat_by_cid").get(42, -1)), -1, "second claimant is refused")

	# Out-of-range indices are refused outright (MAX_SEATS = 16 anchors).
	room.call("_host_assign_seat", 9, 16)
	room.call("_host_assign_seat", 9, -2)
	room.call("_host_assign_seat", 9, 1 << 40)
	eq(int(room.get("_seat_by_cid").get(9, -1)), -1, "out-of-range seat requests refused")

	# -1 releases, and the freed seat becomes claimable again.
	room.call("_host_assign_seat", 7, -1)
	not_ok(room.get("_seat_by_cid").has(7), "-1 releases the seat")
	room.call("_host_assign_seat", 42, 4)
	eq(int(room.get("_seat_by_cid").get(42, -1)), 4, "released seat is re-claimable")
	free_room(room)


func _authoritative_echo_unseats_a_peer_it_omits() -> void:
	var room := _guest_room()
	var avatars: Node3D = room.get_node("Avatars")
	room.call("_on_world", {"type": "sotn.room", "seats": {"7": 2}, "stage": -1, "tick": 1})
	await tree.process_frame
	eq(int(avatars.call("seat_of", "7")), 2, "peer seated by the first echo")

	# Host released/reassigned the seat: the next echo simply omits cid 7.
	room.call("_on_world", {"type": "sotn.room", "seats": {}, "stage": -1, "tick": 2})
	await tree.process_frame
	eq(int(avatars.call("seat_of", "7")), -1, "omitted peer is stood up, not left glued")
	eq(int(room.get("_seat_by_cid").size()), 0, "mirrored seat map matches the echo")
	free_room(room)


## The other half of "peers ignore the pose's <seat>": every seat change must
## then travel the ARBITRATED channel, or the room never learns about it.
##
## `local_controller._process_desktop` sets `seat = -1` on ANY walk step and
## emits only `pose_changed` — `seat_clicked` fires solely on a seat CLICK. So a
## guest who stands up by walking used to tell nobody: peers ignore the pose's
## seat field, the host's `_seat_by_cid` kept the seat forever (refusing it to
## everyone else for the life of the room), and every OTHER client kept the
## walker snapped to the anchor they had physically left.
func _walking_off_a_seat_reaches_the_host() -> void:
	var room := _guest_room()
	var rec := _with_recorder(room)

	# Click seat 4 — the pre-existing click path.
	room.call("_on_local_seat_clicked", 4)
	room.call("_on_local_pose", 0.0, 0.0, 0.0, 4)
	eq(_seat_actions(rec), ["SOTN_SEAT|4"], "the click claims the seat exactly once")

	# Now WALK. local_controller has already dropped `seat` to -1 and re-emitted
	# pose_changed; nothing else fires.
	room.call("_on_local_pose", 1.2, 0.4, 0.9, -1)
	eq(_seat_actions(rec), ["SOTN_SEAT|4", "SOTN_SEAT|-1"],
			"walking off the seat releases it over the arbitrated channel")

	# …and the release is sent ONCE, not at the 10 Hz pose rate.
	for i in 5:
		room.call("_on_local_pose", 1.2 + i, 0.4, 0.9, -1)
	eq(_seat_actions(rec).size(), 2, "an unchanged seat is not re-sent every tick")
	free_room(room)


## Same defect on the host, which arbitrates its own seat in-process.
func _the_host_releases_its_own_seat_when_it_walks() -> void:
	var room := _host_room()          # character_id 1, slot 0
	room.call("_on_local_seat_clicked", 6)
	room.call("_on_local_pose", 0.0, 0.0, 0.0, 6)
	eq(int(room.get("_seat_by_cid").get(1, -1)), 6, "host seats itself on click")

	room.call("_on_local_pose", 3.0, 3.0, 0.0, -1)
	not_ok(room.get("_seat_by_cid").has(1),
			"the host frees its own seat when it walks away")

	# The freed seat is genuinely claimable by someone else afterwards.
	room.call("_host_assign_seat", 42, 6)
	eq(int(room.get("_seat_by_cid").get(42, -1)), 6, "the released seat is re-claimable")

	# …and the host's OWN claim on that now-taken seat is refused by the same
	# arbitration. A guest learns this from the authoritative echo; the host
	# never receives that branch, so it has to stand itself back up or it sits
	# on top of the real holder.
	var local: Node3D = room.get_node("DesktopRig")
	local.call("sit", 6)
	eq(int(local.get("seat")), 6, "the host optimistically sits on click")
	room.call("_on_local_seat_clicked", 6)
	eq(int(room.get("_seat_by_cid").get(42, -1)), 6, "the real holder keeps the seat")
	eq(int(local.get("seat")), -1, "the host stands back up when its claim is refused")
	free_room(room)


## The seat relay must not turn a host-driven sit into a broadcast loop:
## _on_world → sit() → pose tick → SOTN_SEAT → host → _broadcast_world → …
func _a_host_driven_sit_does_not_feed_back() -> void:
	var room := _host_room()
	room.call("_host_assign_seat", 1, 2)
	room.call("_flush_world", 1.0)               # settle the pending broadcast
	var tick_before := int(room.get("_world_tick"))

	# Re-affirming the seat we already hold changes nothing and broadcasts nothing.
	room.call("_on_local_pose", 0.0, 0.0, 0.0, 2)
	room.call("_on_local_pose", 0.0, 0.0, 0.0, 2)
	eq(int(room.get("_seat_by_cid").get(1, -1)), 2, "the seat is unchanged")
	not_ok(bool(room.get("_world_dirty")), "a no-op seat claim owes no broadcast")
	room.call("_flush_world", 1.0)
	eq(int(room.get("_world_tick")), tick_before, "…and none is emitted")
	free_room(room)


## AMPLIFICATION — the BFF relays every member's control action to the whole
## session, so one guest can drive the host's arbitration at its own send rate.
## Each call used to POST an authoritative broadcast immediately, which the BFF
## then fanned out to EVERY member: a 1:N amplifier with a guest on the trigger.
## Broadcasts are coalesced to WORLD_BROADCAST_INTERVAL; the payload is full
## state, so dropping intermediate ticks converges to the same room.
func _forged_action_bursts_cannot_drive_the_broadcast_rate() -> void:
	var room := _host_room()
	room.call("_flush_world", 1.0)
	var tick_before := int(room.get("_world_tick"))

	# 60 alternating seat claims + media toggles from a hostile member, all in
	# one interval — the shape of a 10 Hz control flood.
	for i in 30:
		room.call("_on_movement", {"characterId": 9, "action": "SOTN_SEAT|%d" % (i % 16)})
		room.call("_on_movement", {"characterId": 9, "action": "SOTN_MEDIA|table-1"})
	eq(int(room.get("_world_tick")), tick_before, "no broadcast is emitted inline")
	ok(bool(room.get("_world_dirty")), "…but the state change is remembered")

	room.call("_flush_world", WORLD_INTERVAL)
	eq(int(room.get("_world_tick")), tick_before + 1,
			"60 forged actions collapse into ONE authoritative broadcast")

	# A sub-interval flush after that emits nothing more.
	room.call("_on_movement", {"characterId": 9, "action": "SOTN_SEAT|-1"})
	room.call("_flush_world", WORLD_INTERVAL * 0.4)
	eq(int(room.get("_world_tick")), tick_before + 1, "the rate limit holds")
	room.call("_flush_world", WORLD_INTERVAL)
	eq(int(room.get("_world_tick")), tick_before + 2, "and the change still lands")
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
