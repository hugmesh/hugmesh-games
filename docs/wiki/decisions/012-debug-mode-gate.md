# 012 — Admin-only debug-mode gate on WASM console capture

**Status:** Adopted (Phase 1 of the multiplayer overhaul).

## Decision

`POST /api/diagnostics/wasm-console` requires **both** of these. If either fails, the BFF answers 403:

1. The caller's JWT carries `ROLE_ADMIN`.
2. The `wasm_console_debug_enabled` flag in `system_settings` is `true`.

An admin flips the flag at runtime with `PUT /api/admin/debug-mode/wasm-console`. It lives in Postgres, so it survives restarts. The default is **OFF**.

## Why

The old behaviour took anonymous WASM console captures, so a crash during page bootstrap (before the JWT lands in `sessionStorage`) was still forwarded. That suited a small dev portal. At scale it causes three problems:

- A player's iframe console is not harmless. File paths, partial state dumps and stack traces leak game internals. Always-on capture sends every player's iframe state to the BFF.
- The `wasm-console` SSE channel fans out to admin devtools subscribers. Anonymous, always-on capture gives any watching admin a live feed of every player's iframe noise.
- It fills the audit log with low-signal lines.

Now the surface is closed by default. An admin turns capture on briefly while triaging an iframe issue, then off again.

## How to apply

- `DebugModeService` mirrors the Postgres flag into an `AtomicBoolean`. The hot-path read in `DiagnosticsController` is then a constant-time volatile read, not a DB round-trip per emit.
- `DiagnosticsController` carries `@PreAuthorize("hasRole('ADMIN')")` plus an explicit check on `debugMode.isWasmConsoleEnabled()`.
- The frontend `WasmConsoleForwarderService` mirrors the gate. It reads `/api/admin/debug-mode` on start and re-fetches through `refreshDebugFlag()`. When the gate is off, or the caller is not an admin, the local console mirror still fires and only the BFF POST is suppressed.

## Trade-offs accepted

- **Bootstrap crashes are no longer captured by default.** The original concern is real. The workaround: an admin enables debug mode briefly while reproducing.
- **Per-pod toggle drift.** `DebugModeService` mirrors the flag in memory per pod. Each pod re-reads `system_settings` every 5 s (`REHYDRATE_INTERVAL`), so a toggle on pod A reaches pod B within about 5 s. Every toggle also fires `pg_notify("debug_mode_changed")`, but nothing listens for it yet; immediate propagation is deferred.
- **No per-game / per-session scoping.** It is one global flag. The surface is small today; scoping by game can come later.

## Reference

- [DiagnosticsController](../architecture/05-api-reference.md#diagnosticscontroller--apidiagnostics)
- Liquibase 031 ([`031-system-settings.xml`](../../../backend/src/main/resources/db/changelog/031-system-settings.xml)).
- [howto/toggle-wasm-debug-mode.md](../howto/toggle-wasm-debug-mode.md).
