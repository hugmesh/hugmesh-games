# Snapdragon Bridge — Godot addon

Reusable bridge layer for Snapdragon-hosted Godot games. When a Godot
WASM bundle runs inside the Snapdragon Angular host shell, all network
traffic and real-time events must pass through a JavaScript message
channel rather than through the browser's `fetch`/`XMLHttpRequest`
APIs directly. This addon implements that channel and exposes a clean
GDScript surface so game code never has to handle raw JavaScript
interop. It provides five components:

- **HostBridge** — a Rust GDExtension class that is the central hub.
  It emits typed GDScript signals when the host shell pushes SSE events
  inward (`movement_received`, `world_received`, …) and it exposes
  methods for sending input, camera view, console output, and API
  requests outward to the shell. It also maintains the list of
  multiplayer player slots.
- **`bridge_glue.gd`** — the `JavaScriptBridge` ↔ HostBridge adapter.
  It translates outbound GDScript calls (`input_egress`, `view_egress`,
  `api_request_egress`) into `postMessage` calls to the parent Angular
  host, and it delivers inbound `postMessage` replies (including API
  responses) back into HostBridge so signals fire on the GDScript side.
- **`BridgeApiClient.gd`** — a Callable-based HTTP client built on top
  of `HostBridge.request_api` and the `api_response_received` signal.
  Game code calls `get`, `post`, `put`, or `delete` with a completion
  callback; the client correlates requests to responses by request ID
  internally. Because every call routes through the bridge, the host
  shell's `HttpClient` interceptor automatically attaches the
  `Authorization` header and `X-Session-Id` to every request.
- **`MultiplayerLobby.gd`** — slot orchestration. Listens for
  `slot_registered` and `slot_released` signals from HostBridge,
  fetches the current session members from `/api/sessions/{id}` via the
  bridge, and spawns or despawns one `CharacterBody3D` per slot.
- **`MultiplayerLobbyMenu.tscn`** + `MultiplayerLobbyMenu.gd` — a
  drop-in pre-game lobby UI: lists open sessions, lets the player pick
  a slot, and launches the game.

## API call flow

The diagram below shows how an outbound API call travels from game code
to the BFF and how the response finds its way back.

```mermaid
sequenceDiagram
    participant Game as Game code
    participant BAC as BridgeApiClient.gd
    participant HB as HostBridge (Rust)
    participant BG as bridge_glue.gd
    participant Shell as Angular host shell
    participant BFF as Spring Boot BFF

    Game->>BAC: get("/api/sessions/42", on_done)
    BAC->>HB: request_api("GET", "/api/sessions/42", "")
    Note right of HB: returns request_id (int)
    HB->>BG: api_request_egress signal
    BG->>Shell: postMessage({type:"API_REQUEST", requestId, …})
    Shell->>BFF: fetch /api/sessions/42<br/>(Authorization + X-Session-Id injected)
    BFF-->>Shell: 200 {members: […]}
    Shell-->>BG: postMessage({type:"API_RESPONSE", requestId, status, body})
    BG->>HB: deliver response
    HB-->>BAC: api_response_received(payload_json)
    BAC-->>Game: on_done.call(status, parsed_body)
```

The host shell's `HttpClient` interceptor owns the auth headers — game
code never handles tokens or session IDs.

## Usage

In a game project's `project.godot`, enable the plugin:

```ini
[editor_plugins]
enabled=PackedStringArray("res://addons/snapdragon_bridge/plugin.cfg")
```

Add three sibling nodes directly under your scene root. The expected
scene tree looks like this:

```
SceneRoot (Node3D or similar)
├── HostBridge          ← Rust GDExtension class; do not rename
├── BridgeGlue          ← script: res://addons/snapdragon_bridge/bridge_glue.gd
└── MultiplayerLobby    ← script: res://addons/snapdragon_bridge/multiplayer_lobby.gd
```

Reference them in your root script with:

```gdscript
# scene_loader.gd or scene root
@onready var host_bridge = $HostBridge
@onready var glue        = $BridgeGlue
@onready var lobby       = $MultiplayerLobby
```

`BridgeGlue` finds `HostBridge` by name, so the node name must match
exactly. Alternatively, instantiate `MultiplayerLobbyMenu.tscn` as a
`Control` overlay on your scene to get the canonical session picker
without writing lobby code yourself.

## Game-developer contract

**Preferred surface — `BridgeApiClient`**

Use `BridgeApiClient` for all BFF calls. Pass a `Callable` that receives
`(status: int, body: Dictionary)` when the response arrives:

```gdscript
var api := BridgeApiClient.new(host_bridge)

func _ready() -> void:
    api.get("/api/sessions/42", func(status, body):
        if status == 200:
            print("members: ", body.members)
    )

    api.post("/api/controls", {"action": "move", "dir": "north"}, func(status, body):
        if status != 200:
            push_error("control rejected: %d" % status)
    )
```

**Low-level surface — `HostBridge.request_api`**

If you need the raw request-ID for manual correlation:

```gdscript
var req_id := host_bridge.request_api("GET", "/api/sessions/42", "")

func _on_host_bridge_api_response_received(payload_json: String) -> void:
    var payload := JSON.parse_string(payload_json)
    if payload.requestId == req_id:
        print(payload.status, payload.body)
```

`BridgeApiClient` does this correlation automatically; prefer it over
the raw signal.

**Slot and view reporting**

- `HostBridge.register_player_slot(slot, character_id, is_local)` —
  declares a slot to HostBridge. When using `MultiplayerLobby` end-to-end
  the lobby script calls this for you; only call it directly when
  managing slots manually.
- `HostBridge.report_view(slot, x, y, z, yaw, pitch, fov)` — call at
  approximately 10 Hz from the local player's camera. This feeds the
  per-session `view` SSE channel that administrators watch in the
  Sessions panel.

## Why route API through the bridge

Godot WASM bundles run in a sandboxed iframe. The Snapdragon host shell
owns the user's session — it holds the JWT and the `X-Session-Id` — so
routing all BFF calls through the bridge gives four concrete benefits:

- **Consistent auth.** The host shell's `HttpClient` interceptor
  attaches `Authorization` and `X-Session-Id` to every outbound request.
  Game code does not need to read, store, or refresh tokens.
- **Single egress point.** All network traffic leaves through one
  JavaScript path, making it straightforward to audit, throttle, or
  log BFF calls from any game.
- **Testability.** Mocking, replaying, or intercepting API calls happens
  at the host-shell boundary, outside the Godot bundle. No Godot-side
  test harness is needed.
- **No stray `HTTPRequest` nodes.** Godot's built-in `HTTPRequest` would
  bypass the interceptor entirely, sending requests without auth headers.
  Routing through the bridge makes that impossible.

## Standalone (editor F5) mode

When the game runs outside the Angular host shell — for example when you
press F5 in the Godot editor — `HostBridge.is_streaming()` returns
`false`. In that state `BridgeApiClient` bypasses the bridge and sends
API calls directly via Godot's `HTTPRequest` node against
`http://localhost:8080/api/…`.

This means editor playthroughs work as long as the BFF is running
locally. SSE events from the host shell are unavailable in this mode
(there is no parent frame to send them), so multiplayer features that
depend on `movement_received` or `world_received` will not fire. Single-
player API calls such as loading a scene or fetching session data work
normally.
