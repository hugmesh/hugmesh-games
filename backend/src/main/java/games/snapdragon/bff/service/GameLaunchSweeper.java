package games.snapdragon.bff.service;

import games.snapdragon.bff.repository.GameLaunchRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Bulk-terminates {@code game_launches} rows whose {@code expires_at} has
 * passed but that were never explicitly terminated (player closed the tab
 * without logging out, BFF crashed mid-launch, etc.). Without this the
 * table grows unbounded — every game card click adds a row.
 *
 * <p>Schedule: every 5 minutes. Adjust via
 * {@code app.launches.sweep-fixed-rate-ms} once we want to tune cadence
 * differently between dev / prod.
 */
@Service
public class GameLaunchSweeper {

    private static final Logger log = LoggerFactory.getLogger(GameLaunchSweeper.class);

    private final GameLaunchRepository launches;

    public GameLaunchSweeper(GameLaunchRepository launches) {
        this.launches = launches;
    }

    @Scheduled(fixedRateString = "${app.launches.sweep-fixed-rate-ms:300000}",
               initialDelayString = "${app.launches.sweep-initial-delay-ms:60000}")
    public void sweep() {
        Instant cutoff = Instant.now();
        launches.sweepExpired(cutoff)
                .subscribe(
                    swept -> {
                        if (swept != null && swept > 0) {
                            log.info("[launch-sweeper] terminated {} expired launches (cutoff={})",
                                    swept, cutoff);
                        }
                    },
                    err -> log.warn("[launch-sweeper] sweep failed", err)
                );
    }
}
