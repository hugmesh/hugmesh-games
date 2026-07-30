extends "res://tests/test_case.gd"

# REGRESSION — session-mode avatar leak: nothing pruned departed characterIds.
#
# Matrix mode has always reconciled: the host page pushes `roster(json)` and
# `_on_roster` finishes with `_avatars.prune(keys)`. Session mode had no
# counterpart. An avatar was created by `ensure()` the first time a characterId
# showed up on the movement channel and then lived forever, because NOTHING the
# iframe receives announces a departure:
#
#   * the host shell forwards six SSE channels (ApiBridgeService.SSE_CHANNELS =
#     movement / world / character / time / scene / session-control);
#   * `session-control` carries only SESSION_PAUSED|RESUMED, PLAYER_PAUSED|
#     RESUMED (a tab takeover) and SESSION_CLOSED (the whole room dying);
#   * `GameSessionService.leave` and `ModerationService.kickPlayer` stamp
#     `session_members.left_at` and emit no per-member event at all;
#   * the typed `WorldState` that shares the `world` event name is a per-CHAPTER
#     roster assembled from a pod-lifetime map with no eviction on leave — it
#     only ever grows, and it pools characters from every session in the
#     chapter, so pruning against it would be worse than not pruning.
#
# So `_avatars` climbed toward MAX_AVATARS on ghosts across a long-lived room
# and then REFUSED real newcomers — `upsert()` legitimately declines past the
# cap, which is the documented degrade path from test_avatar_lifecycle.
#
# The fix reads the one authoritative answer over the SD_API_REQUEST channel the
# room already uses: `GET /api/sessions/{id}` → `members` is
# `session_members WHERE left_at IS NULL`.


func suite_name() -> String:
	return "session membership (departed characterIds are pruned)"


## See test_case.plan() — pinned so an aborted suite cannot report green.
func plan() -> int:
	return 41


func run() -> void:
	await _matrix_mode_already_prunes()
	await _member_churn_used_to_exhaust_the_avatar_cap()
	await _roster_prune_never_drops_the_local_avatar()
	await _an_untrustworthy_roster_answer_does_not_empty_the_room()
	await _the_host_frees_a_departed_members_seat_stage_and_surface()
	await _the_membership_read_is_wired_and_parsed()
	await _a_failed_membership_read_is_not_a_stale_controller_claim()


## The premise the session-mode fix is measured against: matrix mode really
## does reconcile off the host bridge's roster. (test_avatar_lifecycle pins the
## node-freeing half; this pins that _on_roster is what drives it.)
func _matrix_mode_already_prunes() -> void:
	var room := make_room()
	var avatars: Node3D = room.get_node("Avatars")
	room.call("_on_roster", ["@a:example.org", "@b:example.org", "@c:example.org"])
	await tree.process_frame
	# Three, not four: the roster is the WHOLE truth in matrix mode, so it also
	# prunes the seed avatar the native-mode boot created. (The host page's
	# roster includes the local user, so this is not a leak in practice.)
	eq(int(avatars.call("keys").size()), 3, "the roster is the whole avatar set")

	room.call("_on_roster", ["@a:example.org"])
	await tree.process_frame
	await tree.process_frame
	ok(bool(avatars.call("has_avatar", "@a:example.org")), "matrix roster keeps the stayer")
	not_ok(bool(avatars.call("has_avatar", "@b:example.org")), "matrix roster prunes the leaver")
	free_room(room)


## THE BUG. A room that churns members: each newcomer poses once (which is what
## creates their rig) and then leaves. Without a prune the 17th arrival is
## refused because sixteen ghosts hold the slots — a real member cannot enter a
## room that has merely been busy.
func _member_churn_used_to_exhaust_the_avatar_cap() -> void:
	var room := _guest_room()
	var avatars: Node3D = room.get_node("Avatars")
	var cap := int((avatars.get_script() as GDScript)
			.get_script_constant_map().get("MAX_AVATARS", 16))

	# cap + 14 members arrive one at a time, pose, and depart. Every departure
	# is visible only in the next membership read.
	var churn := cap + 14
	for i in churn:
		var cid := 100 + i
		room.call("_on_movement",
				{"characterId": cid, "action": "SOTN_POSE|1.00|1.00|0.00"})
		# The BFF's answer while this member is in: us + them.
		room.call("_on_session_roster", [5, cid])
		await tree.process_frame
		# …and now they leave: the next read names only us.
		room.call("_on_session_roster", [5])
		await tree.process_frame

	eq(int(avatars.call("keys").size()), 1,
			"%d members churned through and left exactly one avatar behind" % churn)
	ok(bool(avatars.call("has_avatar", "5")), "the survivor is the local participant")

	# The point of the whole exercise: a genuinely NEW member is still admitted.
	room.call("_on_session_roster", [5, 777])
	room.call("_on_movement", {"characterId": 777, "action": "SOTN_POSE|2.00|0.00|0.00"})
	await tree.process_frame
	ok(bool(avatars.call("has_avatar", "777")),
			"a real newcomer is still admitted after heavy churn")
	free_room(room)


## Our own characterId may be missing from the membership rows for a beat
## (`session_members.character_id` is nullable between join and slot-claim), and
## the local avatar is the one the local rig and the XR visibility toggle key
## off. Pruning ourselves out of the room must be impossible.
func _roster_prune_never_drops_the_local_avatar() -> void:
	var room := _guest_room()
	var avatars: Node3D = room.get_node("Avatars")
	ok(bool(avatars.call("has_avatar", "5")), "the local avatar exists after setup")

	room.call("_on_session_roster", [11, 12])
	await tree.process_frame
	await tree.process_frame
	ok(bool(avatars.call("has_avatar", "5")),
			"a roster that omits our own characterId does not delete us")
	free_room(room)


## An empty `members` array cannot be true while we are reading it (we are a
## member), so it is a body we failed to parse — not an instruction to empty the
## chamber. Same for a body with no members key at all.
func _an_untrustworthy_roster_answer_does_not_empty_the_room() -> void:
	var room := _guest_room()
	var avatars: Node3D = room.get_node("Avatars")
	room.call("_on_movement", {"characterId": 21, "action": "SOTN_POSE|1.00|1.00|0.00"})
	room.call("_on_movement", {"characterId": 22, "action": "SOTN_POSE|1.00|1.00|0.00"})
	await tree.process_frame
	# Four: the two peers, our own "5", and the "local" avatar the harness's
	# NATIVE-mode boot seeded before `_setup_session()` re-keyed us (production
	# only ever takes one branch of the mode match, so it never has both).
	eq(int(avatars.call("keys").size()), 4, "two peers plus the local avatars")

	room.call("_on_session_roster", [])
	await tree.process_frame
	await tree.process_frame
	eq(int(avatars.call("keys").size()), 4, "an empty roster answer is ignored, not applied")

	# A REAL shrink still lands, so the guard is not "never prune".
	room.call("_on_session_roster", [5, 21])
	await tree.process_frame
	await tree.process_frame
	not_ok(bool(avatars.call("has_avatar", "22")), "a genuine departure is still pruned")
	free_room(room)


## Pruning avatars alone would be undone by our own next broadcast: `_on_world`
## re-`ensure`s every cid named in `seats` and in `stage`. And a ghost holding a
## seat makes the host refuse that anchor to everyone else for the life of the
## room (`_host_assign_seat` rejects a taken seat).
func _the_host_frees_a_departed_members_seat_stage_and_surface() -> void:
	var room := _host_room()
	var avatars: Node3D = room.get_node("Avatars")
	room.call("_host_assign_seat", 7, 3)
	room.call("_on_movement", {"characterId": 7, "action": "SOTN_MEDIA|table-2"})
	room.call("_host_set_stage", 7, true)
	room.call("_on_movement", {"characterId": 7, "action": "SOTN_POSE|1.00|1.00|0.00"})
	await tree.process_frame
	eq(int(room.get("_seat_by_cid").get(7, -1)), 3, "the member holds seat 3")
	eq(int(room.get("_placements").get("table-2", -1)), 7, "…and a media surface")
	eq(int(room.get("_stage_cid")), 7, "…and the stage")
	room.call("_flush_world", 1.0)                  # settle the pending broadcast
	var tick_before := int(room.get("_world_tick"))

	# Member 7 leaves. Only the membership read says so.
	room.call("_on_session_roster", [5])
	await tree.process_frame
	await tree.process_frame

	not_ok(bool(avatars.call("has_avatar", "7")), "the departed member's rig is freed")
	not_ok(room.get("_seat_by_cid").has(7), "the seat is released")
	eq(int(room.get("_seat_by_cid").size()), 0, "…and no other seat was harmed")
	not_ok(room.get("_placements").has("table-2"), "the media surface is released")
	eq(int(room.get("_stage_cid")), -1, "the stage grant is revoked")
	ok(bool(room.get("_world_dirty")), "the room owes an authoritative broadcast")
	room.call("_flush_world", 1.0)
	eq(int(room.get("_world_tick")), tick_before + 1, "…and emits exactly one")

	# The freed seat is genuinely claimable again — the point of releasing it.
	room.call("_host_assign_seat", 42, 3)
	eq(int(room.get("_seat_by_cid").get(42, -1)), 3, "the freed seat is re-claimable")

	# A roster that still names everyone changes nothing and broadcasts nothing.
	room.call("_flush_world", 1.0)
	var tick_settled := int(room.get("_world_tick"))
	room.call("_on_session_roster", [5, 42])
	not_ok(bool(room.get("_world_dirty")), "a no-op reconciliation owes no broadcast")
	room.call("_flush_world", 1.0)
	eq(int(room.get("_world_tick")), tick_settled, "…and emits none")
	free_room(room)


## The transport half: the room is actually connected to it, the request goes
## out on the existing SD_API_REQUEST channel, and a GameSessionDto body is
## turned into characterIds (nulls skipped — `characterId` is nullable, and
## folding a null to 0 would invent a phantom "character 0" in the roster).
func _the_membership_read_is_wired_and_parsed() -> void:
	var room := _guest_room()
	var bridge: Node = room.get_node("SessionBridge")
	ok(bridge.is_connected("roster_received", Callable(room, "_on_session_roster")),
			"the membership signal is wired to the prune handler")

	var parse := func(body: Variant) -> Array:
		return bridge.call("_parse_roster", body)

	eq(parse.call({"members": [{"characterId": 5}, {"characterId": 9}]}), [5, 9],
			"members are read as characterIds")
	eq(parse.call({"members": [{"characterId": null}, {"characterId": 4}]}), [4],
			"a member with no character yet is skipped, not folded to 0")
	eq(parse.call({"members": [{"characterId": 4}, {"characterId": 4}]}), [4],
			"duplicates collapse")
	eq(parse.call('{"members":[{"characterId":8}]}'), [8],
			"a body handed over as raw JSON text still parses")
	eq(parse.call({"members": "nope"}), [], "a non-array members field yields nothing")
	eq(parse.call({}), [], "a body with no members field yields nothing")
	eq(parse.call(null), [], "a null body yields nothing")

	# The launch-URL session id is the only caller-supplied part of any path
	# this bridge builds, and the host shell will run ANY path through its
	# authenticated HttpClient.
	ok(bool(bridge.call("_is_safe_session_id",
			"Rk9vQmFyQmF6UXV4MTIzNDU2Nzg5MC1fYWJjZGVm")), "a real session id passes")
	not_ok(bool(bridge.call("_is_safe_session_id",
			"../../api/admin/sessions")), "a path-traversing id is refused")
	not_ok(bool(bridge.call("_is_safe_session_id", "short")), "a too-short id is refused")
	free_room(room)


## The membership GET is a passive read. Routing its failures into the shared
## 403 bucket would post SD_SESSION_EXPIRED — and so re-run the host shell's
## connect/claimControl chain — every poll interval, forever.
func _a_failed_membership_read_is_not_a_stale_controller_claim() -> void:
	var room := _guest_room()
	var bridge: Node = room.get_node("SessionBridge")

	bridge.get("_pending_roster")[71] = true
	eq(str(bridge.call("_classify_response", {"requestId": 71, "status": 200})),
			"roster", "a 200 on the membership read is a roster answer")
	not_ok(bridge.get("_pending_roster").has(71), "a settled membership read is retired")

	bridge.get("_pending_roster")[72] = true
	eq(str(bridge.call("_classify_response", {"requestId": 72, "status": 403})),
			"", "a 403 on the membership read is NOT a stale controller claim")
	bridge.get("_pending_roster")[73] = true
	eq(str(bridge.call("_classify_response", {"requestId": 73, "status": 404})),
			"", "a 404 on the membership read is not a verdict either")

	# …and the pre-existing routing is untouched.
	bridge.get("_pending_world")[74] = true
	eq(str(bridge.call("_classify_response", {"requestId": 74, "status": 403})),
			"world-forbidden", "a 403 on a world POST is still an authority verdict")
	eq(str(bridge.call("_classify_response", {"requestId": 75, "status": 403})),
			"expired", "a 403 on a control POST still triggers the reconnect path")
	free_room(room)


## Boot through the real `_setup_session()` wiring — that is what connects the
## membership signal and runs the host election.
func _session_room(slot_index: int) -> Node3D:
	var room := make_room()
	room.set("mode", 0)              # Mode.SESSION
	var bridge: Node = room.get_node("SessionBridge")
	bridge.set("character_id", 5)
	bridge.set("slot_index", slot_index)
	room.call("_setup_session")
	return room


func _guest_room() -> Node3D:
	return _session_room(3)


func _host_room() -> Node3D:
	return _session_room(0)
