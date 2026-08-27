// =============================================================================
// House toon shader (ALPHA-BLENDED variant).
//
// Identical to house_toon.gdshader except that it writes ALPHA — which in
// Godot makes EVERY material using the shader transparent, hence the separate
// file rather than a uniform on the opaque one. Reserved for the few genuinely
// see-through pieces (slime bodies, insect/moth wings, blush discs, slime
// pets); everything else must stay on the opaque shader so avatars keep one
// draw call and correct sorting.
// =============================================================================
shader_type spatial;
render_mode cull_back, depth_draw_opaque, blend_mix;

#define HOUSE_TOON_TRANSPARENT
#include "res://shaders/house_toon_core.gdshaderinc"
