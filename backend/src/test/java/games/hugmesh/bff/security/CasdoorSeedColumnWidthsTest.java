package games.hugmesh.bff.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Guards {@code casdoor/init_data.json.tpl}, the games realm's first-run seed.
 *
 * <p>Casdoor stores every string of a seeded object in a {@code varchar}
 * column and its init-data loader does not truncate: a value longer than the
 * column makes the insert fail, the loader panics and the process exits, so
 * the games Casdoor never comes up. That is not caught by anything else in this
 * repo (no CI boots a Casdoor), and it happened once: a role description of
 * 247 characters stopped {@code casbin/casdoor} 3.80.0 and v1.811.0 at boot.
 *
 * <p>The widths below were read from {@code information_schema.columns} of a
 * {@code casbin/casdoor:3.80.0} database (Postgres). Every column is 100
 * characters unless listed otherwise. Lengths are counted in code points, which
 * is what Postgres counts for {@code varchar(n)}.
 *
 * <p>The template is valid JSON as committed: the entrypoint's {@code sed} only
 * swaps {@code *_VALUE} placeholders for generated secrets of at most 32
 * characters (password is varchar(150)), so parsing the template itself checks
 * what Casdoor will load.
 */
class CasdoorSeedColumnWidthsTest {

    private static final Path RELATIVE = Path.of("casdoor", "init_data.json.tpl");

    private static final int DEFAULT_WIDTH = 100;

    /** JSON field -> column width, per init-data section, where it is not 100. */
    private static final Map<String, Map<String, Integer>> WIDER = Map.of(
            "organizations", Map.of("logo", 200, "favicon", 200),
            "applications", Map.of("logo", 200, "favicon", 200),
            "users", Map.of("name", 255, "password", 150));

    /** JSON fields per section that land in a varchar column (not text/json). */
    private static final Map<String, Set<String>> CHECKED = Map.of(
            "organizations", Set.of("owner", "name", "displayName", "websiteUrl", "logo", "favicon"),
            "applications", Set.of("owner", "name", "displayName", "description", "organization",
                    "clientId", "clientSecret", "logo", "favicon"),
            "users", Set.of("owner", "name", "displayName", "email", "phone", "affiliation", "bio",
                    "tag", "title"),
            "roles", Set.of("owner", "name", "displayName", "description"),
            "permissions", Set.of("owner", "name", "displayName", "description"));

    /**
     * Games-realm roles the seed may define. Anything else is refused here on
     * purpose: in particular no role that a sync writes into this realm from the
     * mesh ({@code mesh-games-admin}), because {@link GamesAdminPolicy} does not
     * honour one and seeding it would invite something to. Games-realm admin is
     * the realm's own {@code admin} only.
     */
    private static final Set<String> ALLOWED_ROLES = Set.of(
            "admin", "mod", "publisher", "player", "uploader", "play-three-wizards", "play-dodecahedron");

    @Test
    void everySeededStringFitsItsCasdoorColumn() throws IOException {
        JsonNode root = seed();
        List<String> tooLong = new ArrayList<>();
        for (Map.Entry<String, Set<String>> section : CHECKED.entrySet()) {
            JsonNode items = root.path(section.getKey());
            assertThat(items.isArray()).as("section %s", section.getKey()).isTrue();
            for (JsonNode item : items) {
                for (String field : section.getValue()) {
                    JsonNode value = item.path(field);
                    if (!value.isTextual()) continue;
                    int width = WIDER.getOrDefault(section.getKey(), Map.of()).getOrDefault(field, DEFAULT_WIDTH);
                    String text = value.asText();
                    int length = text.codePointCount(0, text.length());
                    if (length > width) {
                        tooLong.add(section.getKey() + "/" + item.path("name").asText("?") + "." + field
                                + " is " + length + " chars, column is varchar(" + width + ")");
                    }
                }
            }
        }
        assertThat(tooLong)
                .as("Casdoor refuses to boot on a seed value longer than its column; shorten these")
                .isEmpty();
    }

    @Test
    void everySectionHasAtLeastOneItem() throws IOException {
        JsonNode root = seed();
        for (String section : CHECKED.keySet()) {
            assertThat(root.path(section).size()).as(section).isGreaterThan(0);
        }
    }

    @Test
    void onlyTheRealmsOwnRolesAreSeeded_noSyncedAdminRole() throws IOException {
        List<String> names = new ArrayList<>();
        for (JsonNode role : seed().path("roles")) {
            assertThat(role.path("owner").asText()).isEqualTo(GamesAdminPolicy.GAMES_REALM_ORGANIZATION);
            names.add(role.path("name").asText());
        }
        assertThat(names).doesNotContain("mesh-games-admin");
        assertThat(names).isSubsetOf(ALLOWED_ROLES);
        assertThat(names).contains(GamesAdminPolicy.REALM_ADMIN_ROLE);
    }

    private static JsonNode seed() throws IOException {
        return new ObjectMapper().readTree(Files.readString(template(), StandardCharsets.UTF_8));
    }

    static Path template() {
        Path start = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (Path dir = start; dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve(RELATIVE);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            if (Files.exists(dir.resolve(".git"))) {
                break; // the repository root: never pick up another checkout's copy
            }
        }
        return fail("Casdoor seed: %s not found from %s up to the repository root. "
                + "If the file moved, move this path with it; do not delete or skip the test.",
                RELATIVE, start);
    }
}
