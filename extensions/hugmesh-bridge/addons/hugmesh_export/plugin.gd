@tool
extends EditorPlugin

# HugMesh Export plugin — single-button workflow that turns the
# current Godot project into a deploy-ready zip the BFF's
# /api/admin/games/upload endpoint can install.
#
# Two surfaces, both invoke the same dialog:
#
#   1. Project → Tools → HugMesh → Export to HugMesh…
#      (long-form, easy to find from any panel).
#
#   2. A toolbar button on the editor's main top-right area
#      (one click, after first-time setup).
#
# The zip the dialog produces contains:
#   • manifest.json        — schema_version 2; declares slug, name,
#                            description, scene_type, features,
#                            supported_events.
#   • register.sql         — INSERT statements the BFF runs inside
#                            the install transaction so the new
#                            game self-registers across the games /
#                            chapters / federation_modules tables.
#   • bundle/              — Godot Web export verbatim (index.html,
#                            index.wasm, index.pck, side wasms, etc).
#   • federation/          — JSON stub the BFF unpacks under
#                            /_remotes/<slug>/ so module federation
#                            picks up the new game without a
#                            frontend rebuild.

const ExportDialog := preload("res://addons/hugmesh_export/export_dialog.gd")

var _menu_button: PopupMenu = null
var _dialog: Window = null
var _toolbar_btn: Button = null


func _enter_tree() -> void:
	# 1) Project → Tools → HugMesh submenu
	_menu_button = PopupMenu.new()
	_menu_button.name = "HugMeshExportMenu"
	_menu_button.add_item("Export to HugMesh…", 0)
	_menu_button.id_pressed.connect(_on_menu_pressed)
	add_tool_submenu_item("HugMesh", _menu_button)

	# 2) Top-right toolbar button — same dialog, one click. The
	#    icon comes from the editor theme so it adapts to dark / light.
	_toolbar_btn = Button.new()
	_toolbar_btn.text = "HugMesh Export"
	_toolbar_btn.tooltip_text = "Build a HugMesh-deployable zip from the current Godot project"
	_toolbar_btn.flat = true
	_toolbar_btn.pressed.connect(_open_export_dialog)
	add_control_to_container(EditorPlugin.CONTAINER_TOOLBAR, _toolbar_btn)


func _exit_tree() -> void:
	remove_tool_menu_item("HugMesh")
	if is_instance_valid(_toolbar_btn):
		remove_control_from_container(EditorPlugin.CONTAINER_TOOLBAR, _toolbar_btn)
		_toolbar_btn.queue_free()
		_toolbar_btn = null
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
	EditorInterface.get_base_control().add_child(_dialog)
	_dialog.popup_centered()
