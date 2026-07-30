# NPC dialogue panel for a produced RPG bundle (hugmesh-rpg-builder).
#
# Pure-GDScript CanvasLayer (mirrors shop_ui.gd — no .tscn Control tree). The
# walk-up proximity Area3D built by room_builder._build_npc registers the NPC
# here (set_active_npc / clear_active_npc); INTERACT (E) via game_bridge then
# toggles the panel. Opening loads the NPC's staged 2D portrait into a
# TextureRect (hidden when the NPC has none), shows name + wrapped dialogue,
# and reports the interaction through game_bridge.talk_npc() so:
#   * the server observes TALK_NPC:<uuid> on the movement echo channel
#     (mirrors SHOP_BUY / ENTER_ROOM), and
#   * the host page receives an SD_DIALOGUE_EVENT carrying ONLY controlled
#     fields — the server-baked npc uuid + a portraitLoaded bool. The
#     free-form NPC name is deliberately NEVER part of that postMessage.
# While open, game_bridge suppresses movement keys (set_dialogue_open).
extends CanvasLayer

var _npc := {}
var _open := false

var _panel: PanelContainer
var _portrait: TextureRect
var _name_label: Label
var _text_label: Label

func _ready() -> void:
    layer = 10
    _build_panel()
    _panel.visible = false

func _build_panel() -> void:
    _panel = PanelContainer.new()
    _panel.name = "Panel"
    _panel.anchor_left = 0.5
    _panel.anchor_top = 1.0
    _panel.anchor_right = 0.5
    _panel.anchor_bottom = 1.0
    _panel.offset_left = -320
    _panel.offset_top = -250
    _panel.offset_right = 320
    _panel.offset_bottom = -20
    add_child(_panel)
    var hbox := HBoxContainer.new()
    _panel.add_child(hbox)
    _portrait = TextureRect.new()
    _portrait.name = "Portrait"
    _portrait.custom_minimum_size = Vector2(190, 190)
    _portrait.expand_mode = TextureRect.EXPAND_IGNORE_SIZE
    _portrait.stretch_mode = TextureRect.STRETCH_KEEP_ASPECT_CENTERED
    hbox.add_child(_portrait)
    var vbox := VBoxContainer.new()
    vbox.size_flags_horizontal = Control.SIZE_EXPAND_FILL
    hbox.add_child(vbox)
    _name_label = Label.new()
    _name_label.name = "NpcName"
    vbox.add_child(_name_label)
    _text_label = Label.new()
    _text_label.name = "NpcText"
    _text_label.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
    _text_label.size_flags_vertical = Control.SIZE_EXPAND_FILL
    vbox.add_child(_text_label)
    var close_btn := Button.new()
    close_btn.text = "Close (Esc)"
    close_btn.pressed.connect(close)
    vbox.add_child(close_btn)

# room_builder wires the proximity Area3D here.
func set_active_npc(npc: Dictionary) -> void:
    _npc = npc

func clear_active_npc() -> void:
    _npc = {}
    if _open:
        close()

func has_active_npc() -> bool:
    return not _npc.is_empty()

func toggle() -> void:
    if _open:
        close()
    else:
        open()

func open() -> void:
    if _npc.is_empty():
        return
    _open = true
    _name_label.text = str(_npc.get("name", ""))
    _text_label.text = str(_npc.get("dialogue", ""))
    var portrait_loaded := false
    var path := str(_npc.get("portrait", ""))
    _portrait.texture = null
    if path != "" and ResourceLoader.exists(path):
        var tex := load(path) as Texture2D
        if tex != null:
            _portrait.texture = tex
            portrait_loaded = true
    _portrait.visible = portrait_loaded
    _panel.visible = true
    _notify_bridge(true)
    var gb := get_tree().get_first_node_in_group("game_bridge")
    if gb != null and gb.has_method("talk_npc"):
        gb.talk_npc(str(_npc.get("uuid", "")), portrait_loaded)

func close() -> void:
    _open = false
    _panel.visible = false
    _notify_bridge(false)

func _notify_bridge(open: bool) -> void:
    var gb := get_tree().get_first_node_in_group("game_bridge")
    if gb != null and gb.has_method("set_dialogue_open"):
        gb.set_dialogue_open(open)

func _unhandled_input(event: InputEvent) -> void:
    if not _open or not (event is InputEventKey) or not event.pressed:
        return
    var key := event as InputEventKey
    if key.keycode == KEY_ESCAPE:
        close()
        get_viewport().set_input_as_handled()
