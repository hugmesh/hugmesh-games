// =============================================================================
// House toon CORE — the shared body of the one stylized surface shader for
// Character Forge avatars (technique.md verdict), written for the
// Compatibility renderer (WebGL2 / single-threaded WASM / Quest 3 browser).
//
// Included by BOTH house_toon.gdshader (opaque, the default for every avatar
// surface) and house_toon_trans.gdshader (alpha-blended, used only by the
// handful of see-through pieces: slime bodies, insect wings, blush discs).
// The two shaders exist as separate files ONLY because a Godot spatial shader
// that writes ALPHA is transparent for every material using it — factoring the
// body here keeps the two variants from drifting apart.
//
// * Diffuse: custom light() half-Lambert ramp quantized into 2-3 bands
//   (band_cuts 1 = classic 2-tone, 2 = 3-tone). COMPATIBILITY DISCIPLINE:
//   DIFFUSE_LIGHT must NEVER go negative there (vec3(-x) clamps to black and
//   breaks darkening tricks) — every factor below is clamped >= 0 and only
//   ADDED, never subtracted.
// * Albedo: the per-avatar 8x8 palette-strip texture (CharForgePalette).
//   Imported GLBs sample it by their remapped UVs; procedural primitives
//   ignore mesh UV by setting `palette_cell` to a cell centre (per-material
//   uniform — per-INSTANCE uniforms are unsupported in Compatibility, which
//   is why recolor rides in the shared palette texture instead).
// * Rim: pure-fragment fresnel added to EMISSION (renderer-safe, no light()
//   dependency).
// * Optional matcap specular: view-space-normal lookup for the FF-stylized
//   sheen at zero light cost; off (black texture / strength 0) by default.
//
// The inverted-hull outline lives in outline_hull.gdshader as a next_pass
// material behind a quality toggle (default OFF) — never here.
// =============================================================================

// Nearest + no-repeat AT THE SAMPLER so palette cells never bleed, regardless
// of texture import settings (the palette is a runtime ImageTexture anyway).
uniform sampler2D palette_tex : source_color, filter_nearest, repeat_disable, hint_default_white;
// (-1,-1) = sample by mesh UV (palette-mapped GLBs); a cell centre from
// CharForgePalette.region_uv() = fixed-cell mode (procedural primitives).
uniform vec2 palette_cell = vec2(-1.0, -1.0);
uniform vec4 albedo_tint : source_color = vec4(1.0, 1.0, 1.0, 1.0);

// ── VENDOR-ATLAS RECOLOUR ────────────────────────────────────────────────────
// Some vendored characters (the KayKit lines) render from ONE baked atlas
// rather than from named material slots, so binding them to a flat house colour
// would paint the whole character one colour — which is why they shipped with
// recolour switched off entirely.
//
// They are, however, palette-indexed art: each body part's texels are a
// LUMINANCE RAMP of a single hue (measured: the Knight's head is a 16-entry
// flesh ramp, its cape a 13-entry red one). So the region colour can replace
// the HUE while the atlas keeps supplying the SHADING — which is what these
// three uniforms do, per mesh node.
//
// atlas_strength 0.0 (the default) makes this branch inert, so every procedural
// avatar and every slot-mapped GLB compiles and renders exactly as before. The
// branch is uniform-constant across a draw, so it does not diverge.
uniform sampler2D atlas_tex : source_color, hint_default_white;
uniform float atlas_strength : hint_range(0.0, 1.0) = 0.0;
// Luminance of THIS node's dominant atlas colour, measured at mount time by
// sampling the atlas along the node's own UVs. Dividing by it makes the region
// colour land exactly on the part's base tone, with highlights above and
// shadows below — rather than darkening every recoloured character.
uniform float atlas_reference_luma : hint_range(0.001, 1.0) = 0.5;
// The node's dominant HUE (0..1), and how far from it a texel may sit and still
// be recoloured.
//
// WHY THIS IS HUE-SELECTIVE RATHER THAN WHOLESALE. A mesh node is not always
// one material: measured on the shipped models, the Mage's "Head" node is 36%
// its dominant colour because it carries the hood AND the face. Tinting the
// whole node would turn the face the colour of the hood. Recolouring only the
// texels near the node's dominant hue leaves the others exactly as the vendor
// drew them, so a mixed node degrades to "part of it recolours" instead of to
// "it is wrong".
uniform float atlas_hue : hint_range(0.0, 1.0) = 0.0;
uniform float atlas_hue_tolerance : hint_range(0.001, 0.5) = 0.083;

// ── MULTI-CLUSTER ATLAS RECOLOUR (the 100Avatars line) ───────────────────────
// The KayKit path above tints ONE mesh node toward ONE region, which needs a
// per-part boundary. Some vendored avatars have none: one mesh node, one
// primitive, one material, no per-vertex colour, no VRM annotation. Measured,
// their UV islands are anatomical (94 islands carrying only 23 distinct
// colours), so no boundary in the FILE is a material boundary.
//
// What IS a material boundary is the texture itself: each of those avatars
// resolves to only 2-4 base colours. So this path classifies each TEXEL to the
// nearest base colour and takes its region from that — no boundary needed at
// all, and no mesh split.
//
// Packed as vec4s rather than arrays because the Compatibility renderer is the
// target and uniform arrays are the fussier path there: _r/_g/_b hold the RGB
// of up to four base colours, _cell_x/_cell_y their palette cells, _luma their
// reference luminances. atlas_cluster_count 0.0 makes the whole branch inert.
uniform vec4 atlas_base_r = vec4(0.0);
uniform vec4 atlas_base_g = vec4(0.0);
uniform vec4 atlas_base_b = vec4(0.0);
uniform vec4 atlas_cell_x = vec4(0.0);
uniform vec4 atlas_cell_y = vec4(0.0);
uniform vec4 atlas_cluster_luma = vec4(0.5);
uniform float atlas_cluster_count = 0.0;
// Confidence gate. When the two nearest base colours are closer together than
// this, the texel is AMBIGUOUS and keeps the vendor's colour rather than being
// assigned a region that is a coin toss. Measured: ~88% of texels classify
// correctly, and this is what keeps the other ~12% from becoming visible
// speckle in the wrong hue.
uniform float atlas_cluster_margin : hint_range(0.0, 0.5) = 0.06;

// Ramp: number of band boundaries in the half-Lambert ramp. 1 => 2 bands,
// 2 => 3 bands (the supported stylistic range; toon=1, anime=2).
uniform float band_cuts : hint_range(1.0, 3.0, 1.0) = 1.0;
uniform float band_softness : hint_range(0.001, 0.5) = 0.04;
// Darkest tone of the unlit band (multiplier on albedo — a LIT floor, never
// a negative subtraction).
uniform float shadow_tone : hint_range(0.0, 1.0) = 0.55;

uniform vec4 rim_color : source_color = vec4(1.0, 1.0, 1.0, 1.0);
uniform float rim_strength : hint_range(0.0, 2.0) = 0.35;
uniform float rim_power : hint_range(0.5, 8.0) = 3.0;

// Optional matcap specular (FF-stylized sheen); black default = off.
uniform sampler2D matcap_tex : source_color, hint_default_black;
uniform float matcap_strength : hint_range(0.0, 2.0) = 0.0;

// Iris-glow / synth-core style emissive boost of the albedo itself.
uniform float emission_boost : hint_range(0.0, 4.0) = 0.0;

#ifdef HOUSE_TOON_TRANSPARENT
uniform float alpha_mul : hint_range(0.0, 1.0) = 1.0;
#endif


void fragment() {
	vec2 uv = (palette_cell.x >= 0.0) ? palette_cell : UV;
	vec4 albedo = texture(palette_tex, uv) * albedo_tint;
	if (atlas_strength > 0.0) {
		// Keep the vendor atlas's shading ramp, take the hue from the palette.
		vec3 vendor = texture(atlas_tex, UV).rgb;
		float luma = dot(vendor, vec3(0.2126, 0.7152, 0.0722));
		float ratio = luma / max(atlas_reference_luma, 0.001);
		vec3 tinted = clamp(albedo.rgb * ratio, vec3(0.0), vec3(1.0));
		// How far this texel's hue is from the node's dominant one (wrapped).
		float mx = max(vendor.r, max(vendor.g, vendor.b));
		float mn = min(vendor.r, min(vendor.g, vendor.b));
		float chroma = mx - mn;
		float hue;
		if (mx == vendor.r) {
			hue = mod((vendor.g - vendor.b) / max(chroma, 0.0001), 6.0);
		} else if (mx == vendor.g) {
			hue = (vendor.b - vendor.r) / max(chroma, 0.0001) + 2.0;
		} else {
			hue = (vendor.r - vendor.g) / max(chroma, 0.0001) + 4.0;
		}
		hue = hue / 6.0;
		float dist = abs(hue - atlas_hue);
		dist = min(dist, 1.0 - dist);
		// An achromatic texel (a white highlight, a black line) has no reliable
		// hue, so it follows the region rather than being left behind as a
		// stripe of the vendor's palette.
		float selected = chroma < 0.02
				? 1.0
				: 1.0 - smoothstep(atlas_hue_tolerance, atlas_hue_tolerance * 1.6, dist);
		albedo.rgb = mix(vendor, tinted, selected * atlas_strength);
	}
	if (atlas_cluster_count > 0.0) {
		vec3 vendor = texture(atlas_tex, UV).rgb;
		float luma = dot(vendor, vec3(0.2126, 0.7152, 0.0722));
		// Nearest of up to four base colours, plain RGB distance. Measured
		// against chromaticity- and saturation-weighted metrics: plain RGB won
		// (87.8% vs 83.3%), and chromaticity collapses entirely on an
		// achromatic avatar, where black and white share one chromaticity.
		float best = 1e9;
		float second = 1e9;
		int pick = 0;
		for (int i = 0; i < 4; i++) {
			if (float(i) >= atlas_cluster_count) { break; }
			vec3 base = vec3(atlas_base_r[i], atlas_base_g[i], atlas_base_b[i]);
			float d = distance(vendor, base);
			if (d < best) { second = best; best = d; pick = i; }
			else if (d < second) { second = d; }
		}
		if (second - best >= atlas_cluster_margin) {
			vec2 cell = vec2(atlas_cell_x[pick], atlas_cell_y[pick]);
			vec3 region_rgb = texture(palette_tex, cell).rgb;
			float ratio = luma / max(atlas_cluster_luma[pick], 0.001);
			albedo.rgb = clamp(region_rgb * ratio, vec3(0.0), vec3(1.0));
		} else {
			albedo.rgb = vendor;
		}
	}
	ALBEDO = albedo.rgb;
#ifdef HOUSE_TOON_TRANSPARENT
	ALPHA = clamp(albedo.a * alpha_mul, 0.0, 1.0);
#endif

	vec3 n = normalize(NORMAL);
	// Fresnel rim (view-space N and V — both fragment built-ins are view-space).
	float fresnel = pow(clamp(1.0 - dot(n, normalize(VIEW)), 0.0, 1.0), rim_power);
	// Matcap: view-space normal xy -> hemisphere lookup (0.497 guards the seam).
	vec2 matcap_uv = clamp(n.xy * 0.497 + 0.5, 0.0, 1.0);
	vec3 matcap = texture(matcap_tex, matcap_uv).rgb;

	EMISSION = rim_color.rgb * (fresnel * rim_strength)
			+ matcap * matcap_strength
			+ ALBEDO * emission_boost;
}


void light() {
	// Half-Lambert: wrap N.L into [0,1] so the dark side keeps shape.
	float half_lambert = clamp(dot(NORMAL, LIGHT) * 0.5 + 0.5, 0.0, 1.0);
	float cuts = max(band_cuts, 1.0);
	float x = half_lambert * (cuts + 1.0);
	float band = floor(x);
	float frac = x - band;
	float soft = clamp(band_softness, 0.001, 0.5);
	// Quantize with a smoothstepped edge per band boundary (anti-alias).
	float level = clamp((band + smoothstep(0.5 - soft, 0.5 + soft, frac)) / (cuts + 1.0), 0.0, 1.0);
	float shade = mix(shadow_tone, 1.0, level);
	// All factors clamped non-negative: DIFFUSE_LIGHT can only ever grow.
	DIFFUSE_LIGHT += max(shade, 0.0) * clamp(ATTENUATION, 0.0, 1.0) * LIGHT_COLOR * ALBEDO / PI;
}
