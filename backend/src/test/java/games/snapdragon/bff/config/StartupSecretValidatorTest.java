package games.snapdragon.bff.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit coverage for the prod-credential fail-fast guard (NIST IA-5 /
 * SC-12; OWASP ASVS 6.2.2). Verifies the dev/compose path stays lenient
 * and the prod path refuses to boot on a blank credential.
 */
class StartupSecretValidatorTest {

    @Test
    void devPathSkipsValidationEvenWhenSecretsBlank() {
        // require-managed-secrets=false (the default: local dev / compose /
        // tests) — blank secrets must NOT abort startup.
        var v = new StartupSecretValidator(false, "", "");
        assertThatCode(v::validate).doesNotThrowAnyException();
    }

    @Test
    void prodPathFailsWhenClientSecretBlank() {
        var v = new StartupSecretValidator(true, "  ", "db-pass");
        assertThatThrownBy(v::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("casdoor.client-secret");
    }

    @Test
    void prodPathFailsWhenDbPasswordBlank() {
        var v = new StartupSecretValidator(true, "real-secret", null);
        assertThatThrownBy(v::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("database password");
    }

    @Test
    void prodPathReportsAllMissingCredentialsAtOnce() {
        var v = new StartupSecretValidator(true, "", "");
        assertThatThrownBy(v::validate)
                .isInstanceOf(IllegalStateException.class)
                .satisfies(ex -> {
                    assertThat(ex.getMessage()).contains("casdoor.client-secret");
                    assertThat(ex.getMessage()).contains("database password");
                });
    }

    @Test
    void prodPathPassesWhenAllSecretsPresent() {
        var v = new StartupSecretValidator(true, "real-secret", "real-db-pass");
        assertThatCode(v::validate).doesNotThrowAnyException();
    }
}
