# Snapdragon Bridge — Godot addon

Reusable bridge layer for Snapdragon-hosted Godot games. Provides:

- **HostBridge** Rust GDExtension class — typed signals for SSE ingress
  (`movement_received`, `world_received`, …), input/view/console egress,
  multiplayer slot bookkeeping, and **bridge-routed API networking**.
- **`bridge_glue.gd`** — the `JavaScriptBridge` ↔ HostBridge plumbing.
  Forwards outbound `input_egress` / `view_egress` / `api_request_egress`
  to the parent host shell; pumps inbound API responses back into
  HostBridge.
- **`BridgeApiClient.gd`** — Callable-based wrapper around the
  bridge's `request_api` / `api_response_received` so game code never
  touches `HTTPRequest`. All API calls flow through the bridge, which
  means the host shell's HttpClient interceptor (auth + `X-Session-Id`)
  applies automatically.
- **`MultiplayerLobby.gd`** — slot orchestration. Subscribes to
  `slot_registered` / `slot_released`, fetches session members from
  `/api/sessions/{id}` *via the bridge*, and spawns one programmatic
  `CharacterBody3D` per registered slot.
- **`MultiplayerLobbyMenu.tscn`** + `MultiplayerLobbyMenu.gd` — drop-in
  pre-game lobby UI: list open sessions, pick a slot, hit *Launch*.

## Usage

In a game project's `project.godot`, enable the plugin:

```ini
[editor_plugins]
enabled=PackedStringArray("res://addons/snapdragon_bridge/plugin.cfg")
```

Then in any scene root, add the three sibling nodes:

```gdscript
# scene_loader.gd or scene root
@onready var host_bridge = $HostBridge        # Rust class from the .gdextension
@onready var glue        = $BridgeGlue        # res://addons/snapdragon_bridge/bridge_glue.gd
@onready var lobby       = $MultiplayerLobby  # res://addons/snapdragon_bridge/multiplayer_lobby.gd
```

Or instantiate `MultiplayerLobbyMenu.tscn` as a Control over your scene
to get the canonical session picker for free.

## Game-developer contract

- `HostBridge.request_api(method, path, body_json) -> int` — returns a
  request id; subscribe to `api_response_received(payload_json)` to
  catch the matching `{requestId, status, body}`.
- `BridgeApiClient.get/post/put/delete(path, body, on_done)` — the
  preferred surface; uses the request-id correlation internally.
- `HostBridge.register_player_slot(slot, character_id, is_local)` —
  declare a slot you've discovered (the addon's lobby script handles
  this for you when used end-to-end).
- `HostBridge.report_view(slot, x, y, z, yaw, pitch, fov)` — call at
  ~10 Hz from the local-player camera so admin observation works.

## Why route API through the bridge

- Single point of network egress — auth + session context applied
  consistently.
- The host shell's `HttpClient` interceptor adds `Authorization` and
  `X-Session-Id` automatically — game code doesn't need to know about
  JWT or session ids.
- Tests / replays / mocking all happen at the host-shell boundary.
- No more rogue `HTTPRequest` instances making BFF calls outside the
  bridge's awareness.

## Standalone (editor F5) mode

When `HostBridge.is_streaming() == false` (no parent module), the
`BridgeApiClient` falls back to a direct `HTTPRequest` against
`http://localhost:8080/api/...`. Editor playthroughs work without
the host shell.
