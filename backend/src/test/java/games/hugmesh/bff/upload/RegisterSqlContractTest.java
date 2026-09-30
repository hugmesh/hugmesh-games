package games.hugmesh.bff.upload;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.fail;

/**
 * The upload-gate contract for the one {@code register.sql} this repository ships,
 * {@code games/character-forge/register/register.sql}, run through the REAL
 * {@link RegisterSqlPolicy}.
 *
 * <p><b>Why this test exists.</b> {@code GameUploadService#runRegisterSql} executes
 * exactly what {@link RegisterSqlPolicy#vet} returns, and {@code vet} is default-deny.
 * Its statement splitter is single-quote aware but does <b>not</b> model {@code --}
 * comments, so a semicolon inside a comment line ends a statement EARLY. The tail
 * fragment then no longer begins with a comment marker, survives
 * {@code stripLeadingComments}, matches neither the INSERT nor the UPDATE shape, and
 * the whole upload is rejected with 400: the install transaction rolls back and the
 * game never lands in the portal catalog. That shipped broken once (two semicolons
 * in the banner comments).
 *
 * <p>{@link RegisterSqlPolicyTest} and {@code GameUploadControllerHttpAuthzTest} only
 * feed the policy synthetic SQL, so nothing else runs the real file through the real
 * vetter. This contract used to live in hugmesh-character-forge-bff-api as a
 * hand-copied mirror of the splitter (the policy was in another repository); both the
 * file and the policy live here now, so this version drives the production code
 * directly and has nothing to keep in lockstep.
 *
 * <p>The file is resolved from the working directory (surefire runs in
 * {@code backend/}) or an ancestor up to the repository root. A missing file FAILS with
 * a message naming the path and never skips: the original reads a path that stopped
 * existing in hugmesh-character-forge-bff-api when the Godot project moved here
 * (b232d38), so it can only throw NoSuchFileException there; a skip would hide that.
 */
class RegisterSqlContractTest {

    private static final Path RELATIVE =
            Path.of("games", "character-forge", "register", "register.sql");

    private static final Pattern INSERT_INTO =
            Pattern.compile("^INSERT\\s+INTO\\s+([A-Za-z_][A-Za-z0-9_]*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern UPDATE_GAMES_FEATURES =
            Pattern.compile("^UPDATE\\s+games\\s+SET\\s+features\\s*=", Pattern.CASE_INSENSITIVE);

    static Path registerSql() {
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
        return fail("register.sql contract: %s not found from %s up to the repository root. "
                + "If the file moved, move this path with it; do not delete or skip the test.",
                RELATIVE, start);
    }

    private static String sql() throws IOException {
        return Files.readString(registerSql(), StandardCharsets.UTF_8);
    }

    @Test
    void theRealPolicyAdmitsEveryStatementOfTheRealFile() throws IOException {
        String sql = sql();

        assertThatCode(() -> RegisterSqlPolicy.vet(sql))
                .as("RegisterSqlPolicy.vet rejects %s; the upload would 400 and roll back",
                        RELATIVE)
                .doesNotThrowAnyException();

        List<String> statements = RegisterSqlPolicy.vet(sql);
        assertThat(statements)
                .as("the four catalog INSERTs + the feature-flag UPDATE, split by the real policy")
                .hasSize(5);
        for (String statement : statements) {
            assertThatCode(() -> RegisterSqlPolicy.validate(statement))
                    .as("RegisterSqlPolicy.validate rejects: %s", preview(statement))
                    .doesNotThrowAnyException();
            assertThat(INSERT_INTO.matcher(statement).find()
                    || UPDATE_GAMES_FEATURES.matcher(statement).find())
                    .as("every vetted statement is a catalog INSERT or the features UPDATE: %s",
                            preview(statement))
                    .isTrue();
        }
    }

    @Test
    void theFourCatalogRowsAndTheFeatureFlagUpdateAreAllPresent() throws IOException {
        List<String> targets = new ArrayList<>();
        for (String statement : RegisterSqlPolicy.vet(sql())) {
            Matcher insert = INSERT_INTO.matcher(statement);
            if (insert.find()) {
                targets.add(insert.group(1).toLowerCase(Locale.ROOT));
            } else if (UPDATE_GAMES_FEATURES.matcher(statement).find()) {
                targets.add("update:games.features");
            } else {
                targets.add("unexpected:" + preview(statement));
            }
        }
        assertThat(targets).containsExactly(
                "games", "chapters", "federation_modules", "game_access", "update:games.features");
    }

    /**
     * The root-cause guard: the vetter's lexer treats a {@code --} line as ordinary
     * text, so a semicolon there splits, and an unbalanced quote there flips the
     * in-string state for the REST of the file. Neither may appear in a comment.
     */
    @Test
    void noCommentLineCarriesASemicolonOrAnUnbalancedQuote() throws IOException {
        String[] lines = sql().split("\n", -1);
        int comments = 0;
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (!trimmed.startsWith("--")) {
                continue;
            }
            comments++;
            assertThat(trimmed)
                    .as("register.sql:%d: a semicolon in a comment splits the statement early",
                            i + 1)
                    .doesNotContain(";");
            assertThat(count(trimmed, '\'') % 2)
                    .as("register.sql:%d: an odd number of single quotes in a comment "
                            + "desyncs the vetter's string lexer", i + 1)
                    .isZero();
        }
        assertThat(comments)
                .as("the file carries its banner comments, so this check is not vacuous")
                .isPositive();
    }

    /**
     * The constructs {@code RegisterSqlPolicy#rejectUnsupportedLexerConstructs} fails
     * closed on. The real {@code vet} call in the first test already proves none sits
     * outside a string literal; these textual checks are stricter on purpose and keep
     * them out of literals and comments too, where the lightweight lexer's view of
     * "inside a string" is exactly what a stray quote would corrupt.
     */
    @Test
    void carriesNoLexerConstructTheVetterFailsClosedOn() throws IOException {
        String body = sql();
        assertThat(body).as("C-style block comments are rejected outright").doesNotContain("/*");
        assertThat(body).as("dollar-quoting is rejected outright").doesNotContain("$$");
        assertThat(Pattern.compile("\\$[A-Za-z_][A-Za-z0-9_]*\\$").matcher(body).find())
                .as("tagged dollar-quoting $tag$ is rejected outright")
                .isFalse();
        assertThat(Pattern.compile("(?<![A-Za-z0-9_])[Ee]'").matcher(body).find())
                .as("C-style escape strings E'...' are rejected outright")
                .isFalse();
    }

    private static int count(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) {
                n++;
            }
        }
        return n;
    }

    private static String preview(String statement) {
        String flat = statement.replace('\n', ' ').trim();
        return flat.length() > 90 ? flat.substring(0, 90) + "..." : flat;
    }
}
