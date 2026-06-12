package games.snapdragon.bff.security;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Specs for {@link DomainRegistry} — the issuer-&gt;brand map that backs the
 * games-realm domain scoping. The registry is the trust boundary: it converts a
 * cryptographically-validated issuer into a brand, fails closed on an unknown
 * issuer in multi-brand mode, and preserves single-issuer back-compat.
 *
 * <p>Delineated:
 * <ul>
 *   <li><b>USER-SITUATIONAL</b> — back-compat: no/one issuer config =&gt; every
 *       token is {@code default}.</li>
 *   <li><b>MULTIUSER-SITUATIONAL</b> — multi-brand: each configured issuer maps
 *       to its own brand.</li>
 *   <li><b>CROSS-USER (isolation)</b> — an unknown issuer fails closed
 *       ({@code null}), never leaking another brand's domain.</li>
 * </ul>
 */
class DomainRegistryTest {

    private static final String ISS_X = "https://casdoor.brandx.example";
    private static final String ISS_Y = "https://casdoor.brandy.example";

    // ===================================================================
    // USER-SITUATIONAL — single-issuer back-compat
    // ===================================================================
    @Nested
    class SingleIssuerBackCompat {

        @Test
        void noConfigAtAllMapsEverythingToDefault() {
            DomainRegistry reg = new DomainRegistry("", "");
            assertThat(reg.domainOf("https://anything.example")).isEqualTo("default");
            assertThat(reg.domainOf(null)).isEqualTo("default");
            assertThat(reg.isMultiIssuer()).isFalse();
        }

        @Test
        void loneIssuerUriSeedsSingleDefaultEntry() {
            DomainRegistry reg = new DomainRegistry(ISS_X, "");
            assertThat(reg.domainOf(ISS_X)).isEqualTo("default");
            assertThat(reg.isMultiIssuer()).isFalse();
            assertThat(reg.isTrusted(ISS_X)).isTrue();
        }

        @Test
        void trailingSlashOnIssuerIsNormalized() {
            DomainRegistry reg = new DomainRegistry(ISS_X + "/", "");
            assertThat(reg.domainOf(ISS_X)).isEqualTo("default");
            assertThat(reg.domainOf(ISS_X + "/")).isEqualTo("default");
        }
    }

    // ===================================================================
    // MULTIUSER-SITUATIONAL — multi-brand mapping
    // ===================================================================
    @Nested
    class MultiBrand {

        @Test
        void eachConfiguredIssuerMapsToItsBrand() {
            DomainRegistry reg = new DomainRegistry("", ISS_X + "=brandx," + ISS_Y + "=brandy");
            assertThat(reg.domainOf(ISS_X)).isEqualTo("brandx");
            assertThat(reg.domainOf(ISS_Y)).isEqualTo("brandy");
            assertThat(reg.isMultiIssuer()).isTrue();
            assertThat(reg.trustedIssuers()).containsExactlyInAnyOrder(ISS_X, ISS_Y);
        }

        @Test
        void domainNamesAreSanitizedToLowercaseSafeChars() {
            DomainRegistry reg = new DomainRegistry("", ISS_X + "=Brand X!");
            assertThat(reg.domainOf(ISS_X)).isEqualTo("brand-x-");
        }

        @Test
        void malformedPairsAreSkipped() {
            // no '=' => skipped; empty issuer or domain => skipped.
            DomainRegistry reg = new DomainRegistry("", "garbage," + ISS_X + "=brandx,=orphan," + ISS_Y + "=");
            assertThat(reg.domainOf(ISS_X)).isEqualTo("brandx");
            assertThat(reg.trustedIssuers()).containsExactly(ISS_X);
        }
    }

    // ===================================================================
    // CROSS-USER — fail-closed isolation
    // ===================================================================
    @Nested
    class FailClosed {

        @Test
        void unknownIssuerInMultiBrandModeReturnsNull() {
            DomainRegistry reg = new DomainRegistry("", ISS_X + "=brandx," + ISS_Y + "=brandy");
            assertThat(reg.domainOf("https://casdoor.evil.example")).isNull();
            assertThat(reg.isTrusted("https://casdoor.evil.example")).isFalse();
            assertThat(reg.domainOf(null)).isNull();
        }

        @Test
        void singleBackCompatEntryFromIssuerUriIsNotMultiIssuer() {
            // issuer-uri configured AND one tenancy pair that equals it => still
            // multi-issuer is decided by the parsed pair count.
            DomainRegistry reg = new DomainRegistry(ISS_X, ISS_X + "=brandx");
            assertThat(reg.isMultiIssuer()).isFalse();
            assertThat(reg.domainOf(ISS_X)).isEqualTo("brandx");
        }
    }
}
