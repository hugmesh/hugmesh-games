package games.hugmesh.bff.migration;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Offline pins over changeset {@code 063-input-gate-feature-flags.xml}
 * (hugmesh-games#13, decision 103b = A) — no database, so they run on a host
 * without Docker. {@link InputGateFeatureFlagsMigrationTest} proves the same
 * SQL against a real Postgres; this proves the file says what it must and is
 * wired into the master changelog.
 */
class InputGateFeatureFlagsChangesetXmlTest {

    static final String CHANGESET = "db/changelog/063-input-gate-feature-flags.xml";
    static final String MASTER = "db/changelog/db.changelog-master.xml";

    /** changeSet id -> the body of its single {@code <sql>} element. */
    static Map<String, String> sqlByChangeset() throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        try (InputStream in = InputGateFeatureFlagsChangesetXmlTest.class.getClassLoader()
                .getResourceAsStream(CHANGESET)) {
            assertThat(in).as(CHANGESET + " on the classpath").isNotNull();
            var f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            NodeList sets = f.newDocumentBuilder().parse(in).getElementsByTagNameNS("*", "changeSet");
            for (int i = 0; i < sets.getLength(); i++) {
                Element cs = (Element) sets.item(i);
                NodeList sql = cs.getElementsByTagNameNS("*", "sql");
                assertThat(sql.getLength()).as(cs.getAttribute("id") + " has one <sql>").isEqualTo(1);
                out.put(cs.getAttribute("id"), sql.item(0).getTextContent().trim());
            }
        }
        return out;
    }

    /** The row-data statements (063-2, 063-3), split on ';', for re-running. */
    static List<String> dataStatements() throws Exception {
        List<String> out = new ArrayList<>();
        for (var e : sqlByChangeset().entrySet()) {
            if (e.getKey().startsWith("063-1")) continue; // the DDL default
            for (String s : e.getValue().split(";")) {
                if (!s.isBlank()) out.add(s.trim());
            }
        }
        return out;
    }

    private static String squash(String sql) {
        return sql.replaceAll("\\s+", " ");
    }

    @Test
    void theDefaultCarriesInstantLoadoutWorldAndControl_andNotSpellLoadout() throws Exception {
        String ddl = squash(sqlByChangeset().get("063-1-games-features-default"));
        assertThat(ddl).contains("ALTER COLUMN features SET DEFAULT");
        for (String key : List.of("instant_loadout", "quests", "items", "char_progression",
                "audio", "gamepad", "mouse", "keyboard", "touch", "world", "control")) {
            assertThat(ddl).as("default carries " + key).containsPattern("\"" + key + "\": true");
        }
        assertThat(ddl).doesNotContain("spell_loadout");
    }

    @Test
    void worldAndControlAreMergedUnderTheRowSoAnExplicitValueWins() throws Exception {
        String sql = squash(sqlByChangeset().get("063-3-games-features-world-control"));
        // jsonb `a || b`: b wins on collision. The seed must be the LEFT operand.
        assertThat(sql).containsPattern(Pattern.quote("'{\"world\": true, \"control\": true}'::jsonb || features"));
        assertThat(sql).doesNotContain("features || '{");
    }

    @Test
    void theRenameNeverOverwritesAnExistingInstantLoadout() throws Exception {
        String sql = squash(sqlByChangeset().get("063-2-games-features-spell-loadout-rename"));
        assertThat(sql).contains("AND NOT jsonb_exists(features, 'instant_loadout')");
        assertThat(sql).contains("SET features = features - 'spell_loadout'");
    }

    @Test
    void noStatementUsesTheQuestionMarkOperator() throws Exception {
        // Liquibase binds `?` as a JDBC placeholder (see 028-7).
        for (String sql : sqlByChangeset().values()) {
            assertThat(sql).doesNotContain("?");
        }
    }

    @Test
    void theMasterChangelogIncludes063OnceAfter062() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(MASTER)) {
            assertThat(in).isNotNull();
            var f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            NodeList inc = f.newDocumentBuilder().parse(in).getElementsByTagNameNS("*", "include");
            List<String> files = new ArrayList<>();
            for (int i = 0; i < inc.getLength(); i++) {
                files.add(((Element) inc.item(i)).getAttribute("file"));
            }
            assertThat(files).containsOnlyOnce(CHANGESET);
            assertThat(files.indexOf(CHANGESET))
                    .as("063 rewrites rows 044-062 inserted, so it must run after them")
                    .isGreaterThan(files.indexOf("db/changelog/062-character-forge-federation.xml"));
        }
    }
}
