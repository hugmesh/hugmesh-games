package games.snapdragon.bff.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Fail-fast guard for prod-sensitive credentials (NIST IA-5 / SC-12;
 * OWASP ASVS 6.2.2 — "no hardcoded / default credentials").
 *
 * <p>The base {@code application.yml} no longer ships insecure literal
 * defaults for the Casdoor client-secret or the database password — an
 * unset env var resolves to empty. That keeps the documented local
 * first-boot flow working (you bring {@code docker compose up -d} up
 * once, read the freshly-generated Casdoor secret out of the container
 * log, then paste it into {@code .env}) and keeps the e2e /
 * integration-test stack booting. But an empty credential must never
 * reach a real deployment.
 *
 * <p>When {@code app.security.require-managed-secrets=true} (set by
 * {@code compose.prod.yml} and {@code compose.staging.yml}; in the real
 * cluster the values arrive via k8s + External-Secrets-Operator + Vault)
 * this validator refuses to finish context startup if any prod-sensitive
 * credential is blank — so a misconfigured production deploy crashes
 * loudly at boot instead of silently running with a missing or guessable
 * credential.
 *
 * <p>Default {@code false}: vanilla local dev / compose / tests are
 * unaffected.
 */
@Component
public class StartupSecretValidator {

    private static final Logger log = LoggerFactory.getLogger(StartupSecretValidator.class);

    private final boolean requireManagedSecrets;
    private final String casdoorClientSecret;
    private final String dbPassword;

    public StartupSecretValidator(
            @Value("${app.security.require-managed-secrets:false}") boolean requireManagedSecrets,
            @Value("${casdoor.client-secret:}") String casdoorClientSecret,
            @Value("${spring.r2dbc.password:}") String dbPassword) {
        this.requireManagedSecrets = requireManagedSecrets;
        this.casdoorClientSecret = casdoorClientSecret;
        this.dbPassword = dbPassword;
    }

    @PostConstruct
    void validate() {
        if (!requireManagedSecrets) {
            log.debug("[startup-secret-validator] app.security.require-managed-secrets=false "
                    + "— skipping prod credential fail-fast (dev/compose/test path)");
            return;
        }

        List<String> missing = new ArrayList<>();
        if (isBlank(casdoorClientSecret)) {
            missing.add("casdoor.client-secret (CASDOOR_CLIENT_SECRET)");
        }
        if (isBlank(dbPassword)) {
            missing.add("database password (DB_PASS / spring.r2dbc.password)");
        }

        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "app.security.require-managed-secrets=true but the following "
                    + "prod-sensitive credential(s) are blank: " + String.join(", ", missing)
                    + ". Refusing to start — supply them via the environment "
                    + "(k8s + External-Secrets-Operator + Vault in production). "
                    + "Never ship a default/guessable value (NIST IA-5; OWASP ASVS 6.2.2).");
        }
        log.info("[startup-secret-validator] managed-secrets check passed "
                + "— client-secret + DB password are set");
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
