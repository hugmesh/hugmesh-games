extends SceneTree

# Headless regression runner for the shadows-of-the-net room.
#
#   godot --headless --path games/shadows-of-the-net --script res://tests/run_tests.gd
#
# (or `make test-godot` from the repo root, which is part of `make test`).
# Exit code 0 = all suites green, 1 = at least one failing assertion, a suite
# that asserted nothing, a suite that failed to parse, or the deadline lapsing.
# See tests/README.md for the conventions.
#
# There is no third-party test framework in this repo, so this is deliberately
# tiny: discover tests/test_*.gd, instantiate, run, print, aggregate. Suites may
# await frames — queue_free() is deferred in Godot, so several of the structural
# regressions only become observable one frame later.

const TESTS_DIR := "res://tests"

## A suite that never returns must FAIL the run, not wedge CI forever: a
## GDScript coroutine cannot be cancelled, so the only honest escape is to give
## the whole run a deadline and exit non-zero when it lapses.
const DEADLINE_SEC := 180.0

var _finished := false


func _initialize() -> void:
	# The root Window has not been notified ready yet while _initialize() runs,
	# so any node added now gets its _ready() — and every @onready var with it —
	# DEFERRED to the first frame. Wait one frame so make_room() hands the
	# suites a fully wired scene instead of a tree full of nulls.
	await process_frame
	_watchdog()
	_run_all()


func _watchdog() -> void:
	await create_timer(DEADLINE_SEC).timeout
	if _finished:
		return
	printerr("[sotn-tests] TIMEOUT — the run did not finish within %ds" % int(DEADLINE_SEC))
	quit(1)


func _run_all() -> void:
	var paths := _discover()
	if paths.is_empty():
		printerr("[sotn-tests] no test suites found under %s" % TESTS_DIR)
		quit(1)
		return

	var total_checks := 0
	var total_failures := 0
	var failed_suites := 0

	for path in paths:
		var script: GDScript = load(path)
		if script == null or not script.can_instantiate():
			# A suite with a PARSE error loads as an un-instantiable script.
			# Reporting it here — instead of calling .new() on it and then
			# awaiting a null — is what keeps a broken suite a RED run rather
			# than a wedged one; that is exactly how this runner used to hang.
			printerr("  ✗ %s — FAILED TO LOAD (parse error?)" % path)
			failed_suites += 1
			total_failures += 1
			continue
		var suite: RefCounted = script.new()
		if suite == null or not suite.has_method("run") or not suite.has_method("suite_name"):
			printerr("  ✗ %s — not a test suite (must extend tests/test_case.gd)" % path)
			failed_suites += 1
			total_failures += 1
			continue
		suite.tree = self
		await suite.run()
		total_checks += suite.checks
		if suite.checks == 0:
			printerr("  ✗ %s — suite ran 0 assertions (vacuous)" % suite.suite_name())
			failed_suites += 1
			total_failures += 1
			continue
		# TAP-style plan check. A GDScript runtime error aborts only the current
		# function frame — the awaiting caller resumes on the next line — so a
		# suite whose assertions stopped executing half way through would
		# otherwise report "0 failures" while silently covering nothing. See
		# test_case.plan().
		var planned: int = suite.plan()
		if planned < 0:
			printerr("  ✗ %s — no plan(); a suite must declare its assertion count"
					% suite.suite_name())
			failed_suites += 1
			total_failures += 1
			continue
		if suite.checks != planned:
			# NB: `%` binds tighter than `+`, so the format args must be applied
			# to the WHOLE concatenated literal, not just its last fragment.
			printerr(("  ✗ %s — ran %d assertions, planned %d (suite ABORTED mid-run,"
					+ " or the plan is stale)") % [suite.suite_name(), suite.checks, planned])
			failed_suites += 1
			total_failures += 1
			for failure in suite.failures:
				printerr("      · %s" % failure)
			continue
		if suite.failures.is_empty():
			print("  ✓ %s (%d checks)" % [suite.suite_name(), suite.checks])
		else:
			failed_suites += 1
			total_failures += suite.failures.size()
			printerr("  ✗ %s (%d checks, %d failed)"
					% [suite.suite_name(), suite.checks, suite.failures.size()])
			for failure in suite.failures:
				printerr("      · %s" % failure)

	_finished = true
	print("[sotn-tests] %d suites, %d checks, %d failures"
			% [paths.size(), total_checks, total_failures])
	quit(1 if failed_suites > 0 else 0)


func _discover() -> Array:
	var found: Array = []
	var dir := DirAccess.open(TESTS_DIR)
	if dir == null:
		return found
	for file_name in dir.get_files():
		# Godot serves .gd from an exported pack as .gdc/.remap; source runs
		# (which is all this runner ever does) see the plain name.
		var base := file_name.trim_suffix(".remap")
		if base.begins_with("test_") and base.ends_with(".gd") and base != "test_case.gd":
			found.append("%s/%s" % [TESTS_DIR, base])
	found.sort()
	return found
