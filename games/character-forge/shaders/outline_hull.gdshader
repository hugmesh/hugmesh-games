// =============================================================================
// Inverted-hull outline — SHARED next_pass material for the house toon shader.
//
// Second pass, front faces culled, vertices inflated along their normals: the
// classic cheap low-poly outline. It still doubles vertex work + draw calls
// per outlined surface, and Meta's Quest analysis prices a SHADER SWITCH at
// +175% draw-call cost — so the whole fleet shares ONE ShaderMaterial
// instance of this shader (CharacterAssembler.outline_material()) and the
// feature sits behind a quality toggle that DEFAULTS OFF (VRChat mobile
// precedent: rely on rim + line-region cells on Quest browser instead).
// =============================================================================
shader_type spatial;
render_mode unshaded, cull_front, depth_draw_opaque;

uniform vec4 outline_color : source_color = vec4(0.09, 0.08, 0.11, 1.0);
// World-unit inflation — tuned for the ~1.8-unit-tall avatar rigs.
uniform float outline_width : hint_range(0.0, 0.05) = 0.008;


void vertex() {
	VERTEX += normalize(NORMAL) * outline_width;
}


void fragment() {
	ALBEDO = outline_color.rgb;
}
