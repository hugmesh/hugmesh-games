@tool
extends RefCounted

# Pure function: dialog inputs → manifest.json dictionary.
# Kept on its own so the BFF's matching parser (and any unit tests) can
# reference the schema version and shape without dragging in the editor UI.

const SCHEMA_VERSION := 1


# `inputs` keys: schema_version, slug, name, description, scene_type,
# chromeless (bool), godot_version
static func build(inputs: Dictionary) -> Dictionary:
	var slug: String = inputs.get("slug", "")
	var asset_url := "/api/assets/levels/%s/index" % slug

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
		"godot_version": inputs.get("godot_version", "unknown"),
		"exported_at":   Time.get_datetime_string_from_system(true),
	}
