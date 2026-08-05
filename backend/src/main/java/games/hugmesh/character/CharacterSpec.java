package games.hugmesh.character;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The canonical, validated parametric humanoid character.
 *
 * <p>This is the ONE source of truth for what a character can be. Every
 * representation — the Godot 3D rig ({@link HumanoidRig}), the 2D SVG, the glTF
 * export, the portable {@link CharacterDocument} — derives from an instance of
 * this class. Nothing re-declares a token set, a bound, or a default anywhere
 * else in the platform.
 *
 * <p><b>Zero dependencies on purpose.</b> No Spring, no Jackson, no third-party
 * anything: this package is canonical in {@code rpg-builder} and synced verbatim
 * into the other services by {@code make sync-character-model} (mirroring the
 * existing {@code make sync-bridge-addon} idiom — the three services are
 * independent Maven projects with per-directory Docker build contexts, so a
 * shared module would mean repo-wide build surgery). Each consumer carries a
 * byte-identity test, so drift fails the build.
 *
 * <p><b>Null means "use the default"; a PRESENT bad value is an error.</b>
 * Validation throws {@link IllegalArgumentException} SYNCHRONOUSLY so a WebFlux
 * controller's {@code @ExceptionHandler} actually sees it (an exception
 * signalled inside a returned {@code Mono} surfaces as a 500, not the promised
 * 400). An out-of-range number is never silently clamped — clamping would turn
 * an accidentally-omitted primitive {@code 0.0} into a plausible-looking wrong
 * value instead of a loud rejection.
 *
 * @see <a href="../../../../../../../docs/wiki/decisions/028-parametric-humanoid-avatars.md">decision 028</a>
 */
public final class CharacterSpec {

    // ── Token whitelists ────────────────────────────────────────────────────
    // Mirrored by the DB CHECK constraints (rpg-builder changelog 009) and the
    // Angular maker constants. Extending one means extending all three plus the
    // HumanoidRig geometry arm — decision 028 lists the full checklist.

    /** {@code lowpoly} is N64 mode: low segment counts + flat shading. */
    public static final Set<String> RENDER_STYLES = Set.of("smooth", "lowpoly");
    public static final Set<String> BODY_SHAPES = Set.of("slim", "stocky", "round");
    public static final Set<String> HEAD_SHAPES = Set.of("round", "square", "oval", "tapered");
    public static final Set<String> HAIR_STYLES =
            Set.of("none", "short", "long", "ponytail", "spiky", "bun", "braid");
    public static final Set<String> OUTFIT_TYPES =
            Set.of("robe", "tunic", "armor", "dress", "jumpsuit", "bare");

    public static final Set<String> HAT_TYPES =
            Set.of("none", "cap", "cone", "top_hat", "helm", "crown", "hood", "bandana", "straw");
    public static final Set<String> HELD_MAIN =
            Set.of("none", "sword", "staff", "axe", "bow", "wand", "pickaxe", "lantern", "book");
    public static final Set<String> HELD_OFF =
            Set.of("none", "shield", "torch", "book", "lantern", "orb");
    public static final Set<String> BACK_ITEMS =
            Set.of("none", "cape", "backpack", "wings", "quiver", "scabbard");
    public static final Set<String> FACE_ITEMS =
            Set.of("none", "glasses", "mask", "visor", "eyepatch");

    /** Render-time face pose, never baked geometry. See {@link Expressions}. */
    public static final Set<String> EXPRESSIONS = Set.of("neutral", "happy", "sad", "angry",
            "surprised", "wink", "sleepy", "determined", "shout");

    // ── Proportions ─────────────────────────────────────────────────────────

    /**
     * One tunable body proportion and the humanoid range it may occupy.
     *
     * <p>The bounds ARE the "reasonable humanoid" contract of decision 028: at
     * either extreme the result is still recognisably bipedal — no zero-length
     * limbs, no arm longer than the body, no head bigger than the torso. They
     * are what lets the maker expose free-running sliders safely.
     */
    public record Bound(String key, double min, double max, double dflt, String label) {
        public boolean contains(double v) {
            return v >= min && v <= max;
        }
    }

    /** Ordered so the maker UI presents them consistently. */
    public static final List<Bound> PROPORTIONS = List.of(
            new Bound("height", 0.70, 1.40, 1.00, "Height"),
            new Bound("head", 0.70, 1.50, 1.00, "Head size"),
            new Bound("neck", 0.60, 1.60, 1.00, "Neck"),
            new Bound("chest", 0.70, 1.40, 1.00, "Chest"),
            new Bound("waist", 0.60, 1.50, 1.00, "Waist"),
            new Bound("shoulder", 0.70, 1.40, 1.00, "Shoulders"),
            new Bound("armLength", 0.75, 1.30, 1.00, "Arm length"),
            new Bound("armThickness", 0.60, 1.50, 1.00, "Arm thickness"),
            new Bound("legLength", 0.75, 1.30, 1.00, "Leg length"),
            new Bound("legThickness", 0.60, 1.50, 1.00, "Leg thickness"),
            new Bound("hand", 0.60, 1.60, 1.00, "Hands"),
            new Bound("foot", 0.60, 1.60, 1.00, "Feet"));

    private static final Map<String, Bound> BY_KEY;

    static {
        Map<String, Bound> m = new LinkedHashMap<>();
        for (Bound b : PROPORTIONS) m.put(b.key(), b);
        BY_KEY = Map.copyOf(m);
    }

    /** Bound for {@code key}, or {@code null} when it is not a proportion. */
    public static Bound bound(String key) {
        return BY_KEY.get(key);
    }

    // ── Colours ─────────────────────────────────────────────────────────────

    /** One recolourable region and its default. */
    public record ColorSlot(String key, String dflt, String label) {}

    public static final List<ColorSlot> COLORS = List.of(
            new ColorSlot("body", "#3f59b3", "Outfit"),
            new ColorSlot("skin", "#e6c8a0", "Skin"),
            new ColorSlot("hat", "#202020", "Hat"),
            new ColorSlot("hair", "#3a2a1c", "Hair"),
            new ColorSlot("accent", "#d8b45a", "Trim"),
            new ColorSlot("item", "#8a8f98", "Items"),
            new ColorSlot("eye", "#2b2b33", "Eyes"));

    private static final Pattern HEX_COLOR = Pattern.compile("^#[0-9a-fA-F]{6}$");

    public static final String DEFAULT_RENDER_STYLE = "smooth";
    public static final String DEFAULT_BODY_SHAPE = "slim";
    public static final String DEFAULT_HEAD_SHAPE = "round";
    public static final String DEFAULT_HAIR_STYLE = "short";
    public static final String DEFAULT_OUTFIT_TYPE = "robe";
    public static final String DEFAULT_ITEM = "none";
    public static final String DEFAULT_EXPRESSION = "neutral";

    // ── State ───────────────────────────────────────────────────────────────

    private final String renderStyle;
    private final String bodyShape;
    private final String headShape;
    private final String hairStyle;
    private final String outfitType;
    private final String hatType;
    private final String heldMain;
    private final String heldOff;
    private final String backItem;
    private final String faceItem;
    private final String expression;
    private final Map<String, Double> proportions;
    private final Map<String, String> colors;
    private final UUID skinTextureAssetId;

    private CharacterSpec(Builder b) {
        this.renderStyle = b.renderStyle;
        this.bodyShape = b.bodyShape;
        this.headShape = b.headShape;
        this.hairStyle = b.hairStyle;
        this.outfitType = b.outfitType;
        this.hatType = b.hatType;
        this.heldMain = b.heldMain;
        this.heldOff = b.heldOff;
        this.backItem = b.backItem;
        this.faceItem = b.faceItem;
        this.expression = b.expression;
        this.proportions = Map.copyOf(b.proportions);
        this.colors = Map.copyOf(b.colors);
        this.skinTextureAssetId = b.skinTextureAssetId;
    }

    public String renderStyle() { return renderStyle; }
    public String bodyShape() { return bodyShape; }
    public String headShape() { return headShape; }
    public String hairStyle() { return hairStyle; }
    public String outfitType() { return outfitType; }
    public String hatType() { return hatType; }
    public String heldMain() { return heldMain; }
    public String heldOff() { return heldOff; }
    public String backItem() { return backItem; }
    public String faceItem() { return faceItem; }
    public String expression() { return expression; }
    public UUID skinTextureAssetId() { return skinTextureAssetId; }

    /** Immutable, always complete — every key in {@link #PROPORTIONS} present. */
    public Map<String, Double> proportions() { return proportions; }

    /** Immutable, always complete — every key in {@link #COLORS} present. */
    public Map<String, String> colors() { return colors; }

    /** Validated proportion, defaulted when absent. Never throws. */
    public double p(String key) {
        Double v = proportions.get(key);
        if (v != null) return v;
        Bound b = BY_KEY.get(key);
        return b == null ? 1.0 : b.dflt();
    }

    /** Validated {@code #rrggbb} for a colour slot, defaulted when absent. */
    public String color(String key) {
        String v = colors.get(key);
        if (v != null) return v;
        for (ColorSlot s : COLORS) if (s.key().equals(key)) return s.dflt();
        return "#ffffff";
    }

    public boolean lowPoly() { return "lowpoly".equals(renderStyle); }

    /** True when the outfit covers the limbs (armour/jumpsuit) rather than skin. */
    public boolean limbsClothed() {
        return "armor".equals(outfitType) || "jumpsuit".equals(outfitType);
    }

    /** Skirt length in metres for the outfit, or 0 when it has none. */
    public double skirtLength() {
        return switch (outfitType) {
            case "robe" -> 0.62;
            case "dress" -> 0.44;
            case "tunic" -> 0.24;
            default -> 0.0;
        };
    }

    /** All defaults — what a project with no saved row renders as. */
    public static CharacterSpec defaults() {
        return builder().build();
    }

    public static Builder builder() { return new Builder(); }

    /** A copy with the expression replaced — used by expression stills. */
    public CharacterSpec withExpression(String expr) {
        return toBuilder().expression(expr).build();
    }

    public Builder toBuilder() {
        Builder b = new Builder();
        b.renderStyle = renderStyle;
        b.bodyShape = bodyShape;
        b.headShape = headShape;
        b.hairStyle = hairStyle;
        b.outfitType = outfitType;
        b.hatType = hatType;
        b.heldMain = heldMain;
        b.heldOff = heldOff;
        b.backItem = backItem;
        b.faceItem = faceItem;
        b.expression = expression;
        b.proportions = new LinkedHashMap<>(proportions);
        b.colors = new LinkedHashMap<>(colors);
        b.skinTextureAssetId = skinTextureAssetId;
        return b;
    }

    // ── Validation helpers (shared by the builder and by callers) ────────────

    /**
     * Whitelisted token, or the default when {@code v} is null/blank.
     *
     * @throws IllegalArgumentException if present and not in {@code allowed}
     */
    public static String token(String v, Set<String> allowed, String dflt, String field) {
        if (v == null || v.isBlank()) return dflt;
        if (!allowed.contains(v)) {
            throw new IllegalArgumentException("unknown " + field + " '" + v
                    + "' — expected one of " + String.join(", ", allowed.stream().sorted().toList()));
        }
        return v;
    }

    /**
     * In-range proportion, or the default when {@code v} is null.
     *
     * @throws IllegalArgumentException if present and outside the humanoid bound
     */
    public static double proportion(Double v, String key) {
        Bound b = BY_KEY.get(key);
        if (b == null) throw new IllegalArgumentException("unknown proportion '" + key + "'");
        if (v == null) return b.dflt();
        if (!Double.isFinite(v) || !b.contains(v)) {
            // Reported under the WIRE name (key + "Scale") rather than the bare
            // key: that is what a caller actually sent, and it preserves the
            // pre-009 "heightScale must be between …" error contract clients
            // and tests already depend on.
            throw new IllegalArgumentException(wireName(key) + " must be between " + b.min()
                    + " and " + b.max() + " (got " + v + ")");
        }
        return v;
    }

    /** The REST/entity field name for a proportion key — always {@code <key>Scale}. */
    public static String wireName(String key) {
        return key + "Scale";
    }

    /**
     * A {@code #rrggbb} colour, or the default when {@code v} is null/blank.
     *
     * @throws IllegalArgumentException if present and not six hex digits
     */
    public static String hexColor(String v, String dflt, String field) {
        if (v == null || v.isBlank()) return dflt;
        if (!HEX_COLOR.matcher(v).matches()) {
            throw new IllegalArgumentException(field + " must be a #rrggbb hex colour");
        }
        return v;
    }

    /** Builder — every setter accepts null, meaning "leave at the default". */
    public static final class Builder {
        private String renderStyle = DEFAULT_RENDER_STYLE;
        private String bodyShape = DEFAULT_BODY_SHAPE;
        private String headShape = DEFAULT_HEAD_SHAPE;
        private String hairStyle = DEFAULT_HAIR_STYLE;
        private String outfitType = DEFAULT_OUTFIT_TYPE;
        private String hatType = DEFAULT_ITEM;
        private String heldMain = DEFAULT_ITEM;
        private String heldOff = DEFAULT_ITEM;
        private String backItem = DEFAULT_ITEM;
        private String faceItem = DEFAULT_ITEM;
        private String expression = DEFAULT_EXPRESSION;
        private UUID skinTextureAssetId;
        private Map<String, Double> proportions = new LinkedHashMap<>();
        private Map<String, String> colors = new LinkedHashMap<>();

        public Builder renderStyle(String v) {
            renderStyle = token(v, RENDER_STYLES, DEFAULT_RENDER_STYLE, "renderStyle");
            return this;
        }

        public Builder bodyShape(String v) {
            bodyShape = token(v, BODY_SHAPES, DEFAULT_BODY_SHAPE, "bodyShape");
            return this;
        }

        public Builder headShape(String v) {
            headShape = token(v, HEAD_SHAPES, DEFAULT_HEAD_SHAPE, "headShape");
            return this;
        }

        public Builder hairStyle(String v) {
            hairStyle = token(v, HAIR_STYLES, DEFAULT_HAIR_STYLE, "hairStyle");
            return this;
        }

        public Builder outfitType(String v) {
            outfitType = token(v, OUTFIT_TYPES, DEFAULT_OUTFIT_TYPE, "outfitType");
            return this;
        }

        public Builder hatType(String v) {
            hatType = token(v, HAT_TYPES, DEFAULT_ITEM, "hatType");
            return this;
        }

        public Builder heldMain(String v) {
            heldMain = token(v, HELD_MAIN, DEFAULT_ITEM, "heldMain");
            return this;
        }

        public Builder heldOff(String v) {
            heldOff = token(v, HELD_OFF, DEFAULT_ITEM, "heldOff");
            return this;
        }

        public Builder backItem(String v) {
            backItem = token(v, BACK_ITEMS, DEFAULT_ITEM, "backItem");
            return this;
        }

        public Builder faceItem(String v) {
            faceItem = token(v, FACE_ITEMS, DEFAULT_ITEM, "faceItem");
            return this;
        }

        public Builder expression(String v) {
            expression = token(v, EXPRESSIONS, DEFAULT_EXPRESSION, "expression");
            return this;
        }

        public Builder skinTextureAssetId(UUID v) {
            skinTextureAssetId = v;
            return this;
        }

        /** Validated against the humanoid bound for {@code key}. */
        public Builder proportion(String key, Double v) {
            proportions.put(key, CharacterSpec.proportion(v, key));
            return this;
        }

        public Builder color(String key, String v) {
            String dflt = null;
            for (ColorSlot s : COLORS) if (s.key().equals(key)) dflt = s.dflt();
            if (dflt == null) throw new IllegalArgumentException("unknown colour slot '" + key + "'");
            colors.put(key, hexColor(v, dflt, key + "Color"));
            return this;
        }

        /** Fills every unset proportion/colour with its default. */
        public CharacterSpec build() {
            for (Bound b : PROPORTIONS) proportions.putIfAbsent(b.key(), b.dflt());
            for (ColorSlot s : COLORS) colors.putIfAbsent(s.key(), s.dflt());
            return new CharacterSpec(this);
        }
    }
}
