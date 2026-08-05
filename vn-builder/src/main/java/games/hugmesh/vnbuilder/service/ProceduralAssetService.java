package games.hugmesh.vnbuilder.service;

import games.hugmesh.character.AvatarSvgRenderer;
import games.hugmesh.character.CharacterDocument;
import games.hugmesh.character.CharacterSpec;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

/**
 * Deterministic, house-style placeholder art generator.
 *
 * <p>{@link #generate(String, String, String)} is a <b>pure function</b> of
 * its inputs: the same {@code (kind, slug, theme)} always yields
 * byte-identical SVG, so regeneration is stable and the output is trivially
 * unit-testable. There is no randomness beyond a seeded PRNG derived from a
 * stable hash of the inputs, and no timestamps / environment reads.
 *
 * <p>The art is intentionally simple "flat vector on a shared geometry
 * canon" placeholder quality (matching the studio house style):
 * <ul>
 *   <li><b>background</b> — a two-stop sky gradient plus a themed silhouette
 *       motif (hills / trees / a lit interior window / a starfield) keyed by
 *       a small theme enum: dawn / dusk / interior / forest / void.</li>
 *   <li><b>portrait</b> — a colored bust silhouette (shoulders + neck + head)
 *       on a tinted backdrop, derived from the character's colour (a hex
 *       {@code theme}) or a hue seeded from the slug, with a seeded hair
 *       accent.</li>
 *   <li><b>character-portrait</b> — a head-and-shoulders render of the
 *       platform's canonical parametric humanoid ({@code games.hugmesh.character},
 *       decision 028), driven by an optional portable Character Document so a
 *       VN portrait can be the SAME character every other surface renders.
 *       Without a document it falls back to the default spec with the outfit
 *       colour seeded exactly the way the flat portrait seeds its hue.</li>
 * </ul>
 *
 * <p>Every generated SVG carries a {@code data-seed} attribute equal to the
 * stable input hash, so distinct inputs are guaranteed to differ byte-wise
 * even if their randomized geometry happened to collide.
 */
@Service
public class ProceduralAssetService {

    public static final String SVG_MIME = "image/svg+xml";

    private static final int BG_W = 1280, BG_H = 720;
    private static final int PT_W = 512, PT_H = 768;

    /** Background palettes: skyTop, skyBottom, accent (sun/moon/window glow),
     *  silhouetteBack, silhouetteFront, motif. */
    private enum Theme {
        DAWN("#ffd9a0", "#ff9e7a", "#fff2cc", "#7a5670", "#4a2f4a", Motif.HILLS),
        DUSK("#2b2b5e", "#ff7e5f", "#ffd36e", "#241a3a", "#120c22", Motif.HILLS),
        INTERIOR("#4a3b32", "#6a5346", "#ffe6a8", "#2c221b", "#191310", Motif.WINDOW),
        FOREST("#bfe0b0", "#6fae72", "#f2ffd9", "#31603c", "#1d3c26", Motif.TREES),
        VOID("#0a0a1c", "#1c1236", "#d4d4f2", "#080814", "#050510", Motif.STARS);

        final String skyTop, skyBottom, accent, silBack, silFront;
        final Motif motif;
        Theme(String t, String b, String a, String sb, String sf, Motif m) {
            skyTop = t; skyBottom = b; accent = a; silBack = sb; silFront = sf; motif = m;
        }
    }

    private enum Motif { HILLS, TREES, WINDOW, STARS }

    private static final Theme[] THEMES = Theme.values();

    /** {@link #generate(String, String, String, Map)} without a Character
     *  Document — the shape every pre-028 call site uses. */
    public byte[] generate(String kind, String slug, String theme) {
        return generate(kind, slug, theme, null);
    }

    /**
     * Produce house-style placeholder SVG bytes.
     *
     * @param kind      "background", "portrait" or "character-portrait"
     *                  (case-insensitive)
     * @param slug      the asset slug — part of the deterministic seed
     * @param theme     optional theme/seed. For backgrounds: a theme name
     *                  (dawn/dusk/interior/forest/void) or any seed string (an
     *                  unrecognized value picks a theme deterministically). For
     *                  portraits: a {@code #rgb}/{@code #rrggbb} base colour, or
     *                  any seed string (colour derived from the seed hue).
     * @param character optional portable Character Document (character-portrait
     *                  only) — the {@code hugmesh.character} interchange map
     * @throws ResponseStatusException 400 on an unknown kind or an invalid
     *         document — thrown synchronously, BEFORE any reactive pipeline, so
     *         it can never surface as a 500 out of a subscribed Mono
     */
    public byte[] generate(String kind, String slug, String theme, Map<String, ?> character) {
        String k = kind == null ? "" : kind.trim().toLowerCase(Locale.ROOT);
        long seed = seed(slug, theme);
        String svg = switch (k) {
            case "background" -> background(seed, theme);
            case "portrait" -> portrait(seed, theme);
            case "character-portrait" -> characterPortrait(seed, theme, character);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "kind must be one of: background, portrait, character-portrait");
        };
        return svg.getBytes(StandardCharsets.UTF_8);
    }

    // ---- backgrounds -------------------------------------------------------

    private String background(long seed, String theme) {
        Theme t = pickTheme(theme, seed);
        Rng rng = new Rng(seed);
        StringBuilder sb = new StringBuilder(1024);
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"").append(BG_W)
          .append("\" height=\"").append(BG_H).append("\" viewBox=\"0 0 ").append(BG_W).append(' ').append(BG_H)
          .append("\" data-kind=\"background\" data-theme=\"").append(t.name().toLowerCase(Locale.ROOT))
          .append("\" data-seed=\"").append(hex(seed)).append("\">");
        sb.append("<defs><linearGradient id=\"sky\" x1=\"0\" y1=\"0\" x2=\"0\" y2=\"1\">")
          .append("<stop offset=\"0\" stop-color=\"").append(t.skyTop).append("\"/>")
          .append("<stop offset=\"1\" stop-color=\"").append(t.skyBottom).append("\"/>")
          .append("</linearGradient></defs>");
        sb.append("<rect width=\"").append(BG_W).append("\" height=\"").append(BG_H).append("\" fill=\"url(#sky)\"/>");
        switch (t.motif) {
            case HILLS -> { celestial(sb, t, rng); hills(sb, t, rng); }
            case TREES -> { celestial(sb, t, rng); trees(sb, t, rng); }
            case WINDOW -> window(sb, t, rng);
            case STARS -> { stars(sb, t, rng); moon(sb, t, rng); }
        }
        sb.append("</svg>");
        return sb.toString();
    }

    private void celestial(StringBuilder sb, Theme t, Rng rng) {
        int cx = 180 + rng.nextInt(BG_W - 360);
        int cy = 120 + rng.nextInt(140);
        int r = 60 + rng.nextInt(40);
        sb.append("<circle cx=\"").append(cx).append("\" cy=\"").append(cy).append("\" r=\"").append(r)
          .append("\" fill=\"").append(t.accent).append("\" opacity=\"0.9\"/>");
    }

    private void moon(StringBuilder sb, Theme t, Rng rng) {
        int cx = 200 + rng.nextInt(BG_W - 400);
        int cy = 110 + rng.nextInt(120);
        int r = 48 + rng.nextInt(28);
        sb.append("<circle cx=\"").append(cx).append("\" cy=\"").append(cy).append("\" r=\"").append(r)
          .append("\" fill=\"").append(t.accent).append("\" opacity=\"0.85\"/>");
    }

    private void hills(StringBuilder sb, Theme t, Rng rng) {
        hillBand(sb, t.silBack, 430, 70, rng);
        hillBand(sb, t.silFront, 540, 110, rng);
    }

    /** A layered hill silhouette: a smooth-ish polyline across the width,
     *  closed to the bottom edge. {@code base} is the crest baseline,
     *  {@code amp} the seeded height variation. */
    private void hillBand(StringBuilder sb, String fill, int base, int amp, Rng rng) {
        int steps = 6;
        int dx = BG_W / steps;
        StringBuilder d = new StringBuilder();
        d.append("M0 ").append(BG_H);
        d.append(" L0 ").append(base - rng.nextInt(amp));
        for (int i = 1; i <= steps; i++) {
            int x = i * dx;
            int y = base - rng.nextInt(amp);
            d.append(" L").append(x).append(' ').append(y);
        }
        d.append(" L").append(BG_W).append(' ').append(BG_H).append(" Z");
        sb.append("<path d=\"").append(d).append("\" fill=\"").append(fill).append("\"/>");
    }

    private void trees(StringBuilder sb, Theme t, Rng rng) {
        // ground
        int ground = 560;
        sb.append("<rect y=\"").append(ground).append("\" width=\"").append(BG_W)
          .append("\" height=\"").append(BG_H - ground).append("\" fill=\"").append(t.silFront).append("\"/>");
        int count = 5 + rng.nextInt(4);
        for (int i = 0; i < count; i++) {
            int cx = rng.nextInt(BG_W);
            int h = 160 + rng.nextInt(160);
            int w = 60 + rng.nextInt(60);
            int topY = ground - h;
            String fill = (i % 2 == 0) ? t.silBack : t.silFront;
            // conifer: stacked triangles
            sb.append("<path d=\"M").append(cx).append(' ').append(topY)
              .append(" L").append(cx - w / 2).append(' ').append(ground)
              .append(" L").append(cx + w / 2).append(' ').append(ground)
              .append(" Z\" fill=\"").append(fill).append("\"/>");
        }
    }

    private void window(StringBuilder sb, Theme t, Rng rng) {
        // back wall already the gradient; add a floor band + a lit window.
        int floorY = 540;
        sb.append("<rect y=\"").append(floorY).append("\" width=\"").append(BG_W)
          .append("\" height=\"").append(BG_H - floorY).append("\" fill=\"").append(t.silFront).append("\"/>");
        int wx = 360 + rng.nextInt(200);
        int wy = 120 + rng.nextInt(60);
        int ww = 360, wh = 300;
        // frame
        sb.append("<rect x=\"").append(wx - 12).append("\" y=\"").append(wy - 12)
          .append("\" width=\"").append(ww + 24).append("\" height=\"").append(wh + 24)
          .append("\" fill=\"").append(t.silBack).append("\"/>");
        // glow
        sb.append("<rect x=\"").append(wx).append("\" y=\"").append(wy)
          .append("\" width=\"").append(ww).append("\" height=\"").append(wh)
          .append("\" fill=\"").append(t.accent).append("\" opacity=\"0.85\"/>");
        // mullions
        sb.append("<rect x=\"").append(wx + ww / 2 - 6).append("\" y=\"").append(wy)
          .append("\" width=\"12\" height=\"").append(wh).append("\" fill=\"").append(t.silBack).append("\"/>");
        sb.append("<rect x=\"").append(wx).append("\" y=\"").append(wy + wh / 2 - 6)
          .append("\" width=\"").append(ww).append("\" height=\"12\" fill=\"").append(t.silBack).append("\"/>");
    }

    private void stars(StringBuilder sb, Theme t, Rng rng) {
        int count = 40 + rng.nextInt(30);
        for (int i = 0; i < count; i++) {
            int cx = rng.nextInt(BG_W);
            int cy = rng.nextInt(BG_H * 3 / 4);
            int r = 1 + rng.nextInt(3);
            sb.append("<circle cx=\"").append(cx).append("\" cy=\"").append(cy).append("\" r=\"").append(r)
              .append("\" fill=\"").append(t.accent).append("\" opacity=\"0.8\"/>");
        }
        // horizon band
        sb.append("<rect y=\"").append(BG_H - 120).append("\" width=\"").append(BG_W)
          .append("\" height=\"120\" fill=\"").append(t.silFront).append("\"/>");
    }

    // ---- portraits ---------------------------------------------------------

    private String portrait(long seed, String theme) {
        Rng rng = new Rng(seed);
        int[] base = baseColour(theme, seed);
        String baseHex = rgbToHex(base);
        String light = rgbToHex(mix(base, WHITE, 35));
        String dark = rgbToHex(mix(base, BLACK, 30));
        String panel = rgbToHex(mix(base, BLACK, 62));
        String halo = rgbToHex(mix(base, WHITE, 12));
        String accent = rgbToHex(accentColour(base, seed));

        int headCx = PT_W / 2;
        int headCy = 300 + rng.nextInt(40);
        int headR = 104 + rng.nextInt(18);

        StringBuilder sb = new StringBuilder(768);
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"").append(PT_W)
          .append("\" height=\"").append(PT_H).append("\" viewBox=\"0 0 ").append(PT_W).append(' ').append(PT_H)
          .append("\" data-kind=\"portrait\" data-seed=\"").append(hex(seed)).append("\">");
        // backdrop panel + soft halo behind the head
        sb.append("<rect width=\"").append(PT_W).append("\" height=\"").append(PT_H)
          .append("\" fill=\"").append(panel).append("\"/>");
        sb.append("<circle cx=\"").append(headCx).append("\" cy=\"").append(headCy + 30)
          .append("\" r=\"200\" fill=\"").append(halo).append("\" opacity=\"0.5\"/>");
        // shoulders / bust
        int shoulderTop = headCy + headR - 10;
        sb.append("<path d=\"M96 ").append(PT_H)
          .append(" L150 ").append(shoulderTop + 60)
          .append(" Q").append(headCx).append(' ').append(shoulderTop - 20)
          .append(' ').append(PT_W - 150).append(' ').append(shoulderTop + 60)
          .append(" L").append(PT_W - 96).append(' ').append(PT_H)
          .append(" Z\" fill=\"").append(baseHex).append("\"/>");
        // neck
        sb.append("<rect x=\"").append(headCx - 34).append("\" y=\"").append(headCy + headR - 60)
          .append("\" width=\"68\" height=\"90\" fill=\"").append(dark).append("\"/>");
        // head
        sb.append("<circle cx=\"").append(headCx).append("\" cy=\"").append(headCy)
          .append("\" r=\"").append(headR).append("\" fill=\"").append(light).append("\"/>");
        // seeded hair accent — a cap arcing over the crown
        int hairDrop = 30 + rng.nextInt(40);
        sb.append("<path d=\"M").append(headCx - headR).append(' ').append(headCy)
          .append(" Q").append(headCx).append(' ').append(headCy - headR - hairDrop)
          .append(' ').append(headCx + headR).append(' ').append(headCy)
          .append(" Q").append(headCx).append(' ').append(headCy - headR / 2)
          .append(' ').append(headCx - headR).append(' ').append(headCy)
          .append(" Z\" fill=\"").append(accent).append("\"/>");
        sb.append("</svg>");
        return sb.toString();
    }

    // ---- canonical character portraits -------------------------------------

    /**
     * Head-and-shoulders render of the canonical parametric humanoid, at the
     * exact dimensions the flat bust portrait uses so the two are drop-in
     * interchangeable anywhere a VN portrait slots in.
     *
     * <p>An absent (or empty — it carries no choices) document falls back to
     * the default spec, with the 'body' (outfit) colour going through the SAME
     * seed path as the flat portrait's base colour: a hex {@code theme} wins,
     * otherwise a hue seeded from the slug. A character keeps its signature
     * colour whether the project uses flat or canonical portraits.
     */
    private String characterPortrait(long seed, String theme, Map<String, ?> character) {
        CharacterSpec spec;
        if (character == null || character.isEmpty()) {
            spec = CharacterSpec.builder()
                    .color("body", rgbToHex(baseColour(theme, seed)))
                    .build();
        } else {
            try {
                spec = CharacterDocument.fromMap(character);
            } catch (IllegalArgumentException e) {
                // fromMap's whole point is strict values; rethrow as the 400 the
                // API contract promises rather than an anonymous 500.
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
            }
        }
        return AvatarSvgRenderer.renderPortrait(spec, spec.expression(), PT_W, PT_H);
    }

    // ---- colour helpers ----------------------------------------------------

    private static final int[] WHITE = {255, 255, 255};
    private static final int[] BLACK = {0, 0, 0};

    /** Base portrait colour: a valid hex {@code theme} wins; otherwise a hue
     *  seeded from the input hash at a fixed pleasant saturation/lightness. */
    private int[] baseColour(String theme, long seed) {
        int[] parsed = tryParseHex(theme);
        if (parsed != null) return parsed;
        int hue = (int) Math.floorMod(seed, 360L);
        return hslToRgb(hue, 55, 58);
    }

    /** A complementary-ish accent hue for the hair cap, seeded off the base. */
    private int[] accentColour(int[] base, long seed) {
        int hue = rgbHue(base);
        int shifted = Math.floorMod(hue + 150 + (int) Math.floorMod(seed >>> 8, 60L), 360);
        return hslToRgb(shifted, 45, 32);
    }

    private Theme pickTheme(String theme, long seed) {
        if (theme != null) {
            String norm = theme.trim().toUpperCase(Locale.ROOT);
            for (Theme t : THEMES) if (t.name().equals(norm)) return t;
        }
        return THEMES[(int) Math.floorMod(seed, THEMES.length)];
    }

    private static int[] tryParseHex(String s) {
        if (s == null) return null;
        String h = s.trim();
        if (h.isEmpty() || h.charAt(0) != '#') return null;
        h = h.substring(1);
        try {
            if (h.length() == 3) {
                int r = Integer.parseInt(h.substring(0, 1), 16);
                int g = Integer.parseInt(h.substring(1, 2), 16);
                int b = Integer.parseInt(h.substring(2, 3), 16);
                return new int[]{r * 17, g * 17, b * 17};
            } else if (h.length() == 6) {
                return new int[]{
                        Integer.parseInt(h.substring(0, 2), 16),
                        Integer.parseInt(h.substring(2, 4), 16),
                        Integer.parseInt(h.substring(4, 6), 16)};
            }
        } catch (NumberFormatException ignored) {
            return null;
        }
        return null;
    }

    /** Integer lerp of {@code a} toward {@code b} by {@code pct}%. */
    private static int[] mix(int[] a, int[] b, int pct) {
        int[] out = new int[3];
        for (int i = 0; i < 3; i++) out[i] = a[i] + (b[i] - a[i]) * pct / 100;
        return out;
    }

    private static String rgbToHex(int[] rgb) {
        return String.format(Locale.ROOT, "#%02x%02x%02x",
                clamp(rgb[0]), clamp(rgb[1]), clamp(rgb[2]));
    }

    private static int clamp(int v) { return v < 0 ? 0 : Math.min(v, 255); }

    /** Deterministic HSL→RGB. h in [0,360), s,l in [0,100]. */
    private static int[] hslToRgb(int h, int s, int l) {
        // Fixed-point (per-mille) to stay integer + deterministic.
        int sN = s * 10, lN = l * 10;                      // 0..1000
        int c = (1000 - Math.abs(2 * lN - 1000)) * sN / 1000;
        int hp = h % 360;
        int x = c * (1000 - Math.abs((hp * 1000 / 60) % 2000 - 1000)) / 1000;
        int m = lN - c / 2;
        int r1, g1, b1;
        if (hp < 60)       { r1 = c; g1 = x; b1 = 0; }
        else if (hp < 120) { r1 = x; g1 = c; b1 = 0; }
        else if (hp < 180) { r1 = 0; g1 = c; b1 = x; }
        else if (hp < 240) { r1 = 0; g1 = x; b1 = c; }
        else if (hp < 300) { r1 = x; g1 = 0; b1 = c; }
        else               { r1 = c; g1 = 0; b1 = x; }
        return new int[]{(r1 + m) * 255 / 1000, (g1 + m) * 255 / 1000, (b1 + m) * 255 / 1000};
    }

    /** Approximate hue of an RGB triple, in [0,360). */
    private static int rgbHue(int[] rgb) {
        int r = rgb[0], g = rgb[1], b = rgb[2];
        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        int d = max - min;
        if (d == 0) return 0;
        int hue;
        if (max == r) hue = (60 * (g - b) / d + 360) % 360;
        else if (max == g) hue = 60 * (b - r) / d + 120;
        else hue = 60 * (r - g) / d + 240;
        return Math.floorMod(hue, 360);
    }

    // ---- deterministic hashing + PRNG -------------------------------------

    /** Stable 64-bit FNV-1a over {@code slug \0 theme}. Deterministic across
     *  JVMs (no String.hashCode reliance issues; pure byte fold). */
    private static long seed(String slug, String theme) {
        long h = 0xcbf29ce484222325L;
        h = fold(h, slug == null ? "" : slug);
        h = fold(h, " ");
        h = fold(h, theme == null ? "" : theme);
        return h;
    }

    private static long fold(long h, String s) {
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            h ^= (b & 0xff);
            h *= 0x100000001b3L;
        }
        return h;
    }

    private static String hex(long seed) {
        return String.format(Locale.ROOT, "%016x", seed);
    }

    /** SplitMix64 — tiny, fast, deterministic. */
    private static final class Rng {
        private long state;
        Rng(long seed) { this.state = seed; }
        private long next() {
            long z = (state += 0x9E3779B97F4A7C15L);
            z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
            z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
            return z ^ (z >>> 31);
        }
        /** Non-negative int in [0, bound). */
        int nextInt(int bound) {
            if (bound <= 0) return 0;
            return (int) (Long.remainderUnsigned(next(), bound));
        }
    }
}
