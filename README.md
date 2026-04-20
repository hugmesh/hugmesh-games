# Three Wizards In A Trenchcoat

A fantasy dungeon-crawler built on **Rust + Godot 4** (WebGL/WASM), served through an **Angular** frontend, with a **Spring Boot** backend that delivers game state and controls via streaming SSE.

> Branch policy: please work on your own branches and open PRs rather than committing directly to `main`.

---

## Quick links

| Resource | Link |
|---|---|
| Issue Tracker | [ThreeWizardsInATrenchcoat/issues](https://github.com/Nin-Crowley/ThreeWizardsInATrenchcoat/issues) |
| Pull Requests | [ThreeWizardsInATrenchcoat/pulls](https://github.com/Nin-Crowley/ThreeWizardsInATrenchcoat/pulls) |

---

## Architecture

All player input — whether from the Angular HUD buttons, Angular keyboard shortcuts, or Godot keyboard events when the game canvas has focus — flows through the API before affecting game state. The SSE stream is the single source of truth for the Godot scene.

```
  Input sources                Spring Boot :8080            Godot WebGL (iframe)
  ─────────────────────────    ─────────────────────────    ────────────────────
  Angular GameControls         ControlController            game_bridge.gd
  [W key / button]──────────►  POST /api/controls           (own EventSource)
                                        │                          │
  Godot _unhandled_input                │                    addEventListener
  [W key in canvas]─────────►  applyControl()               ('game-state', ...)
                                        │                          │
                               GameStateService                    │
                               AtomicReference<PlayerState>        │
                               Sinks.Many<GameState>               │
                               (replay().limit(1))                 │
                                        │                          │
  Angular GameStateService ◄────────── SSE /api/game/stream ──────►│
  (EventSource direct                   │                   state_updated signal
   to :8080)                            │                          │
       │                        Heartbeat (1 s)            player_controller.gd
       │                                                   CharacterBody3D
       ▼                                                   camera-relative move
  GameHUD
  HP/MP bars · Position
```

### Key architectural decisions

**All controls go through the API first.**  
Neither the Angular app nor the Godot scene mutates position directly. Every keypress POSTs to `/api/controls`; the resulting `GameState` comes back over SSE and drives both the Angular HUD and Godot movement.

**Godot has its own EventSource.**  
Rather than a fragile `postMessage` chain (Angular → GameCanvas → iframe → Godot listener), `game_bridge.gd` opens its own `EventSource` directly to the API using `JavaScriptBridge.eval`. In dev it connects to `http://localhost:8080` to bypass the Vite proxy; in production it uses the same origin.

**Reactor sink is `replay().limit(1)`.**  
`Sinks.many().multicast().onBackpressureBuffer()` terminates silently in Reactor 3.7 when all subscribers disconnect; new subscriptions then immediately complete. `replay().limit(1)` is a hot sink that never terminates and replays the latest state to new subscribers on connect.

**Angular connects directly to `:8080` in dev.**  
The Vite dev server proxy injects `Connection: close` on all responses, which kills SSE streams. `environment.ts` sets `apiBase: 'http://localhost:8080'` so `GameStateService` bypasses the proxy entirely. `proxy.conf.js` still has a `configure` hook that forces `keep-alive` on the stream path as a belt-and-suspenders fix.

---

## Data model

### `GameState` — SSE payload (`event: game-state`)

| Field | Type | Description |
|---|---|---|
| `sessionId` | `String` | Server session identifier |
| `player` | `PlayerState` | Full player snapshot |
| `action` | `String \| null` | Last control action applied (`"MOVE_FORWARD"`, `"JUMP"`, …) |
| `pressed` | `boolean` | Whether the action key is held |
| `timestamp` | `long` | Server epoch-ms |
| `tick` | `long` | Monotonically increasing counter |

### `PlayerState` — nested inside `GameState.player`

| Field | Type | Description |
|---|---|---|
| `x` / `y` / `z` | `double` | World position |
| `rotationY` | `double` | Yaw in radians |
| `health` | `int` | HP (0–100) |
| `mana` | `int` | MP (0–100) |

### `ControlEvent` — `POST /api/controls` body

| Field | Type | Description |
|---|---|---|
| `action` | `String` | `MOVE_FORWARD`, `MOVE_BACKWARD`, `MOVE_LEFT`, `MOVE_RIGHT`, `JUMP`, `INTERACT`, `CAST_SPELL_1/2/3` |
| `pressed` | `boolean` | `true` = key down, `false` = release |
| `timestamp` | `long` | Client epoch-ms |

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

### 7. Emscripten (optional)

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

Order: `cargo build --release` → `godot --export-release` → `mvn package` → `ng build`

### Individual steps

```bash
make build-rust      # Rust GDExtension → godot-project/rust_extension/bin/
make build-godot     # Godot WebGL export → frontend/src/assets/game/
make build-backend   # Spring Boot JAR
make build-frontend  # Angular dist
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

> **Important:** Do not use the VS Code Spring Boot Dashboard or the IntelliJ Spring Boot run config to start the backend. These tools auto-restart using stale compiled classes in `target/classes`, which can bring up an old version of the service with bugs that were already fixed. Always use `make dev-backend`, which runs `./mvnw clean spring-boot:run` to compile from source.

> The Godot game iframe only appears after `make build-godot` has been run at least once. Until then the canvas area will be blank.

> `GameStateService` in Angular connects directly to `http://localhost:8080` in dev (see `frontend/src/environments/environment.ts`), bypassing the Vite proxy. This is intentional — the proxy injects `Connection: close` which terminates SSE streams.

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

Keyboard input works both from the Angular page (when the browser window has focus) and from inside the Godot canvas (when the iframe has focus). Both paths POST to `/api/controls`.

On-screen buttons in the Angular HUD mirror all movement actions and support touch input.

---

## Project structure

```
ThreeWizardsInATrenchcoat/
├── Makefile                          # Master build chain
├── game/
│   └── rust/                         # Rust GDExtension crate
│       ├── Cargo.toml
│       └── src/
│           ├── lib.rs                # Extension entry point
│           ├── cave_scene.rs         # CaveScene node
│           ├── player.rs             # PlayerController stub
│           └── game_state.rs         # GameStateBridge stub
├── godot-project/                    # Godot 4.6 project
│   ├── project.godot
│   ├── export_presets.cfg            # Web export → frontend/src/assets/game/
│   ├── scenes/
│   │   └── cave.tscn                 # Cave with CSG collision + player + camera rig
│   ├── scripts/
│   │   ├── camera_controller.gd      # Orbit camera: scroll zoom, drag orbit, FP/3P switch
│   │   ├── game_bridge.gd            # Own EventSource via JavaScriptBridge; keyboard → API
│   │   └── player_controller.gd      # Applies SSE state; camera-relative WASD movement
│   └── rust_extension/
│       └── three_wizards.gdextension # GDExtension manifest
├── frontend/                         # Angular 19 application
│   ├── proxy.conf.js                 # /api → :8080 with SSE keep-alive fix
│   ├── proxy.conf.json               # Legacy JSON proxy (kept for reference)
│   └── src/
│       ├── environments/
│       │   ├── environment.ts        # Dev: apiBase = 'http://localhost:8080'
│       │   └── environment.prod.ts   # Prod: apiBase = '' (same origin)
│       └── app/
│           ├── game-canvas/          # Godot iframe host
│           ├── game-controls/        # WASD/arrow buttons + keyboard shortcuts
│           ├── game-hud/             # HP bar, MP bar, position, API connection badge
│           ├── services/
│           │   ├── control.service.ts    # POST /api/controls
│           │   └── game-state.service.ts # EventSource with reconnect; emits to BehaviorSubjects
│           └── models/game-state.model.ts
└── backend/                          # Spring Boot 3 / Java 21 / WebFlux
    └── src/main/java/com/threewizards/
        ├── config/WebConfig.java          # CORS filter
        ├── controller/
        │   ├── GameStreamController.java  # GET /api/game/stream (SSE)
        │   ├── ControlController.java     # POST /api/controls
        │   └── AssetController.java       # GET /api/assets/{file}
        ├── model/
        │   ├── GameState.java             # Java record
        │   ├── PlayerState.java           # Java record
        │   └── ControlEvent.java          # Java record
        └── service/GameStateService.java  # Sinks.many().replay().limit(1) + 1 s heartbeat
```

---

## SSE debugging

The backend logs all control events and state changes at INFO level, and every SSE emission at DEBUG level.

To watch the SSE stream directly:
```bash
curl -N http://localhost:8080/api/game/stream
```

Each event looks like:
```
event: game-state
data: {"sessionId":"...","player":{"x":0.0,"y":0.9,"z":0.0,...},"action":"MOVE_FORWARD","pressed":true,"timestamp":...,"tick":42}
```

To enable DEBUG logging for the service, add this to `backend/src/main/resources/application.yml`:
```yaml
logging:
  level:
    com.threewizards: DEBUG
```
