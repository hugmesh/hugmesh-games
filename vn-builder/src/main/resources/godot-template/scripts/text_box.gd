# Lightweight TextBox modeled after godot-2d-visual-novel's TextBox.gd.
# Renders a speaker name + body line + choice buttons.
extends Panel

@export var speaker_label_path: NodePath
@export var body_label_path: NodePath
@export var choices_root_path: NodePath

func _ready() -> void:
    pass

func show_line(speaker: String, text: String, choices: Array) -> void:
    if speaker_label_path != NodePath():
        var sl := get_node_or_null(speaker_label_path)
        if sl != null and sl.has_method("set_text"):
            sl.set_text(speaker)
    if body_label_path != NodePath():
        var bl := get_node_or_null(body_label_path)
        if bl != null and bl.has_method("set_text"):
            bl.set_text(text)
    if choices_root_path != NodePath():
        var root := get_node_or_null(choices_root_path)
        if root != null:
            for child in root.get_children():
                child.queue_free()
            for choice in choices:
                var btn := Button.new()
                btn.text = choice.label
                btn.pressed.connect(func(): get_parent().choose(choice.target_scene_id))
                root.add_child(btn)
