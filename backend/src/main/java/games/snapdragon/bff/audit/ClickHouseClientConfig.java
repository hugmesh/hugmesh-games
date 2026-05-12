package games.snapdragon.bff.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Base64;

/**
 * Wires a WebClient against the ClickHouse HTTP interface (port 8123 by
 * default) for read paths that don't fit a Kafka producer — primarily
 * {@link PlayerStateRecoveryService}, which queries
 * {@code snapdragon.player_state_current FINAL} for cross-pod player-
 * state recovery (decision 021).
 *
 * <p>Why HTTP rather than the JDBC driver:
 * <ul>
 *   <li>WebFlux pipeline; everything else in the BFF is reactive.
 *       The JDBC driver wants a blocking pool + a {@code Schedulers.boundedElastic()}
 *       wrap; HTTP via WebClient is non-blocking out of the box.</li>
 *   <li>One less dependency. The ClickHouse HTTP API is stable + simple
 *       — POST a SQL string, get JSON / TSV / CSV back depending on
 *       the {@code default_format} query parameter.</li>
 *   <li>Same `Authorization: Basic` shape Casdoor + Cassandra use, so
 *       credential rotation is uniform.</li>
 * </ul>
 *
 * <p>Bean gated by {@code app.clickhouse.enabled} (default {@code false})
 * mirroring the {@code app.kafka.enabled} pattern. Vanilla compose-up
 * doesn't bring ClickHouse up, so the bean is absent.
 */
@Configuration
@ConditionalOnProperty(name = "app.clickhouse.enabled", havingValue = "true")
public class ClickHouseClientConfig {

    private static final Logger log = LoggerFactory.getLogger(ClickHouseClientConfig.class);

    @Bean
    public WebClient clickHouseWebClient(
            @Value("${app.clickhouse.endpoint:http://clickhouse:8123}") String endpoint,
            @Value("${app.clickhouse.user:default}")     String user,
            @Value("${app.clickhouse.password:}")        String password,
            @Value("${app.clickhouse.database:snapdragon}") String database) {
        log.info("[clickhouse] WebClient wired → endpoint={} user={} database={}",
                endpoint, user, database);
        String basic = "Basic " + Base64.getEncoder()
                .encodeToString((user + ":" + password).getBytes());
        return WebClient.builder()
                .baseUrl(endpoint)
                .defaultHeader(HttpHeaders.AUTHORIZATION, basic)
                .defaultHeader("X-ClickHouse-Database", database)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_PLAIN_VALUE)
                .defaultHeader(HttpHeaders.ACCEPT, "application/json")
                .build();
    }
}
