package games.hugmesh.bff.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

/**
 * Explicitly creates a JDBC DataSource for Liquibase.
 *
 * Spring Boot 3 skips DataSourceAutoConfiguration when an R2DBC ConnectionFactory
 * bean is present, so Liquibase would silently have no DataSource without this.
 */
@Configuration
public class JdbcDataSourceConfig {

    @Bean
    @Primary
    DataSource dataSource(
            @Value("${spring.datasource.url}") String url,
            @Value("${spring.datasource.username}") String username,
            @Value("${spring.datasource.password}") String password) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(url);
        ds.setUsername(username);
        ds.setPassword(password);
        ds.setMaximumPoolSize(3);
        return ds;
    }
}
