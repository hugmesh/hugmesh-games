package games.hugmesh.rpgbuilder.service;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Deterministic, house-style procedural SVG generator for the RPG maker.
 *
 * <p>Given a {@code (kind, slug, theme)} triple it returns flat-vector
 * "placeholder" art as SVG bytes. Output is a <b>pure function</b> of its
 * inputs — the same triple always yields byte-identical SVG — which is what
 * makes it trivially unit-testable and lets a fresh project get tasteful art
 * without a hand-upload (closing maker gap 3: "no way to CREATE art from
 * inside the maker").
 *
 * <p>House style: flat vector art on a shared geometry canon (rounded rects,
 * plain fills, a small seeded palette). No gradients, no external fonts, no
 * randomness or timestamps — every coordinate/colour derives from a
 * {@link SplitMix64} stream seeded by an FNV-1a hash of the inputs.
 *
 * <p>Supported kinds — the RPG asset kinds that most need art:
 * <ul>
 *   <li>{@code tileset} — a 2×2 top-down tilesheet (floor / wall / ground /
 *       door) themed by a seeded {@link Theme} enum.</li>
 *   <li>{@code sprite}  — a top-down character token (body disc + facing mark).</li>
 *   <li>{@code portrait}— an NPC bust silhouette in a seeded colour.</li>
 *   <li>{@code icon}    — an item icon: a seeded glyph on a rounded rect.</li>
 * </ul>
 *
 * <p>All attribute values are single-quoted so the emitted markup is
 * well-formed XML with no Java-string escaping noise.
 */
@Service
public class ProceduralAssetService {

    /** The kinds {@link #generate} knows how to draw. */
    public static final Set<String> SUPPORTED_KINDS = Set.of("tileset", "sprite", "portrait", "icon");

    public boolean supports(String kind) {
        return kind != null && SUPPORTED_KINDS.contains(kind);
    }

    /**
     * Render deterministic house-style SVG bytes for {@code (kind, slug, theme)}.
     * {@code theme} is an optional free-text seed (null/blank ⇒ "").
     *
     * @throws IllegalArgumentException if {@code kind} is not in {@link #SUPPORTED_KINDS}
     */
    public byte[] generate(String kind, String slug, String theme) {
        if (!supports(kind)) {
            throw new IllegalArgumentException("unsupported procedural kind: " + kind);
        }
        SplitMix64 rng = new SplitMix64(seed(kind, slug, theme));
        String svg = switch (kind) {
            case "tileset"  -> tileset(rng);
            case "sprite"   -> sprite(rng);
            case "portrait" -> portrait(rng);
            case "icon"     -> icon(rng);
            default -> throw new IllegalArgumentException("unsupported procedural kind: " + kind);
        };
        return svg.getBytes(StandardCharsets.UTF_8);
    }

    // ---- palettes -----------------------------------------------------------

    /** Soft flat backgrounds for portraits / icons / sprites. */
    private static final String[] BG = {
            "#eef1f8", "#e7ecf7", "#f3eefc", "#eafaf1", "#fdf3e7", "#eaf3fb"
    };
    /** Saturated flat "ink" fills — the hugmesh purples plus complements. */
    private static final String[] INK = {
            "#7c4dff", "#b388ff", "#3f6fd8", "#2aa16b", "#e0873a", "#c8506a", "#4b9dbd", "#6b4bb0"
    };

    /** A seeded top-down tile theme: 6 flat colours for the 2×2 sheet. */
    private record Theme(String name, String floor, String floorLine,
                         String wall, String wallLine, String ground, String door, String accent) {}

    private static final Theme[] THEMES = {
            new Theme("stone", "#c9cfdd", "#aab2c6", "#7b8298", "#5f6478", "#b7c0a6", "#8a6d4b", "#5b6070"),
            new Theme("grass", "#a8d08c", "#8fbd72", "#7d6b4f", "#5f5138", "#c7e0a8", "#6d4c2f", "#4e7a3a"),
            new Theme("wood",  "#c9a06a", "#ad8552", "#6f4f34", "#523822", "#b98f57", "#4b3322", "#8a5a2f"),
            new Theme("sand",  "#e6d3a3", "#d3bd87", "#b79a63", "#9a7f4c", "#decea9", "#8a6d3f", "#b08a50"),
    };

    // ---- generators ---------------------------------------------------------

    /** 128×128 tilesheet: floor (TL) · wall (TR) · ground (BL) · door (BR). */
    private String tileset(SplitMix64 rng) {
        Theme t = THEMES[rng.nextInt(THEMES.length)];
        StringBuilder s = svgOpen(128, 128);

        // floor — base + a 4×4 grout grid.
        s.append("<rect x='0' y='0' width='64' height='64' fill='").append(t.floor()).append("'/>");
        for (int i = 16; i < 64; i += 16) {
            s.append("<line x1='").append(i).append("' y1='0' x2='").append(i).append("' y2='64' stroke='")
             .append(t.floorLine()).append("' stroke-width='2'/>");
            s.append("<line x1='0' y1='").append(i).append("' x2='64' y2='").append(i).append("' stroke='")
             .append(t.floorLine()).append("' stroke-width='2'/>");
        }

        // wall — base + offset brick courses.
        s.append("<rect x='64' y='0' width='64' height='64' fill='").append(t.wall()).append("'/>");
        for (int row = 0; row < 4; row++) {
            int y = row * 16;
            s.append("<line x1='64' y1='").append(y).append("' x2='128' y2='").append(y)
             .append("' stroke='").append(t.wallLine()).append("' stroke-width='2'/>");
            int off = (row % 2 == 0) ? 0 : 16;
            for (int bx = 64 + off; bx < 128; bx += 32) {
                s.append("<line x1='").append(bx).append("' y1='").append(y).append("' x2='").append(bx)
                 .append("' y2='").append(y + 16).append("' stroke='").append(t.wallLine())
                 .append("' stroke-width='2'/>");
            }
        }

        // ground — base + seeded speckles.
        s.append("<rect x='0' y='64' width='64' height='64' fill='").append(t.ground()).append("'/>");
        for (int i = 0; i < 6; i++) {
            int cx = 6 + rng.nextInt(52);
            int cy = 70 + rng.nextInt(52);
            int r = 2 + rng.nextInt(3);
            s.append("<circle cx='").append(cx).append("' cy='").append(cy).append("' r='").append(r)
             .append("' fill='").append(t.accent()).append("'/>");
        }

        // door — frame + slab + knob.
        s.append("<rect x='64' y='64' width='64' height='64' fill='").append(t.floor()).append("'/>");
        s.append("<rect x='78' y='74' width='36' height='54' rx='4' fill='").append(t.door()).append("'/>");
        s.append("<circle cx='106' cy='101' r='3' fill='").append(t.accent()).append("'/>");

        return s.append("</svg>").toString();
    }

    /** 64×64 top-down character token: rounded backing + body disc + facing mark. */
    private String sprite(SplitMix64 rng) {
        String bg = BG[rng.nextInt(BG.length)];
        String body = INK[rng.nextInt(INK.length)];
        String accent = INK[rng.nextInt(INK.length)];
        StringBuilder s = svgOpen(64, 64);
        s.append("<rect x='3' y='3' width='58' height='58' rx='12' fill='").append(bg)
         .append("' stroke='").append(body).append("' stroke-width='2'/>");
        s.append("<circle cx='32' cy='34' r='16' fill='").append(body).append("'/>");
        // facing marker — a small triangle pointing "north" (up).
        s.append("<polygon points='32,10 39,22 25,22' fill='").append(accent).append("'/>");
        s.append("<circle cx='32' cy='34' r='6' fill='").append(bg).append("'/>");
        return s.append("</svg>").toString();
    }

    /** 128×160 NPC bust silhouette: contrasting backing + shoulders + head. */
    private String portrait(SplitMix64 rng) {
        String bg = BG[rng.nextInt(BG.length)];
        String fig = INK[rng.nextInt(INK.length)];
        StringBuilder s = svgOpen(128, 160);
        s.append("<rect x='0' y='0' width='128' height='160' rx='10' fill='").append(bg).append("'/>");
        // shoulders — a wide ellipse anchored below the frame reads as a bust.
        s.append("<ellipse cx='64' cy='172' rx='54' ry='58' fill='").append(fig).append("'/>");
        // neck + head.
        s.append("<rect x='54' y='96' width='20' height='24' fill='").append(fig).append("'/>");
        s.append("<circle cx='64' cy='72' r='34' fill='").append(fig).append("'/>");
        return s.append("</svg>").toString();
    }

    /** 64×64 item icon: rounded backing + one seeded glyph. */
    private String icon(SplitMix64 rng) {
        String bg = BG[rng.nextInt(BG.length)];
        String glyph = INK[rng.nextInt(INK.length)];
        int which = rng.nextInt(6);
        StringBuilder s = svgOpen(64, 64);
        s.append("<rect x='3' y='3' width='58' height='58' rx='14' fill='").append(bg)
         .append("' stroke='").append(glyph).append("' stroke-width='2'/>");
        switch (which) {
            case 0 -> // star
                s.append("<polygon points='32,12 37,26 52,26 40,35 45,50 32,41 19,50 24,35 12,26 27,26' fill='")
                 .append(glyph).append("'/>");
            case 1 -> // gem / diamond
                s.append("<polygon points='32,12 50,32 32,52 14,32' fill='").append(glyph)
                 .append("'/><polygon points='32,12 32,52 14,32' fill='").append(bg)
                 .append("' opacity='0.18'/>");
            case 2 -> // potion
                s.append("<rect x='28' y='14' width='8' height='12' fill='").append(glyph)
                 .append("'/><circle cx='32' cy='40' r='15' fill='").append(glyph).append("'/>");
            case 3 -> // key
                s.append("<circle cx='24' cy='28' r='10' fill='none' stroke='").append(glyph)
                 .append("' stroke-width='5'/><rect x='30' y='26' width='22' height='5' fill='").append(glyph)
                 .append("'/><rect x='44' y='31' width='5' height='8' fill='").append(glyph).append("'/>");
            case 4 -> // coin / ring
                s.append("<circle cx='32' cy='32' r='18' fill='").append(glyph)
                 .append("'/><circle cx='32' cy='32' r='11' fill='").append(bg).append("'/>");
            default -> // shield
                s.append("<path d='M32 12 L52 20 L52 36 Q52 50 32 54 Q12 50 12 36 L12 20 Z' fill='")
                 .append(glyph).append("'/>");
        }
        return s.append("</svg>").toString();
    }

    // ---- primitives ---------------------------------------------------------

    private static StringBuilder svgOpen(int w, int h) {
        return new StringBuilder()
                .append("<svg xmlns='http://www.w3.org/2000/svg' width='").append(w)
                .append("' height='").append(h).append("' viewBox='0 0 ").append(w).append(' ').append(h)
                .append("'>");
    }

    /** FNV-1a 64-bit over the NUL-joined inputs — a stable, order-sensitive seed. */
    private static long seed(String kind, String slug, String theme) {
        String s = nz(kind) + " " + nz(slug) + " " + nz(theme);
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x100000001b3L;
        }
        return h;
    }

    private static String nz(String s) { return s == null ? "" : s; }

    /** Deterministic SplitMix64 — same seed ⇒ same stream ⇒ byte-identical SVG. */
    private static final class SplitMix64 {
        private long state;
        SplitMix64(long seed) { this.state = seed; }
        private long next() {
            long z = (state += 0x9E3779B97F4A7C15L);
            z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
            z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
            return z ^ (z >>> 31);
        }
        int nextInt(int bound) { return (int) Math.floorMod(next(), bound); }
    }
}
