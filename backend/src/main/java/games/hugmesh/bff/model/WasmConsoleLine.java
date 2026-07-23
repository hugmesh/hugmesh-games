package games.hugmesh.bff.model;

import java.time.Instant;

/**
 * One forwarded line from the WASM iframe's console.
 *
 * <p>The wasm-fragment Godot bundle's index.html installs a wrapper
 * around {@code console.error} / {@code console.warn} / {@code
 * window.onerror} (and {@code window.addEventListener('unhandledrejection')})
 * that posts every captured message to its parent window via
 * {@code postMessage({type:"SD_WASM_CONSOLE", level, message, source, line, column, stack})}.
 *
 * <p>The host's home component listens for that postMessage and
 * forwards the captured payload to the BFF over
 * {@code POST /api/diagnostics/wasm-console}, which fans it out on
 * the {@code wasm-console} SSE channel for the admin devtools view.
 *
 * <p>Levels are the same vocabulary as the JS console: {@code error},
 * {@code warn}, {@code info}, {@code log}. Anything else is coerced
 * to {@code log} on ingestion.
 */
public record WasmConsoleLine(
        String level,
        String gameSlug,
        String chapterSlug,
        String message,
        String source,
        Integer line,
        Integer column,
        String stack,
        Instant occurredAt
) {
    public WasmConsoleLine withTimestamp(Instant t) {
        return new WasmConsoleLine(level, gameSlug, chapterSlug, message,
                                   source, line, column, stack, t);
    }
}
