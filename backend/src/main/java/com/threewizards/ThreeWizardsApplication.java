package com.threewizards;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Scheduling enabled here so {@link com.threewizards.service.CassandraSyncService}
 * can run its daily Postgres → Cassandra refresh, and so {@code GameTimeService}
 * can keep ticking the in-game clock.
 */
@SpringBootApplication
@EnableScheduling
public class ThreeWizardsApplication {
    public static void main(String[] args) {
        SpringApplication.run(ThreeWizardsApplication.class, args);
    }
}
