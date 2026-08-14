package games.hugmesh.rpgbuilder.config;

import io.r2dbc.spi.ConnectionFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;

/**
 * Exposes the {@link TransactionalOperator} {@code RpgBuildingService} uses to wrap its
 * multi-write chains (create/update/delete) in one reactive transaction.
 *
 * <p>This module also carries {@code spring-boot-starter-jdbc} (for Liquibase's blocking
 * migration {@code DataSource} — the accepted idiom, not a bug), which autoconfigures a
 * competing {@code DataSourceTransactionManager} alongside R2DBC's own
 * {@code R2dbcTransactionManager}. Defining the {@link TransactionalOperator} bean here,
 * constructed directly off the {@link ConnectionFactory} rather than an injected
 * transaction-manager bean, short-circuits both Boot autoconfigurations'
 * {@code @ConditionalOnMissingBean} checks so exactly one {@code TransactionManager} bean
 * ever exists and a bare {@code @Transactional}'s unqualified bean lookup can never see two.
 */
@Configuration(proxyBeanMethods = false)
public class RpgBuilderTxConfig {

    @Bean
    public TransactionalOperator transactionalOperator(ConnectionFactory connectionFactory) {
        return TransactionalOperator.create(new R2dbcTransactionManager(connectionFactory));
    }
}
