# 027 — Biome themes, real 3D maker worlds, and per-biome Godot e2e

## Context

The RPG maker (decision [018](018-rpg-and-vn-makers.md)) could define
areas, rooms, exits, NPCs and items, and export a Godot Web bundle. But the
generated rooms were two floating `Label3D` nodes: a black screen in a
real export. `rpg_rooms.environment` was an unused "colour hint" int.
The generated tree had no player, no movement and no multiplayer wiring.
A maker-built game could not even launch from the player portal. Its
chapters carry a *relative* `asset_url` like `desert/index.html`, which
the host iframe resolved against the SPA origin, so it silently served
the Angular shell instead of the game.

The goal: a publisher builds, with the browser maker, a game whose rooms
are **real, visually themed 3D worlds** that the player walks through,
with working multiplayer. We **prove it end to end** with a real Godot
export, publish and play, captured as per-biome screenshots and video.

## Decision

### Themes

Six biome themes plus `plain`: **desert, forest, dungeon, cave, town,
castle**. `rpg_areas.theme` (NOT NULL, default `plain`) and an optional
per-room `rpg_rooms.theme` override (Liquibase 006). CC0 textures
(ambientCG, one floor and one wall per biome plus a shared door) are
vendored under `rpg-builder/src/main/resources/biome-assets/`, with
provenance in `manifest.json` and `LICENSES.md`. The export copies only
the themes a project uses into the Godot tree. Each texture gets a minimal
`.import` sidecar (`compress/mode=1` lossy webp, `mipmaps` on). The packed
textures come out ~5× smaller than Godot's headless 2D default and do not
shimmer at floor-grazing camera angles.

### Real 3D rooms

`RpgGodotProjectRenderer` bakes each room into a deterministic
`const ROOM` dictionary. A hand-written `room_builder.gd` turns it into a
textured `StaticBody3D` floor, gapped perimeter walls (plus ceilings for
cave and dungeon), a per-theme `WorldEnvironment` and `DirectionalLight`,
seeded props, and one `Area3D` **exit portal** per exit. Cave has warm
brown fog and dungeon has cold blue fog, far apart on purpose so the e2e
can tell biomes apart. `plain` still gets a floor, light and environment:
the renderer never emits a black room again. All geometry positions
(portals, wall gaps, arrival spawns) come from **one shared
direction→(wall, vector, inward-spawn) table**, so they cannot disagree.

### Movement through levels

Keys no longer jump rooms. `world.gd` drops its key handler. The generated
`game_bridge.gd` (ported from Three Wizards) posts controls to
`/api/controls/{characterId}`, and the SSE echo drives the local
`player.gd` (`CharacterBody3D` plus a third-person camera). A body that
walks into an exit portal `Area3D` fires a `call_deferred` room transition
(verified `body == local Player`; the spawn sits inward past the reciprocal
portal, so there is no ping-pong). On every transition the bridge posts an
`ENTER_ROOM:<uuid>` control. That is both the server-observable proof of
movement through levels and the signal remote avatars use to scope
visibility.

### Multiplayer

The stock `HugMeshMultiplayerLobby` is unusable in web mode: the BFF
`PlayerMovement` payload has no `slot` field, and late joiners never spawn.
Generated games therefore **drop that node** and use a generated
`remote_avatars.gd` keyed on `characterId`. It spawns a distinct avatar the
first time it sees movement from a non-local character. It drives the
avatar with the same action/pressed velocity core as the local player, so
both clients render the same motion instead of keypress-quantized
teleports. It hides avatars whose last `ENTER_ROOM` room differs.
`player.gd` filters movement to the local `characterId`, so one player's
input cannot drive another's body.

### FF-style town

Town areas get grid-placed buildings (`rpg_buildings`, 10×10 grid) with
enterable interiors, multiple floors and basements (`rpg_rooms.building_id`
+ `floor_level`, with auto-created stair `UP`/`DOWN` exits). Shops come in
the canonical Final Fantasy kinds (item/weapon/armour/magic/accessory),
plus inn/tavern/church/house. Shop stock (`rpg_shop_stock`) drives an
in-game buy/sell panel.

**Shops are game-local (v1).** The main BFF has no economy surface: no
gold column on `player_characters`, no endpoint that *grants* inventory,
and rpg-maker item UUIDs cannot bridge to the BFF's `bigint` `items` (the
`register.sql` policy allowlists no item or currency tables). So shop gold
and inventory live in the exported game. Every buy or sell **also** posts a
`SHOP_BUY:` / `SHOP_SELL:` control, so the server observes the transaction
and a second player can see it, and it emits an `SD_SHOP_EVENT` that the
host relays. The **inn is the one BFF-live economy beat**: "rest"
`PUT /api/characters/{id} {currentHp,currentMp}` (which already exists and
emits a real `character` SSE event) restores HP and MP. A server-authoritative
economy (gold column, grant/buy/sell endpoints, manifest-driven item
registration, `CharacterDto.gold`) is a deliberately scoped follow-up.

**There is no save point.** Positions already persist per
`(character, chapter)` in `player_positions` (decision on realtime
storage), so progress survives without one. Currency is called "gold"
(matching the existing `quests.reward_gold` column), not the
trademark-adjacent "gil".

### Bundle dedup

A real Web export's `index.side.wasm` is ~41 MB. The makers export one
bundle per chapter. Copying a whole bundle per area would blow the BFF's
200 MB upload cap at six areas and heap the worker. Instead the worker
exports **once** and moves the invariant engine files
(`index.{js,wasm,side.wasm,pck,png}`, both audio worklets, the bridge
wasm) into `bundle/_engine/`. It writes each area a one-line patched
`index.html` that points `GODOT_CONFIG.executable`, the `<script src>`, the
`fileSizes` keys, the splash `<img>` and every `gdextensionLibs` entry at
`../_engine/…`. A deduped six-area bundle zips to ~20 MB.

### Launch from the portal

`home.component` now mints a launch on the legacy/maker path and passes on
the game slug. `game-canvas._legacyUrl` builds the uid-gated
`/api/assets/levels/<game>/<uid>/<area>/index.html` with the full session
param set. Boot-shipped games (absolute `asset_url`) are unchanged
byte-for-byte.

## Proof

A seeded fixture, **Six Realms** (`rpg-builder` changelog 007, owned by
`publisher-flatland`), ships all six biomes as areas: Scorched Dunes,
Verdant Wildwood, Sunken Halls, Echoing Caverns, Bramblewick (the town) and
Highspire Keep. They are chained so you can walk the whole world, and the
town is a full FF-style town.

`make e2e-godot` brings the TLS stack up with real Godot exports
(`docker-compose.godot.yml`, `E2E_GODOT_ENABLED=true`) and runs the
`@godot`-tagged BDD suite in `e2e/features/makers/biome-worlds.feature`.
It does three things:

- A real build and publish.
- A per-biome explore scenario. It launches the chapter through the actual
  portal UI and asserts that the WASM iframe rendered a non-black,
  biome-distinct world (compositor screenshot plus `pngjs` pixel analysis;
  canvas `getImageData` reads blank because Godot 4.6 web exports do not
  set `preserveDrawingBuffer`). It drives real WASD and asserts the
  movement SSE.
- A two-player scenario that proves one player sees the other's avatar move
  in the same session.

`docs/publishing/screenshots/harvest-from-e2e.py` copies the per-step PNGs
into `docs/publishing/screenshots/build/` and the full webm per scenario
into `docs/publishing/videos/`.

## Consequences

- The maker's item-in-world model is still shop-only (`RpgItem` has no
  room FK). Loose pickups are a follow-up.
- Building floor counts are immutable after create. A resize would have to
  reconcile the auto-created interior rooms.
- The exported bundle depends on the `hugmesh_bridge` wasm being staged
  (`app.build.bridge-dir`). Without it a generated game loads, but movement
  and portals are inert. `world.gd` then shows an on-screen warning, so a
  misconfigured build can be diagnosed from a screenshot.
