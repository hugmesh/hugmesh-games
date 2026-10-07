# How to toggle WASM-console debug mode

The pipeline that sends iframe console errors back to the BFF is **off by default**. Admins switch it on briefly to triage an iframe issue, then off again.

## Prerequisites

- The Casdoor `admin` role (the BFF sees it as `ROLE_ADMIN`).

## Read the current state

```bash
ADMIN_JWT=...

curl -sS -H "Authorization: Bearer $ADMIN_JWT" \
  http://localhost:8080/api/admin/debug-mode
# → { "wasmConsoleEnabled": false }
```

## Enable

```bash
curl -sS -X PUT -H "Authorization: Bearer $ADMIN_JWT" \
  -H 'Content-Type: application/json' \
  -d '{"enabled":true}' \
  http://localhost:8080/api/admin/debug-mode/wasm-console
# → { "wasmConsoleEnabled": true }
```

The flag is stored in `system_settings` (Liquibase 031) and mirrored into the `DebugModeService.AtomicBoolean`. Diagnostic ingests then pass the gate without a database round-trip per emit.

## Confirm captures are flowing

With debug mode on, an iframe `console.error` lands on the SSE `wasm-console` channel:

```bash
# Terminal 1 — admin watches the SSE channel:
curl -N -H "Authorization: Bearer $ADMIN_JWT" \
  http://localhost:8080/api/game/stream

# Terminal 2 — trigger an iframe error in any open game tab. Watch for
# event: wasm-console
# data: {"level":"error","message":"...",...}
```

## Disable

```bash
curl -sS -X PUT -H "Authorization: Bearer $ADMIN_JWT" \
  -H 'Content-Type: application/json' \
  -d '{"enabled":false}' \
  http://localhost:8080/api/admin/debug-mode/wasm-console
```

While the gate is off, `POST /api/diagnostics/wasm-console` returns *403*, even for admin callers. The frontend's `WasmConsoleForwarderService` reads the same flag and skips the request, so non-admin players never attempt the POST.

## Why it's off by default

Iframe console output leaks game-internal detail: file paths, partial state dumps, stack traces. Always-on capture sends every player's iframe state to the BFF and fans it out to admin devtools subscribers. The safe default is closed; admins open it for a triage window. See [decision 012](../decisions/012-debug-mode-gate.md).

## Cross-pod note

The flag is mirrored per pod (an `AtomicBoolean` in `DebugModeService`). Each pod re-reads `system_settings` every 5 seconds (`REHYDRATE_INTERVAL`), so a toggle on pod A reaches pod B within about 5 s. The toggle also fires `pg_notify('debug_mode_changed')`, but no pod listens for it yet; propagation relies on the 5 s re-read. No restart is needed.

## See also

- [Decision 012 — Admin-only debug-mode gate](../decisions/012-debug-mode-gate.md)
- [DiagnosticsController](../architecture/05-api-reference.md#diagnosticscontroller--apidiagnostics)
- [Maintainer runbook](../guides/maintainer-runbook.md)
