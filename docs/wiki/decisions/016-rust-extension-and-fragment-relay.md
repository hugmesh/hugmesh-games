# 016 — Rust GDExtension owns the bridge; Angular wrapper relays SSE + HTTP

**Status:** Adopted (Phase 9 of the multiplayer overhaul).

## Decision

Two coupled changes:

1. **The bulk of the HugMesh Bridge moves into Rust.** What was
   four GDScript files in the addon (`bridge_glue.gd`,
   `bridge_api_client.gd`, `multiplayer_lobby.gd`, plus per-game
   `bridge_glue.gd` copies) becomes three Rust modules — `BridgeGlue`,
   `BridgeApiClient`, `HugMeshMultiplayerLobby` — registered as
   GDExtension classes alongside the existing `HostBridge`. Shipped
   scenes place `HostBridge` and `BridgeGlue` by class type
   (`type="BridgeGlue"`); games create `BridgeApiClient` in code. No
   bridge script files to chase.

2. **The Angular wrapper is now the sole HTTP + SSE transport for the
   iframe.** The wasm / Godot / Rust window is a passive data
   consumer:
     - Wrapper opens an SSE stream on the iframe's behalf in
       response to `SD_SSE_SUBSCRIBE`, then postMessages each typed
       event back as `SD_SSE_EVENT`. It uses the fetch-based
       `openAuthenticatedSse` reader, not `EventSource`, because
       `EventSource` cannot send an `Authorization` header and the
       session-scoped stream is membership-gated.
     - Wrapper performs `HttpClient` calls in response to
       `SD_API_REQUEST`, then postMessages the response back as
       `SD_API_RESPONSE`.
     - Inside the iframe, Rust `BridgeGlue` runs a single
       `window.message` listener that buckets payloads onto two
       JS-side queues (`window._twApiResponseQueue`,
       `window._twSseEventQueue`). `_process` drains both per frame
       and routes through `HostBridge.dispatch_api_response` /
       `HostBridge.dispatch_event`, so the typed signals fire.

Game scripts keep consuming `HostBridge.movement_received`,
`world_received`, etc. The surface they connect to is unchanged.

## Why

- **One transport seam.** The Angular host already owns auth,
  X-Session-Id, retry and telemetry for every other request. Routing
  the iframe through the same `HttpClient` applies interceptors,
  `withCredentials` and any future header policy uniformly, with no
  parallel implementation in the wasm bundle.

- **Move data handling into Rust.** GDScript carried request-id
  correlation, JSON parsing, queue draining and Callable-bound HTTP
  fallbacks. None of that benefits from GDScript, and all of it
  benefits from compile-time checks and a smaller WASM surface. The
  Rust side now owns:
    - HostBridge mode arbitration (`set_streaming_mode`)
    - `request_api` outbound payload framing
    - JSON-array splitting for queue drains
    - per-frame dispatch back into typed signals
    - per-slot avatar lifecycle in
      `HugMeshMultiplayerLobby` (palette / formation / collision
      shape build-out happens in Rust)

## How to apply

- **Add the bridge nodes to the scene root** (or use the bundled
  `multiplayer_lobby_menu.tscn`). This is the shape of the shipped
  `chess.tscn`:

  ```
  [node name="GameBridge" type="Node" parent="."]
  [node name="HostBridge" type="HostBridge" parent="."]
  [node name="BridgeGlue" type="BridgeGlue" parent="GameBridge"]
  ```

  The bridge classes need no `script=`; they are GDExtension-registered.
  The `GameBridge` node in shipped scenes carries the game's own
  `game_bridge.gd`, which posts `SD_SSE_SUBSCRIBE`. `BridgeApiClient`
  is added from code (`BridgeApiClient.new()` + `add_child`).

- **Subscribe to SSE from the iframe** by sending exactly one
  postMessage on boot:

  ```gdscript
  JavaScriptBridge.eval(
      "window.parent.postMessage({type:'SD_SSE_SUBSCRIBE', payload:{sessionId:'%s'}}, '*')" % sid)
  ```

  After that, every typed channel (`movement` / `world` / `character`
  / `time` / `scene` / `session-control`) flows in via `HostBridge.movement_received`
  etc. The wrapper tears down and re-subscribes on chapter remount.

- **Make API calls through `BridgeApiClient`.** Its methods are
  `http_get` / `http_post` / `http_put` / `http_delete` (renamed so
  they don't shadow `Node.get(property)`). Each takes a `Callable`
  that fires with `(status: int, body: Variant)` when the response
  lands.

- **Editor F5 still works.** Standalone mode falls back to a Godot
  `HttpRequest` against `http://localhost:8080`. SSE reception simply
  doesn't fire (the wrapper isn't there), and game scripts that gate
  on the channel having data keep working.

## Trade-offs accepted

- **Initial-load chattiness.** The first `SD_SSE_SUBSCRIBE` adds one
  postMessage hop before events flow: sub-millisecond, once per chapter
  mount.

- **Two JS queues, not one.** API responses and SSE events are
  bucketed separately, so a noisy SSE channel can't starve the API
  drain (or the reverse). The Rust drain runs both per `_process`, at
  a cost of one `JavaScriptBridge.eval` per channel.

- **The wrapper is a shared chokepoint.** Every game's API and SSE
  traffic passes through `ApiBridgeService`. Per-game egress policies
  (e.g. moderator-toggled rate limits) would layer on top of that
  service, not splinter across federated remotes.

## Reference

- [HostBridge](../../../extensions/hugmesh-bridge/src/host_bridge.rs)
- [BridgeGlue](../../../extensions/hugmesh-bridge/src/bridge_glue.rs)
- [BridgeApiClient](../../../extensions/hugmesh-bridge/src/bridge_api_client.rs)
- [HugMeshMultiplayerLobby](../../../extensions/hugmesh-bridge/src/multiplayer_lobby.rs)
- [ApiBridgeService](../../../frontend/src/app/services/api-bridge.service.ts)
- [Decision 014 — All Godot networking flows through the bridge](014-bridge-routed-networking.md)
