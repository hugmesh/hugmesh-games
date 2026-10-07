# 012 — Admin-only debug-mode gate on WASM console capture

**Status:** Adopted (Phase 1 of the multiplayer overhaul).

## Decision

`POST /api/diagnostics/wasm-console` requires **both** of the following — either gate failing rejects with 403:

1. The caller's JWT carries `ROLE_ADMIN`.
2. The `wasm_console_debug_enabled` flag in `system_settings` is `true`.

The flag is admin-toggled at runtime via `PUT /api/admin/debug-mode/wasm-console` and persists in Postgres so it survives restarts. Default is **OFF**.

## Why

The previous behaviour accepted anonymous WASM console captures so a crash mid-page-bootstrap (before the JWT lands in `sessionStorage`) could still be forwarded. That made sense in a small dev portal but produces three problems at scale:

- Players' iframe console contents aren't a benign signal — file paths, partial state dumps, and stack traces routinely leak game-internal detail. Always-on capture sends every player's iframe state to the BFF.
- The `wasm-console` SSE channel fans out to admin devtools subscribers. Anonymous + always-on means any admin watching gets a live feed of every player's iframe noise.
- Operationally, this lights up the audit log with low-signal lines.

Closing the surface by default and giving admins a runtime toggle means: capture is off in steady state, admin enables briefly while triaging an iframe issue, admin disables again afterwards.

## How to apply

- `DebugModeService` mirrors the Postgres flag into an `AtomicBoolean` so the hot-path read in `DiagnosticsController` is a constant-time volatile read instead of a DB round-trip per emit.
- `DiagnosticsController` carries `@PreAuthorize("hasRole('ADMIN')")` plus an explicit check on `debugMode.isWasmConsoleEnabled()`.
- Frontend `WasmConsoleForwarderService` mirrors the gate: it reads `/api/admin/debug-mode` on start and re-fetches via `refreshDebugFlag()`. When the gate is off (or the caller is non-admin), the local console mirror still fires — the BFF POST is suppressed.

## Trade-offs accepted

- **Bootstrap crashes are no longer captured by default.** The original concern (a crash before JWT lands) is real; mitigation: admin enables debug mode briefly while reproducing.
- **Per-pod toggle drift.** `DebugModeService` mirrors the flag in memory per pod. Each pod re-reads `system_settings` every 5 s (`REHYDRATE_INTERVAL`), so a toggle on pod A reaches pod B within about 5 s. Every toggle also fires `pg_notify("debug_mode_changed")`, but nothing listens for it yet; immediate propagation is deferred.
- **No per-game / per-session scoping.** It's a single global flag. Future enhancement could scope by game, but the surface today is small.

## Reference

- [DiagnosticsController](../architecture/05-api-reference.md#diagnosticscontroller--apidiagnostics)
- Liquibase 031 ([`031-system-settings.xml`](../../../backend/src/main/resources/db/changelog/031-system-settings.xml)).
- [howto/toggle-wasm-debug-mode.md](../howto/toggle-wasm-debug-mode.md).
