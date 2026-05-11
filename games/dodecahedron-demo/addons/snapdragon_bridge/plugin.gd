@tool
extends EditorPlugin

# Snapdragon Bridge addon — exposes the bridge-routed networking + the
# multiplayer lobby + the default lobby UI as a reusable Godot library.
# Game projects enable this plugin in project.godot's [editor_plugins]
# block; runtime scripts can then reference
# `res://addons/snapdragon_bridge/...` directly.
#
# Nothing to register in _enter_tree at the moment — the GDExtension's
# Rust classes (HostBridge) are loaded automatically by Godot when the
# .gdextension file is present. This script exists so the addon shows
# up in the editor's Plugin list and can be enabled/disabled per project.

func _enter_tree() -> void:
	pass

func _exit_tree() -> void:
	pass
