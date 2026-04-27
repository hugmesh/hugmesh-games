# 02 — Runtime architecture

> Mirror of [`chapters/02-runtime-architecture.tex`](../../publishing/architecture/chapters/02-runtime-architecture.tex).

The runtime data flow has three tiers:

1. **The player's browser** — federated Angular host, the active remote module, the WASM iframe.
2. **Per-remote nginx fleet** — host nginx, four route-or-game remotes, the asset-tier `wasm-fragment`.
3. **BFF + persistence** — Spring Boot WebFlux on top of Postgres and Cassandra, behind Casdoor.

## Three-tier topology

```mermaid
flowchart TB
    subgraph T1["Tier 1 — Browser"]
        BR[Browser]
    end
    subgraph T2["Tier 2 — Per-remote nginx fleet"]
        H[host nginx<br/>frontend/nginx.conf]
        L[login remote]
        A[admin remote]
        G3[games-three-wizards]
        GD[games-dodecahedron]
        WF[wasm-fragment<br/>Godot bundles]
    end
    subgraph T3["Tier 3 — BFF + persistence"]
        BFF[Spring Boot BFF<br/>WebFlux + R2DBC + JDBC]
        PG[(PostgreSQL)]
        CA[(Cassandra)]
        CD[Casdoor<br/>internal only]
    end
    BR --> H
    H -->|"/_remotes/login/*"| L
    H -->|"/_remotes/admin/*"| A
    H -->|"/_remotes/games-three-wizards/*"| G3
    H -->|"/_remotes/games-dodecahedron/*"| GD
    H -->|"/_remotes/wasm-fragment/*"| WF
    H -->|"/api/*"| BFF
    BFF --> PG
    BFF --> CA
    BFF -.JWKS / token proxy.-> CD
```

Each box is a Docker container. Arrows are HTTP routes proxied by the host nginx. The BFF is the only container that talks to Postgres / Cassandra / Casdoor.

## Browser-side flow

```mermaid
sequenceDiagram
    participant U as User
    participant Host as Angular host (main.ts → bootstrap.ts)
    participant BFF as Spring BFF
    participant Remote as Per-game remote
    participant WF as wasm-fragment
    participant Godot as Godot WASM
    U->>Host: GET /
    Host->>BFF: GET /api/federation/manifest
    BFF-->>Host: { remotes: [...] }
    Host->>Host: initFederation(...) → bootstrap.ts → AppComponent
    U->>Host: click game card
    Host->>BFF: POST /api/games/{slug}/launch
    BFF-->>Host: { uid, expiresAt, versionDir }
    Host->>Remote: loadRemoteModule({remoteName, exposedModule})
    Remote->>WF: iframe src=/_remotes/wasm-fragment/{slug}/index.html?launch_uid=…
    WF-->>Godot: serve bundle (COOP/COEP/CORP headers)
    Godot->>BFF: GET /api/chapters/{id}/scene
    BFF-->>Godot: SceneDto
    Godot->>BFF: GET /api/game/stream  (SSE)
    BFF-->>Godot: event:movement, event:character, event:time, …
```

`SCENE_TYPE_TO_GAME_SLUG` forks the renderer; federated games mint a `launch_uid` for operational visibility; `TW_LEVEL_TRANSITION` `postMessage` is caught at the host level so it works regardless of which renderer is mounted.

### Why `globalThis` and not a service

The federation runtime can't be reached through Angular's DI without making `@angular-architects/native-federation` a static import into a route-handler chunk, which puts it on karma's module graph and breaks tests that don't load the federation runtime at all. Stashing the function on `globalThis` after bootstrap keeps the package out of karma's static graph and out of every route bundle that doesn't need it. See [decision 001](../decisions/001-native-federation.md).

## The host shell

The host nginx serves the host bundle plus a set of `location /_remotes/<slug>/` blocks that proxy each remote's `remoteEntry.json` and chunks. For `wasm-fragment` the host strips upstream COOP / COEP duplicates with `proxy_hide_header` and re-emits the canonical set so the WASM iframe loads with isolated headers.

## The remotes

| Slug | Kind | What it exposes / does |
|---|---|---|
| `login` | `remote-shell` | `./LoginComponent` — mounted via `loadComponent` on `/login`. Inline `POST /api/auth/token` + `sessionStorage.tw_auth` write, mirrors the host `AuthService.StoredAuth` shape. |
| `admin` | `remote-shell` | `./AdminShell` + `./UploadShell` — both expose under `federation_modules.slug='admin'` so `/admin` and `/admin/games` share a build. |
| `games-three-wizards` | `remote-game` | `./GameRoot` — Web Component (`createCustomElement`) with `@Input levelSlug + levelId + launchUid`. Renders cave and field. |
| `games-dodecahedron` | `remote-game` | `./GameRoot` — same pattern, single chrome-less scene. |
| `wasm-fragment` | `fragment` | Asset-tier nginx; bakes `backend/src/main/resources/assets/levels/` and serves at `/_remotes/wasm-fragment/<slug>/index.html`. |

## The BFF

[`backend/`](../../../backend/) runs as a single Spring Boot 3.4 application with both WebFlux (R2DBC, runtime path) and JDBC (Liquibase, blocking) wired in. Spring auto-config skips `DataSourceAutoConfiguration` when an R2DBC `ConnectionFactory` is present, so `JdbcDataSourceConfig` declares an explicit `@Primary HikariDataSource` of pool size 3 just for Liquibase to acquire on boot. See [decision 002](../decisions/002-r2dbc-jdbc-coexistence.md).

## Authentication and access

Casdoor is reachable only from inside the compose network. The host BFF proxies the login flow at `/api/auth/*` and verifies JWTs against the anonymous `/.well-known/jwks` endpoint (not `/api/get-jwks`, which requires admin auth and would 401 on every request). Per-game visibility is enforced by the `game_access` table — see [04 — Data model](04-data-model.md).

## Server-Sent Events — eight channels on one stream

[`GameStreamController`](../../../backend/src/main/java/games/snapdragon/bff/controller/GameStreamController.java) merges **eight** SSE channels onto a single `Flux<ServerSentEvent>`:

- Seven `Sinks.Many` from `GameStateService`: `movement`, `world`, `character`, `scene`, `input`, `audio`, `wasm-console`
- One from `GameTimeService`: `time`
- Plus a 15-second `Flux.interval` keepalive that fires immediately on subscribe so the response headers flush at connect time and the browser `EventSource.onopen` fires in milliseconds.

The latter three channels (`input`, `audio`, `wasm-console`) are part of the generalised input surface introduced for gamepad / mouse / keyboard / touch / audio support — see [04 — Data model](04-data-model.md#generalised-input-event-surface). Subscribers opt into individual channels via the EventSource event-listener model (`source.addEventListener('input', cb)`); merging onto the single connection keeps the host page from needing N EventSources.

### ⚠ The `autoCancel` trap

`Sinks.many().multicast().onBackpressureBuffer(N)` defaults to `autoCancel=true`. When every subscriber disconnects (iframe remount, EventSource reconnect, network blip), the sink terminates **permanently** and all subsequent `tryEmitNext` calls silently drop.

**Every sink in this project passes `autoCancel=false` explicitly.** If a freshly-deployed sink "works once and then silently dies", this is the cause. See [decision 003](../decisions/003-autocancel-false.md).

---

Previous: [01 — Overview](01-overview.md) · Next: [03 — Module Federation](03-federation.md).
