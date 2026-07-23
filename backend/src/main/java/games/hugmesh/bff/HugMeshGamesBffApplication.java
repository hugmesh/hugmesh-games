package games.hugmesh.bff;

import games.hugmesh.bff.aot.HugMeshRuntimeHints;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Scheduling enabled here so {@link games.hugmesh.bff.service.CassandraSyncService}
 * can run its daily Postgres → Cassandra refresh, and so {@code GameTimeService}
 * can keep ticking the in-game clock.
 *
 * <p>{@link HugMeshRuntimeHints} is registered for GraalVM native-image
 * builds — it fills in the reflection / resource hints the Spring Boot AOT
 * processor can't infer on its own (DTO record reflection for Jackson, the
 * Liquibase XML changelog files). Has no effect on the JVM build.
 */
@SpringBootApplication
@EnableScheduling
@ImportRuntimeHints(HugMeshRuntimeHints.class)
public class HugMeshGamesBffApplication {
    public static void main(String[] args) {
        SpringApplication.run(HugMeshGamesBffApplication.class, args);
    }
}
