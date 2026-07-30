extends "res://tests/test_case.gd"

# REGRESSION — the §5 `sticker(userId, b64, mime)` byte channel (host → game).
#
# A member who chose "appear as my flat VN sticker" publishes an `mxc://` and
# nothing else. Matrix media is authenticated and lives on the HOST's origin;
# this wasm bundle runs on the games origin with no access token, so it can
# never dereference one. The room therefore drew billboard members as a blank
# plate with a nametag on it and said so, in avatar_manager._apply_meta's
# docstring, "until the host ships the sticker BYTES". This channel is that,
# and these are the rules it is not allowed to break:
#
#   1. RECEIVING A STICKER *IS* THE BILLBOARD SIGNAL. `mode` does not travel on
#      this channel, so neither half has a mode field to keep in sync with the
#      other; an empty payload means "clear the plate, give them their rig".
#   2. IT NEVER CONJURES A PARTICIPANT. Every other mutator on the manager
#      ensure()s a rig for an unknown key, which is right for channels the host
#      orders after the roster row. A sticker is order-independent by contract
#      — it may arrive before OR after that row — so ensure()ing from one would
#      let ARRIVAL ORDER decide who is in the room, and a sticker for somebody
#      who has left would resurrect them. Unknown key ⇒ dropped, and NOT
#      buffered for replay: the host re-sends per (userId, mxc) when they
#      reappear, and a buffer would be a second, hidden roster.
#   3. THE CONTAINER ALLOW-LIST AND THE BYTE CAP ARE ENFORCED ON BOTH SIDES.
#      The host refuses to send; this side refuses to accept. "The other one
#      bounds it" is precisely the assumption that let MAX_MEDIA_BYTES sit for
#      a whole release on a code path the real transport never entered.
#   4. A REFUSAL IS NOT A CLEAR. Empty is the clear signal, so degrading an
#      oversized / wrong-typed / undecodable payload to empty would let a bad
#      sticker erase the plate a member is already wearing. Refusals leave the
#      participant exactly as they were.
#
# The JS half of this channel (the raw-argument cap inside the injected shim's
# push(), which runs before normArg can turn an over-cap ArrayBuffer into the
# null that MEANS "clear") is a browser-only code path a headless Godot run can
# never execute; it is covered by games/_shared/test/chat3d-envelope.test.mjs.

const CHAT3D := preload("res://scripts/chat3d_bridge.gd")
const AvatarManager := preload("res://scripts/avatar_manager.gd")


func suite_name() -> String:
	return "chat3d sticker bytes (billboard plate channel)"


## See test_case.plan() — pinned so an aborted suite cannot report green.
func plan() -> int:
	return 67


func run() -> void:
	await _a_sticker_paints_the_plate_and_hides_the_rig()
	await _all_three_allow_listed_containers_decode()
	await _a_sticker_never_conjures_a_participant()
	await _a_sticker_before_the_roster_row_is_dropped_not_buffered()
	await _a_disallowed_mime_is_refused()
	await _an_oversized_payload_is_refused()
	await _undecodable_bytes_keep_the_current_look()
	await _an_empty_payload_restores_the_stand_in_rig()


# ── the happy path (the control the refusals below are measured against) ─────

func _a_sticker_paints_the_plate_and_hides_the_rig() -> void:
	var room := make_room()
	var avatars: Node3D = room.get_node("Avatars")
	var bridge: Node = room.get_node("Chat3DBridge")
	room.call("_setup_matrix")
	room.call("_on_roster", [{"userId": "@vn:example.org", "name": "Sticker Sam"}])

	var entry: Dictionary = avatars.get("_avatars")["@vn:example.org"]
	not_ok((entry["billboard"] as MeshInstance3D).visible,
			"a fresh participant starts on their 3D rig, not a plate")
	not_ok(bool(avatars.call("has_sticker_image", "@vn:example.org")),
			"…and carries no sticker image yet")

	bridge.call("_dispatch", "sticker",
			["@vn:example.org", Marshalls.raw_to_base64(_png()), "image/png"])

	ok(bool(avatars.call("has_sticker_image", "@vn:example.org")),
			"the bytes land on the participant's plate")
	ok((entry["billboard"] as MeshInstance3D).visible,
			"receiving a sticker IS the billboard signal — the plate is shown")
	not_ok((entry["rig"] as Node3D).visible, "…and the 3D rig is hidden behind it")
	var mat := _plate_material(entry)
	ok(mat.albedo_texture != null, "the plate carries a real decoded texture")
	eq(mat.albedo_color, Color.WHITE,
			"…unmodulated, so the sticker's own colours are what render")

	# `mode` deliberately does not travel on this channel: the plate is up
	# without the host ever having said the word "billboard".
	eq(str(avatars.call("mode_of", "@vn:example.org")), "",
			"no `mode` slot was needed to put the sticker up")
	eq(str(avatars.call("sticker_of", "@vn:example.org")), "",
			"…and the mxc slot is untouched — bytes and mxc are different channels")
	free_room(room)


## Exactly the three containers the room's decoders can read, which is exactly
## the allow-list both halves match against — so the list can never admit a
## format `Image.load_*_from_buffer` would then refuse.
func _all_three_allow_listed_containers_decode() -> void:
	eq(Array(CHAT3D.STICKER_MIME_TYPES), ["image/png", "image/jpeg", "image/webp"],
			"the sticker allow-list is matrix-ui's STICKER_MIME_TYPES verbatim")

	var room := make_room()
	var avatars: Node3D = room.get_node("Avatars")
	var bridge: Node = room.get_node("Chat3DBridge")
	room.call("_setup_matrix")
	var src := Image.create_empty(8, 8, false, Image.FORMAT_RGB8)
	src.fill(Color(0.2, 0.8, 0.4))

	var cases := [
		["@p:example.org", src.save_png_to_buffer(), "image/png"],
		["@j:example.org", src.save_jpg_to_buffer(0.9), "image/jpeg"],
		["@w:example.org", src.save_webp_to_buffer(false), "image/webp"],
		# A real Content-Type routinely carries parameters and arbitrary case;
		# both halves reduce it to the bare media type before matching.
		["@x:example.org", src.save_png_to_buffer(), "  IMAGE/PNG ; charset=binary "],
	]
	for case in cases:
		room.call("_on_roster", [{"userId": case[0], "name": ""}])
		bridge.call("_dispatch", "sticker",
				[case[0], Marshalls.raw_to_base64(case[1]), case[2]])
		ok(bool(avatars.call("has_sticker_image", case[0])),
				"a '%s' sticker decodes onto the plate" % case[2])
	free_room(room)


# ── rule 2: never conjure a participant ──────────────────────────────────────

func _a_sticker_never_conjures_a_participant() -> void:
	var room := make_room()
	var avatars: Node3D = room.get_node("Avatars")
	var bridge: Node = room.get_node("Chat3DBridge")
	room.call("_setup_matrix")
	var seen: Array = []
	bridge.connect("sticker_received",
			func(uid: String, bytes: PackedByteArray): seen.append([uid, bytes.size()]))

	room.call("_on_roster", [{"userId": "@here:example.org", "name": "Here"}])
	var before: int = int(avatars.call("keys").size())

	var png_b64 := Marshalls.raw_to_base64(_png())
	bridge.call("_dispatch", "sticker", ["@ghost:example.org", png_b64, "image/png"])
	not_ok(bool(avatars.call("has_avatar", "@ghost:example.org")),
			"a sticker for an unknown member creates NO avatar")
	eq(int(avatars.call("keys").size()), before,
			"…and the room's population is unchanged")
	# The DROP IS AT THE MANAGER, not at the bridge: the envelope was well
	# formed, was accepted, and reached the choke point that owns the rule. If
	# the bridge had silently swallowed it instead, the assertion above would
	# still pass while set_sticker's guard rotted untested.
	eq(seen.size(), 1, "the well-formed envelope was accepted and dispatched")
	eq(str(seen[0][0]), "@ghost:example.org", "…for the id the host addressed")

	# A member who LEFT is the same case and by far the more dangerous one — the
	# plate would have to RESURRECT a pruned participant in order to render. It
	# has to be tested on somebody the room really did have: nothing on this
	# channel announces a departure, the host simply stops talking about them,
	# so the drop is the only thing standing between a stale in-flight envelope
	# and a ghost.
	room.call("_on_roster", [{"userId": "@here:example.org", "name": "Here"},
			{"userId": "@gone:example.org", "name": "Gone"}])
	bridge.call("_dispatch", "sticker", ["@gone:example.org", png_b64, "image/png"])
	ok(bool(avatars.call("has_sticker_image", "@gone:example.org")),
			"premise: a real member is really wearing a plate")

	room.call("_on_roster", [{"userId": "@here:example.org", "name": "Here"}])
	not_ok(bool(avatars.call("has_avatar", "@gone:example.org")),
			"premise: the next roster pruned them out of the room")
	bridge.call("_dispatch", "sticker", ["@gone:example.org", png_b64, "image/png"])
	not_ok(bool(avatars.call("has_avatar", "@gone:example.org")),
			"a sticker for a departed member does not resurrect them")
	eq(int(avatars.call("keys").size()), before,
			"…and the room is back to exactly its live population")

	# An empty user id is not an anonymous participant either. Asserted against
	# the running total rather than a literal, so adding a case above cannot
	# silently turn this into a different claim.
	var accepted := seen.size()
	bridge.call("_dispatch", "sticker", ["", png_b64, "image/png"])
	eq(seen.size(), accepted, "an empty userId is refused at the bridge, before dispatch")
	eq(int(avatars.call("keys").size()), before, "…and conjures nothing")

	# Calling the mutator DIRECTLY must be just as inert — the rule lives at the
	# choke point next to the dictionary it would have to write into, not in the
	# room controller's forwarder, so no future caller can route around it.
	avatars.call("set_sticker", "@direct:example.org", _png())
	not_ok(bool(avatars.call("has_avatar", "@direct:example.org")),
			"set_sticker() itself refuses to ensure() an unknown key")
	free_room(room)


func _a_sticker_before_the_roster_row_is_dropped_not_buffered() -> void:
	var room := make_room()
	var avatars: Node3D = room.get_node("Avatars")
	var bridge: Node = room.get_node("Chat3DBridge")
	room.call("_setup_matrix")
	var png_b64 := Marshalls.raw_to_base64(_png())

	# ORDER-INDEPENDENT means "either order is legal", not "the early one is
	# held". The host diffs per (userId, mxc) and re-sends when the member
	# reappears, so the recovery path is a re-send — a queue here would be a
	# second roster nobody prunes, and it would paint a plate onto whoever next
	# claimed that mxid.
	bridge.call("_dispatch", "sticker", ["@late:example.org", png_b64, "image/png"])
	not_ok(bool(avatars.call("has_avatar", "@late:example.org")),
			"a sticker arriving BEFORE the roster row creates nobody")

	room.call("_on_roster", [{"userId": "@late:example.org", "name": "Late"}])
	ok(bool(avatars.call("has_avatar", "@late:example.org")),
			"the roster row then creates them normally")
	not_ok(bool(avatars.call("has_sticker_image", "@late:example.org")),
			"…with NO replayed plate — the early sticker was dropped, not buffered")
	var entry: Dictionary = avatars.get("_avatars")["@late:example.org"]
	not_ok((entry["billboard"] as MeshInstance3D).visible,
			"…so they stand as their deterministic stand-in rig")
	ok((entry["rig"] as Node3D).visible, "…which is visible")

	bridge.call("_dispatch", "sticker", ["@late:example.org", png_b64, "image/png"])
	ok(bool(avatars.call("has_sticker_image", "@late:example.org")),
			"the host's re-send is what puts the plate up — the documented recovery")
	free_room(room)


# ── rule 3 + 4: refusals, and refusals that are not clears ───────────────────

func _a_disallowed_mime_is_refused() -> void:
	var room := make_room()
	var avatars: Node3D = room.get_node("Avatars")
	var bridge: Node = room.get_node("Chat3DBridge")
	room.call("_setup_matrix")
	room.call("_on_roster", [{"userId": "@vn:example.org", "name": "Sam"}])
	var seen: Array = []
	bridge.connect("sticker_received",
			func(uid: String, bytes: PackedByteArray): seen.append([uid, bytes.size()]))

	# A plate that is already up, so a refusal has something to wrongly erase.
	var png_b64 := Marshalls.raw_to_base64(_png())
	bridge.call("_dispatch", "sticker", ["@vn:example.org", png_b64, "image/png"])
	ok(bool(avatars.call("has_sticker_image", "@vn:example.org")), "premise: a plate is up")
	var accepted := seen.size()

	# The BYTES are a perfectly good PNG in every case below — only the declared
	# container is wrong. We refuse on the declaration and deliberately do NOT
	# fall back to sniffing: the sniffer in media_surfaces only REORDERS the
	# admitted formats, and a guess that disagrees with the server is how a
	# channel starts carrying whatever happens to decode.
	# "" is the absent/unparseable header. It is fail-closed on purpose: an empty
	# string matches no allow-list, which is the honest reading of "we could not
	# tell what this is". "image/pngX" is the prefix-match trap; "image" the
	# bare-type one.
	for bad_mime in ["image/gif", "image/svg+xml", "text/html",
			"application/octet-stream", "", "image/pngX", "image"]:
		bridge.call("_dispatch", "sticker", ["@vn:example.org", png_b64, bad_mime])
		eq(seen.size(), accepted,
				"a '%s' sticker is refused at the bridge" % bad_mime)
		ok(bool(avatars.call("has_sticker_image", "@vn:example.org")),
				"…and the plate the member is already wearing survives it")
	free_room(room)


func _an_oversized_payload_is_refused() -> void:
	var room := make_room()
	var avatars: Node3D = room.get_node("Avatars")
	var bridge: Node = room.get_node("Chat3DBridge")
	room.call("_setup_matrix")
	room.call("_on_roster", [{"userId": "@vn:example.org", "name": "Sam"}])
	var seen: Array = []
	bridge.connect("sticker_received",
			func(uid: String, bytes: PackedByteArray): seen.append([uid, bytes.size()]))

	bridge.call("_dispatch", "sticker",
			["@vn:example.org", Marshalls.raw_to_base64(_png()), "image/png"])
	eq(seen.size(), 1, "premise: an ordinary sticker is delivered")
	ok(bool(avatars.call("has_sticker_image", "@vn:example.org")), "premise: a plate is up")

	var cap: int = CHAT3D.MAX_MEDIA_BYTES
	eq(cap, 4 * 1024 * 1024, "the cap is the same 4 MiB number matrix-ui refuses to send")

	# DROPPED WHOLE, unlike placeMedia — which degrades to its metadata plaque.
	# A sticker has no plaque to fall back to: the only other value this channel
	# carries is "empty", and empty MEANS "clear this member's plate", so
	# degrading would let an over-cap payload erase what they are wearing.
	bridge.call("_dispatch", "sticker",
			["@vn:example.org", Marshalls.raw_to_base64(_filler(cap + 1024)), "image/png"])
	eq(seen.size(), 1, "an oversized sticker is dropped, not delivered")
	ok(bool(avatars.call("has_sticker_image", "@vn:example.org")),
			"…and is NOT degraded to a clear — the member keeps their plate")

	# Off-by-one guard on the accepting side: exactly at the cap still crosses
	# the bridge (it is not a decodable image, so it stops at the decoder, which
	# is the next guard down and a different one).
	bridge.call("_dispatch", "sticker",
			["@vn:example.org", Marshalls.raw_to_base64(_filler(cap)), "image/png"])
	eq(seen.size(), 2, "a payload exactly AT the cap is still accepted")
	eq(int(seen[1][1]), cap, "…intact, at its full length")
	free_room(room)


func _undecodable_bytes_keep_the_current_look() -> void:
	var room := make_room()
	var avatars: Node3D = room.get_node("Avatars")
	var bridge: Node = room.get_node("Chat3DBridge")
	room.call("_setup_matrix")
	room.call("_on_roster", [{"userId": "@vn:example.org", "name": "Sam"}])

	bridge.call("_dispatch", "sticker",
			["@vn:example.org", Marshalls.raw_to_base64(_png()), "image/png"])
	var entry: Dictionary = avatars.get("_avatars")["@vn:example.org"]
	var was: Texture2D = _plate_material(entry).albedo_texture
	ok(was != null, "premise: a decoded sticker is on the plate")

	# Truthfully declared `image/png`, but the bytes are not a PNG (a truncated
	# upload, a re-encoded thumbnail, a server that mislabels). Every decoder
	# refuses. Flipping the member to a blank slab would replace a character
	# somebody is looking at with a worse answer than "the new sticker did not
	# arrive".
	bridge.call("_dispatch", "sticker",
			["@vn:example.org", Marshalls.raw_to_base64(_filler(64)), "image/png"])
	ok(bool(avatars.call("has_sticker_image", "@vn:example.org")),
			"undecodable bytes do not clear the plate")
	eq(_plate_material(entry).albedo_texture, was,
			"…the member keeps the exact texture they were wearing")
	ok((entry["billboard"] as MeshInstance3D).visible, "…and stays a billboard")
	free_room(room)


# ── rule 1: an empty payload is the clear ────────────────────────────────────

func _an_empty_payload_restores_the_stand_in_rig() -> void:
	var room := make_room()
	var avatars: Node3D = room.get_node("Avatars")
	var bridge: Node = room.get_node("Chat3DBridge")
	room.call("_setup_matrix")
	room.call("_on_roster", [{"userId": "@vn:example.org", "name": "Sam"}])
	var seen: Array = []
	bridge.connect("sticker_received",
			func(uid: String, bytes: PackedByteArray): seen.append([uid, bytes.size()]))

	bridge.call("_dispatch", "sticker",
			["@vn:example.org", Marshalls.raw_to_base64(_png()), "image/png"])
	var entry: Dictionary = avatars.get("_avatars")["@vn:example.org"]
	ok((entry["billboard"] as MeshInstance3D).visible, "premise: the plate is up")

	# NO MIME ON A CLEAR. The host has no Content-Type to declare for nothing,
	# and demanding one would make "stop showing this picture" undeliverable.
	bridge.call("_dispatch", "sticker", ["@vn:example.org", null, null])
	eq(seen.size(), 2, "a null payload is delivered without any declared type")
	eq(int(seen[1][1]), 0, "…as zero bytes, which is the clear signal")

	not_ok(bool(avatars.call("has_sticker_image", "@vn:example.org")),
			"the plate no longer carries an image")
	not_ok((entry["billboard"] as MeshInstance3D).visible, "…the plate itself is hidden")
	ok((entry["rig"] as Node3D).visible,
			"…and the member is back on their deterministic stand-in rig")
	var mat := _plate_material(entry)
	eq(mat.albedo_texture, null, "the texture is released, not merely hidden")
	eq(mat.albedo_color, AvatarManager.BILLBOARD_BLANK,
			"…and the plate is back to blank for the next sticker")

	# A clear for somebody who never had a plate is inert, not a crash and not
	# a stealth ensure() — the host sends one only for a member it put a plate
	# up for, but the receiving rule must not depend on that.
	bridge.call("_dispatch", "sticker", ["@nobody:example.org", null, null])
	not_ok(bool(avatars.call("has_avatar", "@nobody:example.org")),
			"a clear for an unknown member conjures nobody")

	# And the plate comes back on the next real payload.
	bridge.call("_dispatch", "sticker",
			["@vn:example.org", Marshalls.raw_to_base64(_png()), "image/png"])
	ok(bool(avatars.call("has_sticker_image", "@vn:example.org")),
			"a cleared member can be re-stickered")
	free_room(room)


# ── fixtures ─────────────────────────────────────────────────────────────────

## A real, decodable PNG — encoded by the engine rather than hand-rolled, so
## the test exercises the same decoders production does.
static func _png() -> PackedByteArray:
	var img := Image.create_empty(8, 8, false, Image.FORMAT_RGB8)
	img.fill(Color(0.8, 0.3, 0.9))
	return img.save_png_to_buffer()


static func _filler(n: int) -> PackedByteArray:
	var b := PackedByteArray()
	b.resize(n)
	b.fill(0x41)
	return b


func _plate_material(entry: Dictionary) -> StandardMaterial3D:
	return (entry["billboard"] as MeshInstance3D).material_override as StandardMaterial3D
