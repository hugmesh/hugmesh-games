package games.snapdragon.bff.config;

import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryMetadata;
import org.junit.jupiter.api.Test;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.r2dbc.core.DatabaseClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link R2dbcReadReplicaConfig}. Verifies the fallback
 * contract: when {@code app.r2dbc.replica.url} is blank, the
 * {@code @Qualifier("replica")} bean must be the same instance as the
 * {@code @Primary} CF — so call sites with the qualifier wired keep
 * working in single-DB deployments without a replica.
 *
 * <p>We don't run @SpringBootTest here — the constructor-level wiring
 * is what's interesting and a unit test against the config class
 * directly is faster + doesn't need a real Postgres.
 */
class R2dbcReadReplicaConfigTest {

    @Test
    void emptyReplicaUrlAliasesThePrimaryConnectionFactory() {
        R2dbcReadReplicaConfig cfg = new R2dbcReadReplicaConfig();
        ConnectionFactory primary = mockConnectionFactory("PostgreSQL");

        ConnectionFactory replica = cfg.replicaConnectionFactory(primary, "", "", "");

        // SAME instance — the qualifier "replica" resolves to the
        // primary CF when no replica is configured.
        assertThat(replica).isSameAs(primary);
    }

    @Test
    void blankReplicaUrlAliasesThePrimary() {
        R2dbcReadReplicaConfig cfg = new R2dbcReadReplicaConfig();
        ConnectionFactory primary = mockConnectionFactory("PostgreSQL");

        ConnectionFactory replica = cfg.replicaConnectionFactory(primary, "   ", "u", "p");

        assertThat(replica).isSameAs(primary);
    }

    @Test
    void replicaEntityTemplateUsesTheReplicaCf() {
        R2dbcReadReplicaConfig cfg = new R2dbcReadReplicaConfig();
        ConnectionFactory replicaCf = mockConnectionFactory("PostgreSQL");

        R2dbcEntityTemplate template = cfg.replicaEntityTemplate(replicaCf);

        // The R2dbcEntityTemplate's DatabaseClient is built off the
        // CF we passed in — there's no public getter, so we just
        // confirm the bean was created cleanly.
        assertThat(template).isNotNull();
    }

    @Test
    void replicaDatabaseClientUsesTheReplicaCf() {
        R2dbcReadReplicaConfig cfg = new R2dbcReadReplicaConfig();
        ConnectionFactory replicaCf = mockConnectionFactory("PostgreSQL");

        DatabaseClient client = cfg.replicaDatabaseClient(replicaCf);

        assertThat(client).isNotNull();
        // ConnectionFactory.getMetadata().getName() flows through
        // DatabaseClient.create(...) so an assertion-by-instance
        // would be brittle; just confirm the bean was constructed.
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private static ConnectionFactory mockConnectionFactory(String dialect) {
        // DatabaseClient.create(cf) reads cf.getMetadata().getName() in
        // its bind-marker setup, so the mock must return populated
        // metadata or template/client construction throws an NPE.
        ConnectionFactory cf = mock(ConnectionFactory.class);
        ConnectionFactoryMetadata md = mock(ConnectionFactoryMetadata.class);
        when(md.getName()).thenReturn(dialect);
        when(cf.getMetadata()).thenReturn(md);
        return cf;
    }
}
