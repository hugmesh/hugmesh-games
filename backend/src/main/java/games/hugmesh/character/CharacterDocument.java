package games.hugmesh.character;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The portable interchange format — one character, movable between generators,
 * services and games.
 *
 * <p>This is what makes a character <b>transferable</b>: a maker exports it, a
 * different maker imports it, the BFF stores one per saved avatar, and a game
 * with its own 2D or 3D character generation reads it without knowing anything
 * about our tables. Versioned by {@code schemaVersion} so a v2 can add fields
 * without breaking a v1 reader.
 *
 * <pre>
 * { "schemaVersion": 1, "kind": "hugmesh.character", "name": "…",
 *   "style": "lowpoly",
 *   "proportions": { "height": 1.0, "chest": 1.2, "legLength": 0.9, … },
 *   "shape":  { "body": "slim", "head": "round", "hair": "short", "outfit": "tunic" },
 *   "items":  { "hat": "cap", "heldMain": "sword", "heldOff": "shield",
 *               "back": "cape", "face": "none" },
 *   "colors": { "body": "#…", "skin": "#…", "hat": "#…", "hair": "#…",
 *               "accent": "#…", "item": "#…", "eye": "#…" },
 *   "texture": { "assetId": null }, "expression": "neutral" }
 * </pre>
 *
 * <p><b>No JSON library is used here.</b> The document is exchanged as a plain
 * {@link Map}, which every consuming service's existing Jackson wiring
 * serialises and deserialises for free — keeping this package dependency-free so
 * {@code make sync-character-model} can copy it verbatim into services whose
 * dependency sets differ.
 *
 * <p>Unknown keys are ignored and missing keys take their defaults, so a v2
 * document degrades gracefully into a v1 reader rather than failing. Values that
 * ARE present are fully validated by {@link CharacterSpec} — an import is not a
 * trust boundary bypass.
 */
public final class CharacterDocument {

    private CharacterDocument() {}

    public static final int SCHEMA_VERSION = 1;
    public static final String KIND = "hugmesh.character";

    /** Serialises {@code spec} to the portable document. */
    public static Map<String, Object> toMap(CharacterSpec spec, String name) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("schemaVersion", SCHEMA_VERSION);
        doc.put("kind", KIND);
        doc.put("name", name == null ? "" : name);
        doc.put("style", spec.renderStyle());

        Map<String, Object> props = new LinkedHashMap<>();
        for (CharacterSpec.Bound b : CharacterSpec.PROPORTIONS) props.put(b.key(), spec.p(b.key()));
        doc.put("proportions", props);

        doc.put("shape", ordered(
                "body", spec.bodyShape(),
                "head", spec.headShape(),
                "hair", spec.hairStyle(),
                "outfit", spec.outfitType()));

        doc.put("items", ordered(
                "hat", spec.hatType(),
                "heldMain", spec.heldMain(),
                "heldOff", spec.heldOff(),
                "back", spec.backItem(),
                "face", spec.faceItem()));

        Map<String, Object> colors = new LinkedHashMap<>();
        for (CharacterSpec.ColorSlot c : CharacterSpec.COLORS) colors.put(c.key(), spec.color(c.key()));
        doc.put("colors", colors);

        Map<String, Object> tex = new LinkedHashMap<>();
        tex.put("assetId", spec.skinTextureAssetId() == null ? null : spec.skinTextureAssetId().toString());
        doc.put("texture", tex);

        doc.put("expression", spec.expression());
        return doc;
    }

    /**
     * Parses a portable document into a validated spec.
     *
     * @throws IllegalArgumentException on an unsupported schema version, a
     *         non-character document, or any present-but-invalid value
     */
    public static CharacterSpec fromMap(Map<String, ?> doc) {
        if (doc == null) return CharacterSpec.defaults();

        Object kind = doc.get("kind");
        if (kind != null && !KIND.equals(kind)) {
            throw new IllegalArgumentException("not a " + KIND + " document (kind='" + kind + "')");
        }
        Integer version = asInt(doc.get("schemaVersion"));
        if (version != null && version > SCHEMA_VERSION) {
            throw new IllegalArgumentException("character document schemaVersion " + version
                    + " is newer than this reader supports (" + SCHEMA_VERSION + ")");
        }

        CharacterSpec.Builder b = CharacterSpec.builder()
                .renderStyle(asString(doc.get("style")))
                .expression(asString(doc.get("expression")));

        Map<String, ?> props = asMap(doc.get("proportions"));
        for (CharacterSpec.Bound bound : CharacterSpec.PROPORTIONS) {
            b.proportion(bound.key(), asDouble(props.get(bound.key())));
        }

        Map<String, ?> shape = asMap(doc.get("shape"));
        b.bodyShape(asString(shape.get("body")))
                .headShape(asString(shape.get("head")))
                .hairStyle(asString(shape.get("hair")))
                .outfitType(asString(shape.get("outfit")));

        Map<String, ?> items = asMap(doc.get("items"));
        b.hatType(asString(items.get("hat")))
                .heldMain(asString(items.get("heldMain")))
                .heldOff(asString(items.get("heldOff")))
                .backItem(asString(items.get("back")))
                .faceItem(asString(items.get("face")));

        Map<String, ?> colors = asMap(doc.get("colors"));
        for (CharacterSpec.ColorSlot slot : CharacterSpec.COLORS) {
            b.color(slot.key(), asString(colors.get(slot.key())));
        }

        String assetId = asString(asMap(doc.get("texture")).get("assetId"));
        if (assetId != null && !assetId.isBlank()) {
            try {
                b.skinTextureAssetId(UUID.fromString(assetId));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("texture.assetId must be a UUID");
            }
        }
        return b.build();
    }

    /** The {@code name} field, or {@code ""} — not part of the spec itself. */
    public static String nameOf(Map<String, ?> doc) {
        String n = doc == null ? null : asString(doc.get("name"));
        return n == null ? "" : n;
    }

    // ── Char3DConfig bridge ─────────────────────────────────────────────────

    /**
     * Lossy projection onto the {@code Char3DConfig} v1 shape that the XR /
     * 3D-chat character assembler consumes.
     *
     * <p>That assembler lives in a <b>vendored, read-only</b> tree
     * ({@code games/shadows-of-the-net/scripts/character_assembler.gd}, whose
     * canonical copy is in a different repository), so this mapping is how our
     * characters reach it <b>without editing their files</b> — we speak their
     * format rather than changing it.
     *
     * <p>Lossy in one direction only, and deliberately: their model is
     * creature-first (29 head families, snout/horn/ear traits, 52 blendshapes)
     * while ours is humanoid-first (twelve continuous proportions, item slots).
     * Held items, back items, face items and per-limb proportions have no
     * counterpart there and are dropped; {@code build} and {@code height} are
     * derived so the silhouette survives the trip.
     */
    public static Map<String, Object> toChar3DConfig(CharacterSpec spec, String name) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schemaVersion", 1);
        out.put("name", name == null ? "" : name);

        // Their `build` is 0..1 girth; ours is a coarse preset plus fine sliders.
        double girth = switch (spec.bodyShape()) {
            case "stocky" -> 0.68;
            case "round" -> 0.88;
            default -> 0.32;
        };
        double build = clamp01(girth * 0.6 + (spec.p("chest") - 0.7) / 0.7 * 0.4);
        // Their `height` is 0.5..1.5; ours is 0.7..1.4 — pass through, clamped.
        double height = Math.max(0.5, Math.min(1.5, spec.p("height")));

        out.put("body", ordered("archetype", "humanoid", "build", build, "height", height));
        out.put("head", ordered(
                "family", "humanoid",
                "variant", spec.headShape(),
                "snoutLength", 0.0,
                "earStyle", "none",
                "hornStyle", "none"));
        out.put("parts", ordered(
                "tail", "none",
                "wings", "wings".equals(spec.backItem()) ? "feathered" : "none",
                "ears", "none",
                "feature", "none",
                "accessory", spec.hatType(),
                "markings", "none",
                "hair", spec.hairStyle()));
        out.put("materials", ordered(
                "fur", spec.color("body"),
                "accent", spec.color("accent"),
                "detail", spec.color("item"),
                "line", spec.color("hat"),
                "iris", spec.color("eye"),
                "skin", spec.color("skin"),
                "hair", spec.color("hair")));
        // They know anime/toon/classic; our lowpoly reads closest to toon.
        out.put("style", spec.lowPoly() ? "toon" : "classic");
        return out;
    }

    // ── Coercion helpers (tolerant readers, strict values) ───────────────────

    private static Map<String, Object> ordered(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ?> asMap(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, ?>) m : Map.of();
    }

    private static String asString(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static Double asDouble(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.doubleValue();
        try {
            return Double.valueOf(String.valueOf(o));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("expected a number, got '" + o + "'");
        }
    }

    private static Integer asInt(Object o) {
        Double d = asDouble(o);
        return d == null ? null : (int) (double) d;
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
