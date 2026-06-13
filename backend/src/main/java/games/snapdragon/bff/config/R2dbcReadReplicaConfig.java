package games.snapdragon.bff.config;

import games.snapdragon.bff.util.ConnectionUrls;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;
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
@Conditional(R2dbcReadReplicaConfig.NonEmptyReplicaUrl.class)
public class R2dbcReadReplicaConfig {

    /**
     * Custom condition that matches only when {@code app.r2dbc.replica.url}
     * is present AND non-empty. Spring's stock {@code @ConditionalOnProperty}
     * treats an empty value as "matching" (only literal "false" is treated
     * as non-match) — but our application.yml defines the property with an
     * empty default so the env var can override it, which means an empty
     * value is the dev / test mode and must NOT activate this config.
     */
    static final class NonEmptyReplicaUrl implements org.springframework.context.annotation.Condition {
        @Override
        public boolean matches(ConditionContext ctx, AnnotatedTypeMetadata md) {
            String url = ctx.getEnvironment().getProperty("app.r2dbc.replica.url");
            return url != null && !url.isBlank();
        }
    }

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
        // Mask credentials embedded in the URL (user:pass@ or ?password=) so
        // the route-access/replica DB password can't land in pod logs (CWE-532).
        log.info("[r2dbc-replica] connecting → {}", ConnectionUrls.mask(replicaUrl));
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
