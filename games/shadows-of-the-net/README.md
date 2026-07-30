# Shadows of the Net – CHAT (`shadows-of-the-net`)

A GitS-SAC-ep.9-style noir virtual chat chamber as a Godot 4 wasm-game: a
circular room with a round center table carrying six media plates
(`table-1..6`), four wall screens (`screen-1..4`), a spotlit stage platform
(`stage`), and a 16-seat ring. Participants appear as procedural 3D characters
built by the room-local `CharacterAssembler` (Char3DConfig v1). A sibling
assembler of the same name lives in
`hugmesh-character-forge-bff-api/godot/character-forge/scripts/`; the two
share the **data contract**, not the code — see the provenance header in
`scripts/character_assembler.gd` before "re-syncing" anything.

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
  * `SOTN_MEDIA|<surfaceId>` — surface place/clear toggle (host arbitrates:
    first-come per surface, only the claimant may release).

  **Trust model.** The BFF relays a member's action string *verbatim* to the
  whole session, so every field of every `SOTN_*` action is attacker-controlled
  by any member of the room. Only `<x>`/`<z>`/`<yaw>` are applied from the
  sender, and only after `AvatarManager.set_pose` rejects non-finite values and
  clamps into the chamber. Seat, stage and media placement come **exclusively**
  from the host's authoritative broadcast; the `<seat>` field of `SOTN_POSE` is
  the sender's own optimistic echo and is ignored for remote peers. Every verb
  above has a producer in `room_controller.gd` — adding one without a producer
  is how the stage grant originally shipped unreachable.
* **Host-authoritative room state — worldNative.** Slot 0 (the session
  creator) broadcasts `kind:'world', type:'state'` with payload
  `{"type":"sotn.room","seats":{"<cid>":<seat>},"placements":{"<surfaceId>":<cid>},"stage":<cid|-1>,"tick":n}`
  via `POST /api/input/{cid}`; the BFF's Gate 0b rejects world posts from
  non-creators, and guests re-apply the state verbatim from the `world` SSE
  channel. `world/state` is already seeded in `input_event_types` (050);
  migration 056 opts this slug into `game_event_support`.

  Slot 0 **is** the creator: `GameSessionService.CREATOR_SLOT` pins it on
  create and 409s any other member that asks for it, so the game's
  `slot_index == 0` election and Gate 0b's `isCreator()` name the same member.
  If they ever disagree anyway (a session predating the pin), the BFF wins — a
  403 on our world POST demotes us via `_on_world_forbidden` instead of looping
  `SD_SESSION_EXPIRED` → connect → claimControl forever.

  Session-mode placements carry **no image bytes**: there is no authenticated
  media source behind the games portal the way matrix-ui fetches Matrix media,
  so a placement renders as the metadata-only slate labelled with the claiming
  character. Bytes stay a matrix-mode capability.
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
* Out (`Chat3D.onEmit` callback + postMessage): `ready()`,
  `localPose(x,z,yaw,seat)` ~10 Hz, `mediaActivate(surfaceId)`,
  `vrState(active)`.
* **`ready()` is the handshake and it is not optional.** `Chat3dHostComponent`
  queues *every* outbound envelope (roster / avatar / setLocal / pose /
  placements / …) until it arrives and keeps Enter-VR inert, so the room stays
  an empty chamber until the game announces itself. `_setup_matrix()` emits it
  once the inbound signals are wired, and `chat3d_bridge` re-announces roughly
  once a second (15 attempts) until the host replies, so a host listener that
  attaches after the wasm boots still completes the handshake.
* Inbound envelopes accept the canonical DESIGN §5 argument names first, then
  the documented aliases in `chat3d_bridge.ALIASES` (matrix-ui's builders spell
  them `users` / `config` / `value` / `values` / `speaking` / `meta` / `bytes` /
  `on`). Without the aliases those payloads silently decode to `null` — no
  roster, no avatars, no lip-sync, no speaking ring.
* `ArrayBuffer` / TypedArray arguments are base64-encoded **at push time**, in
  the injected JS. The inbound queue is drained as JSON and `JSON.stringify`
  flattens an `ArrayBuffer` to `{}`, so a host that transfers real image bytes
  by structured clone and a host that sends `pngBase64` both work. Payloads
  over `MAX_MEDIA_BYTES` (4 MiB) degrade to a metadata-only plaque.
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
| `scripts/character_assembler.gd` | room-local Char3DConfig→rig assembler (shares the DATA CONTRACT — not the code — with the character-forge sibling; read its header) |
| `scripts/avatar_manager.gd` | roster → rigs, pose smoothing, openness/blendshape/speaking drives |
| `scripts/media_surfaces.gd` | surface registry, PNG texturing, ray hit-testing |
| `scripts/local_controller.gd` | desktop controls + local pose authority |
| `scripts/xr_boot.gd` | WebXR / OpenXR / fallback branching |
| `tests/` | headless GDScript regression suites (`make test-godot`) |

## Tests

```bash
make test-godot        # headless GDScript suites (needs GODOT=/path/to/godot4)
make test-games-js     # the browser-only JS this game injects, under node
```

`tests/run_tests.gd` discovers `tests/test_*.gd`, instantiates the **real**
`scenes/room.tscn`, and drives the transports through the controller's own
handlers — which is exactly what the SSE / postMessage drains call at runtime.
A suite that asserts nothing is reported as a failure, and the whole run has a
180 s deadline so a wedged coroutine is a red build rather than a hung one.
`games/_shared/test/chat3d-envelope.test.mjs` covers the JS half that a
headless Godot run can never execute (`JavaScriptBridge.eval` needs a browser).
See [`tests/README.md`](tests/README.md).

## Registration

Migration `056-shadows-of-the-net-federation.xml`: `games` + `chapters`
(`asset_url=/_remotes/wasm-fragment/shadows-of-the-net/index.html`) +
`federation_modules` `kind='wasm-game'` metadata
`{"sse":true,"hostAuthoritative":true,"engine":"godot","xr":true}` +
`game_access scope='all'` + `game_event_support` rows. The
`/_remotes/wasm-fragment/` nginx location is generic (both `nginx.conf` and
`nginx-tls.conf`) — no per-slug proxy block is needed.
