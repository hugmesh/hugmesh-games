package games.hugmesh.bff.audit;

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
 * Configures a reactive {@link WebClient} targeting the ClickHouse HTTP interface
 * for the decision 021 cross-pod state recovery path.
 *
 * <h2>Responsibility</h2>
 *
 * <p>Provides the {@code clickHouseWebClient} bean consumed by
 * {@link PlayerStateRecoveryService}, which queries
 * {@code snapdragon.player_state_current FINAL} to recover a player's most-recent
 * movement state after a pod restart.
 *
 * <h2>Why HTTP rather than the JDBC driver</h2>
 *
 * <ul>
 *   <li><b>Non-blocking:</b> The BFF is a WebFlux application. The ClickHouse JDBC
 *       driver requires a blocking connection pool and a {@code Schedulers.boundedElastic()}
 *       wrapper; HTTP via {@code WebClient} is non-blocking out of the box and composes
 *       naturally with the rest of the reactive pipeline.</li>
 *   <li><b>Minimal dependency footprint:</b> The ClickHouse HTTP API is stable and
 *       straightforward — POST a SQL string, get JSON / TSV / CSV back depending on
 *       the {@code FORMAT} clause. No additional JDBC driver jar is required.</li>
 *   <li><b>Uniform credential shape:</b> {@code Authorization: Basic} matches the
 *       pattern used by Casdoor and Cassandra credential rotation, so ClickHouse
 *       credentials are managed the same way.</li>
 * </ul>
 *
 * <h2>Default headers</h2>
 *
 * <ul>
 *   <li>{@code Authorization: Basic …} — base64-encoded {@code user:password}</li>
 *   <li>{@code X-ClickHouse-Database} — scopes all queries to the configured database
 *       so SQL can use unqualified table names</li>
 *   <li>{@code Content-Type: text/plain} — ClickHouse reads the SQL from the request
 *       body as plain text</li>
 *   <li>{@code Accept: application/json} — request JSON-format responses</li>
 * </ul>
 *
 * <h2>Availability</h2>
 *
 * <p>Bean gated by {@code app.clickhouse.enabled} (default {@code false}), mirroring the
 * {@code app.kafka.enabled} pattern. Vanilla compose-up does not bring ClickHouse up, so
 * the bean is absent. Callers inject as {@code Optional<PlayerStateRecoveryService>} and
 * fall back to the Cassandra / Postgres chain when the bean is absent.
 *
 * @see PlayerStateRecoveryService
 */
@Configuration
@ConditionalOnProperty(name = "app.clickhouse.enabled", havingValue = "true")
public class ClickHouseClientConfig {

    private static final Logger log = LoggerFactory.getLogger(ClickHouseClientConfig.class);

    // -------------------------------------------------------------------------
    // Bean
    // -------------------------------------------------------------------------

    /**
     * Creates a {@link WebClient} pre-configured for the ClickHouse HTTP interface.
     *
     * <p>All queries sent through this client inherit the base URL, database scope,
     * authentication header, and content-type defaults declared here. Individual
     * requests only need to supply the SQL body and any {@code param_*} query parameters
     * for ClickHouse's parameterised query substitution.
     *
     * @param endpoint the ClickHouse HTTP endpoint base URL; defaults to
     *                 {@code http://clickhouse:8123}
     * @param user     the ClickHouse user; defaults to {@code default}
     * @param password the ClickHouse password; defaults to empty string (unauthenticated
     *                 dev setup)
     * @param database the ClickHouse database to scope queries to; defaults to
     *                 {@code hugmesh}
     * @return a configured {@link WebClient} ready to POST SQL queries against ClickHouse
     */
    @Bean
    public WebClient clickHouseWebClient(
            @Value("${app.clickhouse.endpoint:http://clickhouse:8123}") String endpoint,
            @Value("${app.clickhouse.user:default}")     String user,
            @Value("${app.clickhouse.password:}")        String password,
            @Value("${app.clickhouse.database:snapdragon}") String database) {
        log.info("[clickhouse] WebClient wired → endpoint={} user={} database={}",
                endpoint, user, database);
        // Build the Basic auth header value once at construction time so every request
        // reuses the pre-encoded string without re-encoding on each call.
        String basic = "Basic " + Base64.getEncoder()
                .encodeToString((user + ":" + password).getBytes());
        return WebClient.builder()
                .baseUrl(endpoint)
                .defaultHeader(HttpHeaders.AUTHORIZATION, basic)
                // X-ClickHouse-Database scopes all queries to the configured database,
                // allowing SQL to reference tables without a fully-qualified prefix.
                .defaultHeader("X-ClickHouse-Database", database)
                // ClickHouse expects the SQL in the request body as plain text.
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_PLAIN_VALUE)
                // Request JSON-format responses so the caller can parse with Jackson.
                .defaultHeader(HttpHeaders.ACCEPT, "application/json")
                .build();
    }
}
