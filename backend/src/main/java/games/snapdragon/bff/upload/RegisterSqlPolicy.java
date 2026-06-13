package games.snapdragon.bff.upload;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Allowlist policy for the SQL executed out of an uploaded bundle's
 * {@code register.sql} (see {@code GameUploadService#runRegisterSql}).
 *
 * <p><b>Why this exists.</b> {@code register.sql} ships <em>inside the
 * uploaded zip</em>, so its bytes are attacker-controlled by anyone who can
 * reach {@code POST /api/admin/games/upload} — a scoped publisher, an
 * uploader, or an admin. Executing it verbatim turned the upload path into an
 * arbitrary-SQL primitive: a malicious bundle could {@code DROP}/{@code DELETE}
 * platform tables, {@code GRANT} itself roles, write
 * {@code publisher_game_scopes} / {@code player_characters} directly, capture a
 * foreign brand by writing the server-owned {@code domain} column, or — if the
 * Postgres role is privileged — {@code COPY … TO PROGRAM} for host RCE. None of
 * that is something a publisher is supposed to be able to do; it breaks the
 * least-privilege role cascade and tenant isolation. (OWASP audit, A03/A04.)
 *
 * <p><b>What it allows.</b> Only the statements the export plugin / maker
 * renderers actually emit to self-register a game's catalog rows:
 * <ul>
 *   <li>{@code INSERT INTO &lt;catalog table&gt;} (with {@code ON CONFLICT … DO
 *       UPDATE/NOTHING}) where the table is one of {@link #INSERT_TABLES};</li>
 *   <li>{@code UPDATE games SET …} restricted to {@link #GAMES_UPDATE_COLUMNS}
 *       (the per-game feature-flag write).</li>
 * </ul>
 * Anything else — a different verb, a non-catalog table, or any reference to
 * the server-owned {@code domain} column — fails the upload with 400 and rolls
 * the install transaction back. Default-deny: a statement that doesn't clearly
 * match an allowed shape is rejected.
 *
 * <p><b>Residual / defence-in-depth.</b> An allowed {@code INSERT … SELECT}
 * can still read other tables into a catalog row. The durable fix is to run
 * {@code register.sql} under a least-privilege Postgres role (no DDL, no access
 * to player/role tables, {@code NOSUPERUSER} so no {@code COPY … TO PROGRAM});
 * this application-layer allowlist is the immediate containment.
 */
public final class RegisterSqlPolicy {

    private RegisterSqlPolicy() {
    }

    /** Catalog tables a bundle may self-register rows into. */
    static final Set<String> INSERT_TABLES = Set.of(
            "games", "levels", "chapters",
            "federation_modules", "game_access", "game_event_support");

    /** Columns a standalone {@code UPDATE games} may set (feature flags only). */
    static final Set<String> GAMES_UPDATE_COLUMNS = Set.of("features");

    /** The server exclusively owns tenant assignment — never writable here. */
    private static final String FORBIDDEN_COLUMN = "domain";

    private static final Pattern INSERT_INTO =
            Pattern.compile("^INSERT\\s+INTO\\s+([A-Za-z_][A-Za-z0-9_]*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern UPDATE_TABLE =
            Pattern.compile("^UPDATE\\s+([A-Za-z_][A-Za-z0-9_]*)\\s+SET\\b", Pattern.CASE_INSENSITIVE);

    /**
     * Validates a whole {@code register.sql} blob: splits it into statements
     * (the same {@code ;}-outside-strings split the executor uses, dropping
     * comment-only fragments) and checks each against the allowlist. Throws
     * {@link ResponseStatusException} 400 on the first violation. Returns the
     * vetted statements in execution order so the caller runs exactly what was
     * validated.
     */
    public static List<String> vet(String sql) {
        List<String> stmts = split(sql);
        for (String s : stmts) {
            validate(s);
        }
        return stmts;
    }

    /** Validates a single already-split, comment-stripped statement. */
    static void validate(String statement) {
        String s = statement.strip();
        if (s.isEmpty()) {
            return;
        }

        if (mentionsIdentifier(s, FORBIDDEN_COLUMN)) {
            throw reject(s, "may not reference the server-owned '" + FORBIDDEN_COLUMN + "' column");
        }

        Matcher ins = INSERT_INTO.matcher(s);
        if (ins.find()) {
            String table = ins.group(1).toLowerCase();
            if (!INSERT_TABLES.contains(table)) {
                throw reject(s, "INSERT into non-catalog table '" + table + "'");
            }
            return;
        }

        Matcher upd = UPDATE_TABLE.matcher(s);
        if (upd.find()) {
            String table = upd.group(1).toLowerCase();
            if (!"games".equals(table)) {
                throw reject(s, "UPDATE of non-allowlisted table '" + table + "'");
            }
            for (String col : updateColumns(s)) {
                if (!GAMES_UPDATE_COLUMNS.contains(col)) {
                    throw reject(s, "UPDATE games may not set column '" + col + "'");
                }
            }
            return;
        }

        throw reject(s, "only INSERT into a catalog table or UPDATE of games feature flags is allowed");
    }

    private static ResponseStatusException reject(String stmt, String why) {
        String preview = stmt.length() > 80 ? stmt.substring(0, 80) + "…" : stmt;
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "register.sql rejected (" + why + "): " + preview);
    }

    // ── statement splitting (mirrors GameUploadService#runRegisterSql) ──────

    private static List<String> split(String sql) {
        List<String> stmts = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inString = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\'' && (i == 0 || sql.charAt(i - 1) != '\\')) {
                inString = !inString;
            }
            if (c == ';' && !inString) {
                addIfStatement(stmts, cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        addIfStatement(stmts, cur.toString());
        return stmts;
    }

    private static void addIfStatement(List<String> stmts, String raw) {
        String s = stripLeadingComments(raw);
        if (!s.isEmpty()) {
            stmts.add(s);
        }
    }

    /**
     * Drops leading blank lines and {@code --} comment lines from a fragment,
     * then trims. The generator prefaces its first statement with a comment
     * banner; without this the whole "comment + first INSERT" fragment trims
     * to a string starting with {@code --} and the first statement would be
     * silently skipped. A fragment that is only comments collapses to empty
     * (skipped); a real statement after the banner is kept and still vetted.
     */
    private static String stripLeadingComments(String raw) {
        String[] lines = raw.split("\n", -1);
        int start = 0;
        while (start < lines.length) {
            String t = lines[start].trim();
            if (t.isEmpty() || t.startsWith("--")) {
                start++;
            } else {
                break;
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < lines.length; i++) {
            if (i > start) {
                sb.append('\n');
            }
            sb.append(lines[i]);
        }
        return sb.toString().trim();
    }

    // ── lightweight lexing helpers (string-literal aware) ───────────────────

    /**
     * True if {@code word} appears as a bare identifier (not inside a single-
     * quoted string literal) in {@code s}. Lets a value like
     * {@code 'my domain game'} pass while catching a {@code domain} column in
     * an INSERT column list, a SET clause, or an ON CONFLICT DO UPDATE.
     */
    private static boolean mentionsIdentifier(String s, String word) {
        boolean inString = false;
        StringBuilder ident = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'' && (i == 0 || s.charAt(i - 1) != '\\')) {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (Character.isLetterOrDigit(c) || c == '_') {
                ident.append(c);
            } else {
                if (ident.toString().equalsIgnoreCase(word)) {
                    return true;
                }
                ident.setLength(0);
            }
        }
        return ident.toString().equalsIgnoreCase(word);
    }

    /**
     * Assignment-target columns of an {@code UPDATE … SET …} statement: the
     * identifier immediately before each top-level (outside-string) {@code =},
     * stopping at a top-level {@code WHERE}. Returns lowercased column names.
     */
    private static List<String> updateColumns(String s) {
        int set = indexOfTopLevelKeyword(s, "SET");
        List<String> cols = new ArrayList<>();
        if (set < 0) {
            return cols;
        }
        boolean inString = false;
        StringBuilder ident = new StringBuilder();
        String lastIdent = "";
        for (int i = set + 3; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'' && s.charAt(i - 1) != '\\') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (Character.isLetterOrDigit(c) || c == '_') {
                ident.append(c);
                continue;
            }
            if (ident.length() > 0) {
                if (ident.toString().equalsIgnoreCase("WHERE")) {
                    break;
                }
                lastIdent = ident.toString();
                ident.setLength(0);
            }
            if (c == '=') {
                cols.add(lastIdent.toLowerCase());
            }
        }
        return cols;
    }

    /** Index of a keyword token at the top level (outside string literals), or -1. */
    private static int indexOfTopLevelKeyword(String s, String kw) {
        boolean inString = false;
        StringBuilder ident = new StringBuilder();
        int identStart = -1;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'' && (i == 0 || s.charAt(i - 1) != '\\')) {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (Character.isLetterOrDigit(c) || c == '_') {
                if (ident.length() == 0) {
                    identStart = i;
                }
                ident.append(c);
            } else {
                if (ident.toString().equalsIgnoreCase(kw)) {
                    return identStart;
                }
                ident.setLength(0);
            }
        }
        return ident.toString().equalsIgnoreCase(kw) ? identStart : -1;
    }
}
