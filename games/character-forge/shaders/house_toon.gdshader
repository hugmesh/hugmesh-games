// =============================================================================
// House toon shader (OPAQUE) — the default surface shader for every Character
// Forge avatar piece. All uniforms, fragment() and light() live in the shared
// core include so the opaque and transparent variants can never drift.
//
// Compatibility discipline, palette-strip albedo, fresnel rim and the optional
// matcap sheen are all documented in house_toon_core.gdshaderinc.
// =============================================================================
shader_type spatial;
render_mode cull_back, depth_draw_opaque;

#include "res://shaders/house_toon_core.gdshaderinc"
