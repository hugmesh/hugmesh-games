extends "res://tests/test_case.gd"

# REGRESSION — the ten divergences a code-to-code convergence audit found
# between the two independently-fixed halves of the Chat3D bridge: this room
# (game half) and snapdragon-matrix-ui's chat3d/** (host half). The envelope,
# the `ready` handshake and 10 of 12 message types had converged; these had not.
#
# Every check below fails against the pre-convergence room. They are grouped by
# the audit's numbering so a finding can be traced to its pin:
#
#   1  extended coat-block regions accepted + derived + rendered
#   2  DEFAULT_MATERIALS pinned to Char3DConfigValidator's palette
#   3  placeMedia decodes JPEG/WebP, not PNG only
#   4  MAX_MEDIA_BYTES enforced on the base64 transport that actually ships
#   5  expression/stage stay wired (the agreed "host wires the producer" half)
#   6  nametags come from the host-supplied display name
#   7  bounded part-id / short-string patterns + display-name sanitisation
#   8  the server's hex range + the flat 0.5 snoutLength default
#   9  billboard-mode avatars are representable
#  10  origin AND source-window pinning (JS half — see the note at the bottom)
#
# THE AUTHORITY for 1/2/7/8 is snapdragon-character-forge-bff-api's
# Char3DConfigValidator; the literals below are copied from it, on purpose, so
# that a future "tidy-up" of the mirror has to disagree with the server in
# writing.

const CharacterAssembler := preload("res://scripts/character_assembler.gd")
const MediaSurfaces := preload("res://scripts/media_surfaces.gd")
const CHAT3D := preload("res://scripts/chat3d_bridge.gd")


func suite_name() -> String:
	return "chat3d convergence (host/game contract divergences)"


## See test_case.plan() — pinned so an aborted suite cannot report green.
func plan() -> int:
	return 102


func run() -> void:
	_default_materials_match_the_server()
	_extended_regions_are_accepted_and_derived()
	await _extended_regions_reach_the_rig()
	_hex_range_and_snout_default_match_the_server()
	_bounded_patterns_match_the_server()
	_display_names_are_sanitised()
	await _media_decodes_jpeg_and_webp()
	await _inbound_media_is_capped()
	await _nametags_use_the_supplied_display_name()
	await _billboard_avatars_are_representable()
	await _expression_and_stage_stay_wired()


# ── 2. DEFAULT_MATERIALS ─────────────────────────────────────────────────────
#
# Five copies of this palette exist (BFF validator, Godot maker, Angular editor,
# matrix-ui room mirror, here). Three of them disagreed on accent/detail/iris,
# so ONE character rendered in three different colour schemes depending on which
# surface drew it. The literals here are Char3DConfigValidator.DEFAULT_MATERIALS.

func _default_materials_match_the_server() -> void:
	var authority := {
		"fur": "#8b5a2b", "accent": "#d2b48c", "detail": "#4a3728",
		"line": "#2f2f2f", "iris": "#00bcd4", "skin": "#ffdbac", "hair": "#563d2d",
	}
	eq(CharacterAssembler.DEFAULT_MATERIALS, authority,
			"DEFAULT_MATERIALS is byte-identical to Char3DConfigValidator's")
	# An unset region resolves through the same table.
	var cfg: Dictionary = CharacterAssembler.parse_config({"head": {"family": "canid"}})
	eq(cfg["materials"]["iris"], "#00bcd4", "an unset region falls back to the canonical hex")


# ── 1. EXTENDED COAT-BLOCK REGIONS ───────────────────────────────────────────
#
# innerMuzzle / socks / innerEar / tailTip are OPTIONAL on the wire; ABSENT
# means "derive it". A mirror that does not know them must not reject the
# definition (matrix-ui hard-rejected a kemono character over exactly this) and
# must not invent its own derivation either.

func _extended_regions_are_accepted_and_derived() -> void:
	eq(Array(CharacterAssembler.EXTENDED_MATERIAL_REGIONS),
			["innerMuzzle", "socks", "innerEar", "tailTip"],
			"the four extended regions are declared, in the server's order")

	var kemono: Dictionary = CharacterAssembler.parse_config({
		"schemaVersion": 1, "head": {"family": "canid"}, "style": "toon",
		"materials": {
			"fur": "#101010", "accent": "#202020", "skin": "#303030",
			"innerMuzzle": "#aabbcc", "socks": "#ddeeff",
			"innerEar": "#123456", "tailTip": "#654321",
		},
	})
	# ACCEPTED, not rejected: the canonical regions still parsed.
	eq(kemono["materials"]["fur"], "#101010",
			"a definition carrying extended regions is not rejected wholesale")
	for region in ["innerMuzzle", "socks", "innerEar", "tailTip"]:
		ok(kemono["materials"].has(region), "supplied %s survives the parse" % region)
	eq(kemono["materials"]["innerEar"], "#123456", "a supplied extended hex is kept verbatim")

	# ABSENT means DERIVE — never store a guessed value (that would freeze this
	# room's derivation into a config that round-trips back to the BFF).
	var plain: Dictionary = CharacterAssembler.parse_config({
		"head": {"family": "canid"},
		"materials": {"fur": "#804020", "accent": "#204080", "skin": "#408020"},
	})
	for region in ["innerMuzzle", "socks", "innerEar", "tailTip"]:
		not_ok(plain["materials"].has(region),
				"an absent %s stays absent (derive, don't freeze)" % region)
	eq(CharacterAssembler.region_color(plain, "socks"),
			Color.from_string("#204080", Color.BLACK), "socks derives from accent")
	eq(CharacterAssembler.region_color(plain, "tailTip"),
			Color.from_string("#204080", Color.BLACK), "tailTip derives from accent")
	eq(CharacterAssembler.region_color(plain, "innerEar"),
			Color.from_string("#408020", Color.BLACK), "innerEar derives from skin")
	eq(CharacterAssembler.region_color(plain, "innerMuzzle"),
			Color.from_string("#804020", Color.BLACK).lightened(
					CharacterAssembler.INNER_MUZZLE_LIGHTEN),
			"innerMuzzle derives from fur, lightened")
	# An explicit value beats the derivation.
	eq(CharacterAssembler.region_color(kemono, "socks"),
			Color.from_string("#ddeeff", Color.BLACK), "an explicit extended hex wins")


## The regions are not just parsed — the rig paints geometry with them.
func _extended_regions_reach_the_rig() -> void:
	var rig: Node3D = CharacterAssembler.new()
	tree.root.add_child(rig)
	rig.call("assemble", {
		"schemaVersion": 1, "style": "toon",
		"head": {"family": "canid"},
		"parts": {"tail": "plain"},
		"materials": {
			"fur": "#804020", "accent": "#204080", "skin": "#408020",
			"innerEar": "#00ff00", "tailTip": "#ff00ff", "innerMuzzle": "#ffff00",
		},
	})
	var mats: Dictionary = rig.get("_materials")
	eq(mats.size(), CharacterAssembler.ALL_MATERIAL_REGIONS.size(),
			"the rig builds a material for every canonical AND extended region")

	var jaw_mesh := rig.find_child("JawMesh", true, false) as MeshInstance3D
	ok(jaw_mesh != null, "the rig has a jaw mesh")
	if jaw_mesh != null:
		eq((jaw_mesh.material_override as StandardMaterial3D).albedo_color,
				Color.from_string("#ffff00", Color.BLACK),
				"the jaw wedge is painted with materials.innerMuzzle")
	var inner_ear := rig.find_child("InnerEar", true, false) as MeshInstance3D
	ok(inner_ear != null, "a canid's ears carry an inner-ear block")
	if inner_ear != null:
		eq((inner_ear.material_override as StandardMaterial3D).albedo_color,
				Color.from_string("#00ff00", Color.BLACK),
				"the inner-ear block is painted with materials.innerEar")
	var tail_tip := rig.find_child("TailTip", true, false) as MeshInstance3D
	ok(tail_tip != null, "a tailed character carries a tail tip")
	if tail_tip != null:
		eq((tail_tip.material_override as StandardMaterial3D).albedo_color,
				Color.from_string("#ff00ff", Color.BLACK),
				"the tail tip is painted with materials.tailTip")
	tree.root.remove_child(rig)
	rig.free()


# ── 8. HEX RANGE + snoutLength DEFAULT ───────────────────────────────────────

func _hex_range_and_snout_default_match_the_server() -> void:
	eq(CharacterAssembler.HEX_PATTERN, "^#[0-9a-fA-F]{3,8}$",
			"the hex pattern is the server's literal")
	# The narrow 3/4/6/8 regex this copy used to carry silently dropped valid
	# stored colours back to the default.
	for hex in ["#abc", "#abcd", "#abcde", "#abcdef", "#abcdef01"]:
		ok(CharacterAssembler.valid_hex(hex), "%s is accepted (server range 3..8)" % hex)
	for bad in ["#ab", "#abcdef012", "abcdef", "#ghijkl", "#fff\n"]:
		not_ok(CharacterAssembler.valid_hex(bad), "%s is rejected" % bad.strip_escapes())

	# snoutLength defaults FLAT to 0.5 — not to a family bias. A lapine used to
	# default to 0.3 here and 0.5 everywhere else, so the same definition grew a
	# different muzzle in the room than in the maker.
	var lapine: Dictionary = CharacterAssembler.parse_config({"head": {"family": "lapine"}})
	almost(lapine["head"]["snoutLength"], 0.5, "an absent snoutLength defaults to 0.5")
	var equine: Dictionary = CharacterAssembler.parse_config({"head": {"family": "equine"}})
	almost(equine["head"]["snoutLength"], 0.5, "…for every family, not just some")
	var explicit: Dictionary = CharacterAssembler.parse_config(
			{"head": {"family": "lapine", "snoutLength": 0.8}})
	almost(explicit["head"]["snoutLength"], 0.8, "an explicit snoutLength is still honoured")


# ── 7. BOUNDED PART-ID / SHORT-STRING PATTERNS ───────────────────────────────
#
# The mirror must never be MORE PERMISSIVE than the validator it mirrors: a
# value accepted here and rejected by the BFF renders in the room and then 400s
# when its wearer tries to save.

func _bounded_patterns_match_the_server() -> void:
	eq(CharacterAssembler.PART_ID_PATTERN, "^[A-Za-z0-9_-]{1,64}$",
			"PART_ID_PATTERN is the server's literal")
	eq(CharacterAssembler.SHORT_STR_PATTERN, "^[A-Za-z0-9 _-]{1,40}$",
			"SHORT_STR_PATTERN is the server's literal")
	eq(CharacterAssembler.KEY_PATTERN, "^[a-z0-9_-]{1,40}$",
			"KEY_PATTERN is the server's literal")

	var cfg: Dictionary = CharacterAssembler.parse_config({
		"head": {"family": "canid", "variant": "Snow Coat", "earStyle": "pointed",
				"hornStyle": "curved horns!"},
		"parts": {"tail": "fluff", "wings": "bad id", "ears": "ears/../etc",
				"feature": "a".repeat(65), "hair": "short"},
	})
	eq(cfg["parts"]["tail"], "fluff", "a well-formed part id survives")
	eq(cfg["parts"]["wings"], null, "a part id with a space is dropped, not truncated")
	eq(cfg["parts"]["ears"], null, "a part id with path characters is dropped")
	eq(cfg["parts"]["feature"], null, "an over-long part id is dropped, not truncated")
	eq(cfg["head"]["variant"], "Snow Coat", "a well-formed variant survives")
	eq(cfg["head"]["earStyle"], "pointed", "a well-formed earStyle survives")
	eq(cfg["head"]["hornStyle"], null, "a hornStyle with punctuation is dropped")
	var newline: Dictionary = CharacterAssembler.parse_config(
			{"head": {"family": "canid", "variant": "ok\nsmuggled"}})
	eq(newline["head"]["variant"], "", "a variant with an embedded newline is unset")
	not_ok(CharacterAssembler.valid_short_str("a".repeat(41)),
			"a 41-character short string is rejected")
	ok(CharacterAssembler.valid_key("tail_1"), "a lower-slug key is accepted")
	not_ok(CharacterAssembler.valid_key("Tail"), "an upper-case key is rejected")


## Display names: control / bidi-override / invisible code points STRIPPED. The
## BFF 400s on them; a room nametag sits next to every other participant's, so
## an unfiltered one visually rewrites a NEIGHBOUR's handle and there is no
## moderation seam on this surface.
func _display_names_are_sanitised() -> void:
	ok(CharacterAssembler.is_forbidden_name_char(0x202E), "U+202E RTL OVERRIDE is forbidden")
	ok(CharacterAssembler.is_forbidden_name_char(0x200B), "U+200B ZERO WIDTH SPACE is forbidden")
	ok(CharacterAssembler.is_forbidden_name_char(0x2069), "U+2069 POP ISOLATE is forbidden")
	ok(CharacterAssembler.is_forbidden_name_char(0x0009), "TAB is forbidden")
	ok(CharacterAssembler.is_forbidden_name_char(0x2028), "U+2028 LINE SEPARATOR is forbidden")
	not_ok(CharacterAssembler.is_forbidden_name_char(0x0041), "'A' is allowed")
	not_ok(CharacterAssembler.is_forbidden_name_char(0x1F600), "an emoji is allowed")

	eq(CharacterAssembler.display_name("Ali\u202Ece"), "Alice",
			"a bidi override is stripped out of a name")
	eq(CharacterAssembler.display_name("  spaced  "), "spaced", "edges are trimmed")
	eq(CharacterAssembler.display_name("\u200B\u200B"), "",
			"a name made only of invisibles collapses to empty")
	eq(CharacterAssembler.display_name(null), "", "a non-string name is empty")
	eq(CharacterAssembler.display_name("n".repeat(200)).length(), 120,
			"a name is capped at the server's 120")
	var cfg: Dictionary = CharacterAssembler.parse_config({"name": "Bo\u202Eb"})
	eq(cfg["name"], "Bob", "parse_config sanitises the config's own name")


# ── 3. placeMedia DECODER ────────────────────────────────────────────────────
#
# The host forwards the UNTRANSCODED original Matrix attachment (it has no
# decoder and adding one is the expensive fix), so the bytes are routinely JPEG
# or WebP. Decoding PNG-only meant every photo in a channel showed a red error
# plate and a warning that blamed the bytes.

func _media_decodes_jpeg_and_webp() -> void:
	var src := Image.create_empty(8, 8, false, Image.FORMAT_RGB8)
	src.fill(Color(0.9, 0.2, 0.2))
	var png := src.save_png_to_buffer()
	var jpg := src.save_jpg_to_buffer(0.9)
	var webp := src.save_webp_to_buffer(false)

	eq(MediaSurfaces.sniff_format(png), "png", "PNG magic is recognised")
	eq(MediaSurfaces.sniff_format(jpg), "jpg", "JPEG magic is recognised")
	eq(MediaSurfaces.sniff_format(webp), "webp", "WebP magic is recognised")
	ok(MediaSurfaces.decode_image(png) != null, "PNG bytes decode")
	ok(MediaSurfaces.decode_image(jpg) != null, "JPEG bytes decode")
	ok(MediaSurfaces.decode_image(webp) != null, "WebP bytes decode")
	ok(MediaSurfaces.decode_image(PackedByteArray([1, 2, 3, 4, 5, 6, 7, 8, 9])) == null,
			"genuinely undecodable bytes still fail (the warning path stays)")
	ok(MediaSurfaces.decode_image(PackedByteArray()) == null, "empty bytes are not an image")

	# End to end: a JPEG placement must TEXTURE the surface, not fall to the
	# error plate.
	var room := make_room()
	var surfaces: Node3D = room.get_node("Surfaces")
	surfaces.call("place", "table-1", {"label": "photo"}, jpg)
	var mesh := surfaces.get_node("Table1") as MeshInstance3D
	var mat := mesh.material_override as StandardMaterial3D
	ok(mat.albedo_texture != null, "a JPEG placement textures the surface")
	eq(mat.albedo_color, Color.WHITE, "…and is not the red undecodable plate")
	surfaces.call("place", "table-2", {"label": "junk"}, PackedByteArray([9, 9, 9, 9]))
	var mat2 := (surfaces.get_node("Table2") as MeshInstance3D).material_override \
			as StandardMaterial3D
	ok(mat2.albedo_texture == null, "undecodable bytes still show the placeholder plate")
	free_room(room)


# ── 4. MEDIA SIZE CAP ────────────────────────────────────────────────────────
#
# MAX_MEDIA_BYTES sat inside the ArrayBuffer/TypedArray normaliser, which the
# base64-string transport that actually shipped never enters — so the cap was
# unreachable and each side's comments assumed the other bounded it. The GDScript
# decode path is the half a headless run can execute; the JS half is pinned in
# games/_shared/test/chat3d-envelope.test.mjs.

func _inbound_media_is_capped() -> void:
	var room := make_room()
	var bridge: Node = room.get_node("Chat3DBridge")
	room.call("_setup_matrix")
	var seen: Array = []
	bridge.connect("place_media_received",
			func(sid: String, meta: Dictionary, bytes: PackedByteArray):
				seen.append({"surface": sid, "meta": meta, "size": bytes.size()}))

	var cap: int = CHAT3D.MAX_MEDIA_BYTES
	var oversized := Marshalls.raw_to_base64(_filler(cap + 1024))
	bridge.call("_dispatch", "placeMedia", ["table-1", "{\"label\":\"big\"}", oversized])
	eq(seen.size(), 1, "an oversized placement still delivers (plaque, not silence)")
	eq(int(seen[0]["size"]), 0, "oversized inbound bytes are dropped at the cap")
	eq(str(seen[0]["meta"].get("label", "")), "big", "…and the metadata survives to label it")

	var small := Marshalls.raw_to_base64(_filler(2048))
	bridge.call("_dispatch", "placeMedia", ["table-2", "{}", small])
	eq(int(seen[1]["size"]), 2048, "an under-cap texture is delivered intact")
	free_room(room)


static func _filler(n: int) -> PackedByteArray:
	var b := PackedByteArray()
	b.resize(n)
	b.fill(0x41)
	return b


# ── 6. DISPLAY NAMES ─────────────────────────────────────────────────────────
#
# The host ships `name` in every roster row and documents it as the nametag
# source; this side built the tag from the mxid localpart and threw it away.

func _nametags_use_the_supplied_display_name() -> void:
	var room := make_room()
	var avatars: Node3D = room.get_node("Avatars")
	room.call("_setup_matrix")

	room.call("_on_roster", [
		{"userId": "@alice:example.org", "name": "Alice Liddell"},
		{"userId": "@bob:example.org", "name": ""},
		{"userId": "@mallory:example.org", "name": "Al\u202Eice"},
	])
	eq(_nametag(avatars, "@alice:example.org"), "Alice Liddell",
			"the roster's display name becomes the nametag")
	eq(_nametag(avatars, "@bob:example.org"), "bob",
			"an empty name falls back to the mxid localpart")
	eq(_nametag(avatars, "@mallory:example.org"), "Alice",
			"a bidi override in a roster name is stripped before it is drawn")

	# A bare-string roster (the tolerant legacy shape) still gets the localpart.
	room.call("_on_roster", ["@carol:example.org"])
	eq(_nametag(avatars, "@carol:example.org"), "carol",
			"a string-only roster row still yields a localpart nametag")

	# The avatar envelope's name updates the tag too.
	room.call("_on_roster", [{"userId": "@dave:example.org", "name": "Dave"}])
	room.call("_on_avatar", "@dave:example.org", null, {"name": "Dave the Third", "mode": "forge"})
	eq(_nametag(avatars, "@dave:example.org"), "Dave the Third",
			"the avatar envelope's name updates the nametag")
	# …but an envelope with NO name must not reset the tag to the localpart. An
	# older two-slot host sends exactly that on every avatar diff.
	room.call("_on_avatar", "@dave:example.org", null, {"name": "", "mode": "forge"})
	eq(_nametag(avatars, "@dave:example.org"), "Dave the Third",
			"a nameless avatar envelope does not clobber the known display name")
	free_room(room)


func _nametag(avatars: Node3D, key: String) -> String:
	var registry: Dictionary = avatars.get("_avatars")
	if not registry.has(key):
		return "<missing>"
	return (registry[key]["label"] as Label3D).text


# ── 9. AVATAR BILLBOARD MODE ─────────────────────────────────────────────────
#
# The host ships mode/sticker/name OUTSIDE the signature and configJson=null for
# billboard participants; the game skipped the emit entirely on a null config,
# so a participant who chose "appear as my flat VN sticker" was rendered as a
# randomised 3D character instead. Resolution: the three fields join the
# signature on BOTH sides.

func _billboard_avatars_are_representable() -> void:
	eq(Array(CHAT3D.SIGNATURES["avatar"]),
			["userId", "configJson", "mode", "sticker", "name"],
			"avatar's signature carries the billboard slots")

	var room := make_room()
	var avatars: Node3D = room.get_node("Avatars")
	var bridge: Node = room.get_node("Chat3DBridge")
	room.call("_setup_matrix")

	# EXACTLY what the host sends for a billboard participant.
	bridge.call("_dispatch", "avatar",
			["@vn:example.org", null, "billboard", "mxc://example.org/sticker", "Sticker Sam"])
	ok(avatars.call("has_avatar", "@vn:example.org"),
			"a null-config billboard avatar is no longer dropped on the floor")
	eq(str(avatars.call("mode_of", "@vn:example.org")), "billboard",
			"the participant is recorded as billboard mode")
	eq(str(avatars.call("sticker_of", "@vn:example.org")), "mxc://example.org/sticker",
			"the sticker mxc is carried (the bytes channel is a host-side follow-up)")
	eq(_nametag(avatars, "@vn:example.org"), "Sticker Sam",
			"a billboard participant still gets their display name")
	var entry: Dictionary = avatars.get("_avatars")["@vn:example.org"]
	ok((entry["billboard"] as MeshInstance3D).visible, "the billboard plate is shown")
	not_ok((entry["rig"] as Node3D).visible, "…and the 3D rig is hidden behind it")

	# Switching back to a forge character restores the rig.
	bridge.call("_dispatch", "avatar", ["@vn:example.org",
			JSON.stringify(CharacterAssembler.random_config(7)), "forge", null, "Sam"])
	not_ok((entry["billboard"] as MeshInstance3D).visible,
			"switching to forge mode hides the billboard again")
	ok((entry["rig"] as Node3D).visible, "…and shows the rig")

	# A non-mxc sticker is dropped rather than carried into the scene.
	bridge.call("_dispatch", "avatar",
			["@vn:example.org", null, "billboard", "https://evil.example/x.png", "Sam"])
	eq(str(avatars.call("sticker_of", "@vn:example.org")), "",
			"a non-mxc sticker URL is refused")

	# An avatar call with neither a config nor a mode is still a no-op — the
	# tolerance added for billboard must not let an empty call wipe a rig.
	var before: int = int(avatars.call("keys").size())
	bridge.call("_dispatch", "avatar", ["@ghost:example.org", null, "", null, ""])
	eq(int(avatars.call("keys").size()), before,
			"an avatar call with no config and no mode creates nothing")
	free_room(room)


# ── 5. EXPRESSION / STAGE ────────────────────────────────────────────────────
#
# Both are declared, unit-tested and fully RECEIVED here, and the host has ZERO
# producers for either — the same "receiver nobody feeds" defect as the original
# `ready` handshake bug, in mirror image.
#
# AGREED RESOLUTION: WIRE THE PRODUCERS ON THE HOST, do not delete the channels.
# An honest source exists for both (`expression` = the VN expression signal that
# already drives the 2D stage, `stage` = the moderator stage-grant surface) and
# both are named DESIGN §5 vocabulary the game can already render. These checks
# are the CONVERGENCE PIN: if the host lane deletes a channel instead, this pin
# has to be deleted with it — deliberately, in the same change — rather than
# left passing over a dead wire.

func _expression_and_stage_stay_wired() -> void:
	var room := make_room()
	var avatars: Node3D = room.get_node("Avatars")
	var bridge: Node = room.get_node("Chat3DBridge")
	room.call("_setup_matrix")
	room.call("_on_roster", [{"userId": "@ex:example.org", "name": "Ex"}])

	bridge.call("_dispatch", "expression", ["@ex:example.org", "surprised"])
	var rig: Node3D = avatars.get("_avatars")["@ex:example.org"]["rig"]
	eq(str(rig.call("get_expression")), "surprised",
			"the expression channel reaches the rig (host must wire its producer)")
	bridge.call("_dispatch", "expression", ["@ex:example.org", "not-a-label"])
	eq(str(rig.call("get_expression")), "neutral", "an unknown label falls back to neutral")

	bridge.call("_dispatch", "stage", ["@ex:example.org", true])
	ok(bool(avatars.call("is_on_stage", "@ex:example.org")),
			"the stage channel reaches the avatar (host must wire its producer)")
	bridge.call("_dispatch", "stage", ["@ex:example.org", false])
	not_ok(bool(avatars.call("is_on_stage", "@ex:example.org")), "…and revokes")
	free_room(room)

# ── 10. ORIGIN + SOURCE-WINDOW PINNING ───────────────────────────────────────
#
# The pin lives in the JS shim chat3d_bridge.gd injects, which a headless Godot
# run never executes. It is covered functionally — by running the real extracted
# template — in games/_shared/test/chat3d-envelope.test.mjs ("3b. SOURCE-WINDOW
# pin"). Asserting a substring of the template here would pass on a shim that no
# longer works, so it is deliberately NOT duplicated.
