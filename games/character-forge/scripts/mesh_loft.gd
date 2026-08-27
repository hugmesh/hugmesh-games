class_name CharForgeLoft
extends RefCounted

# =============================================================================
# SurfaceTool ring-loft / lathe — the curved-geometry primitive behind
# PROCEDURAL V2.
#
# technique.md: "curved snouts/muzzles beat boxes — build by lathe/revolve
# (CSGPolygon3D in SPIN mode, or SurfaceTool ring-lofting a profile curve) then
# BAKE TO A STATIC MESH so no live CSG evaluation ships at runtime; SurfaceTool
# can emit the palette-cell UVs and per-vertex region colors directly while
# building."
#
# This module is the SurfaceTool half of that recommendation: every function
# returns a finished, immutable ArrayMesh (commit()) — there is no CSG node in
# the shipped scene tree at all. Every vertex carries
#   * the palette CELL CENTRE UV, so a lofted piece samples exactly one cell of
#     the shared 8x8 palette texture through the house toon shader, and
#   * the same colour as a vertex COLOR, so the mesh still reads correctly if it
#     is ever drawn with a plain unshaded/vertex-colour material.
#
# Ring convention: rings lie in the local XY plane and advance along +Z, which
# matches the assembler's head space (the face points at +Z).
# =============================================================================

## Radial segments used for muzzles/ears. Kept low on purpose: the Quest-3
## browser budget in technique.md is ~5-15k tris for a WHOLE avatar.
const DEFAULT_SEGMENTS := 12

## A lofted muzzle's tip ring never shrinks below this fraction of its base
## ring — "wide-rounded, tapers into cheeks, NEVER cone" (kemono.md). Mirrors
## CharForgeProportions.MUZZLE_MIN_TIP_FRACTION.
const MIN_TIP_FRACTION := 0.45


## Ring-lofts a list of sections into a closed-ish tube.
##   sections: [{"z": float, "rx": float, "ry": float, "y": float (optional
##             vertical offset of the ring centre)}] ordered along +Z.
##   uv:       palette cell centre every vertex samples.
##   color:    vertex colour (the same region colour).
## Caps: the FRONT (last section) is fanned closed; the back is left open by
## default because it is buried inside the skull.
static func ring_loft(sections: Array, uv: Vector2, color: Color,
		segments: int = DEFAULT_SEGMENTS, cap_front: bool = true,
		cap_back: bool = false) -> ArrayMesh:
	if sections.size() < 2 or segments < 3:
		return ArrayMesh.new()
	var st := SurfaceTool.new()
	st.begin(Mesh.PRIMITIVE_TRIANGLES)
	st.set_smooth_group(0)

	var rings: Array = []
	for section in sections:
		var ring: Array = []
		var cz: float = float(section.get("z", 0.0))
		var cy: float = float(section.get("y", 0.0))
		var rx: float = maxf(float(section.get("rx", 0.05)), 0.0001)
		var ry: float = maxf(float(section.get("ry", 0.05)), 0.0001)
		for s in range(segments):
			var a: float = TAU * float(s) / float(segments)
			ring.append(Vector3(cos(a) * rx, cy + sin(a) * ry, cz))
		rings.append(ring)

	for i in range(rings.size() - 1):
		var a_ring: Array = rings[i]
		var b_ring: Array = rings[i + 1]
		for s in range(segments):
			var n := (s + 1) % segments
			# Two triangles per quad, wound so the outside faces outward.
			_tri(st, uv, color, a_ring[s], b_ring[s], b_ring[n])
			_tri(st, uv, color, a_ring[s], b_ring[n], a_ring[n])

	if cap_front:
		_fan(st, uv, color, rings[rings.size() - 1], segments, true)
	if cap_back:
		_fan(st, uv, color, rings[0], segments, false)

	st.generate_normals()
	return st.commit()


## Revolves a profile around the Y axis (radius, height) pairs, bottom to top.
## Used for horn/ear/plinth shapes where the silhouette is a curve rather than
## a stack of ellipses.
static func lathe(profile: PackedVector2Array, uv: Vector2, color: Color,
		segments: int = DEFAULT_SEGMENTS) -> ArrayMesh:
	if profile.size() < 2 or segments < 3:
		return ArrayMesh.new()
	var sections: Array = []
	for p in profile:
		sections.append({"z": p.y, "rx": maxf(p.x, 0.0001), "ry": maxf(p.x, 0.0001)})
	var mesh := ring_loft(sections, uv, color, segments, true, true)
	# ring_loft builds along +Z; rotate the vertices onto +Y so callers get the
	# conventional lathe orientation.
	return _rotate_zy(mesh, uv, color)


## The kemono muzzle section table — the numeric shape, separated from the
## geometry so it can be asserted directly.
##   depth:      forward projection in head-local units
##   half_width: half width at the CHEEK (base) ring
##   half_height:half height at the base ring
##   taper:      0 = straight tube, 1 = maximum taper (clamped so the tip ring
##               never drops below MIN_TIP_FRACTION of the base -> never a cone)
## Returns 5 sections: cheek blend, base, mid, front, rounded tip.
static func muzzle_sections(depth: float, half_width: float, half_height: float,
		taper: float) -> Array:
	var d: float = maxf(depth, 0.001)
	var hw: float = maxf(half_width, 0.001)
	var hh: float = maxf(half_height, 0.001)
	var t: float = clampf(taper, 0.0, 1.0)
	# Fraction of the base radius kept at the tip, floored so the profile stays
	# a plush wedge instead of collapsing to a point.
	var tip: float = maxf(1.0 - t, MIN_TIP_FRACTION)
	var mid: float = (1.0 + tip) * 0.5
	var front: float = tip + (mid - tip) * 0.35
	return [
		# The cheek ring is WIDER than the base: this is the "tapers into the
		# cheeks" blend that stops the muzzle reading as a bolted-on box.
		{"z": 0.0, "rx": hw * 1.16, "ry": hh * 1.10, "y": 0.0},
		{"z": d * 0.28, "rx": hw, "ry": hh, "y": -0.004 * d},
		{"z": d * 0.62, "rx": hw * mid, "ry": hh * mid, "y": -0.010 * d},
		{"z": d * 0.88, "rx": hw * front, "ry": hh * front, "y": -0.014 * d},
		# Rounded tip: a slightly smaller ring set back in Z gives a dome
		# instead of a flat disc once the front cap is fanned on.
		{"z": d, "rx": hw * tip * 0.86, "ry": hh * tip * 0.86, "y": -0.016 * d},
	]


## A plush, wide-rounded kemono muzzle baked to a static mesh.
static func muzzle(depth: float, half_width: float, half_height: float, taper: float,
		uv: Vector2, color: Color, segments: int = DEFAULT_SEGMENTS) -> ArrayMesh:
	return ring_loft(muzzle_sections(depth, half_width, half_height, taper),
			uv, color, segments, true, false)


## Section table for the large rounded-triangular kemono ear (kemono.md:
## "ears large/high/rounded-triangular, inner-ear separate region"). Built in
## the same +Z convention and rotated onto +Y by rounded_tri_ear().
static func ear_sections(height: float, half_width: float, thickness: float) -> Array:
	var h: float = maxf(height, 0.001)
	var hw: float = maxf(half_width, 0.001)
	var th: float = maxf(thickness, 0.0005)
	return [
		{"z": 0.0, "rx": hw * 0.92, "ry": th},
		{"z": h * 0.30, "rx": hw, "ry": th * 0.95},
		{"z": h * 0.66, "rx": hw * 0.66, "ry": th * 0.80},
		{"z": h * 0.88, "rx": hw * 0.34, "ry": th * 0.60},
		# Rounded (not pointed) tip.
		{"z": h, "rx": hw * 0.16, "ry": th * 0.42},
	]


## Large rounded-triangular ear, growing along +Y.
static func rounded_tri_ear(height: float, half_width: float, thickness: float,
		uv: Vector2, color: Color, segments: int = DEFAULT_SEGMENTS) -> ArrayMesh:
	var mesh := ring_loft(ear_sections(height, half_width, thickness), uv, color,
			segments, true, true)
	return _rotate_zy(mesh, uv, color)


## Triangle count of a mesh's first surface (test/budget helper).
static func triangle_count(mesh: ArrayMesh) -> int:
	if mesh == null or mesh.get_surface_count() == 0:
		return 0
	var arrays: Array = mesh.surface_get_arrays(0)
	var verts: PackedVector3Array = arrays[Mesh.ARRAY_VERTEX]
	return verts.size() / 3


static func _tri(st: SurfaceTool, uv: Vector2, color: Color,
		a: Vector3, b: Vector3, c: Vector3) -> void:
	for v in [a, b, c]:
		st.set_uv(uv)
		st.set_color(color)
		st.add_vertex(v)


static func _fan(st: SurfaceTool, uv: Vector2, color: Color, ring: Array,
		segments: int, forward: bool) -> void:
	var center := Vector3.ZERO
	for p in ring:
		center += p
	center /= float(ring.size())
	for s in range(segments):
		var n := (s + 1) % segments
		if forward:
			_tri(st, uv, color, center, ring[s], ring[n])
		else:
			_tri(st, uv, color, center, ring[n], ring[s])


## Re-commits a +Z-built mesh rotated so its axis runs along +Y.
static func _rotate_zy(mesh: ArrayMesh, uv: Vector2, color: Color) -> ArrayMesh:
	if mesh.get_surface_count() == 0:
		return mesh
	var arrays: Array = mesh.surface_get_arrays(0)
	var verts: PackedVector3Array = arrays[Mesh.ARRAY_VERTEX]
	var st := SurfaceTool.new()
	st.begin(Mesh.PRIMITIVE_TRIANGLES)
	st.set_smooth_group(0)
	for v in verts:
		st.set_uv(uv)
		st.set_color(color)
		st.add_vertex(Vector3(v.x, v.z, -v.y))
	st.generate_normals()
	return st.commit()
