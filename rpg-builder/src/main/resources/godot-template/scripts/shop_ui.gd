# Shop panel for a produced RPG bundle (hugmesh-rpg-builder).
#
# Pure-GDScript CanvasLayer (no .tscn Control tree — see scene_loader.gd's
# overlay precedent). Shops are GAME-LOCAL v1 (RESOLUTION 8): gold + inventory
# live here, starting at 200 gold. Every buy/sell ALSO routes a control through
# game_bridge (SHOP_BUY/SHOP_SELL + SD_SHOP_EVENT) so the transaction is
# server-observed and visible in a second player's iframe.
extends CanvasLayer

const START_GOLD := 200

var _gold := START_GOLD
var _inventory := {}        # item slug -> qty owned
var _stock: Array = []      # active shop stock rows
var _shop_name := ""
var _open := false

var _panel: PanelContainer
var _list: ItemList
var _gold_label: Label
var _title_label: Label

func _ready() -> void:
    layer = 10
    _build_panel()
    hide_panel()

func _build_panel() -> void:
    _panel = PanelContainer.new()
    _panel.name = "Panel"
    _panel.anchor_left = 0.5
    _panel.anchor_top = 0.5
    _panel.anchor_right = 0.5
    _panel.anchor_bottom = 0.5
    _panel.offset_left = -240
    _panel.offset_top = -180
    _panel.offset_right = 240
    _panel.offset_bottom = 180
    add_child(_panel)
    var vbox := VBoxContainer.new()
    _panel.add_child(vbox)
    _title_label = Label.new()
    _title_label.text = "Shop"
    vbox.add_child(_title_label)
    _gold_label = Label.new()
    _gold_label.text = "Gold: %d" % _gold
    vbox.add_child(_gold_label)
    _list = ItemList.new()
    _list.custom_minimum_size = Vector2(460, 220)
    vbox.add_child(_list)
    var buttons := HBoxContainer.new()
    vbox.add_child(buttons)
    var buy_btn := Button.new()
    buy_btn.text = "Buy (Enter)"
    buy_btn.pressed.connect(_on_buy)
    buttons.add_child(buy_btn)
    var sell_btn := Button.new()
    sell_btn.text = "Sell"
    sell_btn.pressed.connect(_on_sell)
    buttons.add_child(sell_btn)
    var close_btn := Button.new()
    close_btn.text = "Close (Esc)"
    close_btn.pressed.connect(hide_panel)
    buttons.add_child(close_btn)

func set_active_shop(stock: Array, shop_name: String) -> void:
    _stock = stock
    _shop_name = shop_name

func clear_active_shop() -> void:
    _stock = []
    _shop_name = ""
    if _open:
        hide_panel()

func has_active_shop() -> bool:
    return _stock.size() > 0

func toggle() -> void:
    if _open:
        hide_panel()
    else:
        show_panel()

func show_panel() -> void:
    if _stock.is_empty():
        return
    _open = true
    _title_label.text = _shop_name
    _refresh_list()
    _panel.visible = true
    _notify_bridge(true)

func hide_panel() -> void:
    _open = false
    _panel.visible = false
    _notify_bridge(false)

func _refresh_list() -> void:
    _list.clear()
    for row in _stock:
        var slug := str(row.get("slug", ""))
        var owned := int(_inventory.get(slug, 0))
        _list.add_item("%s  buy %d / sell %d  (owned %d)" % [
            str(row.get("name", slug)), int(row.get("buy", 0)),
            int(row.get("sell", 0)), owned])
    _gold_label.text = "Gold: %d" % _gold

func _selected_row() -> Dictionary:
    var sel := _list.get_selected_items()
    if sel.is_empty():
        return {}
    var idx := sel[0]
    if idx < 0 or idx >= _stock.size():
        return {}
    return _stock[idx]

func _on_buy() -> void:
    var row := _selected_row()
    if row.is_empty():
        return
    var price := int(row.get("buy", 0))
    if _gold < price:
        return
    var slug := str(row.get("slug", ""))
    var before := _gold
    _gold -= price
    _inventory[slug] = int(_inventory.get(slug, 0)) + 1
    _refresh_list()
    _transaction("buy", slug, before, _gold, 1)

func _on_sell() -> void:
    var row := _selected_row()
    if row.is_empty():
        return
    var slug := str(row.get("slug", ""))
    if int(_inventory.get(slug, 0)) <= 0:
        return
    var before := _gold
    _gold += int(row.get("sell", 0))
    _inventory[slug] = int(_inventory[slug]) - 1
    _refresh_list()
    _transaction("sell", slug, before, _gold, 1)

func _transaction(kind: String, slug: String, before: int, after: int, qty: int) -> void:
    var gb := get_tree().get_first_node_in_group("game_bridge")
    if gb != null and gb.has_method("shop_transaction"):
        gb.shop_transaction(kind, slug, before, after, qty)

func _notify_bridge(open: bool) -> void:
    var gb := get_tree().get_first_node_in_group("game_bridge")
    if gb != null and gb.has_method("set_shop_open"):
        gb.set_shop_open(open)

func _unhandled_input(event: InputEvent) -> void:
    if not _open or not (event is InputEventKey) or not event.pressed:
        return
    var key := event as InputEventKey
    if key.keycode == KEY_ESCAPE:
        hide_panel()
        get_viewport().set_input_as_handled()
    elif key.keycode == KEY_ENTER or key.keycode == KEY_KP_ENTER:
        _on_buy()
        get_viewport().set_input_as_handled()
