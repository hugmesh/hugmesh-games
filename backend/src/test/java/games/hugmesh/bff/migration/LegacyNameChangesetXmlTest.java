package games.hugmesh.bff.migration;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Offline pins for the 2026-10 removal of the pre-rebrand name from the
 * changelog (no database needed).
 *
 * <ul>
 *   <li>No changelog file NAME or CONTENT holds a word whose md5 is the md5
 *       of the old brand name. The name itself is never spelled here.</li>
 *   <li>017 was renamed. Liquibase keys a changeset on (id, author, file), so
 *       every 017 changeset must carry a MARK_RAN precondition that finds its
 *       own row under the old file name. Without it, a database that already
 *       ran 017 runs it again and fails (028 renamed {@code levels}).</li>
 *   <li>No changelog file holds the word that the 054/060 changesets used
 *       to carry as their author (the name of a code assistant tool). That
 *       word is never spelled here either; it is matched by md5.</li>
 *   <li>054 and 060 were re-keyed to author {@code hugmesh}. Like 017, each
 *       changeset must carry a MARK_RAN precondition that finds its own row
 *       under the OLD author in the same file. Without it, a database that
 *       already ran 054 re-runs it: 054-2 then grants on a role that may not
 *       exist yet under its new name, and 054-3 re-enrols the login role.</li>
 *   <li>060 finds the old role by the md5 of its name.</li>
 * </ul>
 *
 * The upgrade itself (main's changelog first, then this one) was run by hand
 * against Postgres 16 with Liquibase 5.0.3; see the pull request.
 */
class LegacyNameChangesetXmlTest {

    static final String DIR = "db/changelog";
    static final String MASTER = DIR + "/db.changelog-master.xml";
    static final String CATALOG_017 = DIR + "/017-dodecahedron-catalog.xml";

    /** md5 of the old brand word, lower and capitalised spellings. */
    static final List<String> LEGACY_WORD_MD5 = List.of(
            "2c3c0f34b3079f2cab54238f4541d9b1",
            "f96173f6b2721b2ad3e6b789508896cc");
    /** md5 of the old last path segment of the 017 changelog file. */
    static final String LEGACY_017_FILE_MD5 = "b8c9d8879b26009690930e84bc1f44df";
    /** md5 of the old register.sql role name. */
    static final String LEGACY_ROLE_MD5 = "020b32edaf685d43f599083e4e1ac5aa";

    /** md5 of the old author of 054-1..3 and 060-1 (lower case). */
    static final String LEGACY_AUTHOR_MD5 = "8bb75b3015682d910daf88b6d728be2c";
    /** The author 054/060 carry now. */
    static final String AUTHOR = "hugmesh";
    /** Re-keyed changesets, by file name, in file order. */
    static final Map<String, List<String>> REKEYED = Map.of(
            "054-register-sql-least-priv-role.xml", List.of(
                    "054-1-create-register-sql-role",
                    "054-2-grant-catalog-tables",
                    "054-3-enroll-app-login-role"),
            "060-rebrand-register-sql-role.xml", List.of(
                    "060-1-rename-register-sql-role"));

    static String md5(String s) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8)));
    }

    static Path changelogDir() throws Exception {
        URL u = LegacyNameChangesetXmlTest.class.getClassLoader().getResource(DIR);
        assertThat(u).as(DIR + " on the classpath").isNotNull();
        return Paths.get(u.toURI());
    }

    static List<Element> changeSets(String resource) throws Exception {
        try (InputStream in = LegacyNameChangesetXmlTest.class.getClassLoader()
                .getResourceAsStream(resource)) {
            assertThat(in).as(resource + " on the classpath").isNotNull();
            var f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            NodeList sets = f.newDocumentBuilder().parse(in).getElementsByTagNameNS("*", "changeSet");
            List<Element> out = new ArrayList<>();
            for (int i = 0; i < sets.getLength(); i++) out.add((Element) sets.item(i));
            return out;
        }
    }

    static List<String> validCheckSums(Element cs) {
        NodeList n = cs.getElementsByTagNameNS("*", "validCheckSum");
        List<String> out = new ArrayList<>();
        for (int i = 0; i < n.getLength(); i++) out.add(n.item(i).getTextContent().trim());
        return out;
    }

    /** file name -> count of words whose md5 (as is, or lower-cased) is in {@code md5s}. */
    static Map<String, Integer> wordHits(List<String> md5s) throws Exception {
        Pattern word = Pattern.compile("[A-Za-z]+");
        Map<String, Integer> hits = new LinkedHashMap<>();
        try (Stream<Path> files = Files.list(changelogDir())) {
            for (Path p : files.sorted().toList()) {
                String name = p.getFileName().toString();
                String text = name + "\n" + Files.readString(p, StandardCharsets.UTF_8);
                Matcher m = word.matcher(text);
                while (m.find()) {
                    String w = m.group();
                    if (md5s.contains(md5(w)) || md5s.contains(md5(w.toLowerCase(Locale.ROOT)))) {
                        hits.merge(name, 1, Integer::sum);
                    }
                }
            }
        }
        return hits;
    }

    @Test
    void noChangelogFileNameOrContentSpellsTheOldBrandName() throws Exception {
        assertThat(wordHits(LEGACY_WORD_MD5))
                .as("changelog files that still hold the old brand word (file -> count)").isEmpty();
    }

    @Test
    void noChangelogFileNameOrContentSpellsTheOldAuthor() throws Exception {
        assertThat(wordHits(List.of(LEGACY_AUTHOR_MD5)))
                .as("changelog files that still hold the old 054/060 author word (file -> count)").isEmpty();
    }

    @Test
    void masterIncludesTheRenamed017File() throws Exception {
        String master;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(MASTER)) {
            assertThat(in).isNotNull();
            master = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(master).contains("<include file=\"" + CATALOG_017 + "\"");
        assertThat(Pattern.compile("db/changelog/017-").matcher(master).results().count())
                .as("exactly one 017 include").isEqualTo(1);
    }

    @Test
    void every017ChangesetIsMarkedRanWhenItsOldRowExists() throws Exception {
        List<Element> sets = changeSets(CATALOG_017);
        assertThat(sets).hasSize(4);
        for (Element cs : sets) {
            String id = cs.getAttribute("id");
            String author = cs.getAttribute("author");
            NodeList pre = cs.getElementsByTagNameNS("*", "preConditions");
            assertThat(pre.getLength()).as(id + " has a precondition").isEqualTo(1);
            Element p = (Element) pre.item(0);
            assertThat(p.getAttribute("onFail")).as(id + " onFail").isEqualTo("MARK_RAN");
            NodeList checks = p.getElementsByTagNameNS("*", "sqlCheck");
            assertThat(checks.getLength()).as(id + " sqlCheck").isEqualTo(1);
            Element check = (Element) checks.item(0);
            assertThat(check.getAttribute("expectedResult")).isEqualTo("0");
            String sql = check.getTextContent().replaceAll("\\s+", " ");
            assertThat(sql)
                    .contains("FROM databasechangelog")
                    .contains("id = '" + id + "'")
                    .contains("author = '" + author + "'")
                    .contains("md5(regexp_replace(filename, '^.*/', '')) = '" + LEGACY_017_FILE_MD5 + "'");
        }
    }

    @Test
    void rekeyedChangesetsAreMarkedRanWhenTheirOldAuthorRowExists() throws Exception {
        for (Map.Entry<String, List<String>> e : REKEYED.entrySet()) {
            String file = e.getKey();
            List<Element> sets = changeSets(DIR + "/" + file);
            assertThat(sets.stream().map(cs -> cs.getAttribute("id")).toList())
                    .as(file + " changeset ids").containsExactlyElementsOf(e.getValue());
            for (Element cs : sets) {
                String id = cs.getAttribute("id");
                assertThat(cs.getAttribute("author")).as(id + " author").isEqualTo(AUTHOR);
                assertThat(validCheckSums(cs)).as(id + " has no validCheckSum (new identity)").isEmpty();
                NodeList pre = cs.getElementsByTagNameNS("*", "preConditions");
                assertThat(pre.getLength()).as(id + " has a precondition").isEqualTo(1);
                Element p = (Element) pre.item(0);
                assertThat(p.getAttribute("onFail")).as(id + " onFail").isEqualTo("MARK_RAN");
                // The guard must sit directly in the top-level AND (or be the
                // only check); inside a <not> or <or> it would mean something else.
                Element parent = p;
                NodeList ands = p.getElementsByTagNameNS("*", "and");
                if (ands.getLength() > 0) parent = (Element) ands.item(0);
                long guards = 0;
                for (var n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
                    if (!(n instanceof Element c) || !"sqlCheck".equals(c.getLocalName())) continue;
                    String sql = c.getTextContent().replaceAll("\\s+", " ");
                    if (!sql.contains("FROM databasechangelog")) continue;
                    assertThat(c.getAttribute("expectedResult")).as(id + " guard expects 0").isEqualTo("0");
                    assertThat(sql)
                            .contains("id = '" + id + "'")
                            .contains("md5(author) = '" + LEGACY_AUTHOR_MD5 + "'")
                            .contains("regexp_replace(filename, '^.*/', '') = '" + file + "'");
                    guards++;
                }
                assertThat(guards).as(id + " old-author guard in the top-level precondition").isEqualTo(1);
            }
        }
    }

    @Test
    void the054RoleIsCreatedUnderTheNewName() throws Exception {
        Element create = changeSets(DIR + "/054-register-sql-least-priv-role.xml").get(0);
        assertThat(create.getAttribute("id")).isEqualTo("054-1-create-register-sql-role");
        String sql = create.getElementsByTagNameNS("*", "sql").item(0).getTextContent();
        assertThat(sql).contains("CREATE ROLE hugmesh_register_sql");
    }

    @Test
    void the060RenameFindsTheOldRoleByItsMd5() throws Exception {
        Element cs = changeSets(DIR + "/060-rebrand-register-sql-role.xml").get(0);
        String pre = cs.getElementsByTagNameNS("*", "preConditions").item(0).getTextContent();
        String sql = cs.getElementsByTagNameNS("*", "sql").item(0).getTextContent();
        assertThat(pre).contains("md5(rolname) = '" + LEGACY_ROLE_MD5 + "'");
        assertThat(sql)
                .contains("md5(rolname) = '" + LEGACY_ROLE_MD5 + "'")
                .contains("RENAME TO hugmesh_register_sql");
    }
}
