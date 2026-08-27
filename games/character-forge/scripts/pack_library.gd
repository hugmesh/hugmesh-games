class_name CharForgePacks
extends RefCounted

# =============================================================================
# THE VENDORED PACKS -> THE ASSEMBLER V2 BASIS REGISTRY.
#
# CharForgeRegistry (scripts/part_registry.gd) knows how to hold a mesh basis
# and how to REFUSE one without complete provenance. This class is what fills
# it: it reads the generated catalogue at res://assets/packs/lanes.json —
# emitted by tools/asset-pipeline/build-lanes.mjs from the hand-authored lane
# mapping plus the committed pack.json manifests — and turns a catalogue entry
# into a registry entry carrying licenseSpdx / attribution / sourceUrl /
# retrievedAt straight from the manifest that THIRD_PARTY_ASSETS.md aggregates.
#
# Nothing here invents a licence, a path or a family. Every field is copied
# from the generated file, and a catalogue that fails to load leaves the
# registry EMPTY — i.e. every family resolves procedural, exactly as it did
# before any pack was vendored. That is the deliberate failure mode: no asset
# is better than an unprovenanced one.
#
# WHY ONE ENTRY AT A TIME: the catalogue has 42 entries but the registry is
# keyed by head family / body archetype, and most of those entries are
# "humanoid". A registry populated with all of them would be a coin toss over
# which humanoid you get. So the UI picks an entry and registry_for() builds a
# registry that routes exactly that entry's family (mount "avatar") or
# archetype (mount "body"); everything else stays procedural.
#
# LANE COVERAGE (the DESIGN §2 styles, as the research verdicts allow):
#   anime     VRoid CC0 VRM bases + the CC-BY Godette mascot.
#   jrpg      Quaternius Universal Base Characters (recolourable), the four
#             modular outfit SETS composed from garment pieces, and KayKit.
#   kemono    PROCEDURAL PRIMARY — legal-inventory.md: "There is NO
#             production-grade CC0 rigged+animated anthro biped pack
#             anywhere". Mesh options are a CC0 body under a procedural beast
#             head, plus the Ultimate Monsters chibi bipeds.
#   monsters  Quaternius Ultimate Monsters + the KayKit undead.
#   import    the 100Avatars VRM corpus (the BYO-VRM importer's reference set).
# =============================================================================

const LANES_PATH := "res://assets/packs/lanes.json"

## Lane ids in the order the UI shows them as style tabs.
const LANE_ORDER := ["anime", "jrpg", "kemono", "monsters", "import"]

static var _catalog: Dictionary = {}
static var _loaded := false
static var _load_error := ""


## The parsed catalogue ({} when it could not be read). Cached after the first
## call — reload() forces a re-read.
static func catalog(path: String = LANES_PATH) -> Dictionary:
	if not _loaded:
		reload(path)
	return _catalog


## Re-reads the catalogue from disk. Returns the parsed dictionary ({} on any
## failure, with the reason available from load_error()).
static func reload(path: String = LANES_PATH) -> Dictionary:
	_loaded = true
	_catalog = {}
	_load_error = ""
	if not FileAccess.file_exists(path):
		_load_error = "no catalogue at " + path
		return _catalog
	var text := FileAccess.get_file_as_string(path)
	if text.is_empty():
		_load_error = "catalogue at %s is empty" % path
		return _catalog
	var parsed: Variant = JSON.parse_string(text)
	if not (parsed is Dictionary):
		_load_error = "catalogue at %s is not a JSON object" % path
		return _catalog
	var dict: Dictionary = parsed
	if String(dict.get("schema", "")) != "3d-pack-lanes":
		_load_error = "catalogue at %s is not a 3d-pack-lanes document" % path
		return _catalog
	_catalog = dict
	return _catalog


## Why the catalogue is empty ("" when it loaded).
static func load_error() -> String:
	if not _loaded:
		reload()
	return _load_error


## True when at least one pack entry is available to mount.
static func available() -> bool:
	return not entries().is_empty()


## Lanes in LANE_ORDER (unknown lane ids keep their catalogue order, last).
static func lanes() -> Array:
	var raw: Variant = catalog().get("lanes", [])
	if not (raw is Array):
		return []
	var out: Array = []
	for lane_id in LANE_ORDER:
		for lane in raw:
			if lane is Dictionary and String(lane.get("id", "")) == lane_id:
				out.append(lane)
	for lane in raw:
		if lane is Dictionary and not (String(lane.get("id", "")) in LANE_ORDER):
			out.append(lane)
	return out


## One lane by id ({} when absent).
static func lane(lane_id: String) -> Dictionary:
	for row in lanes():
		if String(row.get("id", "")) == lane_id:
			return row
	return {}


## Every catalogue entry, flattened, in lane order.
static func entries() -> Array:
	var out: Array = []
	for row in lanes():
		var lane_entries: Variant = row.get("entries", [])
		if lane_entries is Array:
			for e in lane_entries:
				if e is Dictionary:
					out.append(e)
	return out


## Entries of one lane ([] when the lane is unknown).
static func lane_entries(lane_id: String) -> Array:
	var row := lane(lane_id)
	var raw: Variant = row.get("entries", [])
	return raw if raw is Array else []


## One entry by key ({} when unknown).
static func entry(key: String) -> Dictionary:
	for e in entries():
		if String(e.get("key", "")) == key:
			return e
	return {}


## Capability gaps recorded at build time (why a wanted graft is NOT shipped).
static func blocked() -> Array:
	var raw: Variant = catalog().get("blocked", [])
	return raw if raw is Array else []


## Packs that are vendored but reachable from NO lane, and why (donor geometry,
## test fixtures). build-lanes.mjs refuses to emit a catalogue in which a pack
## is neither laned nor explained, so this is never silently empty.
static func unlaned() -> Dictionary:
	var raw: Variant = catalog().get("unlaned", {})
	return raw if raw is Dictionary else {}


## {lanes, entries, recolorable} straight out of the generated catalogue.
static func totals() -> Dictionary:
	var raw: Variant = catalog().get("totals", {})
	return raw if raw is Dictionary else {}


## Every attribution the catalogue carries, deduped by (licence, attribution).
## CC-BY REQUIRES the credit to be user-visible, so the UI reads this — the
## manifest alone does not discharge the licence.
static func attributions() -> Array:
	var seen := {}
	var out: Array = []
	for e in entries():
		var key := "%s|%s" % [e.get("licenseSpdx", ""), e.get("attribution", "")]
		if seen.has(key):
			continue
		seen[key] = true
		out.append({
			"licenseSpdx": String(e.get("licenseSpdx", "")),
			"attribution": String(e.get("attribution", "")),
			"sourceUrl": String(e.get("sourceUrl", "")),
			"retrievedAt": String(e.get("retrievedAt", "")),
			"packId": String(e.get("packId", "")),
			"required": String(e.get("licenseSpdx", "")).begins_with("CC-BY"),
		})
	out.sort_custom(func(a, b): return String(a.attribution) < String(b.attribution))
	return out


## Translates a catalogue entry into the registry's mesh-basis shape. The
## provenance fields are copied verbatim — if the catalogue ever ships an entry
## without them, CharForgeRegistry rejects it and the family stays procedural.
static func basis_entry(e: Dictionary) -> Dictionary:
	var slots_raw: Variant = e.get("slots", {})
	# HOW this entry recolours, which is NOT the same question as WHETHER it
	# does. Three policies, and the middle one is why this cannot be derived
	# from `recolorable`:
	#   map       named material slots -> rebind every surface to a house material
	#   atlas     one baked atlas, no slots -> KEEP the vendor material as the
	#             shading source and tint per mesh node through it. Recolourable,
	#             yet it must preserve materials — deriving preserveMaterials from
	#             `recolorable` sent these down the slot-swap path and flattened
	#             all 15 of the Knight's surfaces to one colour.
	#   preserve  keep the vendor material and give up recolouring.
	var policy := String(e.get("materialPolicy", "preserve"))
	return {
		"basis": CharForgeRegistry.BASIS_MESH,
		"glb": String(e.get("glb", "")),
		# Composed sets (the modular Quaternius outfits): extra GLBs whose skinned
		# meshes are merged onto the primary's skeleton at mount time.
		"pieces": _string_array(e.get("pieces", [])),
		"rig": String(e.get("rig", "")),
		"slots": slots_raw if slots_raw is Dictionary else {},
		# Single-atlas packs (KayKit, 100Avatars, the Khronos Fox) cannot be
		# region-recoloured — binding one atlas material to a flat house colour
		# paints the whole character one colour. materialPolicy "preserve" in the
		# catalogue becomes preserveMaterials here.
		"preserveMaterials": policy != "map",
		"visemes": bool(e.get("visemes", false)),
		"jawBone": "",
		"wholeAvatar": String(e.get("mount", "")) == "avatar",
		"hiddenMeshes": _string_array(e.get("hiddenMeshes", [])),
		# {meshNodeName: paletteRegion} for a single-atlas character. Resolved in
		# the asset pipeline so the mapping is reviewable in the catalogue rather
		# than guessed from node names at runtime.
		"atlasRegions": e.get("atlasRegions", {}) if e.get("atlasRegions", {}) is Dictionary else {},
		# Region order for a character with NO per-part boundary: the Nth largest
		# base colour in its atlas takes the Nth region here.
		"atlasClusters": e.get("atlasClusters", {}) if e.get("atlasClusters", {}) is Dictionary else {},
		# A SECOND vendored model whose head is split off by bone weight at mount
		# time and grafted onto this body (scripts/mesh_surgery.gd). Copied
		# verbatim, licence fields included: CharForgeRegistry validates the donor
		# as strictly as the body, so an under-credited donor rejects the entry.
		"headDonor": e.get("headDonor", {}) if e.get("headDonor", {}) is Dictionary else {},
		"packId": String(e.get("packId", "")),
		"modelId": String(e.get("modelId", "")),
		"key": String(e.get("key", "")),
		"displayName": String(e.get("displayName", "")),
		"lane": String(e.get("lane", "")),
		"licenseSpdx": String(e.get("licenseSpdx", "")),
		"attribution": String(e.get("attribution", "")),
		"sourceUrl": String(e.get("sourceUrl", "")),
		"retrievedAt": String(e.get("retrievedAt", "")),
	}


## A registry routing exactly ONE catalogue entry through the mesh basis.
## Everything else — every other family, every other archetype — stays
## procedural. An unknown key, or an entry the registry rejects on provenance
## grounds, yields the all-procedural registry (never a half-wired one).
static func registry_for(key: String) -> CharForgeRegistry:
	var registry := CharForgeRegistry.default_registry()
	if key.is_empty():
		return registry
	var e := entry(key)
	if e.is_empty():
		push_warning("CharForgePacks: no catalogue entry '%s'" % key)
		return registry
	var basis := basis_entry(e)
	var ok := false
	if String(e.get("mount", "")) == "body":
		ok = registry.register_body(String(e.get("archetype", "")), basis)
	else:
		ok = registry.register_head(String(e.get("family", "")), basis)
	if not ok:
		push_warning("CharForgePacks: catalogue entry '%s' rejected: %s"
				% [key, ", ".join(registry.last_errors)])
		return CharForgeRegistry.default_registry()
	return registry


## A registry wired with EVERY entry of one lane. Later entries win on a
## collision, so this is only meaningful for lanes whose entries occupy
## distinct families (monsters, kemono) — the UI uses registry_for() for the
## humanoid-heavy lanes. Returns how many entries bound via `out_bound`.
static func registry_for_lane(lane_id: String, out_bound: Array = []) -> CharForgeRegistry:
	var registry := CharForgeRegistry.default_registry()
	for e in lane_entries(lane_id):
		var basis := basis_entry(e)
		var ok := false
		if String(e.get("mount", "")) == "body":
			ok = registry.register_body(String(e.get("archetype", "")), basis)
		else:
			ok = registry.register_head(String(e.get("family", "")), basis)
		if ok:
			out_bound.append(String(e.get("key", "")))
	return registry


## Catalogue keys whose entry can render the given head family as a whole
## avatar — what the UI offers when a family is selected.
static func keys_for_family(family: String) -> PackedStringArray:
	var out := PackedStringArray()
	for e in entries():
		if String(e.get("mount", "")) == "avatar" and String(e.get("family", "")) == family:
			out.append(String(e.get("key", "")))
	return out


## The mesh BODY entries — the kemono hybrid (CC0 body, procedural beast head).
static func body_mount_keys() -> PackedStringArray:
	var out := PackedStringArray()
	for e in entries():
		if String(e.get("mount", "")) == "body":
			out.append(String(e.get("key", "")))
	return out


## A compact JSON-able catalogue for the host page (the Angular selector reads
## the same data from the generated TypeScript, but a host embedding only the
## engine can ask for it over the bridge). Bytes/tris/joints are carried so the
## UI can be honest about the Quest-3 budget before a model is downloaded.
static func to_host_json() -> Dictionary:
	var lanes_out: Array = []
	for row in lanes():
		var items: Array = []
		for e in row.get("entries", []):
			items.append({
				"key": e.get("key", ""),
				"displayName": e.get("displayName", ""),
				"family": e.get("family", ""),
				"archetype": e.get("archetype", ""),
				"mount": e.get("mount", ""),
				"style": e.get("style", ""),
				"rig": e.get("rig", ""),
				"tris": e.get("tris", 0),
				"joints": e.get("joints", 0),
				"bytes": e.get("bytes", 0),
				"recolorable": e.get("recolorable", false),
				"visemes": e.get("visemes", false),
				"partMixable": e.get("partMixable", true),
				"licenseSpdx": e.get("licenseSpdx", ""),
				"attribution": e.get("attribution", ""),
				"sourceUrl": e.get("sourceUrl", ""),
				"notes": e.get("notes", ""),
			})
		lanes_out.append({
			"id": row.get("id", ""),
			"displayName": row.get("displayName", ""),
			"summary": row.get("summary", ""),
			"proceduralFamilies": row.get("proceduralFamilies", []),
			"entries": items,
		})
	return {
		"schema": "3d-pack-lanes",
		"available": available(),
		"loadError": load_error(),
		"totals": totals(),
		"lanes": lanes_out,
		"blocked": blocked(),
	}


static func _string_array(raw: Variant) -> PackedStringArray:
	var out := PackedStringArray()
	if raw is Array or raw is PackedStringArray:
		for v in raw:
			var s := String(v).strip_edges()
			if not s.is_empty():
				out.append(s)
	return out
