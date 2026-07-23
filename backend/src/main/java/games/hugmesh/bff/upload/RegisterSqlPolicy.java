package games.hugmesh.bff.upload;

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
        // Reject the lexer constructs this policy does not (and need not) model
        // BEFORE splitting. The legitimate export plugin only ever emits plain
        // single-quoted literals + '--' line comments; anything fancier is both
        // out-of-policy and a desync risk for the lightweight lexer below, so we
        // fail closed instead of trying to lex it precisely. (OWASP A03 — the
        // original lexer mis-handled all of these.)
        rejectUnsupportedLexerConstructs(sql);
        List<String> stmts = split(sql);
        for (String s : stmts) {
            validate(s);
        }
        return stmts;
    }

    /**
     * Fail-closed pre-scan for SQL string/comment forms the lightweight lexer
     * does not model and the generator never emits:
     * <ul>
     *   <li>C-style escape strings {@code E'…'} / {@code e'…'} (backslash IS an
     *       escape there, the inverse of standard-conforming strings);</li>
     *   <li>dollar-quoting {@code $tag$ … $tag$} (a {@code ;} or {@code '} in the
     *       body is data, not a separator/quote);</li>
     *   <li>C-style block comments {@code /* … *}{@code /} (can hide a quote or a
     *       {@code ;}).</li>
     * </ul>
     * Scans OUTSIDE plain single-quoted literals (which the same
     * standard-conforming, doubled-quote-aware rules the splitter uses define)
     * so the literal value {@code 'E''s game'} or {@code 'a $ b'} stays legal.
     */
    private static void rejectUnsupportedLexerConstructs(String sql) {
        boolean inString = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (inString) {
                if (c == '\'') {
                    // Doubled '' is an escaped quote (stay in string); a lone '
                    // closes. Backslash is NOT special (standard_conforming_strings).
                    if (i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                        i++;
                    } else {
                        inString = false;
                    }
                }
                continue;
            }
            // Outside a string literal:
            if (c == '\'') {
                // An E/e immediately preceding the opening quote => escape string.
                if (i > 0) {
                    char p = sql.charAt(i - 1);
                    if ((p == 'E' || p == 'e')
                            && (i == 1 || !isIdentChar(sql.charAt(i - 2)))) {
                        throw reject(sql, "C-style escape string E'...' is not allowed");
                    }
                }
                inString = true;
            } else if (c == '$') {
                if (isDollarQuoteStart(sql, i)) {
                    throw reject(sql, "dollar-quoting $tag$...$tag$ is not allowed");
                }
            } else if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                throw reject(sql, "C-style block comment /* */ is not allowed");
            }
        }
    }

    private static boolean isIdentChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /**
     * True if a dollar-quote tag opens at {@code idx} ({@code c=='$'}): a
     * {@code $}, an optional tag of identifier chars, then a closing {@code $}
     * — e.g. {@code $$} or {@code $tag$}. A bare {@code $1} positional param or
     * a {@code $} in arithmetic is NOT a dollar-quote and is left alone.
     */
    private static boolean isDollarQuoteStart(String sql, int idx) {
        int j = idx + 1;
        // tag is letters/digits/underscore but must not START with a digit
        if (j < sql.length() && Character.isDigit(sql.charAt(j))) {
            return false;
        }
        while (j < sql.length() && isIdentChar(sql.charAt(j))) {
            j++;
        }
        return j < sql.length() && sql.charAt(j) == '$';
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
            if (inString) {
                cur.append(c);
                // Standard-conforming strings: backslash is DATA, never an
                // escape. A single quote closes the literal UNLESS it is doubled
                // ('') — the SQL-standard escaped quote — in which case both
                // chars stay inside the string.
                if (c == '\'') {
                    if (i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                        cur.append(sql.charAt(++i));
                    } else {
                        inString = false;
                    }
                }
                continue;
            }
            if (c == '\'') {
                inString = true;
                cur.append(c);
            } else if (c == ';') {
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
            if (inString) {
                // Standard-conforming: backslash is data; '' is an escaped quote.
                if (c == '\'') {
                    if (i + 1 < s.length() && s.charAt(i + 1) == '\'') {
                        i++;
                    } else {
                        inString = false;
                    }
                }
                continue;
            }
            if (c == '\'') {
                inString = true;
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
            if (inString) {
                if (c == '\'') {
                    if (i + 1 < s.length() && s.charAt(i + 1) == '\'') {
                        i++;
                    } else {
                        inString = false;
                    }
                }
                continue;
            }
            if (c == '\'') {
                inString = true;
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
            if (inString) {
                if (c == '\'') {
                    if (i + 1 < s.length() && s.charAt(i + 1) == '\'') {
                        i++;
                    } else {
                        inString = false;
                    }
                }
                continue;
            }
            if (c == '\'') {
                inString = true;
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
