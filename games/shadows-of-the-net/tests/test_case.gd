extends RefCounted

# Assertion base for the headless GDScript suite (see tests/README.md).
# Subclasses override `suite_name()` and `run()`; `run()` may await frames.
#
# Every assertion records a check so a suite that silently stops asserting
# shows up as a dropped check count rather than as a pass.

var failures: Array[String] = []
var checks := 0
var tree: SceneTree = null


func suite_name() -> String:
	return "unnamed"


## TAP-style plan: exactly how many assertions this suite runs.
##
## NOT bookkeeping — it is the only way this harness can see an ABORTED suite.
## A GDScript runtime error (calling a method that does not exist, indexing a
## missing key, casting a freed object) aborts the current function frame
## WITHOUT propagating: the awaiting caller just resumes at the next line. So a
## production regression that makes a test method blow up half way through
## silently deletes every assertion after it and the suite still reports
## "0 failures". Pinning the count turns that into a red build.
##
## Update it deliberately when you add or remove an assertion.
func plan() -> int:
	return -1


func run() -> void:
	pass


# ── assertions ───────────────────────────────────────────────────────────────

func ok(condition: bool, what: String) -> void:
	checks += 1
	if not condition:
		failures.append("%s — expected true" % what)


func not_ok(condition: bool, what: String) -> void:
	checks += 1
	if condition:
		failures.append("%s — expected false" % what)


func eq(actual: Variant, expected: Variant, what: String) -> void:
	checks += 1
	if actual != expected:
		failures.append("%s — expected %s, got %s" % [what, expected, actual])


func ne(actual: Variant, unexpected: Variant, what: String) -> void:
	checks += 1
	if actual == unexpected:
		failures.append("%s — expected anything but %s" % [what, unexpected])


func almost(actual: float, expected: float, what: String, tol := 0.001) -> void:
	checks += 1
	if not is_finite(actual) or absf(actual - expected) > tol:
		failures.append("%s — expected ~%f, got %f" % [what, expected, actual])


func finite(v: Variant, what: String) -> void:
	checks += 1
	if v is Vector3:
		if not (v as Vector3).is_finite():
			failures.append("%s — expected a finite Vector3, got %s" % [what, v])
	elif v is float or v is int:
		if not is_finite(float(v)):
			failures.append("%s — expected a finite number, got %s" % [what, v])
	else:
		failures.append("%s — not a number/Vector3: %s" % [what, v])


# ── scene helpers ────────────────────────────────────────────────────────────

## Instantiate the real room scene (all scripts, real node layout). Non-web, so
## both bridges stay dormant and the room boots in NATIVE mode; tests drive the
## transports by calling the controller's handlers directly, which is exactly
## what the SSE / postMessage drains do at runtime.
func make_room() -> Node3D:
	var packed: PackedScene = load("res://scenes/room.tscn")
	var room: Node3D = packed.instantiate()
	tree.root.add_child(room)
	return room


func free_room(room: Node) -> void:
	tree.root.remove_child(room)
	room.free()
