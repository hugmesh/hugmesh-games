package games.snapdragon.bff.util;

import java.util.regex.Pattern;

/**
 * Redacts credentials embedded in DB connection strings before they reach
 * logs (CWE-532, "Insertion of Sensitive Information into Log File").
 *
 * <p>The OWASP audit flagged that connection-wiring log lines printed raw
 * R2DBC URLs, and that the discipline was applied ad hoc per call site with
 * no shared helper. This is that shared helper for the games realm: one
 * masker that strips BOTH credential forms an R2DBC/JDBC URL can carry —
 *
 * <ul>
 *   <li>the {@code scheme://user:pass@host} userinfo form, and</li>
 *   <li>credential query params / driver options
 *       ({@code user} / {@code password} / {@code pass} / {@code apikey}).</li>
 * </ul>
 *
 * <p>It is pure string rewriting (no URI parsing) and {@link #mask} never
 * throws, so it is safe to wrap a malformed URL that is about to be logged on
 * an error path — exactly the case where a parse failure would otherwise echo
 * the offending string verbatim.
 */
public final class ConnectionUrls {

    private ConnectionUrls() {
    }

    private static final String REDACTED = "****";

    /** {@code scheme://user:pass@host} → {@code scheme://****@host}. */
    private static final Pattern USERINFO = Pattern.compile("(://)[^@/?#]*@");

    /** {@code ?password=…} / {@code &user=…} / {@code ;apikey=…} (case-insensitive). */
    private static final Pattern CRED_PARAM =
            Pattern.compile("(?i)([?&;](?:password|pass|user|apikey)=)[^&;#\\s]*");

    /**
     * Returns {@code url} with any embedded credentials redacted. Null or
     * blank input is returned unchanged. The host, port, path, and any
     * non-credential query params are preserved so the log line stays useful
     * for diagnosing connectivity.
     */
    public static String mask(String url) {
        if (url == null || url.isBlank()) {
            return url;
        }
        String masked = USERINFO.matcher(url).replaceAll("$1" + REDACTED + "@");
        masked = CRED_PARAM.matcher(masked).replaceAll("$1" + REDACTED);
        return masked;
    }
}
