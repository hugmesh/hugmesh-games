# Three Wizards In A Trenchcoat

A fantasy dungeon-crawler built on **Rust + Godot 4** (WebGL/WASM), served through an **Angular** frontend, with a **Spring Boot** backend that delivers game state and controls via streaming SSE. Authentication is handled by **Casdoor** (OAuth2/OIDC) behind a custom Angular login page — the Casdoor admin UI is never exposed to the network.

> Branch policy: please work on your own branches and open PRs rather than committing directly to `main`.

---

## Quick links

| Resource | Link |
|---|---|
| Issue Tracker | [ThreeWizardsInATrenchcoat/issues](https://github.com/Nin-Crowley/ThreeWizardsInATrenchcoat/issues) |
| Pull Requests | [ThreeWizardsInATrenchcoat/pulls](https://github.com/Nin-Crowley/ThreeWizardsInATrenchcoat/pulls) |
| Godot developer guide | [docs/GODOT_DEVELOPER_GUIDE.md](docs/GODOT_DEVELOPER_GUIDE.md) |

---

## Content hierarchy — games → levels → scenes

The project is organised in three tiers:

```
Game (umbrella)            e.g. "Three Wizards Saga"
  └── Level (one WASM)     e.g. "The Enchanted Cave", "Open Field"
       └── Scene elements  fairies, lights, geometry, NPCs
            + Level assets  custom textures / plugins / sounds
```

- A **Game** is the top-level grouping shown on the home page.
- Each **Level** inside a game is its own compiled Godot WASM bundle, served from
  `/api/assets/levels/{slug}/`. Level transitions unload the current WASM and mount
  the next one in the Angular iframe (see "Level transitions" below).
- **Scene elements** and user-registered **level assets** (textures, plugins,
  sounds) are fetched by the running Godot bundle from the API at load time.
- Levels gate on completed **Quests** via `level_prerequisites`: complete all
  prerequisite quests → the next level unlocks.

Adding levels and assets is a documented workflow — see the
[Godot developer guide](docs/GODOT_DEVELOPER_GUIDE.md).

---

## Architecture

Up to 3 players may be connected simultaneously; exactly one holds the **controller** role and can send movement input. All game state changes originate in the Spring Boot API and propagate to every connected client over a single SSE stream that carries five typed event channels.

```
  Browser / Player (up to 3)
  ──────────────────────────────────────────────────────────────────────────────
  Angular :4200 (nginx in Docker)
  ┌─────────────────────────────────────────────────────────────────────────┐
  │  LoginComponent          AuthService             HTTP Interceptor        │
  │  [username / password]──►POST /api/auth/token──►sessionStorage JWT       │
  │                                                  Bearer: <token>         │
  │  AuthGuard (isAuthenticated?)                    on every /api/ request  │
  │                                                                          │
  │  GameCanvasComponent                                                     │
  │  <iframe src="/api/assets/games/cave/index.html?game_id=N">             │
  │      │                                                                   │
  │      │   Godot WASM (game_bridge.gd)          scene_loader.gd           │
  │      │   JavaScriptBridge.eval(EventSource)   applies scene config       │
  │      │   ──────────────────────────────────►  spawns lights/fairies/geo  │
  │      │                                        _on_time_updated() rotates │
  │      │                                        DirectionalLight3D (sun)   │
  │      │                                                                   │
  │  GameControls (Angular)                                                  │
  │  [W/A/S/D + HUD buttons]──────────────────────────────────────────────► │
  └──────────────────────────────────────────────┬──────────────────────────┘
                                                 │ /api/* (nginx reverse proxy)
  ─────────────────────────────────────────────  │  ───────────────────────────
  Spring Boot API :8080                          ▼
  ┌──────────────────────────────────────────────────────────────────────────┐
  │                                                                          │
  │  CasdoorAuthController          SecurityConfig (WebFlux)                 │
  │  POST /api/auth/token ─────────►JWT RS256 via Casdoor JWKS               │
  │  (ROPC proxy; client_secret     /api/auth/**  → permitAll                │
  │   stays server-side)            /api/sessions/**                         │
  │                                 /api/characters/**  → ROLE_USER          │
  │                                 /api/admin/**       → ROLE_ADMIN         │
  │                                                                          │
  │  GameStreamController                                                    │
  │  GET /api/game/stream ──────────────────────────────────────────────────►│
  │    Flux.merge(movement$, world$, character$, time$, scene$)              │
  │    + 30 s keepalive comment                                              │
  │    ─────────────────────────────────────────────────────────────────►   │
  │    event: movement   {"x","y","z","rotationY","action","pressed"}        │
  │    event: world      {"sessionId","tick","timestamp"}                    │
  │    event: character  {"health","mana","level","xp","xp_to_next"}        │
  │    event: time       {"dayNumber","hour","minute"}                       │
  │    event: scene      {"sceneId","sceneName","sceneType"}                 │
  │                                                                          │
  │  ControlController                 GameStateService                      │
  │  POST /api/controls/{charId} ─────►Sinks.many().replay().limit(1)        │
  │  (validates X-Casdoor-Id           applyControl() mutates PlayerState    │
  │   == controller session)           emits to all SSE subscribers          │
  │                                                                          │
  │  SessionController      ┌─────────────────────────────────────────────┐ │
  │  /sessions/connect      │  PostgreSQL  (R2DBC reactive + JDBC Liquibase│ │
  │  /sessions/control  ───►│  games           player_sessions             │ │
  │  /sessions/me           │  scene_elements  player_positions  (NOTIFY)  │ │
  │                         │  player_characters               skills      │ │
  │  CharacterController    │  character_skills  character_items           │ │
  │  SkillController    ───►│  character_equipment  character_quests        │ │
  │  ItemController         │  game_time_state     quests                  │ │
  │  QuestController        └──────────────────┬──────────────────────────┘ │
  │                                            │                             │
  │  PgNotificationService ◄───────────────────┘ LISTEN game_events         │
  │  (player_positions INSERT/UPDATE            pg_notify on trigger)        │
  │   → emits to GameStateService Sinks)                                    │
  │                                                                          │
  │  GameTimeService                                                         │
  │  Scheduler (every 10 s real = 1 game-minute)                            │
  │  persists day/hour/minute → emits time$ events                          │
  │                                                                          │
  │  AssetController                                                         │
  │  GET /api/assets/games/{slug}/{file} → ClassPathResource                │
  │  (Godot WASM bundle: index.html, index.wasm, index.pck, …)              │
  │                                                                          │
  └──────────────────────────────────────────────────────────────────────────┘
                    │ internal Docker network only
  ──────────────────┼──────────────────────────────────────────────────────────
  Casdoor :8000     ▼   (no host port binding — unreachable from browser)
  ┌──────────────────────────────────────────────────────────────────────────┐
  │  entrypoint.sh seeds init_data.json from env vars (or random defaults)  │
  │  Prints client-id / client-secret / passwords to container logs         │
  │                                                                          │
  │  POST /api/login/oauth/access_token  ← ROPC grant (proxied by API)      │
  │  GET  /api/get-jwks                  ← RS256 public keys for JWT verify │
  └──────────────────────────────────────────────────────────────────────────┘
```

### Key architectural decisions

**Single WASM bundle, multiple scenes.**
Both `cave` and `field` are served from the same Godot export at `/api/assets/games/cave/`. The `?game_id=N` query param is read by `game_bridge.gd`, which fetches `/api/games/{N}/scene` to get the scene config and routes to the correct `.tscn` file. Adding a new scene only requires a DB row and a new Godot scene file — no re-export.

**All controls go through the API first.**
Neither Angular nor Godot mutates position directly. Every keypress POSTs to `/api/controls/{charId}`; the resulting state comes back over SSE and drives both the Angular HUD and Godot movement. The controller role is enforced server-side via `player_sessions.is_controller`.

**Five typed SSE channels on one stream.**
`GameStreamController` merges `Sinks.Many` from `GameStateService`, `GameTimeService`, and `PgNotificationService` into a single `Flux<ServerSentEvent>`. Each event carries a typed `event:` field (`movement`, `world`, `character`, `time`, `scene`). `game_bridge.gd` registers one `addEventListener` per type — no event parsing gymnastics.

**Casdoor kept internal.**
`docker-compose.yml` exposes no host port for Casdoor. The Angular app never redirects to Casdoor's React UI — a custom form POSTs to `/api/auth/token`, which proxies the OAuth2 ROPC grant server-side, keeping `client_secret` off the browser.

**Reactor sink is `replay().limit(1)`.**
`Sinks.many().multicast().onBackpressureBuffer()` terminates silently when all subscribers disconnect in Reactor 3.7. `replay().limit(1)` is a hot sink that never terminates and replays the latest state to new subscribers on connect.

**R2DBC + JDBC coexistence.**
Runtime data access uses non-blocking R2DBC. Liquibase schema migrations need a blocking JDBC `DataSource`. Spring Boot 3 skips `DataSourceAutoConfiguration` when an R2DBC `ConnectionFactory` bean is present, so `JdbcDataSourceConfig` creates an explicit `@Primary HikariDataSource` for Liquibase only (pool size 3).

---

## Data model

### SSE event types — `GET /api/game/stream`

| Event name | Payload fields | Emitted when |
|---|---|---|
| `movement` | `x`, `y`, `z`, `rotationY`, `action`, `pressed` | Control applied / position updated |
| `world` | `sessionId`, `tick`, `timestamp` | Heartbeat (1 s) |
| `character` | `health`, `mana`, `level`, `xp`, `xp_to_next` | Stats change |
| `time` | `dayNumber`, `hour`, `minute` | Every game-minute (~10 s real) |
| `scene` | `sceneId`, `sceneName`, `sceneType` | Scene transition |

### `ControlEvent` — `POST /api/controls/{characterId}` body

| Field | Type | Values |
|---|---|---|
| `action` | `String` | `MOVE_FORWARD`, `MOVE_BACKWARD`, `MOVE_LEFT`, `MOVE_RIGHT`, `JUMP`, `INTERACT`, `CAST_SPELL_1/2/3` |
| `pressed` | `boolean` | `true` = key down, `false` = key up |
| `timestamp` | `long` | Client epoch-ms |

### `SceneDto` — `GET /api/levels/{id}/scene` response

| Field | Type | Description |
|---|---|---|
| `id` | `Long` | Level ID |
| `name` | `String` | Display name |
| `slug` | `String` | URL slug (matches classpath directory) |
| `sceneType` | `String` | `cave`, `field`, … |
| `assetUrl` | `String` | Path to this level's Godot WASM bundle entry-point |
| `elements` | `List<SceneElementDto>` | Scene elements with positions, rotations, properties |

### Level transitions — `TW_LEVEL_TRANSITION` postMessage

When a running Godot WASM needs to hand off to another level's bundle:

```
Godot (inside iframe)
  $GameBridge.request_level_transition("field", 2)
    │
    ▼  postMessage({type:"TW_LEVEL_TRANSITION", slug:"field", levelId:2})
Angular host (game-canvas.component.ts)
  HostListener('window:message') catches payload
    │
    ▼  GameLibraryService.getLevel(2) → resolve fresh assetUrl
  iframe.src = `${level.assetUrl}?level_id=2`
    │
    ▼  Godot bundle cold-boots, fetches /api/levels/2/scene
```

The previous WASM is fully unloaded — persist any in-memory state via the API
(`PUT /api/characters/{id}` etc) *before* calling `request_level_transition`.

---

## API reference

Notation used in the marble diagrams below:

```
──────────────────────────────────────────────►  time axis

Mono<T>  (0 or 1 item, then complete)
   ──────────●─|

Flux<T>  (0..N items, then complete)
   ──●──●──●──●──|

ServerSentEvent stream  (unbounded — never completes unless client disconnects)
   ──●────●────●────●──⋯

Error / 4xx response
   ──────────✗

Empty → switchIfEmpty(404)
   ─────────────────────✗  404
```

---

### `AuthController` — `/api/auth`  *(public)*

#### `POST /api/auth/token`

Exchanges username + password for a Casdoor OAuth2 access token via the Resource Owner Password Credentials grant. The `client_secret` is injected server-side — the browser never sees it.

```
Client                   API                            Casdoor :8000
  │                       │                                  │
  │  {username, password}  │                                  │
  │──────────────────────►│                                  │
  │                       │  form: grant_type=password       │
  │                       │        client_id, client_secret  │
  │                       │  ────────────────────────────────►
  │                       │                                  │
  │                       │  ◄────── {access_token, …} ─────│
  │                       │                                  │
  │  {access_token,       │
  │   token_type,         │
  │   expires_in}         │
  │◄──────────────────────│

Reactor chain:
  Mono.just(req)
   ──●──► WebClient.post("/api/login/oauth/access_token")
           .bodyValue(form)
           .retrieve()
           .bodyToMono(Map)
   ──────────────────────────●─|
                              {access_token, token_type, expires_in, id_token}
```

---

### `GameStreamController` — `/api/game`  *(public)*

#### `GET /api/game/stream`

Multiplexed SSE stream. Five reactive sources are merged into a single connection; each event carries an `event:` type field so clients can register per-type listeners.

```
movement$  (Sinks replay):   ──●──────────────●────────────●──⋯
world$     (1 s heartbeat):  ──●──●──●──●──●──●──●──●──●──●──⋯
character$ (stat changes):   ────────────●────────────────────⋯
time$      (every ~10 s):    ────────────────────●────────────⋯
scene$     (transitions):    ──●──────────────────────────────⋯
keepalive  (30 s comment):   ─────────────────────────────────⋯

Flux.merge(movement$, world$, character$, time$, scene$)
  .mergeWith(Flux.interval(30s).map(_ -> comment))
  ──●──●──●──●──●──●──●──●──●──●──●──●──●──●──●──⋯
    │movement  │world  │character  │time  │scene
    │          │heartbeat every 1s │
    └──────────────────────────────┘
         ServerSentEvent<Object>
```

#### `GET /api/game/state`

Snapshot of the current game state without subscribing to the stream.

```
──request──► Mono.just(gameStateService.currentState())
 ──────────────────────────●─|
                            {snapshot}
```

---

### `GameController` — `/api/games`  *(public)*

Umbrella tier. Each game groups one or more Levels.

#### `GET /api/games` · `GET /api/games/{id}`

```
──request──► gameRepo.findAllByOrderBySortOrderAsc()
 ──────────────●──●──|  Game{id, name, slug, sortOrder, …}
```

#### `GET /api/games/{id}/levels`

Returns every Level inside the umbrella game, ordered by sort_order.

```
──request──► levelRepo.findByGameIdOrderBySortOrderAsc(id)
 ──────────────●──●──|  Level{id, slug, assetUrl, sceneType, …}
```

#### `GET /api/games/available/{characterId}`

Nested structure — every umbrella game with its levels, each level annotated
with `unlocked` / quest-progress for the caller's character. Drives the home
page games-with-levels drilldown.

```
──request──► charQuests.findByCharacterId
              ──●──► games.findAll().flatMap(g ->
                      levels.findByGameId(g.id)
                        .flatMap(build AvailableLevelDto)
                        .collectList()
                      .map(ls -> GameWithLevelsDto))
 ──────────────●──●──|
```

### `LevelController` — `/api/levels`  *(public)*

One level = one compiled Godot WASM bundle.

#### `GET /api/levels` · `GET /api/levels/{id}`

```
──request──► levelRepo.findAllByOrderBySortOrderAsc()
 ──────────────●──●──|  Level…
```

#### `GET /api/levels/{id}/scene`

The descriptor consumed by `scene_loader.gd` at bundle startup.

```
──request──► levels.findById(id)
              ──●──► sceneElements.findByLevelIdOrderBySortOrder(id)
                      ──●──●──● collectList()
                                ──●─|  SceneDto{…, assetUrl, elements:[…]}
```

### `LevelAssetController` — `/api/levels/{levelId}/assets`  *(public)*

Registry for user-added textures / plugins / sounds attached to a level. GDScript
uses labels to look up assets without hardcoding filenames.

#### `GET /api/levels/{levelId}/assets`

```
──request──► levelAssetRepo.findByLevelId(levelId)
 ──────────────●──●──|  LevelAsset{label, assetType, filePath, …}
```

#### `GET /api/levels/{levelId}/assets/by-label/{label}`

```
──request──► levelAssetRepo.findByLevelIdAndLabel(levelId, label)
              │
     ┌────────┴────────┐
     │ found           │ empty
     ▼                 ▼
  ──●─|               ──✗ 404
     LevelAsset
```

#### `POST /api/levels/{levelId}/assets`

```
body: {label, assetType, filePath, contentType, sizeBytes}
──request──► repo.save(LevelAsset{…})
 ──────────────────────────●─|  LevelAsset (with id, uploadedAt)
```

#### `DELETE /api/levels/{levelId}/assets/{assetId}`

```
──request──► repo.deleteById(assetId)
 ──────────────────────────●─|  (empty body)
```

---

### `AssetController` — `/api/assets`  *(public)*

#### `GET /api/assets/levels/{slug}/{filename}`

**Primary path** for per-level WASM bundles. Each level's compiled files live under
`classpath:assets/levels/{slug}/`. The Angular iframe mounts `index.html` from here
and Godot fetches its `.wasm`/`.pck` from the same directory.

#### `GET /api/assets/{filename}` · `GET /api/assets/games/{slug}/{filename}`

Legacy / root-level static assets. The `games/{slug}/` form is kept for
backward-compat with content that hasn't been moved to the `levels/` hierarchy yet.

```
──request──► Mono.fromCallable(() -> ClassPathResource(path))
                   │
         ┌─────────┴──────────┐
         │ exists             │ missing
         ▼                    ▼
  .contentType(mediaType)    ResponseEntity.notFound()
  .body(resource)
  ──────────────●─|           ──────✗  404
```

Supported extensions → content-types:

| ext | type |
|---|---|
| `html` | `text/html` |
| `js` | `application/javascript` |
| `wasm` | `application/wasm` |
| `pck` | `application/octet-stream` |
| `png`/`jpg` | `image/png` · `image/jpeg` |
| `glb`/`gltf` | `model/gltf-binary` · `model/gltf+json` |

---

### `ControlController` — `/api/controls`  *(public — validated by session)*

#### `POST /api/controls/{characterId}`

Accepts control events. Validates that the `X-Casdoor-Id` header matches the session marked `is_controller = true`. Non-controllers receive `403`.

```
──request──► sessionService.isController(casdoorId, characterId)
                   │
         ┌─────────┴──────────────┐
         │ is controller          │ not controller
         ▼                        ▼
  gameStateService                ──✗  403 Forbidden
  .applyControl(event)
         │
  Sinks.many().emitNext(newState)
         │
  ──────●─|  ResponseEntity<Void> 200
```

---

### `SessionController` — `/api/sessions`  *(authenticated)*

#### `POST /api/sessions/connect`

Creates or resumes a player session, enforcing the 3-player cap.

```
──request──► sessionRepo.countActiveSessions()
                   │
         ┌─────────┴──────────┐
         │ count < 3          │ count = 3
         ▼                    ▼
  createOrResumeSession()    ──✗  409 Conflict
  (first joiner auto-claims
   controller role)
         │
  ──────●─|  {sessionToken, isController, playerCount}
```

#### `POST /api/sessions/control`

Attempts to claim the exclusive controller role.

```
──request──► sessionRepo.countByIsControllerTrue()
                   │
         ┌─────────┴──────────┐
         │ count = 0          │ count > 0
         ▼                    ▼
  setController(true)    ──────●─|
         │                {claimed: false}
  ──────●─|
  {claimed: true}
```

#### `DELETE /api/sessions/me`

```
──request──► sessionRepo.deleteByCasdoorId(casdoorId)
 ──────────────────────────●─|  (no body)
```

---

### `CharacterController` — `/api/characters`  *(authenticated)*

#### `GET /api/characters`

```
──request──► characterRepo.findAll()
              ──map(toDto)──►
 ──────────────●──●──●──●──|
               CharacterDto…
```

#### `GET /api/characters/{id}`

```
──request──► characterRepo.findById(id)
                   │
         ┌─────────┴──────────┐
         │ found              │ empty
         ▼                    ▼
  map(toDto)           switchIfEmpty(404)
  ──────●─|                   ──✗
```

#### `PUT /api/characters/{id}`

Patches HP, MP, level, XP, ability stats, and current scene, then notifies the SSE character channel.

```
──request──► characterRepo.findById(id)
              ──●──► map(applyPatch)
                      ──●──► characterRepo.save()
                              ──●──► gameStateService.notifyCharacterChange()
 ──────────────────────────────────────────────●─|
                                              CharacterDto (updated)
```

---

### `SkillController` — `/api/skills`  *(authenticated)*

#### `GET /api/skills`

```
──request──► skillRepo.findAll()
              ──map(toDto)──►
 ──────────────●──●──●──●──|
               SkillDto…
```

#### `GET /api/skills/character/{characterId}`

```
──request──► characterSkillRepo.findByCharacterId(id)
              ──flatMap(cs -> skillRepo.findById(cs.skillId))
              ──map(toDto)──►
 ──────────────●──●──●──|
               SkillDto (learned skills only)
```

#### `GET /api/skills/character/{characterId}/slots`

```
──request──► slotRepo.findByCharacterIdOrderBySlotNumber(id)
              ──map(slot -> {slotNumber, skill or null})──►
 ──────────────●──●──●──●──●──|
               {slot: 1, skill: {…}}
               {slot: 2, skill: null}   (empty slot)
               …
```

#### `PUT /api/skills/character/{characterId}/slots/{slot}`

```
──request──► slotRepo.save(CharacterSkillSlot{charId, slot, skillId})
 ──────────────────────────●─|  (no body)
```

---

### `ItemController` — `/api/items`  *(authenticated)*

#### `GET /api/items/character/{characterId}/inventory`

```
──request──► charItemRepo.findByCharacterId(id)
              ──flatMap(ci -> itemRepo.findById(ci.itemId)
                               .map(item -> ItemDto{…, quantity: ci.quantity}))
 ──────────────●──●──●──|
               ItemDto (with quantity)…
```

#### `GET /api/items/character/{characterId}/equipment`

```
──request──► charEquipRepo.findByCharacterId(id)
              ──flatMap(ce -> itemRepo.findById(ce.itemId))
              ──map(item -> {slot, item or null})──►
 ──────────────●──●──●──●──●──|
               {slot: "head",    item: {…}}
               {slot: "chest",   item: null}  (empty)
               …
```

#### `PUT /api/items/character/{characterId}/equipment/{slot}`

```
──request──► charEquipRepo.save(CharacterEquipment{charId, slot, itemId})
 ──────────────────────────●─|  (no body)
```

---

### `QuestController` — `/api/quests`  *(authenticated)*

#### `GET /api/quests`

```
──request──► questRepo.findAll()
              ──map(toDto)──►
 ──────────────●──●──●──|
               QuestDto…
```

#### `GET /api/quests/character/{characterId}`

```
──request──► charQuestRepo.findByCharacterId(id)
              ──flatMap(cq -> questRepo.findById(cq.questId)
                               .map(q -> QuestDto{…, status: cq.status,
                                                     completedAt: cq.completedAt}))
 ──────────────●──●──●──|
               QuestDto (with character-specific status)…
```

---

### `AdminController` — `/api/admin`  *(ROLE_ADMIN only)*

#### `GET /api/admin/sessions`

```
──request──► sessionRepo.findAll()
 ──────────────●──●──●──|
               SessionDto…
```

#### `DELETE /api/admin/sessions/{casdoorId}`

Force-disconnects a player session regardless of controller status.

```
──request──► sessionRepo.deleteByCasdoorId(casdoorId)
 ──────────────────────────●─|  (no body)
```

#### `GET /api/admin/characters`

```
──request──► characterRepo.findAll()
              ──map(toDto)──►
 ──────────────●──●──●──|
               CharacterDto…
```

#### `PUT /api/admin/characters/{id}`

Same patch pipeline as the player `CharacterController`, without the ownership check.

```
──request──► characterRepo.findById(id)
              ──●──► map(applyPatch)
                      ──●──► characterRepo.save()
 ──────────────────────────────────────●─|
                                      CharacterDto (updated)
```

---

### `GameTimeController` — `/api/time`  *(public)*

#### `GET /api/time`

Returns the current in-game clock state. The `GameTimeService` scheduler advances 1 game-minute every ~10 real seconds and persists to `game_time_state`.

```
GameTimeService scheduler (every 10 s real):
  ──tick──tick──tick──tick──⋯
     │      │      │
  minute++ minute++ day++ (at 23:59)
  persist  emit time$ SSE event

GET /api/time:
──request──► timeRepo.findFirst()
              ──map(toDto)──►
 ──────────────────────────●─|
                            {dayNumber, hour, minute}
```

---

## Database schema

```
games ────────────────────────────────────────────────────  ← umbrella tier
  id, name, description, slug, sort_order,
  thumbnail_url, created_at

levels ───────────────────────────────────────────────────  ← one WASM per row
  id, game_id(→games), name, description, slug,
  scene_type, sort_order, asset_url, scene_config,
  thumbnail_url, created_at

scene_elements ───────────────────────────────────────────
  id, level_id(→levels), element_type, name,
  position_x/y/z, rotation_x/y/z, properties, sort_order

level_assets ─────────────────────────────────────────────  ← user-added content
  id, level_id(→levels), label, asset_type,
  file_path, content_type, size_bytes, uploaded_at
  (unique label per level)

level_prerequisites ──────────────────────────────────────
  id, level_id(→levels), required_quest_id(→quests)

player_characters ────────────────────────────────────────
  id, name, casdoor_id, level, xp, xp_to_next,
  max_hp, current_hp, max_mp, current_mp,
  attack, defense, speed, magic_power,
  current_level_id(→levels), created_at

player_sessions ──────────────────────────────────────────
  id, session_token, casdoor_id,
  character_id(→player_characters),
  is_controller, connected_at, last_ping

player_positions ─────────────────────────────────────────  ◄─ NOTIFY trigger
  id, character_id(→player_characters),
  scene_id(→games), pos_x, pos_y, pos_z,
  rotation_y, updated_at

game_time_state ──────────────────────────────────────────
  id, day_number, hour, minute,
  real_started_at, last_persisted_at

skills ───────  character_skills ───  character_skill_slots
  id, name      character_id          character_id, slot_number
  skill_type    skill_id              skill_id
  mana_cost     learned_at

items ───────  character_items ─────  character_equipment
  id, name      character_id          character_id, slot
  item_type     item_id, quantity      item_id

quests ──────  quest_objectives ────  character_quests
  id, name      quest_id              character_id, quest_id
  reward_xp     description           status, started_at
  reward_gold   required_count        completed_at
```

---

## Godot asset pipeline

Each level compiles to its **own** WASM bundle. `export_presets.cfg` holds one
preset per level; the Makefile loops over `GODOT_LEVELS` and runs
`godot --headless --export-release "<preset>"` for each one, with the output
going under `backend/src/main/resources/assets/levels/<slug>/`.

```
make build-godot
  │
  ├─ cargo build --release     → godot-project/rust_extension/bin/libthree_wizards.so
  │
  ├─ for preset in $(GODOT_LEVELS):
  │    godot --headless --export-release "<preset>"
  │       → backend/src/main/resources/assets/levels/<slug>/
  │          index.html  index.wasm  index.pck  index.js  index.side.wasm
  │    scripts/patch-godot-html.py <slug>/index.html <slug>/index.side.wasm
  │
  └─ ✓ all levels exported

make seed-db
  │
  └─ scripts/seed-games.sql  (ON CONFLICT (slug) DO UPDATE)
       upserts level rows with asset_url = '/api/assets/levels/<slug>/index.html'

make deploy  =  build-godot + seed-db
  (then rebuild the api image so the new classpath assets ship)
```

Adding a new level touches four places — `export_presets.cfg`, `Makefile`
`GODOT_LEVELS`, the `levels` DB row, and (optionally) `level_prerequisites`.
Full walk-through with naming rules and common mistakes: see the
[Godot developer guide](docs/GODOT_DEVELOPER_GUIDE.md).

### User-added assets (textures, plugins, sounds)

Drop a file into `backend/src/main/resources/assets/levels/<slug>/` then register
it so GDScript can resolve it by label:

```
curl -X POST http://localhost:8080/api/levels/<id>/assets \
  -H 'Content-Type: application/json' \
  -d '{"label":"oak-bark","assetType":"texture",
       "filePath":"/api/assets/levels/field/oak.png",
       "contentType":"image/png"}'
```

From GDScript (editor or web, via the `ApiClient` helper):

```gdscript
var api := ApiClient.new(); add_child(api)
api.resolve_asset(level_id, "oak-bark", func(url):
    # … HTTPRequest the url and apply as albedo_texture …
)
```

Level transitions from inside the game are one call:

```gdscript
$GameBridge.request_level_transition("field", 2)
```

See the [Godot developer guide](docs/GODOT_DEVELOPER_GUIDE.md#mid-game-level-transitions) for the full flow.

---

## Prerequisites

| Tool | Version | Purpose |
|---|---|---|
| Rust + Cargo | stable (1.78+) | GDExtension + optional WASM |
| Godot 4 | 4.6 | Game engine / WebGL export |
| Java (JDK) | 21 | Spring Boot API |
| Node.js | 20 LTS | Angular frontend |
| npm | 10+ | JS package manager |
| Angular CLI | 19 | Frontend build tool |
| Make | any | Build orchestration |
| Docker + Compose | 24+ / v2 | Full stack (Postgres, Casdoor, API, nginx) |
| Emscripten | 3.1+ | *(optional)* Rust → WASM |

---

## Installation — Linux

### 1. Rust + Cargo

```bash
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
source "$HOME/.cargo/env"
cargo --version
```

Optional WASM target:
```bash
rustup target add wasm32-unknown-emscripten
```

### 2. Godot 4.6

Download the **Linux (64-bit)** export from [godotengine.org/download](https://godotengine.org/download/linux/).

```bash
unzip Godot_v4.6-stable_linux.x86_64.zip -d ~/tools/godot
sudo ln -s ~/tools/godot/Godot_v4.6-stable_linux.x86_64 /usr/local/bin/godot
godot --version
```

**Export templates** are required for `make build-godot`:

1. Open Godot → Editor → Manage Export Templates
2. Download templates for **4.6 stable**
3. Or place them manually at `~/.local/share/godot/export_templates/4.6.stable/`

### 3. Java 21

```bash
# Debian/Ubuntu
sudo apt update && sudo apt install -y openjdk-21-jdk

# Fedora/RHEL
sudo dnf install java-21-openjdk-devel

# Arch
sudo pacman -S jdk21-openjdk

# SDKMAN (distro-agnostic)
curl -s "https://get.sdkman.io" | bash
source "$HOME/.sdkman/bin/sdkman-init.sh"
sdk install java 21.0.4-tem
```

### 4. Node.js 20 + npm

```bash
curl -o- https://raw.githubusercontent.com/nvm-sh/nvm/v0.39.7/install.sh | bash
source ~/.bashrc
nvm install 20 && nvm use 20
node --version && npm --version
```

### 5. Angular CLI

```bash
npm install -g @angular/cli@19
ng version
```

### 6. Make

```bash
# Debian/Ubuntu
sudo apt install -y make
# Fedora
sudo dnf install make
# Arch
sudo pacman -S make
```

### 7. Docker + Compose

```bash
# Debian/Ubuntu (official repo)
sudo apt install -y docker.io docker-compose-plugin
sudo usermod -aG docker $USER   # re-login after this
docker compose version
```

### 8. Emscripten (optional)

```bash
git clone https://github.com/emscripten-core/emsdk.git ~/emsdk
cd ~/emsdk && ./emsdk install latest && ./emsdk activate latest
source ./emsdk_env.sh
```

Add `source ~/emsdk/emsdk_env.sh` to your shell profile to persist it.

---

## Installation — Windows

### 1. Rust + Cargo

```powershell
winget install Rustlang.Rustup
# Restart terminal
cargo --version
```

### 2. Godot 4.6

Download the **Windows (64-bit)** zip from [godotengine.org/download](https://godotengine.org/download/windows/).

1. Extract to `C:\tools\godot\`
2. Rename `Godot_v4.6-stable_win64.exe` → `godot.exe`
3. Add `C:\tools\godot` to your `PATH`
4. Open Godot → Editor → Manage Export Templates → Download for **4.6 stable**

### 3. Java 21

```powershell
winget install Microsoft.OpenJDK.21
java -version
```

### 4. Node.js 20 + npm

```powershell
winget install OpenJS.NodeJS.LTS
node --version && npm --version
```

### 5. Angular CLI

```powershell
npm install -g @angular/cli@19
```

### 6. Make

```powershell
# Via Chocolatey (run as Administrator):
choco install make

# Or use Git for Windows — make.exe is at C:\Program Files\Git\usr\bin\
```

### 7. Docker Desktop

Download from [docs.docker.com/desktop/install/windows-install](https://docs.docker.com/desktop/install/windows-install/). Enable WSL 2 backend.

---

## Running with Docker Compose (recommended)

```bash
# First run: Casdoor generates random credentials and prints them to its logs
docker compose up -d

# Check Casdoor seed credentials (client-id, client-secret, admin/player passwords)
docker compose logs casdoor | grep -A 6 "Casdoor seed"

# Open the app
open http://localhost:4200
```

The `api` container waits for both `postgres` (healthcheck) and `casdoor` (healthcheck) before starting. Liquibase runs schema migrations automatically on first boot.

To override credentials:
```bash
CASDOOR_CLIENT_SECRET=mysecret CASDOOR_ADMIN_PASS=mypass docker compose up -d
```

---

## Building the project

### First-time setup

```bash
make install
```

This installs frontend `node_modules` and generates the Maven wrapper.

### Full production build

```bash
make build
```

Order: `cargo build --release` → `godot --export-release` → `patch-godot-html.py` → `mvn package` → `ng build`

### Individual steps

```bash
make build-rust      # Rust GDExtension → godot-project/rust_extension/bin/
make build-godot     # Godot WebGL export + HTML patch → backend classpath assets
make build-backend   # Spring Boot JAR
make build-frontend  # Angular dist
make seed-db         # Upsert game records with asset_url in running Postgres
make deploy          # build-godot + seed-db (then rebuild api image to serve new WASM)
```

### Clean

```bash
make clean
```

---

## Running in development

Open **two terminals**:

**Terminal 1 — Spring Boot API (port 8080):**
```bash
make dev-backend
```

**Terminal 2 — Angular dev server (port 4200):**
```bash
make dev-frontend
```

Open [http://localhost:4200](http://localhost:4200).

> **Important:** Do not use the VS Code Spring Boot Dashboard or the IntelliJ Spring Boot run config to start the backend — these tools start from stale compiled classes. Always use `make dev-backend`, which runs `./mvnw clean spring-boot:run`.

> The Godot game iframe only appears after `make build-godot` has been run at least once. Until then the canvas area will be blank.

---

## Camera and controls

### Camera

- **Third-person** by default. The camera orbits a pivot at shoulder height inside the player node.
- **Mouse wheel** zooms in and out (arm length 0–12 units).
- **Click and drag** on the viewport orbits the camera (yaw and pitch).
- When the arm length drops below 0.5 units the camera automatically switches to **first-person**.
- The player body rotates with the camera yaw so "forward" always matches the camera facing direction.

### Keyboard shortcuts

| Key | Alt | Action |
|---|---|---|
| W | ↑ | Move forward (camera-relative) |
| S | ↓ | Move backward |
| A | ← | Strafe left |
| D | → | Strafe right |
| Space | — | Jump |
| E | — | Interact |
| 1 | — | Cast Spell 1 (Fire) |
| 2 | — | Cast Spell 2 (Ice) |
| 3 | — | Cast Spell 3 (Lightning) |

Keyboard input works both from the Angular page and from inside the Godot canvas. Both paths POST to `/api/controls/{characterId}`. Only the session holding `is_controller = true` has its events applied.

---

## Project structure

```
ThreeWizardsInATrenchcoat/
├── Makefile                              # Master build + seed-db + deploy
├── scripts/
│   ├── patch-godot-html.py              # Patches WASM sizes + scene prefetch
│   └── seed-games.sql                   # Idempotent game record upserts
├── docker-compose.yml                   # postgres + casdoor (internal) + api + frontend
├── docker/
│   └── postgres-init.sql                # Creates casdoor DB alongside threewizards
├── casdoor/
│   ├── conf/app.conf                    # Casdoor config (postgres, origin, initDataFile)
│   ├── init_data.json.tpl               # Org / app / user seed template
│   └── entrypoint.sh                    # Substitutes env vars, generates random secrets
├── game/
│   └── rust/                            # Rust GDExtension crate
│       ├── Cargo.toml
│       └── src/
│           ├── lib.rs                   # Extension entry point
│           ├── cave_scene.rs            # CaveScene node
│           ├── player.rs                # PlayerController stub
│           └── game_state.rs            # GameStateBridge stub
├── godot-project/                       # Godot 4.6 project
│   ├── project.godot
│   ├── export_presets.cfg               # Web export → backend classpath assets
│   ├── scenes/
│   │   ├── cave.tscn                    # Cave: CSG geometry + player + camera rig
│   │   └── field.tscn                   # Open field: procedural sky + terrain
│   ├── scripts/
│   │   ├── camera_controller.gd         # Orbit camera: scroll zoom, drag orbit, FP/3P
│   │   ├── fairy.gd                     # Floating fairy particle behaviour
│   │   ├── game_bridge.gd               # EventSource (5 typed channels) + keyboard → API
│   │   ├── player_controller.gd         # Applies SSE movement state
│   │   └── scene_loader.gd              # Fetches scene config, spawns elements, sun rotation
│   └── rust_extension/
│       └── three_wizards.gdextension    # GDExtension manifest
├── frontend/                            # Angular 19 standalone app
│   ├── nginx.conf                       # Reverse-proxies /api → api:8080
│   ├── Dockerfile                       # ng build → nginx
│   └── src/
│       ├── environments/
│       │   ├── environment.ts           # Dev: apiBase = 'http://localhost:8080'
│       │   └── environment.prod.ts      # Prod: apiBase = ''
│       └── app/
│           ├── http-auth.interceptor.ts # Attaches Bearer token to /api/* requests
│           ├── auth.guard.ts            # Route guard: AuthService.isAuthenticated()
│           ├── login/                   # Form-based login (no Casdoor redirect)
│           ├── home/                    # Game listing
│           ├── game-canvas/             # Godot iframe host (reads game.assetUrl)
│           ├── game-controls/           # WASD + spell buttons
│           ├── game-hud/                # HP, MP, position, session badge
│           └── services/
│               ├── auth.service.ts      # sessionStorage JWT, login/logout/getClaims
│               ├── control.service.ts   # POST /api/controls
│               └── game-state.service.ts # EventSource with reconnect; typed events
└── backend/                             # Spring Boot 3.x / Java 21 / WebFlux + R2DBC
    └── src/main/java/com/threewizards/
        ├── config/
        │   ├── JdbcDataSourceConfig.java  # @Primary HikariDataSource for Liquibase
        │   ├── SecurityConfig.java        # JWT resource server, path rules
        │   └── WebConfig.java             # CORS
        ├── controller/                    # (12 controllers — see API Reference above)
        ├── model/                         # R2DBC entities + DTOs + records
        ├── repository/                    # ReactiveCrudRepository interfaces
        └── service/
            ├── GameStateService.java      # Sinks.many().replay().limit(1) + heartbeat
            ├── GameTimeService.java       # In-game clock scheduler
            ├── PgNotificationService.java # LISTEN game_events → SSE scene$
            ├── SessionService.java        # 3-player cap + controller arbitration
            └── AdminPasswordService.java  # Dev profile: prints credentials at startup
```

---

## SSE debugging

```bash
# Watch the full multiplexed stream (all 5 event types)
curl -N http://localhost:8080/api/game/stream

# Filter to a single channel
curl -N http://localhost:8080/api/game/stream | grep -A1 "^event: time"
```

Example events:
```
event: movement
data: {"x":3.2,"y":0.9,"z":-1.4,"rotationY":1.05,"action":"MOVE_FORWARD","pressed":true}

event: time
data: {"dayNumber":1,"hour":8,"minute":12}

event: world
data: {"sessionId":"abc123","tick":441,"timestamp":1714000800000}
```

To enable DEBUG logging:
```yaml
# backend/src/main/resources/application.yml
logging:
  level:
    com.threewizards: DEBUG
```

---

## Troubleshooting — recurring gotchas

Symptoms and the underlying causes that have already bitten this project. If you
hit one of these again, start with the listed file rather than chasing the
symptom across the stack.

### Every API request returns 500 with `Missing required "keys" member`

Spring Security's reactive JWT decoder is fetching JWKs from a non-JWK endpoint.
Casdoor exposes both `/api/get-jwks` (admin-auth required, returns an error
JSON) and `/.well-known/jwks` (anonymous, real JWK Set). Always use the
well-known path:

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          jwk-set-uri: ${CASDOOR_ENDPOINT}/.well-known/jwks
```

### Iframe blocked by Firefox / Chrome refuses to embed the WASM bundle

`SecurityConfig` must call `.headers(h -> h.frameOptions(fo ->
fo.mode(SAMEORIGIN)))`. Spring Security WebFlux defaults to
`X-Frame-Options: DENY` which blocks even same-origin embedding.

### "API offline" stays on for 30 s after every page load

`Flux.interval(period)` first emits at `t = period`, and Reactor-Netty doesn't
flush response headers until the first body chunk — so the browser's
`EventSource` never fires `onopen` and the Angular `connected$` BehaviorSubject
stays `false`. The fix in `GameStreamController` is `Flux.interval(Duration.ZERO,
Duration.ofSeconds(15))` so a `:keepalive` comment ships immediately.

### Liquibase fails on `relation "games_pkey" already exists`

`<renameTable>` in PostgreSQL renames the table only — the backing PK index,
unique constraints, and sequences keep their original names. The `012-5b`
changeset renames `games_pkey` / `games_slug_key` / `games_id_seq` to
`levels_*` (with `IF EXISTS` guards) before `012-6` creates the new
umbrella `games` table. Any future `<renameTable>` migration needs a
matching cleanup changeset.

### Cave loads but is empty (no lights, fairies, or geometry)

Two distinct GD bugs both produce this:

- `add_child` from inside `_ready()` or `_process()` while the scene tree is
  still propagating `_ready` to siblings. Godot 4 emits `Parent node is busy
  setting up children, add_child() failed`. Always use
  `node.add_child.call_deferred(child)` for tree mutations from these callbacks.
  In `scene_loader.gd` both the loading-overlay attachment to the tree root and
  the per-element `_apply(config)` are deferred.
- `JavaScriptBridge.eval(...) != true` is unreliable Variant comparison. Use
  `bool(JavaScriptBridge.eval("!!window.foo"))` so the bridge marshals a real
  JS boolean. Without this the polling loop never advances and the loading
  dots animate forever.

### Iframe shows a stale Godot bundle after rebuild

Browsers aggressively cache iframe contents even when `Cache-Control:
no-store` is set, because the URL didn't change between mounts. The Angular
game-canvas component now appends `&t=${Date.now()}` to the iframe `src` so
each mount gets a unique URL and Firefox refetches.

### Movement events silently dropped at the API

`POST /api/controls/{characterId}` returns `403` when the caller is not the
holder of the controller role. The frontend must call `POST /api/sessions/connect`
on login (which auto-grants controller to the first joiner) before any
controls fire — otherwise the server logs `Not the current controller` and
no movement reaches the SSE stream. The Angular dashboard / home-page
session bootstrap is responsible for that handshake.
