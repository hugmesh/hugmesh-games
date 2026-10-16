@tool
extends RefCounted

# Pure function: dialog inputs → manifest.json dictionary, plus the
# `register.sql` content the BFF's GameUploadController will execute
# inside the upload transaction so the new game lands atomically in
# Postgres alongside its bundle.
#
# Kept on its own so the BFF's matching parser (and any unit tests) can
# reference the schema version and shape without dragging in the editor UI.

const SCHEMA_VERSION := 2


# Default per-feature flag set declared by a vanilla game upload.
# JRPG-shaped titles flip everything on; chrome-less demos override
# specific flags via inputs["features"]. Keys MUST match the BFF's
# games.features jsonb vocabulary: the eleven keys of the column DEFAULT
# set by Liquibase changeset 063-1 (instant_loadout was spell_loadout
# before 028-7; world and control are the two relay kinds). Input gate 2
# refuses an event whose kind flag is ABSENT, so a key missing here is a
# 405 for that game after an install (hugmesh-games#17). Pinned against
# this file's text by ManifestBuilderFeaturesGuardTest in the backend.
const _DEFAULT_FEATURES := {
	"instant_loadout": true,
	"quests": true,
	"items": true,
	"char_progression": true,
	"audio": true,
	"gamepad": true,
	"mouse": true,
	"keyboard": true,
	"touch": true,
	"world": true,
	"control": true,
}


# Default supported_events list. The HugMesh Export plugin emits
# this in the manifest so the upload pipeline can populate the
# game_event_support table — every (game_slug, event_kind, event_type)
# triple a game declares it consumes / emits. Add more by passing
# inputs["supported_events"] from the editor dialog; the BFF rejects
# events whose triple isn't present here.
const _DEFAULT_SUPPORTED_EVENTS := [
	# Universal — every game gets these
	{"kind": "world",   "type": "spawn",          "direction": "out"},
	{"kind": "world",   "type": "despawn",        "direction": "out"},
	{"kind": "world",   "type": "transition",     "direction": "out"},
	{"kind": "world",   "type": "cutscene",       "direction": "out"},
	# Input devices — host-tier polls forward these via postMessage
	{"kind": "gamepad", "type": "connected",      "direction": "in"},
	{"kind": "gamepad", "type": "disconnected",   "direction": "in"},
	{"kind": "gamepad", "type": "button_down",    "direction": "in"},
	{"kind": "gamepad", "type": "button_up",      "direction": "in"},
	{"kind": "gamepad", "type": "axis",           "direction": "in"},
	{"kind": "mouse",   "type": "motion",         "direction": "in"},
	{"kind": "mouse",   "type": "button_down",    "direction": "in"},
	{"kind": "mouse",   "type": "button_up",      "direction": "in"},
	{"kind": "mouse",   "type": "wheel",          "direction": "in"},
	{"kind": "keyboard","type": "key_down",       "direction": "in"},
	{"kind": "keyboard","type": "key_up",         "direction": "in"},
	{"kind": "touch",   "type": "down",           "direction": "in"},
	{"kind": "touch",   "type": "move",           "direction": "in"},
	{"kind": "touch",   "type": "up",             "direction": "in"},
	{"kind": "touch",   "type": "cancel",         "direction": "in"},
	# Audio — bidirectional (game emits cues; host can mirror back)
	{"kind": "audio",   "type": "play",           "direction": "out"},
	{"kind": "audio",   "type": "stop",           "direction": "out"},
	{"kind": "audio",   "type": "volume",         "direction": "both"},
	{"kind": "audio",   "type": "device_change",  "direction": "in"},
]


# `inputs` keys: schema_version, slug, name, description, scene_type,
# chromeless (bool), godot_version, optional remote_entry_url + remote_kind
# (default kind='remote-game') for the federation row, optional
# features (Dictionary; merged into _DEFAULT_FEATURES — keys present
# here override the JRPG defaults), and optional supported_events
# (Array of {kind, type, direction} dicts; replaces the default list
# entirely — set to a narrow subset for chrome-less demos).
static func build(inputs: Dictionary) -> Dictionary:
	var slug: String = inputs.get("slug", "")
	var asset_url := "/api/assets/levels/%s/index" % slug
	var remote_kind: String = inputs.get("remote_kind", "remote-game")
	var remote_entry_url: String = inputs.get(
		"remote_entry_url",
		# Default convention: relative path the frontend nginx proxies
		# through to the matching games-<slug> compose service. Single
		# origin keeps COOP/COEP + CORS simple and works for both
		# host-browser and in-network e2e clients.
		"/_remotes/games-%s/remoteEntry.json" % slug,
	)

	return {
		"schema_version": int(inputs.get("schema_version", SCHEMA_VERSION)),
		"game": {
			"name":        inputs.get("name", ""),
			"slug":        slug,
			"description": inputs.get("description", ""),
		},
		"levels": [
			{
				# v1 packs one level per zip — the slug is the level slug too.
				# Multi-level games either upload several zips OR we extend
				# the schema with a 'levels' source dir later.
				"name":         inputs.get("name", ""),
				"slug":         slug,
				"scene_type":   inputs.get("scene_type", slug),
				"sort_order":   1,
				"asset_url":    asset_url + ".html",
				"scene_config": {},
				"chromeless":   bool(inputs.get("chromeless", false)),
			},
		],
		"federation": {
			# v2 addition: tells the upload endpoint where this game's
			# Module Federation remote will be reachable. The BFF inserts
			# this into `federation_modules` so the host shell discovers
			# it on next manifest fetch.
			"slug":             slug,
			"display_name":     inputs.get("name", ""),
			"kind":             remote_kind,
			"remote_entry_url": remote_entry_url,
			"exposed_module":   "./GameRoot",
			"version":          inputs.get("godot_version", "0.1.0"),
			"metadata":         {
				"hud":        not bool(inputs.get("chromeless", false)),
				"sse":        not bool(inputs.get("chromeless", false)),
				"chromeless": bool(inputs.get("chromeless", false)),
			},
		},
		"access": {
			# Default policy when the game first registers: visible to
			# every authenticated user. Admins can narrow this from the
			# admin console afterwards.
			"default_scope": "all",
		},
		"features":         _merged_features(inputs.get("features", {})),
		"supported_events": inputs.get("supported_events", _DEFAULT_SUPPORTED_EVENTS),
		"godot_version":    inputs.get("godot_version", "unknown"),
		"exported_at":      Time.get_datetime_string_from_system(true),
	}


static func _merged_features(overrides: Dictionary) -> Dictionary:
	var out: Dictionary = {}
	for k in _DEFAULT_FEATURES.keys():
		out[k] = _DEFAULT_FEATURES[k]
	for k in overrides.keys():
		out[k] = overrides[k]
	return out


# Generates the parameterised SQL the upload endpoint runs inside its
# install transaction. Returned as a multi-line string so the editor
# plugin can write it to `register.sql` alongside `manifest.json`.
#
# All values are SQL-escaped (single-quote doubling) since the BFF runs
# these directly via Postgres (no R2DBC parameter binding for one-shot
# upload SQL — keeps the v2 wire shape simple). Don't pass user input
# through here without sanitising slug + names first; the export dialog
# already enforces /[a-z0-9][a-z0-9-]*/ on slug.
static func to_register_sql(manifest: Dictionary) -> String:
	var game: Dictionary = manifest.get("game", {})
	var levels: Array = manifest.get("levels", [])
	var fed: Dictionary = manifest.get("federation", {})
	var slug := _esc(game.get("slug", ""))

	var sql := "-- Generated by HugMesh Export plugin (manifest_builder.to_register_sql)\n"
	sql += "-- The upload endpoint (GameUploadController) runs this inside the same transaction\n"
	sql += "-- as the bundle install + symlink flip so the catalog stays consistent.\n\n"

	sql += "INSERT INTO games (name, description, slug, sort_order, created_at)\n"
	sql += "VALUES ('%s', '%s', '%s', 0, NOW())\n" % [
		_esc(game.get("name", "")),
		_esc(game.get("description", "")),
		slug,
	]
	sql += "ON CONFLICT (slug) DO UPDATE SET name = EXCLUDED.name, description = EXCLUDED.description;\n\n"

	for lvl in levels:
		sql += "INSERT INTO levels (name, description, slug, scene_type, sort_order, asset_url, scene_config, game_id, created_at)\n"
		sql += "SELECT '%s', '', '%s', '%s', %d, '%s', '{}'::jsonb, g.id, NOW() FROM games g WHERE g.slug = '%s'\n" % [
			_esc(lvl.get("name", "")),
			_esc(lvl.get("slug", slug)),
			_esc(lvl.get("scene_type", slug)),
			int(lvl.get("sort_order", 1)),
			_esc(lvl.get("asset_url", "")),
			slug,
		]
		sql += "ON CONFLICT (slug) DO UPDATE SET asset_url = EXCLUDED.asset_url, scene_type = EXCLUDED.scene_type;\n\n"

	if not fed.is_empty():
		sql += "INSERT INTO federation_modules (slug, display_name, kind, remote_entry_url, exposed_module, game_slug, version, enabled, metadata)\n"
		sql += "VALUES ('%s', '%s', '%s', '%s', '%s', '%s', '%s', true, '%s'::jsonb)\n" % [
			_esc(fed.get("slug", slug)),
			_esc(fed.get("display_name", game.get("name", ""))),
			_esc(fed.get("kind", "remote-game")),
			_esc(fed.get("remote_entry_url", "")),
			_esc(fed.get("exposed_module", "./GameRoot")),
			slug,
			_esc(fed.get("version", "0.1.0")),
			_esc(JSON.stringify(fed.get("metadata", {}))),
		]
		sql += "ON CONFLICT (slug) DO UPDATE\n"
		sql += "  SET display_name = EXCLUDED.display_name,\n"
		sql += "      remote_entry_url = EXCLUDED.remote_entry_url,\n"
		sql += "      version = EXCLUDED.version,\n"
		sql += "      metadata = EXCLUDED.metadata,\n"
		sql += "      enabled = true,\n"
		sql += "      updated_at = NOW();\n\n"

	# Open-access default — admins can revoke + replace via the admin UI.
	sql += "INSERT INTO game_access (game_id, scope, scope_value, granted_by, granted_at)\n"
	sql += "SELECT g.id, 'all', NULL, 'system:upload', NOW() FROM games g WHERE g.slug = '%s'\n" % slug
	sql += "ON CONFLICT (game_id, scope, scope_value) DO NOTHING;\n\n"

	# Per-game feature flags — MERGE into the row register.sql just
	# upserted above. `features || '<flags>'` keeps every key the flags
	# do not name (right operand wins on a collision), so a key the
	# server added later survives an upload instead of being dropped by
	# a whole-object replace. Liquibase 063-1 gave the column the same
	# eleven-key default as _DEFAULT_FEATURES. RegisterSqlPolicy allows
	# `UPDATE games SET features = ...` (it only reads the columns left of
	# `=`). The policy strips `--` comments before it splits, so a quote in a
	# banner no longer desyncs it; the banner lines above are still kept free of a
	# single quote and a semicolon so the file reads the same under any older
	# BFF (ManifestBuilderFeaturesGuardTest pins this). Never emit a
	# double-quoted identifier, E'' string, dollar-quote or block comment: the
	# policy rejects the whole upload.
	var features: Dictionary = manifest.get("features", {})
	if not features.is_empty():
		sql += "UPDATE games SET features = features || '%s'::jsonb WHERE slug = '%s';\n\n" % [
			_esc(JSON.stringify(features)),
			slug,
		]

	# Per-game event-support whitelist. game_event_support is composite-
	# keyed on (game_slug, event_kind, event_type) and FKs to
	# input_event_types. The BFF refuses input emits whose triple
	# isn't present here, so an unsupported event from a malicious
	# client (or a misconfigured plugin) bounces with 405.
	var supported: Array = manifest.get("supported_events", [])
	for ev in supported:
		if ev is Dictionary:
			var kind: String = _esc(ev.get("kind", ""))
			var type: String = _esc(ev.get("type", ""))
			var direction: String = _esc(ev.get("direction", "both"))
			if kind == "" or type == "":
				continue
			sql += "INSERT INTO game_event_support (game_slug, event_kind, event_type, direction)\n"
			sql += "VALUES ('%s', '%s', '%s', '%s')\n" % [slug, kind, type, direction]
			sql += "ON CONFLICT (game_slug, event_kind, event_type) DO UPDATE\n"
			sql += "  SET direction = EXCLUDED.direction, enabled = true;\n"

	return sql


static func _esc(s: Variant) -> String:
	return String(s).replace("'", "''")
