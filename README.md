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

Up to 3 players may be connected simultaneously; exactly one holds the **controller** role and can send input. All game state originates in the Spring Boot API and propagates to every connected client over a single SSE stream with five typed event channels.

```
  Browser / Player (up to 3)
  ══════════════════════════════════════════════════════════════════════════════
  Angular :4200 (nginx static server + /api reverse proxy in Docker)
  ┌──────────────────────────────────────────────────────────────────────────┐
  │                                                                          │
  │  LoginComponent ──► AuthService ──► sessionStorage JWT                   │
  │                     POST /api/auth/token (ROPC proxy)                    │
  │                                                                          │
  │  HTTP Interceptor attaches Bearer <jwt> to every /api/* request          │
  │  AuthGuard on every protected route                                      │
  │                                                                          │
  │  HomeComponent (/)                                                       │
  │  └── GameCanvasComponent                                                 │
  │       ngOnInit → SessionService.connect(1) → auto-claims controller role │
  │       ┌─ iframe src="/api/assets/levels/{slug}/index.html?level_id=N&t=…"│
  │       │                                                                  │
  │       │   Godot WASM bundle (per-level; cave / field / …)                │
  │       │   ┌──────────────────────────────────────────────────────────┐  │
  │       │   │  index.html <script> prefetch JSON ────►                  │  │
  │       │   │    fetch /api/levels/N/scene → window._twSceneConfig      │  │
  │       │   │                                                           │  │
  │       │   │  game_bridge.gd                                           │  │
  │       │   │    EventSource /api/game/stream ──► window._twMovement    │  │
  │       │   │      Queue (not latest-wins, FIFO, capped 32)             │  │
  │       │   │    _unhandled_input(KEY_1..5) ──► direct _cast_spell(N)   │  │
  │       │   │      + parallel POST /api/controls/1 (MP / XP server-side)│  │
  │       │   │                                                           │  │
  │       │   │  scene_loader.gd                                          │  │
  │       │   │    _apply.call_deferred(config) spawns lights/fairies/geo │  │
  │       │   │    _on_time_updated() rotates DirectionalLight3D (sun)   │  │
  │       │   │                                                           │  │
  │       │   │  player_controller.gd   (CharacterBody3D)                 │  │
  │       │   │    _sse_dir from MOVE_* events → _physics_process velocity│  │
  │       │   │    HandAnchor Node3D → equipped weapon mesh               │  │
  │       │   │    _cast_spell(N) → glowing projectile from hand anchor   │  │
  │       │   │    _use_first_item() on E → POST use-first-item           │  │
  │       │   └──────────────────────────────────────────────────────────┘  │
  │       │                                                                  │
  │       └── (levelTransition) event ← window:message TW_LEVEL_TRANSITION   │
  │             GameLibraryService.getLevel(id) → swap iframe src            │
  │                                                                          │
  │  GameControls (Angular)                                                  │
  │    WASD / Space / 1-5 / E keyboard + on-screen buttons                   │
  │    Digit keys 1-5 also fire a CSS spell overlay (always-visible cue)     │
  │    HUD spell hotbar hydrates from /api/skills/character/1/slots          │
  │    HUD item quick-slot = first row of /api/items/character/1/inventory   │
  └──────────────────────────────┬───────────────────────────────────────────┘
                                 │ /api/* (nginx → api:8080)
  ══════════════════════════════ │ ═══════════════════════════════════════════
  Spring Boot API :8080          ▼    (WebFlux, R2DBC + JDBC-for-Liquibase)
  ┌──────────────────────────────────────────────────────────────────────────┐
  │                                                                          │
  │  SecurityConfig (WebFlux)                                                │
  │  • /api/auth/**  → permitAll                                             │
  │  • /api/admin/** → ROLE_ADMIN                                            │
  │  • /api/characters|skills|items|quests|sessions/** → authenticated       │
  │  • X-Frame-Options: SAMEORIGIN so the iframe can embed WASM              │
  │  • JWT RS256 verified via ${CASDOOR}/.well-known/jwks (public)           │
  │                                                                          │
  │  CasdoorAuthController                                                   │
  │  POST /api/auth/token ──► ROPC proxy to Casdoor (client_secret server)   │
  │                                                                          │
  │  SessionController                 ControlController                     │
  │  POST /api/sessions/connect        POST /api/controls/{charId}           │
  │  POST /api/sessions/control          checks X-Casdoor-Id matches the     │
  │  DELETE /api/sessions/me             holder of the controller role      │
  │  GET   /api/sessions/history         → 403 if not; else applyControl()   │
  │                                                                          │
  │  GameStreamController ── GET /api/game/stream (text/event-stream)        │
  │    Flux.merge(movement$, world$, character$, time$, scene$) + keepalive  │
  │    Flux.interval(Duration.ZERO, 15s) keepalive → first flush at t=0      │
  │                                                                          │
  │                                                                          │
  │  GameStateService                                                        │
  │  ┌──────────────────────────────────────────────────────────────────┐    │
  │  │ Sinks.many().multicast().onBackpressureBuffer(N, autoCancel=false)│   │
  │  │  • movementSink  (PlayerMovement)                                │    │
  │  │  • worldSink     (WorldState tick + everyone's positions)        │    │
  │  │  • charStatSink  (CharacterDto HP/MP/XP/level)                   │    │
  │  │  • sceneSink     (SceneEvent level_id + payload)                 │    │
  │  │  autoCancel=false is critical — default true terminates the sink │    │
  │  │  when all subscribers disconnect, silently killing the stream.   │    │
  │  └──────────────────────────────────────────────────────────────────┘    │
  │                                                                          │
  │  applyControl(ControlEvent, characterId):                                │
  │    MOVE_*  → mutate PlayerState position                                 │
  │    JUMP    → mutate PlayerState y                                        │
  │    other   → no position change; emit still fires                        │
  │    gate: posChanged || actionChanged || pressedChanged                   │
  │           (pressedChanged catches keyup of held directions)              │
  │                                                                          │
  │  GameTimeService                 CharacterController                     │
  │  scheduler: 10s real = 1 min     PUT  /characters/{id}/quests/{qid}/     │
  │  persists to game_time_state     complete → grants reward_skill_id       │
  │  emits time$ each tick           POST /characters/{id}/use-first-item    │
  │                                                                          │
  │  ┌─────────────────────────────────────────────────────────────────┐   │
  │  │  PostgreSQL (R2DBC runtime + HikariDataSource for Liquibase)     │   │
  │  │                                                                   │   │
  │  │  games → levels → scene_elements        skills ← character_skills │   │
  │  │          (per-level WASM asset_url)     quests ← character_quests │   │
  │  │  level_prerequisites ── quests          items  ← character_items  │   │
  │  │  level_assets (user textures/plugins)    ← character_equipment    │   │
  │  │  player_characters    player_sessions   character_skill_slots     │   │
  │  │  player_positions  ── NOTIFY trigger    game_time_state           │   │
  │  └──────────────────────────┬──────────────────────────────────────┘   │
  │                             │                                            │
  │  PgNotificationService ◄────┘ LISTEN game_events                        │
  │    → feeds sceneSink                                                     │
  │                                                                          │
  │  AssetController                                                         │
  │    GET /api/assets/levels/{slug}/{file} → ClassPathResource             │
  │         (primary WASM bundle path — per-level)                           │
  │    GET /api/assets/games/{slug}/{file}  → legacy fallback                │
  │                                                                          │
  └──────────────────────────────────────────────────────────────────────────┘
                    │ internal Docker network only
  ──────────────────┼───────────────────────────────────────────────────────────
  Casdoor :8000     ▼   (no host port binding — unreachable from the browser)
  ┌──────────────────────────────────────────────────────────────────────────┐
  │  entrypoint.sh renders init_data.json from env vars or random secrets   │
  │  Prints client-id / client-secret / passwords on first-run              │
  │                                                                          │
  │  POST /api/login/oauth/access_token   ← ROPC grant (proxied by API)     │
  │  GET  /.well-known/jwks               ← RS256 public keys (anonymous)   │
  │       ⚠ /api/get-jwks requires admin auth — DO NOT use for verify       │
  └──────────────────────────────────────────────────────────────────────────┘
```

### Key architectural decisions

**Per-level WASM bundles.**
Each level compiles to its own Godot export under `/api/assets/levels/{slug}/`. The iframe URL carries `?level_id=N`; the patched `index.html` prefetches `/api/levels/N/scene` so scene config is ready before the WASM finishes compiling. Adding a new level = new `.tscn` + new preset in `export_presets.cfg` + new row in `levels` — see [docs/GODOT_DEVELOPER_GUIDE.md](docs/GODOT_DEVELOPER_GUIDE.md).

**All controls authenticate through a session.**
Every `/api/controls/{charId}` POST carries `X-Casdoor-Id`; the server rejects it with 403 unless that casdoor user is the holder of the controller role. `GameCanvasComponent.ngOnInit` auto-calls `/api/sessions/connect` (and claims the role if no one else has it), so solo play "just works". Up to 3 players may be connected simultaneously; only one holds the role at a time.

**Spells fire both client-side and server-side.**
The key handler in `game_bridge.gd` calls `player._cast_spell(N)` directly in-WASM so the visual is instant and doesn't depend on the SSE round-trip. The same keypress also POSTs `/api/controls/1` so the server can eventually apply MP cost / XP / cooldown. Angular's hotbar handler additionally fires a CSS overlay as a guaranteed-visible cue — useful for debugging iframe focus issues.

**Five typed SSE channels on one stream.**
`GameStreamController` merges four `Sinks.Many` from `GameStateService` + one from `GameTimeService` + a keepalive `Flux.interval(Duration.ZERO, 15s)` into a single `Flux<ServerSentEvent>`. The immediate first-tick keepalive forces response headers to flush at connect time, so browser `EventSource.onopen` fires in milliseconds instead of waiting for the first real event. Each payload carries an `event:` field (`movement`, `world`, `character`, `time`, `scene`) so clients register per-type listeners.

**SSE sinks must not autoCancel.**
`Sinks.many().multicast().onBackpressureBuffer(N)` defaults to `autoCancel=true` — when every subscriber disconnects (iframe remount, EventSource reconnect, network blip), the sink terminates permanently and all subsequent `tryEmitNext` calls silently drop. Every sink in this project passes `autoCancel=false` explicitly. Producing a "first keypress works, then everything dies" symptom until this was fixed.

**Movement queue, not latest-wins.**
`window._twMovementQueue` is an array that the JS SSE listener pushes to per-event. Previously the listener stored only the latest payload, so rapid keydown+keyup pairs (exactly what spell keys produce) collapsed into just the keyup and the `pressed=true` event was lost between Godot's 16 ms polls. `_drain_movement_queue` now splices the array atomically each frame and emits `state_updated` in FIFO order.

**Casdoor kept internal; JWKS at `/.well-known/jwks`.**
`docker-compose.yml` exposes no host port for Casdoor. Spring Security's JWT decoder is wired to the anonymous `/.well-known/jwks` — **not** `/api/get-jwks`, which requires admin auth and would return an error payload that throws `"Missing required keys member"` on every Bearer-tagged request, including permitAll ones.

**R2DBC + JDBC coexistence.**
Runtime data access is non-blocking R2DBC. Liquibase needs a blocking JDBC `DataSource`. Spring Boot 3 skips `DataSourceAutoConfiguration` when an R2DBC `ConnectionFactory` bean is present, so `JdbcDataSourceConfig` creates an explicit `@Primary HikariDataSource` for Liquibase only (pool size 3).

---

## Data model

### SSE event types — `GET /api/game/stream`

| Event name | Payload fields | Emitted when | Delivery |
|---|---|---|---|
| `movement` | `x`, `y`, `z`, `rotationY`, `action`, `pressed`, `tick` | Every control event (press AND release, see `pressedChanged` gate) | Queued FIFO in-bridge |
| `world` | `sessionId`, `tick`, `timestamp`, `characters[]` | When any character's state changes | Latest-wins snapshot |
| `character` | `health`, `mana`, `level`, `xp`, `xpToNext` | Stat patch or item use | Latest-wins snapshot |
| `time` | `dayNumber`, `hour`, `minute` | Every game-minute (~10 s real) | Latest-wins snapshot |
| `scene` | `sceneId`, `sceneName`, `sceneType`, `eventType` | Scene transition / scene_element NOTIFY | Latest-wins snapshot |
| `:keepalive` (SSE comment) | — | `t=0` on connect, then every 15 s | Flushes response headers so `onopen` fires immediately |

### HUD layout

```
 ┌──────────────────────────────────────────────── canvas-section ───────┐
 │  ╔═══════════════════════════════════════════════════════════════════╗ │
 │  ║                                                                   ║ │
 │  ║                        Godot iframe                               ║ │
 │  ║                  (per-level WASM bundle)                          ║ │
 │  ║                                                                   ║ │
 │  ╚═══════════════════════════════════════════════════════════════════╝ │
 │  ┌──── hud-layer (pointer-events: none) ─────────────────────────────┐ │
 │  │                                                                    │ │
 │  │  app-game-hud   (HP / MP / position / session badge)              │ │
 │  │  app-game-controls                                                 │ │
 │  │                                                                    │ │
 │  │    [W]                              ┌─────── hotbar ─────────┐     │ │
 │  │  [A][S][D]                          │ 🔥 ❄ ⚡ 🪨 ✨  │  🧪 ×3 │     │ │
 │  │    [SPACE]                          │  1  2  3  4  5   │   E  │     │ │
 │  │  (bottom-left)                      └────── bottom-center ────┘    │ │
 │  │                                     (mana cost shown per slot;    │ │
 │  │                                      item slot = first inventory  │ │
 │  │                                      row, consumed on E press)    │ │
 │  └────────────────────────────────────────────────────────────────────┘ │
 └────────────────────────────────────────────────────────────────────────┘
```

### Input flow — keypress to visible effect

```
  ┌────────── parent window (Angular) ──────────┐     ┌───── iframe (Godot WASM) ─────┐
  │                                             │     │                               │
  │  keydown '1'                                │     │  keydown '1' (KEY_1)           │
  │   │                                         │     │   │                            │
  │   ▼                                         │     │   ▼                            │
  │  game-controls.onKeyDown                    │     │  game_bridge._unhandled_input  │
  │   ├── _fireSpellOverlay(1)                  │     │   ├── player._cast_spell(1)   │
  │   │   (CSS orb flies up; always visible)    │     │   │   (projectile from hand)  │
  │   │                                         │     │   │                            │
  │   ├── ControlService.sendControl(           │     │   └── send_control(           │
  │   │     'CAST_SPELL_1', true)               │     │         'CAST_SPELL_1', true) │
  │   │   POST /api/controls/1                  │     │        POST /api/controls/1    │
  │   │   + X-Casdoor-Id                        │     │        (no casdoor header)    │
  │   │                                         │     │                               │
  │   └── 50ms later: sendControl(…, false)     │     │   keyup: send_control(…,false)│
  └─────────────────────────────────────────────┘     └───────────────────────────────┘
                      ║                                                ║
                      ╚══════════════════════╦═════════════════════════╝
                                             ▼
                  ┌─────────────── Spring Boot API ────────────────┐
                  │  ControlController.receiveControl              │
                  │  → applyControl: gate on posChanged ||         │
                  │       actionChanged || pressedChanged          │
                  │  → movementSink.tryEmitNext                    │
                  └──────────────────────┬─────────────────────────┘
                                         ▼
                    GET /api/game/stream Flux<ServerSentEvent>
                                         ▼
                  ┌─────────── iframe JS SSE listener ─────────────┐
                  │  push into window._twMovementQueue (FIFO)      │
                  └──────────────────────┬─────────────────────────┘
                                         ▼
                  game_bridge._drain_movement_queue (each _process frame)
                                         ▼
                  player_controller._on_state_updated (per-event)
                                         ▼
                  match action → _cast_spell / _use_first_item / _sse_dir
```

Spells cast via two redundant paths — the direct in-WASM call fires the visual instantly; the round-trip through the API exists so the server can eventually subtract MP, grant XP, resolve collisions with enemies, etc.

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

#### `PUT /api/characters/{id}/quests/{questId}/complete`

Marks the quest complete and, if it has a `reward_skill_id`, grants the character that skill (idempotent).

```
──request──► charQuests.findByCharacterIdAndQuestId(id, qid)
                   │
         ┌─────────┴──────────┐
         │ found              │ empty
         ▼                    ▼
  status='completed'     switchIfEmpty(404)
  completedAt=NOW()            ──✗
  charQuests.save()
         │
  questRepo.findById(qid)
         │
         ▼ reward_skill_id present?
         │
  charSkills.findByCharacterId
    .filter(skillId == reward)
    .hasElements()
         │
  ┌──────┴──────┐
  │ already has │ new
  ▼             ▼
  no-op       charSkills.save({charId, rewardSkillId})
  │             │
  ●─────────────●─| CharacterQuest (updated)
```

#### `POST /api/characters/{id}/use-first-item`

Consumes the lowest-id inventory item (typically a potion), applies its JSON effect, decrements quantity, and broadcasts the updated character over SSE so the HUD HP/MP bars update instantly.

```
──request──► charItems.findByCharacterId(id)
              .filter(qty > 0).sort(by id).next()
                   │
         ┌─────────┴──────────┐
         │ found              │ empty
         ▼                    ▼
  itemRepo.findById    ──✗ 404 "no usable item"
         │
  parse effect_json:
    "heal":N → currentHp += N (cap maxHp)
    "mana":N → currentMp += N (cap maxMp)
         │
  charItems.save(qty-1)
         │
  characters.save(updated)
         │
  gameStateService.notifyCharacterStatChange
         │
  ──────●─|  CharacterDto (updated)
```

#### `GET /api/characters/{id}/dashboard`

Passive snapshot for the player dashboard — character stats, time, games with levels, quests, session playtime — without loading the game canvas.

```
──request──► zip(
                characterRepo.findById(id).map(toDto),
                progression.availableGamesWithLevels(id).collectList(),
                charQuests.findByCharacterId(id)
                  .flatMap(cq → questRepo.findById → map toDto).collectList(),
                sessions.findHistoryByCasdoorId(char.casdoorId).collectList()
              )
              ──map──► DashboardDto{
                          character, gameTime, games, quests,
                          totalPlayMinutes, totalSessions }
 ──────────────●─|
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
  is_controller, connected_at, last_ping,
  disconnected_at      (null = active; set = session history row)

player_positions ─────────────────────────────────────────  ◄─ NOTIFY trigger
  id, character_id(→player_characters),
  scene_id(→levels), pos_x, pos_y, pos_z,
  rotation_y, updated_at

game_time_state ──────────────────────────────────────────
  id, day_number, hour, minute,
  real_started_at, last_persisted_at

skills ───────  character_skills ───  character_skill_slots
  id, name      character_id          character_id, slot_number
  skill_type    skill_id              skill_id
  mana_cost     learned_at
  effect_json
  icon_url

items ───────  character_items ─────  character_equipment
  id, name      character_id          character_id, slot
  item_type     item_id, quantity      item_id
  effect_json
  icon_url

quests ──────  quest_objectives ────  character_quests
  id, name      quest_id              character_id, quest_id
  reward_xp     description           status, started_at
  reward_gold   required_count        completed_at
  reward_skill_id(→skills)  ← granted on complete
  level_id(→levels)
```

### Spell catalog (migration 014)

Five starter spells auto-slotted for character 1 at slots 1–5; five advanced spells granted by completing the five quests.

| Slot | Starter spell | Element | MP | Damage | Quest reward → Advanced |
|---|---|---|---|---|---|
| 1 | Fireball | fire | 10 | 25 | Into the Cave → **Inferno Lance** (55 dmg, 22 MP) |
| 2 | Ice Shard | ice | 8 | 20 | The Cottage Secret → **Glacial Storm** (48 dmg, slow, 20 MP) |
| 3 | Thunder Bolt | lightning | 12 | 28 | Voice of the Storm → **Chain Lightning** (62 dmg, chain-3, 25 MP) |
| 4 | Stone Spike | earth | 11 | 24 | Heart of the Mountain → **Earthquake** (58 dmg, 28 MP) |
| 5 | Holy Light | light | 14 | 18 + heal 20 | Dawn at the Cottage → **Solar Flare** (70 dmg + heal 40, 30 MP) |

Projectile size scales with tier: starter radius ≈ 0.6, advanced ≈ 1.0.

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
            ├── GameStateService.java      # 4 typed Sinks (autoCancel=false) + keyup gate
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
