# 014 — All Godot networking flows through the HostBridge

**Status:** Adopted.

## Decision

Game-side GDScript never calls `HTTPRequest` to talk to the BFF. Every API call goes through:

```
Game script → BridgeApiClient.http_get/post/put/delete(...)     // Rust GodotClass
  → HostBridge.request_api(method, path, body_json)            // Rust GodotClass
  → api_request_egress signal                                  // HostBridge → BridgeGlue
  → window.parent.postMessage('SD_API_REQUEST', payload)       // BridgeGlue → host (JavaScriptBridge)
  → host shell ApiBridgeService                                // Angular
  → HttpClient.request(...)                                    // auth interceptor
  → BFF                                                        // /api/...
  ← HttpResponse
  ← event.source.postMessage('SD_API_RESPONSE', payload)       // host → iframe
  ← HostBridge.dispatch_api_response                           // BridgeGlue → HostBridge
  ← api_response_received signal                               // HostBridge → BridgeApiClient
  ← BridgeApiClient's pending-callback map fires on_done        // back into game script
```

`BridgeApiClient`, `BridgeGlue`, and `HostBridge` are all native Rust GodotClasses registered by the `snapdragon-bridge` GDExtension (`extensions/snapdragon-bridge/src/`) — none of this is GDScript; game code (`.gd` scripts) only ever calls the `BridgeApiClient` node's methods. This used to be a GDScript addon layer; it was rewritten in Rust (see [`bridge_api_client.rs`](../../../extensions/snapdragon-bridge/src/bridge_api_client.rs)'s own "Architecture (corrected)" doc comment) and the class names carried over unchanged, so game-script call sites didn't need to change.

In editor / native mode (`HostBridge.is_streaming() == false`), `BridgeApiClient` falls back to a direct `HTTPRequest` against `http://localhost:8080`. The contract is the same; only the transport layer changes.

## Why

Three reasons.

1. **Single point of network egress.** Every API call carries the same auth + session context because the host shell adds them. Iframe code never touches JWTs or session ids.
2. **Per-session routing.** The host attaches `X-Session-Id` from the request payload — that's what flips the BFF's `ControlController` into per-session sink routing ([decision 010](010-per-session-sse-sinks.md)). If iframe code makes its own fetch, it can't easily attach that header without the host's `MultiplayerSessionService` state.
3. **Mockability.** Tests / replays / e2e harnesses intercept at the host-shell seam (`ApiBridgeService.spec.ts`) instead of stubbing fetch inside the WASM bundle.

## How to apply

- **Game scripts:** use `BridgeApiClient` (a Rust GodotClass from the `snapdragon-bridge` GDExtension, findable via `ClassDB.instantiate("BridgeApiClient")` or the addon's typed reference). Never `HTTPRequest`. Method names are `http_get`/`http_post`/`http_put`/`http_delete` (not `get`/`post`/… — `get` collides with `Node.get(property)`):
  ```gdscript
  var api := BridgeApiClient.new()
  add_child(api)  # ready() auto-discovers the sibling/scene-root node named "HostBridge"
  api.http_get("/api/chapters/1/scene", func(status, body):
      if status == 200: _apply_scene(body))
  ```
- **Rust HostBridge:** `request_api(method, path, body_json) -> int` + `dispatch_api_response(payload_json)` with a single `api_response_received` signal carrying the JSON payload. Request ids correlate response → callback.
- **BridgeGlue (Rust GodotClass, `bridge_glue.rs`):** subscribes to `api_request_egress` and forwards via `window.parent.postMessage('SD_API_REQUEST', ...)`. Installs a `message` listener that catches `SD_API_RESPONSE` and pumps it back into `HostBridge.dispatch_api_response`.
- **Host shell (`ApiBridgeService`):** listens for `SD_API_REQUEST` postMessages, performs the `HttpClient.request(...)` call (auth interceptor + `X-Session-Id` from the payload), and posts `SD_API_RESPONSE` back to the iframe's content window.

## Trade-offs accepted

- **One extra postMessage hop per request.** Adds ~1–2 ms of latency on the hot path. Acceptable for correctness and consistency.
- **The bridge needs to know about every request type.** Today the bridge is path-agnostic — it just forwards. If we ever need request-shape validation at the bridge layer, a wrapper schema would land in the addon's `BridgeApiClient`.
- **Editor F5 falls back to direct HTTPRequest.** That path doesn't get the auth interceptor or session context. Acceptable: editor playthroughs are dev-only and the standalone codebase isn't authenticated.

## Reference

- [Snapdragon Bridge addon README](../../../extensions/snapdragon-bridge/addons/snapdragon_bridge/README.md)
- [`bridge_api_client.rs`](../../../extensions/snapdragon-bridge/src/bridge_api_client.rs)
- [`bridge_glue.rs`](../../../extensions/snapdragon-bridge/src/bridge_glue.rs)
- [`api-bridge.service.ts`](../../../frontend/src/app/services/api-bridge.service.ts)
- [`SessionIsolationTest`](../../../backend/src/test/java/games/snapdragon/bff/service/SessionIsolationTest.java) — locks the per-session SSE isolation that this routing enables.
