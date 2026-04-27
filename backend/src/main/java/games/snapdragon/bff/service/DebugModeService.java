package games.snapdragon.bff.service;

import games.snapdragon.bff.model.SystemSetting;
import games.snapdragon.bff.repository.SystemSettingRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

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

    private final SystemSettingRepository repo;
    private final AtomicBoolean wasmConsoleEnabled = new AtomicBoolean(false);

    public DebugModeService(SystemSettingRepository repo) {
        this.repo = repo;
    }

    @PostConstruct
    void hydrate() {
        repo.findById(KEY_WASM_CONSOLE)
                .doOnNext(s -> {
                    boolean enabled = "true".equalsIgnoreCase(s.getValue());
                    wasmConsoleEnabled.set(enabled);
                    log.info("Debug mode hydrated: {} = {}", KEY_WASM_CONSOLE, enabled);
                })
                .subscribe();
    }

    /** Hot-path read used by DiagnosticsController. */
    public boolean isWasmConsoleEnabled() {
        return wasmConsoleEnabled.get();
    }

    /**
     * Admin-only flip. Persists to Postgres and updates the in-memory
     * mirror so subsequent diagnostics checks see the new value
     * immediately on this pod. Cross-pod fan-out happens via PgNotify
     * in a future enhancement; for now an admin flipping the flag
     * propagates to other pods on the next service restart, or via a
     * direct admin POST per pod.
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
                .thenReturn(enabled);
    }
}
