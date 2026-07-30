package games.hugmesh.rpgbuilder.service;

import games.hugmesh.character.CharacterSpec;
import games.hugmesh.rpgbuilder.entity.RpgAvatarConfig;
import games.hugmesh.rpgbuilder.repository.RpgAvatarConfigRepository;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Per-project hero avatar config: GET (persisted row or a synthesised
 * non-persisted default) + PUT (atomic upsert — one row per project).
 *
 * <p><b>One whitelist.</b> Since decision 028 every token set, humanoid bound
 * and colour rule lives in {@link CharacterSpec}; this service maps
 * entity ⇄ spec and lets the spec validate. Previously the same rules were
 * restated in the DB CHECKs, this service, the renderer and the Angular
 * component, and could drift.
 *
 * <p>Validation still throws {@link IllegalArgumentException} SYNCHRONOUSLY at
 * method entry — before the reactive chain — because the controller
 * {@code @ExceptionHandler} only sees exceptions raised during handler
 * invocation, not ones signalled inside the returned Mono (which would surface
 * as a 500, not the promised 400).
 *
 * <p>The PUT is a single atomic Postgres
 * {@code INSERT … ON CONFLICT (project_id) DO UPDATE} via
 * {@link DatabaseClient}: R2DBC has no save()-based upsert, and a
 * find-then-insert lets two concurrent PUTs both observe no row and both
 * INSERT — the second violating uq_rpg_avatar_config_project and 500ing.
 * ON CONFLICT on the unique key is idempotent and race-free in one round-trip.
 */
@Service
public class AvatarConfigService {

    // ── Back-compat aliases ─────────────────────────────────────────────────
    // RpgGodotProjectRenderer and existing tests reference these. They now
    // delegate to CharacterSpec rather than restating it; note HEAD_SHAPES and
    // HAT_TYPES have MORE members since 009.

    /** @deprecated use {@link CharacterSpec#BODY_SHAPES}. */
    public static final Set<String> BODY_SHAPES = CharacterSpec.BODY_SHAPES;
    /** @deprecated use {@link CharacterSpec#HEAD_SHAPES}. */
    public static final Set<String> HEAD_SHAPES = CharacterSpec.HEAD_SHAPES;
    /** @deprecated use {@link CharacterSpec#HAT_TYPES}. */
    public static final Set<String> HAT_TYPES = CharacterSpec.HAT_TYPES;

    public static final String DEFAULT_BODY_SHAPE = CharacterSpec.DEFAULT_BODY_SHAPE;
    public static final String DEFAULT_HEAD_SHAPE = CharacterSpec.DEFAULT_HEAD_SHAPE;
    public static final String DEFAULT_HAT_TYPE = CharacterSpec.DEFAULT_ITEM;
    public static final double DEFAULT_HEIGHT_SCALE = 1.0;
    public static final String DEFAULT_BODY_COLOR = "#3f59b3";
    public static final String DEFAULT_HEAD_COLOR = "#e6c8a0";
    public static final String DEFAULT_HAT_COLOR = "#202020";

    public static final double MIN_HEIGHT_SCALE = CharacterSpec.bound("height").min();
    public static final double MAX_HEIGHT_SCALE = CharacterSpec.bound("height").max();

    // ── Column ⇄ spec mapping ───────────────────────────────────────────────
    // Built once so the SQL, the binder and the row mapper can never disagree
    // about which columns exist — the failure mode when 30+ columns are listed
    // by hand in three places.

    /** Proportion key → column. */
    private static final Map<String, String> PROPORTION_COLUMNS = Map.ofEntries(
            Map.entry("height", "height_scale"),
            Map.entry("head", "head_scale"),
            Map.entry("neck", "neck_scale"),
            Map.entry("chest", "chest_scale"),
            Map.entry("waist", "waist_scale"),
            Map.entry("shoulder", "shoulder_scale"),
            Map.entry("armLength", "arm_length_scale"),
            Map.entry("armThickness", "arm_thickness_scale"),
            Map.entry("legLength", "leg_length_scale"),
            Map.entry("legThickness", "leg_thickness_scale"),
            Map.entry("hand", "hand_scale"),
            Map.entry("foot", "foot_scale"));

    /**
     * Colour key → column. Note {@code skin} maps to the pre-existing
     * {@code head_color}: that column has always held the skin tone, and
     * renaming it would break every existing row and the Six Realms seed for
     * no benefit.
     */
    private static final Map<String, String> COLOR_COLUMNS = Map.of(
            "body", "body_color",
            "skin", "head_color",
            "hat", "hat_color",
            "hair", "hair_color",
            "accent", "accent_color",
            "item", "item_color",
            "eye", "eye_color");

    /** Every writable column, in a stable order, with its value extractor. */
    private static final Map<String, Function<CharacterSpec, Object>> COLUMNS = buildColumns();

    private static Map<String, Function<CharacterSpec, Object>> buildColumns() {
        // Insertion-ordered and wrapped unmodifiable rather than Map.copyOf,
        // because COLUMN_ORDER is derived from this key set — Map.copyOf would
        // drop the order and let the SQL column list drift from the binder.
        Map<String, Function<CharacterSpec, Object>> m = new LinkedHashMap<>();
        m.put("render_style", CharacterSpec::renderStyle);
        m.put("body_shape", CharacterSpec::bodyShape);
        m.put("head_shape", CharacterSpec::headShape);
        m.put("hair_style", CharacterSpec::hairStyle);
        m.put("outfit_type", CharacterSpec::outfitType);
        m.put("hat_type", CharacterSpec::hatType);
        m.put("held_main", CharacterSpec::heldMain);
        m.put("held_off", CharacterSpec::heldOff);
        m.put("back_item", CharacterSpec::backItem);
        m.put("face_item", CharacterSpec::faceItem);
        for (CharacterSpec.Bound b : CharacterSpec.PROPORTIONS) {
            String key = b.key();
            m.put(PROPORTION_COLUMNS.get(key), s -> BigDecimal.valueOf(s.p(key)));
        }
        for (CharacterSpec.ColorSlot c : CharacterSpec.COLORS) {
            String key = c.key();
            m.put(COLOR_COLUMNS.get(key), s -> s.color(key));
        }
        return java.util.Collections.unmodifiableMap(m);
    }

    /**
     * Every column the upsert writes, in one deterministic order derived from
     * {@link #COLUMNS} — so the INSERT list, the VALUES binds, the DO UPDATE
     * assignments and the RETURNING projection are all generated from a single
     * source and cannot disagree. The texture column is appended here because
     * it is bound specially (nullable UUID).
     */
    private static final List<String> COLUMN_ORDER = buildOrder();

    private static List<String> buildOrder() {
        java.util.ArrayList<String> out = new java.util.ArrayList<>(COLUMNS.keySet());
        out.add("skin_texture_asset_id");
        return List.copyOf(out);
    }

    private static final String UPSERT_SQL = buildUpsert();

    private static String buildUpsert() {
        String cols = String.join(", ", COLUMN_ORDER);
        String binds = COLUMN_ORDER.stream().map(c -> ":" + c).reduce((a, b) -> a + ", " + b).orElseThrow();
        String updates = COLUMN_ORDER.stream().map(c -> c + " = EXCLUDED." + c)
                .reduce((a, b) -> a + ",\n                " + b).orElseThrow();
        return """
                INSERT INTO rpg_avatar_config (project_id, %s)
                VALUES (:projectId, %s)
                ON CONFLICT (project_id) DO UPDATE SET
                %s
                RETURNING id, project_id, %s
                """.formatted(cols, binds, updates, cols);
    }

    private static final String TEXTURE_OWNED_SQL =
            "SELECT count(*) FROM rpg_assets WHERE id = :assetId AND project_id = :projectId";

    private final RpgProjectService projects;
    private final RpgAvatarConfigRepository configs;
    private final DatabaseClient db;

    public AvatarConfigService(RpgProjectService projects, RpgAvatarConfigRepository configs,
                               DatabaseClient db) {
        this.projects = projects;
        this.configs = configs;
        this.db = db;
    }

    /**
     * The persisted row, or a synthesised default ({@code id == null}, not
     * persisted) when the project has none yet — so the maker always has
     * something to render. 404/403 via the ownership guard.
     */
    public Mono<RpgAvatarConfig> get(UUID projectId, String ownerCasdoorId) {
        return projects.get(projectId, ownerCasdoorId)
                .flatMap(p -> configs.findByProjectId(projectId)
                        .defaultIfEmpty(defaultConfig(projectId)));
    }

    /** The same thing as a validated {@link CharacterSpec}, for renderers. */
    public Mono<CharacterSpec> spec(UUID projectId, String ownerCasdoorId) {
        return get(projectId, ownerCasdoorId).map(AvatarConfigService::toSpec);
    }

    /**
     * Idempotent one-row-per-project upsert. Server stamps id/projectId — any
     * values in the body are ignored (no mass-assignment / cross-project
     * write). A {@code null} field is stamped to its column default; a PRESENT
     * field is validated (unknown token / out-of-range proportion / bad hex →
     * sync IllegalArgumentException → the controller's own handler → 400).
     */
    public Mono<RpgAvatarConfig> put(UUID projectId, RpgAvatarConfig in, String ownerCasdoorId) {
        return putSpec(projectId, toSpec(in), ownerCasdoorId);
    }

    /**
     * Upsert straight from a spec — the import path uses this.
     *
     * <p>The texture check is nested INSIDE the ownership flatMap on purpose.
     * Chaining it as {@code .flatMap(guard).then(upsert)} would still upsert
     * when {@code projects.get} completes empty, because {@code then} ignores
     * the upstream value and subscribes regardless — turning a 404 into a
     * silent write.
     */
    public Mono<RpgAvatarConfig> putSpec(UUID projectId, CharacterSpec spec, String ownerCasdoorId) {
        return projects.get(projectId, ownerCasdoorId)
                .flatMap(p -> requireOwnedTexture(projectId, spec.skinTextureAssetId())
                        .then(upsert(projectId, spec)));
    }

    /**
     * A texture asset must belong to THIS project. Same cross-project IDOR
     * guard {@code AssetStorageService.read} and {@code createNpc} already
     * apply — without it, a publisher could point their avatar at another
     * project's asset id and have the renderer stage those bytes into their
     * own bundle.
     */
    private Mono<Long> requireOwnedTexture(UUID projectId, UUID assetId) {
        if (assetId == null) return Mono.just(0L);
        return db.sql(TEXTURE_OWNED_SQL)
                .bind("assetId", assetId)
                .bind("projectId", projectId)
                .map((row, meta) -> row.get(0, Long.class))
                .one()
                .defaultIfEmpty(0L)
                .handle((count, sink) -> {
                    if (count == null || count == 0) {
                        sink.error(new IllegalArgumentException(
                                "skinTextureAssetId does not belong to this project"));
                    } else {
                        sink.next(count);
                    }
                });
    }

    /** Synthesised column-default config for a project with no row yet. */
    public static RpgAvatarConfig defaultConfig(UUID projectId) {
        RpgAvatarConfig c = fromSpec(CharacterSpec.defaults(), projectId);
        c.setProjectId(projectId);
        return c;
    }

    // ── entity ⇄ spec ───────────────────────────────────────────────────────

    /**
     * Entity → validated spec. Null fields take their defaults; present ones
     * are checked. Throws synchronously on anything invalid.
     */
    public static CharacterSpec toSpec(RpgAvatarConfig in) {
        CharacterSpec.Builder b = CharacterSpec.builder();
        if (in == null) return b.build();

        b.renderStyle(in.getRenderStyle())
                .bodyShape(in.getBodyShape())
                .headShape(in.getHeadShape())
                .hairStyle(in.getHairStyle())
                .outfitType(in.getOutfitType())
                .hatType(in.getHatType())
                .heldMain(in.getHeldMain())
                .heldOff(in.getHeldOff())
                .backItem(in.getBackItem())
                .faceItem(in.getFaceItem())
                .skinTextureAssetId(in.getSkinTextureAssetId());

        PROPORTION_READERS.forEach((key, read) -> b.proportion(key, read.apply(in)));
        COLOR_READERS.forEach((key, read) -> b.color(key, read.apply(in)));
        return b.build();
    }

    /**
     * Entity → spec for the CODE GENERATOR, degrading per field instead of
     * rejecting.
     *
     * <p>{@link #toSpec} is the request path and must reject loudly. The
     * renderer is different: it runs long after validation, on a row the DB
     * CHECKs already guarantee, and its job is to emit GDScript. If something
     * impossible did reach the row, baking one field's default is right;
     * throwing away the author's ENTIRE character because a single colour is
     * malformed is not, and neither is emitting arbitrary text into generated
     * code. Off-whitelist tokens fall back individually and out-of-range
     * proportions are clamped — the defence-in-depth contract that predates
     * decision 028.
     */
    public static CharacterSpec toSpecLenient(RpgAvatarConfig in) {
        CharacterSpec.Builder b = CharacterSpec.builder();
        if (in == null) return b.build();

        lenient(() -> b.renderStyle(in.getRenderStyle()));
        lenient(() -> b.bodyShape(in.getBodyShape()));
        lenient(() -> b.headShape(in.getHeadShape()));
        lenient(() -> b.hairStyle(in.getHairStyle()));
        lenient(() -> b.outfitType(in.getOutfitType()));
        lenient(() -> b.hatType(in.getHatType()));
        lenient(() -> b.heldMain(in.getHeldMain()));
        lenient(() -> b.heldOff(in.getHeldOff()));
        lenient(() -> b.backItem(in.getBackItem()));
        lenient(() -> b.faceItem(in.getFaceItem()));
        lenient(() -> b.skinTextureAssetId(in.getSkinTextureAssetId()));

        PROPORTION_READERS.forEach((key, read) -> {
            Double v = read.apply(in);
            CharacterSpec.Bound bound = CharacterSpec.bound(key);
            Double clamped = (v == null || !Double.isFinite(v))
                    ? null
                    : Math.clamp(v, bound.min(), bound.max());
            lenient(() -> b.proportion(key, clamped));
        });
        COLOR_READERS.forEach((key, read) -> lenient(() -> b.color(key, read.apply(in))));
        return b.build();
    }

    /** Runs one builder step, swallowing a reject so the field keeps its default. */
    private static void lenient(Runnable step) {
        try {
            step.run();
        } catch (IllegalArgumentException ignored) {
            // Deliberate: this field stays at its default.
        }
    }

    /** Validated spec → entity, for responses and defaults. */
    public static RpgAvatarConfig fromSpec(CharacterSpec spec, UUID projectId) {
        RpgAvatarConfig c = new RpgAvatarConfig();
        c.setProjectId(projectId);
        c.setRenderStyle(spec.renderStyle());
        c.setBodyShape(spec.bodyShape());
        c.setHeadShape(spec.headShape());
        c.setHairStyle(spec.hairStyle());
        c.setOutfitType(spec.outfitType());
        c.setHatType(spec.hatType());
        c.setHeldMain(spec.heldMain());
        c.setHeldOff(spec.heldOff());
        c.setBackItem(spec.backItem());
        c.setFaceItem(spec.faceItem());
        c.setSkinTextureAssetId(spec.skinTextureAssetId());
        PROPORTION_WRITERS.forEach((key, write) -> write.accept(c, spec.p(key)));
        COLOR_WRITERS.forEach((key, write) -> write.accept(c, spec.color(key)));
        return c;
    }

    private static final Map<String, Function<RpgAvatarConfig, Double>> PROPORTION_READERS = Map.ofEntries(
            Map.entry("height", RpgAvatarConfig::getHeightScale),
            Map.entry("head", RpgAvatarConfig::getHeadScale),
            Map.entry("neck", RpgAvatarConfig::getNeckScale),
            Map.entry("chest", RpgAvatarConfig::getChestScale),
            Map.entry("waist", RpgAvatarConfig::getWaistScale),
            Map.entry("shoulder", RpgAvatarConfig::getShoulderScale),
            Map.entry("armLength", RpgAvatarConfig::getArmLengthScale),
            Map.entry("armThickness", RpgAvatarConfig::getArmThicknessScale),
            Map.entry("legLength", RpgAvatarConfig::getLegLengthScale),
            Map.entry("legThickness", RpgAvatarConfig::getLegThicknessScale),
            Map.entry("hand", RpgAvatarConfig::getHandScale),
            Map.entry("foot", RpgAvatarConfig::getFootScale));

    private static final Map<String, BiConsumer<RpgAvatarConfig, Double>> PROPORTION_WRITERS = Map.ofEntries(
            Map.entry("height", RpgAvatarConfig::setHeightScale),
            Map.entry("head", RpgAvatarConfig::setHeadScale),
            Map.entry("neck", RpgAvatarConfig::setNeckScale),
            Map.entry("chest", RpgAvatarConfig::setChestScale),
            Map.entry("waist", RpgAvatarConfig::setWaistScale),
            Map.entry("shoulder", RpgAvatarConfig::setShoulderScale),
            Map.entry("armLength", RpgAvatarConfig::setArmLengthScale),
            Map.entry("armThickness", RpgAvatarConfig::setArmThicknessScale),
            Map.entry("legLength", RpgAvatarConfig::setLegLengthScale),
            Map.entry("legThickness", RpgAvatarConfig::setLegThicknessScale),
            Map.entry("hand", RpgAvatarConfig::setHandScale),
            Map.entry("foot", RpgAvatarConfig::setFootScale));

    private static final Map<String, Function<RpgAvatarConfig, String>> COLOR_READERS = Map.of(
            "body", RpgAvatarConfig::getBodyColor,
            "skin", RpgAvatarConfig::getHeadColor,
            "hat", RpgAvatarConfig::getHatColor,
            "hair", RpgAvatarConfig::getHairColor,
            "accent", RpgAvatarConfig::getAccentColor,
            "item", RpgAvatarConfig::getItemColor,
            "eye", RpgAvatarConfig::getEyeColor);

    private static final Map<String, BiConsumer<RpgAvatarConfig, String>> COLOR_WRITERS = Map.of(
            "body", RpgAvatarConfig::setBodyColor,
            "skin", RpgAvatarConfig::setHeadColor,
            "hat", RpgAvatarConfig::setHatColor,
            "hair", RpgAvatarConfig::setHairColor,
            "accent", RpgAvatarConfig::setAccentColor,
            "item", RpgAvatarConfig::setItemColor,
            "eye", RpgAvatarConfig::setEyeColor);

    // ── Persistence ─────────────────────────────────────────────────────────

    private Mono<RpgAvatarConfig> upsert(UUID projectId, CharacterSpec spec) {
        DatabaseClient.GenericExecuteSpec sql = db.sql(UPSERT_SQL).bind("projectId", projectId);
        for (String column : COLUMN_ORDER) {
            if ("skin_texture_asset_id".equals(column)) {
                sql = spec.skinTextureAssetId() == null
                        ? sql.bindNull(column, UUID.class)
                        : sql.bind(column, spec.skinTextureAssetId());
            } else {
                sql = sql.bind(column, COLUMNS.get(column).apply(spec));
            }
        }
        return sql.map(AvatarConfigService::fromRow).one();
    }

    static RpgAvatarConfig fromRow(Row row, RowMetadata meta) {
        RpgAvatarConfig c = new RpgAvatarConfig();
        c.setId(row.get("id", UUID.class));
        c.setProjectId(row.get("project_id", UUID.class));
        c.setRenderStyle(row.get("render_style", String.class));
        c.setBodyShape(row.get("body_shape", String.class));
        c.setHeadShape(row.get("head_shape", String.class));
        c.setHairStyle(row.get("hair_style", String.class));
        c.setOutfitType(row.get("outfit_type", String.class));
        c.setHatType(row.get("hat_type", String.class));
        c.setHeldMain(row.get("held_main", String.class));
        c.setHeldOff(row.get("held_off", String.class));
        c.setBackItem(row.get("back_item", String.class));
        c.setFaceItem(row.get("face_item", String.class));
        c.setSkinTextureAssetId(row.get("skin_texture_asset_id", UUID.class));

        PROPORTION_WRITERS.forEach((key, write) -> {
            BigDecimal v = row.get(PROPORTION_COLUMNS.get(key), BigDecimal.class);
            write.accept(c, v == null ? null : v.doubleValue());
        });
        COLOR_WRITERS.forEach((key, write) ->
                write.accept(c, row.get(COLOR_COLUMNS.get(key), String.class)));
        return c;
    }
}
