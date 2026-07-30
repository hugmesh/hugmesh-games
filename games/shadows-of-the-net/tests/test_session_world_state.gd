extends "res://tests/test_case.gd"

# REGRESSION — session-mode host-authoritative world state.
#
# 1. MEDIA PLACEMENT WAS MATRIX-ONLY. DESIGN §5 specifies session mode as
#    "worldNative kind:'world' host-authoritative room state
#    (placements/stage grants)", but `place_media_received` / `clear_media_
#    received` were wired only inside `_setup_matrix()`, `_on_surface_activated`
#    returned early unless `mode == MATRIX`, and `_broadcast_world()` shipped
#    only {type, seats, stage, tick}. Migration 056-4 grants game_access
#    scope='all', so mode=session is what EVERY portal user gets — all eleven
#    media surfaces were permanently dead geometry on the only shipped launch
#    path. Fixed with a SOTN_MEDIA action + `placements` in the world payload.
#
# 2. THE HOST ELECTION AND THE BFF'S AUTHORITY GATE COULD DISAGREE.
#    The game elects its host from the client-supplied `slot_index == 0`; the
#    BFF grants kind:'world' on `SessionMember.isCreator()`. When they named
#    different members the room had NO host at all AND the false host looped
#    403 -> SD_SESSION_EXPIRED -> connect/claimControl forever. The BFF now
#    pins the creator to slot 0 (GameSessionService.CREATOR_SLOT); this pins
#    the client-side belt-and-braces half: a 403 on OUR world POST demotes us
#    instead of being mistaken for a stale controller claim.


func suite_name() -> String:
	return "session world state (media placements + host demotion)"


## See test_case.plan() — pinned so an aborted suite cannot report green.
func plan() -> int:
	return 30


func run() -> void:
	await _host_election_follows_slot_zero()
	await _host_click_places_media_in_session_mode()
	await _forged_surface_ids_are_refused()
	await _only_the_claimant_can_release_a_surface()
	await _guests_apply_the_authoritative_placement_map()
	await _world_403_demotes_the_host_instead_of_reconnecting()
	await _control_403_still_asks_for_a_reconnect()


func _label_text(room: Node3D, surface_id: String) -> String:
	var labels: Dictionary = (room.get_node("Surfaces") as Node3D).get("_labels")
	var label: Label3D = labels.get(surface_id)
	return label.text if label != null else "<missing>"


## The election itself, plus the wiring the demotion path depends on.
func _host_election_follows_slot_zero() -> void:
	var host := _host_room()
	ok(bool(host.get("_is_host")), "slot 0 elects the room host")
	var bridge: Node = host.get_node("SessionBridge")
	ok(bridge.is_connected("world_forbidden", Callable(host, "_on_world_forbidden")),
			"the BFF's authority verdict is wired to the demotion handler")
	ok(bridge.is_connected("movement_received", Callable(host, "_on_movement")),
			"movement channel is wired")
	ok(bridge.is_connected("world_received", Callable(host, "_on_world")),
			"world channel is wired")
	free_room(host)

	var guest := _guest_room()
	not_ok(bool(guest.get("_is_host")), "a non-zero slot is never the host")
	# A guest must not arbitrate: SEAT/STAGE/MEDIA requests are host-only.
	guest.call("_on_movement", {"characterId": 9, "action": "SOTN_SEAT|2"})
	guest.call("_on_movement", {"characterId": 9, "action": "SOTN_MEDIA|table-2"})
	guest.call("_on_movement", {"characterId": 9, "action": "SOTN_STAGE|1"})
	eq(int(guest.get("_seat_by_cid").size()), 0, "guest does not arbitrate seats")
	eq(int(guest.get("_placements").size()), 0, "guest does not arbitrate placements")
	eq(int(guest.get("_stage_cid")), -1, "guest does not arbitrate the stage")
	free_room(guest)


func _host_click_places_media_in_session_mode() -> void:
	var room := _host_room()

	# The full click path: local_controller -> surface_clicked -> activate() ->
	# surface_activated -> _on_surface_activated. In matrix mode this reached
	# Chat3D; in session mode it used to reach nothing at all.
	room.call("_on_local_surface_clicked", "table-1")
	await tree.process_frame

	var placements: Dictionary = room.get("_placements")
	eq(int(placements.get("table-1", -1)), 5, "host's own click claims the surface")
	room.call("_on_world", {"type": "sotn.room", "seats": {},
			"placements": {"table-1": 5}, "stage": -1, "tick": 2})
	await tree.process_frame
	ne(_label_text(room, "table-1"), "", "the surface actually renders a placement label")

	# Toggling the same surface again releases it.
	room.call("_on_local_surface_clicked", "table-1")
	await tree.process_frame
	not_ok(room.get("_placements").has("table-1"), "second click releases the surface")
	room.call("_on_world", {"type": "sotn.room", "seats": {},
			"placements": {}, "stage": -1, "tick": 3})
	await tree.process_frame
	eq(_label_text(room, "table-1"), "", "released surface is cleared")
	free_room(room)


## The surface id arrives over the movement channel, i.e. verbatim from a peer.
func _forged_surface_ids_are_refused() -> void:
	var room := _host_room()
	for forged in ["../../etc/passwd", "Table1", "table-7", "screen-0", "",
			"stage'; DROP", "table-1 "]:
		room.call("_on_movement", {"characterId": 9, "action": "SOTN_MEDIA|%s" % forged})
	await tree.process_frame
	eq(int(room.get("_placements").size()), 0,
			"no forged surface id produced a placement")

	# …and the allow-listed ones do work, so the guard is not just "deny all".
	room.call("_on_movement", {"characterId": 9, "action": "SOTN_MEDIA|screen-3"})
	eq(int(room.get("_placements").get("screen-3", -1)), 9,
			"an allow-listed surface id from a guest is honoured")

	# A hostile HOST cannot invent a surface either — the apply side filters too.
	room.call("_on_world", {"type": "sotn.room", "seats": {},
			"placements": {"table-99": 1, "screen-3": 9}, "stage": -1, "tick": 4})
	await tree.process_frame
	eq(int(room.get("_applied_placements").size()), 1,
			"unknown surface id in the authoritative map is dropped")
	free_room(room)


func _only_the_claimant_can_release_a_surface() -> void:
	var room := _host_room()
	room.call("_on_movement", {"characterId": 7, "action": "SOTN_MEDIA|screen-1"})
	eq(int(room.get("_placements").get("screen-1", -1)), 7, "first claimant owns the surface")

	room.call("_on_movement", {"characterId": 42, "action": "SOTN_MEDIA|screen-1"})
	eq(int(room.get("_placements").get("screen-1", -1)), 7,
			"a different member cannot clear someone else's placement")

	room.call("_on_movement", {"characterId": 7, "action": "SOTN_MEDIA|screen-1"})
	not_ok(room.get("_placements").has("screen-1"), "the claimant can release it")
	free_room(room)


func _guests_apply_the_authoritative_placement_map() -> void:
	var room := _guest_room()

	# A guest's own click must NOT place locally — it asks and waits for the echo.
	room.call("_on_local_surface_clicked", "screen-2")
	await tree.process_frame
	eq(_label_text(room, "screen-2"), "", "guest click does not self-place")

	room.call("_on_world", {"type": "sotn.room", "seats": {},
			"placements": {"screen-2": 11}, "stage": -1, "tick": 1})
	await tree.process_frame
	ne(_label_text(room, "screen-2"), "", "authoritative echo places the media")

	# Re-broadcasting the SAME map is a no-op diff, and dropping an entry clears.
	room.call("_on_world", {"type": "sotn.room", "seats": {},
			"placements": {"screen-2": 11}, "stage": -1, "tick": 2})
	await tree.process_frame
	ne(_label_text(room, "screen-2"), "", "idempotent re-broadcast keeps the placement")
	room.call("_on_world", {"type": "sotn.room", "seats": {},
			"placements": {}, "stage": -1, "tick": 3})
	await tree.process_frame
	eq(_label_text(room, "screen-2"), "", "removal from the map clears the surface")
	free_room(room)


func _world_403_demotes_the_host_instead_of_reconnecting() -> void:
	var room := _host_room()
	room.call("_host_assign_seat", 7, 1)
	ok(bool(room.get("_is_host")), "starts out believing it is the host")

	var bridge: Node = room.get_node("SessionBridge")
	# Mirror send_world()'s bookkeeping, then hand back the BFF's Gate 0b 403.
	bridge.get("_pending_world")[41] = true
	eq(str(bridge.call("_classify_response", {"requestId": 41, "status": 403})),
			"world-forbidden", "403 on a world POST is classified as an authority verdict")
	bridge.emit_signal("world_forbidden")
	await tree.process_frame

	not_ok(bool(room.get("_is_host")), "the room stands down as host")
	eq(int(room.get("_seat_by_cid").size()), 0, "stale host-only seat map is dropped")
	eq(int(room.get("_placements").size()), 0, "stale host-only placement map is dropped")
	free_room(room)


func _control_403_still_asks_for_a_reconnect() -> void:
	var room := _host_room()
	var bridge: Node = room.get_node("SessionBridge")
	# A 403 whose requestId is NOT an outstanding world POST is the documented
	# stale-controller-claim case that connect/claimControl really does fix.
	eq(str(bridge.call("_classify_response", {"requestId": 7, "status": 403})),
			"expired", "403 on a control POST still triggers the reconnect path")
	# Non-403s are never either verdict.
	bridge.get("_pending_world")[8] = true
	eq(str(bridge.call("_classify_response", {"requestId": 8, "status": 200})),
			"", "a successful world POST produces no verdict")
	not_ok(bridge.get("_pending_world").has(8), "a settled world POST is retired")
	free_room(room)


## Boot the room through the REAL session wiring: `_setup_session()` is what
## connects movement/world/world_forbidden and runs the host election, so the
## suite exercises those connections rather than re-implementing them.
## `slot_index` is the client-supplied launch param; the BFF pins slot 0 to the
## session creator, which is exactly the member Gate 0b lets post kind:'world'.
func _session_room(slot_index: int) -> Node3D:
	var room := make_room()
	room.set("mode", 0)              # Mode.SESSION
	var bridge: Node = room.get_node("SessionBridge")
	bridge.set("character_id", 5)
	bridge.set("slot_index", slot_index)
	room.call("_setup_session")
	return room


func _host_room() -> Node3D:
	return _session_room(0)


func _guest_room() -> Node3D:
	return _session_room(3)
