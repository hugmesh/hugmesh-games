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
var _sse_dir       := Vector3.ZERO
var _pending_jump  := false
var _drop_started  := false
var _intro_active  := false
var _hand_anchor: Node3D = null
var _equipped_mesh: MeshInstance3D = null

func _ready() -> void:
	_game_bridge = get_parent().get_node_or_null("GameBridge")
	if _game_bridge:
		_game_bridge.state_updated.connect(_on_state_updated)
	else:
		push_warning("PlayerController: GameBridge not found under parent")
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

func _on_state_updated(state: Dictionary) -> void:
	if not state.has("action") or state["action"] == null:
		return
	var pressed: bool = state.get("pressed", false)
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
	var color: Color    = SPELL_COLORS[slot - 1]
	var spell_name: String = SPELL_NAMES[slot - 1]
	print("[player] cast spell %d (%s)" % [slot, spell_name])

	var proj := MeshInstance3D.new()
	var sphere := SphereMesh.new()
	sphere.radius = 0.18 + (slot - 1) * 0.04   # bigger spells for higher slots
	sphere.height = sphere.radius * 2.0
	proj.mesh = sphere
	var mat := StandardMaterial3D.new()
	mat.albedo_color = color
	mat.emission_enabled = true
	mat.emission = color
	mat.emission_energy_multiplier = 4.5
	proj.material_override = mat

	# Spawn at hand position, in world space.
	get_parent().add_child(proj)
	if is_instance_valid(_hand_anchor):
		proj.global_position = _hand_anchor.global_position
	else:
		proj.global_position = global_position + Vector3(0, 0.6, 0)

	# Velocity along the player's forward axis (negative Z in local).
	var forward := -transform.basis.z
	var spell_vel := forward * 18.0

	# Manual tween-based motion + light + queue_free.
	var glow := OmniLight3D.new()
	glow.light_color = color
	glow.light_energy = 2.5
	glow.omni_range = 6.0
	proj.add_child(glow)

	var tween := proj.create_tween()
	tween.tween_property(proj, "global_position",
		proj.global_position + spell_vel * 1.6, 1.6)
	tween.parallel().tween_property(mat, "emission_energy_multiplier", 0.0, 1.6)
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
