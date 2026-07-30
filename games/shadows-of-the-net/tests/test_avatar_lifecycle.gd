extends "res://tests/test_case.gd"

# REGRESSION — two structural defects in AvatarManager, both reachable on the
# ORDINARY happy path, both fatal to a documented DESIGN §5 channel.
#
# 1. NAMETAG / SPEAKING RING DESTROYED BY THE FIRST avatar() UPDATE.
#    `CharacterAssembler.assemble()` frees ALL of its own children on every
#    rebuild. The nametag and speaking ring used to be parented to the RIG, so
#    the documented roster()->avatar() order (room_controller._on_roster fires
#    before _on_avatar) destroyed both for every participant who publishes a
#    config — i.e. everyone with a character-forge or randomized avatar. The
#    next `speaking(userId,bool)` then died with
#      SCRIPT ERROR: Trying to cast a freed object. at: set_speaking
#    so nobody ever saw who was talking. Fix: rig / nametag / ring are SIBLINGS
#    under a per-participant root node.
#
# 2. CAP OVERFLOW = A HARD ERROR ON EVERY UPDATE.
#    `upsert()` legitimately refuses past MAX_AVATARS, but `ensure()` used to
#    return void and every mutator then indexed `_avatars[key]` regardless —
#    `Invalid access to property or key` on EVERY pose / speaking / openness /
#    expression / blendshape / stage update for the overflow peer, at 10-15 Hz
#    each, republished to the embedding page by the games shim's console hook.
#    Reachable from a Matrix roster above the clamp, or from session-mode
#    member churn (nothing prunes departed characterIds).

const CharacterAssembler := preload("res://scripts/character_assembler.gd")


func suite_name() -> String:
	return "avatar lifecycle (decorations survive re-assemble; cap is safe)"


## See test_case.plan() — pinned so an aborted suite cannot report green.
func plan() -> int:
	return 25


func run() -> void:
	await _decorations_survive_the_roster_then_avatar_sequence()
	await _speaking_channel_works_after_an_avatar_update()
	await _repeated_avatar_updates_do_not_leak_decorations()
	await _cap_overflow_is_a_no_op_not_an_error()
	await _prune_removes_the_whole_participant_node()


func _rig_of(avatars: Node3D, key: String) -> Node3D:
	return avatars.get("_avatars")[key]["rig"]


func _decorations_survive_the_roster_then_avatar_sequence() -> void:
	var room := make_room()
	var avatars: Node3D = room.get_node("Avatars")

	# The documented order: roster() creates the rig, avatar() re-assembles it.
	room.call("_on_roster", ["@bob:example.org"])
	room.call("_on_avatar", "@bob:example.org", CharacterAssembler.random_config(1234))
	# queue_free() is deferred — the damage only becomes observable next frame.
	await tree.process_frame
	await tree.process_frame

	var state: Dictionary = avatars.get("_avatars")
	ok(state.has("@bob:example.org"), "participant is tracked")
	if not state.has("@bob:example.org"):
		free_room(room)
		return
	var av: Dictionary = state["@bob:example.org"]
	ok(is_instance_valid(av["ring"]), "speaking ring survived assemble()")
	ok(is_instance_valid(av["label"]), "nametag survived assemble()")
	ok(is_instance_valid(av["rig"]), "rig survived assemble()")
	ok(is_instance_valid(av["root"]), "participant root survived assemble()")

	# …and they are SIBLINGS of the rig, not its children — the property that
	# makes them survive. Anything re-parented under the rig regresses this.
	var ring: Node = av["ring"]
	var label: Node = av["label"]
	var rig: Node = av["rig"]
	eq(ring.get_parent(), av["root"], "ring is parented to the participant root")
	eq(label.get_parent(), av["root"], "nametag is parented to the participant root")
	ne(ring.get_parent(), rig, "ring is NOT a child of the rig")
	ne(label.get_parent(), rig, "nametag is NOT a child of the rig")
	free_room(room)


func _speaking_channel_works_after_an_avatar_update() -> void:
	var room := make_room()
	var avatars: Node3D = room.get_node("Avatars")
	room.call("_on_roster", ["@bob:example.org"])
	room.call("_on_avatar", "@bob:example.org", CharacterAssembler.random_config(99))
	await tree.process_frame
	await tree.process_frame

	# DESIGN §5 `speaking(userId, bool)`.
	room.call("_on_speaking", "@bob:example.org", true)
	var ring: MeshInstance3D = avatars.get("_avatars")["@bob:example.org"]["ring"]
	ok(ring.visible, "speaking ring lights up after an avatar() update")
	room.call("_on_speaking", "@bob:example.org", false)
	not_ok(ring.visible, "speaking ring goes dark again")

	# The rest of the per-user channels must survive the rebuild too.
	room.call("_on_openness", "@bob:example.org", 0.7)
	room.call("_on_expression", "@bob:example.org", "happy")
	var weights := PackedByteArray()
	weights.resize(52)
	room.call("_on_blendshapes", "@bob:example.org", weights)
	await tree.process_frame
	ok(is_instance_valid(_rig_of(avatars, "@bob:example.org")),
			"rig still valid after openness/expression/blendshape updates")
	free_room(room)


func _repeated_avatar_updates_do_not_leak_decorations() -> void:
	var room := make_room()
	var avatars: Node3D = room.get_node("Avatars")
	room.call("_on_roster", ["@bob:example.org"])
	for i in 4:
		room.call("_on_avatar", "@bob:example.org", CharacterAssembler.random_config(i))
		await tree.process_frame
	await tree.process_frame

	var av: Dictionary = avatars.get("_avatars")["@bob:example.org"]
	var root_node: Node3D = av["root"]
	ok(is_instance_valid(av["ring"]), "ring survives four re-assembles")
	ok(is_instance_valid(av["label"]), "nametag survives four re-assembles")
	# Exactly one rig + one billboard plate + one nametag + one ring — no
	# duplicate decorations. (The billboard is the sibling that carries a
	# billboard-mode participant; it stays hidden for a 3D rig.)
	eq(root_node.get_child_count(), 4,
			"participant root holds exactly rig+billboard+nametag+ring")
	free_room(room)


## MAX_AVATARS is a real cap; exceeding it must DEGRADE (the 17th peer is not
## rendered), never throw. GDScript has no exceptions, so the regression shows
## up as `Invalid access to property or key` spam rather than a crash — this
## test pins the observable half: the room stays consistent and the tracked
## avatars are untouched by the refused updates.
func _cap_overflow_is_a_no_op_not_an_error() -> void:
	var room := make_room()
	var avatars: Node3D = room.get_node("Avatars")
	var cap := int((avatars.get_script() as GDScript)
			.get_script_constant_map().get("MAX_AVATARS", 16))
	# The scene boots in NATIVE mode and seeds a "local" avatar; drop it so the
	# cap arithmetic below is about the roster alone.
	avatars.call("remove", "local")

	var roster: Array = []
	for i in cap + 4:
		roster.append("@peer%02d:example.org" % i)
	room.call("_on_roster", roster)
	await tree.process_frame

	eq(int(avatars.call("keys").size()), cap, "avatar count is capped at MAX_AVATARS")
	var overflow := "@peer%02d:example.org" % (cap + 2)
	not_ok(bool(avatars.call("has_avatar", overflow)), "overflow peer is not tracked")

	# Every per-user channel for the overflow peer must be an inert no-op.
	not_ok(bool(avatars.call("ensure", overflow)), "ensure() reports refusal over the cap")
	avatars.call("set_pose", overflow, 1.0, 1.0, 0.0, -1)
	avatars.call("set_speaking", overflow, true)
	avatars.call("set_openness", overflow, 0.5)
	avatars.call("set_expression", overflow, "happy")
	var weights := PackedByteArray()
	weights.resize(52)
	avatars.call("set_blendshapes", overflow, weights)
	avatars.call("set_stage", overflow, true)
	await tree.process_frame

	eq(int(avatars.call("keys").size()), cap, "refused updates created no avatar")
	eq(int(avatars.call("seat_of", overflow)), -1, "overflow peer has no seat")
	not_ok(bool(avatars.call("is_on_stage", overflow)), "overflow peer is not on stage")
	# The tracked participants are unharmed.
	ok(bool(avatars.call("has_avatar", "@peer00:example.org")), "admitted peer still tracked")
	free_room(room)


func _prune_removes_the_whole_participant_node() -> void:
	var room := make_room()
	var avatars: Node3D = room.get_node("Avatars")
	room.call("_on_roster", ["@a:example.org", "@b:example.org"])
	await tree.process_frame
	var b_root: Node3D = avatars.get("_avatars")["@b:example.org"]["root"]

	room.call("_on_roster", ["@a:example.org"])
	await tree.process_frame
	await tree.process_frame

	not_ok(bool(avatars.call("has_avatar", "@b:example.org")), "pruned peer dropped")
	not_ok(is_instance_valid(b_root), "pruned peer's whole node tree was freed")
	ok(bool(avatars.call("has_avatar", "@a:example.org")), "remaining peer kept")
	free_room(room)
