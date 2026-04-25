extends CharacterBody3D

const SPEED         := 5.0
const JUMP_VELOCITY := 6.5
const GRAVITY       := 12.0
const DROP_HEIGHT   := 14.0

# Spell colour / scale defaults per slot — overridden by API spell data when available.
# Keep parallel order with character_skill_slots 1..5 in the DB seed.
const SPELL_COLORS := [
	Color(1.0, 0.4, 0.1),    # 1 fire
	Color(0.5, 0.85, 1.0),   # 2 ice
	Color(0.95, 0.95, 0.4),  # 3 lightning
	Color(0.55, 0.4, 0.25),  # 4 earth
	Color(1.0, 0.95, 0.7),   # 5 light
]
const SPELL_NAMES := ["Fireball", "Ice Shard", "Thunder Bolt", "Stone Spike", "Holy Light"]

var _game_bridge: Node = null
var _host_bridge: Node = null
var _sse_dir       := Vector3.ZERO
var _pending_jump  := false
var _drop_started  := false
var _intro_active  := false
var _hand_anchor: Node3D = null
var _equipped_mesh: MeshInstance3D = null

func _ready() -> void:
	# Migration step (PLAN section G follow-up): prefer the Rust
	# HostBridge node if the scene wired one up — its `movement_received`
	# signal carries the same payload as GameBridge.state_updated, just
	# JSON-encoded so the bridge stays portable across Godot snapshots.
	# Fall back to the legacy GameBridge for scenes that haven't been
	# migrated yet OR if the GDExtension failed to load (e.g. no Rust
	# binary in the bundle's bin/).
	_host_bridge = get_parent().get_node_or_null("HostBridge")
	if _host_bridge != null:
		_host_bridge.movement_received.connect(_on_movement_json)
	else:
		_game_bridge = get_parent().get_node_or_null("GameBridge")
		if _game_bridge:
			_game_bridge.state_updated.connect(_on_state_updated)
		else:
			push_warning("PlayerController: neither HostBridge nor GameBridge found under parent")
	_setup_hand_anchor()

# Build a Marker3D-equivalent on the player at shoulder height that the equipped
# weapon mesh attaches to. Position is relative so it follows the player as it moves.
func _setup_hand_anchor() -> void:
	_hand_anchor = Node3D.new()
	_hand_anchor.name = "HandAnchor"
	# Right-hand offset: forward, slightly right, around chest height.
	_hand_anchor.position = Vector3(0.45, 0.6, -0.3)
	add_child(_hand_anchor)

# Called by scene_loader after equipment is fetched from the API. `meshType` is
# a hint — "sword", "wand", "bow" — and we generate a basic mesh accordingly.
func equip_visual(meshType: String) -> void:
	if is_instance_valid(_equipped_mesh):
		_equipped_mesh.queue_free()
	_equipped_mesh = MeshInstance3D.new()
	var mat := StandardMaterial3D.new()
	mat.metallic = 0.4
	match meshType:
		"sword":
			var blade := BoxMesh.new()
			blade.size = Vector3(0.06, 0.9, 0.06)
			_equipped_mesh.mesh = blade
			mat.albedo_color = Color(0.78, 0.82, 0.88)
		"wand", "staff":
			var rod := CylinderMesh.new()
			rod.top_radius = 0.04; rod.bottom_radius = 0.04
			rod.height = 1.0
			_equipped_mesh.mesh = rod
			mat.albedo_color = Color(0.45, 0.28, 0.16)
			mat.emission_enabled = true
			mat.emission = Color(0.6, 0.4, 1.0)
			mat.emission_energy_multiplier = 0.6
		"bow":
			var arc := TorusMesh.new()
			arc.inner_radius = 0.32; arc.outer_radius = 0.36
			_equipped_mesh.mesh = arc
			mat.albedo_color = Color(0.55, 0.35, 0.18)
		_:
			var fallback := SphereMesh.new()
			fallback.radius = 0.1; fallback.height = 0.2
			_equipped_mesh.mesh = fallback
			mat.albedo_color = Color(0.7, 0.7, 0.75)
	_equipped_mesh.material_override = mat
	# Tilt the mesh forward so it reads as held.
	_equipped_mesh.rotation = Vector3(deg_to_rad(70), 0, 0)
	_hand_anchor.add_child(_equipped_mesh)

func start_drop() -> void:
	position.y += DROP_HEIGHT
	velocity = Vector3.ZERO
	_drop_started = true
	_intro_active = true

func _physics_process(delta: float) -> void:
	if not _drop_started:
		return

	if not is_on_floor():
		velocity.y -= GRAVITY * delta
	else:
		if _intro_active:
			_intro_active = false

	if _pending_jump and is_on_floor():
		velocity.y = JUMP_VELOCITY
		_pending_jump = false

	if not _intro_active:
		var world_move := transform.basis * Vector3(_sse_dir.x, 0.0, _sse_dir.z) * SPEED
		velocity.x = world_move.x
		velocity.z = world_move.z
	else:
		velocity.x = 0.0
		velocity.z = 0.0

	move_and_slide()

# HostBridge's typed signals carry stringly-typed JSON payloads (the
# bridge stays portable across gdext Dictionary-API drift). Parse and
# delegate to the existing handler so the migrated subscriber path
# matches the legacy one byte-for-byte.
func _on_movement_json(payload_json: String) -> void:
	var parsed: Variant = JSON.parse_string(payload_json)
	if parsed is Dictionary:
		_on_state_updated(parsed)


func _on_state_updated(state: Dictionary) -> void:
	if not state.has("action") or state["action"] == null:
		return
	var pressed: bool = state.get("pressed", false)
	# Verbose diagnostic — remove once spell chain is confirmed reliable in the wild.
	print("[player] state_updated action=", state["action"], " pressed=", pressed)
	match state["action"]:
		"MOVE_FORWARD":  _sse_dir.z = -1.0 if pressed else 0.0
		"MOVE_BACKWARD": _sse_dir.z =  1.0 if pressed else 0.0
		"MOVE_LEFT":     _sse_dir.x = -1.0 if pressed else 0.0
		"MOVE_RIGHT":    _sse_dir.x =  1.0 if pressed else 0.0
		"JUMP":          if pressed: _pending_jump = true
		"INTERACT":      if pressed: _use_first_item()
		"CAST_SPELL_1":  if pressed: _cast_spell(1)
		"CAST_SPELL_2":  if pressed: _cast_spell(2)
		"CAST_SPELL_3":  if pressed: _cast_spell(3)
		"CAST_SPELL_4":  if pressed: _cast_spell(4)
		"CAST_SPELL_5":  if pressed: _cast_spell(5)

# Spawn a glowing projectile from the player's hand position in the camera-forward
# direction. Self-destructs after 2 seconds. Pure visual — damage / MP cost are
# computed server-side once we wire enemy hit detection.
func _cast_spell(slot: int) -> void:
	if slot < 1 or slot > SPELL_COLORS.size():
		return
	var color: Color       = SPELL_COLORS[slot - 1]
	var spell_name: String = SPELL_NAMES[slot - 1]
	print("[player] *** _cast_spell START slot=%d (%s) ***" % [slot, spell_name])

	# Much larger + brighter projectile — tier 1 at 0.6 radius, tier 5 at 1.0.
	# If this doesn't show up, the spell signal isn't reaching player_controller at all.
	var proj := MeshInstance3D.new()
	var sphere := SphereMesh.new()
	sphere.radius = 0.6 + (slot - 1) * 0.1
	sphere.height = sphere.radius * 2.0
	proj.mesh = sphere
	var mat := StandardMaterial3D.new()
	mat.albedo_color = color
	mat.emission_enabled = true
	mat.emission = color
	mat.emission_energy_multiplier = 8.0
	mat.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	proj.material_override = mat

	# Attach to the scene root (same parent as player) so the projectile doesn't
	# move with the player after spawn.
	var host: Node = get_parent()
	if host == null:
		push_warning("[player] no parent to host spell projectile")
		return
	host.add_child(proj)

	var spawn_pos: Vector3
	if is_instance_valid(_hand_anchor):
		spawn_pos = _hand_anchor.global_position
	else:
		spawn_pos = global_position + Vector3(0, 0.6, 0)
	proj.global_position = spawn_pos
	print("[player] projectile spawned at ", spawn_pos, " parent=", host.name)

	var forward := -transform.basis.z
	if forward.length() < 0.01:
		forward = Vector3(0, 0, -1)   # Fallback if basis is zero during intro
	var travel := forward * 28.0

	var glow := OmniLight3D.new()
	glow.light_color = color
	glow.light_energy = 4.0
	glow.omni_range = 10.0
	proj.add_child(glow)

	var tween := proj.create_tween()
	tween.tween_property(proj, "global_position", spawn_pos + travel, 2.2)
	tween.parallel().tween_property(mat, "emission_energy_multiplier", 0.0, 2.2)
	tween.tween_callback(func(): if is_instance_valid(proj): proj.queue_free())

# E key → tell the API to consume the first inventory item. The API responds
# with a fresh CharacterDto and emits a `character` SSE event so the HUD updates.
func _use_first_item() -> void:
	if not OS.has_feature("web"):
		return
	JavaScriptBridge.eval("""
(function(){
	fetch('/api/characters/1/use-first-item', { method: 'POST' })
		.then(function(r){ if (!r.ok) console.warn('[player] use-first-item', r.status); })
		.catch(function(){});
})();
""")
