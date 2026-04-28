@tool
extends EditorPlugin

# Adds "Snapdragon → Export to Snapdragon…" to the editor's tool menu.
# The dialog gathers per-game metadata, runs the Web export to a temp dir,
# packs everything + a manifest.json into <slug>-snapdragon-bundle.zip,
# and hands the zip to the user to upload via /api/admin/games/upload.

const ExportDialog := preload("res://addons/snapdragon_export/export_dialog.gd")

var _menu_button: PopupMenu = null
var _dialog: Window = null


func _enter_tree() -> void:
	# Tool menu: Project → Tools → Snapdragon Export
	_menu_button = PopupMenu.new()
	_menu_button.name = "SnapdragonExportMenu"
	_menu_button.add_item("Export to Snapdragon…", 0)
	_menu_button.id_pressed.connect(_on_menu_pressed)
	add_tool_submenu_item("Snapdragon", _menu_button)


func _exit_tree() -> void:
	remove_tool_menu_item("Snapdragon")
	if is_instance_valid(_dialog):
		_dialog.queue_free()
		_dialog = null


func _on_menu_pressed(id: int) -> void:
	match id:
		0:
			_open_export_dialog()


func _open_export_dialog() -> void:
	if is_instance_valid(_dialog):
		_dialog.popup_centered()
		return

	_dialog = ExportDialog.new()
	get_editor_interface().get_base_control().add_child(_dialog)
	_dialog.popup_centered()
