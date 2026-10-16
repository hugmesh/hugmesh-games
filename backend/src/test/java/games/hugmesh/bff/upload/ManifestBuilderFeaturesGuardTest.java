package games.hugmesh.bff.upload;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Drift guard for hugmesh-games#17: the export plugin must not write a
 * {@code games.features} object that lacks {@code instant_loadout}, {@code world}
 * or {@code control}, and must not REPLACE the object.
 *
 * <p><b>What this is and is not.</b> {@code manifest_builder.gd} is GDScript and the
 * backend build does not run it, so this class reads the plugin source (and the
 * committed character-forge {@code register.sql} and {@code manifest.json}) as text. It
 * pins: {@code _DEFAULT_FEATURES} is exactly changeset 063-1's eleven keys; the emitted
 * features statement is a jsonb merge ({@code features || '...'}), not
 * {@code features = '...'}; and no {@code --} banner line the generator writes holds a
 * quote or a semicolon, which an older policy splitter would misread (see
 * {@link #generatorBannerKeepsTheSplitterInSyncSoTheFeaturesUpdateIsVettedOnItsOwn}).
 * A change that keeps the text shape but breaks behaviour (for example
 * {@code _merged_features} no longer copying the defaults) is NOT caught here. That
 * behaviour is checked by running the plugin under Godot, which this machine has:
 * preload {@code manifest_builder.gd} from a scratch project's {@code addons/hugmesh_export},
 * call {@code build()} then {@code to_register_sql()} in a {@code SceneTree} script, and
 * run {@code godot --headless --path <project> -s gen.gd}. The committed character-forge
 * {@code register.sql} is hand-maintained (richer comments, a {@code chapters} row), not
 * the generator's output; only its features object is pinned against {@code manifest.json}.
 *
 * <p>The merge itself is simulated in Java ({@code a || b}: the right operand wins
 * on a key collision) to show a fresh install ends with the three flags true. Gate 2
 * ({@code InputEventController}) reads only {@code Boolean.TRUE.equals(flags.get(k))}.
 */
class ManifestBuilderFeaturesGuardTest {

    private static final Set<String> ELEVEN = Set.of(
            "instant_loadout", "quests", "items", "char_progression", "audio",
            "gamepad", "mouse", "keyboard", "touch", "world", "control");

    private static final Path BUILDER = Path.of("extensions", "hugmesh-bridge", "addons",
            "hugmesh_export", "manifest_builder.gd");
    private static final Path REGISTER_SQL = Path.of("games", "character-forge", "register", "register.sql");
    private static final Path MANIFEST_JSON = Path.of("games", "character-forge", "register", "manifest.json");

    private static final ObjectMapper JSON = new ObjectMapper();

    private static Path fromRepoRoot(Path relative) {
        Path start = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (Path dir = start; dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            if (Files.exists(dir.resolve(".git"))) {
                break; // the repository root: never pick up another checkout's copy
            }
        }
        return fail("%s not found from %s up to the repository root. If the file moved, move this "
                + "path with it; do not delete or skip the test.", relative, start);
    }

    private static String read(Path relative) throws IOException {
        return Files.readString(fromRepoRoot(relative), StandardCharsets.UTF_8);
    }

    /** The keys and values of the {@code _DEFAULT_FEATURES} dictionary literal, in source order. */
    private static Map<String, Boolean> pluginDefaults() throws IOException {
        String gd = read(BUILDER);
        Matcher block = Pattern.compile("const\\s+_DEFAULT_FEATURES\\s*:=\\s*\\{(.*?)\\n\\}", Pattern.DOTALL)
                .matcher(gd);
        assertThat(block.find()).as("const _DEFAULT_FEATURES := { ... } in %s", BUILDER).isTrue();
        Map<String, Boolean> out = new LinkedHashMap<>();
        Matcher kv = Pattern.compile("\"([a-z_]+)\"\\s*:\\s*(true|false)").matcher(block.group(1));
        while (kv.find()) {
            out.put(kv.group(1), Boolean.valueOf(kv.group(2)));
        }
        return out;
    }

    /** The keys of the column DEFAULT changeset 063-1 sets (the server side of the contract). */
    private static Map<String, Boolean> serverDefault() throws IOException {
        String res = "db/changelog/063-input-gate-feature-flags.xml";
        String xml;
        try (InputStream in = ManifestBuilderFeaturesGuardTest.class.getClassLoader().getResourceAsStream(res)) {
            assertThat(in).as(res + " on the classpath").isNotNull();
            xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        int from = xml.indexOf("063-1-games-features-default");
        int to = xml.indexOf("063-2-", from);
        assertThat(from).as("changeset 063-1 present").isGreaterThanOrEqualTo(0);
        assertThat(to).as("changeset 063-2 follows 063-1").isGreaterThan(from);
        Map<String, Boolean> out = new LinkedHashMap<>();
        Matcher kv = Pattern.compile("\"([a-z_]+)\"\\s*:\\s*(true|false)").matcher(xml.substring(from, to));
        while (kv.find()) {
            out.put(kv.group(1), Boolean.valueOf(kv.group(2)));
        }
        return out;
    }

    /** jsonb {@code left || right}: the right operand wins on a key collision. */
    private static Map<String, Object> jsonbConcat(Map<String, ?> left, Map<String, ?> right) {
        Map<String, Object> out = new LinkedHashMap<>(left);
        out.putAll(right);
        return out;
    }

    @Test
    void pluginDefaultsAreExactlyTheElevenKeysOfChangeset063() throws IOException {
        Map<String, Boolean> plugin = pluginDefaults();
        assertThat(plugin.keySet())
                .as("_DEFAULT_FEATURES keys in manifest_builder.gd")
                .containsExactlyInAnyOrderElementsOf(ELEVEN);
        assertThat(plugin.values()).as("a vanilla upload declares every flag on").containsOnly(true);
        assertThat(plugin)
                .as("the plugin's defaults and changeset 063-1's column DEFAULT must be the same object")
                .isEqualTo(serverDefault());
    }

    @Test
    void pluginNoLongerNamesTheRetiredSpellLoadoutKey() throws IOException {
        assertThat(pluginDefaults()).doesNotContainKey("spell_loadout");
        assertThat(read(REGISTER_SQL)).doesNotContain("spell_loadout");
        assertThat(read(MANIFEST_JSON)).doesNotContain("spell_loadout");
    }

    @Test
    void pluginEmitsAJsonbMergeNotAWholeObjectReplace() throws IOException {
        String gd = read(BUILDER);
        assertThat(gd)
                .as("the features statement merges into the existing object")
                .contains("UPDATE games SET features = features || '%s'::jsonb");
        assertThat(gd)
                .as("no whole-object replace of games.features is emitted")
                .doesNotContain("SET features = '%s'");
    }

    @Test
    void committedCharacterForgeRegisterSqlMergesAndCarriesAllElevenKeys() throws IOException {
        String sql = read(REGISTER_SQL);
        Matcher m = Pattern.compile("UPDATE games SET features = features \\|\\| '(\\{[^']*\\})'::jsonb",
                Pattern.CASE_INSENSITIVE).matcher(sql);
        assertThat(m.find()).as("register.sql has a jsonb-merge features UPDATE").isTrue();
        Map<String, Object> flags = JSON.readValue(m.group(1), new TypeReference<>() { });
        assertThat(flags.keySet()).containsExactlyInAnyOrderElementsOf(ELEVEN);
        assertThat(sql).as("no replace form left in the file")
                .doesNotContainPattern("(?i)SET\\s+features\\s*=\\s*'");
        // and the manifest it was rendered from says the same thing
        Map<String, Object> manifest = JSON.readValue(read(MANIFEST_JSON), new TypeReference<>() { });
        @SuppressWarnings("unchecked")
        Map<String, Object> manifestFeatures = (Map<String, Object>) manifest.get("features");
        assertThat(manifestFeatures).as("manifest.json features equal the register.sql object").isEqualTo(flags);
    }

    @Test
    void theCommittedStatementIsAdmittedByTheRealPolicy() throws IOException {
        String sql = read(REGISTER_SQL);
        assertThat(RegisterSqlPolicy.vet(sql))
                .anyMatch(s -> s.startsWith("UPDATE games SET features = features ||"));
    }

    @Test
    void generatorBannerKeepsTheSplitterInSyncSoTheFeaturesUpdateIsVettedOnItsOwn() throws IOException {
        // RegisterSqlPolicy strips `--` comments before splitting, so a quote in a banner
        // no longer desyncs it; the generator still keeps its banners free of a quote and
        // a semicolon (an older splitter read them as a string opener / statement end).
        // Take the banner lines the generator really writes.
        Matcher banner = Pattern.compile("\"(--[^\"]*)\\\\n\"").matcher(read(BUILDER));
        List<String> lines = new java.util.ArrayList<>();
        while (banner.find()) {
            lines.add(banner.group(1));
        }
        assertThat(lines).as("`--` banner literals found in %s", BUILDER).hasSizeGreaterThanOrEqualTo(3);
        for (String line : lines) {
            assertThat(line).as("generator banner line").doesNotContain("'").doesNotContain(";");
        }
        String blob = String.join("\n", lines) + "\n\n"
                + "INSERT INTO games (name, description, slug, sort_order, created_at)\n"
                + "VALUES ('n', 'd', 'demo-game', 0, NOW())\n"
                + "ON CONFLICT (slug) DO UPDATE SET name = EXCLUDED.name;\n\n"
                + "UPDATE games SET features = features || '{\"world\":true}'::jsonb WHERE slug = 'demo-game';\n\n";
        List<String> vetted = RegisterSqlPolicy.vet(blob);
        assertThat(vetted).as("the banner is dropped and the INSERT and the features UPDATE are separate statements").hasSize(2);
        assertThat(vetted.get(1)).startsWith("UPDATE games SET features = features ||");
    }

    @Test
    void aFreshInstallLeavesWorldControlAndInstantLoadoutOn() throws IOException {
        // Row as the column DEFAULT creates it, then the plugin's merge on top.
        Map<String, Object> afterInsert = jsonbConcat(serverDefault(), Map.of());
        Map<String, Object> afterUpload = jsonbConcat(afterInsert, pluginDefaults());
        for (String key : List.of("instant_loadout", "world", "control")) {
            assertThat(afterUpload.get(key)).as("gate 2 reads %s as JSON true", key).isEqualTo(true);
        }
        // A key the server added after the plugin was written survives the merge,
        // which a whole-object replace would have dropped.
        Map<String, Object> serverRow = jsonbConcat(serverDefault(), Map.of("future_flag", true));
        assertThat(jsonbConcat(serverRow, pluginDefaults())).containsEntry("future_flag", true);
    }
}
