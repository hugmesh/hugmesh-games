@tool
extends Window

# Modal that gathers per-game metadata, runs the chosen Web export preset
# to a temp directory, and produces a deploy-ready zip:
#
#   <slug>-snapdragon-bundle.zip
#   ├── manifest.json     (schema_version 1 — see ManifestBuilder)
#   └── bundle/           (Godot Web export output verbatim)
#       ├── index.html
#       ├── index.wasm
#       ├── index.pck
#       └── ...
#
# The zip is what the Snapdragon BFF's /api/admin/games/upload endpoint
# accepts; nothing else here knows about HTTP — uploading is a separate
# step the operator does from the admin UI.

const MANIFEST_SCHEMA_VERSION := 2
const ManifestBuilder := preload("res://addons/snapdragon_export/manifest_builder.gd")

var _preset_dropdown: OptionButton
var _slug_edit: LineEdit
var _name_edit: LineEdit
var _desc_edit: TextEdit
var _scene_type_edit: LineEdit
var _chromeless_check: CheckBox
var _output_path_edit: LineEdit
var _status_label: Label
var _export_btn: Button


func _init() -> void:
	title = "Export to Snapdragon"
	size = Vector2i(560, 520)
	close_requested.connect(queue_free)


func _ready() -> void:
	var root := VBoxContainer.new()
	root.add_theme_constant_override("separation", 8)
	root.set_anchors_preset(Control.PRESET_FULL_RECT)
	root.offset_left = 16
	root.offset_top = 16
	root.offset_right = -16
	root.offset_bottom = -16
	add_child(root)

	root.add_child(_make_label("Export preset (Web platform only)", 14, true))
	_preset_dropdown = OptionButton.new()
	_populate_preset_dropdown()
	root.add_child(_preset_dropdown)

	root.add_child(_make_label("Game slug (lowercase, dash-separated)", 14, true))
	_slug_edit = LineEdit.new()
	_slug_edit.placeholder_text = "dodecahedron"
	root.add_child(_slug_edit)

	root.add_child(_make_label("Display name", 14, true))
	_name_edit = LineEdit.new()
	_name_edit.placeholder_text = "Dodecahedron Demo"
	root.add_child(_name_edit)

	root.add_child(_make_label("Description", 14, true))
	_desc_edit = TextEdit.new()
	_desc_edit.custom_minimum_size = Vector2(0, 80)
	_desc_edit.placeholder_text = "A spinning regular dodecahedron — pure visual demo."
	root.add_child(_desc_edit)

	root.add_child(_make_label("scene_type tag (matches frontend SCENE_ICONS / CHROMELESS_SCENE_TYPES)", 14, true))
	_scene_type_edit = LineEdit.new()
	_scene_type_edit.placeholder_text = "dodecahedron"
	root.add_child(_scene_type_edit)

	_chromeless_check = CheckBox.new()
	_chromeless_check.text = "Chrome-less (no HUD, no controls overlay — pure visual demo)"
	root.add_child(_chromeless_check)

	root.add_child(_make_label("Output zip path", 14, true))
	var path_row := HBoxContainer.new()
	root.add_child(path_row)
	_output_path_edit = LineEdit.new()
	_output_path_edit.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	_output_path_edit.text = "user://snapdragon-bundle.zip"
	path_row.add_child(_output_path_edit)
	var browse_btn := Button.new()
	browse_btn.text = "Browse…"
	browse_btn.pressed.connect(_on_browse_pressed)
	path_row.add_child(browse_btn)

	_status_label = _make_label("", 13, false)
	_status_label.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	_status_label.size_flags_vertical = Control.SIZE_EXPAND_FILL
	root.add_child(_status_label)

	var btn_row := HBoxContainer.new()
	btn_row.alignment = BoxContainer.ALIGNMENT_END
	root.add_child(btn_row)

	var cancel_btn := Button.new()
	cancel_btn.text = "Cancel"
	cancel_btn.pressed.connect(queue_free)
	btn_row.add_child(cancel_btn)

	_export_btn = Button.new()
	_export_btn.text = "Export bundle"
	_export_btn.pressed.connect(_on_export_pressed)
	btn_row.add_child(_export_btn)


func _make_label(text: String, font_size: int, bold: bool) -> Label:
	var l := Label.new()
	l.text = text
	l.add_theme_font_size_override("font_size", font_size)
	if bold:
		l.add_theme_color_override("font_color", Color(0.85, 0.85, 0.95))
	return l


func _populate_preset_dropdown() -> void:
	# EditorImportPlugin / EditorExportPlatform reads export_presets.cfg via
	# the editor's project settings — the simplest stable surface is to parse
	# export_presets.cfg ourselves and offer Web platform presets.
	var cfg := ConfigFile.new()
	if cfg.load("res://export_presets.cfg") != OK:
		_preset_dropdown.add_item("(no export_presets.cfg found)")
		_preset_dropdown.disabled = true
		return

	var section_idx := 0
	for section in cfg.get_sections():
		if not section.begins_with("preset.") or section.ends_with(".options"):
			continue
		var platform = cfg.get_value(section, "platform", "")
		if String(platform) != "Web":
			continue
		var name = String(cfg.get_value(section, "name", section))
		_preset_dropdown.add_item(name, section_idx)
		_preset_dropdown.set_item_metadata(section_idx, name)
		section_idx += 1

	if section_idx == 0:
		_preset_dropdown.add_item("(no Web presets in project)")
		_preset_dropdown.disabled = true


func _on_browse_pressed() -> void:
	var fd := FileDialog.new()
	fd.access = FileDialog.ACCESS_FILESYSTEM
	fd.file_mode = FileDialog.FILE_MODE_SAVE_FILE
	fd.add_filter("*.zip", "Snapdragon bundle zip")
	fd.current_file = "snapdragon-bundle.zip"
	fd.confirmed.connect(func() -> void:
		_output_path_edit.text = fd.current_path
		fd.queue_free()
	)
	fd.canceled.connect(fd.queue_free)
	add_child(fd)
	fd.popup_centered(Vector2i(720, 480))


func _on_export_pressed() -> void:
	var slug := _slug_edit.text.strip_edges()
	if slug == "" or not slug.match("[a-z0-9][a-z0-9-]*"):
		_set_status("Slug must be lowercase letters, digits, dashes (e.g. 'dodecahedron').", true)
		return
	var display_name := _name_edit.text.strip_edges()
	if display_name == "":
		_set_status("Display name is required.", true)
		return
	if _preset_dropdown.disabled:
		_set_status("No Web export preset available.", true)
		return

	var preset_name: String = String(_preset_dropdown.get_item_metadata(_preset_dropdown.selected))
	var scene_type := _scene_type_edit.text.strip_edges()
	if scene_type == "":
		scene_type = slug
	var description := _desc_edit.text.strip_edges()
	var chromeless := _chromeless_check.button_pressed
	var output_path := _output_path_edit.text.strip_edges()
	if output_path == "":
		_set_status("Output zip path is required.", true)
		return

	_export_btn.disabled = true
	_set_status("Exporting preset '%s'…" % preset_name, false)

	var staging := "user://snapdragon_staging_%d" % Time.get_ticks_msec()
	DirAccess.make_dir_recursive_absolute(ProjectSettings.globalize_path(staging))

	var bundle_dir := staging + "/bundle"
	DirAccess.make_dir_recursive_absolute(ProjectSettings.globalize_path(bundle_dir))

	# Drive the export by shelling out to the running godot binary so we get
	# the same output as the headless `make build-godot` pipeline. Calling
	# into EditorExportPlatform directly is also possible but its Godot 4 API
	# is still considered experimental.
	var godot_exe := OS.get_executable_path()
	var bundle_index := ProjectSettings.globalize_path(bundle_dir + "/index.html")
	var args := [
		"--headless",
		"--export-release", preset_name,
		bundle_index,
	]
	var output: Array = []
	var exit := OS.execute(godot_exe, args, output, true, false)
	if exit != 0:
		_set_status("Godot export failed (exit %d). Check the output console." % exit, true)
		_export_btn.disabled = false
		print_rich("[snapdragon-export] godot output: ", "\n".join(output))
		return

	# Build manifest.json
	var manifest := ManifestBuilder.build({
		"schema_version": MANIFEST_SCHEMA_VERSION,
		"slug":           slug,
		"name":           display_name,
		"description":    description,
		"scene_type":     scene_type,
		"chromeless":     chromeless,
		"godot_version":  Engine.get_version_info().get("string", "unknown"),
	})
	var mf_path := ProjectSettings.globalize_path(staging + "/manifest.json")
	var mf := FileAccess.open(mf_path, FileAccess.WRITE)
	if mf == null:
		_set_status("Could not write manifest.json (%s)." % mf_path, true)
		_export_btn.disabled = false
		return
	mf.store_string(JSON.stringify(manifest, "  "))
	mf.close()

	# v2: emit register.sql so the BFF's upload endpoint can run it inside
	# the install transaction and self-register the new game (rows in
	# games / levels / federation_modules / game_access). The BFF still
	# validates schema_version + slug before executing.
	var sql_path := ProjectSettings.globalize_path(staging + "/register.sql")
	var sql_file := FileAccess.open(sql_path, FileAccess.WRITE)
	if sql_file == null:
		_set_status("Could not write register.sql (%s)." % sql_path, true)
		_export_btn.disabled = false
		return
	sql_file.store_string(ManifestBuilder.to_register_sql(manifest))
	sql_file.close()

	# Pack staging/ into a zip at output_path.
	var abs_output := output_path
	if abs_output.begins_with("user://") or abs_output.begins_with("res://"):
		abs_output = ProjectSettings.globalize_path(abs_output)
	var zip_err := _zip_directory(ProjectSettings.globalize_path(staging), abs_output)
	if zip_err != OK:
		_set_status("Zip failed (Godot error %d)." % zip_err, true)
		_export_btn.disabled = false
		return

	# Best-effort cleanup of staging dir.
	_recursive_delete(ProjectSettings.globalize_path(staging))

	_set_status("✓ Bundle written to %s\nUpload via /api/admin/games/upload (admin UI: /admin/games)." % abs_output, false)
	_export_btn.disabled = false


func _set_status(text: String, error: bool) -> void:
	_status_label.text = text
	_status_label.add_theme_color_override(
		"font_color",
		Color(1.0, 0.45, 0.45) if error else Color(0.55, 0.85, 0.55),
	)


func _zip_directory(src_dir: String, zip_path: String) -> int:
	var packer := ZIPPacker.new()
	var err := packer.open(zip_path)
	if err != OK:
		return err
	err = _zip_directory_recursive(packer, src_dir, "")
	if err != OK:
		packer.close()
		return err
	return packer.close()


func _zip_directory_recursive(packer: ZIPPacker, abs_dir: String, rel_prefix: String) -> int:
	var d := DirAccess.open(abs_dir)
	if d == null:
		return ERR_CANT_OPEN
	d.list_dir_begin()
	while true:
		var name := d.get_next()
		if name == "":
			break
		if name == "." or name == "..":
			continue
		var abs_path := abs_dir + "/" + name
		var rel_path := name if rel_prefix == "" else rel_prefix + "/" + name
		if d.current_is_dir():
			var sub_err := _zip_directory_recursive(packer, abs_path, rel_path)
			if sub_err != OK:
				return sub_err
		else:
			var bytes := FileAccess.get_file_as_bytes(abs_path)
			if FileAccess.get_open_error() != OK:
				return FileAccess.get_open_error()
			var start_err := packer.start_file(rel_path)
			if start_err != OK:
				return start_err
			var write_err := packer.write_file(bytes)
			if write_err != OK:
				return write_err
			var close_err := packer.close_file()
			if close_err != OK:
				return close_err
	d.list_dir_end()
	return OK


func _recursive_delete(abs_dir: String) -> void:
	var d := DirAccess.open(abs_dir)
	if d == null:
		return
	d.list_dir_begin()
	while true:
		var name := d.get_next()
		if name == "":
			break
		if name == "." or name == "..":
			continue
		var abs_path := abs_dir + "/" + name
		if d.current_is_dir():
			_recursive_delete(abs_path)
		else:
			DirAccess.remove_absolute(abs_path)
	d.list_dir_end()
	DirAccess.remove_absolute(abs_dir)
