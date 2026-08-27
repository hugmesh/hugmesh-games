// =============================================================================
// 5-frame mouth-strip — the lip-sync FALLBACK for meshes without a jaw bone
// (jrpg-bases.md: cheapest JRPG-authentic channel, no DCC tooling needed).
//
// The mouth quad's UVs span [0,1]^2; this shader windows them onto one of the
// 5 horizontal frames of the strip texture. Frame selection from mouth
// openness lives in CharForgeLipSync.mouth_frame() (tested math); the driver
// just pokes `frame_index`. Cutout alpha so the quad corners vanish.
// =============================================================================
shader_type spatial;
render_mode unshaded, cull_back, depth_draw_opaque;

uniform sampler2D strip_tex : source_color, filter_nearest, repeat_disable;
uniform int frame_index : hint_range(0, 4, 1) = 0;

const float FRAME_COUNT = 5.0;


void fragment() {
	float f = clamp(float(frame_index), 0.0, FRAME_COUNT - 1.0);
	vec2 uv = vec2((clamp(UV.x, 0.0, 1.0) + f) / FRAME_COUNT, UV.y);
	vec4 c = texture(strip_tex, uv);
	ALBEDO = c.rgb;
	ALPHA = c.a;
	ALPHA_SCISSOR_THRESHOLD = 0.5;
}
