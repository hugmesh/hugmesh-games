package games.snapdragon.autoconfigure;

import games.snapdragon.bff.service.CassandraSyncService;
import games.snapdragon.bff.service.DistributedRateLimitService;
import games.snapdragon.bff.service.PositionCacheService;
import games.snapdragon.bff.service.RevocationListService;
import games.snapdragon.bff.service.SessionService;
import games.snapdragon.bff.service.realtime.RealtimeTruthStore;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.cassandra.CassandraReactiveDataAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.cassandra.ReactiveSession;
import org.springframework.data.cassandra.core.ReactiveCassandraTemplate;
import org.springframework.data.cassandra.core.cql.ReactiveCqlTemplate;

/**
 * Registers every Cassandra-backed optional service as a true
 * auto-configuration so {@code @ConditionalOnBean(ReactiveCassandraTemplate
 * .class)} is evaluated AFTER Spring Boot's Cassandra auto-configuration has
 * (or hasn't) contributed the template beans.
 *
 * <p><strong>Why this class exists:</strong> these services used to be
 * component-scanned {@code @Service}s carrying the bean condition directly.
 * That never worked: component-scan registration happens before
 * auto-configuration, so the template bean definition did not exist yet and
 * the condition was deterministically FALSE — every one of these services
 * (the decision-017 cross-pod position cache, the pod heartbeat sync, the
 * cluster-wide rate limit, the BFF-side revocation list, and the realtime
 * truth store) was silently absent from every real deployment while the
 * unit suites passed against hand-injected instances. Spring's own javadoc
 * warns that {@code @ConditionalOnBean} is only safe on auto-configuration
 * classes; this class is that auto-configuration, registered via
 * {@code META-INF/spring/org.springframework.boot.autoconfigure
 * .AutoConfiguration.imports}.
 *
 * <p>This package is deliberately OUTSIDE the {@code games.snapdragon.bff}
 * component-scan root — a scanned {@code @Configuration} would be processed
 * with the same too-early condition evaluation that broke the originals.
 *
 * <p>Behaviour is otherwise unchanged: each bean keeps its original
 * {@code app.*.enabled} property gate (all default-on), consumers keep
 * injecting {@code ObjectProvider<...>} and null-checking, and a deployment
 * without the Cassandra starter/template still boots with every one of
 * these absent.
 */
@AutoConfiguration(after = CassandraReactiveDataAutoConfiguration.class)
public class CassandraBackedServicesAutoConfiguration {

    /**
     * Spring Boot auto-configures {@link ReactiveCassandraTemplate} and a
     * {@link ReactiveSession}, but no {@link ReactiveCqlTemplate} — every
     * service below that injects one would fail to materialise. (This too
     * went unnoticed while the services were never instantiated.)
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(ReactiveSession.class)
    ReactiveCqlTemplate reactiveCqlTemplate(ReactiveSession session) {
        return new ReactiveCqlTemplate(session);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(ReactiveCassandraTemplate.class)
    @ConditionalOnProperty(name = "app.position-cache.enabled", havingValue = "true", matchIfMissing = true)
    PositionCacheService positionCacheService(ReactiveCassandraTemplate cassandra,
                                              ReactiveCqlTemplate cql) {
        return new PositionCacheService(cassandra, cql);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(ReactiveCassandraTemplate.class)
    @ConditionalOnProperty(name = "app.cassandra-sync.enabled", havingValue = "true", matchIfMissing = true)
    CassandraSyncService cassandraSyncService(SessionService sessionService,
                                              ReactiveCassandraTemplate cassandra,
                                              ReactiveCqlTemplate cql) {
        return new CassandraSyncService(sessionService, cassandra, cql);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(ReactiveCqlTemplate.class)
    @ConditionalOnProperty(name = "app.rate-limit.distributed.enabled", havingValue = "true", matchIfMissing = true)
    DistributedRateLimitService distributedRateLimitService(ReactiveCqlTemplate cql) {
        return new DistributedRateLimitService(cql);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(ReactiveCqlTemplate.class)
    @ConditionalOnProperty(name = "app.revocation.enabled", havingValue = "true", matchIfMissing = true)
    RevocationListService revocationListService(ReactiveCqlTemplate cql) {
        return new RevocationListService(cql);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(ReactiveCassandraTemplate.class)
    @ConditionalOnProperty(name = "app.realtime.truth-store.enabled", havingValue = "true", matchIfMissing = true)
    RealtimeTruthStore realtimeTruthStore(ReactiveCassandraTemplate cassandra,
                                          ReactiveCqlTemplate cql) {
        return new RealtimeTruthStore(cassandra, cql);
    }
}
