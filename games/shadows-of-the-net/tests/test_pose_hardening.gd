extends "res://tests/test_case.gd"

# REGRESSION — peer-supplied pose values must never reach a Node3D transform
# unvalidated, in EITHER transport.
#
# The shipped session-mode relay used to do
#     _avatars.set_pose(cid, parts[1].to_float(), parts[2].to_float(), …)
# with no finiteness or bounds check, while matrix mode guarded the identical
# payload (chat3d_bridge._arg_f). `String.to_float()` is not a validator:
# "1e400" parses to +INF silently. One forged SOTN_POSE from ANY member of the
# session then made every OTHER participant's renderer assert
#   `Condition "!v.is_finite()" is true. at: instance_set_transform`
# once per frame for the rest of the session, and permanently NaN'd the lerp
# target (lerp(INF, finite) == NaN) so the victim never recovered even from
# later legitimate poses. The games shim republishes each console error to the
# embedding page, so the host document floods too.

const ROOM_RADIUS := 8.6


func suite_name() -> String:
	return "pose hardening (session + matrix + choke point)"


## See test_case.plan() — pinned so an aborted suite cannot report green.
func plan() -> int:
	return 15


func run() -> void:
	await _to_float_really_yields_inf()
	await _session_relay_drops_non_finite_pose()
	await _session_relay_clamps_absurd_finite_pose()
	await _choke_point_rejects_non_finite()
	await _legit_pose_still_applies()
	await _avatar_survives_a_forged_pose_and_stays_finite()


## The premise of the whole guard: Godot's own string→float never signals.
func _to_float_really_yields_inf() -> void:
	ok(not is_finite("1e400".to_float()), "\"1e400\".to_float() is non-finite (INF)")
	ok(is_finite("nan".to_float()), "\"nan\".to_float() is a benign 0.0, not NaN")


func _session_relay_drops_non_finite_pose() -> void:
	var room := make_room()
	_as_session(room, "1")
	var avatars: Node3D = room.get_node("Avatars")

	# Victim exists first (roster/world already introduced them).
	avatars.call("ensure", "2")
	room.call("_on_movement",
			{"characterId": 2, "action": "SOTN_POSE|1e400|1e400|1e400|-1"})
	await tree.process_frame
	await tree.process_frame

	var p: Vector3 = avatars.call("position_of", "2")
	finite(p, "victim position after a forged INF pose")
	ok(p.is_equal_approx(Vector3.ZERO), "forged INF pose moved nobody")

	# NaN spelled a second way — a float literal that parses but is not finite.
	room.call("_on_movement",
			{"characterId": 2, "action": "SOTN_POSE|-1e400|0|0|-1"})
	await tree.process_frame
	finite(avatars.call("position_of", "2"), "victim position after a forged -INF pose")
	free_room(room)


func _session_relay_clamps_absurd_finite_pose() -> void:
	var room := make_room()
	_as_session(room, "1")
	var avatars: Node3D = room.get_node("Avatars")

	# 1e30 is FINITE, so a bare is_finite() check would let it through and park
	# the avatar light-years outside the chamber. local_controller._move_to
	# clamps only the SENDER's own client, so the receiver must clamp too.
	room.call("_on_movement", {"characterId": 3, "action": "SOTN_POSE|1e30|1e30|0|-1"})
	await tree.process_frame
	await tree.process_frame

	var p: Vector3 = avatars.call("position_of", "3")
	finite(p, "position after a huge-but-finite pose")
	ok(Vector2(p.x, p.z).length() <= ROOM_RADIUS + 0.001,
			"huge finite pose clamped inside the chamber (r=%f)" % Vector2(p.x, p.z).length())
	free_room(room)


## Every pose source funnels through AvatarManager.set_pose — the guard lives
## there, not only in the session parser, so the matrix relay and the
## authoritative world echo are covered by the same check.
func _choke_point_rejects_non_finite() -> void:
	var room := make_room()
	var avatars: Node3D = room.get_node("Avatars")
	avatars.call("ensure", "peer")
	avatars.call("set_pose", "peer", 2.0, 2.0, 0.0, -1)
	await tree.process_frame

	var before: Vector3 = avatars.call("position_of", "peer")
	avatars.call("set_pose", "peer", INF, 0.0, 0.0, -1)
	avatars.call("set_pose", "peer", 0.0, NAN, 0.0, -1)
	avatars.call("set_pose", "peer", 0.0, 0.0, INF, -1)
	await tree.process_frame
	await tree.process_frame

	var after: Vector3 = avatars.call("position_of", "peer")
	finite(after, "position after INF/NaN set_pose calls")
	ok(after.is_equal_approx(before) or after.distance_to(before) < 0.6,
			"non-finite set_pose did not retarget the avatar")

	# The matrix-mode entry point must be equally safe.
	room.call("_on_pose", "peer", INF, INF, INF, -1)
	await tree.process_frame
	finite(avatars.call("position_of", "peer"), "position after a matrix-mode INF pose")
	free_room(room)


func _legit_pose_still_applies() -> void:
	var room := make_room()
	_as_session(room, "1")
	var avatars: Node3D = room.get_node("Avatars")
	room.call("_on_movement", {"characterId": 4, "action": "SOTN_POSE|3.00|-2.00|1.20|-1"})
	# Lerp needs many frames to converge; assert the TARGET, which is exact.
	await tree.process_frame
	var state: Dictionary = avatars.get("_avatars")
	ok(state.has("4"), "a legitimate pose creates/updates the peer's avatar")
	if not state.has("4"):
		free_room(room)
		return
	var target: Vector3 = state["4"]["target_pos"]
	almost(target.x, 3.0, "legit pose x reached the avatar target")
	almost(target.z, -2.0, "legit pose z reached the avatar target")
	almost(float(state["4"]["target_yaw"]), 1.2, "legit yaw applied")
	free_room(room)


## A forged pose must not even be able to conjure a new avatar into the room —
## the action is dropped whole, before ensure().
func _avatar_survives_a_forged_pose_and_stays_finite() -> void:
	var room := make_room()
	_as_session(room, "1")
	var avatars: Node3D = room.get_node("Avatars")
	room.call("_on_movement", {"characterId": 99, "action": "SOTN_POSE|1e400|0|0|-1"})
	await tree.process_frame
	not_ok(bool(avatars.call("has_avatar", "99")), "forged INF pose created no avatar")
	free_room(room)


func _as_session(room: Node3D, local_key: String) -> void:
	room.set("mode", 0)              # Mode.SESSION
	room.set("_local_key", local_key)
	(room.get_node("Avatars") as Node3D).set("local_key", local_key)
