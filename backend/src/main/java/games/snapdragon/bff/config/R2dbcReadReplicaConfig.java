package games.snapdragon.bff.config;

import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
 * <p>This entire configuration is gated by
 * {@code @ConditionalOnProperty(name = "app.r2dbc.replica.url")} —
 * so it's only loaded when a replica URL is actually configured.
 * The dev / test default (replica URL empty) keeps Spring Boot's
 * auto-configured {@code connectionFactory} / {@code r2dbcEntityTemplate}
 * / {@code databaseClient} beans intact and no @Qualifier("replica")
 * bean exists. Per-service consumers of the replica qualifier must
 * therefore also be conditional on the same property (or accept a
 * compile-time guarantee that this config is active in their target
 * deployment).
 *
 * <p>Why conditional, not unconditional with fallback: a same-type
 * {@link ConnectionFactory} bean trips Spring Boot's
 * {@code @ConditionalOnMissingBean(ConnectionFactory.class)} gate
 * on its auto-config — the framework's own primary CF then never
 * gets created, and either Spring Data repositories fail to wire
 * or our replica CF becomes the only candidate for its own
 * {@code primary} parameter (cycle).
 */
@Configuration
@ConditionalOnProperty(name = "app.r2dbc.replica.url")
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

}
