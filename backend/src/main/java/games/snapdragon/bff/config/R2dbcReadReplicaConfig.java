package games.snapdragon.bff.config;

import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.r2dbc.core.DatabaseClient;

/**
 * Postgres read replica routing (F3, decision 022). Adds a second
 * {@link ConnectionFactory} bean qualified as {@code "replica"} so
 * services that read CATALOG data (games / chapters / federation
 * modules) can opt into the replica without breaking the existing
 * Spring Data auto-wired primary.
 *
 * <p>Pattern at the call site:
 *
 * <pre>
 *   // Default — hits the primary (write-capable).
 *   private final R2dbcEntityTemplate template;
 *
 *   // Catalog read path — hits the replica.
 *   private final R2dbcEntityTemplate replicaTemplate;
 *
 *   public CatalogService(R2dbcEntityTemplate template,
 *                         @Qualifier("replica") R2dbcEntityTemplate replicaTemplate) {
 *       this.template        = template;
 *       this.replicaTemplate = replicaTemplate;
 *   }
 * </pre>
 *
 * <p>Fallback behaviour: when {@code app.r2dbc.replica.url} is empty
 * (the default), the {@code "replica"} qualifier resolves to the
 * SAME {@link ConnectionFactory} as {@code @Primary} — so call sites
 * with the qualifier wired now will transparently keep working in
 * single-DB deployments. Provisioning the replica is a config-only
 * flip once the streaming-replication container is up.
 *
 * <p>The primary {@link ConnectionFactory} is still built by Spring
 * Boot's auto-config from {@code spring.r2dbc.*}; this class only
 * adds the qualified secondary beans + lookups.
 */
@Configuration
public class R2dbcReadReplicaConfig {

    private static final Logger log = LoggerFactory.getLogger(R2dbcReadReplicaConfig.class);

    /**
     * Replica {@link ConnectionFactory}. When the replica URL is blank
     * we return the primary itself — call sites with {@code @Qualifier
     * ("replica")} resolve to the primary CF and code keeps working
     * without a replica deployed.
     */
    @Bean
    @Qualifier("replica")
    public ConnectionFactory replicaConnectionFactory(
            // Spring auto-injects the @Primary bean (built by Spring Boot's
            // auto-config from spring.r2dbc.*); no explicit @Primary needed
            // on the parameter — that annotation only marks bean DEFINITIONS,
            // not bean LOOKUPS.
            ConnectionFactory primary,
            @Value("${app.r2dbc.replica.url:}")      String replicaUrl,
            @Value("${app.r2dbc.replica.username:}") String replicaUser,
            @Value("${app.r2dbc.replica.password:}") String replicaPassword) {
        if (replicaUrl == null || replicaUrl.isBlank()) {
            log.info("[r2dbc-replica] not configured — qualifier(\"replica\") aliases the primary");
            return primary;
        }
        log.info("[r2dbc-replica] connecting → {}", replicaUrl);
        ConnectionFactoryOptions.Builder opts = ConnectionFactoryOptions.parse(replicaUrl).mutate();
        if (replicaUser != null && !replicaUser.isBlank()) {
            opts.option(ConnectionFactoryOptions.USER,     replicaUser);
        }
        if (replicaPassword != null && !replicaPassword.isBlank()) {
            opts.option(ConnectionFactoryOptions.PASSWORD, replicaPassword);
        }
        return io.r2dbc.spi.ConnectionFactories.get(opts.build());
    }

    @Bean
    @Qualifier("replica")
    public R2dbcEntityTemplate replicaEntityTemplate(
            @Qualifier("replica") ConnectionFactory replicaConnectionFactory) {
        return new R2dbcEntityTemplate(replicaConnectionFactory);
    }

    @Bean
    @Qualifier("replica")
    public DatabaseClient replicaDatabaseClient(
            @Qualifier("replica") ConnectionFactory replicaConnectionFactory) {
        return DatabaseClient.create(replicaConnectionFactory);
    }

    /**
     * Explicit primary {@link R2dbcEntityTemplate}. Required because the
     * replica bean above is also a {@code R2dbcEntityTemplate}, which
     * trips Spring Boot's
     * {@link org.springframework.boot.autoconfigure.data.r2dbc.R2dbcDataAutoConfiguration}
     * {@code @ConditionalOnMissingBean(R2dbcEntityTemplate.class)} —
     * the framework's default {@code r2dbcEntityTemplate} bean is then
     * never created, and Spring Data repositories fail to wire with
     * "bean named 'r2dbcEntityTemplate' could not be found". We define
     * it ourselves, mark it {@code @Primary}, and keep the bean name
     * matching Spring's so {@code @Autowired R2dbcEntityTemplate}
     * resolves without any qualifier at the injection site.
     */
    @Bean(name = "r2dbcEntityTemplate")
    @Primary
    public R2dbcEntityTemplate r2dbcEntityTemplate(ConnectionFactory primary) {
        return new R2dbcEntityTemplate(primary);
    }

    /**
     * Explicit primary {@link DatabaseClient}. Same reasoning as
     * {@link #r2dbcEntityTemplate(ConnectionFactory)} — Spring Boot's
     * R2dbcAutoConfiguration provides {@code databaseClient} behind a
     * {@code @ConditionalOnMissingBean} gate, which our replica
     * DatabaseClient trips.
     */
    @Bean(name = "databaseClient")
    @Primary
    public DatabaseClient databaseClient(ConnectionFactory primary) {
        return DatabaseClient.create(primary);
    }
}
