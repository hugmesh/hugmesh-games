package games.snapdragon.bff.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Issuer-URI to brand/domain map — the single source of truth the converter
 * ({@code SecurityConfig.casdoorAuthoritiesConverter}), the {@link Roles} helpers,
 * and any domain-scoping service/repository layer all consult to derive the brand a
 * token belongs to. Copied from snapdragon-gateway
 * ({@code systems.snapdragon.gateway.security.DomainRegistry}) so the games BFF
 * derives tenant scope identically to the gateway.
 *
 * <h2>Why the issuer</h2>
 *
 * <p>The platform's tenancy decision is one Casdoor per brand, so the token's
 * cryptographically-validated {@code iss} claim is the trustworthy tenant
 * discriminator. The resource server validates {@code iss} against the configured
 * issuer(s) before any authority is emitted, so an issuer reaching this registry
 * is already trusted. Never key the domain off a user-settable claim
 * ({@code owner}/{@code org}/{@code aud}) — a user could forge cross-tenant scope.
 *
 * <h2>Single-issuer back-compat</h2>
 *
 * <p>Given only the existing
 * {@code spring.security.oauth2.resourceserver.jwt.issuer-uri} and an empty
 * {@code app.tenancy.issuers}, the registry seeds exactly one entry
 * {@code {thatIssuer -> "default"}}; with NO issuer-uri at all (the games BFF
 * currently configures only a {@code jwk-set-uri}) the map stays empty and
 * {@link #domainOf(String)} returns {@code "default"} unconditionally. So a
 * single-issuer cluster emits {@code DOMAIN_default} for every token and nothing
 * else changes.
 *
 * <h2>Multi-brand fail-closed</h2>
 *
 * <p>When {@code app.tenancy.issuers} is populated, ONLY the listed issuers are
 * trusted; an issuer not in the map yields {@code null} from
 * {@link #domainOf(String)} so callers fail closed (and the multi-issuer decoder,
 * built from {@link #trustedIssuers()}, 401s an unknown {@code iss} before
 * authorities are emitted).
 *
 * <h2>Caller-role access matrix</h2>
 *
 * <pre>
 *  Caller                 │ domainOf() result
 *  ───────────────────────┼───────────────────────────────────────────────
 *  Single-issuer cluster  │ always "default" (map size 0 → empty branch)
 *  Trusted issuer         │ the configured domain string for that issuer
 *  Unknown issuer         │ null — caller must fail closed
 * </pre>
 *
 * @see games.snapdragon.bff.config.CasdoorAuthorities
 * @see Roles
 * @see games.snapdragon.bff.config.DomainGuardFilter
 * @see games.snapdragon.bff.config.TenancyResourceServerConfig
 */
@Component
public class DomainRegistry {

    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    /** The single-tenant / legacy domain every token maps to when no per-brand
     *  registry is configured. Exposed as a constant so all comparisons use the
     *  same literal rather than scattered {@code "default"} strings. */
    public static final String DEFAULT_DOMAIN = "default";

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    /**
     * Insertion-ordered map from trimmed, trailing-slash-stripped issuer URI
     * to the sanitised ({@code [a-z0-9-]}) brand/domain name. Populated at
     * construction and never mutated afterwards — safe for concurrent reads
     * without synchronisation.
     */
    private final Map<String, String> issuerToDomain = new LinkedHashMap<>();

    /**
     * The lone back-compat issuer URI from
     * {@code spring.security.oauth2.resourceserver.jwt.issuer-uri}. Used only
     * to seed the {@code -> DEFAULT_DOMAIN} entry when no multi-brand
     * {@code app.tenancy.issuers} CSV is provided.
     */
    private final String singleIssuer;

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    /**
     * Builds the registry from Spring properties.
     *
     * <p>The {@code app.tenancy.issuers} property is a comma-separated list of
     * {@code issuerUri=domainName} pairs (e.g.
     * {@code https://auth.brand-a.example=brand-a,https://auth.brand-b.example=brand-b}).
     * Each pair is split on the FIRST {@code =}; the issuer is trailing-slash-stripped
     * (Casdoor sometimes emits trailing slashes), the domain is sanitised to
     * {@code [a-z0-9-]}. Malformed pairs (no {@code =}, or empty side) are silently
     * skipped.
     *
     * <p>If the CSV is absent or blank <em>and</em> {@code issuer-uri} is set,
     * the registry seeds one {@code {issuer-uri -> "default"}} entry so
     * {@link #isMultiIssuer()} returns {@code false} and
     * {@link #domainOf(String)} returns {@code "default"} for the single known issuer.
     *
     * @param singleIssuer the {@code spring.security.oauth2.resourceserver.jwt.issuer-uri}
     *                     property value; empty string when not configured
     * @param issuersCsv   the {@code app.tenancy.issuers} property value;
     *                     empty string when not configured
     */
    public DomainRegistry(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:}") String singleIssuer,
            @Value("${app.tenancy.issuers:}") String issuersCsv) {
        this.singleIssuer = trimSlash(singleIssuer);
        if (issuersCsv != null && !issuersCsv.isBlank()) {
            // Parse each issuerUri=domain pair from the CSV.
            for (String pair : issuersCsv.split(",")) {
                int eq = pair.indexOf('=');
                if (eq <= 0) continue;
                String iss = trimSlash(pair.substring(0, eq).trim());
                String dom = sanitize(pair.substring(eq + 1).trim());
                if (!iss.isEmpty() && !dom.isEmpty()) issuerToDomain.put(iss, dom);
            }
        }
        // Backward-compat: with NO multi-issuer config, the lone configured issuer
        // maps to DEFAULT_DOMAIN so a single-issuer cluster is unchanged.
        if (issuerToDomain.isEmpty() && !this.singleIssuer.isEmpty()) {
            issuerToDomain.put(this.singleIssuer, DEFAULT_DOMAIN);
        }
    }

    // -------------------------------------------------------------------------
    // Registry lookups
    // -------------------------------------------------------------------------

    /**
     * Returns the brand/domain for the given token issuer URI.
     *
     * <p>Three cases:
     * <ol>
     *   <li><b>Registry empty</b> (no {@code issuer-uri} and no
     *       {@code app.tenancy.issuers}) — returns {@code "default"} so a
     *       jwk-set-uri-only single-issuer cluster is unchanged.</li>
     *   <li><b>Known issuer</b> — returns the configured domain string.</li>
     *   <li><b>Unknown issuer in multi-brand mode</b> — returns {@code null}
     *       (fail-closed). Callers must treat {@code null} as a denial.</li>
     * </ol>
     *
     * @param issuer the raw {@code iss} claim value from the JWT; trailing
     *               slashes are stripped before the map lookup
     * @return the brand/domain string, {@code "default"} for a registry-empty
     *         cluster, or {@code null} when multi-brand is active and the
     *         issuer is untrusted
     */
    public String domainOf(String issuer) {
        String iss = trimSlash(issuer);
        if (issuerToDomain.isEmpty()) return DEFAULT_DOMAIN;          // no config -> legacy single-tenant
        String d = issuerToDomain.get(iss);
        if (d != null) return d;
        // Multi-brand mode with an unknown issuer: fail closed. The decoder, built
        // from trustedIssuers(), rejects this iss upstream; defensively return null so
        // any in-process caller also fails closed.
        return null;
    }

    /**
     * Returns {@code true} iff this issuer is in the trusted set (i.e.
     * {@link #domainOf(String)} returns a non-null value). Used by
     * {@link games.snapdragon.bff.config.TenancyResourceServerConfig} to gate
     * whether the per-issuer decoder resolver returns a manager or
     * {@code Mono.empty()} (which yields 401).
     *
     * @param issuer the raw {@code iss} claim value from the JWT
     * @return {@code true} iff a non-null domain is mapped for this issuer
     */
    public boolean isTrusted(String issuer) {
        return domainOf(issuer) != null;
    }

    /**
     * Returns the set of all trusted issuer URIs — the allowlist the multi-issuer
     * decoder is built from. An {@code iss} claim outside this set triggers a 401
     * before any authority is emitted.
     *
     * @return the unmodifiable view of the issuer keys in insertion order
     */
    public java.util.Set<String> trustedIssuers() {
        return issuerToDomain.keySet();
    }

    /**
     * Returns {@code true} iff a per-brand multi-issuer registry was configured
     * (more than the single back-compat {@code issuer-uri -> "default"} entry).
     *
     * <p>This gates whether {@link games.snapdragon.bff.config.SecurityConfig}
     * swaps onto the per-issuer authentication-manager resolver contributed by
     * {@link games.snapdragon.bff.config.TenancyResourceServerConfig}. A registry
     * with exactly one entry (the single-issuer back-compat case) keeps the
     * existing {@code o.jwt(...)} single-decoder path unchanged.
     *
     * @return {@code true} iff more than one trusted issuer is registered
     */
    public boolean isMultiIssuer() {
        return issuerToDomain.size() > 1;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Strips a trailing {@code /} from an issuer URI and trims surrounding
     * whitespace. Casdoor occasionally emits URIs with a trailing slash; the
     * resource server strips it during validation, so we must strip it here too
     * to keep the map key consistent.
     *
     * @param s the raw issuer URI string; may be {@code null}
     * @return the trimmed, trailing-slash-free string, or {@code ""} for null input
     */
    private static String trimSlash(String s) {
        if (s == null) return "";
        String t = s.trim();
        return t.endsWith("/") ? t.substring(0, t.length() - 1) : t;
    }

    /**
     * Sanitises a domain name to the {@code [a-z0-9-]} character set used by
     * the converter and filter. Any character outside that set is replaced with
     * {@code -}, and the result is lowercased, so a hand-typed
     * {@code "BrandX"} or {@code "brand_x"} is treated the same way the
     * {@link games.snapdragon.bff.config.CasdoorAuthorities} converter treats it.
     *
     * @param s the raw domain string from the configuration; may be {@code null}
     * @return the sanitised lowercase string, or {@code ""} for null input
     */
    private static String sanitize(String s) {
        return s == null ? "" : s.trim().toLowerCase().replaceAll("[^a-z0-9-]", "-");
    }
}
