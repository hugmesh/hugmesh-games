package games.snapdragon.bff.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pinning specs for {@link ConnectionUrls}.
 *
 * <p>This masker is the games realm's single answer to the OWASP audit's
 * CWE-532 finding (DB credentials reachable in connection-wiring logs). The
 * contracts locked in here:
 *
 * <ul>
 *   <li>the {@code user:pass@host} userinfo form is redacted while host /
 *       port / path survive;</li>
 *   <li>credential query params / driver options
 *       ({@code user}/{@code password}/{@code pass}/{@code apikey}) are
 *       redacted, case-insensitively, while non-credential params survive;</li>
 *   <li>a credential-free URL is returned byte-for-byte;</li>
 *   <li>null / blank pass through unchanged, and a malformed string never
 *       throws (it is called on error paths about to log a bad URL).</li>
 * </ul>
 */
class ConnectionUrlsTest {

    @Test
    void redactsUserinfoFormButKeepsHostAndPath() {
        String masked = ConnectionUrls.mask(
                "r2dbc:postgresql://gw:S3cret@pg:5432/snapdragon?sslMode=require");
        assertThat(masked).doesNotContain("S3cret");
        assertThat(masked).isEqualTo(
                "r2dbc:postgresql://****@pg:5432/snapdragon?sslMode=require");
    }

    @Test
    void redactsCredentialQueryParamsCaseInsensitively() {
        String masked = ConnectionUrls.mask(
                "r2dbc:postgresql://pg:5432/db?user=gw&PASSWORD=S3cret&applicationName=bff");
        assertThat(masked).doesNotContain("S3cret");
        assertThat(masked).doesNotContain("=gw");
        // Non-credential param is preserved verbatim.
        assertThat(masked).contains("applicationName=bff");
        assertThat(masked).isEqualTo(
                "r2dbc:postgresql://pg:5432/db?user=****&PASSWORD=****&applicationName=bff");
    }

    @Test
    void leavesCredentialFreeUrlUnchanged() {
        String url = "r2dbc:postgresql://pg:5432/snapdragon?sslMode=require";
        assertThat(ConnectionUrls.mask(url)).isEqualTo(url);
    }

    @Test
    void passesNullBlankAndMalformedThrough() {
        assertThat(ConnectionUrls.mask(null)).isNull();
        assertThat(ConnectionUrls.mask("")).isEmpty();
        assertThat(ConnectionUrls.mask("   ")).isEqualTo("   ");
        // No '@', no recognised cred param → returned as-is, never throws.
        assertThat(ConnectionUrls.mask("not a url")).isEqualTo("not a url");
    }
}
