# Shadows of the Net – CHAT (`shadows-of-the-net`)

A noir virtual chat chamber in the style of GitS-SAC-ep.9, built as a Godot 4
wasm-game. The room is circular. A round center table carries six media plates
(`table-1..6`). Four wall screens (`screen-1..4`), a spotlit stage platform
(`stage`) and a 16-seat ring complete it. Participants appear as procedural 3D
characters built by the room-local `CharacterAssembler` (Char3DConfig v1). Much
of the code exists so that one member cannot lie to the others.

A sibling assembler of the same name lives in
`games/character-forge/scripts/character_assembler.gd`. The two share the **data
contract**, not the code. Read the provenance header in
`scripts/character_assembler.gd` before you "re-sync" anything.

Four Godot games in this repo use the Rust bridge (`three-wizards-in-a-trenchcoat`,
`dodecahedron-demo`, `chess-godot`, `connect-four-godot`). This project does not.
It is **pure GDScript with NO hugmesh_bridge GDExtension**, as is `character-forge`.
It has no Rust rules core to reuse, and the XR-chat design needs it self-contained
so the same bundle can be embedded outside the games portal (Matrix). The SD_*
transport that the Rust `HostBridge`/`BridgeGlue` normally provides lives in
`scripts/session_bridge.gd`. It uses `JavaScriptBridge` and the same wire shapes
(`games/_shared/sse-host-client.js` is the reference).

## Dual mode (URL query `mode`)

### `mode=session` (default — games portal)

This is the standard wasm-game contract. The host shell launches
`/_remotes/wasm-fragment/shadows-of-the-net/index.html?session_id=…&character_id=…&slot_index=…`.
The bundle speaks `SD_SSE_SUBSCRIBE` / `SD_API_REQUEST` / `SD_SSE_EVENT`
postMessages with the Angular `ApiBridgeService`.

* **Seat/pose relay — movement channel.** Every member posts control actions
  (`POST /api/controls/{cid}`, echoed to the session as `PlayerMovement`):
  * `SOTN_POSE|<x>|<z>|<yaw>` — local pose, at most 10 Hz, on change.
  * `SOTN_SEAT|<seat>` — seat request. The host validates it: first come, first
    served; `-1` releases.
  * `SOTN_STAGE|<0|1>` — stage request or release. The host arbitrates, one
    holder at a time.
  * `SOTN_MEDIA|<surfaceId>` — place or clear toggle for a surface. The host
    arbitrates: first come per surface, and only the claimant may release.

  **Trust model.** The BFF relays a member's action string *verbatim* to the
  whole session. Every field of every `SOTN_*` action is therefore under the
  control of any member of the room.
  * Only `<x>`/`<z>`/`<yaw>` are applied from the sender, and only after
    `AvatarManager.set_pose` rejects non-finite values and clamps into the
    chamber.
  * Seat, stage and media placement come **only** from the host's authoritative
    broadcast. That is why `SOTN_POSE` carries no seat: a seat declared by a peer
    must never be honoured. Every legitimate seat change already travels the
    arbitrated `SOTN_SEAT` channel (`_sync_local_seat` covers the click, the
    walk-away and a host-driven sit alike).
  * Parsing is length-**tolerant** (`>= 4` fields). A browser on a cached older
    bundle still appends its seat, and the extra field is not read.
  * Every verb above has a producer in `room_controller.gd`, and every field has
    a reader. A verb with no producer is how the stage grant first shipped
    unreachable. A field with no reader is the same defect mirrored.
  * Matrix mode differs and keeps its seat argument: there the host *page* owns
    the seat map and reads it off `Chat3D.localPose(x,z,yaw,seat)`.
* **Membership — polled, not pushed.** Nothing the iframe receives announces a
  *departure*.
  * The host shell forwards six SSE channels (`SSE_CHANNELS` in
    `frontend/src/app/services/api-bridge.service.ts` = movement / world /
    character / time / scene / session-control).
  * `session-control` carries only `SESSION_PAUSED|RESUMED`, `PLAYER_PAUSED|RESUMED`
    (a tab takeover) and `SESSION_CLOSED` (the whole room dying).
  * `GameSessionService.leave` and `ModerationService.kickPlayer` stamp
    `session_members.left_at` and emit nothing per member.
  * The typed `WorldState` that shares the `world` event name is a per-*chapter*
    roster built from a pod-lifetime map. It never evicts on leave, so it only
    grows, and it pools characters from every session in that chapter.

  So the room *reads* the authoritative answer. `session_bridge.request_roster()`
  GETs `/api/sessions/{id}` every 5 s over the same `SD_API_REQUEST` channel the
  controls and world posts use. The `members` list (`left_at IS NULL`) drives
  `_on_session_roster` → `AvatarManager.prune`. On the host it also evicts the
  departed member's seat, placement and stage grant. Without this, `_avatars`
  never shrank, and a long-lived room filled to `MAX_AVATARS` with ghosts and then
  refused real newcomers.
* **Host-authoritative room state — worldNative.** Slot 0 (the session creator)
  broadcasts `kind:'world', type:'state'` with payload
  `{"type":"sotn.room","seats":{"<cid>":<seat>},"placements":{"<surfaceId>":<cid>},"stage":<cid|-1>,"tick":n}`
  via `POST /api/input/{cid}`. The BFF's Gate 0b rejects world posts from
  non-creators. Guests re-apply the state verbatim from the `world` SSE channel.
  `world/state` is already seeded in `input_event_types` (050). Migration 056
  opts this slug into `game_event_support`.

  Slot 0 **is** the creator. `GameSessionService.CREATOR_SLOT` pins it on create
  and answers 409 to any other member that asks for it, so the game's
  `slot_index == 0` election and Gate 0b's `isCreator()` name the same member.
  If they ever disagree (a session that predates the pin), the BFF wins. A 403 on
  our world POST demotes us via `_on_world_forbidden`, instead of looping
  `SD_SESSION_EXPIRED` → connect → claimControl forever.

  Session-mode placements carry **no image bytes**. No authenticated media source
  sits behind the games portal the way matrix-ui fetches Matrix media. A placement
  renders as a metadata-only slate labelled with the claiming character. Bytes
  stay a matrix-mode capability.
* Session avatars are deterministic seeded randoms
  (`CharacterAssembler.random_config(hash(characterId))`). Every client derives
  the same one, and no BFF character storage is involved.

### `mode=matrix`

**No games BFF at all.** The embedding page (hugmesh-matrix-ui's
`Chat3dHostComponent`) drives everything through the versioned `Chat3D`
interface installed on the iframe window (`scripts/chat3d_bridge.gd`):

* In: `roster(json)`,
  `avatar(userId, configJson, mode, sticker, name)`,
  `sticker(userId, b64, mime)`,
  `pose(userId,x,z,yaw,seat)`,
  `openness(userId,f)`, `blendshapes(userId, base64-uint8[52])`,
  `speaking(userId,bool)`, `expression(userId,label)`,
  `placeMedia(surfaceId, metaJson, pngBase64|null)`, `clearMedia(surfaceId)`,
  `stage(userId,bool)`, `setLocal(userId)`, `enterVR()`.
* **`avatar` carries five slots here, not DESIGN §5's two. The two signature
  tables are COMPATIBLE, not identical.**
  * `mode` is one of `forge` / `guest` / `billboard`.
  * A billboard participant has `configJson === null`. `mode` + `sticker` (an
    `mxc://` URL) + `name` describe it entirely.
  * Those three used to ride *outside* the signature, which made them unreachable
    (the decode path took `args` verbatim when it was an array). Billboard
    participants were rendered as randomised 3D characters.
  * matrix-ui deliberately keeps the 2-slot §5 row. It has nothing honest to put
    in the other three: `name` rides in every roster row, and the sticker rides as
    bytes on its own channel (below).
  * The interlock is the shim's short-`args` backfill. A 2-slot envelope decodes
    here to `[userId, cfg, null, null, null]`, and an absent `mode` means "keep the
    rig you have". The host changes nothing it did not speak about.
  * Every *other* row is byte-for-byte matrix-ui's, except `sticker`: matrix-ui's
    `HOST_CALL_SIGNATURES` has no `sticker` row yet.
* **`sticker(userId, b64, mime)` is the billboard BYTE channel.** An `mxc://` is
  unusable from the games origin (no access token, not an HTTP URL). A sticker can
  only reach the room as bytes: the host dereferences the mxc against its
  authenticated media route and ships the base64.
  * It is a channel of its own, not three more `avatar` slots. `avatar` is diffed
    and re-pushed on every avatar-state change, a sticker changes essentially
    never, and megabytes do not belong on a hot path.
  * Receiving one *is* the billboard signal. `mode` never travels on it, so
    neither side has a mode field to keep in sync.
  * An empty payload clears the plate and gives the member back their rig.
  * It is the one inbound channel that refuses to `ensure()` an unknown
    participant. Conjuring from it would let arrival order decide who is in the
    room, and a sticker for somebody who has left would resurrect them.
  * `mime` is allow-listed to the room's three decoders (`image/png` /
    `image/jpeg` / `image/webp`). The payload is re-checked against
    `MAX_MEDIA_BYTES` on receive. Both checks also run on the sending side.
  * An over-cap payload is refused on its base64 LENGTH before it is decoded, so
    the defence-in-depth leg never allocates the buffer it exists to reject.
* **That drop is lossy, and ordering is matrix-ui's job (contract §4, v4).**
  Earlier revisions called this channel "order-independent". A silent drop is not
  order-independent. Nothing on this channel reports one, so a sticker that
  outran its roster row was never seen again. One member joining during the
  15-30 s wasm cold-compile could reorder the host's pre-handshake queue and
  leave another member with no plate, for anybody, for the whole session. The
  contract therefore makes the host:
  * keep that queue in produced order (§4a);
  * flush the admitting roster ahead of everything else (§4b);
  * re-send a plate when its member is newly ADMITTED. This is keyed on the member
    set, so a display-name change re-sends nothing (§4c). v3 re-armed on "any
    roster change", and one member renaming re-drove every sticker in the room;
  * roll its sent-bookkeeping back when its queue evicts under byte or count
    pressure, so a dropped plate is re-sent instead of lost (§4d).

  matrix-ui on `main` does not send `sticker` yet, so these four rules are the
  contract, not shipped host behaviour. The rule on this side is unchanged:
  refuse, and let the host re-send. `tests/test_chat3d_sticker.gd` drives the exact
  interleave.
* **`roster` rows carry `name`, and it is what the nametag says.** The mxid
  localpart is only the fallback for an empty one. Names are sanitised with the
  BFF's display-name rule (control, bidi-override and invisible code points
  stripped) before they are drawn, because a nametag sits next to every other
  participant's.
* **`expression` and `stage` have host producers.** matrix-ui's
  `Chat3dHostComponent` sends `expression` from each member's latest room message
  and `stage` from the moderator-gated stage grant (the "Take the stage" button).
  `tests/test_chat3d_convergence.gd` pins the receivers here.
* Out (`Chat3D.onEmit` callback + postMessage): `ready()`,
  `localPose(x,z,yaw,seat)` at about 10 Hz, `mediaActivate(surfaceId)`,
  `vrState(active)`.
* **`ready()` is the handshake, and it is not optional.** `Chat3dHostComponent`
  queues *every* outbound envelope (roster / avatar / setLocal / pose /
  placements / …) until it arrives, and keeps Enter-VR inert. Until then the room
  stays an empty chamber. `_setup_matrix()` emits it once the inbound signals are
  wired. `chat3d_bridge` re-announces about once a second (15 attempts) until the
  host replies, so a host listener that attaches after the wasm boots still
  completes the handshake.
* Inbound envelopes accept the canonical DESIGN §5 argument names first, then the
  documented aliases in `chat3d_bridge.ALIASES` (matrix-ui's builders spell them
  `users` / `config` / `value` / `values` / `speaking` / `meta` / `bytes` / `on`).
  Without the aliases those payloads decode silently to `null`: no roster, no
  avatars, no lip-sync, no speaking ring.
* `ArrayBuffer` / TypedArray arguments are base64-encoded **at push time**, in the
  injected JS. The inbound queue is drained as JSON, and `JSON.stringify` flattens
  an `ArrayBuffer` to `{}`. A host that transfers real image bytes by structured
  clone and a host that sends `pngBase64` both work. Payloads over
  `MAX_MEDIA_BYTES` (4 MiB) degrade to a metadata-only plaque. That holds on the
  base64-string transport as well as the binary one (the cap used to live only
  inside the binary normaliser, which is to say nowhere that shipped), and again
  in GDScript after decoding. The host enforces the same number before sending.
* **The texture bytes are not necessarily PNG.** The host forwards the
  untranscoded original Matrix attachment. `media_surfaces.decode_image()` tries
  PNG, JPEG and WebP. It sniffs the magic bytes so the right decoder goes first (a
  mismatch logs an engine error that the shim republishes), and warns only when
  all of them refuse.
* Inbound postMessages must come from the allowed origin **and** from
  `window.parent`, the one window this frame also posts back to. Origin alone
  would let any other window on the host origin (a second tab, a popup, a nested
  frame) drive the room. The host pins both directions the same way.
* postMessage envelope, both directions: `{v:1, t:<function-name>, args:[…]}`.
  Args are in signature order; inbound also accepts signature-named fields, and
  outbound carries both. `v !== 1` is dropped.
* Origin allow-list on EVERY message, both ways. The embedding page passes its
  origin as `?origin=`; the fallback is `document.referrer`. With neither,
  postMessage I/O is disabled (never `'*'`). Direct same-origin `Chat3D.*` calls
  still work for dev.
* Media bytes (PNG, JPEG or WebP) are fetched and authenticated by the EMBEDDING
  page (Matrix authenticated media). The wasm never sees credentials, matching the
  platform's iframe boundary.

## XR

`scripts/xr_boot.gd` (autoload `XrBoot`):

* **Web** → `WebXRInterface`, `immersive-vr`, reference spaces
  `bounded-floor, local-floor, local` (required: `local-floor`). The session
  request MUST run inside a user gesture, so the in-scene **Enter VR** button is
  the reliable path. `Chat3D.enterVR()` is honoured, but reports `vrState(false)`
  when the browser denies a gesture-less request. The embedding iframe needs
  `allow="xr-spatial-tracking"`.
* **Native (Linux/Windows PCVR)** → OpenXR (project setting), initialised at boot.
  With no runtime it falls back silently to desktop (`xr/openxr/startup_alert=false`).
* **Non-XR fallback** → third-person mouse/WASD (`scripts/local_controller.gd`):
  WASD/arrows walk, right-drag orbits, the wheel zooms, left-click activates
  surfaces, sits on seats or sets a walk target.

The Web preset ships **threads OFF** (no COOP/COEP requirement, so it embeds in the
Matrix client without cross-origin isolation) and **extensions_support OFF** (no
GDExtension). `export_presets.cfg` gives the full rationale.

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

`tests/run_tests.gd` discovers `tests/test_*.gd` and instantiates the **real**
`scenes/room.tscn`. It drives the transports through the controller's own
handlers, which the SSE / postMessage drains also call at runtime. A suite that
asserts nothing, or whose assertion count differs from its `plan()`, is reported
as a failure. The whole run has a 180 s deadline, so a wedged coroutine is a red
build rather than a hung one. `games/_shared/test/chat3d-envelope.test.mjs` covers
the JS half, which a headless Godot run can never execute
(`JavaScriptBridge.eval` needs a browser). See [`tests/README.md`](tests/README.md).

## Registration

Migration `056-shadows-of-the-net-federation.xml` adds:

* `games` and `chapters` rows (`asset_url=/_remotes/wasm-fragment/shadows-of-the-net/index.html`);
* a `federation_modules` row, `kind='wasm-game'`, metadata
  `{"sse":true,"hostAuthoritative":true,"engine":"godot","xr":true}`;
* `game_access scope='all'`;
* `game_event_support` rows.

The `/_remotes/wasm-fragment/` nginx location is generic (both `nginx.conf` and
`nginx-tls.conf`), so no per-slug proxy block is needed.
