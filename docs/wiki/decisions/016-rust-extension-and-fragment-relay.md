# 016 — Rust GDExtension owns the bridge; Angular wrapper relays SSE + HTTP

**Status:** Adopted (Phase 9 of the multiplayer overhaul).

## Decision

Two coupled changes:

1. **The bulk of the HugMesh Bridge moves into Rust.** What was
   four GDScript files in the addon (`bridge_glue.gd`,
   `bridge_api_client.gd`, `multiplayer_lobby.gd`, plus per-game
   `bridge_glue.gd` copies) becomes three Rust modules — `BridgeGlue`,
   `BridgeApiClient`, `HugMeshMultiplayerLobby` — registered as
   GDExtension classes alongside the existing `HostBridge`. Game
   scenes reference them by class type (`type="BridgeGlue"`,
   `type="BridgeApiClient"`); no script files to chase.

2. **The Angular wrapper is now the sole HTTP + SSE transport for the
   iframe.** The wasm / Godot / Rust window is a passive data
   consumer:
     - Wrapper opens an `EventSource` on the iframe's behalf in
       response to `SD_SSE_SUBSCRIBE`, then postMessages each typed
       event back as `SD_SSE_EVENT`.
     - Wrapper performs `HttpClient` calls in response to
       `SD_API_REQUEST`, then postMessages the response back as
       `SD_API_RESPONSE`.
     - Inside the iframe, Rust `BridgeGlue` runs a single
       `window.message` listener that buckets payloads onto two
       JS-side queues (`window._twApiResponseQueue`,
       `window._twSseEventQueue`); `_process` drains both per frame
       and routes through `HostBridge.dispatch_api_response` /
       `HostBridge.dispatch_event` so the typed signals fire.

Game scripts continue to consume `HostBridge.movement_received`,
`world_received`, etc. — the surface they connect to is unchanged.

## Why

Two reasons drove the split:

- **One transport seam.** Auth, X-Session-Id, retry, telemetry — the
  Angular host already owns these for every other request. Routing
  the iframe through the same `HttpClient` keeps interceptors,
  `withCredentials`, and any future header policy applied uniformly
  with no parallel implementation in the wasm bundle.

- **Move data handling into Rust.** GDScript was carrying
  request-id correlation, JSON parsing, queue draining, and
  Callable-bound HTTP fallbacks. None of that benefits from being in
  GDScript; all of it benefits from compile-time checks and a smaller
  WASM surface. The Rust side now owns:
    - HostBridge mode arbitration (`set_streaming_mode`)
    - `request_api` outbound payload framing
    - JSON-array splitting for queue drains
    - per-frame dispatch back into typed signals
    - per-slot avatar lifecycle in
      `HugMeshMultiplayerLobby` (palette / formation / collision
      shape build-out happens in Rust)

## How to apply

- **Add a HugMesh node trio to the scene root** (or use the
  bundled `multiplayer_lobby_menu.tscn`):

  ```
  [node name="HostBridge"   type="HostBridge"]
  [node name="BridgeGlue"   type="BridgeGlue"   parent="GameBridge"]
  [node name="BridgeApiClient" type="BridgeApiClient" parent="GameBridge"]
  ```

  No `script=` references — every class is GDExtension-registered.

- **Subscribe to SSE from the iframe** by sending exactly one
  postMessage on boot:

  ```gdscript
  JavaScriptBridge.eval(
      "window.parent.postMessage({type:'SD_SSE_SUBSCRIBE', payload:{sessionId:'%s'}}, '*')" % sid)
  ```

  After that, every typed channel (`movement` / `world` / `character`
  / `time` / `scene`) flows in via `HostBridge.movement_received`
  etc. The wrapper auto-tears-down and re-subscribes on chapter
  remount.

- **Make API calls through `BridgeApiClient`** — its method set is
  `http_get` / `http_post` / `http_put` / `http_delete` (renamed so
  it doesn't shadow `Node.get(property)`). Each takes a `Callable`
  that fires with `(status: int, body: Variant)` once the response
  lands.

- **Editor F5 still works.** Standalone mode falls through to a
  Godot `HttpRequest` against `http://localhost:8080`, and SSE
  reception simply doesn't fire (the wrapper isn't there) — game
  scripts that gate on the channel having data continue to function.

## Trade-offs accepted

- **Initial-load chattiness.** The first `SD_SSE_SUBSCRIBE` round-trip
  adds one postMessage hop before SSE events flow. In practice this
  is sub-millisecond and only fires once per chapter mount.

- **Two JS queues vs one.** API responses and SSE events are bucketed
  separately so a noisy SSE channel can't starve the API drain (and
  vice versa). The Rust drain runs both per `_process`; cost is one
  `JavaScriptBridge.eval` per channel.

- **Wrapper is a shared chokepoint.** Every game's API + SSE flows
  through `ApiBridgeService`. If we ever need per-game egress
  policies (e.g. moderator-toggled rate limits), they layer on top of
  the existing service rather than splintering across federated
  remotes.

## Reference

- [HostBridge](../../../extensions/hugmesh-bridge/src/host_bridge.rs)
- [BridgeGlue](../../../extensions/hugmesh-bridge/src/bridge_glue.rs)
- [BridgeApiClient](../../../extensions/hugmesh-bridge/src/bridge_api_client.rs)
- [HugMeshMultiplayerLobby](../../../extensions/hugmesh-bridge/src/multiplayer_lobby.rs)
- [ApiBridgeService](../../../frontend/src/app/services/api-bridge.service.ts)
- [Decision 014 — All Godot networking flows through the bridge](014-bridge-routed-networking.md)
