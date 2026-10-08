# 014 — All Godot networking flows through the HostBridge

**Status:** Adopted.

## Decision

Game-side GDScript never calls `HTTPRequest` to reach the BFF. Every API call goes through:

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

`BridgeApiClient`, `BridgeGlue` and `HostBridge` are native Rust GodotClasses registered by the `hugmesh-bridge` GDExtension (`extensions/hugmesh-bridge/src/`). None of this is GDScript. Game code (`.gd` scripts) only calls the `BridgeApiClient` node's methods. This layer used to be GDScript; it was rewritten in Rust (see the "Architecture (corrected)" comment in [`bridge_api_client.rs`](../../../extensions/hugmesh-bridge/src/bridge_api_client.rs)). The class names carried over, so game-script call sites did not change.

In editor / native mode (`HostBridge.is_streaming() == false`), `BridgeApiClient` falls back to a direct `HTTPRequest` against `http://localhost:8080`. The contract is the same; only the transport changes.

## Why

1. **One point of network egress.** The host shell adds the same auth and session context to every API call. Iframe code never touches JWTs or session ids.
2. **Per-session routing.** The host attaches `X-Session-Id` from the request payload. That header flips the BFF's `ControlController` into per-session sink routing ([decision 010](010-per-session-sse-sinks.md)). Iframe code that made its own fetch could not easily attach it without the host's `MultiplayerSessionService` state.
3. **Mockability.** Tests, replays and e2e harnesses intercept at the host-shell seam (`ApiBridgeService.spec.ts`), not by stubbing fetch inside the WASM bundle.

## How to apply

- **Game scripts:** use `BridgeApiClient` (a Rust GodotClass from the `hugmesh-bridge` GDExtension, found through `ClassDB.instantiate("BridgeApiClient")` or the addon's typed reference). Never `HTTPRequest`. The methods are `http_get` / `http_post` / `http_put` / `http_delete`, not `get` / `post` / …, because `get` collides with `Node.get(property)`:
  ```gdscript
  var api := BridgeApiClient.new()
  add_child(api)  # ready() auto-discovers the sibling/scene-root node named "HostBridge"
  api.http_get("/api/chapters/1/scene", func(status, body):
      if status == 200: _apply_scene(body))
  ```
- **Rust HostBridge:** `request_api(method, path, body_json) -> int` and `dispatch_api_response(payload_json)`, with one `api_response_received` signal carrying the JSON payload. Request ids tie each response to its callback.
- **BridgeGlue (Rust GodotClass, `bridge_glue.rs`):** subscribes to `api_request_egress` and forwards through `window.parent.postMessage('SD_API_REQUEST', ...)`. It installs a `message` listener that catches `SD_API_RESPONSE` and pumps it back into `HostBridge.dispatch_api_response`.
- **Host shell (`ApiBridgeService`):** listens for `SD_API_REQUEST` postMessages, makes the `HttpClient.request(...)` call (auth interceptor + `X-Session-Id` from the payload), and posts `SD_API_RESPONSE` to the iframe's content window.

## Trade-offs accepted

- **One extra postMessage hop per request**, ~1–2 ms on the hot path. We accept it for consistency.
- **The bridge must know every request type.** Today it is path-agnostic and just forwards. If we ever need request-shape validation at the bridge layer, a wrapper schema would go into the addon's `BridgeApiClient`.
- **Editor F5 falls back to direct HTTPRequest.** That path gets neither the auth interceptor nor session context. We accept that: editor playthroughs are dev-only and the standalone codebase isn't authenticated.

## Reference

- [HugMesh Bridge addon README](../../../extensions/hugmesh-bridge/addons/hugmesh_bridge/README.md)
- [`bridge_api_client.rs`](../../../extensions/hugmesh-bridge/src/bridge_api_client.rs)
- [`bridge_glue.rs`](../../../extensions/hugmesh-bridge/src/bridge_glue.rs)
- [`api-bridge.service.ts`](../../../frontend/src/app/services/api-bridge.service.ts)
- [`SessionIsolationTest`](../../../backend/src/test/java/games/hugmesh/bff/service/SessionIsolationTest.java) — locks the per-session SSE isolation that this routing enables.
