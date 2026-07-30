# Runtime room constructor for a produced RPG bundle (hugmesh-rpg-builder).
#
# Each generated room script bakes a `const ROOM := {...}` describing its theme,
# floor size, exits (with pre-computed portal positions + arrival spawns), props,
# NPCs, items, town buildings and (for interior shop floors) shop stock. That
# data is deterministic — the renderer derives everything from the DB rows +
# a single shared direction table, never from slug conventions or randomness at
# runtime. This builder turns that data into real 3D geometry: a textured
# StaticBody3D floor, perimeter walls (gapped at exit directions), a themed
# WorldEnvironment + DirectionalLight, seeded theme props, and Area3D portals.
#
# Everything is plain MeshInstance3D + StaticBody3D (no CSG — the GL Compatibility
# WASM target rebuilds CSG meshes per instantiation). Torch OmniLights are capped
# and shadowless; only the DirectionalLight casts shadows.
extends RefCounted

const Exits := preload("res://scripts/exits.gd")

const MAX_TORCHES := 8
const BIOME_ROOT := "res://assets/biomes/"

# Per-theme look: background + ambient + fog + directional light. Cave and
# dungeon are DELIBERATELY far apart in colour (warm brown/green vs cold
# blue-grey) so the e2e per-biome mean-RGB assertion separates them.
const THEMES := {
    "plain": {
        "bg": Color(0.45, 0.5, 0.58), "ambient": Color(0.55, 0.55, 0.6),
        "fog": false, "light": Color(1.0, 0.98, 0.95), "energy": 1.1, "ceiling": false,
    },
    "desert": {
        "bg": Color(0.85, 0.72, 0.45), "ambient": Color(0.9, 0.78, 0.55),
        "fog": false, "light": Color(1.0, 0.92, 0.72), "energy": 1.5, "ceiling": false,
    },
    "forest": {
        "bg": Color(0.28, 0.45, 0.28), "ambient": Color(0.35, 0.5, 0.32),
        "fog": false, "light": Color(0.8, 0.95, 0.75), "energy": 1.0, "ceiling": false,
    },
    "dungeon": {
        "bg": Color(0.05, 0.07, 0.12), "ambient": Color(0.10, 0.13, 0.2),
        "fog": true, "light": Color(0.45, 0.55, 0.8), "energy": 0.5, "ceiling": true,
    },
    "cave": {
        "bg": Color(0.14, 0.10, 0.05), "ambient": Color(0.16, 0.14, 0.08),
        "fog": true, "light": Color(0.7, 0.6, 0.35), "energy": 0.5, "ceiling": true,
    },
    "town": {
        "bg": Color(0.5, 0.62, 0.78), "ambient": Color(0.6, 0.62, 0.62),
        "fog": false, "light": Color(1.0, 0.96, 0.88), "energy": 1.2, "ceiling": false,
    },
    "castle": {
        "bg": Color(0.42, 0.46, 0.55), "ambient": Color(0.45, 0.48, 0.55),
        "fog": false, "light": Color(0.85, 0.88, 1.0), "energy": 0.9, "ceiling": false,
    },
}

static func _theme(name: String) -> Dictionary:
    return THEMES.get(name, THEMES["plain"])

static func build(room: Node3D, data: Dictionary) -> void:
    var theme: String = data.get("theme", "plain")
    var interior: bool = data.get("interior", false)
    var floor_size: float = data.get("floor_size", 28.0)
    room.set_meta("room_uuid", data.get("uuid", ""))
    room.set_meta("entry_spawns", data.get("entry_spawns", {}))
    room.set_meta("default_spawn", data.get("default_spawn", Vector3(0, 1, 0)))

    _build_environment(room, theme)
    _build_floor(room, theme, floor_size)
    _build_walls(room, theme, floor_size, data.get("wall_gaps", []), interior or _theme(theme)["ceiling"])
    _build_title(room, data.get("name", ""))
    _build_props(room, theme, floor_size, int(data.get("seed", 0)))

    for ex in data.get("exits", []):
        _build_portal(room, ex)
    var exit_door: Variant = data.get("exit_door", null)
    if exit_door is Dictionary:
        _build_portal(room, exit_door)
    for b in data.get("buildings", []):
        _build_building(room, theme, b)
    for n in data.get("npcs", []):
        _build_npc(room, n)
    for it in data.get("items", []):
        _build_item(room, it)
    var shop: Variant = data.get("shop", null)
    if shop is Dictionary:
        _build_shop(room, shop)
    if data.get("rest", false):
        _build_rest(room)

# --- environment + lighting -------------------------------------------------

static func _build_environment(room: Node3D, theme: String) -> void:
    var t := _theme(theme)
    var env := Environment.new()
    env.background_mode = Environment.BG_COLOR
    env.background_color = t["bg"]
    env.ambient_light_source = Environment.AMBIENT_SOURCE_COLOR
    env.ambient_light_color = t["ambient"]
    env.ambient_light_energy = 1.0
    if t["fog"]:
        env.fog_enabled = true
        env.fog_light_color = t["bg"]
        env.fog_density = 0.04
    var we := WorldEnvironment.new()
    we.name = "WorldEnvironment"
    we.environment = env
    room.add_child(we)

    var sun := DirectionalLight3D.new()
    sun.name = "Sun"
    sun.light_color = t["light"]
    sun.light_energy = t["energy"]
    sun.rotation = Vector3(deg_to_rad(-55.0), deg_to_rad(35.0), 0.0)
    sun.shadow_enabled = true
    room.add_child(sun)

# --- floor + walls ----------------------------------------------------------

static func _biome_material(theme: String, kind: String, uv: float) -> StandardMaterial3D:
    var mat := StandardMaterial3D.new()
    var albedo := BIOME_ROOT + theme + "/" + kind + "_albedo.jpg"
    var normal := BIOME_ROOT + theme + "/" + kind + "_normal.jpg"
    if ResourceLoader.exists(albedo):
        mat.albedo_texture = load(albedo)
        mat.uv1_scale = Vector3(uv, uv, uv)
    else:
        # Graceful fallback so a missing/plain theme is still lit, never black.
        mat.albedo_color = _theme(theme)["ambient"]
    if ResourceLoader.exists(normal):
        mat.normal_enabled = true
        mat.normal_texture = load(normal)
    return mat

static func _build_floor(room: Node3D, theme: String, floor_size: float) -> void:
    var body := StaticBody3D.new()
    body.name = "Floor"
    var mi := MeshInstance3D.new()
    var mesh := BoxMesh.new()
    mesh.size = Vector3(floor_size, 0.5, floor_size)
    mi.mesh = mesh
    mi.material_override = _biome_material(theme, "floor", floor_size / 4.0)
    body.add_child(mi)
    var col := CollisionShape3D.new()
    var box := BoxShape3D.new()
    box.size = Vector3(floor_size, 0.5, floor_size)
    col.shape = box
    body.add_child(col)
    body.position = Vector3(0, -0.25, 0)
    room.add_child(body)

static func _build_walls(room: Node3D, theme: String, floor_size: float,
        gaps: Array, ceiling: bool) -> void:
    var h := floor_size / 2.0
    var wall_h := 6.0
    var mat := _biome_material(theme, "wall", floor_size / 4.0)
    var sides := {
        "N": Vector3(0, wall_h / 2.0, -h),
        "S": Vector3(0, wall_h / 2.0, h),
        "E": Vector3(h, wall_h / 2.0, 0),
        "W": Vector3(-h, wall_h / 2.0, 0),
    }
    for dir in sides.keys():
        if gaps.has(dir):
            continue
        var horizontal: bool = (dir == "N" or dir == "S")
        var size: Vector3 = Vector3(floor_size, wall_h, 0.5) if horizontal else Vector3(0.5, wall_h, floor_size)
        _add_box(room, "Wall_" + str(dir), sides[dir], size, mat)
    if ceiling:
        _add_box(room, "Ceiling", Vector3(0, wall_h, 0),
            Vector3(floor_size, 0.5, floor_size), mat)

static func _add_box(parent: Node3D, name: String, pos: Vector3, size: Vector3,
        mat: Material) -> StaticBody3D:
    var body := StaticBody3D.new()
    body.name = name
    body.position = pos
    var mi := MeshInstance3D.new()
    var mesh := BoxMesh.new()
    mesh.size = size
    mi.mesh = mesh
    if mat != null:
        mi.material_override = mat
    body.add_child(mi)
    var col := CollisionShape3D.new()
    var shape := BoxShape3D.new()
    shape.size = size
    col.shape = shape
    body.add_child(col)
    parent.add_child(body)
    return body

static func _build_title(room: Node3D, name: String) -> void:
    if name == "":
        return
    var label := Label3D.new()
    label.name = "RoomTitle"
    label.text = name
    label.font_size = 96
    label.position = Vector3(0, 5.0, 0)
    label.billboard = BaseMaterial3D.BILLBOARD_ENABLED
    room.add_child(label)

# --- deterministic theme props ---------------------------------------------

static func _build_props(room: Node3D, theme: String, floor_size: float, seed: int) -> void:
    var rng := RandomNumberGenerator.new()
    rng.seed = seed
    var reach := floor_size / 2.0 - 3.0
    match theme:
        "desert": _props_desert(room, rng, reach)
        "forest": _props_forest(room, rng, reach)
        "dungeon": _props_dungeon(room, rng, reach)
        "cave": _props_cave(room, rng, reach)
        "castle": _props_castle(room, rng, reach)
        _: pass

static func _rand_xz(rng: RandomNumberGenerator, reach: float) -> Vector3:
    return Vector3(rng.randf_range(-reach, reach), 0.0, rng.randf_range(-reach, reach))

static func _cylinder(color: Color, top: float, bottom: float, height: float) -> MeshInstance3D:
    var mi := MeshInstance3D.new()
    var m := CylinderMesh.new()
    m.top_radius = top
    m.bottom_radius = bottom
    m.height = height
    mi.mesh = m
    var mat := StandardMaterial3D.new()
    mat.albedo_color = color
    mi.material_override = mat
    return mi

static func _props_desert(room: Node3D, rng: RandomNumberGenerator, reach: float) -> void:
    for i in range(6):
        var cactus := _cylinder(Color(0.2, 0.45, 0.25), 0.25, 0.3, 2.5)
        var p := _rand_xz(rng, reach)
        cactus.position = p + Vector3(0, 1.25, 0)
        cactus.name = "Cactus%d" % i
        room.add_child(cactus)
    for i in range(4):
        var rock := MeshInstance3D.new()
        var sm := SphereMesh.new()
        sm.radius = rng.randf_range(0.4, 0.9)
        sm.height = sm.radius * 1.6
        rock.mesh = sm
        var mat := StandardMaterial3D.new()
        mat.albedo_color = Color(0.6, 0.5, 0.4)
        rock.material_override = mat
        rock.position = _rand_xz(rng, reach) + Vector3(0, 0.3, 0)
        rock.name = "Rock%d" % i
        room.add_child(rock)

static func _props_forest(room: Node3D, rng: RandomNumberGenerator, reach: float) -> void:
    for i in range(7):
        var trunk := _cylinder(Color(0.35, 0.22, 0.12), 0.2, 0.3, 3.0)
        var base := _rand_xz(rng, reach)
        trunk.position = base + Vector3(0, 1.5, 0)
        trunk.name = "Trunk%d" % i
        room.add_child(trunk)
        var canopy := MeshInstance3D.new()
        var cone := CylinderMesh.new()
        cone.top_radius = 0.0
        cone.bottom_radius = 1.4
        cone.height = 2.4
        canopy.mesh = cone
        var mat := StandardMaterial3D.new()
        mat.albedo_color = Color(0.15, 0.45, 0.18)
        canopy.material_override = mat
        canopy.position = base + Vector3(0, 3.8, 0)
        canopy.name = "Canopy%d" % i
        room.add_child(canopy)

static func _props_dungeon(room: Node3D, rng: RandomNumberGenerator, reach: float) -> void:
    for i in range(4):
        var pillar := _cylinder(Color(0.4, 0.42, 0.48), 0.5, 0.5, 5.5)
        pillar.position = _rand_xz(rng, reach) + Vector3(0, 2.75, 0)
        pillar.name = "Pillar%d" % i
        room.add_child(pillar)
    _scatter_torches(room, rng, reach, Color(0.55, 0.65, 1.0))

static func _props_cave(room: Node3D, rng: RandomNumberGenerator, reach: float) -> void:
    for i in range(4):
        var stal := _cylinder(Color(0.3, 0.26, 0.2), 0.0, 0.5, 3.0)
        stal.position = _rand_xz(rng, reach) + Vector3(0, 4.5, 0)
        stal.rotation = Vector3(PI, 0, 0)
        stal.name = "Stalactite%d" % i
        room.add_child(stal)
    for i in range(4):
        var mite := _cylinder(Color(0.32, 0.28, 0.22), 0.0, 0.5, 2.5)
        mite.position = _rand_xz(rng, reach) + Vector3(0, 1.25, 0)
        mite.name = "Stalagmite%d" % i
        room.add_child(mite)

static func _props_castle(room: Node3D, rng: RandomNumberGenerator, reach: float) -> void:
    for i in range(6):
        var battlement := MeshInstance3D.new()
        var box := BoxMesh.new()
        box.size = Vector3(1.0, 1.0, 1.0)
        battlement.mesh = box
        var mat := StandardMaterial3D.new()
        mat.albedo_color = Color(0.5, 0.52, 0.58)
        battlement.material_override = mat
        battlement.position = Vector3(-reach + i * (reach * 2.0 / 5.0), 6.0, -reach)
        battlement.name = "Crenel%d" % i
        room.add_child(battlement)
    for i in range(2):
        var banner := MeshInstance3D.new()
        var q := QuadMesh.new()
        q.size = Vector2(1.2, 3.0)
        banner.mesh = q
        var mat := StandardMaterial3D.new()
        mat.albedo_color = Color(0.6, 0.12, 0.15)
        banner.material_override = mat
        banner.position = Vector3(-reach + i * reach * 2.0, 3.0, -reach + 0.6)
        banner.name = "Banner%d" % i
        room.add_child(banner)
    _scatter_torches(room, rng, reach, Color(1.0, 0.7, 0.35))

static func _scatter_torches(room: Node3D, rng: RandomNumberGenerator, reach: float,
        color: Color) -> void:
    for i in range(MAX_TORCHES):
        var light := OmniLight3D.new()
        light.light_color = color
        light.light_energy = 2.0
        light.omni_range = 8.0
        light.shadow_enabled = false
        light.position = _rand_xz(rng, reach) + Vector3(0, 2.5, 0)
        light.name = "Torch%d" % i
        room.add_child(light)

# --- portals ----------------------------------------------------------------

static func _build_portal(room: Node3D, ex: Dictionary) -> void:
    var dir: String = ex.get("dir", "TELEPORT")
    var area := Area3D.new()
    area.name = "Portal_" + dir
    area.position = ex.get("portal", Vector3(0, 1.0, 0))
    var col := CollisionShape3D.new()
    var box := BoxShape3D.new()
    box.size = Vector3(3.0, 3.0, 3.0)
    col.shape = box
    area.add_child(col)
    # Door-textured frame.
    var frame := MeshInstance3D.new()
    var fm := BoxMesh.new()
    fm.size = Vector3(2.4, 3.0, 0.3)
    frame.mesh = fm
    var dmat := StandardMaterial3D.new()
    var door := "res://assets/biomes/_shared/door_albedo.jpg"
    if ResourceLoader.exists(door):
        dmat.albedo_texture = load(door)
    else:
        dmat.albedo_color = Color(0.35, 0.22, 0.12)
    frame.material_override = dmat
    area.add_child(frame)
    # Glowing marker.
    var marker := MeshInstance3D.new()
    var sm := SphereMesh.new()
    sm.radius = 0.35
    sm.height = 0.7
    marker.mesh = sm
    var mm := StandardMaterial3D.new()
    mm.albedo_color = Color(0.6, 0.9, 1.0)
    mm.emission_enabled = true
    mm.emission = Color(0.5, 0.85, 1.0)
    mm.emission_energy_multiplier = 4.0
    marker.material_override = mm
    marker.position = Vector3(0, 1.8, 0)
    area.add_child(marker)
    # Label.
    var label := Label3D.new()
    label.text = dir + " -> " + str(ex.get("target_name", ""))
    label.font_size = 48
    label.position = Vector3(0, 2.6, 0)
    label.billboard = BaseMaterial3D.BILLBOARD_ENABLED
    area.add_child(label)

    area.set_meta("target", ex.get("target", ""))
    area.set_meta("arrival_dir", Exits.reciprocal(dir) if dir != "EXIT_DOOR" else "EXIT_DOOR")
    area.body_entered.connect(_on_portal_body.bind(area, room))
    room.add_child(area)

static func _on_portal_body(body: Node, area: Area3D, room: Node3D) -> void:
    # Only the LOCAL player triggers a transition — remote avatars (parented at
    # the persistent World root) must never teleport your client (CRITIQUE mustFix).
    if not body.is_in_group("local_player"):
        return
    var target: String = str(area.get_meta("target", ""))
    if target == "":
        return
    # Disarm so the arrival spawn (just inside the reciprocal doorway) doesn't
    # instantly re-trigger; the deferred call keeps us out of the physics flush.
    area.set_deferred("monitoring", false)
    var world := room.get_parent()
    if world != null and world.has_method("enter_room_by_uuid"):
        world.call_deferred("enter_room_by_uuid", target, str(area.get_meta("arrival_dir", "")))

# --- buildings (town host rooms) -------------------------------------------

const KIND_GLYPH := {
    "item_shop": "[Items]", "weapon_shop": "[Weapons]", "armor_shop": "[Armor]",
    "magic_shop": "[Magic]", "accessory_shop": "[Accessories]", "inn": "[Inn]",
    "tavern": "[Tavern]", "church": "[Church]", "house": "[House]",
}

static func _build_building(room: Node3D, theme: String, b: Dictionary) -> void:
    var pos: Vector3 = b.get("origin", Vector3.ZERO)
    var w: float = float(b.get("w", 1)) * 4.0
    var d: float = float(b.get("d", 1)) * 4.0
    var floors: int = int(b.get("floors", 1))
    var mat := _biome_material("town", "wall", 2.0)
    var holder := Node3D.new()
    holder.name = "Building_" + str(b.get("slug", "b"))
    holder.position = pos
    room.add_child(holder)
    for f in range(floors):
        _add_box(holder, "FloorBox%d" % f, Vector3(0, 1.5 + f * 3.0, 0),
            Vector3(w, 3.0, d), mat)
    # Prism roof.
    var roof := MeshInstance3D.new()
    var prism := PrismMesh.new()
    prism.size = Vector3(w, 2.0, d)
    roof.mesh = prism
    var rmat := StandardMaterial3D.new()
    rmat.albedo_color = Color(0.5, 0.25, 0.2)
    roof.material_override = rmat
    roof.position = Vector3(0, floors * 3.0 + 1.0, 0)
    holder.add_child(roof)
    # Hanging sign.
    var sign := Label3D.new()
    sign.text = str(KIND_GLYPH.get(b.get("kind", "house"), "")) + " " + str(b.get("name", ""))
    sign.font_size = 48
    sign.position = Vector3(0, 3.2, d / 2.0 + 0.2)
    sign.billboard = BaseMaterial3D.BILLBOARD_ENABLED
    holder.add_child(sign)
    # Door portal into the building's floor-0 interior room.
    var portal := {
        "dir": "EXIT_DOOR",
        "target": b.get("door_target", ""),
        "target_name": b.get("name", ""),
        "portal": pos + Vector3(0, 1.5, d / 2.0 + 0.4),
    }
    _build_portal(room, portal)

# --- NPCs + items -----------------------------------------------------------

static func _build_npc(room: Node3D, n: Dictionary) -> void:
    var holder := Area3D.new()
    holder.name = "Npc_" + str(n.get("slug", "npc"))
    holder.position = n.get("pos", Vector3(0, 0, 0))
    var body := _cylinder(Color(0.5, 0.4, 0.6), 0.2, 0.4, 1.5)
    body.position = Vector3(0, 0.8, 0)
    holder.add_child(body)
    var head := MeshInstance3D.new()
    var sm := SphereMesh.new()
    sm.radius = 0.22
    sm.height = 0.44
    head.mesh = sm
    var hmat := StandardMaterial3D.new()
    hmat.albedo_color = Color(0.9, 0.78, 0.66)
    head.material_override = hmat
    head.position = Vector3(0, 1.75, 0)
    holder.add_child(head)
    var name_label := Label3D.new()
    name_label.text = str(n.get("name", ""))
    name_label.font_size = 40
    name_label.position = Vector3(0, 2.3, 0)
    name_label.billboard = BaseMaterial3D.BILLBOARD_ENABLED
    holder.add_child(name_label)
    var col := CollisionShape3D.new()
    var shape := SphereShape3D.new()
    shape.radius = 3.0
    col.shape = shape
    holder.add_child(col)
    # Walk-up proximity registers the NPC with the DialogueUI panel
    # (shop-trigger pattern); INTERACT (E) via game_bridge then opens it.
    # The old always-visible floating dialogue Label3D is gone — the
    # panel (with the 2D portrait) is the dialogue surface now.
    var npc := {
        "uuid": str(n.get("uuid", "")),
        "name": str(n.get("name", "")),
        "dialogue": str(n.get("dialogue", "")),
        "portrait": str(n.get("portrait", "")),
    }
    holder.body_entered.connect(func(body_):
        if not body_.is_in_group("local_player"):
            return
        var ui := _dialogue_ui(room)
        if ui != null and ui.has_method("set_active_npc"):
            ui.set_active_npc(npc))
    holder.body_exited.connect(func(body_):
        if not body_.is_in_group("local_player"):
            return
        var ui := _dialogue_ui(room)
        if ui != null and ui.has_method("clear_active_npc"):
            ui.clear_active_npc())
    room.add_child(holder)

static func _dialogue_ui(room: Node3D) -> Node:
    var world := room.get_parent()
    if world == null:
        return null
    return world.get_node_or_null("DialogueUI")

static func _build_item(room: Node3D, it: Dictionary) -> void:
    var holder := Node3D.new()
    holder.name = "Item_" + str(it.get("slug", "item"))
    holder.position = it.get("pos", Vector3(0, 1.0, 0))
    var mi := MeshInstance3D.new()
    var box := BoxMesh.new()
    box.size = Vector3(0.5, 0.5, 0.5)
    mi.mesh = box
    var mat := StandardMaterial3D.new()
    mat.albedo_color = Color(0.9, 0.85, 0.4)
    mat.emission_enabled = true
    mat.emission = Color(0.8, 0.75, 0.3)
    mat.emission_energy_multiplier = 1.5
    mi.material_override = mat
    holder.add_child(mi)
    var label := Label3D.new()
    label.text = str(it.get("name", ""))
    label.font_size = 32
    label.position = Vector3(0, 0.8, 0)
    label.billboard = BaseMaterial3D.BILLBOARD_ENABLED
    holder.add_child(label)
    room.add_child(holder)

# --- shops (interior shop floors) ------------------------------------------

static func _build_shop(room: Node3D, shop: Dictionary) -> void:
    # Counter.
    var counter := _add_box(room, "Counter", Vector3(0, 0.6, -3.0),
        Vector3(4.0, 1.2, 0.8), null)
    # Shopkeeper behind it.
    var keeper := _cylinder(Color(0.4, 0.3, 0.5), 0.2, 0.4, 1.6)
    keeper.position = Vector3(0, 0.85, -4.2)
    keeper.name = "Shopkeeper"
    room.add_child(keeper)
    # ShopTrigger: entering it registers the stock with the ShopUI; INTERACT
    # (E) then opens the panel. game_bridge suppresses movement while open.
    var trigger := Area3D.new()
    trigger.name = "ShopTrigger"
    trigger.position = Vector3(0, 1.0, -1.5)
    var col := CollisionShape3D.new()
    var box := BoxShape3D.new()
    box.size = Vector3(5.0, 3.0, 4.0)
    col.shape = box
    trigger.add_child(col)
    trigger.set_meta("stock", shop.get("stock", []))
    trigger.set_meta("shop_name", shop.get("name", "Shop"))
    trigger.body_entered.connect(_on_shop_enter.bind(trigger, room))
    trigger.body_exited.connect(_on_shop_exit.bind(room))
    room.add_child(trigger)

static func _shop_ui(room: Node3D) -> Node:
    var world := room.get_parent()
    if world == null:
        return null
    return world.get_node_or_null("ShopUI")

static func _on_shop_enter(body: Node, trigger: Area3D, room: Node3D) -> void:
    if not body.is_in_group("local_player"):
        return
    var ui := _shop_ui(room)
    if ui != null and ui.has_method("set_active_shop"):
        ui.set_active_shop(trigger.get_meta("stock", []), str(trigger.get_meta("shop_name", "Shop")))

static func _on_shop_exit(body: Node, room: Node3D) -> void:
    if not body.is_in_group("local_player"):
        return
    var ui := _shop_ui(room)
    if ui != null and ui.has_method("clear_active_shop"):
        ui.clear_active_shop()

# Inn / tavern / church rest: the one BFF-LIVE economy beat. Entering the rest
# zone asks game_bridge to PUT the character back to full HP/MP via the
# HostBridge.request_api seam (emits a real `character` SSE the HUD + e2e see).
static func _build_rest(room: Node3D) -> void:
    var bed := _add_box(room, "RestBed", Vector3(0, 0.4, -3.0),
        Vector3(2.0, 0.8, 3.5), null)
    var label := Label3D.new()
    label.text = "Rest"
    label.font_size = 40
    label.position = Vector3(0, 1.2, -3.0)
    label.billboard = BaseMaterial3D.BILLBOARD_ENABLED
    room.add_child(label)
    var trigger := Area3D.new()
    trigger.name = "RestTrigger"
    trigger.position = Vector3(0, 1.0, -1.5)
    var col := CollisionShape3D.new()
    var box := BoxShape3D.new()
    box.size = Vector3(4.0, 3.0, 4.0)
    col.shape = box
    trigger.add_child(col)
    trigger.body_entered.connect(func(body):
        if not body.is_in_group("local_player"):
            return
        var gb := room.get_tree().get_first_node_in_group("game_bridge")
        if gb != null and gb.has_method("rest_heal"):
            gb.rest_heal())
    room.add_child(trigger)
