package games.snapdragon.bff.service;

import games.snapdragon.bff.model.SystemSetting;
import games.snapdragon.bff.repository.SystemSettingRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Admin-controlled runtime toggle for diagnostic ingest from the WASM
 * iframe. Default is OFF — captured browser-side console errors do NOT
 * reach the BFF unless an admin flips this on while triaging an issue.
 *
 * <p>Postgres ({@code system_settings}) is the source of truth so the
 * setting survives restarts. An in-memory {@link AtomicBoolean} mirrors
 * the latest known value so the hot-path
 * {@link DiagnosticsController#submitWasmConsole} check is a constant-time
 * volatile read instead of a DB round-trip per emit.
 *
 * <p>{@code @DependsOn("liquibase")} so the {@code @PostConstruct} initial
 * load runs against the migrated schema (Liquibase 031 creates the row).
 */
@Service
@DependsOn("liquibase")
public class DebugModeService {

    private static final Logger log = LoggerFactory.getLogger(DebugModeService.class);

    public static final String KEY_WASM_CONSOLE = "wasm_console_debug_enabled";
    public static final String CHANNEL_DEBUG_MODE = "debug_mode_changed";

    /** How often each pod re-reads {@code system_settings} to pick up
     *  flag changes made by another pod. The pg_notify publish on
     *  every toggle is for durability + an obvious wake-up signal in
     *  Postgres logs; cross-pod propagation is bounded by this
     *  interval rather than requiring a long-lived LISTEN connection. */
    private static final Duration REHYDRATE_INTERVAL = Duration.ofSeconds(5);

    private final SystemSettingRepository repo;
    private final PgNotificationService pgNotify;
    private final AtomicBoolean wasmConsoleEnabled = new AtomicBoolean(false);
    private Disposable rehydrateSub;

    public DebugModeService(SystemSettingRepository repo, PgNotificationService pgNotify) {
        this.repo     = repo;
        this.pgNotify = pgNotify;
    }

    @PostConstruct
    void hydrate() {
        // Initial load — block-and-set on the same DB call as the
        // periodic ticker, so the constructor doesn't observe a
        // not-yet-hydrated AtomicBoolean.
        loadFromDb()
                .doOnNext(enabled ->
                        log.info("Debug mode hydrated: {} = {}", KEY_WASM_CONSOLE, enabled))
                .subscribe();

        // Periodic re-hydrate so cross-pod toggles propagate within
        // REHYDRATE_INTERVAL even if no LISTEN connection is held.
        // The interval is short enough that admin-driven toggles look
        // immediate to a busy operator switching pods.
        rehydrateSub = Flux.interval(REHYDRATE_INTERVAL, REHYDRATE_INTERVAL)
                .flatMap(t -> loadFromDb())
                .doOnError(e -> log.warn("Debug mode rehydrate failed: {}", e.toString()))
                .onErrorContinue((err, evt) -> log.warn("Debug mode rehydrate continuing past error: {}", err.toString()))
                .subscribe();
    }

    @PreDestroy
    void shutdown() {
        if (rehydrateSub != null && !rehydrateSub.isDisposed()) {
            rehydrateSub.dispose();
        }
    }

    private Mono<Boolean> loadFromDb() {
        return repo.findById(KEY_WASM_CONSOLE)
                .map(s -> "true".equalsIgnoreCase(s.getValue()))
                .defaultIfEmpty(false)
                .doOnNext(wasmConsoleEnabled::set);
    }

    /** Hot-path read used by DiagnosticsController. */
    public boolean isWasmConsoleEnabled() {
        return wasmConsoleEnabled.get();
    }

    /**
     * Admin-only flip. Persists to Postgres and updates the in-memory
     * mirror so subsequent diagnostics checks see the new value
     * immediately on this pod. Other pods pick up the change on their
     * next periodic rehydrate ({@link #REHYDRATE_INTERVAL}); this
     * method also fires a {@code pg_notify("debug_mode_changed")} so
     * the change is visible in the Postgres log + any future LISTEN
     * subscriber gets an immediate event.
     */
    public Mono<Boolean> setWasmConsoleEnabled(boolean enabled, String actorCasdoorId) {
        return repo.findById(KEY_WASM_CONSOLE)
                .switchIfEmpty(Mono.fromSupplier(() -> {
                    SystemSetting s = new SystemSetting();
                    s.setKey(KEY_WASM_CONSOLE);
                    return s;
                }))
                .doOnNext(s -> {
                    s.setValue(Boolean.toString(enabled));
                    s.setUpdatedAt(Instant.now());
                    s.setUpdatedBy(actorCasdoorId);
                })
                .flatMap(repo::save)
                .doOnNext(s -> {
                    wasmConsoleEnabled.set(enabled);
                    log.info("Debug mode toggled by {}: {} -> {}", actorCasdoorId, KEY_WASM_CONSOLE, enabled);
                })
                .flatMap(s -> pgNotify.publish(CHANNEL_DEBUG_MODE,
                        String.format("{\"key\":\"%s\",\"enabled\":%s,\"actor\":\"%s\"}",
                                KEY_WASM_CONSOLE, enabled, actorCasdoorId))
                        .thenReturn(s))
                .thenReturn(enabled);
    }
}
