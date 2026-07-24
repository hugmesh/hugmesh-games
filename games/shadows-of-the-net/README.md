# Shadows of the Net – CHAT (`shadows-of-the-net`)

A GitS-SAC-ep.9-style noir virtual chat chamber as a Godot 4 wasm-game: a
circular room with a round center table carrying six media plates
(`table-1..6`), four wall screens (`screen-1..4`), a spotlit stage platform
(`stage`), and a 16-seat ring. Participants appear as procedural 3D characters
built by the vendored `CharacterAssembler` (Char3DConfig v1 — the canonical
copy lives in `snapdragon-character-forge-bff-api/godot/character-forge/`).

Unlike every other Godot game in this repo, this project is **pure GDScript
with NO hugmesh_bridge GDExtension**: it has no Rust rules core to reuse,
and the XR-chat design requires it to stay self-contained so the same bundle
can be embedded outside the games portal (Matrix). The SD_* transport that the
Rust `HostBridge`/`BridgeGlue` normally provides is implemented in
`scripts/session_bridge.gd` via `JavaScriptBridge` against the exact same wire
shapes (`games/_shared/sse-host-client.js` is the reference).

## Dual mode (URL query `mode`)

### `mode=session` (default — games portal)

Standard wasm-game contract: the host shell launches
`/_remotes/wasm-fragment/shadows-of-the-net/index.html?session_id=…&character_id=…&slot_index=…`
and the bundle speaks `SD_SSE_SUBSCRIBE` / `SD_API_REQUEST` / `SD_SSE_EVENT`
postMessages with the Angular `ApiBridgeService`.

* **Seat/pose relay — movement channel.** Every member posts control actions
  (`POST /api/controls/{cid}`, echoed to the session as `PlayerMovement`):
  * `SOTN_POSE|<x>|<z>|<yaw>|<seat>` — local pose, ≤10 Hz, on change.
  * `SOTN_SEAT|<seat>` — seat request (host validates: first-come, `-1` releases).
  * `SOTN_STAGE|<0|1>` — stage request/release (host arbitrates, one at a time).
* **Host-authoritative room state — worldNative.** Slot 0 (the session
  creator) broadcasts `kind:'world', type:'state'` with payload
  `{"type":"sotn.room","seats":{"<cid>":<seat>},"stage":<cid|-1>,"tick":n}`
  via `POST /api/input/{cid}`; the BFF's Gate 0b rejects world posts from
  non-creators, and guests re-apply the state verbatim from the `world` SSE
  channel. `world/state` is already seeded in `input_event_types` (050);
  migration 056 opts this slug into `game_event_support`.
* Session avatars are deterministic seeded randoms (`CharacterAssembler.
  random_config(hash(characterId))`) — same seed on every client, no BFF
  character storage involved.

### `mode=matrix`

**No games BFF at all.** The embedding page (snapdragon-matrix-ui's
`Chat3dHostComponent`) drives everything through the versioned `Chat3D`
interface installed on the iframe window (`scripts/chat3d_bridge.gd`):

* In: `roster(json)`, `avatar(userId, configJson)`, `pose(userId,x,z,yaw,seat)`,
  `openness(userId,f)`, `blendshapes(userId, base64-uint8[52])`,
  `speaking(userId,bool)`, `expression(userId,label)`,
  `placeMedia(surfaceId, metaJson, pngBase64|null)`, `clearMedia(surfaceId)`,
  `stage(userId,bool)`, `setLocal(userId)`, `enterVR()`.
* Out (`Chat3D.onEmit` callback + postMessage): `localPose(x,z,yaw,seat)`
  ~10 Hz, `mediaActivate(surfaceId)`, `vrState(active)`.
* postMessage envelope, both directions: `{v:1, t:<function-name>, args:[…]}`
  (args in signature order; inbound also accepts signature-named fields,
  outbound carries both). `v !== 1` is dropped.
* Origin allow-list on EVERY message both ways: the embedding page passes its
  origin as `?origin=`; falls back to `document.referrer`. Without either,
  postMessage I/O is disabled (never `'*'`) — direct same-origin `Chat3D.*`
  calls still work for dev.
* Media bytes are PNG only and are fetched/authenticated by the EMBEDDING page
  (Matrix authenticated media) — the wasm never sees credentials, matching the
  platform's iframe boundary.

## XR

`scripts/xr_boot.gd` (autoload `XrBoot`):

* **Web** → `WebXRInterface`, `immersive-vr`, reference spaces
  `bounded-floor, local-floor, local` (required: `local-floor`). Session
  request MUST run inside a user gesture: the in-scene **Enter VR** button is
  the reliable path; `Chat3D.enterVR()` is honoured but reports
  `vrState(false)` honestly when the browser denies a gesture-less request.
  The embedding iframe needs `allow="xr-spatial-tracking"`.
* **Native (Linux/Windows PCVR)** → OpenXR (project setting), auto-initialized
  at boot; no runtime → silent desktop fallback (`xr/openxr/startup_alert=false`).
* **Non-XR fallback** → third-person mouse/WASD (`scripts/local_controller.gd`):
  WASD/arrows walk, right-drag orbits, wheel zooms, left-click activates
  surfaces / sits on seats / sets a walk target.

The Web preset ships **threads OFF** (no COOP/COEP requirement — embeddable in
the Matrix client without cross-origin isolation) and **extensions_support
OFF** (no GDExtension). See `export_presets.cfg` for the full rationale.

## Files

| File | Role |
|---|---|
| `scenes/room.tscn` | the chamber: floor/wall/table/stage, 11 named media surfaces, 16 seat anchors, desktop + XR rigs |
| `scripts/room_controller.gd` | mode branch, host seat/stage arbitration, transport wiring |
| `scripts/session_bridge.gd` | SD_* postMessage transport (pure GDScript) |
| `scripts/chat3d_bridge.gd` | versioned Chat3D interface (matrix mode) |
| `scripts/character_assembler.gd` | **vendored** Char3DConfig→rig assembler (canonical copy: character-forge repo) |
| `scripts/avatar_manager.gd` | roster → rigs, pose smoothing, openness/blendshape/speaking drives |
| `scripts/media_surfaces.gd` | surface registry, PNG texturing, ray hit-testing |
| `scripts/local_controller.gd` | desktop controls + local pose authority |
| `scripts/xr_boot.gd` | WebXR / OpenXR / fallback branching |

## Registration

Migration `056-shadows-of-the-net-federation.xml`: `games` + `chapters`
(`asset_url=/_remotes/wasm-fragment/shadows-of-the-net/index.html`) +
`federation_modules` `kind='wasm-game'` metadata
`{"sse":true,"hostAuthoritative":true,"engine":"godot","xr":true}` +
`game_access scope='all'` + `game_event_support` rows. The
`/_remotes/wasm-fragment/` nginx location is generic (both `nginx.conf` and
`nginx-tls.conf`) — no per-slug proxy block is needed.
