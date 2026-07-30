# 027 — Biome themes, real 3D maker worlds, and per-biome Godot e2e

## Context

The RPG maker (decision [018](018-rpg-and-vn-makers.md)) could define
areas/rooms/exits/NPCs/items and export a Godot Web bundle, but the
generated rooms were two floating `Label3D` nodes — a black screen in a
real export. `rpg_rooms.environment` was an unused "colour hint" int.
There was no player, no movement, no multiplayer wiring in the generated
tree, and a maker-built game could not even launch from the player
portal (its chapters carry a *relative* `asset_url` like
`desert/index.html`, which the host iframe resolved against the SPA
origin and silently served the Angular shell instead of the game).

The goal of this decision: a publisher builds, with the browser maker, a
game whose rooms are **real, visually-themed 3D worlds** the player walks
through, with working multiplayer, and we **prove it end to end** with a
real Godot export, publish, and play — captured as per-biome screenshots
and video.

## Decision

### Themes

Six biome themes plus `plain`: **desert, forest, dungeon, cave, town,
castle**. `rpg_areas.theme` (NOT NULL default `plain`) and an optional
per-room `rpg_rooms.theme` override (Liquibase 006). CC0 textures
(ambientCG, one floor + one wall per biome + a shared door) are vendored
under `rpg-builder/src/main/resources/biome-assets/` (provenance in
`manifest.json` + `LICENSES.md`) and copied — only for the themes a
project actually uses — into the exported Godot tree, each with a minimal
`.import` sidecar (`compress/mode=1` lossy webp, `mipmaps` on) so the
packed textures are ~5× smaller than Godot's headless 2D default and
don't shimmer at floor-grazing camera angles.

### Real 3D rooms

`RpgGodotProjectRenderer` now bakes each room into a deterministic
`const ROOM` dictionary that a hand-written `room_builder.gd` turns into
a textured `StaticBody3D` floor, gapped perimeter walls (+ ceilings for
cave/dungeon), a per-theme `WorldEnvironment` + `DirectionalLight`
(cave = warm brown fog, dungeon = cold blue fog — deliberately far apart
so the e2e can tell biomes apart), seeded props, and one `Area3D` **exit
portal** per exit. `plain` still gets a floor/light/environment — the
renderer never emits a black room again. All geometry positions
(portals, wall gaps, arrival spawns) derive from **one shared
direction→(wall, vector, inward-spawn) table** so they can't disagree.

### Movement through levels

Keys no longer jump rooms. `world.gd` removes its key handler; the
generated `game_bridge.gd` (ported from Three Wizards) posts controls to
`/api/controls/{characterId}`, and the SSE echo drives the local
`player.gd` (`CharacterBody3D` + third-person camera). Walking a body
into an exit portal `Area3D` fires a `call_deferred` room transition
(verified `body == local Player`, spawn offset inward past the reciprocal
portal so there is no ping-pong). On every transition the bridge posts an
`ENTER_ROOM:<uuid>` control — this is both the server-observable proof of
movement-through-levels and the signal remote avatars use to scope
visibility.

### Multiplayer

The stock `SnapdragonMultiplayerLobby` is unusable in web mode (the BFF
`PlayerMovement` payload has no `slot` field, and late-joiners never
spawn), so generated games **drop that node** and use a generated
`remote_avatars.gd` keyed on `characterId`: it lazily spawns a distinct
avatar the first time it sees movement from a non-local character, drives
it with the same action/pressed velocity core as the local player (so
both clients render the same motion instead of keypress-quantized
teleports), and hides avatars whose last `ENTER_ROOM` room differs.
`player.gd` filters movement to the local `characterId` so one player's
input can't drive another's body.

### FF-style town

Town areas get grid-placed buildings (`rpg_buildings`, 10×10 grid) with
enterable interiors, multiple floors and basements (`rpg_rooms.building_id`
+ `floor_level`, with auto-created stair `UP`/`DOWN` exits), and shops of
the canonical Final Fantasy kinds (item/weapon/armour/magic/accessory)
plus inn/tavern/church/house. Shop stock (`rpg_shop_stock`) drives an
in-game buy/sell panel.

**Shops are game-local (v1).** The main BFF has no economy surface — no
gold column on `player_characters`, no endpoint that *grants* inventory,
and rpg-maker item UUIDs cannot bridge to the BFF's `bigint` `items` (the
`register.sql` policy allowlists no item/currency tables). So shop gold
and inventory live in the exported game; every buy/sell **also** posts a
`SHOP_BUY:/SHOP_SELL:` control so the transaction is server-observed and
visible to a second player, and emits an `SD_SHOP_EVENT` the host relays.
The **inn is the one genuinely BFF-live economy beat**: "rest"
`PUT /api/characters/{id} {currentHp,currentMp}` (which already exists and
emits a real `character` SSE event) restores HP/MP. A server-authoritative
economy (gold column, grant/buy/sell endpoints, manifest-driven item
registration, `CharacterDto.gold`) is a deliberately-scoped follow-up.

**No save point** is provided: positions already persist per
`(character, chapter)` via `player_positions` (decision on realtime
storage), so progress survives without one. Currency is called "gold"
(matching the existing `quests.reward_gold` column), not the
trademark-adjacent "gil".

### Bundle dedup

A real Web export's `index.side.wasm` is ~41 MB; copying a whole bundle
per area (the makers export one bundle-per-chapter) would blow the BFF's
200 MB upload cap at six areas and heap the worker. Instead the worker
exports **once**, moves the invariant engine files
(`index.{js,wasm,side.wasm,pck,png}`, both audio worklets, the bridge
wasm) into `bundle/_engine/`, and writes each area a one-line patched
`index.html` pointing `GODOT_CONFIG.executable`, the `<script src>`, the
`fileSizes` keys, the splash `<img>`, and every `gdextensionLibs` entry
at `../_engine/…`. A deduped six-area bundle zips to ~20 MB.

### Launch from the portal

`home.component` now mints a launch on the legacy/maker path and threads
the game slug; `game-canvas._legacyUrl` builds the uid-gated
`/api/assets/levels/<game>/<uid>/<area>/index.html` with the full session
param set. Boot-shipped games (absolute `asset_url`) are unchanged
byte-for-byte.

## Proof

A seeded fixture **Six Realms** (`rpg-builder` changelog 007, owned by
`publisher-flatland`) ships all six biomes as areas — Scorched Dunes,
Verdant Wildwood, Sunken Halls, Echoing Caverns, Bramblewick (the town),
Highspire Keep — chained so you can walk the whole world, with a full
FF-style town.

`make e2e-godot` brings the TLS stack up with real Godot exports
(`docker-compose.godot.yml`, `E2E_GODOT_ENABLED=true`) and runs the
`@godot`-tagged BDD suite in `e2e/features/makers/biome-worlds.feature`:
a real build→publish, then a per-biome explore scenario that launches the
chapter through the actual portal UI, asserts the WASM iframe rendered a
non-black, biome-distinct world (compositor screenshot + `pngjs` pixel
analysis; canvas `getImageData` reads blank because Godot 4.6 web exports
don't set `preserveDrawingBuffer`), drives real WASD and asserts the
movement SSE, and a two-player scenario proving one player sees the
other's avatar move in the same session. Per-step PNGs and a full webm
per scenario are harvested into `docs/publishing/`.

## Consequences

- The maker's item-in-world model is still shop-only (`RpgItem` has no
  room FK); loose pickups are a follow-up.
- Building floor counts are immutable after create (a resize would have
  to reconcile auto-created interior rooms).
- The exported bundle depends on the `snapdragon_bridge` wasm being
  staged (`app.build.bridge-dir`); without it a generated game loads but
  movement/portals are inert, and `world.gd` surfaces an on-screen
  warning so a mis-configured build is diagnosable from a screenshot.
